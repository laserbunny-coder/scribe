package com.laserbunny.scribe

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/** Scribe's colours: dark, like the rest of Michael's tools. */
object C {
    const val BG = 0xFF1A1A1E.toInt()
    const val PAGE = 0xFF0F0F11.toInt()
    const val SURFACE = 0xFF232328.toInt()
    const val BORDER = 0xFF34343B.toInt()
    const val KEY = 0xFF26262B.toInt()
    const val TEXT = 0xFFECECEE.toInt()
    const val DIM = 0xFFA1A1AA.toInt()
    const val ACCENT = 0xFF8AB4F8.toInt()
    const val ON_ACCENT = 0xFF0F0F11.toInt()
    const val REC = 0xFFF28B4B.toInt()
    const val REC_DARK = 0xFF2A1E18.toInt()
    const val GOOD = 0xFF8FD19E.toInt()
    const val BAD = 0xFFF2A0A0.toInt()
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun Context.shape(fill: Int, radiusDp: Number, strokeColor: Int = 0, strokeDp: Number = 0, dashed: Boolean = false) =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != 0) {
            if (dashed) setStroke(dp(strokeDp), strokeColor, dp(4).toFloat(), dp(3).toFloat())
            else setStroke(dp(strokeDp), strokeColor)
        }
    }

fun Context.label(text: CharSequence, sizeSp: Float = 14f, color: Int = C.TEXT, bold: Boolean = false) =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

/** A mode chip; the picked one is filled. */
fun Context.chip(text: String, picked: Boolean) = label(text, 14f, if (picked) C.ON_ACCENT else C.TEXT, picked).apply {
    gravity = Gravity.CENTER
    minHeight = dp(40)
    setPadding(dp(16), 0, dp(16), 0)
    background = if (picked) shape(C.ACCENT, 20) else shape(C.SURFACE, 20, C.BORDER, 1)
    isClickable = true
    isFocusable = true
}

/** A rounded text button: filled, outlined, or plain. */
fun Context.pill(text: String, style: String = "outline", color: Int = C.TEXT) = label(text, 14f, color, style == "filled").apply {
    gravity = Gravity.CENTER
    minHeight = dp(44)
    setPadding(dp(16), 0, dp(16), 0)
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    background = when (style) {
        "filled" -> shape(C.ACCENT, 22)
        "rec" -> shape(0, 22, C.REC, 1.5f)
        "plain" -> null
        else -> shape(0, 22, 0xFF3A3A42.toInt(), 1)
    }
    if (style == "filled") setTextColor(C.ON_ACCENT)
    isClickable = true
    isFocusable = true
}

/** A round icon button. */
fun Context.iconButton(icon: Int, sizeDp: Int, fill: Int, tint: Int, description: String, strokeColor: Int = 0) =
    ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(tint)
        background = shape(fill, sizeDp / 2, strokeColor, if (strokeColor != 0) 3 else 0)
        contentDescription = description
        scaleType = android.widget.ImageView.ScaleType.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        val pad = dp(sizeDp) / 4
        setPadding(pad, pad, pad, pad)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
    }

fun row(context: Context, gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    this.gravity = gravity
}

fun column(context: Context, gravity: Int = Gravity.NO_GRAVITY) = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    this.gravity = gravity
}

fun wrap(widthDp: Int? = null, heightDp: Int? = null, weight: Float = 0f, context: Context? = null) =
    LinearLayout.LayoutParams(
        widthDp?.let { context!!.dp(it) } ?: if (weight > 0) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
        heightDp?.let { context!!.dp(it) } ?: LinearLayout.LayoutParams.WRAP_CONTENT,
        weight,
    )

fun View.margins(context: Context, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
    val lp = (layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    )
    lp.setMargins(context.dp(l), context.dp(t), context.dp(r), context.dp(b))
    layoutParams = lp
    return this
}

/** How loud it's been lately, as a row of bars. */
class LevelView(context: Context) : View(context) {
    private val levels = FloatArray(24)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.REC }
    private val rect = RectF()

    fun push(level: Float) {
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        levels[levels.size - 1] = level
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat() / levels.size
        val bar = w * 0.55f
        for (i in levels.indices) {
            val h = (height * (0.15f + 0.85f * levels[i])).coerceAtMost(height.toFloat())
            val x = i * w + (w - bar) / 2
            rect.set(x, (height - h) / 2, x + bar, (height + h) / 2)
            canvas.drawRoundRect(rect, bar / 2, bar / 2, paint)
        }
    }
}
