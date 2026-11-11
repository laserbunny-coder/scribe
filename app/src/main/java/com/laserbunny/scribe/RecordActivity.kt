package com.laserbunny.scribe

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import org.json.JSONObject

/**
 * The Record tile's see-through screen: starts a recording that goes only to
 * Inkwell (or tries a kept one again, from the notification), then closes.
 * The floating button opens it too, to start a recording that types into the
 * box under SwiftKey: Android only lets the microphone start from a screen.
 */
class RecordActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scribe.init(this)
        when {
            intent?.action == RETRY -> Scribe.process()
            !Scribe.store.connected -> { Toast.makeText(this, "Connect Scribe first", Toast.LENGTH_LONG).show(); Scribe.openApp(this) }
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                Toast.makeText(this, "Allow the microphone in Scribe first", Toast.LENGTH_LONG).show(); Scribe.openApp(this)
            }
            Scribe.state is State.Recording -> Scribe.stop()
            Scribe.state is State.Working -> Toast.makeText(this, "Scribe is still on the last one", Toast.LENGTH_SHORT).show()
            intent?.action == FLOAT -> {
                val p = runCatching { Purpose.from(JSONObject(intent.getStringExtra(PURPOSE) ?: "{}")) }.getOrNull()
                if (p == null || !Scribe.start(p)) Toast.makeText(this, "Scribe couldn't start listening", Toast.LENGTH_SHORT).show()
            }
            Scribe.start(Purpose.Keep) -> Toast.makeText(this, "Scribe is listening. Tap Record again, or Stop in the notification.", Toast.LENGTH_SHORT).show()
        }
        RecordTile.refresh(this)
        finish()
        if (intent?.action == FLOAT) overridePendingTransition(0, 0)
    }

    companion object {
        const val RETRY = "com.laserbunny.scribe.RETRY"
        const val FLOAT = "com.laserbunny.scribe.FLOAT"
        const val PURPOSE = "purpose"
    }
}
