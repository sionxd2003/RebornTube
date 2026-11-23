package com.example.reborntube

import android.app.Application
import android.app.DownloadManager
import android.content.Intent
import android.os.SystemClock
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.os.Environment
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MimeTypes
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import org.schabi.newpipe.extractor.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale

@OptIn(UnstableApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app
    private val library = VideoLibrary(app)

    // Fechas reales de subida: YouTube en las listas solo dice "hace 3 semanas"; la fecha exacta se pide al abrir
    // la ficha del vídeo, una vez, y se guarda en el móvil.
    private val dateStore = app.getSharedPreferences("upload_dates", android.content.Context.MODE_PRIVATE)
    private val exactDates = androidx.compose.runtime.mutableStateMapOf<String, Long>()
    private val dateGate = kotlinx.coroutines.sync.Semaphore(2)
    private val dateFailed = HashSet<String>()

    // Fotos de canal: salen con el vídeo; si no, se recuerdan de otros vídeos del mismo canal o se piden una vez
    private val avatarStore = app.getSharedPreferences("channel_avatars", android.content.Context.MODE_PRIVATE)
    private val avatarMap = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private val avatarGate = kotlinx.coroutines.sync.Semaphore(1)
    private val avatarFailed = HashSet<String>()

    fun avatarFor(card: VideoCard): String? {
        card.avatar?.let { return it }
        val key = card.channelUrl?.let { channelKey(it) } ?: return null
        avatarMap[key]?.let { return it }
        subscriptions.firstOrNull { channelKey(it.url) == key }?.thumb?.let { return it }
        return avatarStore.getString(key, null)
    }

    private fun rememberAvatar(channelUrl: String?, url: String?) {
        if (channelUrl.isNullOrEmpty() || url.isNullOrEmpty()) return
        val key = channelKey(channelUrl)
        if (avatarStore.getString(key, null) == url) return
        avatarMap[key] = url
        avatarStore.edit().putString(key, url).apply()
    }

    suspend fun resolveAvatar(card: VideoCard) {
        val channelUrl = card.channelUrl ?: return
        if (card.avatar != null) { rememberAvatar(channelUrl, card.avatar); return }
        val key = channelKey(channelUrl)
        if (avatarFor(card) != null || key in avatarFailed) return
        avatarGate.acquire()
        try {
            if (avatarFor(card) != null) return
            val info = runCatching { YouTubeRepository.channel(channelUrl) }.getOrNull()
            val url = info?.avatars?.firstOrNull()?.url
            if (url != null) rememberAvatar(channelUrl, url) else avatarFailed += key
        } finally {
            avatarGate.release()
        }
    }

    fun exactDate(card: VideoCard): Long? {
        exactDates[card.url]?.let { return it }
        parseIsoMillis(card.uploaded)?.let { return it }
        return dateStore.getLong(card.url, 0L).takeIf { it > 0 }
    }

    /** Pide la fecha real de este vídeo. Se cancela sola si el vídeo sale de la pantalla antes de empezar. */
    suspend fun resolveDate(card: VideoCard) {
        if (card.isLive || exactDate(card) != null || card.url in dateFailed) return
        dateGate.acquire()
        try {
            if (exactDate(card) != null) return
            val info = runCatching { YouTubeRepository.streamInfo(card.url) }.getOrNull()
            val date = info?.uploadDate?.takeIf { !it.isApproximation }
            val millis = date?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: parseIsoMillis(info?.textualUploadDate)
            if (millis != null) {
                exactDates[card.url] = millis
                dateStore.edit().putLong(card.url, millis).apply()
            } else {
                dateFailed += card.url
            }
        } finally {
            dateGate.release()
        }
    }
    /** Cuenta de Appwrite: inicio de sesión, foto de perfil y sincronización del historial. */
    private val settings = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    var contentRegion by mutableStateOf(
        YouTubeRepository.normalizeRegionCode(settings.getString("content_region", null))
            ?: Locale.getDefault().country.uppercase(Locale.ROOT).takeIf { it.matches(Regex("[A-Z]{2}")) }
            ?: "US"
    )
        private set
    private val pipedTokenStore = PipedTokenStore(app)
    private val pipedApiUrlInitialization = run {
        val saved = settings.getString("piped_api_url", PipedRepository.DEFAULT_API_URL).orEmpty()
        val normalized = runCatching { PipedRepository.normalizeApiUrl(saved) }
            .getOrDefault(PipedRepository.DEFAULT_API_URL)
        val selected = PipedRepository.migrateLegacyDefault(normalized, pipedTokenStore.hasToken)
        if (selected != normalized) settings.edit().putString("piped_api_url", selected).apply()
        selected to (selected != normalized)
    }
    var serviceId by mutableStateOf(settings.getInt("service_id", org.schabi.newpipe.extractor.ServiceList.YouTube.serviceId))
        private set
    @set:JvmName("setPipedApiUrlState")
    var pipedApiUrl by mutableStateOf(pipedApiUrlInitialization.first)
        private set
    var pipedInstances by mutableStateOf(PipedRepository.defaultInstances)
        private set
    var pipedInstancesLoading by mutableStateOf(false)
        private set
    var pipedAccountConnected by mutableStateOf(pipedTokenStore.hasToken)
        private set
    var pipedAccountName by mutableStateOf(pipedTokenStore.username)
        private set
    var pipedAuthLoading by mutableStateOf(false)
        private set
    var pipedAuthMessage by mutableStateOf<String?>(
        if (pipedApiUrlInitialization.second) {
            tr("La instancia Piped anterior se reemplazó por una recomendada porque no estaba respondiendo. Las cuentas son propias de cada instancia.")
        } else null
    )
        private set
    @set:JvmName("setUsePipedFeedState")
    var usePipedFeed by mutableStateOf(settings.getBoolean("use_piped_feed", false))
        private set
    @set:JvmName("setSearchSuggestionsEnabledState")
    var searchSuggestionsEnabled by mutableStateOf(settings.getBoolean("search_suggestions_enabled", true))
        private set
    @set:JvmName("setRelatedContentEnabledState")
    var relatedContentEnabled by mutableStateOf(settings.getBoolean("related_content_enabled", true))
        private set
    @set:JvmName("setAutoplayOnOpenEnabledState")
    var autoplayOnOpenEnabled by mutableStateOf(settings.getBoolean("autoplay_on_open", true))
        private set
    @set:JvmName("setPauseOnScreenOffState")
    var pauseOnScreenOff by mutableStateOf(settings.getBoolean("pause_on_screen_off", false))
        private set
    @set:JvmName("setNewVideoNotificationsEnabledState")
    var newVideoNotificationsEnabled by mutableStateOf(settings.getBoolean("new_video_notifications", true))
        private set
    @set:JvmName("setNotificationIntervalMinutesState")
    var notificationIntervalMinutes by mutableStateOf(
        settings.getInt("notification_interval_minutes", 30).takeIf { it in listOf(15, 30, 60, 120) } ?: 30
    )
        private set
    @set:JvmName("setNotificationWifiOnlyState")
    var notificationWifiOnly by mutableStateOf(settings.getBoolean("notification_wifi_only", false))
        private set
    @set:JvmName("setDataSaverEnabledState")
    var dataSaverEnabled by mutableStateOf(settings.getBoolean("data_saver_enabled", false))
        private set
    @set:JvmName("setSearchFilterValue")
    var searchFilter by mutableStateOf(settings.getString("search_filter", "videos"))
        private set
    val availableSearchFilters: List<String>
        get() = YouTubeRepository.searchFilters(serviceId).filterNot { it == "channels" || it == "playlists" }
    private val player = PlayerHolder.get(app)

    // Ajustes
    @set:JvmName("setThemeState")
    var theme by mutableStateOf(loadThemePreference()) // standard or Gruvbox palette
    var ambientMode by mutableStateOf(settings.getBoolean("ambient_mode", true))
    @set:JvmName("setSkipSponsorsState")
    var skipSponsors by mutableStateOf(settings.getBoolean("skip_sponsors", true))
    @set:JvmName("setDeArrowEnabledState")
    var deArrowEnabled by mutableStateOf(settings.getBoolean("dearrow_enabled", false))
        private set
    var deArrowMessage by mutableStateOf<String?>(null)
        private set
    var showDeArrowContribution by mutableStateOf(false)
        private set
    var deArrowActionLoading by mutableStateOf(false)
        private set
    private val deArrowOverrides = androidx.compose.runtime.mutableStateMapOf<String, DeArrowBranding>()
    private val deArrowLoadedUrls = mutableSetOf<String>()
    @set:JvmName("setSponsorCategoriesState")
    var sponsorCategories by mutableStateOf(
        settings.getStringSet(
            "sponsor_categories",
            setOf(            "sponsor", "selfpromo", "interaction", "intro", "outro")
        )?.intersect(SponsorBlock.availableCategories.toSet()) ?: emptySet()
    )
    @set:JvmName("setFastDownloadState")
    var fastDownload by mutableStateOf(settings.getBoolean("fast_download", true))
    @set:JvmName("setAccentState")
    var accent by mutableStateOf(
        if (settings.getString("accent", null) == "dynamic") "dynamic" else "default"
    )
        private set
    var fireTvCompanionConnected by mutableStateOf<String?>(null)
        private set
    private var fireTvTarget: RemoteDevice? = null
    @set:JvmName("setFireTvPairingCodeState")
    var fireTvPairingCode by mutableStateOf("")
        private set
    private val fireTvSessionTokenPrefix = "fire_tv_session_token_"
    var pairedFireTvCount by mutableStateOf(
        settings.all.keys.count { it.startsWith(fireTvSessionTokenPrefix) }
    )
        private set

    // Navegación
    var tab by mutableStateOf(Tab.Home)
    var libraryList by mutableStateOf<LibList?>(null) // null = menú de la biblioteca
    var openPlaylistId by mutableStateOf<String?>(null)
    var homeTick by mutableStateOf(0) // sube cada vez que se pulsa Inicio (para volver arriba)

    // Búsqueda / feed
    var query by mutableStateOf("")
    var searchCompleted by mutableStateOf(false)
    var results by mutableStateOf<List<VideoCard>>(emptyList())
    var channelResults by mutableStateOf<List<ChannelCard>>(emptyList())
    var feed by mutableStateOf<List<VideoCard>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    // Feed infinito
    var feedMoreLoading by mutableStateOf(false)
    private var feedNeedsRefresh = false
    private var feedJob: Job? = null
    private val feedTopics = listOf(
        tr("música"), tr("videojuegos"), tr("tecnología"), tr("documental"), tr("humor"), tr("cocina"), tr("deportes"), tr("ciencia"),
        tr("viajes"), tr("películas"), tr("anime"), tr("noticias"), tr("tutorial"), tr("podcast"), tr("coches"), tr("mascotas"), tr("diy")
    )

    // Listas de YouTube (las de un canal) y su pantalla
    var channelPlaylists by mutableStateOf<List<RemotePlaylist>>(emptyList())
    var hasPlaylistsTab by mutableStateOf(false)
    var listMessage by mutableStateOf<String?>(null)
    var remoteUrl by mutableStateOf<String?>(null)
    var remoteName by mutableStateOf("")
    var remoteItems by mutableStateOf<List<VideoCard>>(emptyList())
    var remoteLoading by mutableStateOf(false)
    var remoteError by mutableStateOf<String?>(null)
    var remoteHasMore by mutableStateOf(false)
    private var remoteNextPage: Page? = null
    private var playlistsTab: ListLinkHandler? = null

    // Cola de reproducción y sugerencias de búsqueda
    var queue by mutableStateOf<List<VideoCard>>(emptyList())
    var suggestions by mutableStateOf<List<String>>(emptyList())
    private var suggestJob: Job? = null

    // Historial de búsqueda
    var searchHistory by mutableStateOf<List<String>>(emptyList())

    // Directos
    var liveList by mutableStateOf<List<VideoCard>>(emptyList())
    var liveLoading by mutableStateOf(false)
    var liveError by mutableStateOf<String?>(null)

    // Novedades (últimos vídeos de tus suscripciones) y tendencias
    var news by mutableStateOf<List<VideoCard>>(emptyList())
    var newsLoading by mutableStateOf(false)
    var newsError by mutableStateOf<String?>(null)
    var trendingKind by mutableStateOf("all")
    var trending by mutableStateOf<List<VideoCard>>(emptyList())
    var trendingLoading by mutableStateOf(false)
    var trendingError by mutableStateOf<String?>(null)

    // Shorts
    var shorts by mutableStateOf<List<VideoCard>>(emptyList())
    var shortsLoading by mutableStateOf(false)
    var shortsError by mutableStateOf<String?>(null)
    var shortsSession by mutableStateOf<ShortsSession?>(null)
    var shortPlayLoading by mutableStateOf(false)
    var shortPlayError by mutableStateOf<String?>(null)
    var shortInfo by mutableStateOf<StreamInfo?>(null)
    @set:JvmName("setShortVoteState")
    var shortVote by mutableStateOf(0)
    private var shortJob: Job? = null
    private val shortTopics = listOf(
        "shorts", "#shorts", tr("shorts divertidos"), tr("shorts videojuegos"), tr("shorts música"), tr("shorts anime"),
        tr("shorts cocina"), tr("shorts deportes"), tr("shorts mascotas"), tr("shorts tecnología"), tr("shorts humor"),
        tr("shorts fails"), tr("shorts minecraft"), tr("shorts mario kart"), tr("shorts curiosidades"), tr("shorts reacciones")
    ).shuffled()
    private var shortTopicIndex = 0

    // Biblioteca local
    var historyList by mutableStateOf<List<VideoCard>>(emptyList())
    var likedList by mutableStateOf<List<VideoCard>>(emptyList())
    var dislikedList by mutableStateOf<List<VideoCard>>(emptyList())
    var laterList by mutableStateOf<List<VideoCard>>(emptyList())
    var subscriptions by mutableStateOf<List<ChannelCard>>(emptyList())
    var channelGroups by mutableStateOf<List<ChannelGroup>>(emptyList())
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
    var progress by mutableStateOf<Map<String, Long>>(emptyMap()) // url -> posición en ms
    var backupMessage by mutableStateOf<String?>(null)
    var saveDialogCard by mutableStateOf<VideoCard?>(null) // diálogo tr("Guardar en lista")

    // Descargas y reproducción sin conexión
    var downloads by mutableStateOf<List<DownloadRecord>>(emptyList())
    var downloadProgress by mutableStateOf<Map<Long, Int>>(emptyMap()) // id de descarga -> % (100 lista, -1 falló)
    var downloadMessage by mutableStateOf<String?>(null)
    private var downloadRefreshJob: Job? = null
    var offline by mutableStateOf<DownloadRecord?>(null)
    var offlineAudioQueue by mutableStateOf<List<DownloadRecord>>(emptyList())
        private set
    @set:JvmName("setOfflineShuffleState")
    var offlineShuffleEnabled by mutableStateOf(settings.getBoolean("offline_audio_shuffle", false))
        private set
    @set:JvmName("setOfflineRepeatModeState")
    var offlineRepeatMode by mutableStateOf(settings.getInt("offline_audio_repeat_mode", 0).coerceIn(0, 2))
        private set
    private var offlineAudioBaseQueue: List<DownloadRecord> = emptyList()

    // Vídeo actual, pantalla completa, ventana flotante y mini reproductor
    var current by mutableStateOf<StreamInfo?>(null)
    var opening by mutableStateOf<VideoCard?>(null) // vídeo que se está abriendo (pantalla de carga)
    var expanded by mutableStateOf(true) // true = vídeo en grande, false = mini reproductor
    var fullscreen by mutableStateOf(false)
    var inPip by mutableStateOf(false)
    var isPlaying by mutableStateOf(false)
    var isBuffering by mutableStateOf(false)
    @set:JvmName("setSpeedState")
    var speed by mutableStateOf(settings.getFloat("playback_speed", 1f).coerceIn(0.5f, 2f))
    var seekIntervalSeconds by mutableStateOf(settings.getInt("seek_interval_seconds", 10).takeIf { it in listOf(5, 10, 15, 30) } ?: 10)
        private set
    var playbackError by mutableStateOf<String?>(null)
    var playbackPauseMessage by mutableStateOf<String?>(null)
        private set
    var audioOnly by mutableStateOf(false) // modo música: solo audio, sigue sonando en segundo plano
    var loop by mutableStateOf(false) // repetir el vídeo al terminar
    @set:JvmName("setShuffleMusicQueueState")
    var shuffleMusicQueue by mutableStateOf(settings.getBoolean("shuffle_music_queue", false))
        private set
    @set:JvmName("setRepeatMusicQueueState")
    var repeatMusicQueue by mutableStateOf(settings.getBoolean("repeat_music_queue", false))
        private set
    var sleepTimerRemainingMs by mutableStateOf(0L)
        private set
    var sleepAfterCurrent by mutableStateOf(false)
        private set
    @set:JvmName("setBreakReminderMinutesState")
    var breakReminderMinutes by mutableStateOf(
        settings.getInt("break_reminder_minutes", 0).takeIf { it in listOf(0, 15, 30, 45, 60) } ?: 0
    )
        private set
    var showBreakReminder by mutableStateOf(false)
        private set
    var breakReminderElapsedMs by mutableStateOf(0L)
        private set
    private var sleepAfterCurrentUrl: String? = null
    private var sleepAfterOfflineFileName: String? = null
    private var sleepTimerJob: Job? = null
    @set:JvmName("setAutoplayNextState")
    var autoplayNextEnabled by mutableStateOf(settings.getBoolean("autoplay_next", true))
        private set
    @set:JvmName("setAutoplayCountdownEnabledState")
    var autoplayCountdownEnabled by mutableStateOf(settings.getBoolean("autoplay_countdown_enabled", true))
        private set
    @set:JvmName("setFullscreenOrientationState")
    var fullscreenOrientation by mutableStateOf(
        settings.getString("fullscreen_orientation", "landscape")
            ?.takeIf { it in setOf("landscape", "portrait", "sensor") } ?: "landscape"
    )
        private set
    var autoplayNextCard by mutableStateOf<VideoCard?>(null)
        private set
    var autoplayCountdown by mutableStateOf(0)
        private set
    private var autoplayJob: Job? = null
    val miniVisible get() = current != null && !expanded
    private var openJob: Job? = null
    private var switchJob: Job? = null

    // Transmitir a la TV (Google Cast)
    var castContext: CastContext? = null
        private set
    private var castPlayer: CastPlayer? = null
    private var castDirect = false
    private var castFellBack = false
    // Fire TV / TVs DLNA (no Google Cast)
    private var dlna: DlnaPlayer? = null
    private var dlnaFellBack = false
    /** Fire TV encontrado por DIAL pero sin receptor DLNA: se enseñan las opciones. */
    var dialOnlyDevice by mutableStateOf<RemoteDevice?>(null)
    var castStatus by mutableStateOf("") // lo que dice la TV que está haciendo
    var showCastSheet by mutableStateOf(false)
    var casting by mutableStateOf(false)
    var castDeviceName by mutableStateOf("")
    var castMessage by mutableStateOf<String?>(null)
    var castDetail by mutableStateOf("") // modo de envío, para ver qué está haciendo
    /** El reproductor que manda ahora: el de la TV si se está transmitiendo, si no el del móvil. */
    val activePlayer: Player get() = if (casting) (dlna ?: castPlayer ?: player) else player

    // Subtítulos (se dibujan sobre el vídeo)
    var subtitleText by mutableStateOf<String?>(null)
    var subtitleLoading by mutableStateOf(false)
    var subtitleError by mutableStateOf<String?>(null)
    private var subtitleCues: List<Cue> = emptyList()
    private var subtitleJob: Job? = null

    // Calidad y audio (vídeos normales) / calidad por pistas (directos HLS)
    var videoOptions by mutableStateOf<List<VideoStream>>(emptyList())
    var audioOptions by mutableStateOf<List<AudioStream>>(emptyList())
    var selVideo by mutableStateOf<VideoStream?>(null)
    var selAudio by mutableStateOf<AudioStream?>(null)
    var subtitleOptions by mutableStateOf<List<SubtitlesStream>>(emptyList())
    var selSubtitle by mutableStateOf<SubtitlesStream?>(null)
    var hlsMode by mutableStateOf(false)
    var hlsHeights by mutableStateOf<List<Int>>(emptyList())
    var hlsSelected by mutableStateOf<Int?>(null) // null = automática

    // Sponsors saltados automáticamente
    var sponsorSegments by mutableStateOf<List<SponsorSegment>>(emptyList())
        private set
    var skippedMessage by mutableStateOf<String?>(null)
    var showSponsorContribution by mutableStateOf(false)
        private set
    var sponsorActionLoading by mutableStateOf(false)
        private set
    var sponsorBlockMessage by mutableStateOf<String?>(null)
        private set

    // Like / dislike y recomendados
    @set:JvmName("setVoteState")
    var vote by mutableStateOf(0)
    var dislikeCount by mutableStateOf<Long?>(null)
        private set
    var dislikeLoading by mutableStateOf(false)
        private set
    var dislikeError by mutableStateOf<String?>(null)
        private set
    private var dislikeJob: Job? = null
    var related by mutableStateOf<List<VideoCard>>(emptyList())

    // Comentarios (cerrados por defecto; se recuerda la elección)
    var commentsOpen by mutableStateOf(settings.getBoolean("comments_open", false))
    var comments by mutableStateOf<List<CommentsInfoItem>>(emptyList())
    var commentsLoading by mutableStateOf(false)
    var commentsError by mutableStateOf<String?>(null)
    var hasMoreComments by mutableStateOf(false)
    private var commentsInfo: CommentsInfo? = null
    private var nextPage: Page? = null

    // Canal
    var channelUrl by mutableStateOf<String?>(null)
    var channel by mutableStateOf<ChannelInfo?>(null)
    var channelVideos by mutableStateOf<List<VideoCard>>(emptyList())
    var channelLoading by mutableStateOf(false)
    var channelError by mutableStateOf<String?>(null)
    var channelHasMore by mutableStateOf(false)
    var channelSection by mutableStateOf(0) // 0 = Vídeos, 1 = Directos, 2 = Shorts, 3 = Listas
    @set:JvmName("setChannelSortState")
    var channelSort by mutableStateOf(0) // 0 = Recientes, 1 = Populares, 2 = Antiguos
    var hasLiveTab by mutableStateOf(false)
    var hasShortsTab by mutableStateOf(false)
    private var videosTab: ListLinkHandler? = null
    private var liveTab: ListLinkHandler? = null
    private var shortsTab: ListLinkHandler? = null
    private var channelTab: ListLinkHandler? = null
    private var channelNextPage: Page? = null

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            playbackPauseMessage = when {
                playWhenReady -> null
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ->
                    tr("Otra aplicación tomó el audio y Android pausó el video.")
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ->
                    tr("El dispositivo de audio se desconectó o cambió y el video se pausó.")
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE ->
                    tr("La reproducción se pausó desde un control externo.")
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ||
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> null
                else -> trf("Pausa automática del reproductor (motivo %s).", reason)
            }
            syncPlaying()
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            playbackPauseMessage = when (playbackSuppressionReason) {
                Player.PLAYBACK_SUPPRESSION_REASON_NONE -> null
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS ->
                    tr("Esperando a que otra aplicación libere el audio.")
                else -> trf("Reproducción suspendida por el sistema (motivo %s).", playbackSuppressionReason)
            }
            syncPlaying()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncPlaying()
            // Al terminar un vídeo, sigue con el siguiente de la cola
            if (playbackState == Player.STATE_ENDED) handlePlaybackEnded()
        }
        override fun onPlayerError(error: PlaybackException) {
            // DLNA: si la TV no pudo con el manifiesto DASH, se reintenta con el archivo normal
            val d = dlna
            if (casting && d != null && !dlnaFellBack) {
                dlnaFellBack = true
                current?.let { sendToDlna(it, d.currentPosition, fallback = true) }
                return
            }
            // Si la TV no pudo con el servidor del móvil, se reintenta enviando el archivo directamente
            if (casting && !castDirect && !castFellBack) {
                castFellBack = true
                current?.let { castCurrent(it, castPlayer?.currentPosition ?: 0L, direct = true) }
                return
            }
            playbackError = trf("Error de reproducción: %s", error.errorCodeName)
            if (shortsSession != null) shortPlayError = tr("No se pudo reproducir este short")
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (!hlsMode) return
            hlsHeights = tracks.groups
                .filter { it.type == C.TRACK_TYPE_VIDEO }
                .flatMap { g ->
                    (0 until g.length).filter { g.isTrackSupported(it) }.map { g.getTrackFormat(it).height }
                }
                .filter { it > 0 }
                .distinct()
                .sortedDescending()
        }
    }

    init {
        settings.edit().remove("fire_tv_pairing_code").apply()
        player.addListener(playerListener)
        player.setPlaybackSpeed(speed)
        initCast()
        refreshLibrary()
        loadFeed()
        // Guarda el progreso del vídeo cada pocos segundos
        viewModelScope.launch {
            while (true) {
                delay(5000)
                saveProgress()
            }
        }
        // Lo que dice la TV (Google Cast) que está haciendo: sirve para saber por qué un directo no se ve
        viewModelScope.launch {
            while (true) {
                delay(2000)
                castStatus = if (casting && dlna == null) receiverStatus() else ""
            }
        }
        // Salta sponsors: mira la posición cada medio segundo
        viewModelScope.launch {
            while (true) {
                delay(500)
                checkSponsors()
            }
        }
        viewModelScope.launch {
            var lastTick = SystemClock.elapsedRealtime()
            while (true) {
                delay(1_000)
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - lastTick).coerceAtLeast(0L)
                lastTick = now
                if (breakReminderMinutes == 0 || showBreakReminder || !activePlayer.isPlaying) continue
                breakReminderElapsedMs += elapsed
                if (breakReminderElapsedMs >= breakReminderMinutes * 60_000L) {
                    breakReminderElapsedMs = breakReminderMinutes * 60_000L
                    showBreakReminder = true
                }
            }
        }
        // Subtítulos: se actualizan cinco veces por segundo
        viewModelScope.launch {
            while (true) {
                delay(200)
                updateSubtitle()
            }
        }
    }

    override fun onCleared() {
        player.removeListener(playerListener)
        releaseCast()
    }

    private fun syncPlaying() {
        val p = activePlayer
        isPlaying = p.isPlaying
        isBuffering = p.playWhenReady && p.playbackState == Player.STATE_BUFFERING
    }

    /** YouTube a veces pide "confirmar que no eres un bot" desde ciertas redes: se explica en claro. */
    private fun friendly(t: Throwable, default: String): String {
        val m = t.message ?: return default
        return when {
            m.contains("age-restricted", ignoreCase = true) ->
                tr("Este vídeo tiene restricción de edad. YouTube solo lo deja ver con una cuenta iniciada, y RebornTube (igual que NewPipe) no inicia sesión, así que no se puede reproducir aquí.")
            m.contains("LOGIN_REQUIRED") || m.contains("not a bot", ignoreCase = true) ->
                tr("YouTube pidió verificar que no eres un bot desde esta red. Prueba a cambiar entre Wi-Fi y datos móviles, o inténtalo de nuevo en unos minutos.")
            else -> m
        }
    }

    private class LibrarySnapshot(
        val history: List<VideoCard>,
        val liked: List<VideoCard>,
        val disliked: List<VideoCard>,
        val later: List<VideoCard>,
        val subscriptions: List<ChannelCard>,
        val channelGroups: List<ChannelGroup>,
        val playlists: List<Playlist>,
        val downloads: List<DownloadRecord>,
        val progress: Map<String, Long>,
        val searches: List<String>
    )

    /** Lee las listas guardadas fuera del hilo principal para que abrir un vídeo no se quede esperando. */
    private fun refreshLibraryAsync() {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                LibrarySnapshot(
                    library.history(), library.liked(), library.disliked(), library.later(),
                    library.subscriptions(), library.channelGroups(), library.playlists(), library.downloads(),
                    library.progressMap(), library.searches()
                )
            }
            historyList = data.history
            likedList = data.liked
            dislikedList = data.disliked
            laterList = data.later
            subscriptions = data.subscriptions
            channelGroups = data.channelGroups
            playlists = data.playlists
            downloads = data.downloads
            progress = data.progress
            searchHistory = data.searches
        }
    }

    /** Apunta el vídeo en el historial y mira si tiene voto, sin frenar la reproducción. */
    private fun recordWatched(info: StreamInfo) {
        rememberAvatar(info.uploaderUrl, info.uploaderAvatars.firstOrNull()?.url)
        // Al abrir un vídeo ya se conoce su fecha real: se guarda para las listas
        info.uploadDate?.takeIf { !it.isApproximation }?.offsetDateTime()?.toInstant()?.toEpochMilli()?.let {
            exactDates[info.url] = it
            dateStore.edit().putLong(info.url, it).apply()
        }
        viewModelScope.launch {
            val savedVote = withContext(Dispatchers.IO) {
                library.addHistory(info.toCard())
                library.vote(info.url)
            }
            if (current?.url == info.url) vote = savedVote
            refreshLibraryAsync()
            // Actualizar el feed al volver a Inicio evita búsquedas de red en pleno reproductor.
            feedNeedsRefresh = true
        }
    }

    private fun refreshLibrary() {
        historyList = library.history()
        likedList = library.liked()
        dislikedList = library.disliked()
        laterList = library.later()
        subscriptions = library.subscriptions()
        channelGroups = library.channelGroups()
        playlists = library.playlists()
        downloads = library.downloads()
        progress = library.progressMap()
        searchHistory = library.searches()
    }

    // ---------- Ajustes ----------

    private fun loadThemePreference(): String {
        val saved = settings.getString("theme", null)
        val validThemes = setOf(
            "light", "black", "system", "gruvbox_light", "gruvbox_dark"
        )
        val theme = when {
            saved == "catppuccin_latte" || saved == "cappuccino" -> "gruvbox_light"
            saved in setOf("catppuccin_frappe", "catppuccin_macchiato", "catppuccin_mocha") -> "gruvbox_dark"
            saved == "dark" -> "black"
            saved in validThemes -> saved!!
            else -> "gruvbox_dark"
        }
        if (theme != saved) settings.edit().putString("theme", theme).apply()
        return theme
    }

    fun setTheme(value: String) {
        theme = value
        settings.edit().putString("theme", value).apply()
    }

    fun selectContentRegion(code: String) {
        val normalized = YouTubeRepository.normalizeRegionCode(code) ?: return
        if (YouTubeRepository.setContentRegion(appContext, normalized)) {
            contentRegion = normalized
            settings.edit().putString("content_region", normalized).apply()
            loadFeed()
        }
    }

    fun setService(serviceId: Int) {
        if (YouTubeRepository.supportedServices.none { it.serviceId == serviceId }) return
        this.serviceId = serviceId
        settings.edit().putInt("service_id", serviceId).apply()
        val filters = availableSearchFilters
        if (searchFilter == null || searchFilter !in filters) {
            setSearchFilter(filters.firstOrNull { it.contains("video", true) } ?: filters.firstOrNull())
        }
        results = emptyList()
        channelResults = emptyList()
        suggestions = emptyList()
    }

    fun refreshPipedInstances() {
        if (pipedInstancesLoading) return
        viewModelScope.launch {
            pipedInstancesLoading = true
            pipedAuthMessage = null
            try {
                val latest = PipedRepository.availableInstances()
                pipedInstances = latest
                if (latest.none { it.apiUrl == pipedApiUrl }) {
                    pipedInstances = latest + PipedInstance(tr("Instancia seleccionada"), pipedApiUrl)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                pipedAuthMessage = trf("No se pudieron actualizar las instancias: %s", error.message)
            } finally {
                pipedInstancesLoading = false
            }
        }
    }

    fun setPipedApiUrl(value: String) {
        val normalized = try {
            PipedRepository.normalizeApiUrl(value)
        } catch (error: Exception) {
            pipedAuthMessage = error.message ?: tr("La dirección de la instancia no es válida")
            return
        }
        if (pipedApiUrl == normalized) return
        if (pipedAccountConnected) {
            pipedTokenStore.clear()
            pipedAccountConnected = false
            pipedAccountName = ""
            usePipedFeed = false
            settings.edit().putBoolean("use_piped_feed", false).apply()
        }
        pipedApiUrl = normalized
        settings.edit().putString("piped_api_url", normalized).apply()
        pipedAuthMessage = tr("Instancia guardada. Inicia sesión en esta instancia para usar su feed.")
    }

    fun authenticatePiped(username: String, password: String, register: Boolean) {
        if (pipedAuthLoading) return
        val name = username.trim()
        if (name.isBlank() || password.isBlank()) {
            pipedAuthMessage = tr("Escribe tu nombre de usuario y contraseña de Piped.")
            return
        }
        viewModelScope.launch {
            pipedAuthLoading = true
            pipedAuthMessage = null
            try {
                val account = PipedRepository.authenticate(pipedApiUrl, name, password, register)
                pipedTokenStore.save(account)
                pipedAccountConnected = true
                pipedAccountName = account.username
                pipedAuthMessage = tr("Cuenta Piped conectada.")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                pipedAuthMessage = error.message ?: tr("No se pudo conectar con la cuenta Piped.")
            } finally {
                pipedAuthLoading = false
            }
        }
    }

    fun logoutPiped() {
        pipedTokenStore.clear()
        pipedAccountConnected = false
        pipedAccountName = ""
        setUsePipedFeed(false)
        pipedAuthMessage = tr("Sesión Piped cerrada.")
    }

    fun setUsePipedFeed(enabled: Boolean) {
        usePipedFeed = enabled && pipedAccountConnected
        settings.edit().putBoolean("use_piped_feed", usePipedFeed).apply()
        if (usePipedFeed && tab == Tab.News) loadNews()
    }

    fun setSearchSuggestionsEnabled(enabled: Boolean) {
        searchSuggestionsEnabled = enabled
        settings.edit().putBoolean("search_suggestions_enabled", enabled).apply()
        if (!enabled) {
            suggestJob?.cancel()
            suggestions = emptyList()
        } else if (query.isNotBlank()) {
            onQueryChange(query)
        }
    }

    fun setRelatedContentEnabled(enabled: Boolean) {
        relatedContentEnabled = enabled
        settings.edit().putBoolean("related_content_enabled", enabled).apply()
        if (!enabled) {
            related = emptyList()
            relatedCursors = emptyList()
            relatedMoreLoading = false
        } else {
            current?.let(::loadRelated)
        }
    }

    fun setAutoplayOnOpenEnabled(enabled: Boolean) {
        autoplayOnOpenEnabled = enabled
        settings.edit().putBoolean("autoplay_on_open", enabled).apply()
    }

    fun setAutoplayCountdownEnabled(enabled: Boolean) {
        autoplayCountdownEnabled = enabled
        settings.edit().putBoolean("autoplay_countdown_enabled", enabled).apply()
    }

    fun setFullscreenOrientation(orientation: String) {
        require(orientation in setOf("landscape", "portrait", "sensor"))
        fullscreenOrientation = orientation
        settings.edit().putString("fullscreen_orientation", orientation).apply()
    }

    fun setPauseOnScreenOff(enabled: Boolean) {
        pauseOnScreenOff = enabled
        settings.edit().putBoolean("pause_on_screen_off", enabled).apply()
    }

    fun setNewVideoNotificationsEnabled(enabled: Boolean) {
        newVideoNotificationsEnabled = enabled
        settings.edit().putBoolean("new_video_notifications", enabled).apply()
        ChannelUpdateWorker.schedule(appContext)
    }

    fun setNotificationIntervalMinutes(minutes: Int) {
        require(minutes in listOf(15, 30, 60, 120))
        notificationIntervalMinutes = minutes
        settings.edit().putInt("notification_interval_minutes", minutes).apply()
        ChannelUpdateWorker.schedule(appContext)
    }

    fun setNotificationWifiOnly(enabled: Boolean) {
        notificationWifiOnly = enabled
        settings.edit().putBoolean("notification_wifi_only", enabled).apply()
        ChannelUpdateWorker.schedule(appContext)
    }

    fun setDataSaverEnabled(enabled: Boolean) {
        dataSaverEnabled = enabled
        settings.edit().putBoolean("data_saver_enabled", enabled).apply()
    }

    fun setSearchFilter(filter: String?) {
        searchFilter = filter
        settings.edit().putString("search_filter", filter).apply()
    }

    fun updateAmbientMode(enabled: Boolean) {
        ambientMode = enabled
        settings.edit().putBoolean("ambient_mode", enabled).apply()
    }

    fun setSkipSponsors(on: Boolean) {
        skipSponsors = on
        settings.edit().putBoolean("skip_sponsors", on).apply()
        current?.let { loadSponsors(it) }
    }

    fun setDeArrowEnabled(enabled: Boolean) {
        deArrowEnabled = enabled
        deArrowMessage = null
        settings.edit().putBoolean("dearrow_enabled", enabled).apply()
        if (enabled) current?.let { info ->
            viewModelScope.launch { loadDeArrow(info.toCard()) }
        }
    }

    fun displayCard(card: VideoCard): VideoCard {
        if (!deArrowEnabled) return card
        val branding = deArrowOverrides[card.url] ?: return card
        return card.copy(
            title = branding.title ?: card.title,
            thumb = branding.thumbnailUrl ?: card.thumb
        )
    }

    fun displayTitle(info: StreamInfo): String = deArrowOverrides[info.url]?.title ?: info.name.orEmpty()

    suspend fun loadDeArrow(card: VideoCard) {
        if (!deArrowEnabled || card.url in deArrowLoadedUrls) return
        val videoId = DeArrow.videoId(card.url) ?: return
        deArrowLoadedUrls += card.url
        try {
            DeArrow.branding(videoId, card.thumb)?.let { deArrowOverrides[card.url] = it }
        } catch (error: Exception) {
            if (error is CancellationException) {
                deArrowLoadedUrls.remove(card.url)
                throw error
            }
            deArrowLoadedUrls.remove(card.url)
            deArrowMessage = trf("No se pudo consultar DeArrow: %s", error.message)
        }
    }

    fun openDeArrowContribution() {
        val info = current ?: return
        if (DeArrow.videoId(info.url) == null) return
        deArrowMessage = null
        showDeArrowContribution = true
    }

    fun closeDeArrowContribution() {
        showDeArrowContribution = false
    }

    fun submitDeArrowContribution(title: String?, thumbnailTimestamp: Float?) {
        val info = current ?: return
        val videoId = DeArrow.videoId(info.url) ?: return
        if (deArrowActionLoading) return
        val userIdKey = "dearrow_local_user_id"
        val userId = settings.getString(userIdKey, null) ?: java.util.UUID.randomUUID().toString().also {
            settings.edit().putString(userIdKey, it).apply()
        }
        deArrowActionLoading = true
        deArrowMessage = null
        viewModelScope.launch {
            try {
                DeArrow.submit(
                    videoId = videoId,
                    userId = userId,
                    userAgent = "RebornTube/${BuildConfig.VERSION_NAME} (Android)",
                    title = title?.takeIf(String::isNotBlank),
                    thumbnailTimestamp = thumbnailTimestamp
                )
                deArrowMessage = tr("Gracias. Tu propuesta se envió a DeArrow para revisión comunitaria.")
                showDeArrowContribution = false
                deArrowLoadedUrls.remove(info.url)
                loadDeArrow(info.toCard())
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                deArrowMessage = trf("No se pudo enviar la propuesta a DeArrow: %s", error.message)
            } finally {
                deArrowActionLoading = false
            }
        }
    }

    fun setSponsorCategoryEnabled(category: String, enabled: Boolean) {
        require(category in SponsorBlock.availableCategories)
        sponsorCategories = if (enabled) sponsorCategories + category else sponsorCategories - category
        settings.edit().putStringSet("sponsor_categories", sponsorCategories.toSet()).apply()
        current?.let { loadSponsors(it) }
    }

    fun openSponsorContribution() {
        if (current == null || current?.service?.serviceId != org.schabi.newpipe.extractor.ServiceList.YouTube.serviceId) return
        sponsorBlockMessage = null
        showSponsorContribution = true
    }

    fun closeSponsorContribution() {
        showSponsorContribution = false
    }

    fun submitSponsorContribution(startSeconds: Float, endSeconds: Float, category: String) {
        val info = current ?: return
        if (sponsorActionLoading || info.service.serviceId != org.schabi.newpipe.extractor.ServiceList.YouTube.serviceId) return
        val durationSeconds = activePlayer.duration.takeIf { it > 0L }?.div(1000f) ?: 0f
        sponsorActionLoading = true
        sponsorBlockMessage = null
        viewModelScope.launch {
            try {
                SponsorBlock.submitSegment(
                    videoId = info.id,
                    userId = localSponsorBlockUserId(),
                    userAgent = "RebornTube/${BuildConfig.VERSION_NAME} (Android)",
                    startSeconds = startSeconds,
                    endSeconds = endSeconds,
                    category = category,
                    durationSeconds = durationSeconds
                )
                sponsorBlockMessage = tr("Segmento enviado a SponsorBlock para revisión.")
                showSponsorContribution = false
                loadSponsors(info)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                sponsorBlockMessage = trf("No se pudo enviar el segmento: %s", error.message)
            } finally {
                sponsorActionLoading = false
            }
        }
    }

    fun voteSponsorSegment(segment: SponsorSegment, score: Int) {
        val info = current ?: return
        if (sponsorActionLoading || segment.uuid.isBlank()) return
        sponsorActionLoading = true
        sponsorBlockMessage = null
        viewModelScope.launch {
            try {
                SponsorBlock.vote(info.id, segment.uuid, localSponsorBlockUserId(), score)
                sponsorBlockMessage = tr("Gracias por votar el segmento.")
                loadSponsors(info)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                sponsorBlockMessage = trf("No se pudo registrar el voto: %s", error.message)
            } finally {
                sponsorActionLoading = false
            }
        }
    }

    private fun localSponsorBlockUserId(): String {
        val key = "sponsorblock_local_user_id"
        settings.getString(key, null)?.let { return it }
        val bytes = ByteArray(23).also { java.security.SecureRandom().nextBytes(it) }
        val id = android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        ).take(30)
        settings.edit().putString(key, id).apply()
        return id
    }

    fun setAccent(value: String) {
        accent = if (value == "dynamic") "dynamic" else "default"
        settings.edit().putString("accent", accent).apply()
    }

    fun resumeOnPhone() {
        val device = fireTvTarget
        val sessionToken = device?.let(::fireTvPairingToken)
        if (device != null && sessionToken != null) {
            viewModelScope.launch {
                val stopped = withContext(Dispatchers.IO) {
                    Upnp.stopRebornTube(device, sessionToken)
                }
                if (!stopped) {
                    castMessage = tr("No se pudo detener el video en el televisor; se reanudó en el celular.")
                }
                continueOnPhone()
            }
        } else {
            continueOnPhone()
        }
    }

    private fun continueOnPhone() {
        val info = current
        if (info != null && !isLiveInfo(info)) {
            val resumePosition = library.progress(info.url)
            if (resumePosition > 0) player.seekTo(resumePosition)
        }
        progress = library.progressMap()
        FireTvProgressService.stop(appContext)
        fireTvTarget = null
        fireTvCompanionConnected = null
        player.play()
        syncPlaying()
    }

    fun setFireTvPairingCode(value: String) {
        fireTvPairingCode = value.filter { it in '0'..'9' }.take(6)
    }

    fun isFireTvPaired(device: RemoteDevice): Boolean {
        pairedFireTvCount
        return fireTvPairingToken(device) != null
    }

    fun forgetFireTvPairings() {
        val editor = settings.edit()
        settings.all.keys.filter { it.startsWith(fireTvSessionTokenPrefix) }.forEach { editor.remove(it) }
        editor.apply()
        pairedFireTvCount = 0
    }

    private fun fireTvPairingToken(device: RemoteDevice): String? =
        settings.getString(fireTvTokenPreferenceKey(device), null)?.takeIf { it.isNotBlank() }

    private fun saveFireTvPairingToken(device: RemoteDevice, token: String) {
        settings.edit().putString(fireTvTokenPreferenceKey(device), token).apply()
        pairedFireTvCount = settings.all.keys.count { it.startsWith(fireTvSessionTokenPrefix) }
    }

    private fun removeFireTvPairingToken(device: RemoteDevice) {
        settings.edit().remove(fireTvTokenPreferenceKey(device)).apply()
        pairedFireTvCount = settings.all.keys.count { it.startsWith(fireTvSessionTokenPrefix) }
    }

    private fun fireTvTokenPreferenceKey(device: RemoteDevice): String {
        val identity = device.udn?.takeIf { it.isNotBlank() } ?: device.host
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val key = buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
        return fireTvSessionTokenPrefix + key
    }

    private fun clearFireTvPairingCode() {
        fireTvPairingCode = ""
    }

    /** Al salir de la app: pausa (el vídeo queda en la notificación de Android). */
    fun pauseForLeaving() {
        saveProgress()
        if (player.playWhenReady) player.pause()
    }

    fun removeSearch(query: String) {
        library.removeSearch(query)
        searchHistory = library.searches()
    }

    fun clearSearches() {
        library.clearSearches()
        searchHistory = emptyList()
    }

    fun setFastDownload(on: Boolean) {
        fastDownload = on
        settings.edit().putBoolean("fast_download", on).apply()
    }

    fun setBreakReminderMinutes(minutes: Int) {
        require(minutes in listOf(0, 15, 30, 45, 60))
        breakReminderMinutes = minutes
        breakReminderElapsedMs = 0L
        showBreakReminder = false
        settings.edit().putInt("break_reminder_minutes", minutes).apply()
    }

    fun resolveBreakReminder(pausePlayback: Boolean) {
        showBreakReminder = false
        breakReminderElapsedMs = 0L
        if (pausePlayback) activePlayer.pause()
    }

    // ---------- Navegación ----------

    fun selectTab(t: Tab) {
        channelUrl = null
        remoteUrl = null
        libraryList = null
        openPlaylistId = null
        if (t == Tab.Home) goHome()
        if (t == Tab.Shorts && shorts.isEmpty()) loadShorts(reset = true)
        tab = t
    }

    fun openSubscriptions() {
        channelUrl = null
        tab = Tab.Subscriptions
    }

    fun openLive() {
        channelUrl = null
        tab = Tab.Live
        if (liveList.isEmpty()) loadLive()
    }

    fun openNews() {
        channelUrl = null
        tab = Tab.News
        loadNews()
    }

    fun openTrending(kind: String) {
        channelUrl = null
        trendingKind = kind
        tab = Tab.Trending
        loadTrending()
    }

    fun openLibraryList(list: LibList) {
        channelUrl = null
        openPlaylistId = null
        tab = Tab.Library
        libraryList = list
    }

    fun onBack() {
        when {
            opening != null -> cancelOpen()
            offline?.audioOnly == true && expanded -> minimizeOffline()
            offline != null -> closeOffline()
            shortsSession != null -> closeShorts()
            fullscreen -> fullscreen = false
            current != null && expanded -> minimize()
            remoteUrl != null -> closeRemote()
            channelUrl != null -> closeChannel()
            tab == Tab.Library && openPlaylistId != null -> openPlaylistId = null
            tab == Tab.Library && libraryList != null -> libraryList = null
            tab != Tab.Home -> tab = Tab.Home
            tab == Tab.Home && (results.isNotEmpty() || query.isNotBlank()) -> goHome() // sale de la búsqueda
        }
    }

    fun goHome() {
        query = ""
        results = emptyList()
        channelResults = emptyList()
        error = null
        homeTick++
        if (feed.isEmpty() || feedNeedsRefresh) {
            feedNeedsRefresh = false
            loadFeed()
        }
    }

    // ---------- Enlaces de YouTube (abrir con la app / compartir hacia la app) ----------

    fun openLink(text: String) {
        val raw = Regex("https?://\\S+").find(text)?.value ?: return
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        val host = uri.host.orEmpty()
        val segments = uri.pathSegments
        val id: String? = when {
            host.contains("youtu.be") -> segments.firstOrNull()
            segments.firstOrNull() == "watch" -> uri.getQueryParameter("v")
            segments.firstOrNull() in listOf("shorts", "live", "embed") -> segments.getOrNull(1)
            else -> null
        }
        when {
            id != null -> open(
                VideoCard(
                    url = "https://www.youtube.com/watch?v=$id",
                    title = "",
                    channel = "",
                    channelUrl = null,
                    thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                    duration = 0
                )
            )
            segments.firstOrNull()?.let { it == "channel" || it == "c" || it == "user" || it.startsWith("@") } == true ->
                openChannel(raw)
        }
    }

    // ---------- Feed / búsqueda / directos / novedades / tendencias / shorts ----------

    fun onQueryChange(text: String) {
        if (query != text) {
            searchCompleted = false
            results = emptyList()
            channelResults = emptyList()
            searchCursor = null
            resultsMoreLoading = false
            loading = false
            error = null
        }
        query = text
        if (text.isBlank()) {
            results = emptyList()
            channelResults = emptyList() // vuelve al feed tr("Para ti")
        }
        // Autocompletar: pide sugerencias a YouTube un momento después de dejar de escribir
        suggestJob?.cancel()
        if (text.isBlank()) {
            suggestions = emptyList()
        } else if (searchSuggestionsEnabled) {
            val typed = text.trim()
            suggestJob = viewModelScope.launch {
                delay(250)
                runCatching { YouTubeRepository.suggestions(typed, serviceId) }
                    .onSuccess { suggestions = it.take(10) }
            }
        }
    }

    /** Semillas para el feed: lo visto, canales y temas variados (cada llamada elige otras). */
    private fun nextFeedSeeds(): List<String> {
        val titles = library.history().take(30).map { it.title }.filter { it.isNotBlank() }.shuffled().take(4)
        val channels = (library.subscriptions().map { it.name } + library.history().map { it.channel })
            .filter { it.isNotBlank() }.distinct().shuffled().take(3)
        val topics = feedTopics.shuffled().take(if (titles.isEmpty()) 4 else 2)
        return (titles + channels + topics).distinct().take(9)
    }

    fun loadFeed() {
        if (loading) return
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            loading = true
            error = null
            runCatching { YouTubeRepository.feed(nextFeedSeeds()) }
                .onSuccess { list ->
                    feed = list.map { it.toCard() }
                }
                .onFailure { error = friendly(it, tr("Error al cargar el feed")) }
            loading = false
            feedJob = null
        }
    }

    /** Al acercarte al final del feed se precargan más vídeos (scroll infinito). */
    fun loadMoreFeed() {
        if (feedMoreLoading || loading || feed.isEmpty()) return
        feedMoreLoading = true
        viewModelScope.launch {
            var tries = 0
            var added = 0
            while (added == 0 && tries < 2) {
                tries++
                val more = runCatching { YouTubeRepository.feed(nextFeedSeeds()) }
                    .getOrDefault(emptyList()).map { it.toCard() }
                val known = feed.map { it.url }.toHashSet()
                val fresh = more.filter { it.url !in known }
                if (fresh.isNotEmpty()) {
                    feed = feed + fresh
                    added = fresh.size
                }
            }
            feedMoreLoading = false
        }
    }

    fun loadLive() {
        viewModelScope.launch {
            liveLoading = true
            liveError = null
            runCatching { YouTubeRepository.live() }
                .onSuccess { list ->
                    liveList = list.map { it.toCard() }
                    if (liveList.isEmpty()) liveError = tr("No se encontraron directos ahora mismo")
                }
                .onFailure { liveError = friendly(it, tr("Error al cargar los directos")) }
            liveLoading = false
        }
    }

    /** Novedades: los últimos vídeos de tus suscripciones, intercalados canal por canal. */
    fun loadNews() {
        if (usePipedFeed && pipedAccountConnected) {
            viewModelScope.launch {
                newsLoading = true
                newsError = null
                try {
                    val token = pipedTokenStore.token()
                        ?: error("No se encontró la sesión Piped guardada. Vuelve a iniciar sesión.")
                    val videos = PipedRepository.accountFeed(pipedApiUrl, token)
                    news = videos
                    prefetchLikely(videos)
                    if (videos.isEmpty()) newsError = tr("El feed Piped no tiene videos nuevos.")
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    newsError = trf("No se pudo cargar el feed Piped: %s", error.message)
                } finally {
                    newsLoading = false
                }
            }
            return
        }
        val subs = library.subscriptions().take(20)
        if (subs.isEmpty()) {
            news = emptyList()
            newsError = tr("Suscríbete a algunos canales y aquí verás sus últimos vídeos.")
            return
        }
        viewModelScope.launch {
            newsLoading = true
            newsError = null
            val lists = coroutineScope {
                subs.map { s ->
                    async { runCatching { YouTubeRepository.channelLatest(s.url, 4) }.getOrDefault(emptyList()) }
                }.map { it.await() }
            }
            val mixed = ArrayList<VideoCard>()
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) lists.forEach { l -> l.getOrNull(i)?.let { mixed.add(it.toCard()) } }
            news = mixed.distinctBy { it.url }
            if (news.isEmpty()) newsError = tr("No se pudieron cargar las novedades ahora mismo")
            newsLoading = false
        }
    }

    fun loadTrending() {
        viewModelScope.launch {
            trendingLoading = true
            trendingError = null
            runCatching { YouTubeRepository.trending(trendingKind) }
                .onSuccess { list ->
                    trending = list.map { it.toCard() }
                    if (trending.isEmpty()) trendingError = tr("No se encontraron vídeos ahora mismo")
                }
                .onFailure { trendingError = friendly(it, tr("Error al cargar las tendencias")) }
            trendingLoading = false
        }
    }

    private fun nextShortQueries(): List<String> {
        val own = library.history().map { it.channel }.filter { it.isNotBlank() }.distinct()
            .take(2).map { "$it shorts" }
        val topics = ArrayList<String>(own)
        repeat(4) { topics += shortTopics[shortTopicIndex++ % shortTopics.size] }
        return topics
    }

    /** reset = true: lote nuevo ("Actualizar"); false: añade más al final (infinito). */
    fun loadShorts(reset: Boolean) {
        if (shortsLoading) return
        viewModelScope.launch {
            shortsLoading = true
            shortsError = null
            runCatching { YouTubeRepository.shortsFor(nextShortQueries()) }
                .onSuccess { list ->
                    val added = list.map { it.toCard() }
                    shorts = if (reset) added else (shorts + added).distinctBy { it.url }
                    // Deja listos los primeros shorts para que el visor no espere a extraerlos al abrir.
                    prefetchLikely(if (reset) added else added.take(2))
                    if (shorts.isEmpty()) shortsError = tr("No se encontraron shorts ahora mismo")
                    // Si el visor está abierto, sigue mostrando la lista ampliada
                    shortsSession?.let { if (it.canLoadMore && !reset) shortsSession = it.copy(items = shorts) }
                }
                .onFailure { shortsError = friendly(it, tr("Error al cargar los shorts")) }
            shortsLoading = false
        }
    }

    fun loadMoreShorts() = loadShorts(reset = false)

    /** Mantiene listos como mÃ¡ximo dos candidatos; no descarga el contenido del vÃ­deo. */
    private fun prefetchLikely(cards: List<VideoCard>) {
        if (dataSaverEnabled) return
        cards.asSequence()
            .filter { !it.isLive && it.url.isNotBlank() }
            .take(2)
            .forEach { YouTubeRepository.prefetch(it.url) }
    }

    private var searchCursor: YouTubeRepository.SearchCursor? = null
    var resultsMoreLoading by mutableStateOf(false)

    /** Al llegar al final de los resultados se piden más páginas, hasta que se acaben. */
    fun loadMoreResults() {
        val cursor = searchCursor ?: return
        if (resultsMoreLoading || loading || !cursor.hasMore || results.isEmpty()) return
        resultsMoreLoading = true
        viewModelScope.launch {
            var tries = 0
            var added = 0
            while (added == 0 && tries < 3 && cursor.hasMore) {
                tries++
                val more = runCatching { cursor.next() }.getOrDefault(emptyList()).map { it.toCard() }
                if (searchCursor !== cursor) break // se hizo otra búsqueda mientras tanto
                val known = results.map { it.url }.toHashSet()
                val fresh = more.filter { it.url !in known }
                if (fresh.isNotEmpty()) {
                    results = results + fresh
                    prefetchLikely(fresh)
                    added = fresh.size
                }
            }
            resultsMoreLoading = false
        }
    }

    /** Busca vídeos y canales a la vez: si escribes el nombre de una persona sale su canal. */
    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        searchCompleted = false
        results = emptyList()
        channelResults = emptyList()
        resultsMoreLoading = false
        library.addSearch(q)
        searchHistory = library.searches()
        suggestJob?.cancel()
        suggestions = emptyList()
        viewModelScope.launch {
            loading = true
            error = null
            runCatching {
                coroutineScope {
                    val chosenService = serviceId
                    val chosenFilter = searchFilter
                    val cursor = YouTubeRepository.SearchCursor(q, chosenService, chosenFilter)
                    searchCursor = cursor
                    val videos = async { cursor.next() }
                    // Los canales de YouTube se buscan aparte del filtro de videos/audio y del
                    // servicio elegido para resultados multimedia; así siguen apareciendo al
                    // buscar nombres de creadores aunque esté seleccionado SoundCloud u otro.
                    val channels = async {
                        runCatching {
                            YouTubeRepository.searchChannels(q, org.schabi.newpipe.extractor.ServiceList.YouTube.serviceId)
                        }.getOrDefault(emptyList())
                    }
                    videos.await() to channels.await()
                }
            }
                .onSuccess { (videos, channels) ->
                if (query.trim() == q) {
                    results = videos.map { it.toCard() }
                    prefetchLikely(results)
                    channelResults = channels.map { it.toCard() }.take(3)
                    searchCompleted = true
                }
                }
                .onFailure {
                if (query.trim() == q) {
                    error = friendly(it, tr("Error al buscar"))
                    searchCompleted = true
                }
                }
            if (query.trim() == q) loading = false
        }
    }

    // ---------- Reproducción ----------

    /** Abre un vídeo. Mientras se prepara se ve una pantalla de carga con la miniatura. */
    fun open(card: VideoCard, fromQueue: Boolean = false) {
        if (!card.isLive && card.url.isNotBlank()) {
            YouTubeRepository.prefetch(card.url)
        }
        if (offline != null) closeOffline()
        cancelAutoplayNext()
        openJob?.cancel()
        // Al avanzar por la cola no se tapa la pantalla ni se saca el mini reproductor
        if (!fromQueue) opening = card
        // Corta el audio del video actual al instante, mientras NewPipeExtractor obtiene
        // los streams del siguiente. Así no se oye el anterior durante la transición.
        saveProgress()
        player.pause()
        audioOnly = false
        openJob = viewModelScope.launch {
            error = null
            runCatching { YouTubeRepository.streamInfo(card.url) }
                .onSuccess { info ->
                    val resumeAt = if (isLiveInfo(info)) 0L else library.progress(info.url)
                    playbackError = null
                    downloadMessage = null
                    loop = false
                    vote = 0
                    videoOptions = videoOptionsOf(info)
                    audioOptions = audioOptionsOf(info)
                    // Empieza en la calidad que elegiste la última vez (720p la primera): arranca antes
                    val preferred = settings.getInt("pref_height", 720)
                    selVideo = videoOptions.firstOrNull { it.height <= preferred } ?: videoOptions.lastOrNull()
                    selAudio = audioOptions.firstOrNull()
                    // Subtítulos: se recuerda el último idioma elegido
                    subtitleOptions = subtitleOptionsOf(info)
                    val prefLang = settings.getString("subtitle_lang", "") ?: ""
                    selSubtitle = if (prefLang.isEmpty()) null
                    else subtitleOptions.firstOrNull { it.languageTag == prefLang && !it.isAutoGenerated }
                        ?: subtitleOptions.firstOrNull { it.languageTag == prefLang }
                    val connectedFireTv = fireTvTarget.takeIf { fireTvCompanionConnected != null }
                    if (connectedFireTv == null) fireTvCompanionConnected = null
                    current = info
                    audioOnly = false
                    if (!fromQueue) expanded = true
                    // 1) el vídeo, lo primero
                    val shouldAutoPlay = fromQueue || autoplayOnOpenEnabled
                    if (connectedFireTv != null) {
                        applySource(keepPosition = false, startAt = resumeAt, playWhenReady = false)
                        launchFireTvCompanion(connectedFireTv) {
                            FireTvProgressService.stop(appContext)
                            fireTvTarget = null
                            fireTvCompanionConnected = null
                            player.playWhenReady = shouldAutoPlay
                            syncPlaying()
                        }
                    } else {
                        applySource(keepPosition = false, startAt = resumeAt, playWhenReady = shouldAutoPlay)
                    }
                    dlnaFellBack = false
                    if (shouldAutoPlay && castSessionActive()) castCurrent(info, resumeAt)
                    else if (shouldAutoPlay && casting && dlna != null) sendToDlna(info, resumeAt, fallback = false)
                    loadDislikeCount(info)
                    // 2) lo demás, ya con el vídeo en marcha
                    loadSubtitleCues(selSubtitle)
                    recordWatched(info)
                    resetComments(info.url) // los comentarios se piden solo si están abiertos
                    loadRelated(info)
                    loadSponsors(info)
                }
                .onFailure { if (it !is CancellationException) error = friendly(it, tr("Error al cargar el vídeo")) }
            if (opening == card) opening = null
        }
    }

    private fun loadDislikeCount(info: StreamInfo) {
        dislikeJob?.cancel()
        dislikeJob = null
        dislikeCount = null
        dislikeLoading = false
        dislikeError = null
        if (info.service.serviceId != org.schabi.newpipe.extractor.ServiceList.YouTube.serviceId) return

        dislikeLoading = true
        val videoId = info.id
        dislikeJob = viewModelScope.launch {
            try {
                val count = YouTubeRepository.dislikeCount(videoId)
                if (current?.id == videoId) {
                    dislikeCount = count
                    dislikeError = if (count == null) tr("Dislikes no disponibles") else null
                    dislikeLoading = false
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current?.id == videoId) {
                    dislikeCount = null
                    dislikeError = tr("No se pudieron cargar los dislikes")
                    dislikeLoading = false
                }
            }
        }
    }

    fun cancelOpen() {
        openJob?.cancel()
        opening = null
    }

    // Recomendados sin fin: tras los de YouTube se siguen pidiendo vídeos parecidos (por título y por canal)
    private var relatedCursors: List<YouTubeRepository.SearchCursor> = emptyList()
    var relatedMoreLoading by mutableStateOf(false)

    fun loadMoreRelated() {
        val info = current ?: return
        if (!relatedContentEnabled || relatedMoreLoading || related.size >= 150) return
        val cursor = relatedCursors.firstOrNull { it.hasMore } ?: return
        relatedMoreLoading = true
        viewModelScope.launch {
            val more = runCatching { cursor.next() }.getOrDefault(emptyList())
                .map { it.toCard() }.filter { it.url != info.url }
            if (current?.url == info.url) {
                if (!relatedContentEnabled) {
                    relatedMoreLoading = false
                    return@launch
                }
                val known = related.map { it.url }.toHashSet()
                related = related + more.filter { it.url !in known }
            }
            relatedMoreLoading = false
        }
    }

    private fun loadRelated(info: StreamInfo) {
        relatedMoreLoading = false
        if (!relatedContentEnabled) {
            related = emptyList()
            relatedCursors = emptyList()
            return
        }
        relatedCursors = listOfNotNull(info.name, info.uploaderName)
            .filter { it.isNotBlank() }
            .map { YouTubeRepository.SearchCursor(it, YouTubeRepository.serviceIdForUrl(info.url)) }
        val own = info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toCard() }
            .filter { it.url != info.url }
        related = own
        if (own.isEmpty()) {
            // Respaldo: buscar por el título para mostrar contenido parecido
            viewModelScope.launch {
                runCatching { YouTubeRepository.search(info.name ?: "") }
                    .onSuccess { list ->
                        if (current?.url == info.url && relatedContentEnabled) {
                            related = list.map { it.toCard() }.filter { it.url != info.url }
                        }
                    }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun dataSourceFactory(): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(DownloaderImpl.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        return if (fastDownload) ChunkedYoutubeDataSourceFactory(http) else http
    }

    private fun applyVideoTrackState() {
        // En modo música también se desactiva la pista de vídeo (por si el flujo la trae junta)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
            .build()
    }

    private fun applySource(
        keepPosition: Boolean,
        startAt: Long = 0L,
        playWhenReady: Boolean = player.playWhenReady
    ) {
        val info = current ?: return
        val position = if (keepPosition) player.currentPosition else startAt

        hlsMode = usesHls(info, selVideo, selAudio)
        if (hlsMode && !keepPosition) {
            hlsHeights = emptyList()
            hlsSelected = null
            player.trackSelectionParameters =
                player.trackSelectionParameters.buildUpon().clearVideoSizeConstraints().build()
        }
        applyVideoTrackState()

        runCatching { buildSource(info, selVideo, selAudio, dataSourceFactory(), audioOnly) }
            .onSuccess {
                player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                player.setMediaSource(it, position)
                player.prepare()
                player.playWhenReady = playWhenReady
            }
            .onFailure { playbackError = it.message }
    }

    /** Modo música: solo audio, ahorra datos y sigue sonando al salir de la app (con notificación). */
    fun toggleAudioOnly() {
        audioOnly = !audioOnly
        if (current != null) applySource(keepPosition = true)
    }

    fun toggleShuffleMusicQueue() {
        shuffleMusicQueue = !shuffleMusicQueue
        settings.edit().putBoolean("shuffle_music_queue", shuffleMusicQueue).apply()
    }

    fun toggleRepeatMusicQueue() {
        repeatMusicQueue = !repeatMusicQueue
        settings.edit().putBoolean("repeat_music_queue", repeatMusicQueue).apply()
    }

    fun skipPreviousTrack() {
        val active = activePlayer
        if (active.currentPosition > 5_000L) {
            active.seekTo(0L)
            return
        }
        val currentUrl = current?.url ?: return
        val history = library.history()
        val currentIndex = history.indexOfFirst { it.url == currentUrl }
        val previous = if (currentIndex >= 0) {
            history.drop(currentIndex + 1).firstOrNull { it.url != currentUrl }
        } else {
            history.firstOrNull { it.url != currentUrl }
        }
        previous?.let { open(it, fromQueue = true) }
    }

    fun skipNextTrack() {
        if (playNextInQueue(force = true)) return
        val currentUrl = current?.url ?: return
        val history = library.history()
        val historyIndex = history.indexOfFirst { it.url == currentUrl }
        val nextInHistory = historyIndex.takeIf { it > 0 }?.let { history[it - 1] }
        if (nextInHistory != null) {
            open(nextInHistory, fromQueue = true)
            return
        }
        val candidates = related.filter { it.url != currentUrl && !it.isLive }
        val next = if (shuffleMusicQueue) candidates.randomOrNull() else candidates.firstOrNull()
        next?.let { open(it, fromQueue = true) }
    }

    // ---------- Cola de reproducción ----------

    /** Añade un vídeo al final de la cola (o justo después del actual). Si no hay nada sonando, lo reproduce. */
    fun addToQueue(card: VideoCard, next: Boolean) {
        if (current == null && opening == null && offline == null && shortsSession == null) {
            toast(tr("No había nada sonando: se reproduce ahora"))
            open(card)
            return
        }
        val rest = queue.filter { it.url != card.url }
        queue = if (next) listOf(card) + rest else rest + card
        toast(if (next) tr("Se reproducirá a continuación") else tr("Añadido a la cola"))
        if (player.playbackState == Player.STATE_ENDED) {
            cancelAutoplayNext()
            playNextInQueue()
        }
    }

    fun removeFromQueue(card: VideoCard) {
        queue = queue.filter { it.url != card.url }
    }

    fun clearQueue() {
        queue = emptyList()
    }

    private fun playNextInQueue(force: Boolean = false): Boolean {
        if (current == null || shortsSession != null || offline != null || (loop && !force)) return false
        if (queue.isEmpty()) return false
        val nextIndex = if (audioOnly && shuffleMusicQueue) queue.indices.random() else 0
        val next = queue[nextIndex]
        queue = queue.filterIndexed { index, _ -> index != nextIndex }
        open(next, fromQueue = true)
        return true
    }

    private fun handlePlaybackEnded() {
        if (sleepAfterCurrent) {
            val shouldStop =
                (sleepAfterCurrentUrl != null && current?.url == sleepAfterCurrentUrl) ||
                    (sleepAfterOfflineFileName != null && offline?.fileName == sleepAfterOfflineFileName)
            armSleepAfterCurrent(false)
            if (shouldStop) {
                activePlayer.pause()
                return
            }
        }
        if (offline?.audioOnly == true) {
            skipOfflineNextTrack()
            return
        }
        if (audioOnly && repeatMusicQueue) {
            current?.toCard()?.let { track ->
                queue = queue.filterNot { it.url == track.url } + track
            }
        }
        if (!playNextInQueue()) scheduleAutoplayNext()
    }

    private fun scheduleAutoplayNext() {
        if (!autoplayNextEnabled || current == null || shortsSession != null || offline != null || loop) return
        val endedUrl = current?.url ?: return
        val candidates = related.filter { it.url != endedUrl && !it.isLive }
        val next = if (audioOnly && shuffleMusicQueue) candidates.randomOrNull() else candidates.firstOrNull()
        next ?: return
        cancelAutoplayNext()
        if (!autoplayCountdownEnabled) {
            open(next, fromQueue = true)
            return
        }
        autoplayNextCard = next
        autoplayCountdown = 8
        autoplayJob = viewModelScope.launch {
            while (autoplayCountdown > 0) {
                delay(1_000)
                if (!autoplayNextEnabled || autoplayNextCard?.url != next.url) return@launch
                autoplayCountdown--
            }
            autoplayJob = null
            autoplayNextCard = null
            if (current?.url != endedUrl || player.playbackState != Player.STATE_ENDED) return@launch
            if (!playNextInQueue()) open(next, fromQueue = true)
        }
    }

    fun toggleAutoplayNext() {
        autoplayNextEnabled = !autoplayNextEnabled
        settings.edit().putBoolean("autoplay_next", autoplayNextEnabled).apply()
        if (!autoplayNextEnabled) cancelAutoplayNext()
    }

    fun playAutoplayNextNow() {
        val next = autoplayNextCard ?: return
        cancelAutoplayNext()
        open(next, fromQueue = true)
    }

    fun cancelAutoplayNext() {
        autoplayJob?.cancel()
        autoplayJob = null
        autoplayCountdown = 0
        autoplayNextCard = null
    }

    private fun toast(text: String) {
        Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
    }

    /** Repetir: al terminar vuelve a empezar solo. */
    fun toggleLoop() {
        loop = !loop
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun selectSubtitle(s: SubtitlesStream?) {
        selSubtitle = s
        settings.edit().putString("subtitle_lang", s?.languageTag ?: "").apply()
        loadSubtitleCues(s)
    }

    /** Descarga y prepara los subtítulos elegidos (null = sin subtítulos). */
    private fun loadSubtitleCues(s: SubtitlesStream?) {
        subtitleJob?.cancel()
        subtitleCues = emptyList()
        subtitleText = null
        subtitleError = null
        if (s == null) return
        subtitleJob = viewModelScope.launch {
            subtitleLoading = true
            runCatching { SubtitleLoader.load(s) }
                .onSuccess {
                    subtitleCues = it
                    if (it.isEmpty()) subtitleError = tr("No se pudieron leer los subtítulos")
                }
                .onFailure { if (it !is CancellationException) subtitleError = tr("No se pudieron cargar los subtítulos") }
            subtitleLoading = false
        }
    }

    private fun updateSubtitle() {
        if (subtitleCues.isEmpty()) {
            if (subtitleText != null) subtitleText = null
            return
        }
        val text = cueAt(subtitleCues, activePlayer.currentPosition)
        if (text != subtitleText) subtitleText = text
    }

    // ---------- Transmitir a la TV (Google Cast) ----------

    fun openCastSheet() {
        castMessage = null
        showCastSheet = true
    }

    fun closeCastSheet() {
        showCastSheet = false
        clearFireTvPairingCode()
    }

    fun disconnectCast() {
        if (dlna != null) {
            stopDlna(returnToPhone = true)
            return
        }
        castContext?.sessionManager?.endCurrentSession(true)
    }

    @OptIn(UnstableApi::class)
    private fun initCast() {
        val context = runCatching { CastContext.getSharedInstance(appContext) }.getOrNull() ?: return
        castContext = context
        val cp = CastPlayer(context, LiveAwareConverter())
        cp.addListener(playerListener)
        cp.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = startCasting()
            override fun onCastSessionUnavailable() = stopCasting()
        })
        castPlayer = cp
    }

    @OptIn(UnstableApi::class)
    private fun releaseCast() {
        stopDlna(returnToPhone = false)
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        castPlayer = null
        CastProxy.stop()
    }

    @OptIn(UnstableApi::class)
    private fun castSessionActive(): Boolean = castPlayer?.isCastSessionAvailable == true

    @OptIn(UnstableApi::class)
    private fun stopCastPlayback() {
        if (!casting) return
        if (dlna != null) {
            dlna?.stop()
            return
        }
        castPlayer?.stop()
        castPlayer?.clearMediaItems()
    }

    private fun startCasting() {
        stopDlna(returnToPhone = false)
        dialOnlyDevice = null
        castDeviceName = castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName ?: tr("la TV")
        val info = current
        if (info == null) {
            casting = true
            castMessage = trf("Conectado a %s. Abre un vídeo para verlo en la tele.", castDeviceName)
            syncPlaying()
            return
        }
        castCurrent(info, player.currentPosition)
    }

    /**
     * Envía el vídeo actual a la TV. Normal: el móvil sirve un manifiesto DASH con la calidad elegida
     * (o la lista HLS de un directo) y reenvía los trozos. Si eso falla, se manda el archivo con
     * vídeo y audio juntos directamente (calidad más baja).
     */
    @OptIn(UnstableApi::class)
    private fun castCurrent(
        info: StreamInfo, position: Long, direct: Boolean = false,
        probed: Boolean = false, fmp4: Boolean = false
    ) {
        val cp = castPlayer ?: return
        castDirect = direct
        if (!direct && !probed) castFellBack = false
        val live = isLiveInfo(info)

        // Directo: antes de enviarlo se mira si usa segmentos fMP4, que la TV necesita saber
        if (!direct && !probed && live && !info.hlsUrl.isNullOrEmpty()) {
            casting = true
            castMessage = null
            castDetail = tr("Preparando el directo…")
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) {
                    CastProxy.start(appContext)
                    CastProxy.probeHlsFmp4(info.hlsUrl)
                }
                if (casting && dlna == null) castCurrent(info, position, direct = false, probed = true, fmp4 = result == true)
            }
            return
        }
        var url: String? = null
        var mime = MimeTypes.VIDEO_MP4

        if (!direct && CastProxy.start(appContext) != null) {
            if (live && !info.hlsUrl.isNullOrEmpty()) {
                url = CastProxy.proxied(info.hlsUrl)
                mime = MimeTypes.APPLICATION_M3U8
                castDetail = tr("Modo: lista HLS del directo") + if (fmp4) " (fMP4)" else " (TS)"
            } else if (!live) {
                val v = selVideo
                val a = selAudio
                val mpd = if (v != null && a != null) DashManifest.build(info, v, a) else null
                if (mpd != null) {
                    CastProxy.setManifest(mpd)
                    url = CastProxy.manifestUrl()
                    mime = MimeTypes.APPLICATION_MPD
                    castDetail = trf("Modo: manifiesto DASH (%s)", v?.label() ?: "")
                }
            }
        }

        // Un directo solo se puede enviar por el servidor del móvil
        if (url == null && live) {
            casting = true
            castDetail = ""
            castMessage = if (direct) tr("La TV no pudo reproducir este directo.")
            else tr("No se pudo preparar la transmisión. Comprueba que el móvil está conectado a la Wi-Fi.")
            return
        }

        if (url == null) {
            castDetail = tr("Modo directo: calidad baja")
            val progressive = info.videoStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            val muxed = progressive.filter { it.format == MediaFormat.MPEG_4 }.ifEmpty { progressive }
                .maxByOrNull { it.height }
            when {
                muxed != null -> { url = muxed.content; mime = MimeTypes.VIDEO_MP4 }
                !info.hlsUrl.isNullOrEmpty() -> { url = info.hlsUrl; mime = MimeTypes.APPLICATION_M3U8 }
            }
        }
        if (url == null) {
            casting = true
            castMessage = tr("Este vídeo no se puede enviar a la TV.")
            return
        }

        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(mime)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(info.name)
                    .setArtist(info.uploaderName)
                    .setArtworkUri(info.thumbnails.firstOrNull()?.url?.let { Uri.parse(it) })
                    .setExtras(Bundle().apply {
                        putBoolean("live", live)
                        putBoolean("hlsFmp4", fmp4)
                    })
                    .build()
            )
            .build()
        player.pause()
        casting = true
        castMessage = null
        cp.setMediaItem(item, if (live) C.TIME_UNSET else position)
        cp.prepare()
        cp.playWhenReady = true
        syncPlaying()
    }

    /** Lo que dice el receptor de Google Cast (reproduciendo, cargando, error...). */
    private fun receiverStatus(): String {
        val status = castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.mediaStatus ?: return ""
        val text = when (status.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> tr("reproduciendo")
            MediaStatus.PLAYER_STATE_PAUSED -> tr("en pausa")
            MediaStatus.PLAYER_STATE_BUFFERING -> tr("cargando")
            MediaStatus.PLAYER_STATE_LOADING -> tr("preparando")
            MediaStatus.PLAYER_STATE_IDLE -> when (status.idleReason) {
                MediaStatus.IDLE_REASON_ERROR -> tr("ERROR al reproducir")
                MediaStatus.IDLE_REASON_FINISHED -> tr("terminó")
                MediaStatus.IDLE_REASON_CANCELED -> tr("cancelado")
                MediaStatus.IDLE_REASON_INTERRUPTED -> tr("interrumpido")
                else -> tr("inactiva")
            }
            else -> return ""
        }
        return trf("Estado de la TV: %s", text)
    }

    // ---------- Fire TV y TVs DLNA ----------

    /** Vuelve a enviar el vídeo a la TV (cambio de calidad o de audio), sea Google Cast o DLNA. */
    private fun resendToTv(info: StreamInfo, position: Long) {
        if (dlna != null) sendToDlna(info, position, fallback = false)
        else castCurrent(info, position)
    }

    /** Elegir en la lista un Fire TV o una TV DLNA. */
    @OptIn(UnstableApi::class)
    fun castToDevice(device: RemoteDevice) {
        if (!device.canPlayUrl) {
            // Fire TV sin receptor DLNA: se muestran las opciones
            dialOnlyDevice = device
            castMessage = null
            return
        }
        dialOnlyDevice = null
        if (castSessionActive()) castContext?.sessionManager?.endCurrentSession(true)
        stopDlna(returnToPhone = false)
        val d = DlnaPlayer(device)
        d.addListener(playerListener)
        dlna = d
        dlnaFellBack = false
        castDeviceName = device.name
        casting = true
        showCastSheet = false
        val info = current
        if (info == null) {
            castMessage = trf("Conectado a %s. Abre un vídeo para verlo en la tele.", device.name)
            syncPlaying()
            return
        }
        sendToDlna(info, player.currentPosition, fallback = false)
    }

    @OptIn(UnstableApi::class)
    private fun stopDlna(returnToPhone: Boolean) {
        val d = dlna ?: return
        val position = d.currentPosition
        d.removeListener(playerListener)
        d.release()
        dlna = null
        if (returnToPhone && casting) {
            casting = false
            castMessage = null
            castDetail = ""
            player.seekTo(position)
            syncPlaying()
        }
    }

    /** Fire TV sin receptor DLNA: abre el vídeo en la app oficial de YouTube del aparato (lleva anuncios). */
    fun openOnDeviceYouTube(device: RemoteDevice) {
        val info = current ?: run {
            castMessage = tr("Abre primero un vídeo.")
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { Upnp.launchYouTube(device, info.id) }
            castMessage = if (ok) trf("Abierto en la app de YouTube de %s", device.name)
            else tr("No se pudo abrir YouTube en ese aparato.")
            if (ok) {
                player.pause()
                showCastSheet = false
            }
        }
    }

    fun launchFireTvCompanion(device: RemoteDevice) = launchFireTvCompanion(device, null)

    private fun launchFireTvCompanion(device: RemoteDevice, onFailure: (() -> Unit)?) {
        val sessionToken = fireTvPairingToken(device)
        val code = fireTvPairingCode.takeIf { it.length == 6 }
        if (sessionToken == null && code == null) {
            onFailure?.invoke()
            castMessage = tr("Introduce un código de 6 dígitos en Transmitir a.")
            return
        }

        val info = current
        if (info != null && !isLiveInfo(info)) saveProgress()
        val resumePosition = info?.takeIf { !isLiveInfo(it) }?.let { library.progress(it.url) } ?: 0L
        val progressToken = info?.takeIf { !isLiveInfo(it) }?.let {
            UUID.randomUUID().toString().replace("-", "")
        }
        val progressCallbackUrl = progressToken?.let { FireTvProgressService.callbackUrl(appContext) }
        if (progressToken != null && progressCallbackUrl == null) {
            onFailure?.invoke()
            castMessage = tr("No se pudo encontrar la dirección Wi-Fi del celular para sincronizar el progreso.")
            return
        }
        val fastHlsUrl = info?.takeIf { hlsMode }?.hlsUrl
        val fastVideoUrl = if (fastHlsUrl == null) selVideo?.content else null
        val fastAudioUrl = if (fastVideoUrl != null) selAudio?.content else null
        val fastQuality = if (fastVideoUrl != null) selVideo?.height?.let { "${it}p" } else null
        viewModelScope.launch {
            castMessage = tr("Conectando con RebornTube para Fire TV...")
            val result = try {
                withContext(Dispatchers.IO) {
                    Upnp.launchRebornTube(
                        device, code, sessionToken, info, resumePosition,
                        progressCallbackUrl, progressToken,
                        fastVideoUrl, fastAudioUrl, fastHlsUrl, fastQuality
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: FireTvSessionTokenRejectedException) {
                removeFireTvPairingToken(device)
                onFailure?.invoke()
                castMessage = tr("La vinculación con Fire TV ya no es válida. Ingresa el código actual que muestra el televisor.")
                return@launch
            } catch (error: Exception) {
                onFailure?.invoke()
                castMessage = friendly(error, tr("No se pudo conectar con el Fire TV."))
                return@launch
            }

            if (sessionToken == null) clearFireTvPairingCode()
            val returnedToken = result.sessionToken
            if (returnedToken != null) {
                saveFireTvPairingToken(device, returnedToken)
            } else if (sessionToken == null) {
                castMessage = tr("El Fire TV recibió el envío, pero no devolvió un vínculo reutilizable. Actualiza RebornTube TV y vuelve a vincular.")
                return@launch
            }

            if (info != null && progressToken != null) {
                FireTvProgressService.start(appContext, progressToken, info.url)
            } else if (info != null) {
                FireTvProgressService.stop(appContext)
            }
            player.pause()
            fireTvTarget = device
            fireTvCompanionConnected = device.name
            castMessage = trf("Conectado en el televisor %s mediante RebornTube TV.", device.name)
            closeCastSheet()
            syncPlaying()
        }
    }

    /** Manda el vídeo actual a un receptor DLNA: DASH con la calidad elegida, o el archivo normal si no puede. */
    @OptIn(UnstableApi::class)
    private fun sendToDlna(info: StreamInfo, position: Long, fallback: Boolean) {
        val d = dlna ?: return
        val live = isLiveInfo(info)
        if (CastProxy.start(appContext) == null) {
            castMessage = tr("No se pudo preparar la transmisión. Comprueba que el móvil está conectado a la Wi-Fi.")
            return
        }
        var url: String? = null
        var mime = MimeTypes.VIDEO_MP4
        if (live) {
            if (!info.hlsUrl.isNullOrEmpty()) {
                url = CastProxy.hlsUrl(info.hlsUrl)
                mime = "application/vnd.apple.mpegurl"
                castDetail = tr("Modo: lista HLS del directo")
            }
        } else {
            val v = selVideo
            val a = selAudio
            val mpd = if (!fallback && v != null && a != null) DashManifest.build(info, v, a) else null
            if (mpd != null) {
                CastProxy.setManifest(mpd)
                url = CastProxy.manifestUrl()
                mime = MimeTypes.APPLICATION_MPD
                castDetail = trf("Modo: manifiesto DASH (%s)", v?.label() ?: "")
            } else {
                val progressive = info.videoStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                val muxed = progressive.filter { it.format == MediaFormat.MPEG_4 }.ifEmpty { progressive }
                    .maxByOrNull { it.height }
                if (muxed != null) {
                    url = CastProxy.proxied(muxed.content)
                    castDetail = tr("Modo directo: calidad baja")
                }
            }
        }
        if (url == null) {
            castMessage = tr("Este vídeo no se puede enviar a la TV.")
            return
        }
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(mime)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(info.name)
                    .setArtist(info.uploaderName)
                    .setExtras(Bundle().apply {
                        putBoolean("live", live)
                        putLong("durationMs", info.duration * 1000)
                    })
                    .build()
            )
            .build()
        player.pause()
        casting = true
        castMessage = null
        d.setMediaItem(item, if (live) C.TIME_UNSET else position)
        d.prepare()
        d.playWhenReady = true
        syncPlaying()
    }

    /** Al desconectar la TV, el vídeo queda en pausa en el móvil, por donde iba. */
    private fun stopCasting() {
        if (!casting || dlna != null) return
        val position = castPlayer?.currentPosition ?: 0L
        casting = false
        castMessage = null
        castDetail = ""
        player.seekTo(position)
        syncPlaying()
    }

    fun selectVideo(v: VideoStream) {
        val previous = selVideo
        selVideo = v
        settings.edit().putInt("pref_height", v.height).apply()
        if (casting) {
            current?.let { resendToTv(it, activePlayer.currentPosition) }
            return
        }
        applySource(keepPosition = true)
        watchSwitch { selVideo = previous }
    }

    fun selectAudio(a: AudioStream) {
        val previous = selAudio
        selAudio = a
        if (casting) {
            current?.let { resendToTv(it, activePlayer.currentPosition) }
            return
        }
        applySource(keepPosition = true)
        watchSwitch { selAudio = previous }
    }

    /** Si tras cambiar calidad o audio se queda cargando 15 s, vuelve a lo anterior. */
    private fun watchSwitch(revert: () -> Unit) {
        switchJob?.cancel()
        switchJob = viewModelScope.launch {
            delay(15_000)
            if (player.playbackState == Player.STATE_BUFFERING) {
                revert()
                applySource(keepPosition = true)
                playbackError = tr("Esa opción no cargó; se restauró la anterior")
            }
        }
    }

    /** Calidad en directos (HLS): limita la altura máxima; null = automática. */
    fun selectHlsHeight(height: Int?) {
        hlsSelected = height
        val builder = player.trackSelectionParameters.buildUpon()
        if (height == null) builder.clearVideoSizeConstraints() else builder.setMaxVideoSize(Int.MAX_VALUE, height)
        player.trackSelectionParameters = builder.build()
    }

    fun setSpeed(value: Float) {
        speed = value
        settings.edit().putFloat("playback_speed", value).apply()
        player.setPlaybackSpeed(value)
    }

    fun chooseSeekInterval(seconds: Int) {
        require(seconds in listOf(5, 10, 15, 30))
        seekIntervalSeconds = seconds
        settings.edit().putInt("seek_interval_seconds", seconds).apply()
    }

    fun setSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepAfterCurrent = false
        sleepAfterCurrentUrl = null
        sleepAfterOfflineFileName = null

        val durationMs = minutes?.takeIf { it > 0 }?.times(60_000L)
        if (durationMs == null) {
            sleepTimerRemainingMs = 0L
            return
        }

        val deadline = SystemClock.elapsedRealtime() + durationMs
        sleepTimerJob = viewModelScope.launch {
            while (true) {
                val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                sleepTimerRemainingMs = remaining
                if (remaining == 0L) break
                delay(minOf(1_000L, remaining))
            }
            sleepTimerJob = null
            activePlayer.pause()
        }
    }

    fun armSleepAfterCurrent(enabled: Boolean) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerRemainingMs = 0L
        sleepAfterCurrentUrl = if (enabled) current?.url else null
        sleepAfterOfflineFileName = if (enabled) offline?.fileName else null
        sleepAfterCurrent = enabled && (sleepAfterCurrentUrl != null || sleepAfterOfflineFileName != null)
    }

    fun togglePlay() {
        val p = activePlayer
        if (p.playWhenReady) p.pause() else p.play()
    }

    /** Guarda la posición para reanudar desde el historial. */
    fun saveProgress() {
        if (fireTvCompanionConnected != null) return
        val info = current ?: return
        val p = activePlayer
        if (isLiveInfo(info) || p.mediaItemCount == 0) return
        val duration = p.duration
        if (duration <= 0) return
        val position = p.currentPosition
        // Si ya casi terminó (o acaba de empezar), la próxima vez empieza desde el principio
        val value = if (position >= duration - 10_000 || position < 5_000) 0L else position
        library.setProgress(info.url, value)
        progress = if (value > 0) progress + (info.url to value) else progress - info.url
    }

    /** Fracción vista (0..1) para la barrita de las listas. */
    fun fractionOf(card: VideoCard): Float {
        val pos = progress[card.url] ?: return 0f
        if (card.duration <= 0 || pos <= 0) return 0f
        return (pos / 1000f / card.duration).coerceIn(0f, 1f)
    }

    // ---------- Sponsors ----------

    private fun loadSponsors(info: StreamInfo) {
        sponsorSegments = emptyList()
        sponsorBlockMessage = null
        if (sponsorCategories.isEmpty() || isLiveInfo(info) ||
            info.service.serviceId != org.schabi.newpipe.extractor.ServiceList.YouTube.serviceId
        ) return
        val id = info.id
        viewModelScope.launch {
            try {
                val segments = SponsorBlock.segments(id, sponsorCategories)
                if (current?.id == id) sponsorSegments = segments
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current?.id == id) {
                    sponsorBlockMessage = trf("No se pudieron cargar los segmentos: %s", error.message)
                }
            }
        }
    }

    private fun checkSponsors() {
        val p = activePlayer
        if (!skipSponsors || sponsorSegments.isEmpty() || current == null || !p.isPlaying) return
        val position = p.currentPosition
        val segment = sponsorSegments.firstOrNull {
            position >= it.startMs && position < it.endMs - 300
        } ?: return
        p.seekTo(segment.endMs)
        skippedMessage = tr("Sponsor omitido")
        viewModelScope.launch {
            delay(2500)
            skippedMessage = null
        }
    }

    // ---------- Mini reproductor ----------

    /** Atrás: el vídeo sigue sonando en un mini reproductor. */
    fun minimize() {
        fullscreen = false
        expanded = false
    }

    /** Apagar la pantalla devuelve la reproducción al mini reproductor sin detenerla. */
    fun minimizeForScreenOff() {
        if (current == null) return
        fullscreen = false
        expanded = false
        saveProgress()
    }

    fun handleScreenOff() {
        if (pauseOnScreenOff) {
            saveProgress()
            activePlayer.pause()
        } else if (current != null) {
            minimizeForScreenOff()
        } else if (offline?.audioOnly == true) {
            saveProgress()
        } else {
            pauseForLeaving()
        }
    }

    fun expand() { expanded = true }

    /** La X del mini reproductor: detiene y quita el vídeo. */
    fun closePlayer() {
        saveProgress()
        switchJob?.cancel()
        setSleepTimer(null)
        cancelAutoplayNext()
        dislikeJob?.cancel()
        dislikeJob = null
        dislikeCount = null
        dislikeLoading = false
        dislikeError = null
        stopCastPlayback()
        loadSubtitleCues(null)
        player.stop()
        player.clearMediaItems()
        current = null
        expanded = true
        fullscreen = false
        isPlaying = false
        comments = emptyList()
        related = emptyList()
        sponsorSegments = emptyList()
        videoOptions = emptyList()
        audioOptions = emptyList()
        subtitleOptions = emptyList()
        selSubtitle = null
        loop = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        hlsMode = false
    }

    // ---------- Shorts (visor vertical) ----------

    fun openShorts(items: List<VideoCard>, index: Int, canLoadMore: Boolean = false) {
        if (items.isEmpty()) return
        closePlayer() // detiene el vídeo normal
        shortPlayError = null
        shortInfo = null
        shortsSession = ShortsSession(items, index.coerceIn(0, items.size - 1), canLoadMore)
    }

    fun playShort(card: VideoCard) {
        shortJob?.cancel()
        if (current != null) closePlayer() // detiene el vídeo normal
        shortJob = viewModelScope.launch {
            shortPlayLoading = true
            shortPlayError = null
            comments = emptyList()
            runCatching { YouTubeRepository.streamInfo(card.url) }
                .onSuccess { info ->
                    shortInfo = info
                    shortVote = library.vote(info.url)
                    library.addHistory(info.toCard()) // los shorts vistos también van al historial
                    refreshLibrary()
                    applyShort(info)
                    loadComments(info.url)
                }
                .onFailure { if (it !is CancellationException) shortPlayError = friendly(it, tr("No se pudo cargar el short")) }
            shortPlayLoading = false
        }
    }

    /** Se llama al salir de la pestaña Shorts: detiene la reproducción. */
    fun stopShortsPlayback() {
        if (shortsSession != null) return // el visor de un canal se cierra con su propio botón
        shortJob?.cancel()
        shortInfo = null
        comments = emptyList()
        player.stop()
        player.clearMediaItems()
        player.repeatMode = Player.REPEAT_MODE_OFF
        isPlaying = false
    }

    private fun applyShort(info: StreamInfo) {
        val videos = videoOptionsOf(info)
        val video = videos.firstOrNull { it.height <= 1080 } ?: videos.firstOrNull()
        val audio = audioOptionsOf(info).firstOrNull()
        applyVideoTrackStateOff()
        runCatching { buildSource(info, video, audio, dataSourceFactory()) }
            .onSuccess {
                player.repeatMode = Player.REPEAT_MODE_ONE // los shorts se repiten
                player.setMediaSource(it)
                player.prepare()
                player.playWhenReady = true
            }
            .onFailure { shortPlayError = it.message }
    }

    private fun applyVideoTrackStateOff() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .build()
    }

    fun setShortVote(value: Int) {
        val info = shortInfo ?: return
        val newValue = if (shortVote == value) 0 else value
        shortVote = newValue
        library.setVote(info.toCard(), newValue)
        refreshLibrary()
    }

    fun closeShorts() {
        shortJob?.cancel()
        shortsSession = null
        shortInfo = null
        comments = emptyList()
        player.stop()
        player.clearMediaItems()
        player.repeatMode = Player.REPEAT_MODE_OFF
        isPlaying = false
    }

    // ---------- Like / dislike / más tarde / listas / suscripciones / historial (locales) ----------

    fun setVote(value: Int) {
        val info = current ?: return
        val newValue = if (vote == value) 0 else value
        vote = newValue
        library.setVote(info.toCard(), newValue)
        refreshLibrary()
    }

    /** Quita un vídeo de "Me gusta" o "No me gusta". */
    fun removeVote(card: VideoCard) {
        library.setVote(card, 0)
        if (current?.url == card.url) vote = 0
        refreshLibrary()
    }

    fun removeFromHistory(card: VideoCard) {
        library.removeHistory(card.url)
        refreshLibrary()
    }

    fun clearHistory() {
        library.clearHistory()
        refreshLibrary()
    }

    fun isLater(url: String) = laterList.any { it.url == url }

    fun openExternal(card: VideoCard) {
        openExternalUrl(card.url, tr("Abrir con otra app"))
    }

    fun openExternalUrl(url: String, chooserTitle: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching {
            appContext.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast(tr("No hay una app compatible para abrir este enlace")) }
    }

    fun toggleLater(card: VideoCard) {
        library.toggleLater(card)
        refreshLibrary()
    }

    fun isSubscribed(url: String) = subscriptions.any { channelKey(it.url) == channelKey(url) }

    fun isChannelNotificationsEnabled(url: String) =
        library.isChannelNotificationsEnabled(url)

    fun setChannelNotificationsEnabled(url: String, enabled: Boolean) {
        library.setChannelNotificationsEnabled(url, enabled)
        ChannelUpdateWorker.schedule(appContext)
        if (enabled) ChannelUpdateWorker.enqueueNow(appContext)
    }

    fun toggleSubscribe(card: ChannelCard) {
        val adding = !isSubscribed(card.url)
        library.toggleSubscription(card)
        refreshLibrary()
        if (adding) {
            viewModelScope.launch {
                val isYoutube = runCatching {
                    YouTubeRepository.serviceIdForUrl(card.url) == org.schabi.newpipe.extractor.ServiceList.YouTube.serviceId
                }.getOrDefault(false)
                val latest = if (isYoutube) ChannelUpdateClient.fetchLatest(card.url) else null
                if (latest != null) library.updateSubscriptionLastVideo(card.url, latest.videoId)
                refreshLibrary()
            }
        }
    }

    fun channelInGroup(groupId: String, channelUrl: String) = library.channelInGroup(groupId, channelUrl)

    fun toggleChannelInGroup(groupId: String, channelUrl: String) {
        library.toggleChannelInGroup(groupId, channelUrl)
        channelGroups = library.channelGroups()
    }

    fun createChannelGroup(name: String) {
        if (name.isBlank()) return
        library.createChannelGroup(name)
        channelGroups = library.channelGroups()
    }

    fun deleteChannelGroup(groupId: String) {
        library.deleteChannelGroup(groupId)
        channelGroups = library.channelGroups()
    }

    // Listas de reproducción propias
    fun openSaveDialog(card: VideoCard) { saveDialogCard = card }
    fun closeSaveDialog() { saveDialogCard = null }

    fun createPlaylist(name: String, firstCard: VideoCard? = null) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val id = library.createPlaylist(clean)
        if (firstCard != null) library.togglePlaylistItem(id, firstCard)
        refreshLibrary()
    }

    fun deletePlaylist(id: String) {
        library.deletePlaylist(id)
        if (openPlaylistId == id) openPlaylistId = null
        refreshLibrary()
    }

    fun togglePlaylistItem(id: String, card: VideoCard) {
        library.togglePlaylistItem(id, card)
        refreshLibrary()
    }

    // ---------- Descargas ----------

    /** Los videos usan Android DownloadManager; el audio se guarda con el datasource del reproductor. */
    fun download(info: StreamInfo, audioOnlyDownload: Boolean) {
        if (audioOnlyDownload) {
            downloadAudio(info)
            return
        }
        val streamUrl: String
        val extension: String
        val muxed = info.videoStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.height }
        if (muxed == null) {
            downloadMessage = tr("Este vídeo no tiene una versión con audio para descargar. Prueba con \"Solo audio\".")
            return
        }
        streamUrl = muxed.content
        extension = "mp4"

        val safeName = (info.name ?: "video").replace(Regex("[^\\p{L}\\p{N} _.-]"), "").trim().take(60)
        val fileName = "$safeName-${info.id}.$extension"
        val thumb = info.thumbnails.maxByOrNull { it.width.toLong() * it.height.toLong() }?.url
            ?: info.thumbnails.firstOrNull()?.url

        runCatching {
            val manager = appContext.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(streamUrl))
                .setTitle(info.name ?: tr("Vídeo"))
                .setDescription("RebornTube")
                .addRequestHeader("User-Agent", DownloaderImpl.USER_AGENT)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, fileName)
            val dmId = manager.enqueue(request)
            library.addDownload(
                DownloadRecord(
                    id = info.url,
                    title = info.name ?: "",
                    channel = info.uploaderName ?: "",
                    thumb = thumb,
                    fileName = fileName,
                    dmId = dmId,
                    audioOnly = false,
                    duration = info.duration * 1000L,
                    uploaded = uploadText(info.textualUploadDate, info.uploadDate)
                )
            )
        }
            .onSuccess {
                refreshLibrary()
                downloadMessage = tr("Descargando… míralo en Biblioteca > Descargas")
            }
            .onFailure { downloadMessage = trf("No se pudo iniciar la descarga: %s", it.message) }
    }

    private fun downloadAudio(info: StreamInfo) {
        val audio = audioOptionsOf(info).firstOrNull()
        if (audio == null) {
            downloadMessage = tr("Este vídeo no tiene audio descargable")
            return
        }
        val extension = audio.format?.suffix?.lowercase()?.filter(Char::isLetterOrDigit)
            ?.ifEmpty { "audio" } ?: "audio"
        val safeName = (info.name ?: "audio").replace(Regex("[^\\p{L}\\p{N} _.-]"), "").trim().take(60)
        val fileName = "$safeName-${info.id}.$extension"
        val thumb = info.thumbnails.maxByOrNull { it.width.toLong() * it.height.toLong() }?.url
            ?: info.thumbnails.firstOrNull()?.url
        val artworkFile = thumb?.takeIf(String::isNotBlank)?.let {
            File(appContext.filesDir, "music-artwork/${info.id}.image")
        }
        val request = OneTimeWorkRequestBuilder<AudioDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                Data.Builder()
                    .putString(AudioDownloadWorker.KEY_URL, audio.content)
                    .putString(AudioDownloadWorker.KEY_FILE_NAME, fileName)
                    .apply {
                        if (!thumb.isNullOrBlank() && artworkFile != null) {
                            putString(AudioDownloadWorker.KEY_ARTWORK_URL, thumb)
                            putString(AudioDownloadWorker.KEY_ARTWORK_PATH, artworkFile.absolutePath)
                        }
                    }
                    .build()
            )
            .build()
        val randomBits = UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE
        val recordId = -randomBits.coerceAtLeast(1L)
        val record = DownloadRecord(
            id = info.url,
            title = info.name ?: "",
            channel = info.uploaderName ?: "",
            thumb = thumb,
            fileName = fileName,
            dmId = recordId,
            audioOnly = true,
            duration = info.duration * 1000L,
            artworkPath = artworkFile?.absolutePath,
            uploaded = uploadText(info.textualUploadDate, info.uploadDate),
            workId = request.id.toString()
        )
        runCatching {
            library.addDownload(record)
            WorkManager.getInstance(appContext)
                .enqueueUniqueWork("audio-download-${info.id}", ExistingWorkPolicy.REPLACE, request)
            downloadProgress = downloadProgress + (recordId to 0)
        }.onSuccess {
            refreshLibrary()
            downloadMessage = tr("Descargando en el formato original (YouTube no ofrece FLAC real); míralo en Biblioteca > Descargas")
        }.onFailure {
            library.removeDownload(fileName)
            downloadMessage = trf("No se pudo iniciar la descarga: %s", it.message)
        }
    }

    /** Actualiza el porcentaje de cada descarga (la pantalla de Descargas lo llama cada poco). */
    fun refreshDownloads() {
        if (downloadRefreshJob?.isActive == true) return
        val records = downloads
        if (records.isEmpty()) {
            downloadProgress = emptyMap()
            return
        }
        downloadRefreshJob = viewModelScope.launch {
            val (progress, failure) = withContext(Dispatchers.IO) {
                val result = HashMap<Long, Int>()
                val managerRecords = records.filter { it.workId == null && it.dmId > 0L }
                if (managerRecords.isNotEmpty()) {
                    val manager = appContext.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
                    manager.query(DownloadManager.Query().setFilterById(*managerRecords.map { it.dmId }.toLongArray()))?.use { c ->
                        val idCol = c.getColumnIndex(DownloadManager.COLUMN_ID)
                        val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val soFarCol = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        while (c.moveToNext()) {
                            result[c.getLong(idCol)] = when (c.getInt(statusCol)) {
                                DownloadManager.STATUS_SUCCESSFUL -> 100
                                DownloadManager.STATUS_FAILED -> -1
                                else -> {
                                    val total = c.getLong(totalCol)
                                    val soFar = c.getLong(soFarCol)
                                    if (total > 0) (soFar * 100 / total).toInt().coerceIn(0, 99) else 0
                                }
                            }
                        }
                    }
                }
                var error: String? = null
                val workManager = WorkManager.getInstance(appContext)
                records.filter { it.workId != null }.forEach { record ->
                    val fileExists = downloadFile(record).isFile
                    val workInfo = runCatching {
                        workManager.getWorkInfoById(UUID.fromString(record.workId)).get()
                    }.getOrNull()
                    result[record.dmId] = when (workInfo?.state) {
                        WorkInfo.State.SUCCEEDED -> 100
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED, null -> {
                            if (workInfo?.state == WorkInfo.State.FAILED && error == null && !fileExists) {
                                error = workInfo.outputData.getString(AudioDownloadWorker.KEY_ERROR)
                            }
                            if (fileExists) 100 else -1
                        }
                        else -> workInfo.progress.getInt(AudioDownloadWorker.KEY_PERCENT, 0).coerceIn(0, 99)
                    }
                }
                result to error
            }
            downloadProgress = progress
            if (failure != null) downloadMessage = trf("Falló la descarga de audio: %s", failure)
        }
    }

    private fun downloadFile(rec: DownloadRecord) =
        File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), rec.fileName)

    fun deleteDownload(rec: DownloadRecord) {
        if (rec.workId != null) {
            runCatching { WorkManager.getInstance(appContext).cancelWorkById(UUID.fromString(rec.workId)) }
        } else runCatching {
            val manager = appContext.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.remove(rec.dmId)
        }
        downloadFile(rec).delete()
        rec.artworkPath?.let { File(it).delete() }
        library.removeDownload(rec.fileName)
        offlineAudioBaseQueue = offlineAudioBaseQueue.filterNot { it.fileName == rec.fileName }
        offlineAudioQueue = offlineAudioQueue.filterNot { it.fileName == rec.fileName }
        refreshLibrary()
    }

    /** Reproduce un archivo descargado sin necesidad de conexión. */
    fun playOffline(rec: DownloadRecord) {
        val file = downloadFile(rec)
        if (!file.exists()) {
            downloadMessage = tr("No se encontró el archivo")
            return
        }
        if (offline == null) closePlayer()
        offlineAudioBaseQueue = if (rec.audioOnly) {
            val ready = library.downloads().filter { it.audioOnly && downloadFile(it).isFile }
            if (ready.any { it.fileName == rec.fileName }) ready else ready + rec
        } else {
            emptyList()
        }
        offlineAudioQueue = if (rec.audioOnly && offlineShuffleEnabled) {
            listOf(rec) + offlineAudioBaseQueue.filterNot { it.fileName == rec.fileName }.shuffled()
        } else {
            offlineAudioBaseQueue
        }
        expanded = true
        startOfflineTrack(rec)
    }

    fun playOfflineFromQueue(rec: DownloadRecord) {
        val index = offlineAudioQueue.indexOfFirst { it.fileName == rec.fileName }
        if (index >= 0) {
            offlineAudioQueue = offlineAudioQueue.drop(index) + offlineAudioQueue.take(index)
        }
        startOfflineTrack(rec)
    }

    private fun startOfflineTrack(rec: DownloadRecord) {
        val file = downloadFile(rec)
        if (!file.exists()) {
            downloadMessage = tr("No se encontró el archivo")
            return
        }
        offline = rec
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(rec.title)
                    .setArtist(rec.channel)
                    .setArtworkUri(
                        rec.artworkPath?.let(::File)?.takeIf { it.isFile }?.let { Uri.fromFile(it) }
                            ?: rec.thumb?.let(Uri::parse)
                    )
                    .build()
            )
            .build()
        player.repeatMode = if (offlineRepeatMode == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    fun skipOfflineNextTrack(): Boolean {
        val queue = offlineAudioQueue.filter { downloadFile(it).isFile }
        if (queue != offlineAudioQueue) offlineAudioQueue = queue
        if (queue.isEmpty()) return false
        val currentIndex = queue.indexOfFirst { it.fileName == offline?.fileName }
        val nextIndex = when {
            currentIndex < 0 && queue.isNotEmpty() -> 0
            currentIndex + 1 < queue.size -> currentIndex + 1
            offlineRepeatMode == 2 -> 0
            else -> return false
        }
        startOfflineTrack(queue[nextIndex])
        return true
    }

    fun skipOfflinePreviousTrack(): Boolean {
        if (player.currentPosition > 5_000L) {
            player.seekTo(0L)
            return true
        }
        val queue = offlineAudioQueue.filter { downloadFile(it).isFile }
        if (queue != offlineAudioQueue) offlineAudioQueue = queue
        val currentIndex = queue.indexOfFirst { it.fileName == offline?.fileName }
        val previousIndex = when {
            currentIndex > 0 -> currentIndex - 1
            offlineRepeatMode == 2 && queue.isNotEmpty() -> queue.lastIndex
            else -> return false
        }
        startOfflineTrack(queue[previousIndex])
        return true
    }

    fun toggleOfflineShuffle() {
        offlineShuffleEnabled = !offlineShuffleEnabled
        settings.edit().putBoolean("offline_audio_shuffle", offlineShuffleEnabled).apply()
        val current = offline
        val available = offlineAudioBaseQueue.filter { downloadFile(it).isFile }
        offlineAudioQueue = if (current == null) {
            if (offlineShuffleEnabled) available.shuffled() else available
        } else if (offlineShuffleEnabled) {
            listOf(current) + available.filterNot { it.fileName == current.fileName }.shuffled()
        } else {
            available
        }
    }

    fun cycleOfflineRepeatMode() {
        offlineRepeatMode = (offlineRepeatMode + 1) % 3
        settings.edit().putInt("offline_audio_repeat_mode", offlineRepeatMode).apply()
        player.repeatMode = if (offlineRepeatMode == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun closeOffline() {
        setSleepTimer(null)
        offline = null
        offlineAudioQueue = emptyList()
        offlineAudioBaseQueue = emptyList()
        player.stop()
        player.clearMediaItems()
        isPlaying = false
    }

    fun minimizeOffline() {
        if (offline?.audioOnly == true) expanded = false
    }

    // ---------- Copia de seguridad ----------

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            backupMessage = runCatching { withContext(Dispatchers.IO) { Backup.export(appContext, library, uri) } }
                .fold({ tr("Copia guardada correctamente") }, { trf("No se pudo exportar: %s", it.message) })
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            backupMessage = runCatching { withContext(Dispatchers.IO) { Backup.import(appContext, library, uri) } }
                .fold(
                    { found -> if (found) tr("Copia importada") else tr("Ese zip no es una copia de RebornTube") },
                    { trf("No se pudo importar: %s", it.message) }
                )
            refreshLibrary()
        }
    }

    fun importTakeout(uri: Uri) {
        viewModelScope.launch {
            backupMessage = runCatching { withContext(Dispatchers.IO) { Backup.importTakeout(appContext, library, uri) } }
                .fold(
                    { (videos, channels) ->
                        if (videos == 0 && channels == 0) tr("No se encontró historial ni suscripciones en ese archivo")
                        else trf("Importados: %s vídeos del historial y %s suscripciones", videos, channels)
                    },
                    { trf("No se pudo importar: %s", it.message) }
                )
            refreshLibrary()
        }
    }

    // ---------- Comentarios ----------

    private var commentsUrl: String? = null
    private var commentsRequested = false

    fun toggleComments() {
        commentsOpen = !commentsOpen
        settings.edit().putBoolean("comments_open", commentsOpen).apply()
        // Primera vez que se abren en este vídeo: ahora sí se piden
        val url = commentsUrl
        if (commentsOpen && url != null && !commentsRequested) loadComments(url)
    }

    /** Vídeo nuevo: se vacían los comentarios y solo se piden si la sección está abierta. */
    private fun resetComments(url: String) {
        commentsUrl = url
        commentsRequested = false
        comments = emptyList()
        commentsError = null
        hasMoreComments = false
        commentsInfo = null
        nextPage = null
        commentsLoading = false
        if (commentsOpen) loadComments(url)
    }

    private fun loadComments(url: String) {
        commentsRequested = true
        comments = emptyList()
        commentsError = null
        hasMoreComments = false
        commentsInfo = null
        nextPage = null
        viewModelScope.launch {
            commentsLoading = true
            runCatching { YouTubeRepository.comments(url) }
                .onSuccess { info ->
                    commentsInfo = info
                    comments = info.relatedItems.filterIsInstance<CommentsInfoItem>()
                    nextPage = info.nextPage
                    hasMoreComments = nextPage != null
                    if (comments.isEmpty()) commentsError = tr("Sin comentarios (o están desactivados)")
                }
                .onFailure { commentsError = friendly(it, tr("No se pudieron cargar los comentarios")) }
            commentsLoading = false
        }
    }

    fun loadMoreComments() {
        val info = commentsInfo ?: return
        val page = nextPage ?: return
        if (commentsLoading) return
        viewModelScope.launch {
            commentsLoading = true
            runCatching { YouTubeRepository.moreComments(info, page) }
                .onSuccess {
                    comments = comments + it.items.filterIsInstance<CommentsInfoItem>()
                    nextPage = it.nextPage
                    hasMoreComments = nextPage != null
                }
                .onFailure { commentsError = friendly(it, tr("Error al cargar más comentarios")) }
            commentsLoading = false
        }
    }

    // ---------- Canal ----------

    /** Abre el canal; si hay un vídeo en pantalla grande, pasa a mini reproductor. */
    fun openChannel(url: String?) {
        if (url.isNullOrEmpty()) return
        channelUrl = url
        channel = null
        channelVideos = emptyList()
        channelError = null
        channelHasMore = false
        channelSection = 0
        channelSort = 0
        hasLiveTab = false
        hasShortsTab = false
        hasPlaylistsTab = false
        channelPlaylists = emptyList()
        listMessage = null
        remoteUrl = null
        videosTab = null
        liveTab = null
        shortsTab = null
        playlistsTab = null
        channelTab = null
        channelNextPage = null
        minimize()

        viewModelScope.launch {
            channelLoading = true
            runCatching { YouTubeRepository.channel(url) }
                .onSuccess { info ->
                    channel = info
                    videosTab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
                        ?: info.tabs.firstOrNull()
                    liveTab = info.tabs.firstOrNull { ChannelTabs.LIVESTREAMS in it.contentFilters }
                    shortsTab = info.tabs.firstOrNull { ChannelTabs.SHORTS in it.contentFilters }
                    hasLiveTab = liveTab != null
                    hasShortsTab = shortsTab != null
                    playlistsTab = info.tabs.firstOrNull { ChannelTabs.PLAYLISTS in it.contentFilters }
                    hasPlaylistsTab = playlistsTab != null
                }
                .onFailure { channelError = friendly(it, tr("No se pudo cargar el canal")) }
            channelLoading = false
            if (channel != null) loadTab(videosTab, 0)
        }
    }

    fun selectChannelSection(section: Int) {
        if (channelSection == section) return
        channelSection = section
        channelSort = 0
        loadTab(
            when (section) {
                1 -> liveTab
                2 -> shortsTab
                3 -> playlistsTab
                else -> videosTab
            },
            section
        )
    }

    private fun playlistCards(items: List<org.schabi.newpipe.extractor.InfoItem>): List<RemotePlaylist> =
        items.filterIsInstance<PlaylistInfoItem>().map {
            RemotePlaylist(it.url, it.name ?: "", it.thumbnails.firstOrNull()?.url, it.streamCount)
        }

    private fun loadTab(tab: ListLinkHandler?, section: Int) {
        channelTab = tab
        channelVideos = emptyList()
        channelPlaylists = emptyList()
        channelError = null
        channelHasMore = false
        channelNextPage = null
        if (tab == null) {
            channelError = tr("Este canal no tiene esta sección")
            return
        }
        viewModelScope.launch {
            channelLoading = true
            val serviceId = channelUrl?.let { runCatching { YouTubeRepository.serviceIdForUrl(it) }.getOrNull() }
            runCatching { YouTubeRepository.channelTab(tab, serviceId) }
                .onSuccess { page ->
                    if (section == 3) {
                        channelPlaylists = playlistCards(page.relatedItems)
                        if (channelPlaylists.isEmpty()) channelError = tr("Este canal no tiene listas")
                    } else {
                        channelVideos = page.relatedItems
                            .filterIsInstance<StreamInfoItem>()
                            .map { it.toCard() }
                        prefetchLikely(channelVideos)
                        if (channelVideos.isEmpty()) channelError = tr("No hay vídeos en esta sección")
                    }
                    channelNextPage = page.nextPage
                    channelHasMore = channelNextPage != null
                }
                .onFailure { channelError = friendly(it, tr("No se pudieron cargar los vídeos")) }
            channelLoading = false
        }
    }

    fun loadMoreChannelVideos() {
        val tab = channelTab ?: return
        val page = channelNextPage ?: return
        if (channelLoading) return
        viewModelScope.launch {
            channelLoading = true
            val serviceId = channelUrl?.let { runCatching { YouTubeRepository.serviceIdForUrl(it) }.getOrNull() }
            runCatching { YouTubeRepository.moreChannelTab(tab, page, serviceId) }
                .onSuccess { more ->
                    if (channelSection == 3) {
                        channelPlaylists = channelPlaylists + playlistCards(more.items)
                    } else {
                        channelVideos = channelVideos +
                            more.items.filterIsInstance<StreamInfoItem>().map { it.toCard() }
                        prefetchLikely(channelVideos.takeLast(2))
                    }
                    channelNextPage = more.nextPage
                    channelHasMore = channelNextPage != null
                }
                .onFailure { channelError = friendly(it, tr("Error al cargar más")) }
            channelLoading = false
        }
    }

    // ---------- Listas de YouTube (pantalla de una lista y guardarla como propia) ----------

    fun openRemotePlaylist(rp: RemotePlaylist) {
        remoteUrl = rp.url
        remoteName = rp.name
        remoteItems = emptyList()
        remoteError = null
        remoteHasMore = false
        remoteNextPage = null
        viewModelScope.launch {
            remoteLoading = true
            runCatching { YouTubeRepository.playlist(rp.url) }
                .onSuccess { info ->
                    remoteName = info.name ?: rp.name
                    remoteItems = info.relatedItems.map { it.toCard() }
                    prefetchLikely(remoteItems)
                    remoteNextPage = info.nextPage
                    remoteHasMore = remoteNextPage != null
                    if (remoteItems.isEmpty()) remoteError = tr("Esta lista está vacía")
                }
                .onFailure { remoteError = friendly(it, tr("No se pudo cargar la lista")) }
            remoteLoading = false
        }
    }

    fun loadMoreRemote() {
        val url = remoteUrl ?: return
        val page = remoteNextPage ?: return
        if (remoteLoading) return
        viewModelScope.launch {
            remoteLoading = true
            runCatching { YouTubeRepository.morePlaylist(url, page) }
                .onSuccess { more ->
                    val added = more.items.map { it.toCard() }
                    remoteItems = remoteItems + added
                    prefetchLikely(added)
                    remoteNextPage = more.nextPage
                    remoteHasMore = remoteNextPage != null
                }
                .onFailure { remoteError = friendly(it, tr("Error al cargar más")) }
            remoteLoading = false
        }
    }

    fun closeRemote() {
        remoteUrl = null
        remoteItems = emptyList()
    }

    /** Guarda una lista de YouTube como lista propia (hasta unos 200 vídeos). */
    fun saveRemotePlaylist(url: String, fallbackName: String) {
        viewModelScope.launch {
            listMessage = tr("Guardando lista…")
            runCatching {
                val info = YouTubeRepository.playlist(url)
                val cards = ArrayList<VideoCard>(info.relatedItems.map { it.toCard() })
                var page = info.nextPage
                while (page != null && cards.size < 200) {
                    val more = YouTubeRepository.morePlaylist(url, page)
                    cards += more.items.map { it.toCard() }
                    page = more.nextPage
                }
                val name = (info.name ?: fallbackName).ifBlank { fallbackName }
                val id = library.createPlaylist(name)
                library.addPlaylistItems(id, cards)
                name to cards.size
            }
                .onSuccess { (name, count) ->
                    refreshLibrary()
                    listMessage = trf("Lista \"%s\" guardada en Biblioteca > Listas (%s vídeos)", name, count)
                }
                .onFailure { listMessage = trf("No se pudo guardar la lista: %s", it.message) }
        }
    }

    /** Recientes / Populares / Antiguos: se ordenan los últimos vídeos cargados del canal (hasta ~600). */
    fun setChannelSort(sort: Int) {
        if (sort == channelSort) return
        channelSort = sort
        if (sort == 0) loadTab(videosTab, 0) else loadAllChannelVideos(sort)
    }

    private fun loadAllChannelVideos(sort: Int) {
        val tab = videosTab ?: return
        channelTab = tab
        channelVideos = emptyList()
        channelError = null
        channelHasMore = false
        channelNextPage = null
        viewModelScope.launch {
            channelLoading = true
            runCatching {
                val serviceId = channelUrl?.let { runCatching { YouTubeRepository.serviceIdForUrl(it) }.getOrNull() }
                val first = YouTubeRepository.channelTab(tab, serviceId)
                val all = ArrayList<VideoCard>(
                    first.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toCard() }
                )
                var page = first.nextPage
                var pages = 1
                while (page != null && pages < 20) {
                    val more = YouTubeRepository.moreChannelTab(tab, page, serviceId)
                    all += more.items.filterIsInstance<StreamInfoItem>().map { it.toCard() }
                    page = more.nextPage
                    pages++
                }
                all
            }
                .onSuccess { all ->
                    // Los vídeos llegan del más nuevo al más viejo
                    channelVideos = if (sort == 1) all.sortedByDescending { it.views } else all.reversed()
                    if (channelVideos.isEmpty()) channelError = tr("No hay vídeos en esta sección")
                }
                .onFailure { channelError = friendly(it, tr("No se pudieron cargar los vídeos")) }
            channelLoading = false
        }
    }

    fun closeChannel() {
        channelUrl = null
    }
}
