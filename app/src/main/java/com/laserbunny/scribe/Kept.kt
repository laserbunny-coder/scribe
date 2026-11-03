package com.laserbunny.scribe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Says how a Record-tile recording went, since there's no keyboard on screen to show it. */
object Kept {
    private const val CHANNEL = "kept"
    private const val ID = 8

    fun done(context: Context, title: String, mode: String) {
        notify(context, "Kept in Inkwell", listOf(title, mode).filter { it.isNotBlank() }.joinToString("  ·  ").ifEmpty { "On today's page" }, null, timeout = 8_000)
    }

    fun failed(context: Context, message: String, canRetry: Boolean) {
        val retry = if (canRetry) PendingIntent.getActivity(
            context, 3, Intent(context, RecordActivity::class.java).setAction(RecordActivity.RETRY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ) else null
        notify(context, if (canRetry) "Scribe kept the recording, but Inkwell doesn't have it yet" else "Scribe couldn't keep that one", if (canRetry) "$message Tap to try again." else message, retry)
    }

    private fun notify(context: Context, title: String, text: String, tap: PendingIntent?, timeout: Long = 0) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Kept in Inkwell", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Says when a recording from the Record tile is in Inkwell, or needs another try"
                setShowBadge(false)
            })
        }
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        if (tap != null) b.setContentIntent(tap)
        if (timeout > 0) b.setTimeoutAfter(timeout)
        try { nm.notify(ID, b.build()) } catch (e: SecurityException) { }
    }
}
