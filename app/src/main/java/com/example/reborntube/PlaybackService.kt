package com.example.reborntube

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Publica la sesión multimedia: Android muestra la notificación con título, canal,
 * miniatura y controles (también en la pantalla de bloqueo) y permite seguir escuchando
 * con la app en segundo plano.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val activityIntent = Intent().setComponent(ComponentName(this, MainActivity::class.java))
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val activityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, PlayerHolder.get(this))
            .setSessionActivity(activityPendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release() // el player es compartido: no lo liberamos aquí
        session = null
        super.onDestroy()
    }
}
