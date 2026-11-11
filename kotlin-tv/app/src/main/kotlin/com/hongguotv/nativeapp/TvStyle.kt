// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// TV 界面的公共颜色及背景样式；调整公共外观时优先查看此处。

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Shared native colors and shapes; no live blur or focus animation. */
object TvStyle {
    val background = Color.rgb(11, 15, 21)
    val surface = Color.rgb(23, 30, 40)
    val raised = Color.rgb(32, 42, 54)
    val accent = Color.rgb(255, 120, 68)
    val text = Color.rgb(243, 244, 246)
    val muted = Color.rgb(147, 160, 178)
    val outline = Color.rgb(43, 55, 70)
    const val POSTER_ASPECT = .7f

    fun shape(context: Context, color: Int, border: Int = Color.TRANSPARENT, radius: Int = 10) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * context.resources.displayMetrics.density
            setStroke((2 * context.resources.displayMetrics.density).toInt(), border)
        }

    fun card(context: Context, focused: Boolean, selected: Boolean = false) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (focused) intArrayOf(Color.argb(155, 184, 119, 82), Color.argb(235, 30, 42, 60))
            else if (selected) intArrayOf(Color.argb(90, 100, 54, 35), Color.argb(210, 26, 35, 50))
            else intArrayOf(Color.argb(205, 28, 38, 55), Color.argb(215, 18, 27, 41))).apply {
            cornerRadius = 10 * context.resources.displayMetrics.density
            setStroke(maxOf(1, (context.resources.displayMetrics.density).toInt()),
                if (focused) Color.argb(200, 255, 172, 123) else if (selected) Color.argb(105, 255, 120, 68) else Color.argb(20, 255, 255, 255))
        }
}
