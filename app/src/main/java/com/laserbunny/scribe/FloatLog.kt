package com.laserbunny.scribe

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short diary of what the floating button saw (keyboard up or down, the box
 * opening or closing, Scribe's state, what it typed), so a problem on the phone
 * can be traced. It keeps only the last lines, and never the words themselves:
 * lengths and app names only. The Scribe app copies it with one tap.
 */
object FloatLog {
    private const val PREFS = "floatlog"
    private const val KEEP = 120
    private val lines = ArrayDeque<String>()
    private var loaded = false
    private val clock = SimpleDateFormat("HH:mm:ss.S", Locale.US)

    @Synchronized
    fun add(context: Context, what: String) {
        load(context)
        lines.addLast(clock.format(Date()) + "  " + what)
        while (lines.size > KEEP) lines.removeFirst()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lines", lines.joinToString("\n")).apply()
    }

    @Synchronized
    fun text(context: Context): String {
        load(context)
        return lines.joinToString("\n")
    }

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lines", null)
            ?.split("\n")?.filter { it.isNotBlank() }?.let { lines.addAll(it.takeLast(KEEP)) }
    }
}
