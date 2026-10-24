package com.laserbunny.scribe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * The Scribe keyboard: mode chips, one big record button, and the few keys
 * you need around a dictation (back to your usual keyboard, delete, enter).
 * The words go in through the text box's own connection, never the clipboard,
 * and a result that can't go in right away waits here with a button.
 */
class ScribeKeyboard : InputMethodService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var note: TextView
    private lateinit var body: FrameLayout
    private var timer: TextView? = null
    private var level: LevelView? = null

    private var app = ""
    private var field = ""
    private var lastModesFetch = 0L

    private val changed: () -> Unit = { render(); typeIn() }

    override fun onCreate() {
        super.onCreate()
        Scribe.init(this)
        Scribe.listen(changed)
    }

    override fun onDestroy() {
        Scribe.unlisten(changed)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        root = column(this).apply {
            setBackgroundColor(C.BG)
            setPadding(dp(12), dp(12), dp(12), dp(14))
        }
        chips = row(this)
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        note = label("", 12f, C.DIM).apply { setPadding(dp(4), dp(8), dp(4), dp(4)) }
        root.addView(note)
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(196)))
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        app = info.packageName ?: ""
        field = "$app:${info.fieldId}"
        // Fresh modes now and then, in case they were changed on another device.
        if (SystemClock.elapsedRealtime() - lastModesFetch > 5 * 60_000) {
            lastModesFetch = SystemClock.elapsedRealtime()
            Scribe.refreshModes()
        }
        render()
        typeIn()
    }

    // ---- Typing in ----

    private fun typeIn() {
        val ic = currentInputConnection ?: return
        if (!isInputViewShown) return
        val i = Scribe.takeInsert(field) ?: return
        ic.beginBatchEdit()
        val replace = i.replace
        if (!replace.isNullOrEmpty()) {
            val before = ic.getTextBeforeCursor(replace.length, 0)?.toString()
            if (before == replace) ic.deleteSurroundingText(replace.length, 0)
        }
        if (i.text.isNotEmpty()) {
            // A space between this and words already there.
            val prev = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
            val gap = if (prev.isNotEmpty() && !prev[0].isWhitespace() && i.replace == null) " " else ""
            ic.commitText(gap + i.text, 1)
        }
        ic.endBatchEdit()
    }

    private fun copy(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)!!
        cm.setPrimaryClip(ClipData.newPlainText("Scribe", text))
    }

    // ---- Drawing ----

    private fun render() {
        if (!::root.isInitialized) return
        timer = null
        level = null
        val s = Scribe.state
        renderChips(s)
        body.removeAllViews()
        val view = when (s) {
            is State.Idle -> idle()
            is State.Recording -> recording(s)
            is State.Working -> working(s)
            is State.Done -> done(s)
            is State.Failed -> failed(s)
            is State.Draft -> draft(s)
        }
        body.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (s is State.Recording) tick()
    }

    private fun renderChips(s: State) {
        chips.removeAllViews()
        val drafting = s is State.Draft || (s is State.Recording && s.purpose is Purpose.MakeMode)
        (chips.parent as View).visibility = if (drafting) View.GONE else View.VISIBLE
        if (drafting) {
            note.text = if (s is State.Recording) "Say what the mode should do, and which apps it’s for." else ""
            note.visibility = if (note.text.isEmpty()) View.GONE else View.VISIBLE
            return
        }
        val modes = Scribe.store.modes
        val picked = when (s) {
            is State.Recording -> (s.purpose as Purpose.Dictate).mode
            is State.Done -> s.result.mode
            else -> Scribe.store.modeFor(app).id
        }
        for (m in modes) {
            val c = chip(m.name, m.id == picked)
            c.setOnClickListener { pickMode(m) }
            c.setOnLongClickListener { Scribe.openMode(m); true }
            chips.addView(c, wrap(heightDp = 40, context = this).apply { marginEnd = dp(8) })
        }
        val plus = iconButton(R.drawable.ic_plus, 40, 0, C.DIM, "New mode", 0xFF4A4A52.toInt())
        plus.background = shape(0, 20, 0xFF4A4A52.toInt(), 1, dashed = true)
        plus.setOnClickListener { record(Purpose.MakeMode(null)) }
        chips.addView(plus)

        note.text = when (s) {
            is State.Done -> s.warning ?: "Tap another mode to redo the same words. Hold a mode to change it."
            is State.Idle -> {
                val m = Scribe.store.modeFor(app)
                if (m.apps.contains(app) && Scribe.store.chosenFor(app) == null) "${m.name} picked itself here. Tap another to switch."
                else "Hold a mode to change it, or + to make one by talking."
            }
            else -> ""
        }
        note.visibility = if (note.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun pickMode(m: Mode) {
        when (val s = Scribe.state) {
            is State.Recording -> Scribe.setMode(m.id)
            is State.Done -> if (m.id != s.result.mode) Scribe.redo(m.id, app)
            else -> { Scribe.store.choose(app, m.id); render() }
        }
    }

    private fun idle(): View {
        val c = column(this, Gravity.CENTER)
        Scribe.waitingElsewhere(field)?.let { w -> c.addView(waitingBar(w.text)) }
        c.addView(controls(bigMic(96, "Tap to talk")), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return c
    }

    /** The keyboard switch on the left, the big button in the middle, delete and enter on the right. */
    private fun controls(middle: View): View {
        val r = row(this, Gravity.CENTER_VERTICAL)
        r.setPadding(dp(8), 0, dp(8), 0)
        val back = iconButton(R.drawable.ic_keyboard, 52, C.KEY, C.TEXT, "Back to your usual keyboard")
        back.setOnClickListener { backToKeyboard() }
        r.addView(back)
        r.addView(View(this), wrap(weight = 1f))
        r.addView(middle)
        r.addView(View(this), wrap(weight = 1f))
        val keys = column(this)
        val del = iconButton(R.drawable.ic_backspace, 52, C.KEY, C.TEXT, "Delete")
        repeatWhileHeld(del) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
        val enter = iconButton(R.drawable.ic_enter, 52, C.KEY, C.TEXT, "Enter")
        enter.setOnClickListener { enter() }
        keys.addView(del)
        keys.addView(enter.margins(this, t = 10))
        r.addView(keys)
        return r
    }

    private fun bigMic(size: Int, caption: String): View {
        val c = column(this, Gravity.CENTER_HORIZONTAL)
        val b = iconButton(R.drawable.ic_mic, size, C.REC, C.BG, "Start talking")
        b.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            record(Purpose.Dictate(Scribe.store.modeFor(app).id, app, field))
        }
        c.addView(b)
        c.addView(label(caption, 13f, C.DIM).margins(this, t = 8))
        return c
    }

    private fun recording(s: State.Recording): View {
        val c = column(this)
        val top = row(this)
        top.setPadding(dp(8), dp(4), dp(8), 0)
        top.addView(View(this).apply { background = shape(C.REC, 5) }, wrap(10, 10, context = this@ScribeKeyboard))
        timer = label("0:00", 22f, C.TEXT, true).apply { fontFeatureSettings = "'tnum'" }
        top.addView(timer!!.margins(this, l = 10, r = 12))
        level = LevelView(this)
        top.addView(level, wrap(heightDp = 32, weight = 1f, context = this))
        c.addView(top)

        val r = row(this, Gravity.CENTER_VERTICAL)
        r.setPadding(dp(8), 0, dp(8), 0)
        val cancel = iconButton(R.drawable.ic_close, 52, C.KEY, C.TEXT, "Throw this recording away")
        cancel.setOnClickListener { Scribe.cancel() }
        r.addView(cancel)
        r.addView(View(this), wrap(weight = 1f))
        val mid = column(this, Gravity.CENTER_HORIZONTAL)
        val stop = iconButton(R.drawable.ic_stop, 88, C.REC_DARK, C.REC, "Stop and type it in", C.REC)
        stop.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            Scribe.stop()
        }
        mid.addView(stop)
        mid.addView(label(if (Scribe.paused) "Paused" else "Tap to stop", 13f, C.DIM).margins(this, t = 6))
        r.addView(mid)
        r.addView(View(this), wrap(weight = 1f))
        val pause = iconButton(if (Scribe.paused) R.drawable.ic_play else R.drawable.ic_pause, 52, C.KEY, C.TEXT, if (Scribe.paused) "Go on" else "Pause")
        pause.setOnClickListener { Scribe.pause() }
        r.addView(pause)
        c.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        c.addView(label("No time limit. Keeps listening with the screen off.", 12f, C.DIM).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return c
    }

    private fun tick() {
        val t = timer ?: return
        val ms = Scribe.elapsedMs()
        t.text = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)
        level?.push(if (Scribe.paused) 0f else Scribe.level())
        main.postDelayed({ if (Scribe.state is State.Recording && timer === t) tick() }, 120)
    }

    private fun working(s: State.Working): View {
        val c = column(this, Gravity.CENTER)
        c.addView(ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(C.ACCENT) }, wrap(40, 40, context = this))
        c.addView(label(s.label, 15f, C.TEXT).margins(this, t = 12))
        return c
    }

    private fun done(s: State.Done): View {
        // Said in another text box: offer it here rather than claim it went in.
        if (Scribe.waitingElsewhere(field) != null) return idle()
        val c = column(this)
        val bar = row(this)
        bar.background = shape(C.SURFACE, 14)
        bar.setPadding(dp(12), dp(6), dp(6), dp(6))
        bar.addView(iconButton(R.drawable.ic_check, 24, 0, C.GOOD, "").apply { isClickable = false; setPadding(0, 0, 0, 0) })
        val name = if (s.result.mode == "original") "said" else Scribe.store.modes.firstOrNull { it.id == s.result.mode }?.name ?: "said"
        bar.addView(label(if (s.result.mode == "original") "Typed in as you said it" else "Typed in as $name", 14f).apply {
            maxLines = 1
        }.margins(this, l = 8), wrap(weight = 1f))
        if (s.result.mode != "original") bar.addView(pill("Original").apply { setOnClickListener { Scribe.redo("original", app) } }.margins(this, r = 6))
        bar.addView(pill("Undo").apply { setOnClickListener { Scribe.undo() } })
        c.addView(bar)
        c.addView(controls(column(this, Gravity.CENTER_HORIZONTAL).apply {
            val mic = iconButton(R.drawable.ic_mic, 72, C.REC, C.BG, "Talk again")
            mic.setOnClickListener { record(Purpose.Dictate(Scribe.store.modeFor(app).id, app, field)) }
            addView(mic)
            addView(label("Copy", 13f, C.ACCENT).apply {
                setPadding(dp(12), dp(10), dp(12), dp(4))
                setOnClickListener { copy(s.result.text); text = "Copied" }
            })
        }), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return c
    }

    private fun failed(s: State.Failed): View {
        val c = column(this, Gravity.CENTER)
        c.setPadding(dp(12), 0, dp(12), 0)
        c.addView(label(s.message, 15f, C.TEXT).apply { gravity = Gravity.CENTER })
        val r = row(this, Gravity.CENTER)
        val notConnected = !Scribe.store.connected || s.message.contains("connect", ignoreCase = true)
        if (notConnected) r.addView(pill("Open Scribe", "filled").apply { setOnClickListener { Scribe.openApp(this@ScribeKeyboard) } }.margins(this, r = 8))
        if (s.canRetry && !notConnected) r.addView(pill("Retry", "filled").apply { setOnClickListener { Scribe.process() } }.margins(this, r = 8))
        r.addView(pill(if (s.canRetry) "Later" else "OK").apply { setOnClickListener { Scribe.dismiss() } })
        c.addView(r.margins(this, t = 16))
        return c
    }

    private fun draft(s: State.Draft): View {
        val c = column(this)
        val saved = s.mode.id.isNotEmpty() && Scribe.store.modes.any { it.id == s.mode.id }
        val unchanged = saved && Scribe.store.modes.first { it.id == s.mode.id } == s.mode
        val card = column(this)
        card.background = shape(C.SURFACE, 16, C.BORDER, 1)
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
        card.addView(label(s.mode.name.ifEmpty { "New mode" }, 16f, C.TEXT, true))
        card.addView(label(s.mode.prompt.ifEmpty { "(Says it as you said it.)" }, 13f, C.TEXT).apply { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END }.margins(this, t = 4))
        if (s.mode.apps.isNotEmpty()) card.addView(label("Picks itself in: " + s.mode.apps.joinToString(", ") { appName(it) }, 12f, C.DIM).margins(this, t = 6))
        c.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val r = row(this)
        r.addView(pill("Say a change", "rec", C.REC).apply { setOnClickListener { record(Purpose.MakeMode(s.mode)) } }, wrap(weight = 1f))
        if (unchanged) {
            r.addView(pill("Remove", "plain", C.BAD).apply { setOnClickListener { Scribe.deleteMode(s.mode.id) } }.margins(this, l = 4))
            r.addView(pill("Done", "filled").apply { setOnClickListener { Scribe.dismiss() } }.margins(this, l = 4), wrap(weight = 1f))
        } else {
            r.addView(pill("Cancel", "plain", C.DIM).apply { setOnClickListener { Scribe.dismiss() } }.margins(this, l = 4))
            r.addView(pill("Save", "filled").apply { setOnClickListener { Scribe.saveDraft() } }.margins(this, l = 4), wrap(weight = 1f))
        }
        c.addView(r.margins(this, t = 10))
        return c
    }

    private fun waitingBar(text: String): View {
        val bar = row(this)
        bar.background = shape(C.SURFACE, 14)
        bar.setPadding(dp(12), dp(6), dp(6), dp(6))
        bar.addView(label("Waiting: “${text.take(40)}${if (text.length > 40) "…" else ""}”", 13f).apply { maxLines = 1 }, wrap(weight = 1f))
        bar.addView(pill("Type it in", "filled").apply {
            setOnClickListener { Scribe.moveHere(field); typeIn(); render() }
        }.margins(this, l = 6))
        return bar
    }

    // ---- Keys ----

    private fun record(purpose: Purpose) {
        if (!Scribe.store.connected) { Scribe.openApp(this); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Scribe.openApp(this); return
        }
        Scribe.start(purpose)
    }

    private fun backToKeyboard() {
        if (!switchToPreviousInputMethod()) {
            getSystemService(InputMethodManager::class.java)!!.showInputMethodPicker()
        }
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val multiLine = ((info?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!noAction && !multiLine && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /** Delete repeats while held, like any keyboard. */
    private fun repeatWhileHeld(v: View, act: () -> Unit) {
        var held = false
        val again = object : Runnable {
            override fun run() { if (held) { act(); main.postDelayed(this, 60) } }
        }
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    held = true; act()
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    main.postDelayed(again, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { held = false; main.removeCallbacks(again); if (e.action == MotionEvent.ACTION_UP) view.performClick() }
            }
            true
        }
    }

    private fun appName(id: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(id, 0)).toString()
    } catch (e: Exception) { id.removeSuffix(".exe").substringAfterLast('.') }
}
