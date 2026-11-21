// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*

/** Shared episode ranges, grid and focus paths for detail and playback. */
internal class EpisodeSelectorView(
    context: Context,
    count: Int,
    current: Int,
    val group: Int,
    lastWatched: Int?,
    lastCompleted: Boolean,
    onGroup: (Int) -> Unit,
    onJump: (Int, View) -> Unit,
    onSelect: (Int) -> Unit,
    private val transparent: Boolean = false,
    private val showJump: Boolean = true,
) : LinearLayout(context) {
    val groupButtons = mutableListOf<TextView>()
    val episodes = mutableListOf<View>()
    val jump: TextView
    private var focusedEpisode = if (current / 20 == group) current else group * 20
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun button(value: String, selected: Boolean = false, action: () -> Unit) = TextView(context).apply {
        id = View.generateViewId()
        text = value
        textSize = 16f
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isSelected = selected
        setTextColor(TvStyle.text)
        setPadding(dp(14), dp(3), dp(14), dp(3))
        fun paint() {
            background = if (transparent) TvStyle.shape(context, Color.TRANSPARENT,
                if (hasFocus()) TvStyle.text else Color.TRANSPARENT, radius = 12)
                else TvStyle.card(context, hasFocus(), isSelected)
            setTextColor(if (transparent && isSelected) TvStyle.accent else TvStyle.text)
        }
        setOnFocusChangeListener { _, _ -> paint() }
        setOnClickListener { action() }
        paint()
    }

    init {
        orientation = VERTICAL
        val ranges = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val strip = LinearLayout(context)
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            addView(strip)
        }
        ranges.addView(scroll, LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(12) })
        val maxGroup = (count - 1).coerceAtLeast(0) / 20
        for (index in 0..maxGroup) {
            val start = index * 20 + 1
            val end = minOf((index + 1) * 20, count)
            val cell = button("$start–$end", index == group) { if (index != group) onGroup(index) }.apply {
                tag = "episode-group-$index"
                contentDescription = "第 $start 至 $end 集分组"
                textSize = 13f
                minWidth = dp(88)
                setPadding(dp(14), 0, dp(14), 0)
                fun paintGroup() {
                    setTextColor(if (isSelected) TvStyle.accent else if (hasFocus()) TvStyle.text else TvStyle.muted)
                    setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    background = TvStyle.shape(context,
                        if (!transparent && (isSelected || hasFocus())) TvStyle.raised else Color.TRANSPARENT,
                        if (hasFocus()) TvStyle.text else Color.TRANSPARENT, radius = 20)
                }
                setOnFocusChangeListener { _, _ -> paintGroup() }
                paintGroup()
            }
            groupButtons += cell
            strip.addView(cell, LayoutParams(-2, dp(40)).apply { rightMargin = dp(6) })
        }
        jump = button("跳转集数") { onJump(focusedEpisode, jump) }
        if (showJump) ranges.addView(jump, LayoutParams(-2, dp(44)))
        addView(ranges, LayoutParams(-1, dp(54)))
        addView(TextView(context).apply {
            text = "高亮为当前集 · 上次为续播位置 · 方向键选择，确定播放"
            textSize = 12f
            setTextColor(TvStyle.muted)
            setPadding(0, 0, 0, dp(6))
        })
        (group * 20 until minOf((group + 1) * 20, count)).toList().chunked(10).forEach { numbers ->
            val row = LinearLayout(context)
            numbers.forEach { index ->
                val cell = button("${index + 1}" + when {
                    index == current -> "\n当前"
                    index == lastWatched -> if (lastCompleted) "\n已看" else "\n上次"
                    else -> ""
                }, index == current) { onSelect(index) }.apply {
                    tag = "episode-$index"
                    textSize = 12f
                    includeFontPadding = false
                    setPadding(dp(2), dp(3), dp(2), dp(3))
                    contentDescription = "第 ${index + 1} 集"
                    val paint = onFocusChangeListener
                    setOnFocusChangeListener { view, focused ->
                        paint?.onFocusChange(view, focused)
                        if (focused) focusedEpisode = index
                    }
                }
                row.addView(cell, LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) })
                episodes += cell
            }
            repeat(10 - numbers.size) { row.addView(Space(context), LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) }) }
            addView(row, LayoutParams(-1, dp(60)))
        }
        val activeGroup = groupButtons[group.coerceIn(groupButtons.indices)]
        groupButtons.forEachIndexed { i, cell ->
            cell.nextFocusLeftId = groupButtons.getOrNull(i - 1)?.id ?: cell.id
            cell.nextFocusRightId = groupButtons.getOrNull(i + 1)?.id ?: if (showJump) jump.id else cell.id
            cell.nextFocusUpId = cell.id
            cell.nextFocusDownId = episodes.firstOrNull()?.id ?: cell.id
        }
        jump.nextFocusLeftId = groupButtons.last().id
        jump.nextFocusRightId = jump.id
        jump.nextFocusUpId = jump.id
        jump.nextFocusDownId = episodes.firstOrNull()?.id ?: jump.id
        episodes.forEachIndexed { i, cell ->
            cell.nextFocusLeftId = if (i % 10 == 0) cell.id else episodes[i - 1].id
            cell.nextFocusRightId = if (i % 10 == 9 || i == episodes.lastIndex) cell.id else episodes[i + 1].id
            cell.nextFocusUpId = if (i >= 10) episodes[i - 10].id else activeGroup.id
            cell.nextFocusDownId = episodes.getOrNull(i + 10)?.id ?: cell.id
        }
        scroll.post { scroll.scrollTo((activeGroup.left - scroll.width / 2 + activeGroup.width / 2).coerceAtLeast(0), 0) }
    }
}
