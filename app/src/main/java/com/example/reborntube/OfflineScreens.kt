package com.example.reborntube

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun DownloadsScreen(vm: MainViewModel) {
    // Mientras se ve esta pantalla, se actualiza el porcentaje de cada descarga
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshDownloads()
            delay(1500)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(tr("Descargas"), onBack = { vm.libraryList = null })
        vm.downloadMessage?.let { Text(it, modifier = Modifier.padding(horizontal = 12.dp)) }

        if (vm.downloads.isEmpty()) {
            NoteBox(tr("Aún no has descargado nada. Dentro de un vídeo pulsa \"Descargar\" y elige vídeo con audio o solo audio."))
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (vm.miniVisible) MiniPlayerBottomSpace else 0.dp)
        ) {
            items(vm.downloads) { rec ->
                val pct = vm.downloadProgress[rec.dmId]
                val ready = pct == 100
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = ready) { vm.playOffline(rec) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = rec.artworkPath?.let { File(it) }?.takeIf { it.isFile } ?: rec.thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(128.dp).aspectRatio(16f / 9f).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(rec.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall)
                        Text(rec.channel, style = MaterialTheme.typography.bodySmall)
                        Text(
                            when {
                                pct == null -> tr("Comprobando…")
                                pct == 100 -> if (rec.audioOnly) tr("Solo audio · lista") else tr("Lista · toca para ver")
                                pct < 0 -> tr("La descarga falló")
                                else -> trf("Descargando %s%%", pct)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    var menuOpen by remember(rec.dmId) { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = tr("MÃ¡s opciones"))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (ready) {
                                DropdownMenuItem(
                                    text = { Text(tr("Reproducir"), style = MaterialTheme.typography.bodyMedium) },
                                    onClick = { menuOpen = false; vm.playOffline(rec) },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(tr("Borrar descarga"), style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; vm.deleteDownload(rec) },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Reproductor para archivos descargados (funciona sin conexión). */
@Composable
fun OfflinePlayer(vm: MainViewModel, rec: DownloadRecord) {
    val context = LocalContext.current
    val exo = remember(context) { PlayerHolder.get(context) }

    if (rec.audioOnly) {
        OfflineMusicPlayer(vm, rec, exo)
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        VideoView(exo, Modifier.fillMaxSize())

        PlayerControls(
            vm = vm,
            exo = exo,
            live = false,
            fullscreen = false,
            showExtras = false,
            onMinimize = vm::closeOffline,
            onShare = null
        )

        Text(
            rec.title,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp, start = 64.dp, end = 16.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfflineMusicPlayer(vm: MainViewModel, rec: DownloadRecord, exo: androidx.media3.exoplayer.ExoPlayer) {
    val ink = Color(0xFF11130F)
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val cover = remember(rec.artworkPath, rec.thumb) {
        rec.artworkPath?.let { File(it) }?.takeIf { it.isFile } ?: rec.thumb
    }
    var position by remember(rec.fileName) { mutableLongStateOf(0L) }
    var duration by remember(rec.fileName) { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var menuOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }

    LaunchedEffect(exo) {
        while (true) {
            if (!dragging) {
                position = exo.currentPosition.coerceAtLeast(0L)
                duration = exo.duration.takeIf { it > 0L && it != androidx.media3.common.C.TIME_UNSET }
                    ?: rec.duration.coerceAtLeast(0L)
            }
            delay(400)
        }
    }

    Box(Modifier.fillMaxSize().background(ink)) {
        if (cover != null) {
            AsyncImage(
                model = cover, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(38.dp).alpha(0.42f)
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(accent.copy(alpha = 0.24f), ink, ink))
            )
        )

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::minimizeOffline) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = tr("Minimizar"), tint = Color.White)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr("REPRODUCIENDO DESDE"), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.72f))
                    Text(tr("Descargas"), style = MaterialTheme.typography.titleSmall, color = Color.White)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = tr("Más opciones"), tint = Color.White)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (rec.audioOnly) {
                            DropdownMenuItem(
                                text = { Text(trf("Cola de audio (%s)", vm.offlineAudioQueue.size)) },
                                onClick = { menuOpen = false; queueOpen = true }
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Detener en 15 min")) },
                                onClick = { menuOpen = false; vm.setSleepTimer(15) }
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Detener en 30 min")) },
                                onClick = { menuOpen = false; vm.setSleepTimer(30) }
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Detener en 60 min")) },
                                onClick = { menuOpen = false; vm.setSleepTimer(60) }
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Al terminar este audio")) },
                                onClick = { menuOpen = false; vm.armSleepAfterCurrent(true) }
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Cancelar temporizador")) },
                                enabled = vm.sleepAfterCurrent || vm.sleepTimerRemainingMs > 0L,
                                onClick = { menuOpen = false; vm.setSleepTimer(null) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(tr("Borrar descarga")) },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menuOpen = false; vm.deleteDownload(rec); vm.closeOffline() }
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Cerrar reproductor")) },
                            onClick = { menuOpen = false; vm.closeOffline() }
                        )
                    }
                }
            }

            Spacer(Modifier.weight(0.55f))
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val artSize = maxWidth - 12.dp
                if (cover != null) {
                    AsyncImage(
                        model = cover, contentDescription = rec.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(artSize).clip(RoundedCornerShape(18.dp))
                    )
                } else {
                    Box(
                        Modifier.size(artSize).clip(RoundedCornerShape(18.dp)).background(Color(0xFF292D27)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(88.dp)) }
                }
            }

            Spacer(Modifier.weight(0.55f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rec.title, color = Color.White, style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(rec.channel.ifBlank { tr("Artista desconocido") }, color = accent,
                        style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = accent, modifier = Modifier.padding(start = 12.dp).size(24.dp))
            }

            Spacer(Modifier.height(14.dp))
            Slider(
                value = if (dragging) dragValue else if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    if (duration > 0L) exo.seekTo((dragValue * duration).toLong())
                    dragging = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = accent, activeTrackColor = accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth().height(32.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatClock(if (dragging) (dragValue * duration).toLong() else position), color = Color.White.copy(alpha = 0.76f), style = MaterialTheme.typography.labelSmall)
                Text("-${formatClock((duration - position).coerceAtLeast(0L))}", color = Color.White.copy(alpha = 0.76f), style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val repeatLabel = when (vm.offlineRepeatMode) {
                    1 -> tr("Repetir canción")
                    2 -> tr("Repetir cola")
                    else -> tr("No repetir")
                }
                IconButton(onClick = vm::toggleOfflineShuffle, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = tr("Aleatorio"),
                        tint = if (vm.offlineShuffleEnabled) accent else Color.White.copy(alpha = 0.75f)
                    )
                }
                IconButton(onClick = vm::cycleOfflineRepeatMode, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (vm.offlineRepeatMode == 1) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = repeatLabel,
                        tint = if (vm.offlineRepeatMode != 0) accent else Color.White.copy(alpha = 0.75f)
                    )
                }
                IconButton(onClick = { vm.skipOfflinePreviousTrack() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = tr("Pista anterior"), tint = Color.White)
                }
                IconButton(onClick = { exo.seekTo((exo.currentPosition - 10_000L).coerceAtLeast(0L)) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Replay10, contentDescription = tr("Retroceder 10 s"), tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Box(
                    Modifier.size(68.dp).clip(CircleShape).background(accent)
                        .clickable { vm.togglePlay() }, contentAlignment = Alignment.Center
                ) {
                    Icon(if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (vm.isPlaying) tr("Pausar") else tr("Reproducir"),
                        tint = onAccent, modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = { exo.seekTo((exo.currentPosition + 10_000L).coerceAtMost(duration)) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Forward10, contentDescription = tr("Avanzar 10 s"), tint = Color.White, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = { vm.skipOfflineNextTrack() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.SkipNext, contentDescription = tr("Pista siguiente"), tint = Color.White)
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Download, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Disponible sin conexión"), color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodySmall)
            }
            if (vm.sleepTimerRemainingMs > 0L) {
                Text(
                    trf("Temporizador: %s", formatClock(vm.sleepTimerRemainingMs)),
                    color = accent,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.Start).padding(top = 8.dp)
                )
            } else if (vm.sleepAfterCurrent) {
                Text(
                    tr("Se detendrá al terminar este audio"),
                    color = accent,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.Start).padding(top = 8.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (queueOpen) {
            ModalBottomSheet(onDismissRequest = { queueOpen = false }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(tr("Cola de audio"), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillChip(
                            tr("Aleatorio"),
                            selected = vm.offlineShuffleEnabled
                        ) { vm.toggleOfflineShuffle() }
                        PillChip(
                            when (vm.offlineRepeatMode) {
                                1 -> tr("Repetir canción")
                                2 -> tr("Repetir cola")
                                else -> tr("No repetir")
                            },
                            selected = vm.offlineRepeatMode != 0
                        ) { vm.cycleOfflineRepeatMode() }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (vm.offlineAudioQueue.isEmpty()) {
                        Text(tr("No hay audios descargados listos."), modifier = Modifier.padding(vertical = 12.dp))
                    } else {
                        LazyColumn(Modifier.heightIn(max = 420.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(vm.offlineAudioQueue, key = { it.fileName }) { track ->
                                val selected = track.fileName == rec.fileName
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            queueOpen = false
                                            vm.playOfflineFromQueue(track)
                                        }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = track.artworkPath?.let { File(it) }?.takeIf { it.isFile } ?: track.thumb,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            track.channel.ifBlank { tr("Artista desconocido") },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    if (selected) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = accent)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
