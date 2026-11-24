package com.example.reborntube

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private const val KO_FI_DONATION_URL = "https://ko-fi.com/K7T22B2UMR"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = if (vm.miniVisible) MiniPlayerBottomSpace else 0.dp)
    ) {
        ScreenHeader(tr("Ajustes"), onBack = { vm.selectTab(Tab.Home) })

        SectionTitle(tr("Servicio"))
        var serviceMenu by remember { mutableStateOf(false) }
        Box(Modifier.padding(horizontal = 12.dp)) {
            OutlinedButton(onClick = { serviceMenu = true }) {
                Text(YouTubeRepository.serviceName(vm.serviceId))
            }
            DropdownMenu(expanded = serviceMenu, onDismissRequest = { serviceMenu = false }) {
                YouTubeRepository.supportedServices.forEach { service ->
                    DropdownMenuItem(
                        text = { Text(service.toString()) },
                        onClick = { vm.setService(service.serviceId); serviceMenu = false }
                    )
                }
            }
        }
        val filters = vm.availableSearchFilters
        if (filters.isNotEmpty()) {
            var filterMenu by remember { mutableStateOf(false) }
            Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                OutlinedButton(onClick = { filterMenu = true }) {
                    Text(searchFilterLabel(vm.searchFilter ?: filters.first()))
                }
                DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                    filters.forEach { filter ->
                        DropdownMenuItem(
                            text = { Text(searchFilterLabel(filter)) },
                            onClick = { vm.setSearchFilter(filter); filterMenu = false }
                        )
                    }
                }
            }
        }
        Text(
            tr("El servicio y el tipo elegidos se usan para buscar resultados compatibles."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        PipedAccountSettings(vm)
        SectionTitle(tr("DeArrow"))
        SwitchRow(
            tr("Usar títulos y miniaturas de DeArrow"),
            tr("Usa solo propuestas aprobadas o bloqueadas por la comunidad. La miniatura original se conserva si DeArrow no devuelve una propuesta confiable."),
            vm.deArrowEnabled,
            vm::setDeArrowEnabled
        )
        vm.deArrowMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        SwitchRow(
            tr("Mostrar sugerencias al buscar"),
            tr("Incluye búsquedas recientes y sugerencias del servicio seleccionado."),
            vm.searchSuggestionsEnabled,
            vm::setSearchSuggestionsEnabled
        )

        SectionTitle(tr("Apariencia"))
        ThemeOption(vm, tr("Claro (fondo blanco)"), "light")
        ThemeOption(vm, tr("Negro puro"), "black")
        ThemeOption(vm, tr("Igual que el sistema"), "system")
        SectionTitle(tr("Gruvbox"))
        ThemeOption(vm, tr("Gruvbox claro"), "gruvbox_light")
        ThemeOption(vm, tr("Gruvbox oscuro"), "gruvbox_dark")
        SwitchRow(
            tr("Modo ambiente"),
            tr("Muestra los colores difuminados del video únicamente detrás del reproductor."),
            vm.ambientMode,
            vm::updateAmbientMode
        )

        if (Build.VERSION.SDK_INT >= 31) {
            SwitchRow(
                tr("Colores del sistema"),
                tr("Usa los colores dinámicos de Android en la interfaz."),
                vm.accent == "dynamic",
                { vm.setAccent(if (it) "dynamic" else "default") },
                enabled = !vm.theme.startsWith("gruvbox_")
            )
        }

        SectionTitle(tr("Reproducción en segundo plano"))
        Text(
            tr("Automático: el audio sigue sonando y aparece en los controles multimedia; los videos pasan a ventana flotante cuando el sistema lo permite."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        SectionTitle(tr("SponsorBlock"))
        SwitchRow(
            tr("Saltar sponsors automáticamente"),
            tr("Omite patrocinios, autopromoción, intros y despedidas usando la base de datos comunitaria SponsorBlock (sponsor.ajay.app). Solo consulta el inicio de un código del vídeo, no el vídeo completo."),
            vm.skipSponsors,
            vm::setSkipSponsors
        )
        var sponsorCategoriesExpanded by remember { mutableStateOf(false) }
        TextButton(
            onClick = { sponsorCategoriesExpanded = !sponsorCategoriesExpanded },
            enabled = vm.skipSponsors,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Text(tr(if (sponsorCategoriesExpanded) "Ocultar categorías" else "Configurar categorías"))
        }
        if (sponsorCategoriesExpanded) {
            val categoryLabels = listOf(
                "sponsor" to tr("Patrocinios"),
                "selfpromo" to tr("Autopromoción"),
                "interaction" to tr("Recordatorios de interacción"),
                "intro" to tr("Introducciones"),
                "outro" to tr("Créditos y despedidas"),
                "preview" to tr("Avances"),
                "filler" to tr("Relleno"),
                "music_offtopic" to tr("Música fuera de tema"),
                "exclusive_access" to tr("Acceso exclusivo")
            )
            categoryLabels.forEach { (category, label) ->
                SwitchRow(
                    label,
                    "",
                    category in vm.sponsorCategories,
                    { vm.setSponsorCategoryEnabled(category, it) },
                    enabled = vm.skipSponsors
                )
            }
        }
        SectionTitle(tr("Reproducción"))
        SwitchRow(
            tr("Modo de ahorro de datos"),
            tr("Oculta miniaturas y avatares en listas y desactiva la precarga de videos."),
            vm.dataSaverEnabled,
            vm::setDataSaverEnabled
        )
        SwitchRow(
            tr("Descarga rápida por fragmentos"),
            tr("Pide el vídeo en trozos para evitar la limitación de velocidad de YouTube. Desactívalo si algún vídeo no carga."),
            vm.fastDownload,
            vm::setFastDownload
        )
        SwitchRow(
            tr("Reproducir automáticamente al abrir"),
            tr("Inicia el video al seleccionarlo. Si se desactiva, el reproductor se abre en pausa."),
            vm.autoplayOnOpenEnabled,
            vm::setAutoplayOnOpenEnabled
        )
        SwitchRow(
            tr("Mostrar contenido relacionado"),
            tr("Muestra recomendaciones debajo del reproductor y las usa para la reproducción automática."),
            vm.relatedContentEnabled,
            vm::setRelatedContentEnabled
        )
        SwitchRow(
            tr("Pausar al apagar la pantalla"),
            tr("Pausa el video cuando se apaga la pantalla; si se desactiva, el reproductor queda minimizado."),
            vm.pauseOnScreenOff,
            vm::setPauseOnScreenOff
        )
        SwitchRow(
            tr("Cuenta regresiva antes del siguiente video"),
            tr("Muestra una cuenta regresiva antes de iniciar la recomendación automática."),
            vm.autoplayCountdownEnabled,
            vm::setAutoplayCountdownEnabled
        )
        var fullscreenOrientationMenu by remember { mutableStateOf(false) }
        Box(Modifier.padding(horizontal = 12.dp)) {
            OutlinedButton(onClick = { fullscreenOrientationMenu = true }) {
                Text(
                    tr("Orientación de pantalla completa") + ": " +
                        tr(
                            when (vm.fullscreenOrientation) {
                                "portrait" -> "Vertical"
                                "sensor" -> "Automática"
                                else -> "Horizontal"
                            }
                        )
                )
            }
            DropdownMenu(expanded = fullscreenOrientationMenu, onDismissRequest = { fullscreenOrientationMenu = false }) {
                listOf(
                    "landscape" to tr("Horizontal"),
                    "portrait" to tr("Vertical"),
                    "sensor" to tr("Automática")
                ).forEach { (orientation, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            vm.setFullscreenOrientation(orientation)
                            fullscreenOrientationMenu = false
                        }
                    )
                }
            }
        }
        SectionTitle(tr("Bienestar digital"))
        var breakReminderMenu by remember { mutableStateOf(false) }
        Box(Modifier.padding(horizontal = 12.dp)) {
            OutlinedButton(onClick = { breakReminderMenu = true }) {
                Text(
                    if (vm.breakReminderMinutes == 0) tr("Desactivado")
                    else trf("Cada %s min", vm.breakReminderMinutes)
                )
            }
            DropdownMenu(expanded = breakReminderMenu, onDismissRequest = { breakReminderMenu = false }) {
                listOf(0, 15, 30, 45, 60).forEach { minutes ->
                    DropdownMenuItem(
                        text = {
                            Text(if (minutes == 0) tr("Desactivado") else trf("Cada %s min", minutes))
                        },
                        onClick = {
                            vm.setBreakReminderMinutes(minutes)
                            breakReminderMenu = false
                        }
                    )
                }
            }
        }
        Text(
            tr("Muestra un recordatorio después del tiempo elegido mientras se reproduce audio o video."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        SectionTitle(tr("Notificaciones"))
        SwitchRow(
            tr("Notificar nuevos videos"),
            tr("Comprueba los canales a los que estás suscrito y avisa cuando publiquen."),
            vm.newVideoNotificationsEnabled,
            vm::setNewVideoNotificationsEnabled
        )
        var notificationIntervalMenu by remember { mutableStateOf(false) }
        Box(Modifier.padding(horizontal = 12.dp)) {
            OutlinedButton(
                onClick = { notificationIntervalMenu = true },
                enabled = vm.newVideoNotificationsEnabled
            ) {
                Text(trf("Comprobar cada %s min", vm.notificationIntervalMinutes))
            }
            DropdownMenu(expanded = notificationIntervalMenu, onDismissRequest = { notificationIntervalMenu = false }) {
                listOf(15, 30, 60, 120).forEach { minutes ->
                    DropdownMenuItem(
                        text = { Text(trf("Comprobar cada %s min", minutes)) },
                        onClick = {
                            vm.setNotificationIntervalMinutes(minutes)
                            notificationIntervalMenu = false
                        }
                    )
                }
            }
        }
        SwitchRow(
            tr("Solo con Wi-Fi"),
            tr("Limita la comprobación de nuevos videos a conexiones Wi-Fi sin medición."),
            vm.notificationWifiOnly,
            vm::setNotificationWifiOnly,
            enabled = vm.newVideoNotificationsEnabled
        )

        SectionTitle(tr("Fire TV"))
        Text(
            tr("Para vincular un Fire TV nuevo, abre Transmitir a e introduce allí el código que muestra el televisor. Los Fire TV ya vinculados conservan su conexión privada."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        FilledTonalButton(
            onClick = vm::openCastSheet,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(tr("Buscar y conectar con Fire TV"))
        }
        if (vm.pairedFireTvCount > 0) {
            Text(
                trf("Fire TV vinculados: %s", vm.pairedFireTvCount),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            TextButton(
                onClick = vm::forgetFireTvPairings,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Text(tr("Olvidar Fire TV vinculados"))
            }
        }
        Text(
            tr("Cuando aparezca tu Fire TV, toca Enviar a RebornTube para Fire TV."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        SectionTitle(tr("Apoya RebornTube"))
        Text(
            tr("Si disfrutas de la app, puedes apoyar su desarrollo con una donación voluntaria en Ko-fi. Se abrirá la página oficial fuera de la app; la donación no desbloquea funciones."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        FilledTonalButton(
            onClick = { vm.openExternalUrl(KO_FI_DONATION_URL, tr("Donar con Ko-fi")) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.Favorite, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(tr("Donar con Ko-fi"))
        }

        SectionTitle(tr("Actualizaciones"))
        FilledTonalButton(
            onClick = vm::checkForUpdates,
            enabled = !vm.updateChecking,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(if (vm.updateChecking) tr("Comprobando…") else tr("Buscar actualización"))
        }
        vm.updateMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Text(
            trf("RebornTube · versión %s", BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun PipedAccountSettings(vm: MainViewModel) {
    var instanceMenu by remember { mutableStateOf(false) }
    var customApiUrl by remember { mutableStateOf(vm.pipedApiUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    SectionTitle(tr("Cuenta Piped (opcional)"))
    Text(
        tr("Piped es una cuenta de la instancia elegida, no una cuenta de YouTube. Tu contraseña solo se envía a esa instancia; el token de sesión se cifra en este dispositivo."),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        OutlinedButton(onClick = { instanceMenu = true }) {
            Text(trf("Instancia: %s", vm.pipedApiUrl.removePrefix("https://")))
        }
        DropdownMenu(expanded = instanceMenu, onDismissRequest = { instanceMenu = false }) {
            vm.pipedInstances.forEach { instance ->
                DropdownMenuItem(
                    text = { Text(tr(instance.name)) },
                    onClick = {
                        vm.setPipedApiUrl(instance.apiUrl)
                        customApiUrl = instance.apiUrl
                        instanceMenu = false
                    }
                )
            }
        }
    }
    TextButton(
        onClick = vm::refreshPipedInstances,
        enabled = !vm.pipedInstancesLoading,
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        Text(if (vm.pipedInstancesLoading) tr("Cargando instancias…") else tr("Actualizar instancias"))
    }
    OutlinedTextField(
        value = customApiUrl,
        onValueChange = { customApiUrl = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        label = { Text(tr("URL de instancia personalizada")) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
    )
    FilledTonalButton(
        onClick = { vm.setPipedApiUrl(customApiUrl) },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    ) { Text(tr("Usar esta instancia")) }

    if (vm.pipedAccountConnected) {
        Text(
            trf("Conectado como %s", vm.pipedAccountName),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        TextButton(onClick = vm::logoutPiped, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(tr("Cerrar sesión Piped"))
        }
    } else {
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            label = { Text(tr("Usuario Piped")) },
            singleLine = true
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            label = { Text(tr("Contraseña Piped")) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = { vm.authenticatePiped(username, password, register = false) },
                enabled = !vm.pipedAuthLoading && username.isNotBlank() && password.isNotBlank()
            ) { Text(tr("Iniciar sesión")) }
            OutlinedButton(
                onClick = { vm.authenticatePiped(username, password, register = true) },
                enabled = !vm.pipedAuthLoading && username.isNotBlank() && password.isNotBlank()
            ) { Text(tr("Crear cuenta")) }
            if (vm.pipedAuthLoading) CircularProgressIndicator(Modifier.size(24.dp))
        }
    }
    vm.pipedAuthMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
    SwitchRow(
        tr("Usar el feed de esta cuenta"),
        tr("Muestra en Novedades los videos que entrega el feed de la instancia Piped."),
        vm.usePipedFeed,
        vm::setUsePipedFeed,
        enabled = vm.pipedAccountConnected
    )
}

private fun searchFilterLabel(filter: String): String = when (filter.lowercase()) {
    "videos" -> tr("Videos")
    "music_songs", "songs", "tracks", "audio" -> tr("Audio y canciones")
    "music_videos" -> tr("Videos musicales")
    else -> filter.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun SectionTitle(text: String) = SectionHeader(text)

@Composable
private fun ThemeOption(vm: MainViewModel, label: String, value: String) =
    OptionCard(label, vm.theme == value) { vm.setTheme(value) }

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    SettingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (description.isNotBlank()) {
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }
}
