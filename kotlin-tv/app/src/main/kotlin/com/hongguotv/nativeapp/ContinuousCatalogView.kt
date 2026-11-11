// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 连续分页结果视图：将列表展示、追加加载和遥控器焦点衔接集中管理。

import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.hongguotv.core.RankPosition
import com.hongguotv.core.Series

/** Framework GridView recycles offscreen cards; pagination never replaces the grid or its focus. */
internal class ContinuousCatalogView(
    context: Context,
    private val columns: Int,
    private val upper: View,
    private val bind: (Series, View?) -> View,
    private val selected: (Series) -> Unit,
    private val open: (Series) -> Unit,
    private val more: (Series, View) -> Unit,
    private val load: () -> Unit,
) : LinearLayout(context) {
    data class Position(val id: String, val top: Int)

    private val entries = mutableListOf<Series>()
    private val ids = mutableMapOf<String, Long>()
    private var nextId = 0L
    private var available = false
    private var busy = false
    private var failureReason = ""
    private var failed = false
    private var closed = false
    private var navigating = false
    private var enterAdded = false
    private var restorePosition: Position? = null
    private var itemHeightLimit = 0
    private var remoteNavigation = false
    private var sideEntry: View? = null

    fun setSideEntry(view: View?) {
        sideEntry = view
    }

    var onBrowsePosition: ((Int) -> Unit)? = null
    var onGridFocus: ((Boolean) -> Unit)? = null
    val grid =
        object : GridView(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    itemHeightLimit = View.MeasureSpec.getSize(heightMeasureSpec) - dp(6)
                    for (i in 0 until childCount) (getChildAt(i) as? CatalogPosterCard)?.fitHeight(
                        itemHeightLimit
                    )
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                }
            }
            .apply {
                id = View.generateViewId()
                numColumns = columns
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                horizontalSpacing = dp(14)
                verticalSpacing = dp(10)
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                setPadding(dp(3), dp(3), dp(3), dp(3))
                selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                descendantFocusability = FOCUS_BLOCK_DESCENDANTS
                nextFocusUpId = upper.id
            }
    private val status =
        TextView(context).apply {
            textSize = 13f
            setTextColor(TvStyle.muted)
        }
    val next = action("加载更多") { requestMore(true) }
    val top =
        action("回到顶部") {
            if (entries.isNotEmpty()) {
                navigating = false
                onBrowsePosition?.invoke(0)
                grid.setSelection(0)
                grid.requestFocus()
            } else upper.requestFocus()
        }
    private val cards =
        object : BaseAdapter() {
            override fun getCount() = entries.size

            override fun getItem(position: Int) = entries[position]

            override fun getItemId(position: Int) =
                entries.getOrNull(position)?.let { ids.getOrPut(it.id) { nextId++ } }
                    ?: AdapterView.INVALID_ROW_ID

            override fun hasStableIds() = true

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = entries[position]
                return bind(item, convertView).apply {
                    (this as? CatalogPosterCard)?.fitHeight(itemHeightLimit)
                    tag = item.id
                    isFocusable = false
                    isClickable = false
                    isLongClickable = false
                    if (this is ViewGroup) descendantFocusability = FOCUS_BLOCK_DESCENDANTS
                    background =
                        TvStyle.card(
                            context,
                            grid.hasFocus() && grid.selectedItemPosition == position,
                        )
                }
            }
        }

    init {
        orientation = VERTICAL
        grid.adapter = cards
        grid.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, i, _ -> entries.getOrNull(i)?.let(open) }
        grid.onItemLongClickListener =
            AdapterView.OnItemLongClickListener { _, view, i, _ ->
                entries.getOrNull(i)?.let { more(it, view) }
                true
            }
        grid.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    if (closed) return
                    entries.getOrNull(position)?.let(selected)
                    paintFocus()
                    if (grid.hasFocus()) onBrowsePosition?.invoke(position)
                    if (
                        navigating &&
                            grid.hasFocus() &&
                            position >= maxOf(columns, entries.size - columns * 2)
                    )
                        requestMore(false)
                }
            }
        grid.setOnFocusChangeListener { _, focus ->
            paintFocus()
            onGridFocus?.invoke(focus)
        }
        grid.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_UP)
                    entries.getOrNull(grid.selectedItemPosition)?.let { item ->
                        grid.selectedView?.let { more(item, it) }
                    }
                true
            } else if (event.action == KeyEvent.ACTION_DOWN) {
                navigating = key in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
                when {
                    key == KeyEvent.KEYCODE_DPAD_LEFT &&
                        grid.selectedItemPosition % columns == 0 &&
                        sideEntry != null -> {
                        sideEntry?.requestFocus()
                        true
                    }
                    key == KeyEvent.KEYCODE_DPAD_UP &&
                        grid.selectedItemPosition in 0 until columns -> {
                        upper.requestFocus()
                        true
                    }
                    key == KeyEvent.KEYCODE_DPAD_DOWN &&
                        grid.selectedItemPosition >= ((entries.size - 1) / columns) * columns -> {
                        if (remoteNavigation) {
                            if (failed) next.requestFocus() else if (available) requestMore(false)
                        } else {
                            (if (available) next else top).requestFocus()
                            if (available && !failed) requestMore(true)
                        }
                        true
                    }
                    else -> false
                }
            } else false
        }
        grid.setOnScrollListener(
            object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, state: Int) {
                    if (state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) navigating = true
                }

                override fun onScroll(view: AbsListView?, first: Int, count: Int, total: Int) {
                    if (grid.hasFocus())
                        onBrowsePosition?.invoke(grid.selectedItemPosition.coerceAtLeast(first))
                    if (navigating && first > 0 && first + count >= total - columns * 2)
                        requestMore(false)
                }
            }
        )
        addView(grid, LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        footer.addView(status, LayoutParams(0, -2, 1f))
        footer.addView(next)
        footer.addView(top)
        addView(footer)
        listOf(next, top).forEach { it.nextFocusUpId = grid.id }
        next.nextFocusRightId = top.id
        top.nextFocusLeftId = next.id
        grid.nextFocusDownId = next.id
        paintStatus()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun action(value: String, click: () -> Unit) =
        TextView(context).apply {
            id = View.generateViewId()
            text = value
            textSize = 14f
            setTextColor(TvStyle.text)
            isFocusable = true
            isClickable = true
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = TvStyle.card(context, false)
            setOnFocusChangeListener { _, focus -> background = TvStyle.card(context, focus) }
            setOnClickListener { click() }
        }

    private fun paintFocus() {
        for (i in 0 until grid.childCount) grid.getChildAt(i).background =
            TvStyle.card(
                context,
                grid.hasFocus() && grid.firstVisiblePosition + i == grid.selectedItemPosition,
            )
    }

    private fun paintStatus() {
        status.text =
            when {
                entries.isEmpty() && !available -> "暂无结果，可切换分类或搜索词"
                busy -> "已加载 ${entries.size} 部 · 正在加载…"
                failed -> "加载失败$failureReason，已保留列表 · 按「重试加载」重试"
                available -> "已加载 ${entries.size} 部 · 向下浏览继续加载"
                else -> "共 ${entries.size} 部 · 已加载完毕"
            }
        next.text =
            when {
                busy -> "加载中…"
                failed -> "重试加载"
                else -> "加载更多"
            }
        next.visibility = if (available && (!remoteNavigation || failed)) VISIBLE else GONE
        top.visibility = if (remoteNavigation) GONE else VISIBLE
        grid.nextFocusDownId =
            if (remoteNavigation) {
                if (failed) next.id else grid.id
            } else if (available) next.id else top.id
    }

    fun useRemoteNavigation() {
        remoteNavigation = true
        paintStatus()
    }

    fun resetToTop() {
        navigating = false
        enterAdded = false
        restorePosition = null
        onBrowsePosition?.invoke(0)
        grid.setSelection(if (entries.isEmpty()) AdapterView.INVALID_POSITION else 0)
        post {
            if (!closed && entries.isNotEmpty()) {
                grid.setSelection(0)
                grid.smoothScrollToPositionFromTop(0, 0, 0)
            }
        }
    }

    fun show(items: List<Series>, hasMore: Boolean, position: Position? = null) {
        entries.clear()
        entries.addAll(items.distinctBy { it.id })
        available = hasMore
        busy = false
        failed = false
        restorePosition = position
        cards.notifyDataSetChanged()
        paintStatus()
        val index = entries.indexOfFirst { it.id == position?.id }.coerceAtLeast(0)
        grid.setSelection(if (entries.isEmpty()) AdapterView.INVALID_POSITION else index)
        entries.getOrNull(index)?.let(selected)
        post {
            if (closed) return@post
            if (position != null && index > 0)
                grid.smoothScrollToPositionFromTop(index, position.top, 0)
            restorePosition = null
        }
    }

    fun capture(): Position? =
        entries.getOrNull(grid.selectedItemPosition)?.let {
            Position(it.id, grid.selectedView?.top ?: 0)
        } ?: restorePosition

    fun requestMore(manual: Boolean) {
        if (closed || !available || (failed && !manual)) return
        if (busy) {
            if (manual && next.hasFocus()) enterAdded = true
            return
        }
        busy = true
        failed = false
        navigating = false
        enterAdded = manual && next.hasFocus()
        paintStatus()
        load()
    }

    fun append(items: List<Series>, hasMore: Boolean) {
        if (closed) return
        val seen = entries.mapTo(HashSet()) { it.id }
        val firstNew = entries.size
        entries.addAll(items.filter { seen.add(it.id) })
        available = hasMore
        busy = false
        failed = false
        navigating = false
        val focusAdded = enterAdded && next.hasFocus()
        enterAdded = false
        cards.notifyDataSetChanged()
        paintStatus()
        if (focusAdded && entries.size > firstNew) {
            grid.setSelection(firstNew)
            grid.requestFocus()
        } else if (!available && next.hasFocus()) {
            if (remoteNavigation) grid.requestFocus() else top.requestFocus()
        }
    }

    fun fail(problem: Throwable? = null) {
        if (closed) return
        failureReason =
            when (problem) {
                is com.hongguotv.core.ContentHttpException -> "（HTTP ${problem.statusCode}）"
                is java.net.SocketTimeoutException -> "（连接超时）"
                is java.net.UnknownHostException -> "（无法连接网站）"
                is java.net.SocketException -> "（网络连接中断）"
                null -> ""
                else -> "（响应异常）"
            }
        busy = false
        failed = true
        navigating = false
        enterAdded = false
        paintStatus()
    }

    fun enter() {
        grid.requestFocus()
    }

    fun close() {
        closed = true
        navigating = false
        grid.onItemSelectedListener = null
        grid.setOnScrollListener(null)
    }
}

internal class CatalogPosterCard(context: Context) : LinearLayout(context) {
    val image =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    private val poster = FrameLayout(context)
    private val rank =
        TextView(context).apply {
            textSize = 13f
            setTextColor(TvStyle.text)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val density = resources.displayMetrics.density
            setPadding(
                (6 * density).toInt(),
                (3 * density).toInt(),
                (6 * density).toInt(),
                (3 * density).toInt(),
            )
            setBackgroundColor(Color.argb(200, 15, 18, 24))
            visibility = GONE
        }
    private val title =
        TextView(context).apply {
            textSize = 14f
            setTextColor(TvStyle.text)
            includeFontPadding = false
            minLines = 2
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    private val badge =
        TextView(context).apply {
            textSize = 12f
            setTextColor(TvStyle.muted)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    private var preferredHeight = 0

    init {
        orientation = VERTICAL
        val density = resources.displayMetrics.density
        setPadding(
            (4 * density).toInt(),
            (4 * density).toInt(),
            (4 * density).toInt(),
            (9 * density).toInt(),
        )
        val width = (resources.displayMetrics.widthPixels * .9f - 14 * density * 5) / 6
        preferredHeight = (width / TvStyle.POSTER_ASPECT).toInt()
        poster.addView(image, FrameLayout.LayoutParams(-1, -1))
        poster.addView(
            rank,
            FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.START),
        )
        addView(poster, LayoutParams(-1, preferredHeight))
        addView(title)
        addView(badge)
    }

    fun show(item: Series, position: RankPosition? = null) {
        title.text = item.title
        badge.text = position?.heat?.takeIf { it.isNotBlank() } ?: item.badge
        rank.text = position?.rank?.let { "第 $it 名" }.orEmpty()
        rank.visibility = if (position?.rank != null) VISIBLE else GONE
        rank.setTextColor(
            if ((position?.rank ?: Int.MAX_VALUE) <= 3) TvStyle.accent else TvStyle.text
        )
        contentDescription =
            listOf(
                    rank.text.toString(),
                    item.title,
                    item.badge,
                    item.tags,
                    position?.heat.orEmpty(),
                    position?.metrics.orEmpty(),
                )
                .filter { it.isNotBlank() }
                .joinToString("，")
    }

    fun fitHeight(available: Int) {
        if (available <= 0) return
        val density = resources.displayMetrics.density
        val minimumPoster = (32 * density).toInt()
        val padding = paddingTop + paddingBottom + (8 * density).toInt()
        val lines =
            if (available >= padding + title.lineHeight * 2 + badge.lineHeight + minimumPoster) 2
            else 1
        if (title.minLines != lines) title.minLines = lines
        if (title.maxLines != lines) title.maxLines = lines
        badge.visibility =
            if (available >= padding + title.lineHeight * lines + badge.lineHeight + minimumPoster)
                VISIBLE
            else GONE
        val reserve =
            padding +
                title.lineHeight * lines +
                if (badge.visibility == VISIBLE) badge.lineHeight else 0
        val height =
            minOf(
                preferredHeight,
                (available - reserve).coerceAtLeast((32 * resources.displayMetrics.density).toInt()),
            )
        if (poster.layoutParams.height != height)
            poster.layoutParams = poster.layoutParams.apply { this.height = height }
    }
}
