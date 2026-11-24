package com.example.reborntube

import android.text.format.DateUtils
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** Datos mínimos de un vídeo para listas (feed, biblioteca...). */
data class VideoCard(
    val url: String,
    val title: String,
    val channel: String,
    val channelUrl: String?,
    val thumb: String?,
    val duration: Long,
    val isLive: Boolean = false,
    val views: Long = -1,
    val uploaded: String? = null, // "hace 2 años"
    val avatar: String? = null // foto del canal
)

data class ChannelCard(
    val url: String,
    val name: String,
    val thumb: String?,
    val subscribers: Long,
    val lastVideoId: String? = null
)

/** Grupo local para organizar suscripciones por tema. */
data class ChannelGroup(val id: String, val name: String, val channelUrls: List<String>)

enum class Tab { Home, Shorts, Library, Settings, Subscriptions, Live, News, Trending }

/** Lista de shorts abierta en el visor vertical y posición inicial. */
data class ShortsSession(val items: List<VideoCard>, val index: Int, val canLoadMore: Boolean = false)

enum class LibList { History, Liked, Disliked, Later, Queue, Playlists, Downloads, Music }

/** Lista de reproducción creada por la persona (se guarda en el móvil). */
data class Playlist(val id: String, val name: String, val items: List<VideoCard>)

/** Descarga hecha con el gestor de descargas de Android (archivo en la carpeta de la app). */
data class DownloadRecord(
    val id: String,
    val title: String,
    val channel: String,
    val thumb: String?,
    val fileName: String,
    val dmId: Long,
    val audioOnly: Boolean,
    val duration: Long = 0L,
    val artworkPath: String? = null,
    val uploaded: String? = null,
    val workId: String? = null
)

/** Identificador del canal (lo último de la URL) para comparar sin depender del formato. */
fun channelKey(url: String) = url.trimEnd('/').substringAfterLast('/')

private fun StreamType?.isLive() =
    this == StreamType.LIVE_STREAM || this == StreamType.AUDIO_LIVE_STREAM

/**
 * Fecha para guardar en la tarjeta. Si YouTube da la fecha exacta se guarda en formato ISO.
 * Si solo da una aproximación ("hace 3 semanas") NO se inventa una fecha: se guarda el texto de YouTube,
 * y la fecha real se pide aparte cuando el vídeo se ve en pantalla.
 */
fun uploadText(textual: String?, date: DateWrapper?): String? {
    textual?.takeIf { it.isNotBlank() }?.let { return it }
    if (date != null && !date.isApproximation) {
        date.offsetDateTime()?.let { return it.toString() }
    }
    return null
}

fun parseIsoMillis(text: String?): Long? {
    if (text.isNullOrBlank() || !text.contains('T')) return null
    return runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}

/** Fecha real de subida con el día, el mes y el año, en el idioma del móvil ("19 oct 2026"). */
fun formatExactDate(millis: Long): String = java.time.Instant.ofEpochMilli(millis)
    .atZone(java.time.ZoneId.systemDefault())
    .format(
        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
            .withLocale(java.util.Locale.getDefault())
    )

/** Lo subido hace menos de un día se dice con horas ("hace 3 h"); lo demás con la fecha completa. */
fun uploadLabel(millis: Long): String {
    val now = System.currentTimeMillis()
    val age = (now - millis).coerceAtLeast(0L)
    if (age < DateUtils.DAY_IN_MILLIS) {
        return DateUtils.getRelativeTimeSpanString(
            millis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }
    val zone = java.time.ZoneId.systemDefault()
    val uploaded = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val years = java.time.temporal.ChronoUnit.YEARS.between(uploaded, today)
    val months = java.time.temporal.ChronoUnit.MONTHS.between(uploaded, today)
    val weeks = java.time.temporal.ChronoUnit.WEEKS.between(uploaded, today)
    val days = java.time.temporal.ChronoUnit.DAYS.between(uploaded, today)
    return when {
        years >= 1 -> relativeAge(years, "año", "años")
        months >= 1 -> relativeAge(months, "mes", "meses")
        weeks >= 1 -> relativeAge(weeks, "semana", "semanas")
        else -> relativeAge(days.coerceAtLeast(1), "día", "días")
    }
}

private fun relativeAge(value: Long, singular: String, plural: String): String =
    trf("hace %s %s", value, tr(if (value == 1L) singular else plural))

/** Texto que se ve en pantalla: la fecha exacta si el valor guardado es ISO; si no, el texto de YouTube tal cual. */
fun displayUploaded(text: String): String = parseIsoMillis(text)?.let { uploadLabel(it) } ?: text

fun StreamInfoItem.toCard() = VideoCard(
    url, name ?: "", uploaderName ?: "", uploaderUrl,
    thumbnails.maxByOrNull { image -> image.width.toLong() * image.height.toLong() }?.url ?: thumbnails.firstOrNull()?.url,
    duration, streamType.isLive(), viewCount, uploadText(textualUploadDate, uploadDate),
    uploaderAvatars.firstOrNull()?.url
)

fun StreamInfo.toCard() = VideoCard(
    url, name ?: "", uploaderName ?: "", uploaderUrl,
    thumbnails.maxByOrNull { image -> image.width.toLong() * image.height.toLong() }?.url ?: thumbnails.firstOrNull()?.url,
    duration, streamType.isLive(), viewCount, uploadText(textualUploadDate, uploadDate),
    uploaderAvatars.firstOrNull()?.url
)

fun ChannelInfoItem.toCard() = ChannelCard(
    url, name ?: "", thumbnails.firstOrNull()?.url, subscriberCount
)

/** Lista de YouTube (por ejemplo, las listas de un canal). */
data class RemotePlaylist(val url: String, val name: String, val thumb: String?, val count: Long)
