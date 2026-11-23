package com.example.reborntube

import android.media.MediaCodecList
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream

private data class VideoDecoder(val mimeType: String, val capabilities: android.media.MediaCodecInfo.CodecCapabilities)

private val videoDecoders: List<VideoDecoder>? by lazy {
    runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filterNot { it.isEncoder }
            .flatMap { codec ->
                codec.supportedTypes.mapNotNull { mime ->
                    if (!mime.startsWith("video/")) return@mapNotNull null
                    runCatching { VideoDecoder(mime, codec.getCapabilitiesForType(mime)) }.getOrNull()
                }
            }
    }.getOrNull()
}

internal fun videoCodecMimeType(codec: String): String? = when {
    codec.startsWith("av01", ignoreCase = true) -> "video/av01"
    codec.startsWith("vp09", ignoreCase = true) || codec.startsWith("vp9", ignoreCase = true) ->
        "video/x-vnd.on2.vp9"
    codec.startsWith("avc1", ignoreCase = true) || codec.startsWith("avc3", ignoreCase = true) ->
        "video/avc"
    codec.startsWith("hev1", ignoreCase = true) || codec.startsWith("hvc1", ignoreCase = true) ->
        "video/hevc"
    else -> null
}

private fun decoderSupports(stream: VideoStream, mimeType: String): Boolean {
    val decoders = videoDecoders ?: return true
    return decoders.asSequence()
        .filter { it.mimeType.equals(mimeType, ignoreCase = true) }
        .any { decoder ->
            val videoCapabilities = decoder.capabilities.videoCapabilities
            videoCapabilities == null || stream.width <= 0 || stream.height <= 0 ||
                runCatching { videoCapabilities.isSizeSupported(stream.width, stream.height) }.getOrDefault(false)
        }
}

private fun codecPriority(stream: VideoStream): Int {
    val mimeType = videoCodecMimeType(stream.codec) ?: return 0
    if (!decoderSupports(stream, mimeType)) return -1
    return when (mimeType) {
        "video/av01" -> 3
        "video/x-vnd.on2.vp9" -> 2
        else -> 1
    }
}

fun VideoStream.label(): String {
    val resolution = when {
        height >= 2160 -> "4K (${height}p)"
        height >= 1440 -> "2K (${height}p)"
        else -> "${height}p"
    }
    return resolution + if (fps > 30) " $fps" else ""
}

fun AudioStream.shortLabel(): String = audioTrackName ?: audioLocale?.displayName ?: tr("Original")

fun AudioStream.label(): String {
    val type = when (audioTrackType) {
        AudioTrackType.DUBBED -> tr(" (doblada)")
        AudioTrackType.DESCRIPTIVE -> tr(" (descriptiva)")
        else -> ""
    }
    return "${shortLabel()}$type · ${averageBitrate}kbps"
}

/** Pistas MP4/WebM sin audio, incluida entrega DASH (YouTube suele publicar 2K/4K en VP9/WebM). */
fun videoOptionsOf(info: StreamInfo): List<VideoStream> = info.videoOnlyStreams
    .filter {
        (it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.deliveryMethod == DeliveryMethod.DASH) &&
            (it.format == MediaFormat.MPEG_4 || it.format == MediaFormat.WEBM)
    }
    .filter { stream ->
        videoCodecMimeType(stream.codec)?.let { decoderSupports(stream, it) } ?: true
    }
    .groupBy { it.height to it.fps }
    .mapNotNull { (_, list) ->
        list.maxWithOrNull(
            compareBy<VideoStream> { it.deliveryMethod == DeliveryMethod.DASH }
                .thenBy { codecPriority(it) }
                .thenBy { it.bitrate }
        )
    }
    .sortedWith(compareByDescending<VideoStream> { it.height }.thenByDescending { it.fps })

/** Una pista de audio por idioma/tipo (la original primero). */
fun audioOptionsOf(info: StreamInfo): List<AudioStream> = info.audioStreams
    .filter {
        (it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.deliveryMethod == DeliveryMethod.DASH) &&
            (it.format == MediaFormat.M4A || it.format == MediaFormat.WEBMA ||
                it.format == MediaFormat.WEBMA_OPUS || it.format == MediaFormat.MP3 ||
                it.format == MediaFormat.OPUS || it.format == MediaFormat.OGG)
    }
    .groupBy { it.audioTrackId ?: "default" }
    .map { (_, list) ->
        list.maxWithOrNull(
            compareBy<AudioStream> { it.deliveryMethod == DeliveryMethod.DASH }
                .thenBy { it.averageBitrate }
        )!!
    }
    .sortedBy { if (it.audioTrackType == AudioTrackType.ORIGINAL) 0 else 1 }

fun SubtitlesStream.label(): String =
    displayLanguageName + if (isAutoGenerated) tr(" (automático)") else ""

/** Subtítulos disponibles (VTT o TTML): primero los de verdad, luego los automáticos; antes el idioma del móvil. */
fun subtitleOptionsOf(info: StreamInfo): List<SubtitlesStream> {
    val device = java.util.Locale.getDefault().language
    return info.subtitles
        .filter { it.format == MediaFormat.VTT || it.format == MediaFormat.TTML }
        .groupBy { it.languageTag + it.isAutoGenerated }
        .map { (_, list) -> list.firstOrNull { it.format == MediaFormat.VTT } ?: list.first() }
        .sortedWith(
            compareBy<SubtitlesStream>({ it.isAutoGenerated }, { !it.languageTag.startsWith(device) }, { it.displayLanguageName })
        )
}

fun isLiveInfo(info: StreamInfo): Boolean =
    info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM

internal fun isRetryablePlaybackErrorCode(errorCode: Int): Boolean =
    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

/**
 * HLS solo para directos o cuando no hay vídeo+audio progresivos. Antes se usaba HLS siempre que
 * existía, y por eso el selector de calidad no hacía nada en vídeos de emisiones ya terminadas.
 */
fun usesHls(info: StreamInfo, video: VideoStream?, audio: AudioStream?): Boolean =
    !info.hlsUrl.isNullOrEmpty() && (isLiveInfo(info) || video == null || audio == null)

/** MediaItem con título, canal y miniatura: es lo que Android enseña en la notificación. */
private fun mediaItem(uri: String, info: StreamInfo, background: Boolean = false): MediaItem = MediaItem.Builder()
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(info.name)
            .setArtist(if (background) tr("En segundo plano") + " · " + info.uploaderName else info.uploaderName)
            .setArtworkUri(info.thumbnails.firstOrNull()?.url?.let { Uri.parse(it) })
            .build()
    )
    .build()

@OptIn(UnstableApi::class)
fun buildSource(
    info: StreamInfo,
    video: VideoStream?,
    audio: AudioStream?,
    factory: DataSource.Factory,
    audioOnly: Boolean = false
): MediaSource {
    if (usesHls(info, video, audio)) {
        return HlsMediaSource.Factory(factory).createMediaSource(mediaItem(info.hlsUrl, info, audioOnly))
    }

    val progressive = ProgressiveMediaSource.Factory(factory)

    // Modo música: solo se descarga el audio (ahorra datos y sigue sonando en segundo plano)
    if (audioOnly && audio != null) {
        return progressive.createMediaSource(mediaItem(audio.content, info, background = true))
    }

    // Algunos servicios de NewPipe (por ejemplo SoundCloud) publican solo audio.
    if (video == null && audio != null) {
        return progressive.createMediaSource(mediaItem(audio.content, info, background = true))
    }

    if (video != null && audio != null) {
        return MergingMediaSource(
            progressive.createMediaSource(mediaItem(video.content, info)),
            progressive.createMediaSource(MediaItem.fromUri(audio.content))
        )
    }

    if (info.service.serviceId == org.schabi.newpipe.extractor.ServiceList.YouTube.serviceId &&
        !isLiveInfo(info)
    ) {
        error("YouTube no proporcionó pistas de video y audio independientes para reproducción DASH.")
    }

    val muxed = info.videoStreams
        .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        .maxByOrNull { it.height }
        ?: error("No hay streams reproducibles para este vídeo")

    return progressive.createMediaSource(mediaItem(muxed.content, info))
}
