package com.example.reborntube

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun PlaylistsScreen(vm: MainViewModel) {
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(tr("Listas"), onBack = { vm.libraryList = null }) {
            TextButton(onClick = { creating = true }) { Text(tr("Nueva lista")) }
        }

        if (vm.playlists.isEmpty()) {
            NoteBox(tr("Aún no tienes listas. Crea una y guarda vídeos desde el reproductor, desde los shorts, o con una pulsación larga sobre cualquier vídeo."))
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (vm.miniVisible) MiniPlayerBottomSpace else 0.dp)
        ) {
            items(vm.playlists) { pl ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { vm.openPlaylistId = pl.id }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        val thumb = pl.items.firstOrNull()?.thumb
                        if (thumb != null) {
                            AsyncImage(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.Default.LibraryAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Row(Modifier.weight(1f).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(
                                trf("%s vídeos", pl.items.size), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { vm.deletePlaylist(pl.id) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = tr("Borrar lista"))
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        NewListDialog(onDismiss = { creating = false }) { name ->
            vm.createPlaylist(name)
            creating = false
        }
    }
}

@Composable
fun PlaylistDetailScreen(vm: MainViewModel, id: String) {
    val playlist = vm.playlists.firstOrNull { it.id == id }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(playlist?.name ?: tr("Lista"), onBack = { vm.openPlaylistId = null })

        if (playlist == null || playlist.items.isEmpty()) {
            NoteBox(tr("Esta lista está vacía."))
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (vm.miniVisible) MiniPlayerBottomSpace else 0.dp)
        ) {
            items(playlist?.items ?: emptyList()) { card ->
                // La X quita el vídeo de esta lista
                VideoItem(vm, card, onRemove = { vm.togglePlaylistItem(id, card) }, compact = true)
            }
        }
    }
}

@Composable
fun NewListDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Nueva lista")) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(tr("Nombre de la lista")) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text(tr("Crear")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } }
    )
}

/** Elige en qué listas guardar el vídeo (puede estar en varias) o crea una nueva. */
@Composable
fun SaveToListDialog(vm: MainViewModel, card: VideoCard) {
    var creating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = vm::closeSaveDialog,
        title = { Text(tr("Guardar en lista")) },
        text = {
            Column {
                if (vm.playlists.isEmpty()) {
                    Text(tr("Aún no tienes listas. Crea la primera."))
                }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(vm.playlists) { pl ->
                        val inList = pl.items.any { it.url == card.url }
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.togglePlaylistItem(pl.id, card) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = inList, onCheckedChange = { vm.togglePlaylistItem(pl.id, card) })
                            Text(pl.name, modifier = Modifier.weight(1f))
                            Text("${pl.items.size}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(onClick = { creating = true }) { Text(tr("+ Nueva lista")) }
            }
        },
        confirmButton = { TextButton(onClick = vm::closeSaveDialog) { Text(tr("Listo")) } }
    )

    if (creating) {
        NewListDialog(onDismiss = { creating = false }) { name ->
            vm.createPlaylist(name, firstCard = card)
            creating = false
        }
    }
}

/** Fila de una lista de YouTube: tocar la abre, el botón la guarda en tus listas. */
@Composable
fun RemotePlaylistRow(rp: RemotePlaylist, onClick: () -> Unit, onSave: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = rp.thumb,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(rp.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            if (rp.count >= 0) Text(trf("%s vídeos", rp.count), style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onSave) {
            Icon(Icons.Default.LibraryAdd, contentDescription = tr("Agregar a mis listas"))
        }
    }
}

@Composable
fun RemotePlaylistScreen(vm: MainViewModel) {
    val url = vm.remoteUrl ?: return

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(vm.remoteName.ifBlank { tr("Lista") }, onBack = vm::closeRemote) {
            TextButton(onClick = { vm.saveRemotePlaylist(url, vm.remoteName) }) { Text(tr("Guardar en mis listas")) }
        }
        vm.listMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        vm.remoteError?.let { Text(it, modifier = Modifier.padding(12.dp)) }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (vm.miniVisible) MiniPlayerBottomSpace else 0.dp)
        ) {
            items(vm.remoteItems) { card -> VideoItem(vm, card, compact = true) }
            item {
                LoadMoreSentinel(vm.remoteHasMore, vm.remoteLoading, vm.remoteItems.size, vm::loadMoreRemote)
            }
        }
    }
}
