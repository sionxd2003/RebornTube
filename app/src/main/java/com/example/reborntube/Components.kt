package com.example.reborntube

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Fila de vídeo conectada al ViewModel: abrir y menú de tres puntos (listas, más tarde, cola, canal). */
@Composable
fun VideoItem(vm: MainViewModel, card: VideoCard, onRemove: (() -> Unit)? = null, compact: Boolean = false) {
    val later = vm.isLater(card.url)
    val displayCard = vm.displayCard(card)
    LaunchedEffect(card.url, vm.deArrowEnabled) {
        vm.loadDeArrow(card)
    }
    LaunchedEffect(card.url, card.isLive, vm.dataSaverEnabled) {
        if (!vm.dataSaverEnabled && !card.isLive && card.url.isNotBlank()) {
            YouTubeRepository.prefetch(card.url)
        }
    }
    val options = buildList<Pair<String, () -> Unit>> {
        add(tr("Guardar en lista") to { vm.openSaveDialog(card) })
        add((if (later) tr("Quitar de Ver más tarde") else tr("Ver más tarde")) to { vm.toggleLater(card) })
        add(tr("Añadir a la cola") to { vm.addToQueue(card, next = false) })
        add(tr("Reproducir a continuación") to { vm.addToQueue(card, next = true) })
        add(tr("Abrir con otra app") to { vm.openExternal(card) })
        if (!card.channelUrl.isNullOrEmpty()) add(tr("Ir al canal") to { vm.openChannel(card.channelUrl) })
    }
    VideoRow(
        card = displayCard,
        progress = vm.fractionOf(card),
        onClick = { vm.open(card) },
        onRemove = onRemove,
        onLongClick = { vm.openSaveDialog(card) }, // pulsación larga: guardar en una lista
        menuItems = options,
        exactDate = vm.exactDate(card),
        resolveDate = { vm.resolveDate(card) },
        avatarUrl = vm.avatarFor(card),
        resolveAvatar = { vm.resolveAvatar(card) },
        compact = compact,
        hideThumbnail = vm.dataSaverEnabled
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoRow(
    card: VideoCard,
    progress: Float,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    menuItems: List<Pair<String, () -> Unit>> = emptyList(),
    exactDate: Long? = null,
    resolveDate: (suspend () -> Unit)? = null,
    avatarUrl: String? = null,
    resolveAvatar: (suspend () -> Unit)? = null,
    compact: Boolean = false,
    hideThumbnail: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    // Mientras el vídeo está en pantalla se busca su fecha real de subida (se guarda para no repetirlo)
    LaunchedEffect(card.url) { resolveDate?.invoke() }
    LaunchedEffect(card.url, avatarUrl, hideThumbnail) {
        if (!hideThumbnail && avatarUrl == null) resolveAvatar?.invoke()
    }
    val thumbnail: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(if (compact) 12.dp else 18.dp)).background(scheme.surfaceVariant)) {
            if (!hideThumbnail) {
                AsyncImage(
                    model = card.thumb, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            }
            if (card.isLive) {
                Text(
                    tr("EN DIRECTO"), color = scheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                        .clip(RoundedCornerShape(8.dp)).background(scheme.primary)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            } else if (card.duration > 0) {
                Text(
                    formatDuration(card.duration), color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(if (compact) 5.dp else 10.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            // Barrita con el color del tema: por dónde ibas en este vídeo
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    color = scheme.primary, trackColor = Color.Transparent
                )
            }
        }
    }
    val details: @Composable RowScope.() -> Unit = {
            if (!compact) ChannelAvatar(card.channel, avatarUrl.takeUnless { hideThumbnail })
            Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    card.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall
                )
                val meta = buildList {
                    if (card.channel.isNotBlank()) add(card.channel)
                    if (!card.isLive && card.views >= 0) add(trf("%s vistas", formatCount(card.views)))
                    val uploaded = card.uploaded?.let { displayUploaded(it) } ?: exactDate?.let { uploadLabel(it) }
                    if (!card.isLive && !uploaded.isNullOrBlank()) add(uploaded)
                }.joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant
                    )
                }
            }
            if (menuItems.isNotEmpty()) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = tr("Más opciones"), tint = scheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        menuItems.forEach { (label, action) ->
                            DropdownMenuItem(
                                text = { Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1) },
                                onClick = { menuOpen = false; action() },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = tr("Quitar de la lista"), tint = scheme.onSurfaceVariant)
                }
            }
    }
    if (compact) {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(Modifier.weight(0.92f)) { thumbnail() }
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1.08f), verticalAlignment = Alignment.Top, content = details)
        }
    } else {
        Column(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            thumbnail()
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top, content = details)
        }
    }
}

@Composable
fun ChannelRow(card: ChannelCard, onClick: () -> Unit, onManageGroups: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = card.thumb,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(card.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
            val subs = if (card.subscribers >= 0) trf("%s suscriptores · ", formatCount(card.subscribers)) else ""
            Text(subs + tr("Canal"), style = MaterialTheme.typography.bodySmall)
        }
        if (onManageGroups != null) {
            IconButton(onClick = onManageGroups) {
                Icon(Icons.Default.MoreVert, contentDescription = tr("Grupos de canales"))
            }
        }
    }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Volver"))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title, style = MaterialTheme.typography.headlineSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Pantalla que aparece al instante al tocar un vídeo, mientras se prepara. Atrás la cancela. */
@Composable
fun OpeningOverlay(card: VideoCard) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = card.thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.5f,
                    modifier = Modifier.fillMaxSize()
                )
                CircularProgressIndicator()
            }
            Text(card.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
            Text(
                card.channel,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}

/**
 * Al llegar al final de una lista (cuando este elemento aparece en pantalla) pide la siguiente tanda
 * automáticamente. Si una carga falla no se repite en bucle: se reintenta al volver a pasar por aquí.
 */
@Composable
fun LoadMoreSentinel(hasMore: Boolean, loading: Boolean, key: Any, onLoad: () -> Unit) {
    var requested by remember { mutableStateOf<Any?>(null) }
    if (loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
    LaunchedEffect(key, hasMore, loading) {
        if (hasMore && !loading && requested != key) {
            requested = key
            onLoad()
        }
    }
}
