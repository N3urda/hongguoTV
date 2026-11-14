// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.widget.Switch
import android.widget.TextView

/** Shared visual language for browsing pages, modal panels and playback controls. */
internal object TvVisual {
    val accent = TvStyle.accent
    val value = TvStyle.muted
    val primary = TvStyle.text
    val focusedValue = TvStyle.text

    fun panel(context: Context, modal: Boolean = false) = TvStyle.shape(
        context, TvStyle.surface,
        if (modal) TvStyle.outline else Color.TRANSPARENT, radius = 10,
    )

    fun row(context: Context, focused: Boolean, selected: Boolean = false, category: Boolean = false): Drawable =
        TvStyle.shape(context,
            if (focused) TvStyle.raised else if (selected || category) TvStyle.surface else Color.TRANSPARENT,
            if (focused) TvStyle.text else if (selected) TvStyle.accent else Color.TRANSPARENT,
            radius = 7)

    fun tab(context: Context, focused: Boolean, selected: Boolean): Drawable =
        TvStyle.shape(context,
            if (focused) TvStyle.raised else if (selected) TvStyle.surface else Color.TRANSPARENT,
            if (focused) TvStyle.text else if (selected) TvStyle.accent else Color.TRANSPARENT,
            radius = 7)

    fun backdrop(context: Context): Drawable = android.graphics.drawable.ColorDrawable(TvStyle.background)

}

/** Keeps the complete label as accessible text; draws label and current value separately. */
internal class SettingsValueView(context: Context) : TextView(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    init { setSingleLine(true) }
    override fun onDraw(canvas: Canvas) {
        val parts = text.toString().split("  ·  ", limit = 2)
        val right = parts.getOrNull(1) ?: "›"
        ink.typeface = typeface
        ink.textSize = textSize
        val available = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(0f)
        val valueText = TextUtils.ellipsize(right, android.text.TextPaint(ink), available * .45f, TextUtils.TruncateAt.END).toString()
        val valueWidth = ink.measureText(valueText)
        val gap = 18 * resources.displayMetrics.density
        val title = TextUtils.ellipsize(parts[0], android.text.TextPaint(ink),
            (available - valueWidth - gap).coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()
        val baseline = height / 2f - (ink.fontMetrics.ascent + ink.fontMetrics.descent) / 2
        ink.color = if (hasFocus()) Color.WHITE else TvVisual.primary
        canvas.drawText(title, paddingLeft.toFloat(), baseline, ink)
        ink.color = if (hasFocus()) TvVisual.focusedValue else TvVisual.value
        canvas.drawText(valueText, width - paddingRight - valueWidth, baseline, ink)
    }
}

/** One focus target per settings row; this child only displays the boolean value. */
internal class SettingsSwitch(context: Context) : Switch(context) {
    var rowFocused: Boolean = false
        set(value) { field = value; invalidate() }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = resources.displayMetrics.density
        setMeasuredDimension(resolveSize((54 * d).toInt(), widthMeasureSpec), resolveSize((30 * d).toInt(), heightMeasureSpec))
    }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val h = minOf(height.toFloat(), 26 * d)
        val top = (height - h) / 2
        val inset = 2 * d
        val rect = RectF(inset, top + inset, width - inset, top + h - inset)
        ink.color = if (isChecked) TvVisual.accent else Color.argb(50, 255, 255, 255)
        canvas.drawRoundRect(rect, h / 2, h / 2, ink)
        if (rowFocused) {
            ink.style = Paint.Style.STROKE
            ink.strokeWidth = d
            ink.color = TvVisual.focusedValue
            canvas.drawRoundRect(RectF(d / 2, top + d / 2, width - d / 2, top + h - d / 2), h / 2, h / 2, ink)
            ink.style = Paint.Style.FILL
        }
        val radius = h / 2 - 5 * d
        ink.color = Color.WHITE
        canvas.drawCircle(if (isChecked) width - h / 2 else h / 2, height / 2f, radius, ink)
    }
}
