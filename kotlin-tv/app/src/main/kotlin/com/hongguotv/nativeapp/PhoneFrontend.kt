// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.Drawable
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.AdapterView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hongguotv.core.CatalogPage
import com.hongguotv.core.CloudReservation
import com.hongguotv.core.CloudProgress
import com.hongguotv.core.ContentType
import com.hongguotv.core.Detail
import com.hongguotv.core.DiscoveryCursor
import com.hongguotv.core.DiscoverySelection
import com.hongguotv.core.FilteredDiscoveryPage
import com.hongguotv.core.RankBoard
import com.hongguotv.core.RelatedWork
import com.hongguotv.core.ResumeTarget
import com.hongguotv.core.Series

/** Touch navigation and views for Android handsets. Playback and storage belong to the activity. */
internal class PhoneFrontend(
    private val activity: Activity,
    private val library: Library,
    private val artwork: (ImageView, String) -> Unit,
    private val load: (CatalogRequest, (CatalogPage) -> Unit, (Throwable) -> Unit) -> Unit,
    private val loadDiscovery: (DiscoverySelection, DiscoveryCursor?, (FilteredDiscoveryPage?, Throwable?) -> Unit) -> Unit,
    private val open: (Series, Boolean) -> Unit,
    private val openCloud: (CloudProgress) -> Unit,
    private val openFeed: () -> Unit,
    private val settings: () -> Unit,
    private val cloud: AccountController,
    private val loadRelated: (Series, (List<RelatedWork>) -> Unit, (Throwable) -> Unit) -> Unit,
    private val describeReservation: (CloudReservation) -> String,
) {
    enum class Tab(val label: String, val glyph: Int) {
        HOME("首页", 0), DISCOVER("找剧", 1), SEARCH("搜索", 7), LIBRARY("我的", 3),
    }

    data class CatalogRequest(
        val tab: Tab,
        val query: String,
        val page: Int,
        val board: RankBoard?,
    )

    private var tab = Tab.HOME
    private val discovery = PhoneDiscovery.State()
    private var rank = false
    private var board = RankBoard.ALL
    private var query = ""
    private var draft = ""
    private var libraryTab = 0
    private var reservationOnline = false
    private var reservationResults: LinearLayout? = null
    private var savedResults: LinearLayout? = null
    private var savedSnapshot: Any? = null
    private var savedScope: Int? = null
    private var homeResume: LinearLayout? = null
    private var homeResumeEntry: HistoryEntry? = null
    private var accountFavorite: Pair<String, TextView>? = null
    private var relatedActions: List<Pair<RelatedWork, TextView>> = emptyList()
    private val pages = LinkedHashMap<CatalogRequest, CatalogPage>()
    private val positions = mutableMapOf<Tab, Int>()
    private var scroll: ScrollView? = null
    private var container: FrameLayout? = null
    private var focusSearch = false

    private val ink get() = TvStyle.text
    private val muted get() = TvStyle.muted
    private val surface get() = TvStyle.surface
    private val accent get() = TvStyle.accent

    fun start(initial: Int) {
        tab = when (initial) {
            1, 2 -> Tab.DISCOVER
            7 -> Tab.SEARCH
            in 3..5 -> Tab.LIBRARY
            else -> Tab.HOME
        }
        rank = initial == 2
        libraryTab = when (initial) { 3 -> 1; 5 -> 3; else -> 0 }
    }

    fun rememberPosition() {
        scroll?.let { positions[tab] = it.scrollY }
        scroll = null
    }

    fun back(): Boolean {
        when {
            tab == Tab.SEARCH && (draft.isNotEmpty() || query.isNotEmpty()) -> {
                draft = ""
                query = ""
                select(Tab.SEARCH, focus = true)
            }
            tab != Tab.HOME -> select(Tab.HOME)
            else -> return false
        }
        return true
    }

    private fun select(next: Tab, focus: Boolean = false) {
        rememberPosition()
        tab = next
        focusSearch = focus
        container?.let(::showCatalog)
    }

    fun showCatalog(root: FrameLayout) {
        container = root
        val frame = frame(root)
        val heading = row()
        if (tab == Tab.HOME) heading.addView(ImageView(activity).apply {
            setImageResource(R.drawable.brand_mark)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(30), dp(33)).apply { rightMargin = dp(7) })
        val title = when {
            tab == Tab.HOME -> "红果短剧"
            tab == Tab.DISCOVER && rank -> "排行榜"
            else -> tab.label
        }
        heading.addView(label(title, 26f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        when (tab) {
            Tab.HOME -> heading.addView(action("随心看", primary = true, run = openFeed))
            Tab.DISCOVER -> heading.addView(action(if (rank) "返回找剧" else "排行榜") {
                rank = !rank
                positions.remove(tab)
                showCatalog(root)
            })
            Tab.LIBRARY -> heading.addView(action("设置", run = settings))
            else -> Unit
        }
        frame.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val (body, scroller) = scrollBody(frame)
        scroll = scroller
        val outlet = column()
        when (tab) {
            Tab.HOME -> {
                homeResume = column().also(body::addView)
                renderHomeResume()
                body.addView(label("热门短剧", 19f, bold = true))
                space(body, 10)
                body.addView(outlet)
                fill(outlet, scroller)
            }
            Tab.DISCOVER -> {
                if (rank) {
                    body.addView(chips(RankBoard.entries.map { it.label }, board.ordinal) { index ->
                        board = RankBoard.entries[index]
                        positions.remove(tab)
                        showCatalog(root)
                    })
                    space(body, 12)
                    body.addView(outlet)
                    fill(outlet, scroller)
                } else {
                    val screen = PhoneDiscovery(
                        activity, discovery, scroller, positions[tab] ?: 0, loadDiscovery, ::action, ::posterGrid,
                    )
                    body.addView(screen, LinearLayout.LayoutParams(-1, -2))
                    screen.start()
                }
            }
            Tab.SEARCH -> {
                frame.addView(searchBar(), 1)
                body.addView(outlet)
                if (query.isEmpty()) recentSearches(outlet)
                else fill(outlet, scroller)
            }
            Tab.LIBRARY -> renderLibrary(body)
        }
        frame.addView(bottomNavigation())
        if (tab == Tab.LIBRARY || tab == Tab.SEARCH && query.isEmpty()) restore(scroller)
    }

    private fun searchBar(): View {
        val row = row().apply { setPadding(0, 0, 0, dp(12)) }
        val field = EditText(activity).apply {
            textSize = 16f
            contentDescription = "搜索剧名"
            hint = "搜索剧名"
            setTextColor(ink)
            setHintTextColor(muted)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            filters = arrayOf(InputFilter.LengthFilter(80))
            setText(draft)
            setSelectAllOnFocus(true)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = shape(surface, 12)
        }
        fun submit() {
            val value = field.text.toString().trim()
            if (value.isBlank()) {
                field.error = "请输入剧名"
                return
            }
            (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(field.windowToken, 0)
            search(value)
        }
        field.setOnEditorActionListener { _, event, _ ->
            if (event == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { draft = s.toString() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        row.addView(field, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(action("搜索", primary = true, run = ::submit), LinearLayout.LayoutParams(-2, dp(52)).apply { leftMargin = dp(8) })
        if (focusSearch) {
            focusSearch = false
            field.post {
                if (field.isAttachedToWindow) {
                    field.requestFocus()
                    (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        return row
    }

    private fun search(value: String) {
        query = value
        draft = value
        val request = request()
        pages.keys.removeAll { it.copy(page = 1) == request }
        positions.remove(tab)
        library.rememberSearch(value, ContentType.SHORT)
        container?.let(::showCatalog)
    }

    private fun recentSearches(body: LinearLayout) {
        val searches = library.searches().map { it.query }.distinct().take(12)
        if (searches.isEmpty()) {
            body.addView(message("输入剧名查找短剧和漫剧"))
            return
        }
        body.addView(label("最近搜索", 18f, bold = true))
        space(body, 8)
        searches.forEach { value -> body.addView(lineAction(value) { search(value) }) }
        body.addView(action("清除记录") { library.clearSearches(); container?.let(::showCatalog) })
    }

    private fun request(page: Int = 1) = CatalogRequest(
        tab, if (tab == Tab.SEARCH) query else "", page,
        board.takeIf { tab == Tab.DISCOVER && rank },
    )

    private fun fill(outlet: LinearLayout, scroller: ScrollView) {
        val key = request()
        val known = generateSequence(1) { it + 1 }.map { pages[key.copy(page = it)] }.takeWhile { it != null }.filterNotNull().toList()
        if (known.isNotEmpty()) {
            renderResults(outlet, scroller, key, known)
            return
        }
        outlet.addView(message("正在加载剧集…"))
        fetch(key, outlet, { renderResults(outlet, scroller, key, listOf(it)) }) {
            outlet.removeAllViews()
            outlet.addView(message("剧集暂时无法加载"))
            outlet.addView(action("重试") { outlet.removeAllViews(); fill(outlet, scroller) })
        }
    }

    private fun fetch(key: CatalogRequest, outlet: View, done: (CatalogPage) -> Unit, failed: () -> Unit) {
        load(key, { result ->
            pages[key] = result
            while (pages.size > 24) pages.remove(pages.keys.first())
            if (outlet.isAttachedToWindow) done(result)
        }, { if (outlet.isAttachedToWindow) failed() })
    }

    private fun renderResults(outlet: LinearLayout, scroller: ScrollView, key: CatalogRequest, results: List<CatalogPage>) {
        outlet.removeAllViews()
        val items = results.flatMap { it.items }.distinctBy { it.id }
        if (items.isEmpty()) outlet.addView(message("没有找到匹配的剧集"))
        else {
            if (key.tab == Tab.SEARCH) {
                outlet.addView(label("“${key.query}”的搜索结果", 17f, bold = true))
                space(outlet, 8)
                items.forEach { series -> outlet.addView(seriesRow(series, series.tags.ifBlank { series.badge })) }
            } else if (key.board != null) items.forEachIndexed { index, series ->
                outlet.addView(seriesRow(series, "第 ${index + 1} 名"))
            } else posterGrid(outlet, items)
        }
        if (key.tab != Tab.HOME && results.last().hasMore && results.size < 20) {
            lateinit var next: TextView
            next = action("加载更多") {
                val top = scroller.scrollY
                next.isEnabled = false
                next.text = "正在加载…"
                fetch(key.copy(page = results.size + 1), outlet, {
                    positions[tab] = top
                    renderResults(outlet, scroller, key, results + it)
                }, {
                    next.text = "重试加载"
                    next.isEnabled = true
                })
            }
            outlet.addView(next)
        }
        restore(scroller)
    }

    private fun restore(scroller: ScrollView) {
        val top = positions[tab] ?: 0
        if (top > 0) scroller.post { scroller.scrollTo(0, top) }
    }

    private fun renderLibrary(body: LinearLayout) {
        val actions = row()
        actions.addView(action("账户") { cloud.show() })
        body.addView(actions)
        space(body, 10)
        body.addView(chips(listOf("观看历史", "收藏", "稍后看", "预约"), libraryTab) {
            libraryTab = it
            positions.remove(tab)
            container?.let(::showCatalog)
        })
        space(body, 12)
        when (libraryTab) {
            0, 1 -> {
                savedResults = column().also(body::addView)
                renderSaved()
                refreshSavedIfNeeded()
            }
            2 -> library.later().let { if (it.isEmpty()) body.addView(message("还没有稍后看的剧集")) else posterGrid(body, it) }
            3 -> {
                val filters = FrameLayout(activity)
                fun renderFilters() {
                    filters.removeAllViews()
                    filters.addView(chips(listOf("待上线", "已上线"), if (reservationOnline) 1 else 0) {
                        reservationOnline = it == 1
                        renderFilters()
                        refreshAccount()
                    })
                }
                renderFilters()
                body.addView(filters)
                reservationResults = column().also(body::addView)
                refreshAccount()
            }
        }
    }

    private data class HistoryEntry(
        val series: Series,
        val index: Int,
        val position: Long,
        val updatedAt: Long,
        val completed: Boolean,
        val remote: CloudProgress? = null,
    )

    private fun latestHistory(): List<HistoryEntry> {
        val local = library.history().map {
            HistoryEntry(it.series, it.episodeIndex, it.position, it.updatedAt, it.completed)
        }
        val remote = cloud.historyRows().filter { it.updatedAt > 0 }.map {
            HistoryEntry(it.series, it.episodeIndex, it.position, it.updatedAt,
                it.duration > 0 && it.position > 0 && it.duration - it.position <= 2_000, it)
        }
        // Stable sort prefers the local record when both devices have the same timestamp.
        return (local + remote).sortedByDescending { it.updatedAt }.distinctBy { it.series.id }
    }

    private fun renderHomeResume() {
        val outlet = homeResume ?: return
        val entry = latestHistory().firstOrNull()
        if (entry == homeResumeEntry) return
        homeResumeEntry = entry
        outlet.removeAllViews()
        if (entry != null) {
            outlet.addView(label("接着看", 18f, bold = true))
            outlet.addView(historyRow(entry, "继续播放"))
            space(outlet, 22)
        }
    }

    private fun historyRow(item: HistoryEntry, cta: String? = null): View {
        val suffix = if (item.remote != null) " · 账户" else ""
        val progress = if (item.completed) "已看完" else time(item.position)
        val scope = cloud.historyScope
        return seriesRow(item.series, "第 ${item.index + 1} 集 · $progress$suffix", cta, resume = true,
            onOpen = {
                if (item.remote == null) open(item.series, true)
                else if (cloud.signedIn && scope == cloud.historyScope) openCloud(item.remote)
            })
    }

    private fun refreshSavedIfNeeded() {
        if (!cloud.signedIn || savedScope == cloud.historyScope) return
        savedScope = cloud.historyScope
        if (libraryTab == 0 && !cloud.historyReady && !cloud.historyLoading) cloud.refreshHistory()
        if (libraryTab == 1 && !cloud.favoriteState().loaded && !cloud.favoriteState().loading) cloud.refreshFavorites()
    }

    private fun renderSaved() {
        val outlet = savedResults ?: return
        val history = libraryTab == 0
        val histories = if (history) latestHistory() else emptyList()
        val localFavorites = if (history) emptyList() else library.favorites()
        val accountFavorites = if (history) emptyList() else cloud.favoriteState().rows.map { it.series }
        val signedIn = cloud.signedIn
        val loading = if (history) cloud.historyLoading else cloud.favoriteState().loading
        val status = if (history) cloud.historyStatus else cloud.favoriteState().message
        val snapshot = listOf(libraryTab, histories, localFavorites, accountFavorites, signedIn, loading, status, cloud.historyScope)
        if (snapshot == savedSnapshot) return
        savedSnapshot = snapshot
        outlet.removeAllViews()
        if (signedIn) {
            if (status.isNotBlank()) outlet.addView(message(status))
            outlet.addView(action("刷新账户记录") {
                if (history) cloud.refreshHistory() else cloud.refreshFavorites()
            }.apply { isEnabled = !loading })
        }
        if (history) {
            if (histories.isEmpty()) outlet.addView(message("观看记录会出现在这里"))
            histories.forEach { outlet.addView(historyRow(it)) }
        } else {
            val cards = (localFavorites + accountFavorites).distinctBy { it.id }
            val accountIds = accountFavorites.mapTo(mutableSetOf()) { it.id }
            val localIds = localFavorites.mapTo(mutableSetOf()) { it.id }
            if (cards.isEmpty()) outlet.addView(message("还没有收藏"))
            cards.forEach { series ->
                val source = when {
                    series.id in accountIds && series.id in localIds -> "本机和账户"
                    series.id in accountIds -> "账户"
                    else -> "本机"
                }
                outlet.addView(seriesRow(series, listOf(series.badge, source).filter(String::isNotBlank).joinToString(" · ")))
            }
        }
    }

    fun refreshAccount() {
        if (homeResume?.isAttachedToWindow == true) renderHomeResume()
        accountFavorite?.let { (id, button) ->
            if (button.isAttachedToWindow) button.text = cloud.favoriteActionLabel(id)
        }
        if (savedResults?.isAttachedToWindow == true) {
            renderSaved()
            refreshSavedIfNeeded()
        }
        val results = reservationResults?.takeIf { it.isAttachedToWindow }
        val buttons = relatedActions.filter { (_, button) -> button.isAttachedToWindow }
        buttons.forEach { (work, button) ->
            val state = cloud.reservationLabel(work)
            button.text = if (state == "已提交") "查询预约状态" else state
            button.contentDescription = "${work.series.title} · ${button.text}"
            button.isEnabled = state != "已预约" && state != "预约中…"
        }
        if (results != null) {
            val state = cloud.reservationState(reservationOnline)
            results.removeAllViews()
            if (!cloud.signedIn) {
                results.addView(message("登录红果账户后查看预约"))
                results.addView(action("登录红果账户", primary = true) { cloud.show() })
            } else {
                if (state.loading || !state.loaded || state.message.startsWith("读取失败"))
                    results.addView(message(state.message))
                else if (state.rows.isEmpty())
                    results.addView(message(if (reservationOnline) "暂无已上线的预约" else "暂无待上线的预约"))
                if (!state.loading) results.addView(action("刷新预约") { cloud.refreshReservations() })
                state.rows.forEach { row ->
                    results.addView(seriesRow(row.series, describeReservation(row), openable = row.online))
                }
            }
        }
        if ((results != null || buttons.isNotEmpty()) && cloud.signedIn &&
            cloud.reservationState(false).message == "尚未读取预约记录") cloud.refreshReservations()
    }

    private fun relatedWorks(body: LinearLayout, series: Series) {
        val outlet = column()
        body.addView(outlet)
        fun load() {
            outlet.removeAllViews()
            space(outlet, 20)
            outlet.addView(label("相关作品", 20f, bold = true))
            outlet.addView(message("正在加载相关作品…"))
            loadRelated(series, loaded@ { works ->
                if (!outlet.isAttachedToWindow) return@loaded
                outlet.removeAllViews()
                if (works.isNotEmpty()) {
                    space(outlet, 20)
                    outlet.addView(label("相关作品", 20f, bold = true))
                }
                relatedActions = works.mapNotNull { work ->
                    val meta = listOf(work.season, if (work.upcoming) "待上线" else work.series.badge)
                        .filter(String::isNotBlank).joinToString(" · ")
                    outlet.addView(seriesRow(work.series, meta, openable = !work.upcoming))
                    if (!work.upcoming) return@mapNotNull null
                    val notice = label("", 13f, muted)
                    lateinit var reserve: TextView
                    reserve = action("预约") {
                        notice.text = ""
                        cloud.reserve(work) done@ { status ->
                            if (!reserve.isAttachedToWindow) return@done
                            notice.text = status
                            refreshAccount()
                        }
                    }
                    outlet.addView(reserve)
                    outlet.addView(notice)
                    work to reserve
                }
                refreshAccount()
            }, {
                if (outlet.isAttachedToWindow) {
                    outlet.removeAllViews()
                    outlet.addView(message("相关作品暂时无法加载"))
                    outlet.addView(action("重试加载相关作品", run = ::load))
                }
            })
        }
        load()
    }

    fun showDetail(
        root: FrameLayout,
        detail: Detail,
        resume: ResumeTarget,
        current: Int?,
        play: (Int, Long) -> Unit,
        back: () -> Unit,
        cloudFavorite: (Series, View) -> Unit,
    ) {
        val frame = frame(root)
        frame.addView(lineAction("‹ 返回", run = back))
        val (body, _) = scrollBody(frame)
        val series = detail.series
        val hero = row().apply { gravity = Gravity.CENTER_VERTICAL }
        hero.addView(poster(series), LinearLayout.LayoutParams(dp(104), dp(148)).apply { rightMargin = dp(17) })
        val copy = column()
        copy.addView(label(series.title, 25f, bold = true))
        space(copy, 8)
        copy.addView(label(listOf("全 ${detail.episodes.size} 集", series.tags).filter(String::isNotBlank).joinToString(" · "), 13f, muted))
        hero.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(hero)
        space(body, 16)
        if (series.description.isNotBlank()) {
            val synopsis = label(series.description, 15f, muted).apply {
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 3
                setLineSpacing(dp(4).toFloat(), 1f)
            }
            body.addView(synopsis)
            lateinit var expand: TextView
            expand = action("展开简介") {
                val collapsed = synopsis.maxLines == 3
                synopsis.maxLines = if (collapsed) Int.MAX_VALUE else 3
                expand.text = if (collapsed) "收起简介" else "展开简介"
            }
            body.addView(expand)
        }
        val actions = row()
        fun toggle(active: () -> Boolean, labels: Pair<String, String>, update: () -> Unit) {
            lateinit var button: TextView
            fun title() = if (active()) labels.second else labels.first
            button = action(title()) { update(); button.text = title(); button.isSelected = active() }
            button.isSelected = active()
            actions.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
        }
        toggle({ library.favorite(series.id) }, "收藏" to "已收藏") { library.toggle(series) }
        toggle({ library.queued(series.id) }, "稍后看" to "已加入稍后看") { library.toggleLater(series) }
        lateinit var accountButton: TextView
        accountButton = action(cloud.favoriteActionLabel(series.id)) { cloudFavorite(series, accountButton) }
        accountFavorite = series.id to accountButton
        if (cloud.signedIn && !cloud.favoriteState().loaded && !cloud.favoriteState().loading) cloud.refreshFavorites()
        actions.addView(accountButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        body.addView(actions)
        space(body, 22)
        body.addView(label("选集", 20f, bold = true))
        val selected = current?.takeIf { it in detail.episodes.indices } ?: resume.index
        val episodes = column()
        val groups = episodeGroups(detail.episodes.size)
        fun render(group: Int) {
            renderEpisodeGroup(episodes, detail.episodes.size, group, selected) { index ->
                play(index, if (index == resume.index) resume.position else 0)
            }
        }
        if (groups.size > 1) {
            val groupBar = FrameLayout(activity)
            fun groupTabs(selectedGroup: Int) {
                groupBar.removeAllViews()
                groupBar.addView(chips(groups, selectedGroup) { groupTabs(it) })
                render(selectedGroup)
            }
            groupTabs(selected / episodesPerGroup)
            body.addView(groupBar)
        } else render(0)
        body.addView(episodes)
        relatedWorks(body, series)
        if (detail.episodes.isNotEmpty()) {
            val label = if (resume.position > 0) "继续看第 ${resume.index + 1} 集" else "播放第 ${resume.index + 1} 集"
            frame.addView(action(label, primary = true) { play(resume.index, resume.position) },
                LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        }
    }

    private val episodesPerGroup = 20

    private fun episodeGroups(total: Int): List<String> = (0 until total step episodesPerGroup).map { start ->
        "${start + 1}–${minOf(start + episodesPerGroup, total)}"
    }

    private fun renderEpisodeGroup(parent: LinearLayout, total: Int, group: Int, current: Int, select: (Int) -> Unit) {
        val columns = if (activity.resources.configuration.screenWidthDp < 360) 4 else 5
        parent.removeAllViews()
        val start = group * episodesPerGroup
        (start until minOf(start + episodesPerGroup, total)).chunked(columns).forEach { indices ->
            parent.addView(row().apply {
                indices.forEach { index ->
                    val episode = action("${index + 1}", primary = index == current) { select(index) }.apply {
                        contentDescription = "第 ${index + 1} 集"
                        isSelected = index == current
                        setPadding(0, 0, 0, 0)
                        setSingleLine()
                    }
                    addView(episode, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    })
                }
                repeat(columns - indices.size) { addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f)) }
            })
        }
    }

    fun showEpisodePicker(total: Int, current: Int, select: (Int) -> Unit) {
        if (total <= 0) return
        val groups = episodeGroups(total)
        val content = column().apply { setPadding(dp(16), 0, dp(16), dp(8)) }
        val grid = column()
        val scroller = ScrollView(activity).apply { addView(content) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("选集 · 共 $total 集")
            .setView(scroller)
            .setNegativeButton("关闭", null)
            .create()
        fun choose(index: Int) {
            dialog.dismiss()
            select(index)
        }
        if (groups.size > 1) {
            val jump = EditText(activity).apply {
                hint = "输入集数"
                inputType = InputType.TYPE_CLASS_NUMBER
                imeOptions = EditorInfo.IME_ACTION_GO
                setSingleLine()
                filters = arrayOf(InputFilter.LengthFilter(total.toString().length))
            }
            fun jumpToEpisode() {
                val number = jump.text.toString().toIntOrNull()
                if (number != null && number in 1..total) choose(number - 1)
                else jump.error = "请输入 1–$total"
            }
            jump.setOnEditorActionListener { _, action, _ ->
                if (action != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
                jumpToEpisode()
                true
            }
            content.addView(row().apply {
                addView(jump, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
                addView(action("跳转播放", run = ::jumpToEpisode))
            })
            val spinner = Spinner(activity).apply {
                contentDescription = "选集分组"
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, groups.map { "$it 集" })
                    .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                setSelection(current / episodesPerGroup)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        renderEpisodeGroup(grid, total, position, current, ::choose)
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }
            content.addView(spinner, LinearLayout.LayoutParams(-1, dp(48)))
        }
        renderEpisodeGroup(grid, total, current / episodesPerGroup, current, ::choose)
        content.addView(grid)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(-1, -2)
        }
    }

    fun frame(root: FrameLayout): LinearLayout {
        reservationResults = null
        savedResults = null
        savedSnapshot = null
        savedScope = null
        homeResume = null
        homeResumeEntry = null
        accountFavorite = null
        relatedActions = emptyList()
        root.removeAllViews()
        root.setBackgroundColor(TvStyle.background)
        return column().apply {
            fun pad(insets: Insets) {
                setPadding(dp(16) + insets.left, dp(12) + insets.top, dp(16) + insets.right, dp(8) + insets.bottom)
            }
            pad(Insets.NONE)
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, window ->
                pad(window.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()))
                window
            }
            root.addView(this, FrameLayout.LayoutParams(-1, -1))
            ViewCompat.requestApplyInsets(this)
        }
    }

    private fun scrollBody(frame: LinearLayout): Pair<LinearLayout, ScrollView> {
        val scroll = ScrollView(activity).apply { isFillViewport = true; clipToPadding = false }
        val content = column().apply { setPadding(0, 0, 0, dp(12)) }
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        frame.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return content to scroll
    }

    private fun bottomNavigation() = row().apply {
        setPadding(0, dp(6), 0, 0)
        Tab.entries.forEach { item ->
            val selected = item == tab
            val control = action(item.label) { select(item, focus = item == Tab.SEARCH) }.apply {
                isSelected = selected
                textSize = 12f
                includeFontPadding = false
                background = ripple(if (selected) TvStyle.raised else Color.TRANSPARENT, 12)
                setPadding(0, dp(8), 0, dp(5))
                compoundDrawablePadding = dp(3)
                setCompoundDrawablesWithIntrinsicBounds(null, TvGlyph(item.glyph, dp(20), if (selected) accent else ink), null, null)
            }
            addView(control, LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        }
    }

    private fun chips(labels: List<String>, selected: Int, choose: (Int) -> Unit): View {
        val row = row()
        labels.forEachIndexed { index, title ->
            row.addView(action(title, primary = index == selected) { choose(index) }.apply { isSelected = index == selected },
                LinearLayout.LayoutParams(-2, dp(46)).apply { rightMargin = dp(8) })
        }
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            if (selected in 1 until row.childCount) post {
                val child = row.getChildAt(selected)
                if (child.right > width) scrollTo(child.left, 0)
            }
        }
    }

    private fun posterGrid(parent: LinearLayout, items: List<Series>) {
        val columns = (activity.resources.configuration.screenWidthDp / 120).coerceIn(2, 5)
        val width = (activity.resources.configuration.screenWidthDp - 40) / columns
        items.chunked(columns).forEach { chunk ->
            val row = row().apply { gravity = Gravity.TOP }
            chunk.forEach { series ->
                val tile = column().apply {
                    background = ripple(Color.TRANSPARENT, 10)
                    isFocusable = true
                    contentDescription = series.title
                    setOnClickListener { open(series, false) }
                }
                tile.addView(poster(series), LinearLayout.LayoutParams(-1, dp(width * 1.4f)))
                tile.addView(label(series.title, 14f, bold = true).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(2), dp(7), dp(2), dp(11))
                })
                row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
            }
            repeat(columns - chunk.size) { row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f)) }
            parent.addView(row)
        }
    }

    private fun seriesRow(series: Series, subtitle: String, cta: String? = null, resume: Boolean = false, openable: Boolean = true, onOpen: (() -> Unit)? = null) = row().apply {
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = if (openable) ripple(surface, 12) else shape(surface, 12)
        if (openable) {
            isFocusable = true
            setOnClickListener { if (onOpen == null) open(series, resume) else onOpen() }
        }
        addView(poster(series), LinearLayout.LayoutParams(dp(52), dp(73)).apply { rightMargin = dp(12) })
        val copy = column()
        copy.addView(label(series.title, 16f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (subtitle.isNotBlank()) {
            space(copy, 6)
            copy.addView(label(subtitle, 13f, muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        }
        addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        if (openable) addView(label(cta ?: "›", 14f, accent).apply { setPadding(dp(8), 0, 0, 0) })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
    }

    private fun poster(series: Series) = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = shape(surface, 9)
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        artwork(this, series.cover)
    }

    fun lineAction(title: String, run: () -> Unit) = action(title, run = run).apply {
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) }
    }

    fun message(title: String) = label(title, 15f, muted).apply { setPadding(dp(4), dp(18), dp(4), dp(16)) }

    fun playbackAction(title: String, key: String, run: () -> Unit) =
        PlaybackIconButton(activity, key, touch = true).apply {
            text = title
            tag = key
            background = ripple(surface, 12)
            setOnClickListener { run() }
        }

    fun action(title: String, primary: Boolean = false, run: () -> Unit) =
        android.widget.Button(activity).apply {
            text = title
            isAllCaps = false
            textSize = 14f
            setTextColor(if (primary) readableAccent() else ink)
            background = ripple(if (primary) accent else surface, 12)
            gravity = Gravity.CENTER
            minimumHeight = dp(46)
            minHeight = dp(46)
            minimumWidth = 0
            minWidth = 0
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener { run() }
        }

    private fun readableAccent(): Int = if (androidx.core.graphics.ColorUtils.calculateContrast(Color.BLACK, accent) >= 4.5) Color.BLACK else Color.WHITE

    private fun label(value: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun row() = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun space(parent: LinearLayout, height: Int) { parent.addView(View(activity), LinearLayout.LayoutParams(1, dp(height))) }
    private fun dp(value: Number) = (value.toFloat() * activity.resources.displayMetrics.density + .5f).toInt()
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun ripple(color: Int, radius: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x30ffffff), shape(color, radius), shape(Color.WHITE, radius))
    private fun time(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }
}
