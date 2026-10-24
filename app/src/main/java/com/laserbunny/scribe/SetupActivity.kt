package com.laserbunny.scribe

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Scribe's own screen: the few setup steps (connect, microphone, turn the
 * keyboard on), a box to try it in, and your modes to read or type changes into.
 */
class SetupActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private lateinit var page: LinearLayout
    private val changed: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scribe.init(this)
        page = column(this).apply { setPadding(dp(20), dp(28), dp(20), dp(40)) }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(C.PAGE)
            isFillViewport = true
            addView(page)
        })
        connectFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        connectFrom(intent)
    }

    override fun onResume() {
        super.onResume()
        Scribe.listen(changed)
        Scribe.refreshModes()
        render()
    }

    override fun onPause() {
        Scribe.unlisten(changed)
        super.onPause()
    }

    /** Inkwell sends the connection back as scribe://connect?token=…&url=… */
    private fun connectFrom(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "scribe" || uri.host != "connect") return
        val token = uri.getQueryParameter("token") ?: ""
        val url = uri.getQueryParameter("url") ?: ""
        if (!Regex("^[0-9a-f]{48}$").matches(token) || !url.startsWith("https://")) {
            toast("That connection link didn’t look right. Try Connect again.")
            return
        }
        Scribe.store.token = token
        Scribe.store.url = url
        toast("Scribe is connected")
        Scribe.refreshModes()
        render()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun micAllowed() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun keyboardOn(): Boolean {
        val imm = getSystemService(InputMethodManager::class.java)!!
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun keyboardPicked(): Boolean {
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        return current.startsWith("$packageName/")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The keyboard picker closes without telling us; look again.
        if (hasFocus) render()
    }

    private fun render() {
        page.removeAllViews()
        page.addView(label("Scribe", 28f, C.TEXT, true))
        page.addView(label("Talk, and it types it in the way you want.", 15f, C.DIM).margins(this, t = 4, b = 20))

        val store = Scribe.store
        step(1, "Connect to your account", store.connected,
            if (store.connected) "Connected through Inkwell." else "Opens Inkwell, where you’re already signed in, and comes back here.",
            if (store.connected) "Connect again" else "Connect",
        ) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://laserbunny-coder.github.io/inkwell/?connect=scribe")))
        }
        step(2, "Allow the microphone", micAllowed(),
            "Scribe only listens after you tap the record button.", "Allow",
        ) {
            val asks = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) asks += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(asks.toTypedArray(), 1)
        }
        step(3, "Turn on the Scribe keyboard", keyboardOn(),
            "In the list that opens, switch on Scribe. Android warns that keyboards can see what you type; Scribe only sends your recordings.",
            "Open keyboard settings",
        ) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        step(4, "Try it", keyboardPicked(),
            "Tap the box, switch to Scribe, and say something. Your usual keyboard is one tap away (bottom left of Scribe).",
            "Switch keyboard",
        ) { getSystemService(InputMethodManager::class.java)!!.showInputMethodPicker() }
        page.addView(EditText(this).apply {
            hint = "Try Scribe here"
            setHintTextColor(C.DIM)
            setTextColor(C.TEXT)
            background = shape(C.SURFACE, 14, C.BORDER, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        page.addView(label("Your modes", 20f, C.TEXT, true).margins(this, t = 32, b = 4))
        page.addView(label("Tap one to read or change it. On the keyboard: hold a mode to change it by talking, or + to make one.", 13f, C.DIM).margins(this, b = 12))
        for (m in store.modes) {
            val card = column(this)
            card.background = shape(C.SURFACE, 14, C.BORDER, 1)
            card.setPadding(dp(14), dp(12), dp(14), dp(12))
            card.addView(label("${m.name}   ·  ${Store.modelName(m.model)}", 16f, C.TEXT, true))
            card.addView(label(m.prompt.ifEmpty { "As you said it." }, 13f, C.DIM).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }.margins(this, t = 4))
            card.setOnClickListener { editMode(m) }
            page.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        page.addView(pill("New mode").apply { setOnClickListener { editMode(Mode("", "", "", emptyList())) } }.margins(this, t = 4))

        Scribe.store.last?.let { last ->
            page.addView(label("Last dictation", 20f, C.TEXT, true).margins(this, t = 32, b = 8))
            page.addView(label(last.text, 14f, C.TEXT).apply { setTextIsSelectable(true) })
            page.addView(pill("Copy").apply {
                setOnClickListener {
                    getSystemService(android.content.ClipboardManager::class.java)!!
                        .setPrimaryClip(android.content.ClipData.newPlainText("Scribe", last.text))
                    toast("Copied")
                }
            }.margins(this, t = 8))
        }
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        page.addView(label("Scribe $version", 12f, C.DIM).margins(this, t = 32))
    }

    private fun step(n: Int, title: String, done: Boolean, help: String, action: String, act: () -> Unit) {
        val card = column(this)
        card.background = shape(C.SURFACE, 16, if (done) 0xFF2E4A35.toInt() else C.BORDER, 1)
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        val top = row(this)
        top.addView(label(if (done) "✓" else "$n", 15f, if (done) C.GOOD else C.ACCENT, true).apply {
            gravity = Gravity.CENTER
            background = shape(if (done) 0xFF1F2A1F.toInt() else 0xFF1E2636.toInt(), 14)
        }, wrap(28, 28, context = this))
        top.addView(label(title, 16f, C.TEXT, true).margins(this, l = 12), wrap(weight = 1f))
        card.addView(top)
        card.addView(label(help, 13f, C.DIM).margins(this, t = 8))
        card.addView(pill(action, if (done) "outline" else "filled").apply { setOnClickListener { act() } }.margins(this, t = 12))
        page.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
    }

    /** Read or type changes into a mode. */
    private fun editMode(m: Mode) {
        val box = column(this).apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val name = EditText(this).apply { setText(m.name); hint = "Name, like Work Slack"; setSingleLine() }
        val prompt = EditText(this).apply {
            setText(m.prompt)
            hint = "How it writes, in plain words"
            minLines = 4
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val apps = EditText(this).apply {
            setText(m.apps.joinToString(", "))
            hint = "Apps it picks itself in (app ids)"
            setSingleLine()
        }
        val models = android.widget.RadioGroup(this)
        val current = m.model.ifEmpty { if (m.id.isEmpty()) "haiku" else "opus" }
        Store.MODELS.forEachIndexed { i, (id, text) ->
            models.addView(android.widget.RadioButton(this).apply { this.id = i + 1; this.text = text; isChecked = id == current })
        }
        box.addView(name); box.addView(prompt); box.addView(models); box.addView(apps)
        val d = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (m.id.isEmpty()) "New mode" else m.name)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val changedMode = Mode(m.id, name.text.toString().trim(), prompt.text.toString().trim(),
                    apps.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    Store.MODELS.getOrNull(models.checkedRadioButtonId - 1)?.first ?: current)
                if (changedMode.name.isEmpty()) { toast("A mode needs a name"); return@setPositiveButton }
                save { Api(Scribe.store).saveMode(changedMode) }
            }
            .setNegativeButton("Cancel", null)
        if (m.id.isNotEmpty()) d.setNeutralButton("Remove") { _, _ -> save { Api(Scribe.store).deleteMode(m.id) } }
        d.show()
    }

    private fun save(job: () -> Unit) {
        work.execute {
            try {
                job()
                Scribe.store.modes = Api(Scribe.store).modes()
                main.post { render() }
            } catch (e: Exception) {
                main.post { toast(e.message ?: "Couldn’t save that. Try again.") }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!micAllowed() && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Asked too often: Android only lets it be turned on in Settings now.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        render()
    }
}
