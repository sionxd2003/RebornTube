package com.example.reborntube

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Copia de seguridad (zip) y Google Takeout
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) vm.exportBackup(uri)
        }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.importBackup(uri)
        }
    private val takeoutLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.importTakeout(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Conectarse al servicio hace que Android muestre la notificación multimedia.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()

        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            // Tema: Claro / Negro puro / Sistema, combinado con el color de acento elegido
            val systemDark = isSystemInDarkTheme()
            val dark = when (vm.theme) {
                "light", "gruvbox_light" -> false
                "system" -> systemDark
                else -> true
            }
            val scheme = buildColorScheme(vm.accent, vm.theme, systemDark, LocalContext.current)
            ThemeBarsEffect(dark)

            MaterialTheme(colorScheme = scheme, shapes = AppShapes) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                    val info = vm.current

                    // La pantalla no se apaga mientras se ve un vídeo, un short o un archivo descargado
                    KeepScreenOn(
                        vm.isPlaying && (
                            (info != null && (vm.expanded || vm.inPip)) ||
                                vm.shortInfo != null || (vm.offline != null && vm.expanded)
                            )
                    )

                    // Atrás: carga -> descarga -> shorts -> pantalla completa -> vídeo -> mini; lista/canal -> volver
                    BackHandler(
                        enabled = (info != null && vm.expanded) || vm.channelUrl != null || vm.remoteUrl != null ||
                            vm.tab != Tab.Home || vm.opening != null || vm.shortsSession != null ||
                            (vm.offline != null && vm.expanded) ||
                            vm.results.isNotEmpty() || vm.query.isNotBlank()
                    ) { vm.onBack() }

                    if (info != null && vm.inPip) {
                        // Ventana flotante: solo el vídeo
                        PlayerScreen(vm, info)
                    } else {
                        Box(Modifier.fillMaxSize()) {
                            MainScaffold(
                                vm,
                                onExport = { exportLauncher.launch("RebornTube-copia.zip") },
                                onImport = { importLauncher.launch(arrayOf("*/*")) },
                                onTakeout = { takeoutLauncher.launch(arrayOf("*/*")) }
                            )
                            if (info != null && vm.expanded) {
                                Box(Modifier.fillMaxSize()) {
                                    Box(
                                        Modifier.fillMaxSize()
                                            .background(MaterialTheme.colorScheme.background)
                                    )
                                    AmbientInterfaceBackground(vm, Modifier.matchParentSize())
                                    Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                                        PlayerScreen(vm, info)
                                    }
                                }
                            }
                            vm.shortsSession?.let { session ->
                                Surface(Modifier.fillMaxSize()) { ShortsViewer(vm, session) }
                            }
                            vm.offline?.takeIf { vm.expanded }?.let { rec ->
                                Surface(Modifier.fillMaxSize()) { OfflinePlayer(vm, rec) }
                            }
                            // Pantalla de carga inmediata al tocar un vídeo
                            vm.opening?.let { OpeningOverlay(it) }
                        }
                    }

                    // "Guardar en lista" (desde el reproductor, los shorts o pulsación larga)
                    vm.saveDialogCard?.let { SaveToListDialog(vm, it) }
                    if (vm.showSponsorContribution && vm.current != null) {
                        SponsorBlockContributionDialog(vm)
                    }
                    if (vm.showDeArrowContribution && vm.current != null) {
                        DeArrowContributionDialog(vm)
                    }

                    if (vm.showBreakReminder && !vm.inPip) {
                        AlertDialog(
                            onDismissRequest = { vm.resolveBreakReminder(pausePlayback = false) },
                            title = { Text(tr("Tómate un descanso")) },
                            text = {
                                Text(trf("Llevas %s minutos reproduciendo. ¿Quieres pausar y descansar?", vm.breakReminderMinutes))
                            },
                            confirmButton = {
                                TextButton(onClick = { vm.resolveBreakReminder(pausePlayback = true) }) {
                                    Text(tr("Pausar y descansar"))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { vm.resolveBreakReminder(pausePlayback = false) }) {
                                    Text(tr("Seguir reproduciendo"))
                                }
                            }
                        )
                    }

                    // Lista de dispositivos para transmitir a la TV
                    if (vm.showCastSheet) CastSheet(vm)
                }
                    }
            }
        }
    }

    /** Enlaces de YouTube abiertos con esta app o compartidos hacia ella. */
    private fun handleIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent?.dataString
            Intent.ACTION_SEND -> intent?.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        vm.openLink(text)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** El vídeo pasa automáticamente a PiP; el audio continúa con los controles multimedia del sistema. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.current != null && vm.isPlaying && !vm.audioOnly) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        val screenIsOff = !getSystemService(PowerManager::class.java).isInteractive
        if (screenIsOff && (vm.current != null || vm.offline != null || vm.shortInfo != null) &&
            !isInPictureInPictureMode
        ) {
            vm.handleScreenOff()
            super.onStop()
            return
        }

        // El audio sigue en segundo plano; el vídeo solo continúa si el sistema abrió PiP.
        val playingDownloadedAudio = vm.offline?.audioOnly == true
        if (!vm.audioOnly && !playingDownloadedAudio && !isInPictureInPictureMode) {
            vm.pauseForLeaving()
        } else {
            vm.saveProgress()
        }
        super.onStop()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

/** Iconos de la barra de estado oscuros con tema claro y claros con tema oscuro. */
@Composable
private fun ThemeBarsEffect(dark: Boolean) {
    val activity = LocalContext.current.findActivity()
    SideEffect {
        val window = activity?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(enabled) {
        if (enabled) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

@Composable
fun MainScaffold(vm: MainViewModel, onExport: () -> Unit, onImport: () -> Unit, onTakeout: () -> Unit) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun go(action: () -> Unit) {
        scope.launch { drawerState.close() }
        action()
    }

    // Menú lateral, con las secciones de NewPipe como homenaje (Ajustes está en la barra de abajo)
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppMark()
                        Spacer(Modifier.width(8.dp))
                        Wordmark()
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { scope.launch { drawerState.close() } }) {
                            Icon(Icons.Default.Close, contentDescription = tr("Cerrar menú"))
                        }
                    }
                    val inLibrary = vm.tab == Tab.Library
                    DrawerHeading(tr("Tu contenido"))
                    DrawerEntry(Icons.Default.Subscriptions, tr("Suscripciones"), vm.tab == Tab.Subscriptions) { go { vm.openSubscriptions() } }
                    DrawerEntry(Icons.Default.Subscriptions, tr("Novedades"), vm.tab == Tab.News) { go { vm.openNews() } }
                    DrawerEntry(Icons.Default.Bookmarks, tr("Listas de reproducción"), inLibrary && vm.libraryList == LibList.Playlists) { go { vm.openLibraryList(LibList.Playlists) } }
                    DrawerEntry(Icons.Default.Download, tr("Descargas"), inLibrary && vm.libraryList == LibList.Downloads) { go { vm.openLibraryList(LibList.Downloads) } }
                    DrawerEntry(Icons.Default.History, tr("Historial"), inLibrary && vm.libraryList == LibList.History) { go { vm.openLibraryList(LibList.History) } }
                    DrawerEntry(Icons.Default.Queue, tr("Cola"), inLibrary && vm.libraryList == LibList.Queue) { go { vm.openLibraryList(LibList.Queue) } }
                    DrawerHeading(tr("Explorar"))
                    DrawerEntry(Icons.Default.SportsEsports, tr("Tendencias videojuegos")) { go { vm.openTrending("gaming") } }
                    DrawerEntry(Icons.Default.MusicNote, tr("Tendencias música")) { go { vm.openTrending("music") } }
                    DrawerEntry(Icons.Default.Movie, tr("Tendencias películas y series")) { go { vm.openTrending("movies") } }
                    DrawerEntry(Icons.Default.Podcasts, tr("Tendencias pódcasts")) { go { vm.openTrending("podcasts") } }
                    DrawerEntry(Icons.Default.LocalFireDepartment, tr("Tendencias")) { go { vm.openTrending("all") } }
                    DrawerEntry(Icons.Default.LiveTv, tr("En directo"), vm.tab == Tab.Live) { go { vm.openLive() } }
                    DrawerHeading(tr("La app"))
                    DrawerEntry(Icons.Default.Settings, tr("Ajustes"), vm.tab == Tab.Settings) { go { vm.selectTab(Tab.Settings) } }
                }
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
                when {
                    vm.remoteUrl != null -> RemotePlaylistScreen(vm)
                    vm.channelUrl != null -> ChannelScreen(vm)
                    vm.tab == Tab.Home -> SearchScreen(vm, onMenu = { scope.launch { drawerState.open() } })
                    vm.tab == Tab.Shorts -> ShortsScreen(vm)
                    vm.tab == Tab.Settings -> SettingsScreen(vm)
                    vm.tab == Tab.Library -> LibraryScreen(vm, onExport, onImport, onTakeout)
                    vm.tab == Tab.Subscriptions -> SubscriptionsScreen(vm)
                    vm.tab == Tab.News -> NewsScreen(vm)
                    vm.tab == Tab.Trending -> TrendingScreen(vm)
                    else -> LiveScreen(vm)
                }
                // Ventana flotante: solo se mueve por esta área (encima de la barra de abajo)
                val miniInfo = vm.current
                if (miniInfo != null && !vm.expanded) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        MiniPlayer(vm, miniInfo)
                    }
                } else {
                    val offlineAudio = vm.offline?.takeIf { it.audioOnly && !vm.expanded }
                    if (offlineAudio != null) {
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                            OfflineAudioMiniPlayer(vm, offlineAudio)
                        }
                    }
                }
            }
            NavigationBar(
                modifier = Modifier.clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 0.dp
            ) {
                val atRoot = vm.channelUrl == null && vm.remoteUrl == null
                NavigationBarItem(
                    selected = atRoot && vm.tab in listOf(
                        Tab.Home, Tab.Subscriptions, Tab.Live, Tab.News, Tab.Trending
                    ),
                    onClick = { vm.selectTab(Tab.Home) },
                    icon = { Icon(Icons.Default.Home, contentDescription = tr("Inicio")) },
                    label = { Text(tr("Inicio"), maxLines = 1) },
                    alwaysShowLabel = false
                )
                NavigationBarItem(
                    selected = atRoot && vm.tab == Tab.Library,
                    onClick = { vm.selectTab(Tab.Library) },
                    icon = { Icon(Icons.Default.LibraryBooks, contentDescription = tr("Biblioteca")) },
                    label = { Text(tr("Biblioteca"), maxLines = 1) },
                    alwaysShowLabel = false
                )
                NavigationBarItem(
                    selected = atRoot && vm.tab == Tab.Settings,
                    onClick = { vm.selectTab(Tab.Settings) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = tr("Ajustes")) },
                    label = { Text(tr("Ajustes"), maxLines = 1) },
                    alwaysShowLabel = false
                )
            }
        }
    }
}

@Composable
private fun DrawerHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 4.dp)
    )
}

/** Entrada del menú: objetivo táctil grande (56 dp), la sección actual resaltada. */
@Composable
private fun DrawerEntry(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 56.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: MainViewModel, onMenu: () -> Unit) {
    val listState = rememberLazyListState()
    val pullRefreshState = rememberPullToRefreshState()
    var feedRefreshing by remember { mutableStateOf(false) }
    var refreshStarted by remember { mutableStateOf(false) }
    LaunchedEffect(vm.loading) {
        if (vm.loading) refreshStarted = true
        else if (refreshStarted) {
            feedRefreshing = false
            refreshStarted = false
        }
    }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val showingFeed = vm.results.isEmpty() && vm.query.isBlank()
    var showRegionDialog by remember { mutableStateOf(false) }

    // Al tocar la barra salen tus búsquedas recientes y, al escribir, las sugerencias de YouTube
    val historyMatches = vm.searchHistory
        .filter { vm.query.isBlank() || it.contains(vm.query.trim(), ignoreCase = true) }
        .take(if (vm.query.isBlank()) 10 else 4)
    val remoteSuggestions = vm.suggestions.filter { s -> historyMatches.none { it.equals(s, ignoreCase = true) } }
    val showSuggestions = focused &&
        (historyMatches.isNotEmpty() || (vm.searchSuggestionsEnabled && remoteSuggestions.isNotEmpty()))

    // Con el teclado y las sugerencias abiertos, atrás solo las cierra
    BackHandler(enabled = focused) { focusManager.clearFocus() }

    if (showRegionDialog) {
        AlertDialog(
            onDismissRequest = { showRegionDialog = false },
            title = { Text(tr("Región de contenido")) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(YouTubeRepository.regions) { region ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    vm.selectContentRegion(region.code)
                                    showRegionDialog = false
                                }
                                .background(
                                    if (vm.contentRegion == region.code)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        Color.Transparent,
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                tr(region.nameEs),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (vm.contentRegion == region.code) {
                                Text(region.code, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRegionDialog = false }) { Text(tr("Cerrar")) }
            }
        )
    }

    fun runSearch(text: String) {
        vm.onQueryChange(text)
        vm.search()
        focusManager.clearFocus()
    }

    // Búsqueda por voz: pide el permiso del micrófono la primera vez
    var showVoice by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showVoice = true
        else android.widget.Toast.makeText(context, tr("Sin el permiso del micrófono no se puede buscar por voz"), android.widget.Toast.LENGTH_LONG).show()
    }
    fun openVoice() {
        focusManager.clearFocus()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            showVoice = true
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    if (showVoice) {
        VoiceSearchDialog(
            onResult = { showVoice = false; runSearch(it) },
            onDismiss = { showVoice = false }
        )
    }

    // Al pulsar Inicio, volver arriba del feed
    LaunchedEffect(vm.homeTick) {
        if (vm.homeTick > 0) listState.animateScrollToItem(0)
    }

    // Scroll infinito: al acercarte al final se precargan más vídeos
    val nearEnd = remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd.value, vm.feed.size) {
        if (showingFeed && nearEnd.value) vm.loadMoreFeed()
    }

    Column(Modifier.fillMaxSize()) {
        // Barra superior: el logo propio (abre el menú) y la TV
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Botón de menú siempre visible (también se abre deslizando desde el borde izquierdo)
            IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Menu, contentDescription = tr("Abrir menú"))
            }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onMenu).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppMark()
                Spacer(Modifier.width(8.dp))
                Wordmark()
            }
            IconButton(onClick = { showRegionDialog = true }) {
                Icon(
                    Icons.Default.Public,
                    contentDescription = tr("Región de contenido"),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = vm::openCastSheet) {
                Icon(
                    Icons.Default.Cast,
                    contentDescription = tr("Transmitir a la TV"),
                    tint = if (vm.casting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        // Buscador en forma de cápsula, con el micrófono dentro
        OutlinedTextField(
            value = vm.query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .onFocusChanged { focused = it.isFocused },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Transparent
            ),
            placeholder = { Text(tr("Buscar vídeos o canales")) },
            leadingIcon = {
                IconButton(onClick = { runSearch(vm.query) }) {
                    Icon(Icons.Default.Search, contentDescription = tr("Buscar"))
                }
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = tr("Borrar"))
                        }
                    }
                    IconButton(onClick = { openVoice() }) {
                        Icon(Icons.Default.Mic, contentDescription = tr("Buscar por voz"))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch(vm.query) })
        )

        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        }

        if (showSuggestions) {
            if (vm.query.isBlank() && historyMatches.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        tr("Búsquedas recientes"),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = vm::clearSearches) { Text(tr("Borrar todo")) }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                // Búsquedas anteriores (reloj, con X para quitarlas)
                items(historyMatches) { q ->
                    Row(
                        Modifier.fillMaxWidth().clickable { runSearch(q) }
                            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Text(q, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.removeSearch(q) }) {
                            Icon(Icons.Default.Close, contentDescription = tr("Quitar de las búsquedas"))
                        }
                    }
                }
                // Sugerencias de YouTube (lupa, con flecha para pasarlas a la barra sin buscar)
                items(if (vm.searchSuggestionsEnabled) remoteSuggestions else emptyList()) { q ->
                    Row(
                        Modifier.fillMaxWidth().clickable { runSearch(q) }
                            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Text(q, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.onQueryChange(q) }) {
                            Icon(Icons.Default.NorthWest, contentDescription = tr("Usar en la búsqueda"))
                        }
                    }
                }
            }
        } else {
            if (showingFeed) {
                // Fila de filtros que se desliza de lado
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { PillChip(tr("Para ti"), selected = true) { } }
                    item { PillChip(tr("Suscripciones"), Icons.Default.Subscriptions) { vm.openSubscriptions() } }
                    item { PillChip(tr("Novedades"), Icons.Default.Subscriptions) { vm.openNews() } }
                    item { PillChip(tr("Directos"), Icons.Default.LiveTv) { vm.openLive() } }
                    item { PillChip(tr("Tendencias videojuegos"), Icons.Default.SportsEsports) { vm.openTrending("gaming") } }
                    item { PillChip(tr("Tendencias música"), Icons.Default.MusicNote) { vm.openTrending("music") } }
                    item { PillChip(tr("Tendencias películas y series"), Icons.Default.Movie) { vm.openTrending("movies") } }
                    item { PillChip(tr("Tendencias pódcasts"), Icons.Default.Podcasts) { vm.openTrending("podcasts") } }
                    item { PillChip(tr("Tendencias"), Icons.Default.LocalFireDepartment) { vm.openTrending("all") } }
                }
            }

            val list = if (showingFeed) vm.feed else vm.results

            val resultsContent: @Composable () -> Unit = {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = if (vm.miniVisible) MiniPlayerBottomSpace else 0.dp)
            ) {
                if (!showingFeed && vm.searchCompleted) {
                    item { SectionHeader(tr("Canales")) }
                    if (vm.channelResults.isEmpty() && !vm.loading) {
                        item { NoteBox(tr("No se encontraron canales.")) }
                    }
                    items(vm.channelResults) { ch -> ChannelRow(ch, onClick = { vm.openChannel(ch.url) }) }
                    item { SectionHeader(tr("Videos")) }
                    if (list.isEmpty() && !vm.loading) {
                        item { NoteBox(tr("No se encontraron videos.")) }
                    }
                }
                if (showingFeed || vm.searchCompleted) {
                    items(list) { card -> FeedCard(vm, card) }
                }
                item {
                    if (showingFeed || vm.searchCompleted) {
                        // Resultados de búsqueda: al llegar aquí se piden más páginas
                        if (!showingFeed) {
                            LaunchedEffect(vm.results.size) { vm.loadMoreResults() }
                        }
                        if ((showingFeed && vm.feedMoreLoading) || (!showingFeed && vm.resultsMoreLoading)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                        }
                    }
                }
            }
            }
            if (showingFeed) {
                PullToRefreshBox(
                    isRefreshing = feedRefreshing,
                    onRefresh = {
                        if (!vm.loading && !vm.feedMoreLoading) {
                            feedRefreshing = true
                            vm.loadFeed()
                        }
                    },
                    state = pullRefreshState,
                    modifier = Modifier.fillMaxSize()
                ) { resultsContent() }
            } else {
                resultsContent()
            }
        }
    }
}
