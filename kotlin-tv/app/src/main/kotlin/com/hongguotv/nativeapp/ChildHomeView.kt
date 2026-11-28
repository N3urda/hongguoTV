// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.hongguotv.core.Series
import com.hongguotv.core.SeriesDisplayOrder

/** Sectioned, recycled rows using the same cards as TV search. */
internal class ChildHomeView(
    context: Context,
    allowed: List<Series>,
    history: List<WatchProgress>,
    private val upper: View,
    private val loadCover: (ImageView, String) -> Unit,
    private val open: (Series, Boolean) -> Unit,
    private val restored: Position? = null,
) : LinearLayout(context) {
    data class Position(val key: String, val row: Int, val top: Int)
    private data class Card(val key: String, val series: Series, val resume: Boolean)
    private data class Row(val heading: String = "", val cards: List<Card> = emptyList())
    private val density = resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).toInt()
    private val columns = when {
        resources.displayMetrics.widthPixels / density < 800 -> 2
        resources.displayMetrics.widthPixels / density < 1200 -> 3
        else -> 4
    }
    private val rows = mutableListOf<Row>()
    private var focusedKey = restored?.key.orEmpty()
    private var pendingFocus: String? = null
    private val list = ListView(context).apply {
        divider = null; dividerHeight = 0
        selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        itemsCanFocus = true
        isVerticalScrollBarEnabled = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }
    init {
        orientation = VERTICAL
        val names = SeriesDisplayOrder.byName(allowed)
        val ids = names.mapTo(HashSet()) { it.id }
        val recent = history.filter { it.series.id in ids }.sortedByDescending { it.updatedAt }.distinctBy { it.series.id }
        rows += Row("最近播放")
        if (recent.isEmpty()) rows += Row("暂无观看记录")
        rows += recent.map { entry ->
            val position = entry.position / 1000
            val badge = "第 ${entry.episodeIndex + 1} 集 · ${position / 60}:${(position % 60).toString().padStart(2, '0')}" +
                if (entry.completed) " · 已看完" else ""
            Card("recent:${entry.series.id}", entry.series.copy(badge = badge), true)
        }.chunked(columns).map { Row(cards = it) }
        rows += Row("允许列表 · ${names.size} 部")
        rows += names.map { Card("allowed:${it.id}", it, false) }.chunked(columns).map { Row(cards = it) }
        if (names.isEmpty()) rows += Row("请家长使用手机控制台添加允许观看的作品")
        list.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(position: Int) = if (rows[position].cards.isEmpty()) 0 else 1
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = rows[position].cards.isNotEmpty()
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val data = rows[position]
                if (data.cards.isEmpty()) return (recycled as? TextView ?: TextView(context)).apply {
                    text = data.heading; textSize = 18f; setTextColor(TvStyle.text)
                    setPadding(dp(4), dp(12), dp(4), dp(8)); isFocusable = false
                }
                val row = recycled as? LinearLayout ?: LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    repeat(columns) {
                        addView(SearchResultCard(context), LayoutParams(0, dp(114), 1f).apply {
                            setMargins(dp(4), dp(4), dp(4), dp(6))
                        })
                    }
                }
                repeat(columns) { index ->
                    val view = row.getChildAt(index) as SearchResultCard
                    val card = data.cards.getOrNull(index)
                    view.visibility = if (card == null) View.INVISIBLE else View.VISIBLE
                    view.isFocusable = card != null
                    if (card != null) {
                        view.tag = card.key
                        view.show(card.series)
                        view.background = TvStyle.card(context, view.hasFocus())
                        loadCover(view.image, card.series.cover)
                        view.setOnClickListener { focusedKey = card.key; open(card.series, card.resume) }
                        view.setOnFocusChangeListener { _, focus ->
                            view.background = TvStyle.card(context, focus)
                            if (focus) focusedKey = card.key
                        }
                        view.setOnKeyListener { _, key, event ->
                            if (key !in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) false
                            else {
                                if (event.action == KeyEvent.ACTION_DOWN) move(position, index, key)
                                true
                            }
                        }
                    }
                }
                return row
            }
        }
        list.viewTreeObserver.addOnGlobalLayoutListener {
            pendingFocus?.let { key ->
                list.findViewWithTag<View>(key)?.let { card ->
                    pendingFocus = null
                    card.requestFocus()
                }
            }
        }
        addView(list, LayoutParams(-1, -1))
    }
    private fun move(row: Int, column: Int, key: Int) {
        var target = row
        var index = column
        when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> index = (column - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> index = (column + 1).coerceAtMost(rows[row].cards.lastIndex)
            else -> {
                val step = if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                target += step
                while (target in rows.indices && rows[target].cards.isEmpty()) target += step
                if (target < 0) { upper.requestFocus(); return }
                if (target !in rows.indices) return
                index = index.coerceAtMost(rows[target].cards.lastIndex)
            }
        }
        focus(target, rows[target].cards[index].key)
    }
    private fun focus(row: Int, key: String) {
        focusedKey = key
        val visible = list.findViewWithTag<View>(key)
        if (visible != null && visible.isShown) {
            visible.requestFocus()
            visible.requestRectangleOnScreen(android.graphics.Rect(0, 0, visible.width, visible.height))
        } else {
            pendingFocus = key
            list.setSelection(row)
        }
    }
    fun enter() {
        val target = rows.indexOfFirst { row -> row.cards.any { it.key == focusedKey } }
            .takeIf { it >= 0 } ?: rows.indexOfFirst { it.cards.isNotEmpty() }
        if (target < 0) { upper.requestFocus(); return }
        val key = rows[target].cards.firstOrNull { it.key == focusedKey }?.key ?: rows[target].cards.first().key
        if (restored != null) list.setSelectionFromTop(restored.row.coerceIn(rows.indices), restored.top)
        list.post { focus(target, key) }
    }
    fun capture() = Position(focusedKey, list.firstVisiblePosition, list.getChildAt(0)?.top ?: 0)
}
