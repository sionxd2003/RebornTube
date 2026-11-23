package com.example.reborntube

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class DeArrowBranding(
    val title: String?,
    val thumbnailUrl: String?
)

object DeArrow {
    private const val BASE_URL = "https://sponsor.ajay.app"
    private const val THUMBNAIL_URL = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail"
    private const val CACHE_TTL_MS = 60 * 60 * 1000L

    private data class CachedBranding(val expiresAt: Long, val value: DeArrowBranding?)
    private val cache = LruCache<String, CachedBranding>(256)

    fun videoId(videoUrl: String): String? {
        val uri = android.net.Uri.parse(videoUrl)
        val host = uri.host?.lowercase() ?: return null
        val id = when {
            host == "youtu.be" -> uri.lastPathSegment
            host == "youtube.com" || host.endsWith(".youtube.com") ->
                uri.getQueryParameter("v") ?: uri.pathSegments.firstOrNull { it == "shorts" || it == "embed" }
                    ?.let { marker -> uri.pathSegments.getOrNull(uri.pathSegments.indexOf(marker) + 1) }
            else -> null
        }
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }

    suspend fun branding(videoId: String, fallbackThumbnail: String?): DeArrowBranding? =
        withContext(Dispatchers.IO) {
            synchronized(cache) {
                cache.get(videoId)?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
                    return@withContext it.value
                }
            }
            val prefix = MessageDigest.getInstance("SHA-256")
                .digest(videoId.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(4)
            val connection = URL("$BASE_URL/api/branding/$prefix").openConnection() as HttpURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            val branding = try {
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_NOT_FOUND) return@withContext cache(videoId, null)
                if (code !in 200..299) error("DeArrow devolvió HTTP $code")
                val payload = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val entry = payload.optJSONObject(videoId)
                if (entry == null) null else {
                    val title = entry.optJSONArray("titles")?.optJSONObject(0)?.let { candidate ->
                        candidate.takeIf {
                            !it.optBoolean("original") &&
                                (it.optBoolean("locked") || it.optInt("votes", -1) >= 0) &&
                                it.optString("title").isNotBlank()
                        }?.optString("title")
                            ?.replace(">", "")
                            ?.trim()
                            ?.takeIf(String::isNotBlank)
                    }
                    val timestamp = entry.optJSONArray("thumbnails")?.optJSONObject(0)?.let { candidate ->
                        candidate.takeIf {
                            !it.optBoolean("original") &&
                                (it.optBoolean("locked") || it.optInt("votes", -1) >= 0) &&
                                it.optDouble("timestamp", -1.0) >= 0.0
                        }?.optDouble("timestamp", -1.0)
                    }
                    val thumb = timestamp?.takeIf { it >= 0.0 }?.let { time ->
                        val fallback = fallbackThumbnail?.takeIf { it.startsWith("https://i.ytimg.com/") }
                        buildString {
                            append(THUMBNAIL_URL)
                            append("?videoID=").append(encode(videoId))
                            append("&time=").append(time)
                            if (fallback != null) append("&redirectUrl=").append(encode(fallback))
                        }
                    }
                    if (title == null && thumb == null) null else DeArrowBranding(title, thumb)
                }
            } finally {
                connection.disconnect()
            }
            cache(videoId, branding)
        }

    suspend fun submit(
        videoId: String,
        userId: String,
        userAgent: String,
        title: String?,
        thumbnailTimestamp: Float?
    ): Unit = withContext(Dispatchers.IO) {
        require(!title.isNullOrBlank() || thumbnailTimestamp != null)
        val body = JSONObject()
            .put("videoID", videoId)
            .put("userID", userId)
            .put("userAgent", userAgent)
            .apply {
                if (!title.isNullOrBlank()) put("title", JSONObject().put("title", title.trim()))
                if (thumbnailTimestamp != null) {
                    put(
                        "thumbnail",
                        JSONObject().put("timestamp", thumbnailTimestamp).put("original", false)
                    )
                }
            }
        val connection = URL("$BASE_URL/api/branding").openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json")
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }
                error(message?.takeIf(String::isNotBlank) ?: "DeArrow devolvió HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
        synchronized(cache) { cache.remove(videoId) }
        Unit
    }

    private fun cache(videoId: String, value: DeArrowBranding?): DeArrowBranding? {
        synchronized(cache) {
            cache.put(videoId, CachedBranding(System.currentTimeMillis() + CACHE_TTL_MS, value))
        }
        return value
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
