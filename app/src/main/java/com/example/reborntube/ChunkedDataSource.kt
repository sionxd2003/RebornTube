package com.example.reborntube

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Los vídeos de YouTube que se piden de golpe se descargan limitados a poca velocidad (por eso
 * iban lentos y a veces la bolita no paraba de girar al cambiar de pista de audio).
 * Aquí se piden en trozos de 10 MB con el parámetro "range", que es lo que hace NewPipe.
 * Lo que no sea un vídeo de googlevideo con "clen" pasa tal cual.
 */
@UnstableApi
class ChunkedYoutubeDataSource(private val upstream: DataSource) : DataSource {
    private var passthrough = false
    private var spec: DataSpec? = null
    private var position = 0L
    private var limit = 0L // fin exclusivo
    private var chunkRemaining = 0L
    private var chunkOpen = false
    private var requestNumber = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val clen = uri.getQueryParameter("clen")?.toLongOrNull()
        val chunkable = clen != null &&
            uri.host?.endsWith("googlevideo.com") == true &&
            uri.path?.contains("videoplayback") == true &&
            uri.getQueryParameter("range") == null

        if (!chunkable) {
            passthrough = true
            return upstream.open(dataSpec)
        }

        passthrough = false
        spec = dataSpec
        position = dataSpec.position
        val specEnd = if (dataSpec.length != C.LENGTH_UNSET.toLong()) position + dataSpec.length else Long.MAX_VALUE
        limit = minOf(specEnd, clen!!)
        if (position >= limit) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        chunkOpen = false
        chunkRemaining = 0
        openChunk()
        return limit - position
    }

    private fun openChunk() {
        val original = spec!!
        val end = minOf(position + CHUNK - 1, limit - 1)
        val chunkUri: Uri = original.uri.buildUpon()
            .appendQueryParameter("range", "$position-$end")
            .appendQueryParameter("rn", (++requestNumber).toString())
            .build()
        upstream.open(
            DataSpec.Builder()
                .setUri(chunkUri)
                .setHttpRequestHeaders(original.httpRequestHeaders)
                .setKey(original.key)
                .build()
        )
        chunkRemaining = end - position + 1
        chunkOpen = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (position >= limit) return C.RESULT_END_OF_INPUT
        if (!chunkOpen || chunkRemaining <= 0L) {
            upstream.close()
            chunkOpen = false
            openChunk()
        }
        val toRead = minOf(length.toLong(), chunkRemaining).toInt()
        val read = upstream.read(buffer, offset, toRead)
        if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        position += read
        chunkRemaining -= read
        return read
    }

    override fun getUri(): Uri? = if (passthrough) upstream.uri else spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
        chunkOpen = false
        spec = null
    }

    private companion object {
        const val CHUNK = 10L * 1024 * 1024
    }
}

@UnstableApi
class ChunkedYoutubeDataSourceFactory(private val http: DefaultHttpDataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = ChunkedYoutubeDataSource(http.createDataSource())
}
