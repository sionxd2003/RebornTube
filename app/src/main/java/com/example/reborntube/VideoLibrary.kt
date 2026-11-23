package com.example.reborntube

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Historial, me gusta, no me gusta, "más tarde", suscripciones y progreso guardados en el móvil.
 * Los votos y suscripciones son locales: YouTube no permite hacerlo sin iniciar sesión.
 */
class VideoLibrary(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun history() = read(HISTORY)
    fun liked() = read(LIKED)
    fun disliked() = read(DISLIKED)
    fun later() = read(LATER)

    fun addHistory(card: VideoCard) {
        write(HISTORY, (listOf(card) + history().filter { it.url != card.url }).take(MAX_HISTORY))
    }

    fun removeHistory(url: String) {
        write(HISTORY, history().filter { it.url != url })
        setProgress(url, 0)
    }

    fun clearHistory() {
        prefs.edit().remove(HISTORY).apply()
    }

    fun vote(url: String): Int = when {
        liked().any { it.url == url } -> 1
        disliked().any { it.url == url } -> -1
        else -> 0
    }

    /** value: 1 = me gusta, -1 = no me gusta, 0 = quitar voto. Las dos listas son independientes. */
    fun setVote(card: VideoCard, value: Int) {
        val liked = liked().filter { it.url != card.url }
        val disliked = disliked().filter { it.url != card.url }
        write(LIKED, if (value == 1) listOf(card) + liked else liked)
        write(DISLIKED, if (value == -1) listOf(card) + disliked else disliked)
    }

    fun toggleLater(card: VideoCard) {
        val current = later()
        val exists = current.any { it.url == card.url }
        write(LATER, if (exists) current.filter { it.url != card.url } else listOf(card) + current)
    }

    // ----- Historial de búsqueda -----

    fun searches(): List<String> {
        val arr = JSONArray(prefs.getString(SEARCHES, "[]"))
        return List(arr.length()) { arr.getString(it) }
    }

    private fun writeSearches(list: List<String>) {
        prefs.edit().putString(SEARCHES, JSONArray(list).toString()).apply()
    }

    fun addSearch(query: String) {
        writeSearches((listOf(query) + searches().filter { !it.equals(query, ignoreCase = true) }).take(30))
    }

    fun removeSearch(query: String) {
        writeSearches(searches().filter { it != query })
    }

    fun clearSearches() {
        prefs.edit().remove(SEARCHES).apply()
    }

    // ----- Suscripciones -----

    fun subscriptions(): List<ChannelCard> {
        val arr = JSONArray(prefs.getString(SUBS, "[]"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            ChannelCard(
                url = o.getString("url"),
                name = o.optString("name"),
                thumb = o.optString("thumb").ifEmpty { null },
                subscribers = o.optLong("subs", -1),
                lastVideoId = o.optString("lastVideoId").ifEmpty { null }
            )
        }
    }

    fun isChannelNotificationsEnabled(url: String): Boolean =
        channelKey(url) !in disabledChannelNotifications()

    fun setChannelNotificationsEnabled(url: String, enabled: Boolean) {
        val key = channelKey(url)
        val disabled = disabledChannelNotifications().toMutableSet()
        if (enabled) disabled.remove(key) else disabled.add(key)
        prefs.edit().putString(
            DISABLED_CHANNEL_NOTIFICATIONS,
            JSONArray(disabled.sorted()).toString()
        ).apply()
    }

    private fun disabledChannelNotifications(): Set<String> =
        runCatching {
            val array = JSONArray(prefs.getString(DISABLED_CHANNEL_NOTIFICATIONS, "[]") ?: "[]")
            buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
        }.getOrDefault(emptySet())

    fun toggleSubscription(card: ChannelCard) {
        val current = subscriptions()
        val exists = current.any { channelKey(it.url) == channelKey(card.url) }
        val updated = if (exists) current.filter { channelKey(it.url) != channelKey(card.url) }
        else listOf(card) + current
        val arr = JSONArray()
        updated.forEach { arr.put(channelJson(it)) }
        prefs.edit().putString(SUBS, arr.toString()).apply()
    }

    fun updateSubscriptionLastVideo(url: String, videoId: String): Boolean {
        val current = subscriptions()
        val index = current.indexOfFirst { channelKey(it.url) == channelKey(url) }
        if (index < 0 || current[index].lastVideoId == videoId) return false
        val updated = current.toMutableList()
        updated[index] = updated[index].copy(lastVideoId = videoId)
        val arr = JSONArray()
        updated.forEach { arr.put(channelJson(it)) }
        prefs.edit().putString(SUBS, arr.toString()).apply()
        return true
    }

    fun channelGroups(): List<ChannelGroup> {
        val arr = JSONArray(prefs.getString(CHANNEL_GROUPS, "[]"))
        return List(arr.length()) { index ->
            val item = arr.getJSONObject(index)
            val channels = item.optJSONArray("channels") ?: JSONArray()
            ChannelGroup(
                item.optString("id"), item.optString("name"),
                List(channels.length()) { channels.optString(it) }
            )
        }
    }

    fun createChannelGroup(name: String): ChannelGroup {
        val group = ChannelGroup(System.currentTimeMillis().toString(), name.trim(), emptyList())
        writeChannelGroups(channelGroups() + group)
        return group
    }

    fun deleteChannelGroup(id: String) = writeChannelGroups(channelGroups().filterNot { it.id == id })

    fun channelInGroup(id: String, channelUrl: String): Boolean = channelGroups()
        .firstOrNull { it.id == id }?.channelUrls?.any { channelKey(it) == channelKey(channelUrl) } == true

    fun toggleChannelInGroup(id: String, channelUrl: String) {
        writeChannelGroups(channelGroups().map { group ->
            if (group.id != id) group
            else {
                val current = group.channelUrls.filterNot { channelKey(it) == channelKey(channelUrl) }
                group.copy(channelUrls = if (current.size == group.channelUrls.size) current + channelUrl else current)
            }
        })
    }

    private fun writeChannelGroups(groups: List<ChannelGroup>) {
        val array = JSONArray()
        groups.forEach { group ->
            array.put(JSONObject().put("id", group.id).put("name", group.name)
                .put("channels", JSONArray(group.channelUrls)))
        }
        prefs.edit().putString(CHANNEL_GROUPS, array.toString()).apply()
    }

    // ----- Progreso de reproducción (milisegundos) -----

    fun progress(url: String): Long = progressObj().optLong(url, 0L)

    fun progressMap(): Map<String, Long> {
        val o = progressObj()
        val map = HashMap<String, Long>()
        o.keys().forEach { map[it] = o.optLong(it) }
        return map
    }

    fun setProgress(url: String, positionMs: Long) {
        val o = progressObj()
        if (positionMs <= 0) o.remove(url) else o.put(url, positionMs)
        if (o.length() > MAX_PROGRESS) o.remove(o.keys().next())
        prefs.edit().putString(PROGRESS, o.toString()).apply()
    }

    private fun progressObj() = JSONObject(prefs.getString(PROGRESS, "{}") ?: "{}")

    // ----- Copia de seguridad -----

    fun exportJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("history", JSONArray(prefs.getString(HISTORY, "[]")))
        .put("liked", JSONArray(prefs.getString(LIKED, "[]")))
        .put("disliked", JSONArray(prefs.getString(DISLIKED, "[]")))
        .put("later", JSONArray(prefs.getString(LATER, "[]")))
        .put("subs", JSONArray(prefs.getString(SUBS, "[]")))
        .put("channelGroups", JSONArray(prefs.getString(CHANNEL_GROUPS, "[]")))
        .put("progress", progressObj())
        .put("playlists", JSONArray(prefs.getString(PLAYLISTS, "[]")))
        .put("searches", JSONArray(prefs.getString(SEARCHES, "[]")))

    /** Une la copia con lo que ya hay (no borra nada). */
    fun importJson(json: JSONObject) {
        merge(HISTORY, json.optJSONArray("history")) { it.optString("url") }
        merge(LIKED, json.optJSONArray("liked")) { it.optString("url") }
        merge(DISLIKED, json.optJSONArray("disliked")) { it.optString("url") }
        merge(LATER, json.optJSONArray("later")) { it.optString("url") }
        merge(SUBS, json.optJSONArray("subs")) { channelKey(it.optString("url")) }
        json.optJSONArray("channelGroups")?.let { incoming ->
            val existing = channelGroups().associateBy { it.name.lowercase() }.toMutableMap()
            for (i in 0 until incoming.length()) {
                val o = incoming.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                if (name.isBlank()) continue
                val urls = o.optJSONArray("channels") ?: JSONArray()
                val imported = List(urls.length()) { urls.optString(it).orEmpty() }.filter { it.isNotBlank() }
                val key = name.lowercase()
                val old = existing[key]
                existing[key] = if (old == null) ChannelGroup(o.optString("id").ifBlank { System.currentTimeMillis().toString() }, name, imported)
                    else old.copy(channelUrls = (old.channelUrls + imported).distinctBy(::channelKey))
            }
            writeChannelGroups(existing.values.toList())
        }
        json.optJSONArray("playlists")?.let { importPlaylists(it) }
        json.optJSONArray("searches")?.let { incoming ->
            val current = searches().toMutableList()
            for (i in 0 until incoming.length()) {
                val q = incoming.optString(i)
                if (q.isNotBlank() && current.none { it.equals(q, ignoreCase = true) }) current.add(q)
            }
            writeSearches(current.take(30))
        }
        json.optJSONObject("progress")?.let { incoming ->
            val current = progressObj()
            incoming.keys().forEach { k -> if (!current.has(k)) current.put(k, incoming.optLong(k)) }
            prefs.edit().putString(PROGRESS, current.toString()).apply()
        }
    }

    private fun importPlaylists(incoming: JSONArray) {
        val current = playlists().toMutableList()
        for (i in 0 until incoming.length()) {
            val o = incoming.optJSONObject(i) ?: continue
            val name = o.optString("name")
            val items = o.optJSONArray("items") ?: JSONArray()
            val cards = List(items.length()) { j -> cardFrom(items.getJSONObject(j)) }
            val idx = current.indexOfFirst { it.name == name }
            if (idx >= 0) {
                val existing = current[idx]
                current[idx] = existing.copy(
                    items = existing.items + cards.filter { c -> existing.items.none { it.url == c.url } }
                )
            } else {
                current.add(Playlist(o.optString("id").ifEmpty { "${System.currentTimeMillis()}$i" }, name, cards))
            }
        }
        writePlaylists(current)
    }

    fun mergeHistory(cards: List<VideoCard>) {
        val arr = JSONArray()
        cards.forEach { arr.put(videoJson(it)) }
        merge(HISTORY, arr) { it.optString("url") }
    }

    fun mergeSubscriptions(cards: List<ChannelCard>) {
        val arr = JSONArray()
        cards.forEach { arr.put(channelJson(it)) }
        merge(SUBS, arr) { channelKey(it.optString("url")) }
    }

    private fun merge(key: String, incoming: JSONArray?, keyOf: (JSONObject) -> String) {
        if (incoming == null) return
        val current = JSONArray(prefs.getString(key, "[]"))
        val seen = HashSet<String>()
        val out = JSONArray()
        for (i in 0 until current.length()) {
            val o = current.getJSONObject(i)
            if (seen.add(keyOf(o))) out.put(o)
        }
        for (i in 0 until incoming.length()) {
            val o = incoming.optJSONObject(i) ?: continue
            if (keyOf(o).isNotEmpty() && seen.add(keyOf(o))) out.put(o)
        }
        prefs.edit().putString(key, out.toString()).apply()
    }

    // ----- Listas de reproducción propias -----

    fun playlists(): List<Playlist> {
        val arr = JSONArray(prefs.getString(PLAYLISTS, "[]"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val items = o.optJSONArray("items") ?: JSONArray()
            Playlist(
                o.getString("id"),
                o.optString("name"),
                List(items.length()) { j -> cardFrom(items.getJSONObject(j)) }
            )
        }
    }

    private fun writePlaylists(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { pl ->
            val items = JSONArray()
            pl.items.forEach { items.put(videoJson(it)) }
            arr.put(JSONObject().put("id", pl.id).put("name", pl.name).put("items", items))
        }
        prefs.edit().putString(PLAYLISTS, arr.toString()).apply()
    }

    fun createPlaylist(name: String): String {
        val id = System.currentTimeMillis().toString()
        writePlaylists(playlists() + Playlist(id, name, emptyList()))
        return id
    }

    fun deletePlaylist(id: String) {
        writePlaylists(playlists().filter { it.id != id })
    }

    /** Añade el vídeo a la lista, o lo quita si ya estaba. */
    fun togglePlaylistItem(id: String, card: VideoCard) {
        writePlaylists(
            playlists().map { pl ->
                if (pl.id != id) pl
                else {
                    val exists = pl.items.any { it.url == card.url }
                    pl.copy(items = if (exists) pl.items.filter { it.url != card.url } else pl.items + card)
                }
            }
        )
    }

    /** Añade varios vídeos a una lista de una sola vez (sin repetir los que ya están). */
    fun addPlaylistItems(id: String, cards: List<VideoCard>) {
        writePlaylists(
            playlists().map { pl ->
                if (pl.id != id) pl
                else pl.copy(items = pl.items + cards.filter { c -> pl.items.none { it.url == c.url } }.distinctBy { it.url })
            }
        )
    }

    // ----- Descargas -----

    fun downloads(): List<DownloadRecord> {
        val arr = JSONArray(prefs.getString(DOWNLOADS, "[]"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            DownloadRecord(
                id = o.getString("id"),
                title = o.optString("title"),
                channel = o.optString("channel"),
                thumb = o.optString("thumb").ifEmpty { null },
                fileName = o.getString("file"),
                dmId = o.optLong("dm"),
                audioOnly = o.optBoolean("audio"),
                duration = o.optLong("duration"),
                artworkPath = o.optString("artworkPath").ifEmpty { null },
                uploaded = o.optString("uploaded").ifEmpty { null },
                workId = o.optString("workId").ifEmpty { null }
            )
        }
    }

    private fun writeDownloads(list: List<DownloadRecord>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channel)
                    .put("thumb", it.thumb ?: "").put("file", it.fileName).put("dm", it.dmId)
                    .put("audio", it.audioOnly).put("duration", it.duration)
                    .put("artworkPath", it.artworkPath ?: "").put("uploaded", it.uploaded ?: "")
                    .put("workId", it.workId ?: "")
            )
        }
        prefs.edit().putString(DOWNLOADS, arr.toString()).apply()
    }

    fun addDownload(rec: DownloadRecord) {
        writeDownloads(listOf(rec) + downloads().filter { it.fileName != rec.fileName })
    }

    fun removeDownload(fileName: String) {
        writeDownloads(downloads().filter { it.fileName != fileName })
    }

    // ----- Interno -----

    private fun cardFrom(o: JSONObject) = VideoCard(
        url = o.getString("url"),
        title = o.optString("title"),
        channel = o.optString("channel"),
        channelUrl = o.optString("channelUrl").ifEmpty { null },
        thumb = o.optString("thumb").ifEmpty { null },
        duration = o.optLong("duration"),
        isLive = o.optBoolean("live", false),
        views = o.optLong("views", -1),
        uploaded = o.optString("uploaded").ifEmpty { null },
        avatar = o.optString("avatar").ifEmpty { null }
    )


    private fun read(key: String): List<VideoCard> {
        val arr = JSONArray(prefs.getString(key, "[]"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            VideoCard(
                url = o.getString("url"),
                title = o.optString("title"),
                channel = o.optString("channel"),
                channelUrl = o.optString("channelUrl").ifEmpty { null },
                thumb = o.optString("thumb").ifEmpty { null },
                duration = o.optLong("duration"),
                isLive = o.optBoolean("live", false),
                views = o.optLong("views", -1),
                uploaded = o.optString("uploaded").ifEmpty { null },
                avatar = o.optString("avatar").ifEmpty { null }
            )
        }
    }

    private fun write(key: String, list: List<VideoCard>) {
        val arr = JSONArray()
        list.forEach { arr.put(videoJson(it)) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun videoJson(it: VideoCard) = JSONObject()
        .put("url", it.url)
        .put("title", it.title)
        .put("channel", it.channel)
        .put("channelUrl", it.channelUrl ?: "")
        .put("thumb", it.thumb ?: "")
        .put("duration", it.duration)
        .put("live", it.isLive)
        .put("views", it.views)
        .put("uploaded", it.uploaded ?: "")
        .put("avatar", it.avatar ?: "")

    private fun channelJson(it: ChannelCard) = JSONObject()
        .put("url", it.url)
        .put("name", it.name)
        .put("thumb", it.thumb ?: "")
        .put("subs", it.subscribers)
        .put("lastVideoId", it.lastVideoId ?: "")

    private companion object {
        const val HISTORY = "history"
        const val LIKED = "liked"
        const val DISLIKED = "disliked"
        const val LATER = "later"
        const val SUBS = "subs"
        const val CHANNEL_GROUPS = "channel_groups"
        const val PROGRESS = "progress"
        const val PLAYLISTS = "playlists"
        const val SEARCHES = "searches"
        const val DOWNLOADS = "downloads"
        const val DISABLED_CHANNEL_NOTIFICATIONS = "disabled_channel_notifications"
        const val MAX_HISTORY = 300
        const val MAX_PROGRESS = 1000
    }
}
