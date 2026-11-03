package com.laserbunny.scribe

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * "Record" in the phone's pull-down quick settings: tap it, talk (the screen
 * can go off), tap it again or Stop in the notification. Nothing is typed
 * anywhere; the recording is written up the way Inkwell's Capture would and
 * filed on today's page in Inkwell.
 */
class RecordTile : TileService() {

    override fun onStartListening() {
        Scribe.init(this)
        show()
    }

    override fun onClick() {
        Scribe.init(this)
        if (Scribe.state is State.Recording) {
            Scribe.stop()
            show()
            return
        }
        // The microphone may only be started from a screen the person can see,
        // so a tiny screen of Scribe's own opens, starts it and closes again.
        val i = Intent(this, RecordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun show() {
        val t = qsTile ?: return
        val on = Scribe.state is State.Recording
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = if (on) "Stop" else "Record"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Scribe is listening" else "Into Inkwell"
        t.updateTile()
    }

    companion object {
        /** Asks the tile to redraw (recording started or stopped). */
        fun refresh(context: android.content.Context) {
            try { requestListeningState(context, android.content.ComponentName(context, RecordTile::class.java)) } catch (e: Exception) { }
        }
    }
}
