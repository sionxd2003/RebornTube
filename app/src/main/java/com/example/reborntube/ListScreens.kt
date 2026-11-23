package com.example.reborntube

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubscriptionsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var editingChannel by remember { mutableStateOf<ChannelCard?>(null) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(tr("Suscripciones"), onBack = vm::onBack) {
            IconButton(onClick = { groupName = ""; showCreateGroup = true }) {
                Icon(Icons.Default.Add, contentDescription = tr("Nuevo grupo"))
            }
        }
        OutlinedButton(
            onClick = { ChannelUpdateWorker.enqueueNow(context) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) { Text(tr("Forzar revisión ahora")) }

        if (vm.channelGroups.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PillChip(tr("Todos"), selected = selectedGroup == null) { selectedGroup = null }
                vm.channelGroups.forEach { group ->
                    PillChip(group.name, selected = selectedGroup == group.id) { selectedGroup = group.id }
                }
            }
        }

        if (vm.subscriptions.isEmpty()) {
            Text(
                tr("Aún no sigues a ningún canal. Entra a un canal y pulsa Suscribirse."),
                modifier = Modifier.padding(12.dp)
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (vm.miniVisible) MiniPlayerBottomSpace else 0.dp)
        ) {
            val visible = vm.subscriptions.filter { ch ->
                selectedGroup == null || vm.channelInGroup(selectedGroup!!, ch.url)
            }
            items(visible) { ch ->
                ChannelRow(ch, onClick = { vm.openChannel(ch.url) }, onManageGroups = { editingChannel = ch })
            }
        }
    }

    if (showCreateGroup) {
        AlertDialog(
            onDismissRequest = { showCreateGroup = false },
            title = { Text(tr("Nuevo grupo")) },
            text = {
                OutlinedTextField(
                    value = groupName, onValueChange = { groupName = it },
                    label = { Text(tr("Nombre del grupo")) }, singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.createChannelGroup(groupName); showCreateGroup = false }) { Text(tr("Crear")) }
            },
            dismissButton = { TextButton(onClick = { showCreateGroup = false }) { Text(tr("Cancelar")) } }
        )
    }

    editingChannel?.let { channel ->
        AlertDialog(
            onDismissRequest = { editingChannel = null },
            title = { Text(tr("Grupos de canales")) },
            text = {
                Column {
                    if (vm.channelGroups.isEmpty()) Text(tr("Crea un grupo con el botón + para organizar tus canales."))
                    vm.channelGroups.forEach { group ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(
                                checked = vm.channelInGroup(group.id, channel.url),
                                onCheckedChange = { vm.toggleChannelInGroup(group.id, channel.url) }
                            )
                            Text(group.name, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                if (selectedGroup == group.id) selectedGroup = null
                                vm.deleteChannelGroup(group.id)
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = tr("Eliminar grupo"))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editingChannel = null }) { Text(tr("Listo")) } }
        )
    }
}

@Composable
fun LiveScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(tr("Directos"), onBack = vm::onBack) {
            TextButton(onClick = vm::loadLive) { Text(tr("Actualizar")) }
        }

        if (vm.liveLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.liveError?.let { Text(it, modifier = Modifier.padding(12.dp)) }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (vm.miniVisible) MiniPlayerBottomSpace else 0.dp)
        ) {
            items(vm.liveList) { card -> VideoItem(vm, card, compact = true) }
        }
    }
}

@Composable
fun NewsScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(tr("Novedades"), onBack = vm::onBack) {
            TextButton(onClick = vm::loadNews) { Text(tr("Actualizar")) }
        }
        if (vm.newsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.newsError?.let { Text(it, modifier = Modifier.padding(12.dp)) }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (vm.miniVisible) MiniPlayerBottomSpace else 0.dp)
        ) {
            items(vm.news) { card -> VideoItem(vm, card, compact = true) }
        }
    }
}

@Composable
fun TrendingScreen(vm: MainViewModel) {
    val title = when (vm.trendingKind) {
        "gaming" -> tr("Tendencias videojuegos")
        "music" -> tr("Tendencias música")
        "movies" -> tr("Tendencias películas y series")
        "podcasts" -> tr("Tendencias pódcasts")
        else -> tr("Tendencias")
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, onBack = vm::onBack) {
            TextButton(onClick = vm::loadTrending) { Text(tr("Actualizar")) }
        }
        if (vm.trendingLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.trendingError?.let { Text(it, modifier = Modifier.padding(12.dp)) }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (vm.miniVisible) MiniPlayerBottomSpace else 0.dp)
        ) {
            items(vm.trending) { card -> VideoItem(vm, card, compact = true) }
        }
    }
}
