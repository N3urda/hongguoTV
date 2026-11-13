package com.hongguotv.nativeapp

import android.content.Context
import android.view.Gravity
import android.widget.*

/** Readable, non-focusable guidance; actions remain with the owning screen. */
internal class TvEmptyState(context: Context, title: String, detail: String) : LinearLayout(context) {
    val heading = TextView(context)
    val description = TextView(context)
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        setPadding(dp(24), dp(24), dp(24), dp(32))
        addView(ImageView(context).apply { setImageDrawable(TvGlyph(7, dp(38), TvStyle.muted)); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }, LayoutParams(dp(38), dp(38)).apply { bottomMargin = dp(20) })
        heading.text = title; heading.textSize = 21f; heading.setTextColor(TvStyle.text); heading.gravity = Gravity.CENTER
        heading.typeface = android.graphics.Typeface.DEFAULT_BOLD
        addView(heading, LayoutParams(-1, -2))
        description.text = detail; description.textSize = 13f; description.setTextColor(TvStyle.muted); description.gravity = Gravity.CENTER
        description.setLineSpacing(dp(5).toFloat(), 1f)
        addView(description, LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }
}
