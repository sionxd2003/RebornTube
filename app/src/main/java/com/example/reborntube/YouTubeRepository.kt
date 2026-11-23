package com.example.reborntube

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.Locale
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

object YouTubeRepository {
    data class Region(val code: String, val nameEs: String)

    val regions = listOf(
        Region("US", "Estados Unidos"),
        Region("MX", "México"),
        Region("ES", "España"),
        Region("AR", "Argentina"),
        Region("CO", "Colombia"),
        Region("CL", "Chile"),
        Region("PE", "Perú"),
        Region("BR", "Brasil"),
        Region("GB", "Reino Unido"),
        Region("DE", "Alemania"),
        Region("FR", "Francia"),
        Region("IT", "Italia"),
        Region("JP", "Japón"),
        Region("KR", "Corea del Sur"),
        Region("IN", "India")
    )

    fun normalizeRegionCode(code: String?): String? =
        code?.uppercase(Locale.ROOT)?.takeIf { it.matches(Regex("[A-Z]{2}")) }

    fun setContentRegion(context: Context, code: String): Boolean {
        val normalized = normalizeRegionCode(code) ?: return false
        val locale = Locale(Locale.getDefault().language, normalized)
        return runCatching {
            NewPipe.init(
                DownloaderImpl.initialize(context),
                Localization.fromLocale(locale),
                ContentCountry(normalized)
            )
            true
        }.getOrDefault(false)
    }

    private val returnDislikeClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val youtube get() = ServiceList.YouTube
    val supportedServices: List<StreamingService> get() = NewPipe.getServices()
    fun serviceName(serviceId: Int): String = runCatching { NewPipe.getService(serviceId).toString() }.getOrDefault("YouTube")
    fun searchFilters(serviceId: Int): List<String> = runCatching {
        NewPipe.getService(serviceId).searchQHFactory.availableContentFilter.toList()
    }.getOrDefault(emptyList())
    private fun serviceFor(url: String): StreamingService = NewPipe.getServiceByUrl(url)
    fun serviceIdForUrl(url: String): Int = serviceFor(url).serviceId

    private val defaultTopics = listOf(tr("música"), tr("videojuegos"), tr("tecnología"), tr("documental"), tr("humor"))

    suspend fun search(query: String, serviceId: Int = youtube.serviceId): List<StreamInfoItem> = withContext(Dispatchers.IO) {
        val service = NewPipe.getService(serviceId)
        val handler = searchHandler(service, query)
        SearchInfo.getInfo(service, handler)
            .relatedItems
            .filterIsInstance<StreamInfoItem>()
    }

    private fun searchHandler(service: StreamingService, query: String, filter: String? = null) =
        if (!filter.isNullOrBlank()) service.searchQHFactory.fromQuery(query, listOf(filter), "")
        else if (service.serviceId == youtube.serviceId) service.searchQHFactory.fromQuery(query, listOf("videos"), "")
        else service.searchQHFactory.fromQuery(query)

    /**
     * Resultados de búsqueda por páginas: cada llamada a next() trae la siguiente tanda
     * (así se puede seguir bajando y salen todos los vídeos relacionados, no solo los primeros).
     */
    class SearchCursor(
        query: String,
        serviceId: Int = ServiceList.YouTube.serviceId,
        contentFilter: String? = null
    ) {
        private val service = NewPipe.getService(serviceId)
        private val handler = searchHandler(service, query, contentFilter)
        private var started = false
        private var nextPage: Page? = null
        private val seen = HashSet<String>()

        val hasMore: Boolean get() = !started || nextPage != null

        suspend fun next(): List<StreamInfoItem> = withContext(Dispatchers.IO) {
            val items: List<StreamInfoItem>
            if (!started) {
                started = true
                val info = SearchInfo.getInfo(service, handler)
                nextPage = info.nextPage
                items = info.relatedItems.filterIsInstance<StreamInfoItem>()
            } else {
                val page = nextPage ?: return@withContext emptyList()
                val more = SearchInfo.getMoreItems(service, handler, page)
                nextPage = more.nextPage
                items = more.items.filterIsInstance<StreamInfoItem>()
            }
            items.filter { seen.add(it.url) }
        }
    }

    suspend fun searchChannels(query: String, serviceId: Int = youtube.serviceId): List<ChannelInfoItem> = withContext(Dispatchers.IO) {
        val service = NewPipe.getService(serviceId)
        val handler = service.searchQHFactory.fromQuery(query, listOf("channels"), "")
        SearchInfo.getInfo(service, handler)
            .relatedItems
            .filterIsInstance<ChannelInfoItem>()
    }

    suspend fun channel(url: String): ChannelInfo = withContext(Dispatchers.IO) {
        ChannelInfo.getInfo(serviceFor(url), url)
    }

    suspend fun channelTab(tab: ListLinkHandler, serviceId: Int? = null): ChannelTabInfo = withContext(Dispatchers.IO) {
        val service = serviceId?.let { NewPipe.getService(it) } ?: serviceFor(tab.url)
        ChannelTabInfo.getInfo(service, resolveChannelTab(service, tab))
    }

    suspend fun moreChannelTab(tab: ListLinkHandler, page: Page, serviceId: Int? = null) = withContext(Dispatchers.IO) {
        val service = serviceId?.let { NewPipe.getService(it) } ?: serviceFor(tab.url)
        ChannelTabInfo.getMoreItems(service, resolveChannelTab(service, tab), page)
    }

    /** Some extractors return tab paths such as /@handle/videos instead of absolute URLs. */
    private fun resolveChannelTab(service: StreamingService, tab: ListLinkHandler): ListLinkHandler {
        val address = runCatching {
            val uri = java.net.URI(tab.url)
            if (uri.isAbsolute) uri.toString()
            else java.net.URI(service.baseUrl.trimEnd('/') + "/").resolve(uri).toString()
        }.getOrElse { tab.url }
        if (address == tab.url) return tab
        return runCatching { service.channelTabLHFactory.fromUrl(address) }.getOrDefault(tab)
    }

    suspend fun playlist(url: String): PlaylistInfo = withContext(Dispatchers.IO) {
        PlaylistInfo.getInfo(serviceFor(url), url)
    }

    suspend fun morePlaylist(url: String, page: Page) = withContext(Dispatchers.IO) {
        PlaylistInfo.getMoreItems(serviceFor(url), url, page)
    }

    /** Sugerencias al escribir (autocompletar de YouTube). */
    suspend fun suggestions(query: String, serviceId: Int = youtube.serviceId): List<String> = withContext(Dispatchers.IO) {
        NewPipe.getService(serviceId).suggestionExtractor.suggestionList(query)
    }

    // Caché corta de la información de vídeos (abrir un vídeo ya precargado es instantáneo).
    // Los directos no se guardan: sus direcciones caducan antes.
    private class CachedInfo(val at: Long, val info: StreamInfo)

    private val infoCache = LruCache<String, CachedInfo>(32)
    private val inflight = HashMap<String, Deferred<StreamInfo>>()
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val INFO_TTL_MS = 30 * 60 * 1000L

    private fun cached(url: String): StreamInfo? = synchronized(infoCache) {
        infoCache.get(url)?.takeIf { System.currentTimeMillis() - it.at < INFO_TTL_MS }?.info
    }

    /** Pide la información una sola vez aunque la pidan a la vez varios (precarga + el toque en el vídeo). */
    private fun fetchInfo(url: String): Deferred<StreamInfo> = synchronized(inflight) {
        inflight.getOrPut(url) {
            prefetchScope.async {
                try {
                    val info = StreamInfo.getInfo(serviceFor(url), url)
                    val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
                    if (!live) synchronized(infoCache) { infoCache.put(url, CachedInfo(System.currentTimeMillis(), info)) }
                    info
                } finally {
                    synchronized(inflight) { inflight.remove(url) }
                }
            }
        }
    }

    suspend fun streamInfo(url: String): StreamInfo {
        cached(url)?.let { return it }
        return fetchInfo(url).await()
    }

    /** Descarga la información en segundo plano para que el vídeo abra antes (y se calienta el extractor). */
    fun prefetch(url: String) {
        if (cached(url) != null) return
        runCatching { fetchInfo(url) }
    }

    suspend fun comments(url: String): CommentsInfo = withContext(Dispatchers.IO) {
        CommentsInfo.getInfo(serviceFor(url), url)
    }

    suspend fun dislikeCount(videoId: String): Long? = withContext(Dispatchers.IO) {
        if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return@withContext null

        val request = Request.Builder()
            .url("https://returnyoutubedislikeapi.com/votes?videoId=$videoId")
            .header("Accept", "application/json")
            .header("User-Agent", "RebornTube/1.0")
            .build()

        return@withContext returnDislikeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                null
            } else {
                val json = response.body?.string()?.let(::JSONObject) ?: return@use null
                if (json.optBoolean("deleted")) null
                else json.optLong("dislikes", -1L).takeIf { it >= 0L }
            }
        }
    }

    suspend fun moreComments(info: CommentsInfo, page: Page) = withContext(Dispatchers.IO) {
        CommentsInfo.getMoreItems(youtube, info, page)
    }

    /** Varias páginas de resultados de una búsqueda de vídeos. */
    suspend fun searchPages(query: String, pages: Int): List<StreamInfoItem> = withContext(Dispatchers.IO) {
        val handler = youtube.searchQHFactory.fromQuery(query, listOf("videos"), "")
        val info = SearchInfo.getInfo(youtube, handler)
        val out = info.relatedItems.filterIsInstance<StreamInfoItem>().toMutableList()
        var page = info.nextPage
        var n = 1
        while (page != null && n < pages) {
            val more = SearchInfo.getMoreItems(youtube, handler, page)
            out += more.items.filterIsInstance<StreamInfoItem>()
            page = more.nextPage
            n++
        }
        out
    }

    /** Shorts: se buscan varias consultas (3 páginas cada una) y se quedan los de hasta ~1 min. */
    suspend fun shortsFor(queries: List<String>): List<StreamInfoItem> = coroutineScope {
        queries.map { q -> async { runCatching { searchPages(q, 3) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
            .filter { it.duration in 1L..65L }
            .distinctBy { it.url }
            .shuffled()
    }

    // Quioscos de YouTube que usa NewPipe para las tendencias (se prueban varios nombres posibles)
    private val kioskIds = mapOf(
        "gaming" to listOf("trending_gaming", "Trending Gaming", "gaming"),
        "music" to listOf("trending_music", "Trending Music", "music"),
        "movies" to listOf("trending_movies_and_shows", "trending_movies", "movies"),
        "podcasts" to listOf("trending_podcasts_episodes", "trending_podcasts", "podcasts"),
        "all" to listOf("Trending")
    )

    /** Tendencias reales de YouTube (videojuegos, música, películas y series, pódcasts, general). */
    suspend fun trending(kind: String): List<StreamInfoItem> = withContext(Dispatchers.IO) {
        for (id in kioskIds[kind] ?: kioskIds.getValue("all")) {
            val items = runCatching {
                val url = youtube.kioskList.getListLinkHandlerFactoryByType(id).fromId(id).url
                KioskInfo.getInfo(youtube, url).relatedItems.filterIsInstance<StreamInfoItem>()
            }.getOrNull()
            if (!items.isNullOrEmpty()) return@withContext items
        }
        trendingBySearch(kind)
    }

    /** Respaldo: se aproxima con búsquedas por tema si el quiosco no responde. */
    private suspend fun trendingBySearch(kind: String): List<StreamInfoItem> = coroutineScope {
        val queries = when (kind) {
            "gaming" -> listOf("tendencias videojuegos", "gaming trending", "nuevos lanzamientos videojuegos")
            "music" -> listOf("música tendencias", "top canciones hoy", "new music video official")
            "movies" -> listOf("tráiler película estreno", "movie trailer new", "tráiler serie estreno")
            "podcasts" -> listOf("podcast episodio completo", "mejores podcasts", "podcast entrevista")
            else -> listOf("tendencias hoy", "trending now", "lo más visto hoy")
        }
        val lists = queries.map { q -> async { runCatching { search(q) }.getOrDefault(emptyList()) } }.awaitAll()
        val mixed = ArrayList<StreamInfoItem>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) lists.forEach { l -> l.getOrNull(i)?.let(mixed::add) }
        mixed.filter { it.duration !in 1L..65L }.distinctBy { it.url }
    }

    /** Últimos vídeos de un canal (para "Novedades"). */
    suspend fun channelLatest(url: String, limit: Int): List<StreamInfoItem> = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(serviceFor(url), url)
        val tab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
            ?: info.tabs.firstOrNull()
            ?: return@withContext emptyList()
        ChannelTabInfo.getInfo(youtube, tab).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .take(limit)
    }

    /**
     * Directos: YouTube no da una lista de "en vivo" por NewPipeExtractor, así que se buscan
     * varias consultas y se quedan solo los resultados que son emisiones en directo.
     */
    suspend fun live(): List<StreamInfoItem> = coroutineScope {
        val queries = listOf("en directo", "directo ahora", "live now", "live stream")
        queries.map { q -> async { runCatching { search(q) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
            .filter { it.streamType == StreamType.LIVE_STREAM }
            .distinctBy { it.url }
    }

    /**
     * "Para ti" (FYP) local: YouTube no ofrece su portada por NewPipeExtractor,
     * así que la construimos buscando a partir de lo que has visto (o temas por defecto)
     * y mezclando los resultados.
     */
    suspend fun feed(seedTitles: List<String>): List<StreamInfoItem> = coroutineScope {
        val seeds = if (seedTitles.isEmpty()) defaultTopics.shuffled().take(3)
        else seedTitles

        val lists = seeds.map { seed ->
            async { runCatching { search(seed) }.getOrDefault(emptyList()) }
        }.awaitAll()

        // Intercalar resultados de cada búsqueda para variar el feed
        val mixed = mutableListOf<StreamInfoItem>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) lists.forEach { l -> l.getOrNull(i)?.let(mixed::add) }

        // Sin shorts en el feed de inicio
        mixed.distinctBy { it.url }.filter { it.duration !in 1L..65L }
    }
}
