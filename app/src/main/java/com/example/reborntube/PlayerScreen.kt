package com.example.reborntube

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo

@Composable
fun PlayerScreen(vm: MainViewModel, info: StreamInfo) {
    val context = LocalContext.current
    val exo = remember { PlayerHolder.get(context) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    FullscreenEffect(vm)

    if (vm.inPip) {
        // Ventana flotante: solo el vídeo
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (vm.ambientMode) AmbientVideoView(exo, Modifier.fillMaxSize())
            else VideoView(exo, Modifier.fillMaxSize())
        }
    } else if (vm.fullscreen) {
        Box(Modifier.fillMaxSize().background(if (vm.ambientMode) Color.Transparent else Color.Black)) {
            VideoWithControls(vm, exo, info, Modifier.fillMaxSize(), fullscreen = true)
            vm.autoplayNextCard?.let { card ->
                AutoplayNextPrompt(vm, card, Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
            }
        }
    } else if (landscape) {
        // Horizontal: vídeo a la izquierda; calidad, audio, like y comentarios a la derecha
        Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.weight(0.6f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    VideoWithControls(vm, exo, info, Modifier.fillMaxWidth().aspectRatio(16f / 9f), fullscreen = false)
                }
                vm.autoplayNextCard?.let { card -> AutoplayNextPrompt(vm, card) }
            }
            key(info.url) { Details(vm, info, Modifier.weight(0.4f).fillMaxHeight()) }
        }
    } else {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            VideoWithControls(vm, exo, info, Modifier.fillMaxWidth().aspectRatio(16f / 9f), fullscreen = false)
            vm.autoplayNextCard?.let { card -> AutoplayNextPrompt(vm, card) }
            key(info.url) { Details(vm, info, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun VideoWithControls(
    vm: MainViewModel,
    exo: Player,
    info: StreamInfo,
    modifier: Modifier,
    fullscreen: Boolean
) {
    val context = LocalContext.current
    Box(modifier.background(if (vm.ambientMode) Color.Transparent else Color.Black)) {
        if (vm.audioOnly) {
            // Modo música: sin vídeo, solo la portada
            AsyncImage(
                model = info.thumbnails.firstOrNull()?.url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.35f,
                modifier = Modifier.fillMaxSize()
            )
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(56.dp)
            )
        } else {
            if (vm.ambientMode) {
                AsyncImage(
                    model = info.thumbnails.firstOrNull()?.url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.48f,
                    modifier = Modifier.fillMaxSize().blur(36.dp)
                )
            }
            VideoView(exo, Modifier.fillMaxSize())
        }
        if (vm.fireTvCompanionConnected != null) {
            Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Cast,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    trf("Conectado en el televisor %s mediante RebornTube TV.", vm.fireTvCompanionConnected.orEmpty()),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    tr("El video está en pausa en el celular y continúa en el televisor."),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = vm::resumeOnPhone) {
                    Text(tr("Desconectar y continuar en el celular"))
                }
            }
        } else if (vm.casting) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Cast, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                Text(trf("Reproduciendo en %s", vm.castDeviceName), color = Color.White)
                if (vm.castDetail.isNotEmpty()) {
                    Text(vm.castDetail, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                }
                // Cuántos archivos ha pedido la TV al móvil: si se queda en 0, la TV no llega a esta red
                val requests by produceState(initialValue = 0) {
                    while (true) {
                        value = CastProxy.requestCount
                        kotlinx.coroutines.delay(1000)
                    }
                }
                Text(
                    trf("La TV ha pedido %s archivos al móvil", requests),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall
                )
                if (vm.castStatus.isNotEmpty()) {
                    Text(vm.castStatus, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                }
                CastProxy.lastError?.let {
                    Text(it, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (vm.fireTvCompanionConnected == null) {
            PlayerControls(
                vm = vm,
                exo = vm.activePlayer,
                live = isLiveInfo(info),
                fullscreen = fullscreen,
                showExtras = true,
                onMinimize = { if (fullscreen) vm.fullscreen = false else vm.minimize() },
                onShare = { shareVideo(context, info) },
                onShareMoment = { shareVideoAt(context, info, vm.activePlayer.currentPosition) }
            )
        }
    }
}

@Composable
private fun AutoplayNextPrompt(vm: MainViewModel, card: VideoCard, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val progress = ((8 - vm.autoplayCountdown) / 8f).coerceIn(0f, 1f)
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = scheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = card.thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Siguiente recomendado"), style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                    Text(card.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        trf("Se reproducirá en %s", vm.autoplayCountdown),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = vm::playAutoplayNextNow, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = tr("Reproducir ahora"), tint = scheme.primary)
                }
                IconButton(onClick = vm::cancelAutoplayNext, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Close, contentDescription = tr("Cancelar"), tint = scheme.onSurfaceVariant)
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = scheme.primary,
                trackColor = Color.Transparent
            )
        }
    }
}

/** Pantalla completa: gira a horizontal y oculta las barras del sistema. */
@Composable
private fun FullscreenEffect(vm: MainViewModel) {
    val activity = LocalContext.current.findActivity()

    LaunchedEffect(vm.fullscreen, vm.fullscreenOrientation) {
        val act = activity ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (vm.fullscreen) {
            act.requestedOrientation = when (vm.fullscreenOrientation) {
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                "sensor" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Al salir de la pantalla del vídeo, deshacer todo
    DisposableEffect(Unit) {
        onDispose {
            val act = activity
            if (act != null) {
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            vm.fullscreen = false
        }
    }
}

@Composable
internal fun VideoView(exo: Player, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                useController = false // los controles los dibuja PlayerControls
                player = exo
            }
        },
        onRelease = { it.player = null }
    )
}

@Composable
private fun AmbientVideoView(exo: Player, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.view.LayoutInflater.from(ctx)
                .inflate(R.layout.player_view_ambient, FrameLayout(ctx), false)
                .apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    (this as PlayerView).apply {
                        useController = false
                        player = exo
                    }
                }
        },
        onRelease = { (it as? PlayerView)?.player = null }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Details(vm: MainViewModel, info: StreamInfo, modifier: Modifier) {
    LaunchedEffect(info.url, vm.deArrowEnabled) {
        vm.loadDeArrow(info.toCard())
    }
    val context = LocalContext.current
    val chapters = remember(info.url) {
        info.streamSegments
            .filter { it.startTimeSeconds >= 0 && !it.title.isNullOrBlank() }
            .distinctBy { it.startTimeSeconds }
            .sortedBy { it.startTimeSeconds }
    }
    var chaptersExpanded by remember(info.url) { mutableStateOf(false) }
    var currentPosition by remember(info.url) { mutableLongStateOf(0L) }
    LaunchedEffect(info.url, chaptersExpanded) {
        while (chaptersExpanded && chapters.isNotEmpty()) {
            currentPosition = vm.activePlayer.currentPosition
            kotlinx.coroutines.delay(500)
        }
    }
    val activeChapterStart = chapters
        .lastOrNull { it.startTimeSeconds * 1_000L <= currentPosition }
        ?.startTimeSeconds

    LazyColumn(modifier, contentPadding = PaddingValues(12.dp)) {
        item {
            vm.playbackError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            Text(
                vm.displayTitle(info), style = MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))

            // Canal: tocar la fila (o "Ver canal") abre sus vídeos
            val channelUrl = info.uploaderUrl
            val hasChannel = !channelUrl.isNullOrEmpty()
            val views = listOf(
                if (info.viewCount >= 0) trf("%s visualizaciones", formatCount(info.viewCount)) else "",
                uploadText(info.textualUploadDate, info.uploadDate)?.let { displayUploaded(it) }.orEmpty()
            ).filter { it.isNotBlank() }.joinToString(" · ")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = hasChannel) { vm.openChannel(channelUrl) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = info.uploaderAvatars.firstOrNull()?.url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(info.uploaderName ?: "", style = MaterialTheme.typography.titleSmall)
                    if (views.isNotEmpty()) Text(views, style = MaterialTheme.typography.bodySmall)
                }
                if (hasChannel) {
                    PillChip(tr("Ver canal"), selected = true) { vm.openChannel(channelUrl) }
                }
            }
            Spacer(Modifier.height(12.dp))

            // Todas las opciones visibles: se ajustan en varias líneas en vez de deslizar
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Like / dislike (locales: YouTube no permite votar sin cuenta)
                PillChip(
                    formatCount(info.likeCount).ifEmpty { tr("Me gusta") },
                    if (vm.vote == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    selected = vm.vote == 1
                ) { vm.setVote(1) }
                PillChip(
                    vm.dislikeCount?.let { formatCount(it) }
                        ?: if (vm.dislikeLoading) tr("Cargando...") else tr("No me gusta"),
                    if (vm.vote == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                    selected = vm.vote == -1
                ) { vm.setVote(-1) }
                if (hasChannel) {
                    val subscribed = vm.isSubscribed(channelUrl.orEmpty())
                    PillChip(
                        if (subscribed) tr("Suscrito") else tr("Suscribirse"),
                        selected = !subscribed
                    ) {
                        vm.toggleSubscribe(
                            ChannelCard(
                                channelUrl.orEmpty(),
                                info.uploaderName ?: "",
                                info.uploaderAvatars.firstOrNull()?.url,
                                -1
                            )
                        )
                    }
                }
                PillChip(tr("Compartir"), Icons.Default.Share) { shareVideo(context, info) }
                PillChip(tr("Ver en la web"), Icons.Default.Share) { vm.openExternal(info.toCard()) }
                PillChip(tr("Guardar"), Icons.Default.LibraryAdd) { vm.openSaveDialog(info.toCard()) }
                PillChip(tr("Música"), Icons.Default.MusicNote, selected = vm.audioOnly) { vm.toggleAudioOnly() }
                if (vm.audioOnly) {
                    PillChip(tr("Anterior")) { vm.skipPreviousTrack() }
                    PillChip(tr("Siguiente")) { vm.skipNextTrack() }
                    PillChip(
                        tr("Aleatorio"),
                        Icons.Default.Shuffle,
                        selected = vm.shuffleMusicQueue
                    ) { vm.toggleShuffleMusicQueue() }
                    PillChip(
                        tr("Repetir cola"),
                        Icons.Default.Repeat,
                        selected = vm.repeatMusicQueue
                    ) { vm.toggleRepeatMusicQueue() }
                }
                if (vm.queue.isNotEmpty()) {
                    PillChip(trf("Cola (%s)", vm.queue.size)) { vm.minimize(); vm.openLibraryList(LibList.Queue) }
                }
                PillChip(tr("Repetir"), Icons.Default.Repeat, selected = vm.loop) { vm.toggleLoop() }
                PillChip("TV", Icons.Default.Cast) { vm.openCastSheet() }
                if (vm.subtitleOptions.isNotEmpty() && !vm.hlsMode) {
                    PickerChip<org.schabi.newpipe.extractor.stream.SubtitlesStream?>(
                        label = tr("Subtítulos") + ": " + (vm.selSubtitle?.label() ?: tr("No")),
                        options = listOf<Pair<String, org.schabi.newpipe.extractor.stream.SubtitlesStream?>>(tr("Desactivados") to null) +
                            vm.subtitleOptions.take(40).map { it.label() to it },
                        onPick = vm::selectSubtitle
                    )
                }
                PickerChip(
                    label = tr("Descargar"),
                    options = listOf(tr("Vídeo con audio") to false, tr("Solo audio") to true),
                    onPick = { vm.download(info, it) }
                )
                if (vm.hlsMode && vm.hlsHeights.isNotEmpty()) {
                    PickerChip(
                        label = tr("Calidad") + ": " + (vm.hlsSelected?.let { "${it}p" } ?: "Auto"),
                        options = listOf<Pair<String, Int?>>(tr("Automática") to null) +
                            vm.hlsHeights.map { "${it}p" to it },
                        onPick = vm::selectHlsHeight
                    )
                } else if (!vm.hlsMode && vm.videoOptions.isNotEmpty()) {
                    PickerChip(
                        label = tr("Calidad") + ": " + (vm.selVideo?.label() ?: "-"),
                        options = vm.videoOptions.map { it.label() to it },
                        onPick = vm::selectVideo
                    )
                }
                if (!vm.hlsMode && vm.audioOptions.size > 1) {
                    PickerChip(
                        label = tr("Audio") + ": " + (vm.selAudio?.shortLabel() ?: "-"),
                        options = vm.audioOptions.map { it.label() to it },
                        onPick = vm::selectAudio
                    )
                }
            }

            vm.subtitleError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            vm.dislikeError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.downloadMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            var expanded by remember(info.url) { mutableStateOf(false) }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(14.dp)
            ) {
                val description = info.description?.content?.htmlToText() ?: ""
                ClickableText(
                    text = linkAndTimestampText(description, vm),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { offset ->
                        val link = linkAndTimestampText(description, vm).getStringAnnotations(
                            tag = "URL",
                            start = offset,
                            end = offset
                        ).firstOrNull()?.item
                        val timestamp = linkAndTimestampText(description, vm).getStringAnnotations(
                            tag = "TIME",
                            start = offset,
                            end = offset
                        ).firstOrNull()?.item
                        if (link != null) vm.openExternalUrl(link, tr("Abrir enlace"))
                        if (timestamp != null) vm.activePlayer.seekTo(parseCommentTimestamp(timestamp) * 1_000L)
                    }
                )
                Text(
                    if (expanded) tr("Ver menos") else tr("Ver más"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp).clickable { expanded = !expanded }
                )
            }
        }

        if (chapters.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${tr("Capítulos")} (${chapters.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { chaptersExpanded = !chaptersExpanded }) {
                        Text(tr(if (chaptersExpanded) "Ocultar capítulos" else "Mostrar capítulos"))
                    }
                }
            }
            if (chaptersExpanded) {
                itemsIndexed(chapters) { _, chapter ->
                    val active = chapter.startTimeSeconds == activeChapterStart
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (active) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .clickable {
                                vm.activePlayer.seekTo(chapter.startTimeSeconds * 1_000L)
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!chapter.previewUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = chapter.previewUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(width = 72.dp, height = 42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            formatClock(chapter.startTimeSeconds * 1_000L),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            chapter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (info.service.serviceId == org.schabi.newpipe.extractor.ServiceList.YouTube.serviceId && !isLiveInfo(info)) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Segmentos SponsorBlock"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::openSponsorContribution) { Text(tr("Contribuir")) }
                }
                TextButton(onClick = vm::openDeArrowContribution) {
                    Text(tr("Proponer título/miniatura a DeArrow"))
                }
                vm.sponsorBlockMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                vm.deArrowMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(vm.sponsorSegments, key = { it.uuid }) { segment ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sponsorCategoryLabel(segment.category), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${formatClock(segment.startMs)} – ${formatClock(segment.endMs)} · ${trf("%s votos", segment.votes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        enabled = !vm.sponsorActionLoading,
                        onClick = { vm.voteSponsorSegment(segment, 1) }
                    ) { Text("+") }
                    TextButton(
                        enabled = !vm.sponsorActionLoading,
                        onClick = { vm.voteSponsorSegment(segment, 0) }
                    ) { Text("−") }
                }
            }
        }

        // Comentarios justo debajo de la descripción, con botón para abrir/ocultar
        item {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tr("Comentarios"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                PillChip(if (vm.commentsOpen) tr("Ocultar") else tr("Ver comentarios"), selected = !vm.commentsOpen) {
                    vm.toggleComments()
                }
            }
        }

        if (vm.commentsOpen) {
            items(vm.comments) { CommentRow(vm, it) }

            item {
                vm.commentsError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                LoadMoreSentinel(vm.hasMoreComments, vm.commentsLoading, vm.comments.size, vm::loadMoreComments)
            }
        }

        if (vm.relatedContentEnabled) {
            item {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Recomendados"), style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            items(vm.related) { card -> VideoItem(vm, card, compact = true) }
            item {
                LaunchedEffect(vm.related.size, vm.current?.url) { vm.loadMoreRelated() }
                if (vm.relatedMoreLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            }
        }
    }
}

@Composable
private fun <T> PickerChip(label: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PillChip(label) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) })
            }
        }
    }
}

private fun linkAndTimestampText(html: String, vm: MainViewModel): AnnotatedString {
    val text = html.htmlToText()
    val urlRegex = Regex("""https?://[^\s<]+""")
    val timeRegex = Regex("""(?<!\d)(?:\d{1,2}:)?[0-5]?\d:[0-5]\d(?!\d)""")
    return buildAnnotatedString {
        append(text)
        urlRegex.findAll(text).forEach { match ->
            addStyle(SpanStyle(color = androidx.compose.ui.graphics.Color.Blue, textDecoration = TextDecoration.Underline), match.range.first, match.range.last + 1)
            addStringAnnotation("URL", match.value.trimEnd('.', ',', ')'), match.range.first, match.range.last + 1)
        }
        timeRegex.findAll(text).forEach { match ->
            addStyle(SpanStyle(color = androidx.compose.ui.graphics.Color.Blue, textDecoration = TextDecoration.Underline), match.range.first, match.range.last + 1)
            addStringAnnotation("TIME", match.value, match.range.first, match.range.last + 1)
        }
    }
}

private fun parseCommentTimestamp(value: String): Long {
    val parts = value.split(":").mapNotNull { it.toLongOrNull() }
    var seconds = 0L
    parts.forEach { seconds = seconds * 60 + it }
    return seconds
}

@Composable
internal fun CommentRow(vm: MainViewModel, c: CommentsInfoItem) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        AsyncImage(
            model = c.uploaderAvatars.firstOrNull()?.url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(36.dp).clip(CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            val pinned = if (c.isPinned) "📌 " else ""
            Text(
                "$pinned${c.uploaderName ?: ""} · ${c.textualUploadDate ?: ""}",
                style = MaterialTheme.typography.labelMedium
            )
            val comment = c.commentText?.content ?: ""
            ClickableText(
                text = linkAndTimestampText(comment, vm),
                style = MaterialTheme.typography.bodyMedium,
                onClick = { offset ->
                    val annotated = linkAndTimestampText(comment, vm)
                    val link = annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.item
                    val timestamp = annotated.getStringAnnotations("TIME", offset, offset).firstOrNull()?.item
                    if (link != null) vm.openExternalUrl(link, tr("Abrir enlace"))
                    if (timestamp != null) vm.activePlayer.seekTo(parseCommentTimestamp(timestamp) * 1_000L)
                }
            )
            if (c.likeCount > 0) {
                Text("👍 ${formatCount(c.likeCount.toLong())}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
