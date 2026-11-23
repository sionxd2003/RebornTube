package com.example.reborntube

import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.URL

@UnstableApi
class AudioDownloadWorker(
    appContext: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Falta la URL del audio"))
        val fileName = inputData.getString(KEY_FILE_NAME)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Falta el nombre del archivo"))
        val directory = File(
            applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: applicationContext.filesDir,
            ""
        )
        val destination = File(directory, fileName)
        val temporary = File(directory, "$fileName.part")
        val artworkUrl = inputData.getString(KEY_ARTWORK_URL)
        val artworkPath = inputData.getString(KEY_ARTWORK_PATH)

        return try {
            directory.mkdirs()
            val source = ChunkedYoutubeDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setUserAgent(DownloaderImpl.USER_AGENT)
                    .setConnectTimeoutMs(20_000)
                    .setReadTimeoutMs(30_000)
                    .setAllowCrossProtocolRedirects(true)
            ).createDataSource()
            val expectedBytes = Uri.parse(url).getQueryParameter("clen")?.toLongOrNull()
            var receivedBytes = 0L
            try {
                val openedLength = source.open(DataSpec.Builder().setUri(Uri.parse(url)).build())
                val totalBytes = expectedBytes ?: openedLength.takeIf { it > 0L }
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer, 0, buffer.size)
                        if (read == C.RESULT_END_OF_INPUT) break
                        if (read < 0) error("El servidor interrumpió la descarga")
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        receivedBytes += read
                        if (totalBytes != null && totalBytes > 0L) {
                            setProgress(workDataOf(KEY_PERCENT to (receivedBytes * 100 / totalBytes).toInt().coerceIn(0, 99)))
                        }
                    }
                }
            } finally {
                source.close()
            }
            if (receivedBytes == 0L) error("El servidor entregó un archivo vacío")
            if (destination.exists() && !destination.delete()) error("No se pudo reemplazar el archivo anterior")
            if (!temporary.renameTo(destination)) error("No se pudo guardar el archivo descargado")

            if (!artworkUrl.isNullOrBlank() && !artworkPath.isNullOrBlank()) {
                runCatching { downloadArtwork(artworkUrl, File(artworkPath)) }
                    .onFailure { Log.w(TAG, "No se pudo guardar la portada de la descarga", it) }
            }
            Result.success(workDataOf(KEY_PERCENT to 100))
        } catch (cancelled: CancellationException) {
            temporary.delete()
            throw cancelled
        } catch (exception: Exception) {
            temporary.delete()
            Log.e(TAG, "Falló la descarga de audio", exception)
            Result.failure(workDataOf(KEY_ERROR to (exception.message ?: "Error de red o almacenamiento")))
        }
    }

    private fun downloadArtwork(url: String, destination: File) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        val connection = URL(url).openConnection().apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", DownloaderImpl.USER_AGENT)
        }
        connection.getInputStream().use { input ->
            temporary.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        if (destination.exists() && !destination.delete()) error("No se pudo reemplazar la portada")
        if (!temporary.renameTo(destination)) error("No se pudo guardar la portada")
    }

    companion object {
        const val KEY_URL = "audio_url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_ARTWORK_URL = "artwork_url"
        const val KEY_ARTWORK_PATH = "artwork_path"
        const val KEY_PERCENT = "percent"
        const val KEY_ERROR = "error"
        private const val TAG = "AudioDownloadWorker"
    }
}
