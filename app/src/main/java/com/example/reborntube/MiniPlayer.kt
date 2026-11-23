package com.example.reborntube

import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File

val MiniPlayerBottomSpace = 80.dp

/** Mini reproductor acoplado al pie, con gestos de deslizar para expandir o cerrar. */
@Composable
fun MiniPlayer(vm: MainViewModel, info: StreamInfo) {
    val context = LocalContext.current
    val exo = androidx.compose.runtime.remember { PlayerHolder.get(context) }
    val swipeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    val thumbnail = info.thumbnails.maxByOrNull { it.width.toLong() * it.height.toLong() }?.url

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(68.dp)
            .clickable { vm.expand() }
            .pointerInput(info.url) {
                var verticalDistance = 0f
                detectVerticalDragGestures(
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        verticalDistance += amount
                    },
                    onDragEnd = {
                        when {
                            verticalDistance > swipeThreshold -> vm.closePlayer()
                            verticalDistance < -swipeThreshold -> vm.expand()
                        }
                    },
                    onDragCancel = { verticalDistance = 0f }
                )
            },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(112.dp)
                    .fillMaxHeight()
                    .background(Color.Black)
                    .clickable { vm.expand() }
            ) {
                if (vm.audioOnly) {
                    AsyncImage(
                        model = thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            (LayoutInflater.from(ctx).inflate(R.layout.mini_player_view, FrameLayout(ctx), false) as PlayerView)
                                .also { it.player = exo }
                        },
                        onRelease = { it.player = null }
                    )
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { vm.expand() }
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    info.name.orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    info.uploaderName.orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            IconButton(onClick = vm::togglePlay) {
                Icon(
                    if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (vm.isPlaying) tr("Pausar") else tr("Reproducir")
                )
            }
            IconButton(onClick = vm::closePlayer) {
                Icon(Icons.Default.Close, contentDescription = tr("Cerrar"))
            }
        }
    }
}

@Composable
fun OfflineAudioMiniPlayer(vm: MainViewModel, record: DownloadRecord) {
    val cover = record.artworkPath?.let(::File)?.takeIf { it.isFile } ?: record.thumb
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(68.dp)
            .clickable { vm.expand() },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(64.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .clickable { vm.expand() }
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { vm.expand() }
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(record.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    record.channel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = vm::togglePlay) {
                Icon(
                    if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (vm.isPlaying) tr("Pausar") else tr("Reproducir")
                )
            }
            IconButton(onClick = vm::closeOffline) {
                Icon(Icons.Default.Close, contentDescription = tr("Cerrar reproductor"))
            }
        }
    }
}
