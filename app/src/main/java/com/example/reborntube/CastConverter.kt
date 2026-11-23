package com.example.reborntube

import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem

/**
 * Igual que el conversor normal, pero los directos se envían a la TV como emisión en vivo.
 * Si se envían como vídeo normal, la TV muestra solo el título y una barra, sin imagen.
 */
@UnstableApi
class LiveAwareConverter : MediaItemConverter {
    private val default = DefaultMediaItemConverter()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val item = default.toMediaQueueItem(mediaItem)
        val live = mediaItem.mediaMetadata.extras?.getBoolean("live") == true
        val info = item.media
        if (!live || info == null) return item
        val builder = MediaInfo.Builder(info.contentId)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(info.contentType ?: "")
            .setMetadata(info.metadata)
            .setCustomData(info.customData)
        // Si el directo usa segmentos fMP4, se le dice a la TV (si no, a algunas se les queda la pantalla en negro)
        if (mediaItem.mediaMetadata.extras?.getBoolean("hlsFmp4") == true) {
            for (name in listOf("setHlsSegmentFormat", "setHlsVideoSegmentFormat")) {
                runCatching { builder.javaClass.getMethod(name, String::class.java).invoke(builder, "fmp4") }
            }
        }
        return MediaQueueItem.Builder(builder.build()).setAutoplay(true).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem = default.toMediaItem(mediaQueueItem)
}
