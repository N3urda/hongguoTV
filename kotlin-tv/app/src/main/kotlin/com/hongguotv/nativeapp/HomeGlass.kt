// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Homepage-only glass palette; does not change other screens' visual theme. */
internal object HomeGlass {
    fun panel(context: Context, focused: Boolean = false, selected: Boolean = false) =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (focused) intArrayOf(0xff132a39.toInt(), 0xff191b30.toInt())
            else if (selected) intArrayOf(0xee203849.toInt(), 0xee192431.toInt())
            else intArrayOf(0xee1c2330.toInt(), 0xf20c101a.toInt()),
        ).apply {
            val density = context.resources.displayMetrics.density
            cornerRadius = 18 * density
            setStroke((if (focused) 2 * density else density).toInt().coerceAtLeast(1),
                if (focused) Color.rgb(93, 214, 255) else if (selected) 0xff42758f.toInt() else 0xff394453.toInt())
        }
}
