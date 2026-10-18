package com.dfdx047.odinhub.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dfdx047.odinhub.R
import com.dfdx047.odinhub.main.MainActivity

/** Notificação "há uma versão nova do Odin Hub": tocar abre a app, onde aparece o pop-up. */
object UpdateNotifier {
    private const val CHANNEL = "odinhub_updates"
    private const val ID = 4711

    fun show(context: Context, release: AppRelease, isEn: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, if (isEn) "App updates" else "Atualizações da app", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_app_settings)
            .setContentTitle(if (isEn) "Odin Hub ${release.tag} is available" else "Odin Hub ${release.tag} disponível")
            .setContentText(if (isEn) "Tap to update" else "Toque para atualizar")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ID, notification) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}
