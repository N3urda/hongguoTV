// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 排行榜界面组件：展示榜单，转发作品点击和加载请求。

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import com.hongguotv.core.*

/** Recycled poster grid: one focus target per work, stable source ranks after filtering. */
internal class RankingScreen(
    private val activity: Activity,
    private val state: RankBrowserState,
    private val nav: List<View>,
    private val tools: TvTools,
    private val cover: (ImageView, String) -> Unit,
    private val open: (Series) -> Unit,
    private val more: (Series, View) -> Unit,
    private val load: (RankBoard, ((RankSnapshot?, String?) -> Unit)) -> Unit,
) : LinearLayout(activity) {
    private val controls = mutableListOf<TextView>()
    private val heading = label("热播排行榜", 22f)
    private val subtitle = label("", 12f, TvStyle.muted)
    private val body = LinearLayout(activity).apply { orientation = VERTICAL }
    private var filterButton: TextView
    private var refreshButton: TextView
    private var browser: ContinuousCatalogView? = null
    private var headerOffset = 0
    private var browseStep = 0
    private var resetting = false
    private var closed = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float = 16f, color: Int = TvStyle.text) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
        }

    private fun params(w: Int = -1, h: Int = -2) = LayoutParams(w, h)

    private fun row() = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }

    private fun button(value: String, action: () -> Unit) =
        label(value, 14f).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            minHeight = dp(36)
            fun paint() {
                setTextColor(if (isSelected) TvStyle.accent else TvStyle.text)
                background = TvStyle.card(activity, hasFocus(), isSelected)
            }
            setOnFocusChangeListener { _, _ -> paint() }
            setOnClickListener { action() }
            paint()
        }

    init {
        orientation = VERTICAL
        val title = row().apply { setPadding(0, dp(2), 0, dp(4)) }
        heading.typeface = Typeface.DEFAULT_BOLD
        title.addView(heading, params(0).apply { weight = 1f })
        title.addView(label("方向键选剧  ·  确定看详情  ·  长按更多", 12f, TvStyle.muted))
        addView(title)
        val bar = row()
        RankBoard.entries.forEach { board ->
            val control =
                button(board.label) {
                        // 每榜独立异步：TTL 内的缓存直接渲染零请求；未缓存或过期则后台
                        // 拉取该榜，其他榜单的在途请求不受影响，切换不会让任何读取重来。
                        if (state.board == board) return@button
                        state.select(board)
                        markControls()
                        render()
                        maybeRefresh(board)
                    }
                    .apply {
                        isSelected = state.board == board
                        nextFocusUpId = nav[2].id
                    }
            bar.addView(
                control,
                params().apply {
                    width = dp(92)
                    rightMargin = dp(8)
                },
            )
            controls += control
        }
        bar.addView(View(activity), params(0, 1).apply { weight = 1f })
        filterButton = button("筛选题材") { chooseTopic() }.apply { nextFocusUpId = nav[2].id }
        refreshButton = button("刷新") { maybeRefresh(state.board, force = true) }
            .apply { nextFocusUpId = nav[2].id }
        bar.addView(filterButton, params(-2))
        bar.addView(refreshButton, params(-2).apply { leftMargin = dp(8) })
        controls += filterButton
        controls += refreshButton
        controls.forEachIndexed { i, v ->
            v.nextFocusLeftId = controls.getOrNull(i - 1)?.id ?: v.id
            v.nextFocusRightId = controls.getOrNull(i + 1)?.id ?: v.id
        }
        nav.forEach { it.nextFocusDownId = controls[state.board.ordinal].id }
        addView(bar)
        subtitle.setPadding(0, dp(4), 0, dp(5))
        subtitle.maxLines = 2
        addView(subtitle)
        addView(body, params(-1, 0).apply { weight = 1f })
        markControls()
        render()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val viewport = MeasureSpec.getSize(heightMeasureSpec)
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(viewport + headerOffset, MeasureSpec.EXACTLY),
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
        if (scrollY != 0) scrollTo(0, 0)
        if (changed) requestLayout()
    }

    private fun updateHeaderOffset(
        position: Int = browser?.grid?.selectedItemPosition?.coerceAtLeast(0) ?: 0
    ) {
        val list = browser ?: return
        if (resetting) {
            if (position > 0) resetting = false else return
        }
        val header = getChildAt(0).height + getChildAt(1).height + subtitle.height
        if (header <= 0) return
        val cardHeight = list.grid.getChildAt(0)?.height ?: 0
        if (browseStep == 0 && cardHeight > 0) browseStep = cardHeight + dp(10)
        val step = if (browseStep > 0) browseStep else dp(180) + dp(10)
        val row = position / 6
        val offset =
            if (row > 0) minOf(header, row * step)
            else if (list.grid.hasFocus() && cardHeight > 0 && height > 0) {
                val nonGrid = (list.height - list.grid.height).coerceAtLeast(0)
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
        if (child !== body) resetHeaderOffset()
        super.requestChildFocus(child, focused)
    }

    private fun markControls() {
        RankBoard.entries.forEachIndexed { i, b ->
            controls[i].isSelected = state.board == b
            controls[i].text = b.label + if (state.isRefreshing(b)) " …" else ""
            controls[i].setTextColor(if (state.board == b) TvStyle.accent else TvStyle.text)
            controls[i].background = TvStyle.card(activity, controls[i].hasFocus(), state.board == b)
        }
        refreshButton.text = if (state.isRefreshing(state.board)) "读取中…" else "刷新"
        heading.text = "${state.board.label}热播榜"
    }

    fun start(force: Boolean, focusNav: Boolean) {
        if (focusNav) nav[2].requestFocus() else controls[state.board.ordinal].requestFocus()
        // 进入页面时 TTL 内的缓存直接展示；是否续期由状态机判断，不做模态等待。
        maybeRefresh(state.board, force = force && !state.isFresh(state.board))
        if (!state.isRefreshing(state.board) && !focusNav && state.selectedId.isNotEmpty()) {
            browser?.enter()
            post { if (!closed) updateHeaderOffset() }
        } else {
            resetHeaderOffset()
        }
    }

    fun backToTop() {
        resetting = true
        headerOffset = 0
        scrollTo(0, 0)
        browser?.resetToTop()
        state.selectedId =
            state.snapshots[state.board]?.filtered(state.topic)?.firstOrNull()?.id.orEmpty()
        requestLayout()
        post {
            if (!closed && browser?.grid?.hasFocus() != true) {
                resetting = false
                resetHeaderOffset()
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        browser?.close()
        browser = null
    }

    /** 单一入口：按 TTL 决定是否拉取当前/目标榜单；同榜在途自动去重，跨榜并行。 */
    private fun maybeRefresh(target: RankBoard, force: Boolean = false) {
        if (!force && state.isFresh(target)) return
        if (!state.beginRefresh(target)) {
            markControls()
            return
        }
        markControls()
        if (target == state.board) {
            refreshButton.text = "读取中…"
            // 旧榜单继续展示（若有），render 会为静默续期补上状态提示。
            render()
            if (state.snapshots[target] == null)
                subtitle.text = "正在读取完整榜单，题材筛选将覆盖全部已返回作品…"
        }
        load(target) { snapshot, error ->
            state.endRefresh(target, snapshot)
            markControls()
            if (target != state.board) return@load // 其他榜的后台完成只更新按钮状态
            refreshButton.text = "刷新"
            if (snapshot != null) {
                if (state.topic !in snapshot.topics) state.topic = ""
                render()
            } else {
                render()
                if (state.snapshots[target] == null) {
                    body.removeAllViews()
                    body.addView(
                        label("暂时无法读取榜单，请按右上方刷新重试", 17f, TvStyle.muted).apply {
                            setPadding(dp(16), dp(30), 0, 0)
                        }
                    )
                }
                subtitle.text =
                    if (state.snapshots[target] != null) "本次更新失败，保留上次榜单 · ${error.orEmpty()}"
                    else "读取失败 · ${error.orEmpty()} · 按右上方刷新重试"
            }
        }
    }

    private fun chooseTopic() {
        val snapshot = state.snapshots[state.board] ?: return
        val values = listOf("") + snapshot.topics
        val labels =
            values.map {
                if (it.isEmpty()) "全部题材 · ${snapshot.items.size} 部"
                else "$it · ${snapshot.filtered(it).size} 部"
            }
        tools.chooseGrid(
            "${state.board.label}热播榜 · 选择题材",
            labels,
            values.indexOf(state.topic),
            filterButton,
        ) { i ->
            state.filter(values[i])
            render()
        }
    }

    private fun render() {
        val enterRows = browser?.grid?.hasFocus() == true
        browser?.close()
        body.removeAllViews()
        if (!enterRows) resetHeaderOffset()
        val snapshot = state.snapshots[state.board]
        filterButton.text = if (state.topic.isBlank()) "筛选题材" else "题材：${state.topic}"
        filterButton.isEnabled = snapshot != null
        val all = snapshot?.filtered(state.topic).orEmpty()
        subtitle.text =
            snapshot?.let {
                "${it.ranking.updatedText.ifBlank { "来源：红果官网热播榜" }}  ·  ${it.items.size} 部${if(state.topic.isNotEmpty()) "  ·  ${state.topic} ${all.size} 部（保留原榜名次）" else "  ·  筛选覆盖本榜全部作品"}"
            } ?: "选择榜单，发现下一部好剧"
        // 静默续期：旧榜单继续展示，只在状态行末尾提示后台更新。
        if (snapshot != null && state.isRefreshing(state.board))
            subtitle.text = subtitle.text.toString() + "  ·  正在后台更新…"
        if (all.isEmpty()) {
            browser = null
            resetHeaderOffset()
            body.addView(
                label(
                    if (snapshot == null) "正在准备榜单，请稍候…" else "本榜暂无符合条件的作品，可切换题材",
                    17f,
                    TvStyle.muted,
                )
            )
            controls.forEach { it.nextFocusDownId = it.id }
            return
        }
        val saved = state.selectedId
        lateinit var list: ContinuousCatalogView
        list =
            ContinuousCatalogView(
                activity,
                6,
                controls[state.board.ordinal],
                { item, recycled ->
                    val card = recycled as? CatalogPosterCard ?: CatalogPosterCard(activity)
                    card.show(item, snapshot?.ranking?.positions?.get(item.id))
                    cover(card.image, item.cover)
                    card
                },
                { state.selectedId = it.id },
                {
                    state.selectedId = it.id
                    open(it)
                },
                { item, anchor ->
                    state.selectedId = item.id
                    more(item, anchor)
                },
                {},
            )
        list.onBrowsePosition = { position -> updateHeaderOffset(position) }
        list.onGridFocus = { focused ->
            if (focused) {
                resetting = false
                updateHeaderOffset()
            } else if (resetting || list.grid.selectedItemPosition < 6 || !hasFocus()) {
                resetHeaderOffset()
            }
        }
        browser = list
        body.addView(list, params(-1, 0).apply { weight = 1f })
        list.show(
            all,
            false,
            saved.takeIf { it.isNotBlank() }?.let { ContinuousCatalogView.Position(it, 0) },
        )
        controls.forEach { it.nextFocusDownId = list.grid.id }
        if (enterRows) {
            list.enter()
            post { if (!closed) updateHeaderOffset() }
        }
    }
}
