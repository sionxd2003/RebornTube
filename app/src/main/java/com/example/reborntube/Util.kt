package com.example.reborntube

import androidx.core.text.HtmlCompat
import android.net.Uri

/** Las descripciones y comentarios de YouTube llegan en HTML (<br>, <a href>...). */
fun String.htmlToText(): String =
    HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()

fun formatCount(n: Long): String = when {
    n < 0 -> ""
    n >= 1_000_000 -> "%.1f M".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1f K".format(n / 1_000.0)
    else -> n.toString()
}

/** Busca la Activity dentro de los ContextWrapper de Compose. */
fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

fun shareVideo(context: android.content.Context, info: org.schabi.newpipe.extractor.stream.StreamInfo) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, info.name)
        putExtra(android.content.Intent.EXTRA_TEXT, "${info.name}\n${info.url}")
    }
    val chooser = android.content.Intent.createChooser(send, tr("Compartir vídeo"))
    if (context !is android.app.Activity) chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

fun shareVideoAt(
    context: android.content.Context,
    info: org.schabi.newpipe.extractor.stream.StreamInfo,
    positionMs: Long
) {
    val seconds = (positionMs / 1_000L).coerceAtLeast(0L)
    val url = Uri.parse(info.url).buildUpon()
        .appendQueryParameter("t", "${seconds}s")
        .build()
        .toString()
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, info.name)
        putExtra(android.content.Intent.EXTRA_TEXT, "${info.name}\n$url")
    }
    val chooser = android.content.Intent.createChooser(send, tr("Compartir vídeo"))
    if (context !is android.app.Activity) chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

/** Ventana flotante (Picture-in-Picture), como hace YouTube al salir de la app. */
fun android.app.Activity.enterPip() {
    if (android.os.Build.VERSION.SDK_INT >= 26) {
        enterPictureInPictureMode(
            android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9))
                .build()
        )
    }
}

fun formatClock(ms: Long): String = formatDuration(ms / 1000)

/**
 * Conectar a la TV: abre la pantalla del sistema para transmitir la pantalla (Transmitir / Smart View /
 * Duplicar pantalla, según el móvil). Así lo que se ve en la app sale también en la tele.
 */
fun openCastSettings(context: android.content.Context) {
    val options = listOf(
        android.content.Intent("android.settings.CAST_SETTINGS"),
        android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
    )
    for (intent in options) {
        if (context !is android.app.Activity) intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    android.widget.Toast.makeText(
        context,
        tr("Abre \"Transmitir\" o \"Smart View\" desde los ajustes rápidos de tu móvil"),
        android.widget.Toast.LENGTH_LONG
    ).show()
}
