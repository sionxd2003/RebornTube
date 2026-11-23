package com.example.reborntube

import android.content.Context
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.File
import java.util.concurrent.TimeUnit

/** Implementación del Downloader que NewPipeExtractor necesita para hacer peticiones HTTP. */
class DownloaderImpl private constructor(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val url = request.url()
        val data = request.dataToSend()

        val body = when {
            data != null -> data.toRequestBody()
            method == "POST" -> ByteArray(0).toRequestBody()
            else -> null
        }

        val builder = okhttp3.Request.Builder()
            .method(method, body)
            .url(url)
            .addHeader("User-Agent", USER_AGENT)

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString()
            )
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

        @Volatile
        private var downloader: DownloaderImpl? = null

        fun initialize(context: Context): DownloaderImpl {
            downloader?.let { return it }
            return synchronized(this) {
                downloader ?: run {
                    val appContext = context.applicationContext
                    val cache = Cache(
                        File(appContext.cacheDir, "newpipe_http_cache"),
                        32L * 1024L * 1024L
                    )
                    val client = OkHttpClient.Builder()
                        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                        .cache(cache)
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .writeTimeout(15, TimeUnit.SECONDS)
                        .callTimeout(15, TimeUnit.SECONDS)
                        .build()
                    DownloaderImpl(client).also { downloader = it }
                }
            }
        }

        val instance: DownloaderImpl
            get() = checkNotNull(downloader) { "DownloaderImpl must be initialized by App first." }
    }
}
