// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.widget.TextView

/** Icon-only transport control; text remains the accessible action name. */
internal class PlaybackIconButton(context: Context, private val action: String) : TextView(context) {
    init {
        id = generateViewId()
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setOnFocusChangeListener { _, _ -> paintFocus() }
        paintFocus()
    }
    private fun paintFocus() {
        background = TvStyle.shape(context, Color.TRANSPARENT,
            if (hasFocus()) TvStyle.text else Color.TRANSPARENT, radius = 12)
        invalidate()
    }
    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        contentDescription = text
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        if (action == "speed") {
            val label = text.toString().substringAfterLast(' ')
            val pen = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = TvStyle.text
                textSize = 24 * resources.displayMetrics.scaledDensity
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                textAlign = android.graphics.Paint.Align.CENTER
            }
            canvas.drawText(label, width / 2f, height / 2f - (pen.ascent() + pen.descent()) / 2f, pen)
            return
        }
        val kind = when (action) {
            "transport-play" -> if (text.toString() == "暂停") 11 else 10
            "episodes" -> 1
            "danmaku" -> 13
            else -> 4
        }
        val size = (28 * resources.displayMetrics.density).toInt()
        TvGlyph(kind, size, TvStyle.text).apply {
            setBounds((width - size) / 2, (height - size) / 2, (width + size) / 2, (height + size) / 2)
            draw(canvas)
        }
    }
}
