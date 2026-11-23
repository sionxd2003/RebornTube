package com.example.reborntube

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Un aparato DLNA (por ejemplo un Fire TV con un receptor) visto como un Player de Media3, para que
 * los botones, la barra de progreso y la cola funcionen igual que con el reproductor del móvil o con Google Cast.
 * La TV reproduce la dirección que le pasa el móvil; aquí solo se manda play/pausa/saltar y se lee el estado.
 */
@UnstableApi
class DlnaPlayer(val device: RemoteDevice) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    private var item: MediaItem? = null
    private var live = false
    private var wantPlay = true
    private var playback = Player.STATE_IDLE
    private var positionMs = 0L
    private var positionAt = 0L
    private var durationMs = C.TIME_UNSET
    private var error: PlaybackException? = null

    private var preparedAt = 0L
    private var everPlayed = false
    private var pendingSeekMs = 0L
    private var lastCommandAt = 0L
    private var url = ""
    private var mime = ""

    private val done: ListenableFuture<*> get() = Futures.immediateVoidFuture()

    override fun getState(): SimpleBasePlayer.State {
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_RELEASE
            )
            .build()

        val data = item?.let {
            SimpleBasePlayer.MediaItemData.Builder("dlna")
                .setMediaItem(it)
                .setDurationUs(if (!live && durationMs > 0) durationMs * 1000 else C.TIME_UNSET)
                .setIsSeekable(!live)
                .setIsDynamic(live)
                .build()
        }

        val anchor = positionMs
        val at = positionAt
        val running = wantPlay && playback == Player.STATE_READY
        return SimpleBasePlayer.State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(wantPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playback)
            .setIsLoading(playback == Player.STATE_BUFFERING)
            .setPlayerError(error)
            .setPlaylist(if (data != null) listOf(data) else emptyList())
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(SimpleBasePlayer.PositionSupplier {
                if (running) anchor + (SystemClock.elapsedRealtime() - at) else anchor
            })
            .build()
    }

    // ---------- Órdenes que llegan de la interfaz ----------

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        item = mediaItems.firstOrNull()
        val extras = item?.mediaMetadata?.extras
        live = extras?.getBoolean("live") == true
        url = item?.localConfiguration?.uri?.toString() ?: ""
        mime = item?.localConfiguration?.mimeType ?: "video/mp4"
        durationMs = extras?.getLong("durationMs", C.TIME_UNSET) ?: C.TIME_UNSET
        positionMs = if (startPositionMs == C.TIME_UNSET || live) 0L else startPositionMs
        positionAt = SystemClock.elapsedRealtime()
        pendingSeekMs = if (positionMs > 3000) positionMs else 0L
        error = null
        everPlayed = false
        playback = Player.STATE_IDLE
        return done
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        item = null
        return done
    }

    override fun handlePrepare(): ListenableFuture<*> {
        val current = item ?: return done
        playback = Player.STATE_BUFFERING
        preparedAt = SystemClock.elapsedRealtime()
        lastCommandAt = preparedAt
        val title = current.mediaMetadata.title?.toString() ?: ""
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    Upnp.setUri(device, url, mime, title, live)
                    Upnp.play(device)
                    if (!wantPlay) Upnp.pause(device) // lo pausaron mientras se preparaba
                }
            }
            ok.exceptionOrNull()?.let { fail(it) }
            invalidateState()
        }
        startPolling()
        return done
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wantPlay = playWhenReady
        lastCommandAt = SystemClock.elapsedRealtime()
        // Ya parado en la TV (terminó): "play" vuelve a mandar el vídeo
        if (playWhenReady && playback == Player.STATE_ENDED) {
            positionMs = 0L
            handlePrepare()
            return done
        }
        // Mientras se está preparando, handlePrepare ya manda el play
        if (!everPlayed) return done
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { if (playWhenReady) Upnp.play(device) else Upnp.pause(device) }
            }
        }
        return done
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = if (positionMs == C.TIME_UNSET) 0L else positionMs
        this.positionMs = target
        positionAt = SystemClock.elapsedRealtime()
        lastCommandAt = positionAt
        if (!live) {
            scope.launch { runCatching { withContext(Dispatchers.IO) { Upnp.seek(device, target) } } }
        }
        return done
    }

    override fun handleStop(): ListenableFuture<*> {
        pollJob?.cancel()
        playback = Player.STATE_IDLE
        scope.launch { runCatching { withContext(Dispatchers.IO) { Upnp.stop(device) } } }
        return done
    }

    override fun handleRelease(): ListenableFuture<*> {
        pollJob?.cancel()
        runCatching { Thread { runCatching { Upnp.stop(device) } }.start() }
        scope.cancel()
        return done
    }

    // ---------- Estado de la TV ----------

    private fun fail(cause: Throwable) {
        playback = Player.STATE_IDLE
        error = PlaybackException(cause.message, cause, PlaybackException.ERROR_CODE_REMOTE_ERROR)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(1000)
                val state = runCatching { withContext(Dispatchers.IO) { Upnp.transportState(device) } }.getOrNull()
                val pos = runCatching { withContext(Dispatchers.IO) { Upnp.position(device) } }.getOrNull()
                val now = SystemClock.elapsedRealtime()
                val settled = now - lastCommandAt > 2500

                when (state) {
                    "PLAYING" -> {
                        everPlayed = true
                        playback = Player.STATE_READY
                        if (settled && !wantPlay) wantPlay = true // alguien le dio a play con el mando de la TV
                        if (pendingSeekMs > 0) {
                            val target = pendingSeekMs
                            pendingSeekMs = 0
                            runCatching { withContext(Dispatchers.IO) { Upnp.seek(device, target) } }
                        }
                    }
                    "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> {
                        everPlayed = true
                        playback = Player.STATE_READY
                        if (settled && wantPlay) wantPlay = false // pausa desde el mando de la TV
                    }
                    "TRANSITIONING" -> playback = Player.STATE_BUFFERING
                    "STOPPED", "NO_MEDIA_PRESENT" -> {
                        if (everPlayed && playback != Player.STATE_IDLE && settled) {
                            playback = Player.STATE_ENDED // terminó (o la paró alguien en la TV)
                        } else if (!everPlayed && now - preparedAt > 25_000) {
                            fail(IllegalStateException("La TV no empezó a reproducir"))
                        }
                    }
                }

                if (pos != null && pos.first >= 0 && playback == Player.STATE_READY && settled) {
                    positionMs = pos.first
                    positionAt = now
                    if (!live && pos.second > 0) durationMs = pos.second
                }
                invalidateState()
                if (playback == Player.STATE_IDLE && error != null) break
            }
        }
    }
}
