// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hongguotv.core.DiscoveryCursor
import com.hongguotv.core.DiscoveryFilter
import com.hongguotv.core.DiscoveryGroup
import com.hongguotv.core.DiscoverySelection
import com.hongguotv.core.FilteredDiscoveryPage
import com.hongguotv.core.Series

internal class PhoneDiscovery(
    private val activity: Activity,
    private val state: State,
    private val scroller: ScrollView,
    initialScroll: Int,
    private val fetch: (DiscoverySelection, DiscoveryCursor?, (FilteredDiscoveryPage?, Throwable?) -> Unit) -> Unit,
    private val button: (String, Boolean, () -> Unit) -> TextView,
    private val renderCards: (LinearLayout, List<Series>) -> Unit,
) : LinearLayout(activity) {
    class State {
        var selection = DiscoverySelection()
        var groups = emptyList<DiscoveryGroup>()
        var items = emptyList<Series>()
        var next: DiscoveryCursor? = null
        var loaded = false
        var showFilters = true
        var openGroup: String? = null
    }

    private var serial = 0
    private var loading = false
    private var pageFailed = false
    private var restoreOffset = initialScroll
    private val types = column()
    private val groups = column()
    private val summary = text(13f)
    private val status = text(14f).apply { accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE }
    private val results = column()
    private val toggle = button("", false) {
        state.showFilters = !state.showFilters
        renderFilters()
    }
    private val reset = button("重置", false) { change(DiscoverySelection(), resetGroups = true) }
    private val refresh = button("刷新", false) { request(null) }
    private val more = button("加载更多", false) { state.next?.let(::request) }
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        val content = scroller.getChildAt(0) ?: return@OnScrollChangedListener
        val bottom = scroller.scrollY + scroller.height
        if (!loading && !pageFailed && scroller.scrollY > 0 && state.items.isNotEmpty()
            && content.height - bottom < dp(200)) {
            state.next?.let(::request)
        }
    }

    init {
        orientation = VERTICAL
        addView(types)
        addView(row().apply {
            addView(toggle, LayoutParams(0, dp(48), 1f))
            addView(reset, LayoutParams(-2, dp(48)).apply { marginStart = dp(6) })
            addView(refresh, LayoutParams(-2, dp(48)).apply { marginStart = dp(6) })
        })
        addView(groups)
        addView(summary)
        addView(status)
        addView(results)
        addView(more, LayoutParams(-1, dp(48)))
    }

    fun start() {
        renderFilters()
        renderResults()
        if (!state.loaded) request(null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scroller.viewTreeObserver.addOnScrollChangedListener(scrollListener)
    }

    override fun onDetachedFromWindow() {
        scroller.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        serial++
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width != oldWidth) post {
            if (isAttachedToWindow) {
                renderFilters()
                restoreScroll()
            }
        }
    }

    private fun renderFilters() {
        val otherGroups = state.groups.filter { it.type != "genre" && it.options.isNotEmpty() }
        toggle.visibility = if (otherGroups.isEmpty()) GONE else VISIBLE
        toggle.text = if (state.showFilters) "收起筛选" else "展开筛选"
        reset.isEnabled = state.selection.values.isNotEmpty()
        reset.alpha = if (reset.isEnabled) 1f else .45f
        groups.visibility = if (state.showFilters) VISIBLE else GONE
        types.removeAllViews()
        groups.removeAllViews()
        if (width > 0) {
            state.groups.firstOrNull { it.type == "genre" }?.let { types.addView(filterRow(it)) }
            if (state.showFilters) otherGroups.forEach { groups.addView(filterRow(it)) }
        }
        summary.text = state.groups.mapNotNull { group ->
            val id = state.selection.values[group.type] ?: return@mapNotNull null
            group.options.firstOrNull { it.id == id }?.let { "${group.label} ${it.label}" }
        }.joinToString(" · ")
        summary.visibility = if (summary.text.isEmpty()) GONE else VISIBLE
    }

    private fun filterRow(group: DiscoveryGroup): LinearLayout {
        val genre = group.type == "genre"
        val options = listOf(DiscoveryFilter("", if (genre) "全部类型" else "全部")) + group.options
        val current = state.selection.values[group.type].orEmpty()
        val selected = options.firstOrNull { it.id == current }
        val gap = dp(6)
        val labelWidth = if (genre) 0 else dp(36)
        val sizer = button("", false) {}.apply { layoutParams = LayoutParams(-2, -2) }
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val widths = options.associateWith { option ->
            sizer.text = option.label
            sizer.measure(unspecified, unspecified)
            sizer.measuredWidth.coerceAtLeast(dp(48))
        }
        val available = width - labelWidth
        val hasMore = widths.values.sum() + gap * (options.size - 1) > available
        val expanded = hasMore && state.openGroup == group.type
        val lineWidth = (available - if (hasMore) dp(48) else 0).coerceAtLeast(1)
        fun naturalWidth(option: DiscoveryFilter) = widths.getValue(option).coerceAtMost(lineWidth)
        var used = 0
        val firstLine = options.takeWhile {
            used += naturalWidth(it) + if (used > 0) gap else 0
            used <= lineWidth
        }
        val ordered = if (!expanded && selected != null && selected !in firstLine)
            listOf(selected) + options.filterNot { it == selected } else options
        val choices = column()
        var line = row().also { choices.addView(it) }
        used = 0
        for (option in ordered) {
            val chipWidth = naturalWidth(option)
            if (used > 0 && used + gap + chipWidth > lineWidth) {
                if (!expanded) break
                line = row().also {
                    choices.addView(it, LayoutParams(-1, -2).apply { topMargin = gap })
                }
                used = 0
            }
            val pressed = option.id == current
            val chip = button(option.label, pressed) {
                if (!pressed) change(state.selection.choose(group.type, option.id), resetGroups = genre)
            }.apply {
                isSelected = pressed
                minHeight = dp(48)
            }
            val margin = if (used > 0) gap else 0
            line.addView(chip, LayoutParams(chipWidth, -2).apply { marginStart = margin })
            used += chipWidth + margin
        }
        return row().apply {
            gravity = Gravity.TOP
            setPadding(0, 0, 0, gap)
            if (!genre) addView(TextView(activity).apply {
                text = group.label
                textSize = 13f
                setTextColor(TvStyle.muted)
                gravity = Gravity.CENTER_VERTICAL
            }, LayoutParams(labelWidth, dp(48)))
            addView(choices, LayoutParams(0, -2, 1f))
            if (hasMore) addView(expandButton(group, expanded), LayoutParams(dp(48), dp(48)))
        }
    }

    private fun expandButton(group: DiscoveryGroup, expanded: Boolean) = ImageButton(activity).apply {
        contentDescription = "${if (expanded) "收起" else "展开"}${group.label}选项"
        tag = group.type
        isSelected = expanded
        scaleType = ImageView.ScaleType.CENTER
        background = RippleDrawable(ColorStateList.valueOf(0x24ffffff), null, ColorDrawable(Color.WHITE))
        setImageDrawable(DiscoveryChevron(dp(18), if (expanded) TvStyle.accent else TvStyle.muted, expanded))
        setOnClickListener {
            val focused = hasFocus()
            state.openGroup = if (expanded) null else group.type
            renderFilters()
            if (focused) this@PhoneDiscovery.findViewWithTag<View>(group.type)?.requestFocus()
        }
    }

    private fun change(selection: DiscoverySelection, resetGroups: Boolean) {
        restoreOffset = 0
        state.selection = selection
        state.items = emptyList()
        state.next = null
        state.loaded = false
        state.showFilters = true
        state.openGroup = null
        if (resetGroups) state.groups = state.groups.filter { it.type == "genre" }
        renderFilters()
        renderResults()
        scroller.scrollTo(0, 0)
        request(null)
    }

    private fun request(cursor: DiscoveryCursor?) {
        if (cursor != null && loading) return
        val ticket = ++serial
        loading = true
        pageFailed = false
        setStatus(if (cursor == null) "正在按当前条件加载…" else "正在加载更多…")
        updateActions()
        fetch(state.selection, cursor) { page, _ ->
            if (!isAttachedToWindow || ticket != serial) return@fetch
            loading = false
            if (page == null) {
                pageFailed = cursor != null
                setStatus(if (pageFailed) "加载更多失败，已保留现有结果" else "暂时无法加载，请刷新重试")
                updateActions()
                return@fetch
            }
            state.next = page.next
            if (cursor == null) {
                state.groups = page.groups
                state.items = page.items
                state.loaded = true
                renderFilters()
            } else {
                val seen = state.items.mapTo(HashSet()) { it.id }
                state.items = state.items + page.items.filter { seen.add(it.id) }
            }
            setStatus("")
            renderResults()
            restoreScroll()
        }
    }

    private fun restoreScroll() {
        if (restoreOffset <= 0 || width <= 0 || !state.loaded) return
        val offset = restoreOffset
        restoreOffset = 0
        scroller.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                scroller.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (isAttachedToWindow) scroller.scrollTo(0, offset)
            }
        })
    }

    private fun renderResults() {
        results.removeAllViews()
        if (state.loaded) {
            if (state.items.isEmpty()) results.addView(text(15f).apply { text = "没有符合当前条件的剧集" })
            else renderCards(results, state.items)
        }
        updateActions()
    }

    private fun updateActions() {
        refresh.isEnabled = !loading
        more.visibility = if (state.next == null) GONE else VISIBLE
        more.isEnabled = !loading
        more.text = if (loading) "加载中…" else if (pageFailed) "重试加载更多" else "加载更多"
    }

    private fun setStatus(message: String) {
        status.text = message
        status.visibility = if (message.isEmpty()) GONE else VISIBLE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun column() = LinearLayout(activity).apply { orientation = VERTICAL }
    private fun row() = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun text(size: Float) = TextView(activity).apply {
        textSize = size
        setTextColor(TvStyle.muted)
        setPadding(0, dp(8), 0, dp(8))
    }
}

private class DiscoveryChevron(private val size: Int, color: Int, private val up: Boolean) : Drawable() {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = size / 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun draw(canvas: Canvas) {
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val height = size * if (up) -.17f else .17f
        canvas.drawPath(Path().apply {
            moveTo(x - size * .34f, y - height)
            lineTo(x, y + height)
            lineTo(x + size * .34f, y - height)
        }, pen)
    }
    override fun setAlpha(alpha: Int) { pen.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { pen.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
