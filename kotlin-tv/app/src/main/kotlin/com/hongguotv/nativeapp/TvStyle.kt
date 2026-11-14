// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// TV 界面的公共颜色及背景样式；调整公共外观时优先查看此处。

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Shared native colors and shapes; no live blur or focus animation. */
object TvStyle {
    val background = Color.rgb(17, 19, 23)
    val surface = Color.rgb(29, 32, 38)
    val raised = Color.rgb(43, 47, 54)
    val accent = Color.rgb(255, 112, 95)
    val text = Color.rgb(243, 244, 246)
    val muted = Color.rgb(164, 172, 185)
    val outline = Color.rgb(64, 69, 78)
    const val POSTER_ASPECT = .7f

    fun shape(context: Context, color: Int, border: Int = Color.TRANSPARENT, radius: Int = 10) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * context.resources.displayMetrics.density
            setStroke((2 * context.resources.displayMetrics.density).toInt(), border)
        }

    fun card(context: Context, focused: Boolean, selected: Boolean = false) =
        GradientDrawable().apply {
            setColor(if (focused) raised else if (selected) raised else surface)
            cornerRadius = 7 * context.resources.displayMetrics.density
            setStroke(maxOf(1, (2 * context.resources.displayMetrics.density).toInt()),
                if (focused) text else Color.TRANSPARENT)
        }
}
