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
import com.dpdpxray.app.capture.XrayVpnService

/**
 * The background watch's notifications: the capture's ongoing one, with the time left and a Stop action, and one when
 * the watch has finished (TraceVeil does not jump in front of whatever the person is doing). Local only.
 */
internal object WatchNotifier {
    private const val DONE_CHANNEL = "watch_done"
    private const val DONE_ID = 7302

    /** Updates the ongoing notification; called when the minutes left change. */
    fun progress(context: Context, app: String, remainingMs: Long, hindi: Boolean) {
        val title = WatchCopy.notificationTitle(app, remainingMs)
        val text = WatchCopy.notificationText(app)
        XrayVpnService.showWatchNotice(
            context,
            title = if (hindi) title.hi else title.en,
            text = if (hindi) text.hi else text.en,
            stopLabel = if (hindi) WatchCopy.stop.hi else WatchCopy.stop.en,
        )
    }

    fun finished(context: Context, app: String, hindi: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "Background watch finished", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, DONE_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = WatchCopy.finishedTitle(app)
        val text = WatchCopy.finishedText
        val n = Notification.Builder(context, DONE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(if (hindi) title.hi else title.en)
            .setContentText(if (hindi) text.hi else text.en)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(DONE_ID, n) }
    }
}
