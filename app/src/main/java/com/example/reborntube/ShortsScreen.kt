package com.example.reborntube

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Pestaña "Shorts": entras directo al visor vertical (como los reels). Desliza arriba o abajo
 * para cambiar de short; sigue cargando más al acercarte al final.
 */
@Composable
fun ShortsScreen(vm: MainViewModel) {
    LaunchedEffect(Unit) {
        if (vm.shorts.isEmpty()) vm.loadShorts(reset = true)
    }
    // Al salir de la pestaña se detiene el short
    DisposableEffect(Unit) {
        onDispose { vm.stopShortsPlayback() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (vm.shorts.isEmpty()) {
            if (vm.shortsLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(vm.shortsError ?: tr("No hay shorts por ahora"), color = Color.White)
                    TextButton(onClick = { vm.loadShorts(reset = true) }) { Text(tr("Reintentar")) }
                }
            }
        } else {
            ShortsPager(
                vm = vm,
                items = vm.shorts,
                startIndex = 0,
                canLoadMore = true,
                embedded = true,
                onClose = {}
            )
        }
    }
}

/** Visor de shorts de un canal (se abre encima de todo, con flecha para cerrar). */
@Composable
fun ShortsViewer(vm: MainViewModel, session: ShortsSession) {
    Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        ShortsPager(
            vm = vm,
            items = vm.shortsSession?.items ?: session.items,
            startIndex = session.index,
            canLoadMore = session.canLoadMore,
            embedded = false,
            onClose = vm::closeShorts
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortsPager(
    vm: MainViewModel,
    items: List<VideoCard>,
    startIndex: Int,
    canLoadMore: Boolean,
    embedded: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val exo = remember { PlayerHolder.get(context) }
    val scheme = MaterialTheme.colorScheme
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    var showComments by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(pager.settledPage) {
        items.getOrNull(pager.settledPage)?.let { vm.playShort(it) }
        if (canLoadMore && pager.settledPage >= items.size - 3) vm.loadMoreShorts()
    }
    LaunchedEffect(exo) {
        while (true) {
            if (!dragging) {
                position = exo.currentPosition
                duration = exo.duration.coerceAtLeast(0L)
            }
            delay(250)
        }
    }

    val currentCard = items.getOrNull(pager.currentPage)

    Box(Modifier.fillMaxSize()) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val card = items.getOrNull(page)
            if (card != null) {
                Box(Modifier.fillMaxSize().clickable { vm.togglePlay() }) {
                    if (page == pager.settledPage) {
                        VideoView(exo, Modifier.fillMaxSize())
                    } else {
                        AsyncImage(
                            model = card.thumb,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // Indicador de pausa
                    if (page == pager.settledPage && !vm.isPlaying && !vm.isBuffering && !vm.shortPlayLoading) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = tr("Reproducir"),
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.align(Alignment.Center).size(80.dp)
                        )
                    }
                    Column(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth(0.78f)
                            .padding(start = 16.dp, end = 16.dp, bottom = 52.dp)
                    ) {
                        Text(card.channel, color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(
                            card.title,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // Botones laterales (actúan sobre el short que se está viendo)
        if (currentCard != null) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 8.dp, bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val likes = vm.shortInfo?.likeCount ?: -1L
                ShortAction(
                    if (vm.shortVote == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    formatCount(likes).ifEmpty { tr("Me gusta") },
                    if (vm.shortVote == 1) scheme.primary else Color.White
                ) { vm.setShortVote(1) }
                ShortAction(
                    if (vm.shortVote == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                    tr("No me gusta"),
                    if (vm.shortVote == -1) scheme.primary else Color.White
                ) { vm.setShortVote(-1) }
                ShortAction(Icons.Default.ChatBubble, tr("Comentarios")) { showComments = true }
                val later = vm.isLater(currentCard.url)
                ShortAction(
                    if (later) Icons.Filled.WatchLater else Icons.Outlined.WatchLater,
                    tr("Más tarde"),
                    if (later) scheme.primary else Color.White
                ) { vm.toggleLater(currentCard) }
                ShortAction(Icons.Default.LibraryAdd, tr("Guardar")) { vm.openSaveDialog(currentCard) }
                ShortAction(Icons.Default.Share, tr("Compartir")) {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, "${currentCard.title}\n${currentCard.url}")
                    }
                    context.startActivity(android.content.Intent.createChooser(send, tr("Compartir short")))
                }
            }
        }

        if (!embedded) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x66000000), CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Cerrar"), tint = Color.White)
            }
        }

        if (vm.isBuffering || vm.shortPlayLoading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = scheme.primary)
        }
        vm.shortPlayError?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }

        // Al arrastrar la barra se ve el segundo al que vas a saltar
        if (dragging && duration > 0) {
            Text(
                formatClock((dragValue * duration).toLong()),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.Center)
                    .background(Color(0xAA000000), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Barra de tiempo: se puede arrastrar para adelantar o retroceder
        Slider(
            value = if (dragging) dragValue
            else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                if (duration > 0) exo.seekTo((dragValue * duration).toLong())
                dragging = false
            },
            colors = SliderDefaults.colors(
                thumbColor = scheme.primary,
                activeTrackColor = scheme.primary,
                inactiveTrackColor = Color(0x55FFFFFF)
            ),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp)
        )
    }

    if (showComments) {
        ModalBottomSheet(onDismissRequest = { showComments = false }) {
            Text(
                tr("Comentarios"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(Modifier.fillMaxHeight(0.6f), contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(vm.comments) { CommentRow(vm, it) }
                item {
                    vm.commentsError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    LoadMoreSentinel(vm.hasMoreComments, vm.commentsLoading, vm.comments.size, vm::loadMoreComments)
                }
            }
        }
    }
}

@Composable
private fun ShortAction(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(4.dp)
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(Color(0x77000000)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint)
        }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
