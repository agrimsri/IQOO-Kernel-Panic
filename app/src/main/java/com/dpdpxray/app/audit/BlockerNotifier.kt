package com.dpdpxray.app.audit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.dpdpxray.app.MainActivity
import com.dpdpxray.core.consent.BlockerKind

/**
 * Tells the person, while the audited app is on screen, that a sign-in or verification step is waiting for them.
 * Without it they would never know why the check paused. Nothing is sent anywhere; it is a local notification.
 */
internal object BlockerNotifier {
    private const val CHANNEL = "audit_needs_you"
    private const val ID = 7301

    fun post(context: Context, app: String, kind: BlockerKind, fields: List<String> = emptyList()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Check needs you", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown while a running check is waiting for you to sign in or pass a verification step."
            },
        )
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = BlockerCopy.notificationText(app, kind, fields)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(BlockerCopy.notificationTitle(app))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("$text\n${BlockerCopy.notificationTextHi(app, kind, fields)}"))
            .setContentIntent(tap)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { nm.notify(ID, n) }
    }

    fun clear(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }
}
