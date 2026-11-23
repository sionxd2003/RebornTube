package com.example.reborntube

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class FireTvProgressService : Service() {
    private val running = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null
    @Volatile private var lastProgressAt = SystemClock.elapsedRealtime()

    private val stopWhenInactive = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() - lastProgressAt > INACTIVE_TIMEOUT_MS) {
                stopSelf()
            } else {
                handler.postDelayed(this, INACTIVE_CHECK_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("RebornTube")
            .setContentText("Sincronizando el progreso con Fire TV")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startServer()
        handler.postDelayed(stopWhenInactive, INACTIVE_CHECK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        val videoUrl = intent?.getStringExtra(EXTRA_VIDEO_URL).orEmpty()
        if (token.matches(Regex("[A-Za-z0-9_-]{32,64}")) && videoUrl.startsWith("https://")) {
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_VIDEO_URL, videoUrl)
                .apply()
        }
        lastProgressAt = SystemClock.elapsedRealtime()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.set(false)
        handler.removeCallbacksAndMessages(null)
        runCatching { server?.close() }
        server = null
        super.onDestroy()
    }

    private fun startServer() {
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                val socket = ServerSocket(PORT)
                server = socket
                while (running.get()) {
                    val client = socket.accept()
                    Thread({ handleClient(client) }, "fire-tv-progress-client").start()
                }
            } catch (_: Exception) {
                if (running.get()) {
                    Log.e(TAG, "No se pudo iniciar el servidor de progreso de Fire TV.")
                    stopSelf()
                }
            }
        }, "fire-tv-progress-server").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            try {
                val requestLine = readLine(input, 4096) ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size < 2 || parts[0] != "POST" || parts[1] != "/progress") {
                    respond(output, 404)
                    return
                }
                var contentLength = 0
                while (true) {
                    val line = readLine(input, 8192) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0 && line.substring(0, colon).equals("Content-Length", true)) {
                        contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: 0
                    }
                }
                if (contentLength !in 1..8192) {
                    respond(output, 400)
                    return
                }
                val body = ByteArray(contentLength)
                var offset = 0
                while (offset < body.size) {
                    val count = input.read(body, offset, body.size - offset)
                    if (count < 0) break
                    offset += count
                }
                if (offset != body.size) {
                    respond(output, 400)
                    return
                }
                val fields = body.toString(Charsets.UTF_8).split('&').mapNotNull { item ->
                    val index = item.indexOf('=')
                    if (index < 0) null else URLDecoder.decode(item.substring(0, index), "UTF-8") to
                        URLDecoder.decode(item.substring(index + 1), "UTF-8")
                }.toMap()
                val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                val expectedToken = preferences.getString(KEY_TOKEN, null).orEmpty()
                val expectedUrl = preferences.getString(KEY_VIDEO_URL, null).orEmpty()
                val suppliedToken = fields["token"].orEmpty()
                if (expectedToken.isEmpty() || expectedUrl.isEmpty() ||
                    !MessageDigest.isEqual(
                        expectedToken.toByteArray(Charsets.UTF_8),
                        suppliedToken.toByteArray(Charsets.UTF_8)
                    ) || fields["videoUrl"] != expectedUrl
                ) {
                    respond(output, 403)
                    return
                }
                val position = fields["positionMs"]?.toLongOrNull()?.coerceAtLeast(0) ?: run {
                    respond(output, 400)
                    return
                }
                val duration = fields["durationMs"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
                val savedPosition = if (position < 5_000 || duration > 0 && position >= duration - 10_000) {
                    0L
                } else {
                    position
                }
                VideoLibrary(this).setProgress(expectedUrl, savedPosition)
                lastProgressAt = SystemClock.elapsedRealtime()
                respond(output, 200)
            } catch (error: Exception) {
                Log.w(TAG, "No se pudo procesar la actualización de progreso.", error)
                runCatching { respond(output, 400) }
            }
        }
    }

    private fun readLine(input: BufferedInputStream, limit: Int): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < limit) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.UTF_8)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun respond(output: BufferedOutputStream, code: Int) {
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            else -> "Not Found"
        }
        output.write("HTTP/1.1 $code $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        output.flush()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sincronización con Fire TV",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene actualizado el progreso de reproducción en Fire TV."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val PORT = 8766
        private const val CHANNEL_ID = "fire_tv_progress_sync"
        private const val NOTIFICATION_ID = 8766
        private const val PREFERENCES = "fire_tv_progress_sync"
        private const val KEY_TOKEN = "token"
        private const val KEY_VIDEO_URL = "video_url"
        private const val INACTIVE_TIMEOUT_MS = 90_000L
        private const val INACTIVE_CHECK_MS = 15_000L
        private const val TAG = "FireTvProgressService"
        const val EXTRA_TOKEN = "com.reborntube.app.extra.FIRE_TV_PROGRESS_TOKEN"
        const val EXTRA_VIDEO_URL = "com.reborntube.app.extra.FIRE_TV_VIDEO_URL"

        fun callbackUrl(context: Context): String? {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networks = manager.allNetworks.sortedByDescending { network ->
                val capabilities = manager.getNetworkCapabilities(network)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            }
            for (network in networks) {
                val capabilities = manager.getNetworkCapabilities(network) ?: continue
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) continue
                val address = manager.getLinkProperties(network)?.linkAddresses
                    ?.mapNotNull { it.address as? Inet4Address }
                    ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?: continue
                return "http://${address.hostAddress}:$PORT/progress"
            }
            return null
        }

        fun start(context: Context, token: String, videoUrl: String) {
            val intent = Intent(context, FireTvProgressService::class.java)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_VIDEO_URL, videoUrl)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .remove(KEY_TOKEN)
                .remove(KEY_VIDEO_URL)
                .apply()
            context.stopService(Intent(context, FireTvProgressService::class.java))
        }
    }
}
