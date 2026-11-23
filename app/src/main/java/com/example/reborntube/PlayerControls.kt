package com.example.reborntube

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay

/**
 * Controles propios: panel redondeado abajo con los colores del tema (play, barra de tiempo y una
 * fila de acciones), botón para minimizar arriba a la izquierda y aviso cuando se salta un sponsor.
 * Un toque muestra u oculta el panel; doble toque a un lado retrocede/avanza 10 s.
 *
 * showExtras = false (archivos descargados): sin compartir, ventana flotante, pantalla completa ni modo música.
 */
@Composable
fun PlayerControls(
    vm: MainViewModel,
    exo: Player,
    live: Boolean,
    fullscreen: Boolean,
    showExtras: Boolean,
    onMinimize: () -> Unit,
    onShare: (() -> Unit)?,
    onShareMoment: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var visible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var menuOpen by remember { mutableStateOf(false) }

    // Posición actual
    LaunchedEffect(exo) {
        while (true) {
            if (!dragging) {
                position = exo.currentPosition
                duration = exo.duration.coerceAtLeast(0L)
            }
            delay(250)
        }
    }

    // El panel se oculta solo a los pocos segundos mientras se reproduce
    LaunchedEffect(visible, vm.isPlaying, interaction, menuOpen) {
        if (visible && vm.isPlaying && !menuOpen) {
            delay(3500)
            visible = false
        }
    }

    fun seekBy(deltaMs: Long) {
        exo.seekTo((exo.currentPosition + deltaMs).coerceAtLeast(0L))
        interaction++
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onTap = { visible = !visible; interaction++ },
                onDoubleTap = { offset ->
                    if (!live) {
                        val seekMs = vm.seekIntervalSeconds * 1_000L
                        seekBy(if (offset.x < size.width / 2) -seekMs else seekMs)
                    }
                }
            )
        }
    ) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                // Minimizar / cerrar
                IconButton(
                    onClick = onMinimize,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .background(scheme.surface.copy(alpha = 0.75f), CircleShape)
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, tr("Minimizar"), tint = scheme.onSurface)
                }

                // Panel de control
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(scheme.surface.copy(alpha = 0.88f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(42.dp).clip(CircleShape).background(scheme.primary)
                                .clickable { vm.togglePlay(); interaction++ },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (vm.isPlaying) tr("Pausar") else tr("Reproducir"),
                                tint = scheme.onPrimary
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (live) {
                            Spacer(Modifier.weight(1f))
                            Text(tr("EN DIRECTO"), color = scheme.error, style = MaterialTheme.typography.labelLarge)
                        } else {
                            Slider(
                                value = if (dragging) dragValue
                                else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                                onValueChange = { dragging = true; dragValue = it; interaction++ },
                                onValueChangeFinished = {
                                    position = (dragValue * duration).toLong()
                                    exo.seekTo(position)
                                    dragging = false
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = scheme.primary,
                                    activeTrackColor = scheme.primary,
                                    inactiveTrackColor = scheme.onSurface.copy(alpha = 0.25f)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${formatClock(position)} / ${formatClock(duration)}",
                                color = scheme.onSurface,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!live) {
                            PanelIcon(Icons.Default.Replay10, tr("Retroceder 10 s")) { seekBy(-10_000L) }
                            PanelIcon(Icons.Default.Forward10, tr("Avanzar 10 s")) { seekBy(10_000L) }
                        }
                        if (showExtras) {
                            // Repetir: al terminar vuelve a empezar
                            PanelIcon(
                                if (vm.loop) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                tr("Repetir (bucle)"),
                                tint = if (vm.loop) scheme.primary else scheme.onSurface
                            ) { vm.toggleLoop(); interaction++ }
                            // Modo música: solo audio, sigue sonando al salir de la app
                            PanelIcon(
                                Icons.Default.MusicNote,
                                tr("Modo música (segundo plano)"),
                                tint = if (vm.audioOnly) scheme.primary else scheme.onSurface
                            ) { vm.toggleAudioOnly(); interaction++ }
                            // Conectar a la TV
                            PanelIcon(
                Icons.Default.Cast,
                tr("Transmitir a la TV"),
                tint = if (vm.casting) scheme.primary else scheme.onSurface
            ) { vm.openCastSheet(); interaction++ }
                        }
                        Box {
                            PanelIcon(Icons.Default.Settings, tr("Ajustes")) { menuOpen = true; interaction++ }
                            SettingsMenu(
                                vm = vm,
                                withStreamOptions = showExtras,
                                onShare = onShare,
                                onPip = if (showExtras) ({ context.findActivity()?.enterPip() }) else null,
                                onShareMoment = onShareMoment,
                                open = menuOpen
                            ) { menuOpen = false }
                        }
                        if (showExtras) {
                            PanelIcon(
                                if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                if (fullscreen) tr("Salir de pantalla completa") else tr("Pantalla completa")
                            ) { vm.fullscreen = !fullscreen; interaction++ }
                        }
                    }
                }
            }
        }

        // Aviso de sponsor omitido (se ve aunque el panel esté oculto)
        vm.skippedMessage?.let {
            Text(
                it,
                color = scheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                    .clip(RoundedCornerShape(16.dp)).background(scheme.primary)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // Subtítulos (como en NewPipe): texto blanco sobre fondo oscuro, abajo en el centro
        vm.subtitleText?.let { text ->
            Text(
                text,
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(start = 24.dp, end = 24.dp, bottom = if (visible) 108.dp else 20.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }

        // Al arrastrar la barra se ve el segundo al que vas a saltar
        if (dragging && duration > 0) {
            Text(
                formatClock((dragValue * duration).toLong()),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.Center)
                    .background(Color(0xAA000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        if (vm.isBuffering) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = scheme.primary)
        }

        vm.playbackPauseMessage?.takeIf { !vm.isPlaying && !vm.isBuffering }?.let { message ->
            Text(
                message,
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun PanelIcon(
    icon: ImageVector,
    description: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, description, tint = tint)
    }
}

/** Calidad, audio y velocidad. Los directos (HLS) usan las calidades que ofrece la emisión. */
@Composable
private fun SettingsMenu(
    vm: MainViewModel,
    withStreamOptions: Boolean,
    onShare: (() -> Unit)?,
    onPip: (() -> Unit)?,
    onShareMoment: (() -> Unit)?,
    open: Boolean,
    onDismiss: () -> Unit
) {
    fun mark(selected: Boolean) = if (selected) "✓ " else ""

    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        if (onShare != null) {
            DropdownMenuItem(text = { Text(tr("Compartir")) }, onClick = { onDismiss(); onShare() })
        }
        if (onShareMoment != null) {
            DropdownMenuItem(text = { Text(tr("Compartir desde este momento")) }, onClick = { onDismiss(); onShareMoment() })
        }
        if (onPip != null) {
            DropdownMenuItem(text = { Text(tr("Ventana flotante")) }, onClick = { onDismiss(); onPip() })
        }
        if (withStreamOptions) {
            if (vm.hlsMode && vm.hlsHeights.isNotEmpty()) {
                MenuHeader(tr("Calidad"))
                DropdownMenuItem(
                    text = { Text(mark(vm.hlsSelected == null) + tr("Automática")) },
                    onClick = { vm.selectHlsHeight(null); onDismiss() }
                )
                vm.hlsHeights.forEach { h ->
                    DropdownMenuItem(
                        text = { Text(mark(vm.hlsSelected == h) + "${h}p") },
                        onClick = { vm.selectHlsHeight(h); onDismiss() }
                    )
                }
            } else if (!vm.hlsMode && vm.videoOptions.isNotEmpty()) {
                MenuHeader(tr("Calidad"))
                vm.videoOptions.forEach { v ->
                    DropdownMenuItem(
                        text = { Text(mark(v == vm.selVideo) + v.label()) },
                        onClick = { vm.selectVideo(v); onDismiss() }
                    )
                }
            }

            if (!vm.hlsMode && vm.audioOptions.size > 1) {
                MenuHeader(tr("Audio"))
                vm.audioOptions.forEach { a ->
                    DropdownMenuItem(
                        text = { Text(mark(a == vm.selAudio) + a.label()) },
                        onClick = { vm.selectAudio(a); onDismiss() }
                    )
                }
            }
        }

        if (withStreamOptions && !vm.hlsMode && vm.subtitleOptions.isNotEmpty()) {
            MenuHeader(tr("Subtítulos"))
            DropdownMenuItem(
                text = { Text(mark(vm.selSubtitle == null) + tr("Desactivados")) },
                onClick = { vm.selectSubtitle(null); onDismiss() }
            )
            vm.subtitleOptions.take(40).forEach { st ->
                DropdownMenuItem(
                    text = { Text(mark(st == vm.selSubtitle) + st.label()) },
                    onClick = { vm.selectSubtitle(st); onDismiss() }
                )
            }
        }

        MenuHeader(tr("Reproducción automática"))
        DropdownMenuItem(
            text = {
                Text(
                    trf(
                        "Reproducción automática: %s",
                        tr(if (vm.autoplayNextEnabled) "Activada" else "Desactivada")
                    )
                )
            },
            onClick = { vm.toggleAutoplayNext(); onDismiss() }
        )

        MenuHeader(tr("Temporizador"))
        if (vm.sleepTimerRemainingMs > 0L) {
            Text(
                trf("Se detendrá en %s", formatClock(vm.sleepTimerRemainingMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        } else if (vm.sleepAfterCurrent) {
            Text(
                tr("Se detendrá al terminar este vídeo"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        listOf(15, 30, 45, 60).forEach { minutes ->
            DropdownMenuItem(
                text = { Text(trf("Detener en %s min", minutes)) },
                onClick = { vm.setSleepTimer(minutes); onDismiss() }
            )
        }
        DropdownMenuItem(
            text = { Text(mark(vm.sleepAfterCurrent) + tr("Al terminar este vídeo")) },
            onClick = { vm.armSleepAfterCurrent(true); onDismiss() }
        )
        DropdownMenuItem(
            text = { Text(tr("Cancelar temporizador")) },
            enabled = vm.sleepAfterCurrent || vm.sleepTimerRemainingMs > 0L,
            onClick = { vm.setSleepTimer(null); onDismiss() }
        )

        if (vm.audioOnly) {
            MenuHeader(tr("Modo música"))
            DropdownMenuItem(
                text = { Text(trf("Aleatorio: %s", tr(if (vm.shuffleMusicQueue) "Activada" else "Desactivada"))) },
                onClick = { vm.toggleShuffleMusicQueue(); onDismiss() }
            )
            DropdownMenuItem(
                text = { Text(trf("Repetir cola: %s", tr(if (vm.repeatMusicQueue) "Activada" else "Desactivada"))) },
                onClick = { vm.toggleRepeatMusicQueue(); onDismiss() }
            )
            DropdownMenuItem(
                text = { Text(tr("Anterior")) },
                onClick = { vm.skipPreviousTrack(); onDismiss() }
            )
            DropdownMenuItem(
                text = { Text(tr("Siguiente")) },
                onClick = { vm.skipNextTrack(); onDismiss() }
            )
        }

        MenuHeader(tr("Velocidad"))
        listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
            DropdownMenuItem(
                text = { Text(mark(vm.speed == s) + if (s == 1f) tr("Normal") else "${s}x") },
                onClick = { vm.setSpeed(s); onDismiss() }
            )
        }

        MenuHeader(tr("Salto con doble toque"))
        listOf(5, 10, 15, 30).forEach { seconds ->
            DropdownMenuItem(
                text = { Text(mark(vm.seekIntervalSeconds == seconds) + trf("%s segundos", seconds)) },
                onClick = { vm.chooseSeekInterval(seconds); onDismiss() }
            )
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}
