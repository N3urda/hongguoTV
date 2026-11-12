// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.widget.Switch
import android.widget.TextView

/** Shared visual language for browsing pages, modal panels and playback controls. */
internal object TvVisual {
    private var sharedNoise: Bitmap? = null
    val accent = Color.rgb(255, 120, 68)
    val value = Color.rgb(192, 204, 222)
    val primary = Color.rgb(241, 245, 251)
    val focusedValue = Color.rgb(255, 158, 112)

    fun panel(context: Context, modal: Boolean = false) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.argb(if (modal) 250 else 220, 44, 59, 80), Color.argb(if (modal) 250 else 220, 36, 49, 68))).apply {
        cornerRadius = 16 * context.resources.displayMetrics.density
        setStroke(maxOf(1, context.resources.displayMetrics.density.toInt()), Color.argb(40, 255, 255, 255))
    }

    fun row(context: Context, focused: Boolean, selected: Boolean = false, category: Boolean = false): Drawable =
        object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val density = context.resources.displayMetrics.density
            override fun draw(canvas: Canvas) {
                val rect = RectF(bounds)
                val radius = 9 * density
                // Concentrate the warm light near the leading edge; keep the value end quiet.
                paint.shader = if (focused) LinearGradient(rect.left, 0f, rect.right, 0f,
                    intArrayOf(Color.argb(166, 255, 120, 68), Color.argb(64, 255, 120, 68), Color.argb(10, 255, 120, 68)),
                    floatArrayOf(0f, .32f, 1f), Shader.TileMode.CLAMP) else null
                paint.color = if (focused) { Color.WHITE } else if (selected) Color.argb(24, 255, 120, 68) else if (category) Color.argb(45, 28, 43, 65) else Color.TRANSPARENT
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(rect, radius, radius, paint)
                paint.shader = null
                if (focused || selected) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = density
                    paint.shader = if (focused) LinearGradient(rect.left, 0f, rect.right, 0f,
                        intArrayOf(Color.argb(160, 255, 180, 130), Color.argb(46, 255, 120, 68), Color.argb(12, 255, 120, 68)),
                        floatArrayOf(0f, .32f, 1f), Shader.TileMode.CLAMP) else null
                    paint.color = if (focused) { Color.WHITE } else Color.argb(70, 255, 120, 68)
                    rect.inset(density / 2, density / 2)
                    canvas.drawRoundRect(rect, radius, radius, paint)
                    paint.shader = null
                    paint.style = Paint.Style.FILL
                    paint.color = accent
                    val barHeight = minOf(28 * density, rect.height() * .65f)
                    canvas.drawRoundRect(RectF(rect.left + 6 * density, rect.centerY() - barHeight / 2,
                        rect.left + 9 * density, rect.centerY() + barHeight / 2), 2 * density, 2 * density, paint)
                }
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(filter: ColorFilter?) {}
            @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
        }

    fun tab(context: Context, focused: Boolean, selected: Boolean): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = context.resources.displayMetrics.density
        override fun draw(canvas: Canvas) {
            val rect = RectF(bounds)
            if (focused) {
                paint.color = Color.argb(28, 255, 120, 68)
                canvas.drawRoundRect(rect, 7 * density, 7 * density, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density
                paint.color = Color.argb(150, 255, 120, 68)
                rect.inset(density / 2, density / 2)
                canvas.drawRoundRect(rect, 7 * density, 7 * density, paint)
                paint.style = Paint.Style.FILL
            }
            if (selected) {
                paint.color = accent
                canvas.drawRoundRect(RectF(bounds.left + 9 * density, bounds.bottom - 3 * density,
                    bounds.right - 9 * density, bounds.bottom - density), density, density, paint)
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(filter: ColorFilter?) {}
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun backdrop(context: Context): Drawable = object : Drawable() {
        private val noise = sharedNoise ?: BitmapFactory.decodeResource(context.resources, R.drawable.tv_noise_tile).also { sharedNoise = it }
        private val grain = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat().coerceAtLeast(1f)
            val h = bounds.height().toFloat().coerceAtLeast(1f)
            paint.shader = RadialGradient(w * .5f, h * .30f, w * .58f,
                Color.rgb(61, 83, 113), Color.rgb(31, 43, 61), Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            paint.shader = RadialGradient(w * .92f, h * .95f, w * .48f,
                Color.argb(22, 130, 75, 40), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            paint.shader = grain
            paint.alpha = 10
            canvas.drawRect(bounds, paint)
            paint.alpha = 255
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(filter: ColorFilter?) {}
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
    }
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
