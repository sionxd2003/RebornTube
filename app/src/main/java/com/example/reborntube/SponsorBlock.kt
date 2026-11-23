package com.example.reborntube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class SponsorSegment(
    val uuid: String,
    val category: String,
    val startMs: Long,
    val endMs: Long,
    val votes: Int
)

/**
 * SponsorBlock community API. Reads use a 4-character video hash prefix to protect privacy.
 * Submissions and votes are only made from explicit user actions.
 */
object SponsorBlock {
    val availableCategories = listOf(
        "sponsor", "selfpromo", "interaction", "intro", "outro",
        "preview", "filler", "music_offtopic", "exclusive_access"
    )
    private const val BASE_URL = "https://sponsor.ajay.app"

    suspend fun segments(
        videoId: String,
        categories: Set<String> = setOf("sponsor", "selfpromo", "interaction", "intro", "outro")
    ): List<SponsorSegment> = withContext(Dispatchers.IO) {
        val selected = categories.intersect(availableCategories.toSet())
        if (selected.isEmpty()) return@withContext emptyList()
        val query = URLEncoder.encode(JSONArray(selected.sorted()).toString(), "UTF-8")
        val connection = URL("$BASE_URL/api/skipSegments/${hashPrefix(videoId)}?categories=$query")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 6_000
        connection.readTimeout = 6_000
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return@withContext emptyList()
            if (connection.responseCode !in 200..299) {
                error("SponsorBlock devolvió HTTP ${connection.responseCode}")
            }
            val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            buildList {
                for (i in 0 until array.length()) {
                    val video = array.optJSONObject(i) ?: continue
                    if (video.optString("videoID") != videoId) continue
                    val entries = video.optJSONArray("segments") ?: continue
                    for (j in 0 until entries.length()) {
                        val segment = entries.optJSONObject(j) ?: continue
                        if (segment.optString("actionType", "skip") != "skip") continue
                        val range = segment.optJSONArray("segment") ?: continue
                        if (range.length() < 2) continue
                        val start = (range.optDouble(0, -1.0) * 1000).toLong()
                        val end = (range.optDouble(1, -1.0) * 1000).toLong()
                        if (start < 0 || end <= start) continue
                        add(
                            SponsorSegment(
                                uuid = segment.optString("UUID"),
                                category = segment.optString("category"),
                                startMs = start,
                                endMs = end,
                                votes = segment.optInt("votes")
                            )
                        )
                    }
                }
            }.sortedBy { it.startMs }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun submitSegment(
        videoId: String,
        userId: String,
        userAgent: String,
        startSeconds: Float,
        endSeconds: Float,
        category: String,
        durationSeconds: Float
    ) = withContext(Dispatchers.IO) {
        require(category in availableCategories)
        require(startSeconds >= 0f && endSeconds > startSeconds)
        val segment = JSONObject()
            .put("segment", JSONArray().put(startSeconds).put(endSeconds))
            .put("category", category)
            .put("actionType", "skip")
        val body = JSONObject()
            .put("videoID", videoId)
            .put("userID", userId)
            .put("userAgent", userAgent)
            .put("videoDuration", durationSeconds)
            .put("segments", JSONArray().put(segment))
            .toString()
        request(
            URL("$BASE_URL/api/skipSegments"),
            method = "POST",
            body = body,
            contentType = "application/json"
        )
    }

    suspend fun vote(videoId: String, uuid: String, userId: String, score: Int) = withContext(Dispatchers.IO) {
        require(score == 0 || score == 1 || score == 20)
        val url = URL(
            "$BASE_URL/api/voteOnSponsorTime?UUID=${encode(uuid)}&videoID=${encode(videoId)}" +
                "&userID=${encode(userId)}&type=$score"
        )
        request(url, method = "POST")
    }

    private fun request(url: URL, method: String, body: String? = null, contentType: String? = null) {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType ?: "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }
                error(message?.takeIf(String::isNotBlank) ?: "SponsorBlock devolvió HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun hashPrefix(videoId: String): String =
        MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(4)

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
