package com.example.reborntube

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun LibraryScreen(vm: MainViewModel, onExport: () -> Unit, onImport: () -> Unit, onTakeout: () -> Unit) {
    when (val which = vm.libraryList) {
        null -> LibraryMenu(vm, onExport, onImport, onTakeout)
        LibList.Playlists -> {
            val id = vm.openPlaylistId
            if (id == null) PlaylistsScreen(vm) else PlaylistDetailScreen(vm, id)
        }
        LibList.Downloads -> DownloadsScreen(vm)
        else -> LibraryListScreen(vm, which)
    }
}

@Composable
private fun LibraryMenu(vm: MainViewModel, onExport: () -> Unit, onImport: () -> Unit, onTakeout: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = if (vm.miniVisible) MiniPlayerBottomSpace else 0.dp)
    ) {
        Text(
            tr("Biblioteca"), style = MaterialTheme.typography.headlineMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 12.dp)
        )
        // Cuadrícula de dos columnas con tarjetas grandes (filas normales: así cada tarjeta mide bien)
        val tiles = listOf(
            TileData(Icons.Default.History, tr("Historial"), trf("%s vídeos", vm.historyList.size),
                vm.historyList.firstOrNull()?.thumb) { vm.libraryList = LibList.History },
            TileData(Icons.Default.ThumbUp, tr("Me gusta"), trf("%s vídeos", vm.likedList.size),
                vm.likedList.firstOrNull()?.thumb) { vm.libraryList = LibList.Liked },
            TileData(Icons.Default.ThumbDown, tr("No me gusta"), trf("%s vídeos", vm.dislikedList.size),
                vm.dislikedList.firstOrNull()?.thumb) { vm.libraryList = LibList.Disliked },
            TileData(Icons.Default.WatchLater, tr("Más tarde"), trf("%s vídeos", vm.laterList.size),
                vm.laterList.firstOrNull()?.thumb) { vm.libraryList = LibList.Later },
            TileData(Icons.Default.Queue, tr("Cola"), trf("%s vídeos", vm.queue.size),
                vm.queue.firstOrNull()?.thumb) { vm.libraryList = LibList.Queue },
            TileData(Icons.Default.Bookmarks, tr("Listas"), trf("%s listas", vm.playlists.size),
                vm.playlists.firstNotNullOfOrNull { pl -> pl.items.firstOrNull()?.thumb }) {
                vm.libraryList = LibList.Playlists
            },
            TileData(Icons.Default.Download, tr("Descargas"), trf("%s archivos", vm.downloads.size),
                vm.downloads.firstOrNull()?.thumb) { vm.libraryList = LibList.Downloads }
        )
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { LibraryTile(it, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        SectionHeader(tr("Copia de seguridad"))
        Text(
            tr("Guarda en un zip tus me gusta, no me gusta, suscripciones, historial, \"Más tarde\", listas y el progreso de los vídeos, y recupéralos cuando quieras."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(onClick = onExport) { Text(tr("Exportar zip")) }
            FilledTonalButton(onClick = onImport) { Text(tr("Importar zip")) }
        }
        FilledTonalButton(
            onClick = onTakeout,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) { Text(tr("Importar de Google Takeout")) }
        Text(
            tr("Takeout (takeout.google.com) te deja descargar el historial y las suscripciones de tu cuenta de Google. Sube aquí el zip, o el archivo de historial o de suscripciones."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        vm.backupMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
        }
    }
}

private class TileData(
    val icon: ImageVector, val title: String, val subtitle: String, val thumb: String?, val onClick: () -> Unit
)

@Composable
private fun LibraryTile(t: TileData, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = t.onClick)
            .padding(10.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            if (t.thumb != null) {
                AsyncImage(t.thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(t.icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Row(Modifier.padding(top = 10.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(t.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    t.subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun LibraryListScreen(vm: MainViewModel, which: LibList) {
    val title: String
    val list: List<VideoCard>
    when (which) {
        LibList.History -> { title = tr("Historial"); list = vm.historyList }
        LibList.Liked -> { title = tr("Me gusta"); list = vm.likedList }
        LibList.Disliked -> { title = tr("No me gusta"); list = vm.dislikedList }
        LibList.Later -> { title = tr("Más tarde"); list = vm.laterList }
        LibList.Queue -> { title = tr("Cola"); list = vm.queue }
        else -> { title = ""; list = emptyList() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, onBack = { vm.libraryList = null }) {
            if (which == LibList.History && list.isNotEmpty()) {
                TextButton(onClick = vm::clearHistory) { Text(tr("Borrar todo")) }
            }
            if (which == LibList.Queue && list.isNotEmpty()) {
                TextButton(onClick = vm::clearQueue) { Text(tr("Vaciar cola")) }
            }
        }

        if (list.isEmpty()) {
            NoteBox(tr("Aún no hay vídeos aquí."))
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (vm.miniVisible) MiniPlayerBottomSpace else 0.dp)
        ) {
            items(list) { card ->
                // X para quitar ese vídeo de la lista (el botón "Borrar todo" sigue en el historial)
                val remove: (() -> Unit)? = when (which) {
                    LibList.History -> { { vm.removeFromHistory(card) } }
                    LibList.Liked, LibList.Disliked -> { { vm.removeVote(card) } }
                    LibList.Queue -> { { vm.removeFromQueue(card) } }
                    else -> null
                }
                VideoItem(vm, card, onRemove = remove, compact = true)
            }
        }
    }
}
