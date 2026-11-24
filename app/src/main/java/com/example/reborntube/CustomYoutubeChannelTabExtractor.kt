package com.example.reborntube

import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

object CustomYoutubeChannelTabExtractor {
    const val PARAM_POPULAR = ""
    const val PARAM_OLDEST = ""

    data class ChannelVideoPage(
        val items: List<StreamInfoItem>,
        val nextPage: Page?
    )

    fun isAvailable(sort: ChannelVideoSortOrder): Boolean = when (sort) {
        ChannelVideoSortOrder.LATEST -> true
        ChannelVideoSortOrder.POPULAR -> PARAM_POPULAR.isNotBlank()
        ChannelVideoSortOrder.OLDEST -> PARAM_OLDEST.isNotBlank()
    }

    fun browse(sort: ChannelVideoSortOrder, browseId: String, continuation: String? = null): ChannelVideoPage {
        val body = JSONObject().apply {
            put("context", JSONObject().put("client", JSONObject()
                .put("hl", java.util.Locale.getDefault().language)
                .put("gl", java.util.Locale.getDefault().country.ifBlank { "US" })
                .put("clientName", "ANDROID")
                .put("clientVersion", "20.07")
            ))
            if (continuation == null) {
                put("browseId", browseId)
                val params = when (sort) {
                    ChannelVideoSortOrder.POPULAR -> PARAM_POPULAR
                    ChannelVideoSortOrder.OLDEST -> PARAM_OLDEST
                    ChannelVideoSortOrder.LATEST -> ""
                }
                if (params.isNotBlank()) put("params", params)
            } else {
                put("continuation", continuation)
            }
        }

        val response: Response = DownloaderImpl.instance.postWithContentTypeJson(
            "https://www.youtube.com/youtubei/v1/browse",
            emptyMap(),
            body.toString().toByteArray(Charsets.UTF_8)
        )
        if (response.responseCode() !in 200..299) {
            throw IllegalStateException("Innertube respondió HTTP ${response.responseCode()}")
        }
        val root = JSONObject(response.responseBody() ?: "{}")
        val items = parseItems(root)
        val token = findContinuation(root)
        return ChannelVideoPage(items, token?.takeIf(String::isNotBlank)?.let(::Page))
    }

    private fun parseItems(root: JSONObject): List<StreamInfoItem> {
        val items = ArrayList<StreamInfoItem>()
        val seen = HashSet<String>()
        collectRenderers(root, items, seen)
        return items
    }

    private fun collectRenderers(
        value: Any?,
        output: MutableList<StreamInfoItem>,
        seen: MutableSet<String>
    ) {
        when (value) {
            is JSONObject -> {
                val renderer = value.optJSONObject("gridVideoRenderer")
                    ?: value.optJSONObject("videoRenderer")
                    ?: value.optJSONObject("lockupViewModel")
                if (renderer != null) {
                    rendererToItem(renderer, value)?.let {
                        if (seen.add(it.url)) output.add(it)
                    }
                }
                val keys = value.keys()
                while (keys.hasNext()) collectRenderers(value.opt(keys.next()), output, seen)
            }
            is JSONArray -> for (i in 0 until value.length()) {
                collectRenderers(value.opt(i), output, seen)
            }
        }
    }

    private fun rendererToItem(renderer: JSONObject, container: JSONObject): StreamInfoItem? {
        val videoId = renderer.optString("videoId")
            .ifBlank { renderer.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")?.optString("videoId").orEmpty() }
        if (videoId.isBlank()) return null

        val title = simpleText(renderer.opt("title"))
            .ifBlank { simpleText(renderer.opt("headline")) }
        val uploader = simpleText(renderer.opt("longBylineText"))
            .ifBlank { simpleText(renderer.opt("ownerText")) }
            .ifBlank { simpleText(renderer.opt("shortBylineText")) }
        val uploadText = simpleText(renderer.opt("publishedTimeText"))
            .ifBlank { simpleText(renderer.opt("metadata")) }
        val duration = parseDuration(simpleText(renderer.opt("lengthText")))
        val views = parseViews(simpleText(renderer.opt("viewCountText"))
            .ifBlank { simpleText(renderer.opt("shortViewCountText")) })

        val item = StreamInfoItem(
            2,
            "https://www.youtube.com/watch?v=$videoId",
            title,
            StreamType.VIDEO_STREAM
        )
        item.uploaderName = uploader
        item.duration = duration
        item.viewCount = views
        item.textualUploadDate = uploadText
        item.thumbnails = parseThumbnails(renderer)
        val uploaderUrl = renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")?.optString("browseId")
            ?.takeIf(String::isNotBlank)
            ?.let { "https://www.youtube.com/channel/$it" }
        item.uploaderUrl = uploaderUrl
        return item
    }

    private fun parseThumbnails(renderer: JSONObject): List<org.schabi.newpipe.extractor.Image> {
        val array = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: renderer.optJSONObject("thumbnailViewModel")?.optJSONArray("thumbnails")
            ?: return emptyList()
        val out = ArrayList<org.schabi.newpipe.extractor.Image>()
        for (i in 0 until array.length()) {
            val image = array.optJSONObject(i)
            if (image != null) {
                val url = image.optString("url")
                if (url.isNotBlank()) {
                    val height = image.optInt("height", org.schabi.newpipe.extractor.Image.HEIGHT_UNKNOWN)
                    val width = image.optInt("width", org.schabi.newpipe.extractor.Image.WIDTH_UNKNOWN)
                    out += org.schabi.newpipe.extractor.Image(
                        url,
                        width,
                        height,
                        org.schabi.newpipe.extractor.Image.ResolutionLevel.fromHeight(height)
                    )
                }
            }
        }
        return out
    }

    private fun simpleText(value: Any?): String = when (value) {
        is JSONObject -> value.optString("simpleText").ifBlank {
            val runs = value.optJSONArray("runs")
            buildString {
                if (runs != null) for (i in 0 until runs.length()) {
                    append(runs.optJSONObject(i)?.optString("text").orEmpty())
                }
            }
        }
        is String -> value
        else -> ""
    }

    private fun parseDuration(text: String): Long {
        val parts = text.trim().split(":").mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) return -1L
        var seconds = 0L
        parts.forEach { seconds = seconds * 60 + it }
        return seconds
    }

    private fun parseViews(text: String): Long {
        val digits = text.lowercase().replace(Regex("[^0-9,.]"), "")
            .replace(".", "").replace(",", "")
        val value = digits.toLongOrNull() ?: -1L
        return when {
            value >= 0 && text.contains(Regex("[Mm]")) -> value * 1_000_000L
            value >= 0 && text.contains(Regex("[Kk]")) -> value * 1_000L
            else -> value
        }
    }

    private fun findContinuation(root: JSONObject): String? {
        var found: String? = null
        fun walk(value: Any?) {
            if (found != null) return
            when (value) {
                is JSONObject -> {
                    val token = value.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")?.optString("token")
                        ?.ifBlank { null }
                    if (token != null) found = token
                    val keys = value.keys()
                    while (keys.hasNext()) walk(value.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        walk(root)
        return found
    }
}
