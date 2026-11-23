package com.example.reborntube

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/** Un único ExoPlayer compartido entre la pantalla y el servicio de notificación. */
object PlayerHolder {
    private var player: ExoPlayer? = null

    @OptIn(UnstableApi::class)
    fun get(context: Context): ExoPlayer = player ?: ExoPlayer.Builder(context.applicationContext)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(50_000, 50_000, 2_500, 5_000)
                .build()
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
        .also { player = it }
}
