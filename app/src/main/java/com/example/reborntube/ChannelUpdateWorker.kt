package com.example.reborntube

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.TimeUnit

data class LatestChannelVideo(val videoId: String, val title: String)

/** Consulta instancias públicas oficiales de Invidious, en orden de respaldo. */
object ChannelUpdateClient {
    private val instances = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://yt.chocolatemoo53.com",
        "https://invidious.tiekoetter.com"
    )
    private val channelIdPattern = Regex("UC[A-Za-z0-9_-]{22}")

    suspend fun fetchLatest(channelUrl: String): LatestChannelVideo? {
        val initialId = channelIdPattern.find(channelUrl)?.value
        for (instance in instances) {
            val channelId = initialId ?: resolveChannelId(instance, channelUrl) ?: continue
            val payload = getJson("$instance/api/v1/channels/$channelId/latest") ?: continue
            val videos = when (payload) {
                is JSONObject -> payload.optJSONArray("videos") ?: payload.optJSONArray("latestVideos")
                is JSONArray -> payload
                else -> null
            } ?: continue
            for (i in 0 until videos.length()) {
                val item = videos.optJSONObject(i) ?: continue
                val id = item.optString("videoId").ifBlank { item.optString("id") }
                if (id.isNotBlank()) {
                    val title = item.optString("title").ifBlank { "Nuevo video" }
                    return LatestChannelVideo(id, title)
                }
            }
        }
        return null
    }

    private suspend fun resolveChannelId(instance: String, channelUrl: String): String? {
        val query = URLEncoder.encode(channelUrl, Charsets.UTF_8.name())
        val results = getJson("$instance/api/v1/search?q=$query&type=channel") as? JSONArray ?: return null
        for (i in 0 until results.length()) {
            val id = results.optJSONObject(i)?.optString("authorId")
            if (!id.isNullOrBlank() && channelIdPattern.matches(id)) return id
        }
        return null
    }

    private suspend fun getJson(address: String): Any? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "RebornTube/1.0 (Android)")
            try {
                if (connection.responseCode !in 200..299) return@withContext null
                connection.inputStream.bufferedReader().use { JSONTokener(it.readText()).nextValue() }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        } finally {
            // Respeta el límite entre cada petición, también cuando una instancia falla.
            delay(500)
        }
    }
}

class ChannelUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("new_video_notifications", true)
        ) return Result.success()

        val library = VideoLibrary(applicationContext)
        val channels = library.subscriptions().filter { channel ->
            library.isChannelNotificationsEnabled(channel.url) &&
            runCatching {
                YouTubeRepository.serviceIdForUrl(channel.url) == org.schabi.newpipe.extractor.ServiceList.YouTube.serviceId
            }.getOrDefault(false)
        }
        if (channels.isEmpty()) return Result.success()

        var successfulChecks = 0
        for (channel in channels) {
            if (isStopped) return Result.retry()
            val latest = ChannelUpdateClient.fetchLatest(channel.url)
            if (latest != null) {
                successfulChecks++
                val previous = channel.lastVideoId
                library.updateSubscriptionLastVideo(channel.url, latest.videoId)
                if (!previous.isNullOrBlank() && previous != latest.videoId) {
                    notifyNewVideo(channel, latest)
                }
            }
            if (channel != channels.last()) delay(500)
        }
        return if (successfulChecks > 0) Result.success() else Result.retry()
    }

    private fun notifyNewVideo(channel: ChannelCard, video: LatestChannelVideo) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureNotificationChannel()
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://www.youtube.com/watch?v=${Uri.encode(video.videoId)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            video.videoId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(applicationContext, NOTIFICATION_CHANNEL)
        } else {
            Notification.Builder(applicationContext)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(channel.name.ifBlank { tr("Nuevo video") })
            .setContentText(video.title)
            .setStyle(Notification.BigTextStyle().bigText(video.title))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(applicationContext)
                .notify(video.videoId.hashCode(), notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL, tr("Videos nuevos"), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        private const val PERIODIC_WORK = "channel_video_updates_periodic"
        private const val IMMEDIATE_WORK = "channel_video_updates_now"
        private const val NOTIFICATION_CHANNEL = "new_channel_videos"

        fun schedule(context: Context) {
            val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (!settings.getBoolean("new_video_notifications", true)) {
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
                return
            }
            val interval = settings.getInt("notification_interval_minutes", 30)
                .takeIf { it in listOf(15, 30, 60, 120) } ?: 30
            val networkType = if (settings.getBoolean("notification_wifi_only", false)) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()
            val request = PeriodicWorkRequestBuilder<ChannelUpdateWorker>(interval.toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ChannelUpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
