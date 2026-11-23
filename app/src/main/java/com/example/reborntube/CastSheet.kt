package com.example.reborntube

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import kotlinx.coroutines.launch

/**
 * Lista de dispositivos para transmitir (como en YouTube): busca Chromecast y TVs con Google Cast
 * en la red y deja elegir uno. Si no aparece ninguno, ofrece duplicar la pantalla.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastSheet(vm: MainViewModel) {
    val context = LocalContext.current
    val castContext = vm.castContext
    val routes = remember { mutableStateListOf<MediaRouter.RouteInfo>() }

    // Fire TV y TVs DLNA: se buscan por la Wi-Fi mientras la hoja está abierta
    val scope = rememberCoroutineScope()
    val scanner = remember { DeviceScanner(context) }
    DisposableEffect(Unit) {
        scanner.start(scope)
        onDispose { scanner.stop() }
    }
    val others = scanner.devices
    val fireTvTargets = others.filter { it.isFireTv && it.dialAppsUrl != null }
    var manualFireTvAddress by remember { mutableStateOf("") }
    var manualFireTvStatus by remember { mutableStateOf<String?>(null) }
    var manualFireTvConnecting by remember { mutableStateOf(false) }

    if (castContext != null) {
        DisposableEffect(Unit) {
            val mediaRouter = MediaRouter.getInstance(context)
            val selector = castContext.mergedSelector ?: MediaRouteSelector.EMPTY

            fun refresh() {
                routes.clear()
                routes.addAll(mediaRouter.routes.filter { !it.isDefault && it.matchesSelector(selector) })
            }

            val callback = object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
                override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
                override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
                override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
            }
            mediaRouter.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            refresh()
            onDispose { mediaRouter.removeCallback(callback) }
        }
    }

    ModalBottomSheet(onDismissRequest = vm::closeCastSheet) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(tr("Transmitir a"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))

            vm.castMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = vm.fireTvPairingCode,
                onValueChange = vm::setFireTvPairingCode,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("Código de emparejamiento")) },
                placeholder = { Text("000000") },
                supportingText = {
                    Text(tr("Se usa para vincular un Fire TV nuevo; los ya vinculados usan su vínculo guardado."))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(8.dp))

            if (castContext == null) {
                Text(
                    tr("No se pudo iniciar Google Cast en este móvil (hace falta Google Play Services)."),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else if (routes.isEmpty() && others.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(tr("Buscando dispositivos cercanos…"))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    tr("Conecta el móvil y el Fire TV a la misma Wi-Fi principal, no a una red de invitados. Si no aparece, desactiva el aislamiento de clientes y permite SSDP multicast (UDP 1900) y conexiones locales TCP 8765 en el router."),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (fireTvTargets.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = manualFireTvAddress,
                    onValueChange = {
                        manualFireTvAddress = it.filter { char -> char.isDigit() || char == '.' }.take(15)
                        manualFireTvStatus = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("IP del Fire TV")) },
                    placeholder = { Text("192.168.1.25") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Text(
                    tr("Usa la dirección IP que aparece en la pantalla de RebornTube TV."),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !manualFireTvConnecting && manualFireTvAddress.isNotBlank(),
                        onClick = {
                            scope.launch {
                                manualFireTvConnecting = true
                                manualFireTvStatus = null
                                val connected = try {
                                    scanner.addFireTvByAddress(manualFireTvAddress)
                                } finally {
                                    manualFireTvConnecting = false
                                }
                                manualFireTvStatus = if (connected) {
                                    tr("Fire TV encontrado.")
                                } else {
                                    tr("No se pudo acceder al Fire TV. Comprueba la IP y que el módem permita la comunicación entre dispositivos.")
                                }
                            }
                        }
                    ) {
                        Text(if (manualFireTvConnecting) tr("Conectando…") else tr("Conectar por IP"))
                    }
                    manualFireTvStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            if (castContext != null && (routes.isNotEmpty() || others.isNotEmpty())) {
                routes.forEach { route ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                MediaRouter.getInstance(context).selectRoute(route)
                                vm.closeCastSheet()
                            }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Cast, contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(route.name, style = MaterialTheme.typography.titleMedium)
                            val description = route.description
                            if (!description.isNullOrBlank()) {
                                Text(description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (route.isSelected) {
                            Text(tr("Conectado"), color = MaterialTheme.colorScheme.primary)
                        } else if (route.isConnecting) {
                            Text(tr("Conectando…"))
                        }
                    }
                }
            }

            others.forEach { device ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { vm.castToDevice(device) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Tv, contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    device.isFireTv && device.canPlayUrl -> tr("Fire TV · receptor DLNA")
                                    device.isFireTv -> tr("Fire TV · sin receptor DLNA")
                                    else -> tr("TV o receptor DLNA")
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (device.isFireTv && vm.isFireTvPaired(device)) {
                                Text(
                                    tr("Vinculado a RebornTube"),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    if (device.isFireTv && device.dialAppsUrl != null) {
                        TextButton(
                            onClick = { vm.launchFireTvCompanion(device) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(tr("Enviar a RebornTube para Fire TV"))
                        }
                    }
                }
            }

            // Fire TV encontrado pero sin receptor DLNA: qué se puede hacer
            vm.dialOnlyDevice?.let { device ->
                Spacer(Modifier.height(8.dp))
                Text(
                    trf(
                        "%s no tiene un receptor DLNA instalado, así que no puede recibir vídeos de esta app. Instala en el Fire TV una app receptora (por ejemplo AirScreen) y vuelve a abrir esta lista, o usa Duplicar pantalla.",
                        device.name
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                if (device.dialAppsUrl != null) {
                    TextButton(onClick = { vm.openOnDeviceYouTube(device) }) {
                        Text(tr("Abrir en la app de YouTube del Fire TV (con anuncios)"))
                    }
                }
            }

            if (vm.casting) {
                TextButton(onClick = { vm.disconnectCast(); vm.closeCastSheet() }) {
                    Text(tr("Dejar de transmitir"))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { openCastSettings(context); vm.closeCastSheet() }) {
                Text(tr("Duplicar pantalla (ajustes del sistema)"))
            }
        }
    }
}
