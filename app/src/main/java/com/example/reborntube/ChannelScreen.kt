package com.example.reborntube

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import coil.compose.AsyncImage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChannelScreen(vm: MainViewModel) {
    val info = vm.channel

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = if (vm.miniVisible) MiniPlayerBottomSpace else 0.dp)
    ) {
        item {
            ScreenHeader(tr("Canal"), onBack = vm::closeChannel)

            if (vm.channelLoading && info == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (info != null) {
                // Banner del canal
                val banner = info.banners.lastOrNull()?.url
                if (banner != null) {
                    AsyncImage(
                        model = banner,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).aspectRatio(6.2f)
                            .clip(RoundedCornerShape(20.dp))
                    )
                }
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = info.avatars.firstOrNull()?.url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(CircleShape)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            info.name ?: "", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        if (info.subscriberCount >= 0) {
                            Text(
                                trf("%s suscriptores", formatCount(info.subscriberCount)),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // Suscripción local (se guarda en el móvil)
                val subscribed = vm.isSubscribed(info.url)
                val toggle = {
                    vm.toggleSubscribe(
                        ChannelCard(info.url, info.name ?: "", info.avatars.firstOrNull()?.url, info.subscriberCount)
                    )
                }
                val notificationsEnabled = vm.isChannelNotificationsEnabled(info.url)
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        vm.setChannelNotificationsEnabled(info.url, true)
                    }
                }
                var ringBell by remember(info.url) { mutableStateOf(false) }
                val bellRotation = remember(info.url) { Animatable(0f) }
                val bellScale by animateFloatAsState(
                    targetValue = if (notificationsEnabled) 1f else 0.88f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "bellScale"
                )
                LaunchedEffect(ringBell) {
                    if (!ringBell) return@LaunchedEffect
                    bellRotation.animateTo(-12f, tween(70))
                    bellRotation.animateTo(12f, tween(90))
                    bellRotation.animateTo(-8f, tween(90))
                    bellRotation.animateTo(8f, tween(90))
                    bellRotation.animateTo(0f, tween(120))
                    ringBell = false
                }
                Row(
                    Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PillChip(if (subscribed) tr("Suscrito") else tr("Suscribirse"), selected = !subscribed) { toggle() }
                    Spacer(Modifier.weight(1f))
                    if (subscribed) {
                        IconButton(
                            modifier = Modifier.size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            onClick = {
                                ringBell = true
                                if (notificationsEnabled) {
                                    vm.setChannelNotificationsEnabled(info.url, false)
                                } else if (Build.VERSION.SDK_INT >= 33) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    vm.setChannelNotificationsEnabled(info.url, true)
                                }
                            }
                        ) {
                            Icon(
                                if (notificationsEnabled) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                                contentDescription = tr(if (notificationsEnabled) "Desactivar notificaciones" else "Activar notificaciones"),
                                tint = if (notificationsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.graphicsLayer {
                                    rotationZ = bellRotation.value
                                    scaleX = bellScale
                                    scaleY = bellScale
                                }
                            )
                        }
                    }
                }

                val desc = info.description
                if (!desc.isNullOrBlank()) {
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(14.dp)
                    )
                }

                // Secciones del canal (se ajustan en varias líneas, no hace falta deslizar)
                FlowRow(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PillChip(tr("Vídeos"), selected = vm.channelSection == 0) { vm.selectChannelSection(0) }
                    if (vm.hasLiveTab) {
                        PillChip(tr("Directos"), selected = vm.channelSection == 1) { vm.selectChannelSection(1) }
                    }
                    if (vm.hasShortsTab) {
                        PillChip(tr("Shorts"), selected = vm.channelSection == 2) { vm.selectChannelSection(2) }
                    }
                    if (vm.hasPlaylistsTab) {
                        PillChip(tr("Listas"), selected = vm.channelSection == 3) { vm.selectChannelSection(3) }
                    }
                }
            }

            vm.listMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            vm.channelError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }
        }

        if (vm.channelSection == 3) {
            // Listas del canal: tocar abre la lista; el "+" la guarda en tus listas
            items(vm.channelPlaylists) { rp ->
                RemotePlaylistRow(
                    rp,
                    onClick = { vm.openRemotePlaylist(rp) },
                    onSave = { vm.saveRemotePlaylist(rp.url, rp.name) }
                )
            }
        } else {
            itemsIndexed(vm.channelVideos) { index, card ->
                if (vm.channelSection == 2) {
                    if (index % 3 == 0) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            repeat(3) { column ->
                                val shortIndex = index + column
                                val short = vm.channelVideos.getOrNull(shortIndex)
                                if (short == null) Spacer(Modifier.weight(1f))
                                else ChannelShortTile(short, Modifier.weight(1f)) {
                                    vm.openShorts(vm.channelVideos, shortIndex)
                                }
                            }
                        }
                    }
                } else {
                    VideoItem(vm, card, compact = true)
                }
            }
        }

        item {
            if (info != null) {
                LoadMoreSentinel(
                    hasMore = vm.channelHasMore,
                    loading = vm.channelLoading,
                    key = vm.channelVideos.size + vm.channelPlaylists.size,
                    onLoad = vm::loadMoreChannelVideos
                )
            }
        }
    }
}

@Composable
private fun ChannelShortTile(card: VideoCard, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.padding(horizontal = 2.dp, vertical = 4.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.56f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick)
        ) {
            AsyncImage(
                model = card.thumb,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (card.views >= 0) {
                Text(
                    formatCount(card.views),
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            card.title,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 4.dp)
        )
    }
}
