// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 找剧页面的界面辅助组件；与遥控器三栏搜索入口配合使用。

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.*
import com.hongguotv.core.*

/** Server-defined filter groups; each row selects one condition and rows combine on the server. */
internal class DiscoveryScreen(
    context: Context,
    private val state: DiscoveryBrowserState,
    private val upper: View,
    private val cover: (ImageView, String) -> Unit,
    private val open: (Series) -> Unit,
    private val more: (Series, View) -> Unit,
    private val fetch:
        (DiscoverySelection, DiscoveryCursor?, (FilteredDiscoveryPage?, Throwable?) -> Unit) -> Unit,
) : LinearLayout(context) {
    private val types = LinearLayout(context)
    private val groups = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll =
        ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(groups)
        }
    private val summary =
        TextView(context).apply {
            textSize = 12f
            setTextColor(TvStyle.muted)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    private val controls = linkedMapOf<Pair<String, String>, TextView>()
    private val strips = linkedMapOf<String, HorizontalScrollView>()
    private val toggle =
        button("收起筛选") {
            expand(!state.expanded)
            toggleFocus()
        }
    private val reset = button("重置") { change(DiscoverySelection()) }
    private val refresh = button("刷新") { request(null) }
    private val browser: ContinuousCatalogView
    private var filterDialog: AlertDialog? = null
    private var loading = false
    private var closed = false
    private var headerOffset = 0
    private var browseStep = state.browseStep
    private var restoring = true
    private var resetting = false
    private var serial = 0
    var navigationChanged: (() -> Unit)? = null

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    init {
        val restoredOffset = state.headerOffset
        orientation = VERTICAL
        val bar = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        bar.addView(types, LayoutParams(0, -2, 1f))
        bar.addView(toggle)
        bar.addView(reset)
        bar.addView(refresh)
        addView(bar)
        addView(scroll, LayoutParams(-1, dp(200)))
        addView(summary)
        browser =
            ContinuousCatalogView(
                context,
                6,
                toggle,
                { item, recycled ->
                    (recycled as? CatalogPosterCard ?: CatalogPosterCard(context)).apply {
                        show(item)
                        cover(image, item.cover)
                    }
                },
                { item -> state.position = item.id to 0 },
                open,
                more,
                { if (!loading) request(state.next) },
            )
        browser.onBrowsePosition = { position -> updateHeaderOffset(position) }
        browser.onGridFocus = { focused ->
            if (focused) {
                resetting = false
                updateHeaderOffset()
            } else if (resetting || browser.grid.selectedItemPosition < 6 || !hasFocus()) {
                resetHeaderOffset()
            }
        }
        browser.useRemoteNavigation()
        addView(browser, LayoutParams(-1, 0, 1f))
        renderFilters()
        showItems()
        expand(state.expanded)
        headerOffset = restoredOffset
        post {
            if (!closed) {
                scroll.scrollTo(0, state.filterVerticalOffset)
                restoring = false
                requestLayout()
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        groups.measure(
            widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height =
            minOf(
                groups.measuredHeight,
                minOf(dp(260), (View.MeasureSpec.getSize(heightMeasureSpec) * .48f).toInt()),
            )
        if (scroll.layoutParams.height != height)
            scroll.layoutParams = scroll.layoutParams.apply { this.height = height }
        val viewport = View.MeasureSpec.getSize(heightMeasureSpec)
        super.onMeasure(
            widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(viewport + headerOffset, View.MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(measuredWidth, viewport)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b + headerOffset)
        scrollTo(0, headerOffset)
    }

    private fun resetHeaderOffset() {
        val changed = headerOffset != 0
        headerOffset = 0
        state.headerOffset = 0
        if (scrollY != 0) scrollTo(0, 0)
        if (changed) requestLayout()
    }

    private fun updateHeaderOffset(
        position: Int = browser.grid.selectedItemPosition.coerceAtLeast(0)
    ) {
        if (restoring) return
        if (resetting) {
            if (position > 0) resetting = false else return
        }
        val header = getChildAt(0).height + scroll.height + summary.height
        if (header <= 0) return
        val cardHeight = browser.grid.getChildAt(0)?.height ?: 0
        if (browseStep == 0 && cardHeight > 0) browseStep = cardHeight + dp(10)
        val step = if (browseStep > 0) browseStep else dp(180) + dp(10)
        val row = position / 6
        val offset =
            if (!state.expanded) 0
            else if (row > 0) minOf(header, row * step)
            else if (browser.grid.hasFocus() && cardHeight > 0 && height > 0) {
                val nonGrid = (browser.height - browser.grid.height).coerceAtLeast(0)
                val baseGrid = height - header - nonGrid
                (cardHeight + dp(6) - baseGrid).coerceIn(0, header)
            } else 0
        if (headerOffset != offset) {
            headerOffset = offset
            if (offset == 0 && scrollY != 0) scrollTo(0, 0)
            requestLayout()
        }
    }

    override fun requestChildFocus(child: View, focused: View) {
        if (child !== browser) resetHeaderOffset()
        super.requestChildFocus(child, focused)
    }

    private fun button(label: String, chosen: () -> Boolean = { false }, action: () -> Unit) =
        TextView(context).apply {
            id = View.generateViewId()
            text = label
            textSize = 12f
            includeFontPadding = false
            maxLines = 1
            isFocusable = true
            isClickable = true
            setPadding(dp(8), dp(5), dp(8), dp(5))
            minHeight = dp(30)
            fun paint() {
                val selected = chosen()
                isSelected = selected
                setTextColor(if (selected) TvStyle.accent else TvStyle.text)
                background = TvStyle.card(context, hasFocus(), selected)
            }
            setOnFocusChangeListener { _, _ -> paint() }
            setOnClickListener { action() }
            paint()
        }

    private fun toggleFocus() {
        toggle.requestFocus()
    }

    private fun expand(value: Boolean) {
        headerOffset = 0
        scrollTo(0, 0)
        requestLayout()
        state.expanded = value
        scroll.visibility = if (value) VISIBLE else GONE
        toggle.text = if (value) "收起筛选" else "展开筛选"
        val target =
            if (value)
                controls.entries.firstOrNull { it.key.first != "genre" }?.value?.id
                    ?: browser.grid.id
            else browser.grid.id
        (types.getChildAt(0) as? LinearLayout)?.let { row ->
            for (i in 0 until row.childCount) row.getChildAt(i).nextFocusDownId = target
        }
        controls.filterKeys { it.first == "genre" }.values.forEach { it.nextFocusDownId = target }
        listOf(toggle, reset, refresh).forEach { it.nextFocusDownId = target }
    }

    private fun renderFilters() {
        val focused = controls.entries.firstOrNull { it.value.hasFocus() }?.key
        controls.clear()
        strips.clear()
        types.removeAllViews()
        groups.removeAllViews()
        val available = state.groups.ifEmpty { listOf(DiscoveryGroup("genre", "类型", emptyList())) }
        available.forEach { group ->
            val row = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            if (group.type != "genre")
                row.addView(
                    TextView(context).apply {
                        text = group.label
                        textSize = 12f
                        setTextColor(TvStyle.muted)
                    },
                    LayoutParams(dp(40), -2),
                )
            val choices = LinearLayout(context)
            val all = DiscoveryFilter("", "全部")
            val options = listOf(all) + group.options
            val budget = (resources.displayMetrics.widthPixels - dp(320)).coerceAtLeast(dp(180))
            var used = 0
            val visibleOptions = options.takeWhile { option ->
                val probe = button(option.label) {}
                probe.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
                used += probe.measuredWidth + dp(8)
                used <= budget
            }.toMutableList()
            val overflow = visibleOptions.size < options.size && group.type != "genre"
            if (!overflow) { visibleOptions.clear(); visibleOptions.addAll(options) }
            val current = state.selection.values[group.type].orEmpty()
            options.firstOrNull { it.id == current }?.let { selected ->
                if (overflow && selected !in visibleOptions) {
                    if (visibleOptions.size > 1) visibleOptions.removeAt(visibleOptions.lastIndex)
                    visibleOptions.add(selected)
                }
            }
            visibleOptions.forEach { option ->
                val chip =
                    button(
                            if (group.type == "genre" && option.id.isEmpty()) "全部类型"
                            else option.label,
                            { state.selection.values[group.type].orEmpty() == option.id },
                        ) {
                            if (state.selection.values[group.type].orEmpty() != option.id)
                                change(
                                    state.selection.choose(group.type, option.id),
                                    group.type to option.id,
                                )
                        }
                        .apply {
                            if (group.type != "genre") {
                                minHeight = dp(26)
                                setPadding(dp(8), dp(3), dp(8), dp(3))
                            }
                        }
                controls[group.type to option.id] = chip
                choices.addView(chip, LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            }
            val strip =
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    isFocusable = false
                    addView(choices)
                }
            strips[group.type] = strip
            strip.post {
                if (!closed) {
                    val selected =
                        controls[group.type to state.selection.values[group.type].orEmpty()]
                    var offset = state.filterOffsets[group.type] ?: 0
                    if (
                        selected != null &&
                            (selected.left < offset || selected.right > offset + strip.width)
                    )
                        offset = (selected.left - strip.width / 3).coerceAtLeast(0)
                    strip.scrollTo(offset, 0)
                }
            }
            row.addView(strip, LayoutParams(0, -2, 1f))
            if (overflow) {
                val expand = button("全部 · ${group.options.size} 项 ›") { showAllFilters(group) }
                controls[group.type to "__all_options__"] = expand
                row.addView(expand, LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            }
            if (group.type == "genre") types.addView(row, LayoutParams(-1, -2))
            else groups.addView(row, LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
        }
        val rows =
            available.map { group ->
                controls.filterKeys { it.first == group.type }.values.toList()
            }
        rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { index, chip ->
                chip.nextFocusLeftId = row.getOrNull(index - 1)?.id ?: chip.id
                chip.nextFocusRightId =
                    row.getOrNull(index + 1)?.id ?: if (rowIndex == 0) toggle.id else chip.id
                chip.nextFocusUpId =
                    rows
                        .getOrNull(rowIndex - 1)
                        ?.let { previous ->
                            previous.getOrElse(index.coerceAtMost(previous.lastIndex)) { entry() }
                        }
                        ?.id ?: upper.id
                chip.nextFocusDownId =
                    rows
                        .getOrNull(rowIndex + 1)
                        ?.let { next ->
                            next.getOrElse(index.coerceAtMost(next.lastIndex)) { toggle }
                        }
                        ?.id ?: browser.grid.id
            }
        }
        listOf(toggle, reset, refresh).forEach { it.nextFocusUpId = upper.id }
        toggle.nextFocusLeftId = rows.firstOrNull()?.lastOrNull()?.id ?: upper.id
        toggle.nextFocusRightId = reset.id
        reset.nextFocusLeftId = toggle.id
        reset.nextFocusRightId = refresh.id
        refresh.nextFocusLeftId = reset.id
        expand(state.expanded)
        focused?.let { controls[it]?.requestFocus() }
        navigationChanged?.invoke()
    }

    private fun showAllFilters(group: DiscoveryGroup) {
        filterDialog?.dismiss()
        val options = listOf(DiscoveryFilter("", "全部")) + group.options
        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = TvVisual.panel(context, modal = true)
        }
        panel.addView(TextView(context).apply {
            text = "${group.label} · 全部选项"
            textSize = 20f
            setTextColor(TvStyle.text)
            setPadding(0, 0, 0, dp(16))
        })
        val grid = GridLayout(context).apply { columnCount = 4 }
        val cells = options.map { option ->
            button(option.label, { state.selection.values[group.type].orEmpty() == option.id }) {
                filterDialog?.dismiss()
                if (!closed && state.selection.values[group.type].orEmpty() != option.id)
                    change(state.selection.choose(group.type, option.id), group.type to option.id)
            }.also { cell ->
                cell.gravity = android.view.Gravity.CENTER
                grid.addView(cell, GridLayout.LayoutParams().apply {
                    width = dp(140)
                    height = dp(42)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }
        }
        cells.forEachIndexed { index, cell ->
            cell.nextFocusLeftId = cells.getOrNull(index - 1)?.takeIf { index % 4 != 0 }?.id ?: cell.id
            cell.nextFocusRightId = cells.getOrNull(index + 1)?.takeIf { index % 4 != 3 }?.id ?: cell.id
            cell.nextFocusUpId = cells.getOrNull(index - 4)?.id ?: cell.id
            cell.nextFocusDownId = cells.getOrNull(index + 4)?.id ?: cell.id
        }
        panel.addView(ScrollView(context).apply { addView(grid) }, LayoutParams(-1, -2))
        val dialog = AlertDialog.Builder(context).setView(panel).create()
        filterDialog = dialog
        dialog.setOnDismissListener {
            if (filterDialog === dialog) filterDialog = null
            if (!closed) controls[group.type to "__all_options__"]?.requestFocus()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(dp(640), minOf(dp(420), (resources.displayMetrics.heightPixels * .85f).toInt()))
        cells[options.indexOfFirst { it.id == state.selection.values[group.type].orEmpty() }.coerceAtLeast(0)].requestFocus()
    }

    private fun showItems() {
        browser.show(
            state.items,
            state.next != null,
            state.position?.let { ContinuousCatalogView.Position(it.first, it.second) },
        )
        browser.visibility = if (state.loaded) VISIBLE else INVISIBLE
        summary.text = selectionSummary()
    }

    private fun selectionSummary(): String {
        val selected =
            state.selection.values
                .map { (type, id) ->
                    val group = state.groups.firstOrNull { it.type == type }
                    "${group?.label ?: DiscoverySelection.labels[type]}：${group?.options?.firstOrNull { it.id==id }?.label ?: id}"
                }
                .joinToString(" · ")
        return selected.ifEmpty { "全部类型 · 全部条件" }
    }

    private fun change(selection: DiscoverySelection, focus: Pair<String, String>? = null) {
        browseStep = 0
        state.browseStep = 0
        if (focus?.first == "genre" || selection.values.isEmpty()) {
            state.filterOffsets.clear()
            state.filterVerticalOffset = 0
        } else focus?.let { state.filterOffsets.remove(it.first) }
        state.selection = selection
        state.items = emptyList()
        state.next = null
        state.loaded = false
        state.position = null
        state.expanded = true
        if (focus?.first == "genre" || selection.values.isEmpty())
            state.groups = state.groups.filter { it.type == "genre" }
        showItems()
        renderFilters()
        focus?.let { controls[it]?.requestFocus() }
        request(null)
    }

    private fun request(cursor: DiscoveryCursor?) {
        val ticket = ++serial
        loading = true
        refresh.text = "读取中…"
        summary.text = if (cursor == null) "正在按当前条件加载…" else "正在继续加载…"
        fetch(state.selection, cursor) { result, error ->
            if (closed || ticket != serial) return@fetch
            loading = false
            refresh.text = "刷新"
            if (result != null) {
                if (cursor == null) {
                    state.items = result.items
                    state.groups = result.groups
                    state.loaded = true
                    state.next = result.next
                    renderFilters()
                    showItems()
                } else {
                    val seen = state.items.mapTo(HashSet()) { it.id }
                    state.items = state.items + result.items.filter { seen.add(it.id) }
                    state.next = result.next
                    browser.append(result.items, result.next != null)
                    summary.text = selectionSummary()
                }
            } else if (cursor != null) {
                browser.fail(error)
                summary.text = "加载失败，已保留列表 · ${selectionSummary()}"
            } else {
                summary.text = "分类读取失败，请按刷新重试"
            }
        }
    }

    fun entry(): View = controls["genre" to state.selection.values["genre"].orEmpty()] ?: toggle

    fun backToTop() {
        resetting = true
        headerOffset = 0
        state.headerOffset = 0
        state.filterVerticalOffset = 0
        scroll.scrollTo(0, 0)
        scrollTo(0, 0)
        browser.resetToTop()
        state.position = state.items.firstOrNull()?.let { it.id to 0 }
        requestLayout()
        post {
            if (!closed && !browser.grid.hasFocus()) {
                resetting = false
                resetHeaderOffset()
            }
        }
    }

    fun start(focus: Boolean) {
        if (focus) {
            if (state.position != null && state.items.isNotEmpty()) browser.enter()
            else entry().requestFocus()
        }
        if (!state.loaded) request(null)
    }

    fun close() {
        if (closed) return
        state.headerOffset = headerOffset
        state.browseStep = browseStep
        state.filterVerticalOffset = scroll.scrollY
        strips.forEach { (type, strip) -> state.filterOffsets[type] = strip.scrollX }
        closed = true
        filterDialog?.dismiss()
        filterDialog = null
        serial++
        browser.capture()?.let { state.position = it.id to it.top }
        browser.close()
    }
}
