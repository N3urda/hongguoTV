// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.Series

/** Real covers are not clean backdrops: keep source lettering and app text in separate columns. */
internal class HomeSpotlight(
    context: Context,
    series: Series,
    status: String,
    resume: Boolean,
    cover: (ImageView, String) -> Unit,
    open: (Boolean) -> Unit,
) : LinearLayout(context) {
    val play: TextView
    val detail: TextView
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = TvStyle.shape(context, TvStyle.surface)
        val info = LinearLayout(context).apply { orientation = VERTICAL }
        fun label(value: String, size: Float, color: Int, lines: Int = 1) = TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        info.addView(label(if (resume) "接着上次的故事" else "今日精选", 12f, TvStyle.accent))
        info.addView(label(series.title, 26f, TvStyle.text, 2), LayoutParams(-1, -2).apply { topMargin = dp(5) })
        info.addView(label(status.ifBlank { series.tags }, 13f, TvStyle.muted), LayoutParams(-1, -2).apply { topMargin = dp(5) })
        val actions = LinearLayout(context)
        fun action(value: String, proceed: () -> Unit) = label(value, 14f, TvStyle.text).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            minHeight = dp(40)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = TvStyle.card(context, false)
            setOnFocusChangeListener { _, focus -> background = TvStyle.card(context, focus) }
            setOnClickListener { proceed() }
        }
        play = action(if (resume) "▶ 继续观看" else "▶ 开始观看") { open(true) }
        detail = action("查看详情") { open(false) }
        play.tag = "home:spotlight:play"
        detail.tag = "home:spotlight:detail"
        actions.addView(play)
        actions.addView(detail, LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        play.nextFocusRightId = detail.id
        detail.nextFocusLeftId = play.id
        info.addView(actions, LayoutParams(-1, -2).apply { topMargin = dp(10) })
        addView(info, LayoutParams(0, -2, 1f))
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(image, LayoutParams(dp(104), dp(148)).apply { leftMargin = dp(24) })
        cover(image, series.cover)
    }
}
