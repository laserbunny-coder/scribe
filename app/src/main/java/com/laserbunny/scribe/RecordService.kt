package com.laserbunny.scribe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps Scribe listening while the screen is off or another app is on top,
 * and until the recording has been sent off. Its notification has a Stop
 * button, so a long ramble can be ended from the lock screen too.
 */
class RecordService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Scribe.init(this)
        when (intent?.action) {
            STOP -> Scribe.stop()
            PAUSE -> Scribe.pause()
        }
        val s = Scribe.state
        if (s !is State.Recording && s !is State.Working) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(ID, notification(this))
            }
        } catch (e: Exception) {
            // Recording still works while the keyboard is open; only screen-off listening needs this.
            Log.w("Scribe", "foreground", e)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 7
        private const val CHANNEL = "listening"
        private const val STOP = "stop"
        private const val PAUSE = "pause"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, RecordService::class.java))
            } catch (e: Exception) {
                Log.w("Scribe", "service", e)
            }
        }

        /** Refreshes the notification, or takes it away once there's nothing to keep going. */
        fun update(context: Context) {
            val s = Scribe.state
            val nm = context.getSystemService(NotificationManager::class.java)!!
            if (s is State.Recording || s is State.Working) {
                try { nm.notify(ID, notification(context)) } catch (e: SecurityException) { }
            } else {
                // The service sees the new state and stops itself.
                try { context.startService(Intent(context, RecordService::class.java)) } catch (e: Exception) { nm.cancel(ID) }
            }
        }

        private fun notification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)!!
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while Scribe is listening, with a Stop button"
                    setShowBadge(false)
                })
            }
            val s = Scribe.state
            val action = { what: String, label: String ->
                val pi = PendingIntent.getService(
                    context, what.hashCode(),
                    Intent(context, RecordService::class.java).setAction(what),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                Notification.Action.Builder(null, label, pi).build()
            }
            val b = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(PendingIntent.getActivity(
                    context, 1, Intent(context, SetupActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ))
            if (s is State.Recording) {
                b.setContentTitle(if (Scribe.paused) "Scribe is paused" else "Scribe is listening")
                    .setContentText("Tap Stop when you're done. It keeps going with the screen off.")
                    .setUsesChronometer(!Scribe.paused)
                    .setWhen(System.currentTimeMillis() - Scribe.elapsedMs())
                    .addAction(action(STOP, "Stop"))
                    .addAction(action(PAUSE, if (Scribe.paused) "Go on" else "Pause"))
            } else {
                b.setContentTitle("Scribe").setContentText((s as? State.Working)?.label ?: "Working…")
            }
            return b.build()
        }
    }
}
