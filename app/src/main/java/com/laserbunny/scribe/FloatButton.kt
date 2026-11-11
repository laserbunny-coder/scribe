package com.laserbunny.scribe

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import kotlin.math.abs

/**
 * The floating Scribe button. While another keyboard (SwiftKey) is open, a
 * small orange button sits just above it; a tap switches straight to the
 * Scribe keyboard, with no list to pick from, and Scribe's own keyboard key
 * switches back. It can be dragged anywhere and stays where it's put.
 *
 * It's an accessibility service because only those may both see when a
 * keyboard is open and switch keyboards. It reads nothing on screen except
 * whether a keyboard is up, whose it is, and whether the box is a password
 * box (it hides there). Recording never lives here: it runs in Scribe's
 * keyboard and RecordService as always, so this button can't cut one short.
 */
class FloatButton : AccessibilityService() {

    companion object {
        /** On while Android has the service running (shown in the Scribe app). */
        @Volatile var running = false
            private set
        private const val PREFS = "float"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var button: View? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }
    private val keyboardAt = Rect()
    private val check = Runnable { update() }

    override fun onServiceConnected() {
        running = true
        wm = getSystemService(WindowManager::class.java)!!
        update()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Windows change in bursts while a keyboard slides in: look once it settles.
        main.removeCallbacks(check)
        main.postDelayed(check, 120)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacks(check)
        hide()
        running = false
        super.onDestroy()
    }

    /** Shows the button above another keyboard, or hides it. */
    private fun update() {
        val ime = try {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (e: Exception) { null }
        if (ime == null || isScribe(ime) || inPasswordBox()) { hide(); return }
        ime.getBoundsInScreen(keyboardAt)
        if (keyboardAt.height() <= 0) { hide(); return }
        show()
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
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val size = dp(48)
        val screenW = resources.displayMetrics.widthPixels
        // Where it was left: from the right edge, and how far above the keyboard.
        val fromRight = prefs.getInt("fromRight", dp(10))
        val above = prefs.getInt("above", dp(64))
        params.width = size
        params.height = size
        params.x = (screenW - size - fromRight).coerceIn(0, maxOf(0, screenW - size))
        params.y = (keyboardAt.top - size - above).coerceAtLeast(0)
        val b = button
        if (b == null) {
            val v = iconButton(R.drawable.ic_mic, 48, C.REC, C.BG, "Switch to the Scribe keyboard")
            v.elevation = dp(6).toFloat()
            v.setOnTouchListener(Drag())
            try {
                wm.addView(v, params)
                button = v
            } catch (e: Exception) { /* the overlay was refused: nothing to show */ }
        } else {
            try { wm.updateViewLayout(b, params) } catch (e: Exception) { button = null }
        }
    }

    private fun hide() {
        button?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        button = null
    }

    /** Straight to the Scribe keyboard. */
    private fun toScribe() {
        val id = ComponentName(this, ScribeKeyboard::class.java).flattenToShortString()
        val switched = Build.VERSION.SDK_INT >= 30 && try { softKeyboardController.switchToInputMethod(id) } catch (e: Exception) { false }
        if (switched) { hide(); return }
        val imm = getSystemService(InputMethodManager::class.java)!!
        if (imm.enabledInputMethodList.none { it.packageName == packageName }) {
            Toast.makeText(this, "Turn on the Scribe keyboard first, in the Scribe app.", Toast.LENGTH_LONG).show()
            Scribe.openApp(this)
        } else {
            imm.showInputMethodPicker()
        }
    }

    /** A tap switches; a drag moves the button and keeps it there. */
    private inner class Drag : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > dp(8) || abs(dy) > dp(8))) moved = true
                    if (moved) {
                        params.x = (startX + dx).toInt().coerceIn(0, maxOf(0, resources.displayMetrics.widthPixels - v.width))
                        params.y = (startY + dy).toInt().coerceIn(0, maxOf(0, keyboardAt.top - v.height))
                        try { wm.updateViewLayout(v, params) } catch (e: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putInt("fromRight", resources.displayMetrics.widthPixels - v.width - params.x)
                            .putInt("above", keyboardAt.top - v.height - params.y)
                            .apply()
                    } else {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        toScribe()
                    }
                }
            }
            return true
        }
    }
}
