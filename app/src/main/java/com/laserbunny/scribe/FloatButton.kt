package com.laserbunny.scribe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.annotation.TargetApi
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The floating Scribe button over another keyboard (SwiftKey).
 *
 * Tap it to talk: Scribe's panel covers the keys while it listens, the words
 * are typed straight into the box, and SwiftKey is back at once with a small
 * strip offering Undo and Fix. Hold it to switch to the full Scribe keyboard
 * instead (Scribe's keyboard key switches back). It can be dragged and stays
 * where it's put, and hides in password boxes.
 *
 * It's an accessibility service because only those may see when a keyboard
 * is open, switch keyboards, and (Android 13+) type into the box without
 * being the keyboard. It looks only at whether a keyboard is open and whose
 * it is, the box's own text around the cursor when typing in or fixing, and
 * whether it's a password box. The microphone is started through Scribe's
 * see-through screen (as the Record tile does), since Android only lets an
 * app on screen start listening; the recording then lives in RecordService
 * as always, so this button can't cut one short.
 */
class FloatButton : AccessibilityService() {

    companion object {
        /** On while Android has the service running (shown in the Scribe app). */
        @Volatile var running = false
            private set
        private const val PREFS = "float"
        private const val HOLD_MS = 450L
        private const val STRIP_MS = 8000L
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var button: ImageButton? = null
    private var panel: View? = null
    private var strip: View? = null
    private var timer: TextView? = null
    private val keyboardAt = Rect()
    private val check = Runnable { update() }
    private val hideStrip = Runnable { removeStrip() }
    private val tick = object : Runnable {
        override fun run() {
            val t = timer ?: return
            val s = (Scribe.elapsedMs() / 1000).toInt()
            t.text = "%d:%02d".format(s / 60, s % 60)
            main.postDelayed(this, 500)
        }
    }

    /** True from a tap on this button until what it said has landed (or failed). */
    private var ours = false
    /** The box being typed into, as the Scribe keyboard names it ("package:fieldId"). */
    private var field: String? = null
    private var fieldApp = ""
    /** What the strip offers to copy when the box wouldn't take the words. */
    private var unsent: String? = null

    private val buttonParams = overlayParams()
    private val changed: () -> Unit = { onScribe() }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    override fun onServiceConnected() {
        running = true
        wm = getSystemService(WindowManager::class.java)!!
        Scribe.init(this)
        Scribe.listen(changed)
        update()
    }

    /** Android 13+: lets the button type into the box while SwiftKey stays the keyboard. */
    @TargetApi(33)
    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            super.onStartInput(attribute, restarting)
            fieldApp = attribute.packageName ?: ""
            field = "$fieldApp:${attribute.fieldId}"
            // Something may be waiting for this box (the box comes back after the see-through screen).
            main.postDelayed({ onScribe() }, 150)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Windows change in bursts while a keyboard slides in: look once it settles.
        main.removeCallbacks(check)
        main.postDelayed(check, 120)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        Scribe.unlisten(changed)
        main.removeCallbacksAndMessages(null)
        removePanel(); removeStrip(); hide()
        running = false
        super.onDestroy()
    }

    // ---- Where the button sits ----

    private fun busy() = Scribe.state.let { it is State.Recording || it is State.Working }

    /** Shows the button above another keyboard, or hides it. */
    private fun update() {
        // While it's listening for this button, it stays put even if the keyboard blinks away.
        if (ours && busy()) { show(); return }
        val ime = try {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (e: Exception) { null }
        if (ime == null || isScribe(ime) || inPasswordBox()) { hide(); removeStrip(); return }
        ime.getBoundsInScreen(keyboardAt)
        if (keyboardAt.height() <= 0) { hide(); return }
        show()
        if (ours) deliver()
    }

    private fun isScribe(ime: AccessibilityWindowInfo): Boolean {
        val pkg = try { ime.root?.packageName?.toString() } catch (e: Exception) { null }
        if (pkg != null) return pkg == packageName
        val current = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        return current.startsWith("$packageName/")
    }

    private fun inPasswordBox(): Boolean = try {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isPassword == true
    } catch (e: Exception) { false }

    private fun show() {
        if (keyboardAt.height() <= 0) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val size = dp(48)
        val screenW = resources.displayMetrics.widthPixels
        // Where it was left: from the right edge, and how far above the keyboard.
        val fromRight = prefs.getInt("fromRight", dp(10))
        val above = prefs.getInt("above", dp(64))
        buttonParams.width = size
        buttonParams.height = size
        buttonParams.x = (screenW - size - fromRight).coerceIn(0, max(0, screenW - size))
        buttonParams.y = (keyboardAt.top - size - above).coerceAtLeast(0)
        val b = button
        if (b == null) {
            val v = iconButton(R.drawable.ic_mic, 48, C.REC, C.BG, "Talk with Scribe. Hold for the Scribe keyboard.")
            v.elevation = dp(6).toFloat()
            v.setOnTouchListener(Drag())
            try {
                wm.addView(v, buttonParams)
                button = v
            } catch (e: Exception) { /* the overlay was refused: nothing to show */ }
        } else {
            try { wm.updateViewLayout(b, buttonParams) } catch (e: Exception) { button = null }
        }
        paintButton()
    }

    private fun paintButton() {
        val b = button ?: return
        val listening = ours && Scribe.state is State.Recording
        b.setImageResource(if (listening) R.drawable.ic_stop else R.drawable.ic_mic)
        b.background = shape(if (listening) C.REC_DARK else C.REC, 24, if (listening) C.REC else 0, if (listening) 2 else 0)
        b.imageTintList = android.content.res.ColorStateList.valueOf(if (listening) C.REC else C.BG)
    }

    private fun hide() {
        button?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        button = null
    }

    // ---- Tap, hold, drag ----

    /** A tap talks (or stops), a hold switches to the Scribe keyboard, a drag moves the button. */
    private inner class Drag : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downAt = 0L
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = buttonParams.x; startY = buttonParams.y
                    downAt = SystemClock.uptimeMillis()
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > dp(8) || abs(dy) > dp(8))) moved = true
                    if (moved) {
                        buttonParams.x = (startX + dx).toInt().coerceIn(0, max(0, resources.displayMetrics.widthPixels - v.width))
                        buttonParams.y = (startY + dy).toInt().coerceIn(0, max(0, keyboardAt.top - v.height))
                        try { wm.updateViewLayout(v, buttonParams) } catch (e: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putInt("fromRight", resources.displayMetrics.widthPixels - v.width - buttonParams.x)
                            .putInt("above", keyboardAt.top - v.height - buttonParams.y)
                            .apply()
                    } else {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        if (SystemClock.uptimeMillis() - downAt >= HOLD_MS) toScribe() else tapped()
                    }
                }
            }
            return true
        }
    }

    private fun canType() = Build.VERSION.SDK_INT >= 33 && inputMethod != null

    private fun tapped() {
        val s = Scribe.state
        when {
            s is State.Recording && ours -> Scribe.stop()
            s is State.Recording || s is State.Working -> toast("Scribe is busy with the last one.")
            !canType() -> toScribe()  // Before Android 13 the button can only switch keyboards.
            else -> {
                val f = field
                if (f == null) { toast("Tap into a text box first."); return }
                listen(Purpose.Dictate(Scribe.store.modeFor(fieldApp).id, fieldApp, f))
            }
        }
    }

    /**
     * Starts listening through Scribe's see-through screen: Android lets an app
     * start the microphone only while it's on screen, and that screen closes at once.
     */
    private fun listen(p: Purpose) {
        removeStrip()
        unsent = null
        ours = true
        val i = Intent(this, RecordActivity::class.java)
            .setAction(RecordActivity.FLOAT)
            .putExtra(RecordActivity.PURPOSE, p.toJson().toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        try { startActivity(i) } catch (e: Exception) { ours = false; toast("Couldn't start listening. Try the Scribe keyboard.") }
    }

    /** Straight to the Scribe keyboard. */
    private fun toScribe() {
        val id = ComponentName(this, ScribeKeyboard::class.java).flattenToShortString()
        val switched = Build.VERSION.SDK_INT >= 30 && try { softKeyboardController.switchToInputMethod(id) } catch (e: Exception) { false }
        if (switched) { ours = false; removeStrip(); hide(); return }
        val imm = getSystemService(InputMethodManager::class.java)!!
        if (imm.enabledInputMethodList.none { it.packageName == packageName }) {
            toast("Turn on the Scribe keyboard first, in the Scribe app.")
            Scribe.openApp(this)
        } else {
            imm.showInputMethodPicker()
        }
    }

    // ---- Following Scribe ----

    private fun onScribe() {
        val s = Scribe.state
        if (!ours) { removePanel(); return }
        if (s is State.Recording || s is State.Working) showPanel(s) else removePanel()
        paintButton()
        deliver()
        // A box that wouldn't take the words keeps its Copy strip.
        if (unsent != null) return
        when (s) {
            is State.Done -> showStrip(doneStrip(s))
            is State.Fixed -> showStrip(fixedStrip(s))
            is State.Failed -> { showStrip(failedStrip(s)); ours = s.canRetry }
            is State.Idle -> if (strip == null) ours = false
            else -> {}
        }
    }

    // ---- Typing into the box (Android 13+) ----

    /**
     * What came back for this box goes in now (the Scribe keyboard isn't up,
     * or the button wouldn't be). After a try that didn't land it waits for
     * Copy, or for the Scribe keyboard, instead of trying again and again.
     */
    @TargetApi(33)
    private fun deliver() {
        val f = field ?: return
        if (!canType() || button == null || Scribe.typeFailed) return
        Scribe.takeInsert(f)?.let { typeIn(it) }
    }

    @TargetApi(33)
    private fun conn(): InputMethod.AccessibilityInputConnection? =
        if (Build.VERSION.SDK_INT >= 33) inputMethod?.currentInputConnection else null

    /** The box's text before the cursor (or selection), the selected text, and the text after. */
    @TargetApi(33)
    private fun around(before: Int, after: Int): Triple<String, String, String>? {
        if (Build.VERSION.SDK_INT < 33) return null
        val st = try { conn()?.getSurroundingText(before, after, 0) } catch (e: Exception) { null } ?: return null
        val t = st.text?.toString() ?: return null
        val a = min(st.selectionStart, st.selectionEnd)
        val b = max(st.selectionStart, st.selectionEnd)
        if (a < 0 || b > t.length) return null
        return Triple(t.substring(0, a), t.substring(a, b), t.substring(b))
    }

    /** Types it in, swapping out what it replaces; a fix only ever goes in where it can replace. */
    @TargetApi(33)
    private fun typeIn(i: Insert) {
        val c = conn() ?: return couldNot(i)
        val r = i.replace ?: ""
        val ra = i.replaceAfter
        if (r.isNotEmpty() || !ra.isNullOrEmpty()) {
            val (b, sel, a) = around(r.length, ra?.length ?: 0) ?: return couldNot(i)
            when {
                ra != null -> if (sel.isEmpty() && b == r && a == ra) c.deleteSurroundingText(r.length, ra.length) else return couldNot(i)
                i.selection && sel == r -> {}  // typing replaces the selection
                sel.isEmpty() && b == r -> c.deleteSurroundingText(r.length, 0)
                i.strict -> return couldNot(i)
            }
        }
        if (i.text.isEmpty()) return
        val prev = if (i.replace == null) around(1, 0)?.first ?: "" else ""
        val gap = if (prev.isNotEmpty() && !prev[0].isWhitespace()) " " else ""
        c.commitText(gap + i.text, 1, null)
        main.postDelayed({ landed(i) }, 700)
    }

    /** Looks that the words really arrived; if not, they wait to be copied. */
    @TargetApi(33)
    private fun landed(i: Insert) {
        val squash = { s: String -> s.replace(Regex("\\s+"), " ").trim() }
        val here = around(2000, 0)?.first ?: return
        if (squash(here).contains(squash(i.text).takeLast(24))) return
        couldNot(i)
    }

    private fun couldNot(i: Insert) {
        unsent = i.text
        if (!i.strict && i.text.isNotEmpty()) Scribe.putBack(i.copy(replace = null))
        showStrip(listOf(
            label("Couldn’t type it here", 13f, C.BAD),
            pill("Copy").apply { setOnClickListener { copy(i.text); (this as TextView).text = "Copied" } },
        ))
    }

    // ---- The panel over the keys while it listens ----

    private fun showPanel(s: State) {
        if (keyboardAt.height() <= 0) return
        // Take the old panel away first: removing it after building the new one
        // also stopped the new one's timer, so it sat at 0:00.
        removePanel()
        val p = overlayParams().apply {
            x = keyboardAt.left; y = keyboardAt.top
            width = keyboardAt.width(); height = keyboardAt.height()
        }
        val v = column(this).apply {
            background = shape(C.BG, 0)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val purpose = (s as? State.Recording)?.purpose
        if (purpose is Purpose.Dictate) {
            val chips = row(this)
            for (m in Scribe.store.modes) {
                val c = chip(m.name, m.id == purpose.mode)
                c.setOnClickListener { Scribe.setMode(m.id) }
                chips.addView(c, wrap(heightDp = 36, context = this).apply { marginEnd = dp(8) })
            }
            v.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        }
        if (s is State.Recording) {
            timer = label("0:00", 22f, C.TEXT, true).apply { fontFeatureSettings = "'tnum'" }
            v.addView(timer!!.margins(this, t = 10))
            val r = row(this, Gravity.CENTER_VERTICAL)
            r.addView(iconButton(R.drawable.ic_close, 52, C.KEY, C.TEXT, "Throw this recording away").apply {
                setOnClickListener { ours = false; Scribe.cancel() }
            })
            r.addView(View(this), wrap(weight = 1f))
            r.addView(iconButton(R.drawable.ic_stop, 80, C.REC_DARK, C.REC, "Stop and type it in", C.REC).apply {
                setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); Scribe.stop() }
            })
            r.addView(View(this), wrap(weight = 1f))
            r.addView(View(this), wrap(52, 52, context = this))
            v.addView(r.margins(this, t = 6))
            val note = if (purpose is Purpose.Fix) "Say what to change, or “the whole thing” for everything in the box. Tap the square when done."
                else "Tap the square to type it in. Keeps listening with the screen off."
            v.addView(label(note, 12f, C.DIM).apply { gravity = Gravity.CENTER }.margins(this, t = 8))
            main.removeCallbacks(tick); main.post(tick)
        } else {
            timer = null
            v.addView(label((s as? State.Working)?.label ?: "Writing it out…", 16f, C.TEXT).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        try { wm.addView(v, p); panel = v } catch (e: Exception) {}
    }

    private fun removePanel() {
        main.removeCallbacks(tick)
        timer = null
        panel?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        panel = null
    }

    // ---- The strip beside the button after it's typed in ----

    private fun doneStrip(s: State.Done): List<View> = listOf(
        label("✓ ${Scribe.store.modes.firstOrNull { it.id == s.result.mode }?.name ?: "Typed in"}", 13f, C.GOOD),
        pill("Undo").apply { setOnClickListener { Scribe.undo(); dropStrip() } },
        fixPill(),
    )

    private fun fixedStrip(s: State.Fixed): List<View> {
        val views = mutableListOf<View>(label(if (s.how == "whole") "✓ Changed it all" else "✓ Changed", 13f, C.GOOD))
        s.learn.firstOrNull()?.let { term ->
            val kept = term in s.remembered
            views += pill(if (kept) "✓ $term" else "Remember $term", if (kept) "plain" else "rec", if (kept) C.GOOD else C.REC).apply {
                setOnClickListener { Scribe.remember(term) }
            }
        }
        views += pill("Undo").apply { setOnClickListener { Scribe.undoFix(); dropStrip() } }
        return views
    }

    private fun failedStrip(s: State.Failed): List<View> {
        val views = mutableListOf<View>(label(s.message.take(60), 13f, C.BAD).apply { maxWidth = dp(180) })
        if (s.canRetry) views += pill("Retry").apply { setOnClickListener { Scribe.process() } }
        views += pill("OK").apply { setOnClickListener { Scribe.dismiss(); ours = false; removeStrip() } }
        return views
    }

    /** Fix by voice, from the strip: like holding Fix on the Scribe keyboard. */
    private fun fixPill() = pill("Fix", "rec", C.REC).apply {
        setOnClickListener {
            val last = Scribe.store.last?.takeIf { it.field == field }?.text
            val (b, sel, a) = around(max(FIX_MAX + 1, (last?.length ?: 0) + FIX_AROUND), FIX_MAX + 1)
                ?: return@setOnClickListener toast("Can’t read this box. Hold the button for the Scribe keyboard.")
            val f = field ?: return@setOnClickListener
            val target = fixTargetOf(b, a, sel.ifEmpty { null }, last, Scribe.store.modeFor(fieldApp).id, fieldApp, f)
            when {
                target == null -> toast("Nothing to fix here yet.")
                target.text.length > FIX_MAX -> toast("That’s a lot to change at once. Select a smaller part.")
                else -> listen(target)
            }
        }
    }

    private fun showStrip(views: List<View>) {
        removeStrip()
        val b = button ?: return
        val r = row(this, Gravity.CENTER_VERTICAL).apply {
            background = shape(C.SURFACE, 18, C.BORDER, 1)
            setPadding(dp(10), dp(4), dp(4), dp(4))
            elevation = dp(6).toFloat()
        }
        views.forEach { r.addView(it.margins(this, r = 6)) }
        val p = overlayParams()
        r.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        p.x = (buttonParams.x - r.measuredWidth - dp(8)).coerceAtLeast(0)
        p.y = buttonParams.y + (b.height - r.measuredHeight) / 2
        try { wm.addView(r, p); strip = r } catch (e: Exception) {}
        main.removeCallbacks(hideStrip)
        main.postDelayed(hideStrip, STRIP_MS)
    }

    private fun removeStrip() {
        dropStrip()
        if (!busy()) ours = false
    }

    /**
     * Takes the strip away but keeps following Scribe, for Undo: Scribe tells
     * the button a moment later, and the old words go back in then.
     */
    private fun dropStrip() {
        main.removeCallbacks(hideStrip)
        strip?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        strip = null
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)!!.setPrimaryClip(ClipData.newPlainText("Scribe", text))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
