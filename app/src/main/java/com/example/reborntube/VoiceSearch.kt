package com.example.reborntube

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

private enum class VoicePhase { Listening, Processing, Error }

/**
 * Búsqueda por voz a pantalla completa. Diseño propio de RebornTube: una esfera con los colores de la app
 * cuyos anillos laten con el volumen de tu voz, y barras de ecualizador debajo. Usa el reconocimiento de voz
 * del sistema; en cuanto termina de entenderte, lanza la búsqueda.
 */
@Composable
fun VoiceSearchDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)

    var phase by remember { mutableStateOf(VoicePhase.Listening) }
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var rawLevel by remember { mutableFloatStateOf(0f) }
    var attempt by remember { mutableIntStateOf(0) } // al subirlo se vuelve a escuchar

    DisposableEffect(attempt) {
        phase = VoicePhase.Listening
        text = ""
        message = null
        rawLevel = 0f

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            phase = VoicePhase.Error
            message = tr("Este móvil no tiene reconocimiento de voz disponible.")
            return@DisposableEffect onDispose { }
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onEndOfSpeech() {
                if (phase == VoicePhase.Listening) phase = VoicePhase.Processing
            }

            override fun onRmsChanged(rmsdB: Float) {
                rawLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { text = it }
            }

            override fun onResults(results: Bundle?) {
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (best.isEmpty()) {
                    phase = VoicePhase.Error
                    message = tr("No te he entendido. Prueba otra vez.")
                } else {
                    text = best
                    phase = VoicePhase.Processing
                    currentOnResult(best)
                }
            }

            override fun onError(error: Int) {
                phase = VoicePhase.Error
                message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        tr("No te he oído. Acerca el móvil y prueba otra vez.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        tr("Sin conexión para reconocer la voz.")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        tr("Falta el permiso del micrófono.")
                    else -> tr("No se pudo escuchar. Prueba otra vez.")
                }
            }
        })
        recognizer.startListening(recognizerIntent(context))
        onDispose { runCatching { recognizer.destroy() } }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth()) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = tr("Cerrar"))
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    when (phase) {
                        VoicePhase.Listening -> if (text.isEmpty()) tr("Habla ahora…") else tr("Te escucho…")
                        VoicePhase.Processing -> tr("Buscando…")
                        VoicePhase.Error -> tr("Ups")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))

                // Lo que vas diciendo, en grande
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val shown = if (phase == VoicePhase.Error) message.orEmpty() else text
                    Text(
                        shown,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        color = if (phase == VoicePhase.Error) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                }

                VoiceOrb(level = rawLevel, active = phase == VoicePhase.Listening)
                Spacer(Modifier.height(16.dp))
                Equalizer(level = rawLevel, active = phase == VoicePhase.Listening)
                Spacer(Modifier.height(24.dp))

                if (phase == VoicePhase.Error) {
                    Button(onClick = { attempt++ }) { Text(tr("Intentar de nuevo")) }
                } else {
                    Text(
                        tr("Prueba con el nombre de un vídeo, un canal o un tema"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun recognizerIntent(context: Context) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
}

/** Esfera con degradado y tres anillos que se expanden; con la voz se hace más grande y brillante. */
@Composable
private fun VoiceOrb(level: Float, active: Boolean) {
    val smooth by animateFloatAsState(if (active) level else 0f, tween(120), label = "level")
    val transition = rememberInfiniteTransition(label = "rings")
    val phase by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "phase"
    )
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val orb = 46.dp.toPx() * (1f + 0.35f * smooth)
            val maxRadius = size.minDimension / 2
            if (active) {
                for (i in 0 until 3) {
                    val p = (phase + i / 3f) % 1f
                    val radius = orb + (maxRadius - orb) * p
                    drawCircle(
                        color = primary.copy(alpha = (1f - p) * (0.15f + 0.35f * smooth)),
                        radius = radius, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx())
                    )
                }
            }
            drawCircle(
                brush = Brush.radialGradient(listOf(tertiary, primary), center = center, radius = orb),
                radius = orb, center = center
            )
        }
        Icon(
            Icons.Default.Mic, contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(36.dp)
        )
    }
}

/** Siete barras que suben y bajan con el volumen de la voz. */
@Composable
private fun Equalizer(level: Float, active: Boolean) {
    val smooth by animateFloatAsState(if (active) level else 0f, tween(100), label = "eq")
    val transition = rememberInfiniteTransition(label = "eqwave")
    val t by transition.animateFloat(
        0f, 6.2832f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "wave"
    )
    val color = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.width(160.dp).height(40.dp)) {
        val bars = 7
        val gap = 8.dp.toPx()
        val barWidth = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val wave = abs(sin(t + i * 0.8f))
            val h = (6.dp.toPx() + (size.height - 6.dp.toPx()) * smooth * (0.35f + 0.65f * wave))
                .coerceAtMost(size.height)
            drawRoundRect(
                color = color.copy(alpha = if (active) 0.9f else 0.3f),
                topLeft = Offset(i * (barWidth + gap), (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2)
            )
        }
    }
}
