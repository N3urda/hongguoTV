package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.hongguotv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RankingScreenTest {
    private fun views(view: View): List<View> =
        listOf(view) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
            else emptyList())

    private fun layout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 960, 540)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @Test
    fun posterGridKeepsSourceRanksAndRestoresDetailSelection() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val tools = TvTools(activity)
        try {
            val state = RankBrowserState()
            val items =
                (1..25).map { Series(it.toString(), "剧$it", tags = if (it == 25) "系统" else "穿越") }
            // 经 endRefresh 播种新鲜缓存：进入与切换都不应再触发请求。
            state.endRefresh(
                RankBoard.ALL,
                RankSnapshot(
                    RankBoard.ALL,
                    items,
                    ComicRanking(
                        items.associate { it.id to RankPosition(it.id.toInt() + 10, "100万热度") },
                        2,
                        "今日已更新",
                    ),
                ),
            )
            val nav =
                (0..7).map {
                    TextView(activity).apply {
                        id = View.generateViewId()
                        isFocusable = true
                    }
                }
            var opened = ""
            var longPressed = ""
            val screen =
                RankingScreen(
                    activity,
                    state,
                    nav,
                    tools,
                    { _, _ -> },
                    { opened = it.id },
                    { item, _ -> longPressed = item.id },
                    { _, _ -> fail("cached board fetched") },
                )
            activity.setContentView(FrameLayout(activity).apply { addView(screen) })
            screen.start(false, false)
            layout(screen)
            val browser = views(screen).filterIsInstance<ContinuousCatalogView>().single()
            assertEquals(6, browser.grid.numColumns)
            browser.enter()
            layout(screen)
            val first = browser.grid.getChildAt(0)
            browser.grid.performItemClick(first, 0, browser.grid.adapter.getItemId(0))
            assertEquals("1", opened)
            assertEquals("1", state.selectedId)
            browser.grid.dispatchKeyEvent(
                android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_MENU,
                )
            )
            assertEquals("1", longPressed)
            val allViews = views(screen)
            assertTrue(allViews.filterIsInstance<TextView>().any { it.text.toString() == "第 11 名" })
            assertNotEquals(View.NO_ID, browser.grid.nextFocusUpId)
            assertEquals(25, browser.grid.adapter.count)
            browser.grid.setSelection(20)
            layout(screen)
            assertEquals("21", browser.capture()?.id)
            assertTrue(browser.grid.hasFocus())
            assertTrue(browser.grid.childCount < 25)
            val returned =
                RankingScreen(
                    activity,
                    state,
                    nav,
                    tools,
                    { _, _ -> },
                    {},
                    { _, _ -> },
                    { _, _ -> fail("return fetched cached board") },
                )
            activity.setContentView(returned)
            returned.start(false, false)
            layout(returned)
            val restored = views(returned).filterIsInstance<ContinuousCatalogView>().single()
            assertEquals("21", restored.capture()?.id)
            views(returned)
                .filterIsInstance<TextView>()
                .single { it.text.toString() == "筛选题材" }
                .performClick()
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            views(dialog.window!!.decorView)
                .filterIsInstance<TextView>()
                .single { it.text.toString() == "系统 · 1 部" }
                .performClick()
            assertEquals("系统", state.topic)
            assertEquals(1, state.page)
            assertFalse(dialog.isShowing)
            val filtered =
                RankingScreen(
                    activity,
                    state,
                    nav,
                    tools,
                    { _, _ -> },
                    {},
                    { _, _ -> },
                    { _, _ -> fail("unexpected fetch") },
                )
            activity.setContentView(filtered)
            filtered.start(false, false)
            layout(filtered)
            assertNotNull(filtered.findViewWithTag<View>("25"))
            assertNull(filtered.findViewWithTag<View>("1"))
            assertTrue(
                views(filtered).filterIsInstance<TextView>().any { it.text.toString() == "第 35 名" }
            )
        } finally {
            tools.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun failedRefreshRetainsLastSuccessfulBoard() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val tools = TvTools(activity)
        try {
            val state = RankBrowserState()
            state.snapshots[RankBoard.ALL] =
                RankSnapshot(
                    RankBoard.ALL,
                    listOf(Series("1", "已有作品")),
                    ComicRanking(emptyMap(), 1, ""),
                )
            val nav =
                (0..7).map {
                    TextView(activity).apply {
                        id = View.generateViewId()
                        isFocusable = true
                    }
                }
            val screen =
                RankingScreen(
                    activity,
                    state,
                    nav,
                    tools,
                    { _, _ -> },
                    {},
                    { _, _ -> },
                    { _, done -> done(null, "离线") },
                )
            activity.setContentView(screen)
            screen.start(true, false)
            layout(screen)
            assertNotNull(screen.findViewWithTag<View>("1"))
            assertTrue(
                views(screen).filterIsInstance<TextView>().any { it.text.toString().contains("保留上次榜单") }
            )
        } finally {
            tools.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun controlButtons(screen: RankingScreen): List<TextView> =
        RankingScreen::class.java.getDeclaredField("controls").apply { isAccessible = true }
            .get(screen) as List<TextView>

    private fun statusLine(screen: RankingScreen): TextView =
        RankingScreen::class.java.getDeclaredField("subtitle").apply { isAccessible = true }
            .get(screen) as TextView

    @Test
    fun switchingBoardsWhileLoadingStartsNewLoadAndDiscardsStaleResult() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val tools = TvTools(activity)
        try {
            val state = RankBrowserState()
            val nav =
                (0..7).map {
                    TextView(activity).apply {
                        id = View.generateViewId()
                        isFocusable = true
                    }
                }
            val requested = mutableListOf<RankBoard>()
            var stale: ((RankSnapshot?, String?) -> Unit)? = null
            val screen =
                RankingScreen(
                    activity,
                    state,
                    nav,
                    tools,
                    { _, _ -> },
                    {},
                    { _, _ -> },
                ) { board, done ->
                    requested += board
                    if (board == RankBoard.ALL) stale = done
                }
            activity.setContentView(FrameLayout(activity).apply { addView(screen) })
            screen.start(true, true)
            assertEquals(listOf(RankBoard.ALL), requested)

            // 加载尚未返回时切榜：两个榜单各自在途、互不取消，切换即时生效。
            controlButtons(screen)[1].performClick()
            assertEquals(RankBoard.REAL, state.board)
            assertEquals(listOf(RankBoard.ALL, RankBoard.REAL), requested)

            // 第一个榜的失败回调在后台返回：只写入状态，不覆盖当前榜的界面。
            val loadingText = statusLine(screen).text.toString()
            stale?.invoke(null, "读取失败")
            assertEquals(loadingText, statusLine(screen).text.toString())
        } finally {
            tools.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun freshBoardsRenderWithoutAnyRequest() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val tools = TvTools(activity)
        try {
            val state = RankBrowserState()
            state.endRefresh(
                RankBoard.ALL,
                RankSnapshot(RankBoard.ALL, listOf(Series("1", "综合")), ComicRanking(emptyMap(), 1, "")),
            )
            state.endRefresh(
                RankBoard.REAL,
                RankSnapshot(RankBoard.REAL, listOf(Series("2", "真人")), ComicRanking(emptyMap(), 1, "")),
            )
            val nav =
                (0..7).map {
                    TextView(activity).apply {
                        id = View.generateViewId()
                        isFocusable = true
                    }
                }
            val screen =
                RankingScreen(
                    activity,
                    state,
                    nav,
                    tools,
                    { _, _ -> },
                    {},
                    { _, _ -> },
                ) { _, _ -> fail("fresh board within TTL must not fetch") }
            activity.setContentView(FrameLayout(activity).apply { addView(screen) })
            screen.start(true, true) // 强制位会被 TTL 收敛为零请求
            layout(screen)
            controlButtons(screen)[1].performClick()
            layout(screen)
            assertEquals(RankBoard.REAL, state.board)
            assertNotNull(screen.findViewWithTag<View>("2"))
        } finally {
            tools.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun switchingAwayDoesNotCancelOtherBoardLoads() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val tools = TvTools(activity)
        try {
            val state = RankBrowserState()
            val nav =
                (0..7).map {
                    TextView(activity).apply {
                        id = View.generateViewId()
                        isFocusable = true
                    }
                }
            val pending = mutableMapOf<RankBoard, (RankSnapshot?, String?) -> Unit>()
            val screen =
                RankingScreen(
                    activity,
                    state,
                    nav,
                    tools,
                    { _, _ -> },
                    {},
                    { _, _ -> },
                ) { board, done -> pending[board] = done }
            activity.setContentView(FrameLayout(activity).apply { addView(screen) })
            screen.start(true, true)
            controlButtons(screen)[1].performClick() // 两个榜并行在途
            assertEquals(setOf(RankBoard.ALL, RankBoard.REAL), pending.keys)

            // 离开当前榜后，后台读取照常完成并写入缓存。
            pending.getValue(RankBoard.ALL)(
                RankSnapshot(RankBoard.ALL, listOf(Series("1", "综合")), ComicRanking(emptyMap(), 1, "")),
                null,
            )
            assertNotNull(state.snapshots[RankBoard.ALL])

            // 切回已完成的榜：TTL 内零新请求，直接展示。
            controlButtons(screen)[0].performClick()
            layout(screen)
            assertEquals(setOf(RankBoard.ALL, RankBoard.REAL), pending.keys)
            assertNotNull(screen.findViewWithTag<View>("1"))
        } finally {
            tools.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun expiredCacheShowsOldDataWhileRenewingInBackground() {
        var now = 0L
        val state = RankBrowserState(clock = { now }, ttlMillis = 10 * 60_000)
        state.endRefresh(
            RankBoard.ALL,
            RankSnapshot(RankBoard.ALL, listOf(Series("1", "旧综合")), ComicRanking(emptyMap(), 1, "")),
        )
        now += 10 * 60_000 + 1 // 缓存过期
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val tools = TvTools(activity)
        try {
            val nav =
                (0..7).map {
                    TextView(activity).apply {
                        id = View.generateViewId()
                        isFocusable = true
                    }
                }
            var renewals = 0
            val screen =
                RankingScreen(
                    activity,
                    state,
                    nav,
                    tools,
                    { _, _ -> },
                    {},
                    { _, _ -> },
                ) { _, _ -> renewals += 1 }
            activity.setContentView(FrameLayout(activity).apply { addView(screen) })
            screen.start(true, true)
            layout(screen)
            assertEquals(1, renewals) // 过期触发一次后台续期
            assertNotNull(screen.findViewWithTag<View>("1")) // 旧数据继续展示
            assertTrue(statusLine(screen).text.toString().contains("后台更新"))
        } finally {
            tools.destroy()
            controller.pause().stop().destroy()
        }
    }

    private fun unclippedRectInRoot(view: View, root: View): android.graphics.Rect {
        val rect = android.graphics.Rect(0, 0, view.width, view.height)
        var current: View = view
        while (current !== root) {
            val parent = current.parent as? ViewGroup ?: break
            rect.offset(current.left - parent.scrollX, current.top - parent.scrollY)
            val left = if (parent.clipToPadding) parent.paddingLeft else 0
            val top = if (parent.clipToPadding) parent.paddingTop else 0
            val right =
                parent.width - if (parent.clipToPadding) parent.paddingRight else 0
            val bottom =
                parent.height - if (parent.clipToPadding) parent.paddingBottom else 0
            if (!rect.intersect(left, top, right, bottom)) {
                return android.graphics.Rect()
            }
            current = parent
        }
        if (!rect.intersect(0, 0, root.width, root.height)) {
            return android.graphics.Rect()
        }
        return rect
    }

    @Test
    @Config(sdk = [26], qualifiers = "w960dp-h540dp-land-xhdpi")
    fun fullHd320DpiSelectedFirstRowCardKeepsPosterAspectAndShowsFullTwoLineTitleAndHeat() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().visible()
        val activity = controller.get()
        try {
            val library =
                MainActivity::class.java.getDeclaredField("library").apply { isAccessible = true }
                    .get(activity) as Library
            (Library::class.java.getDeclaredField("loader").apply { isAccessible = true }
                    .get(library) as java.util.concurrent.ExecutorService)
                .submit {}
                .get(5, java.util.concurrent.TimeUnit.SECONDS)
            val account =
                MainActivity::class.java.getDeclaredField("cloudAccount").apply {
                        isAccessible = true
                    }
                    .get(activity) as AccountController
            (AccountController::class.java.getDeclaredField("worker").apply { isAccessible = true }
                    .get(account) as java.util.concurrent.ExecutorService)
                .submit {}
                .get(5, java.util.concurrent.TimeUnit.SECONDS)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()

            val rankState =
                MainActivity::class.java.getDeclaredField("rankBrowser").apply {
                        isAccessible = true
                    }
                    .get(activity) as RankBrowserState
            val items =
                listOf(Series("rank-1", "万妖图录传\n第十四季", tags = "玄幻")) +
                    (2..24).map { Series("rank-$it", "热播榜单测试剧\n第${it}季", tags = "都市") }
            val positions =
                items.mapIndexed { idx, s ->
                    s.id to RankPosition(idx + 1, if (idx == 0) "9437万热度" else "${9000 - idx}万热度")
                }.toMap()
            rankState.endRefresh(
                RankBoard.ALL,
                RankSnapshot(RankBoard.ALL, items, ComicRanking(positions, 1, "今日已更新")),
            )

            MainActivity::class
                .java
                .getDeclaredMethod("switchTab", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(activity, 2)
            val decor = activity.window.decorView
            fun layoutFullHd() {
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                )
                decor.layout(0, 0, 1920, 1080)
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
            }
            layoutFullHd()
            layoutFullHd()

            val screen = views(decor).filterIsInstance<RankingScreen>().single()
            val allButton = controlButtons(screen)[RankBoard.ALL.ordinal]
            assertTrue(allButton.requestFocus())
            layoutFullHd()
            assertTrue("Expected 综合 tab button to hold focus", allButton.hasFocus())

            val browser = views(screen).filterIsInstance<ContinuousCatalogView>().single()
            val unfocusedFirstCard = browser.grid.getChildAt(0) as CatalogPosterCard
            assertEquals(
                "First-row card heat badge should already fit before moving focus down",
                unfocusedFirstCard.badge.height,
                unclippedRectInRoot(unfocusedFirstCard.badge, decor).height(),
            )

            assertEquals(browser.grid.id, allButton.nextFocusDownId)
            val nextFocus = decor.findViewById<View>(allButton.nextFocusDownId)
            assertTrue(nextFocus.requestFocus())
            layoutFullHd()
            layoutFullHd()
            assertTrue(browser.grid.hasFocus())
            assertEquals(0, browser.grid.selectedItemPosition)

            val firstCard = browser.grid.getChildAt(0) as CatalogPosterCard
            val expectedPosterHeight =
                (firstCard.poster.width / TvStyle.POSTER_ASPECT).let { kotlin.math.round(it).toInt() }
            assertTrue(
                "Expected 7:10 poster height ~$expectedPosterHeight for width ${firstCard.poster.width}, got ${firstCard.poster.height}",
                kotlin.math.abs(firstCard.poster.height - expectedPosterHeight) <= 1,
            )
            assertEquals("万妖图录传\n第十四季", firstCard.title.text.toString())
            assertEquals(2, firstCard.title.lineCount)
            assertEquals("9437万热度", firstCard.badge.text.toString())
            assertTrue(firstCard.badge.height > 0)

            val visibleTitle = unclippedRectInRoot(firstCard.title, decor)
            val visibleBadge = unclippedRectInRoot(firstCard.badge, decor)
            val visibleCard = unclippedRectInRoot(firstCard, decor)
            assertEquals(
                "Selected card 2-line title must not be clipped at the bottom",
                firstCard.title.height,
                visibleTitle.height(),
            )
            assertEquals(
                "Selected card heat badge must be 100% visible in viewport",
                firstCard.badge.height,
                visibleBadge.height(),
            )
            assertEquals(
                "Entire selected first-row card must fit inside GridView and RankingScreen viewport",
                firstCard.height,
                visibleCard.height(),
            )

            // Scrolling to lower rows expands the grid smoothly, and Back to top nav resets cleanly.
            browser.grid.setSelection(12)
            layoutFullHd()
            layoutFullHd()
            assertTrue(screen.scrollY > 0)
            @Suppress("DEPRECATION")
            activity.onBackPressed()
            layoutFullHd()
            layoutFullHd()
            assertEquals(0, screen.scrollY)
        } finally {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            controller.destroy()
        }
    }

    @Test
    @Config(sdk = [26], qualifiers = "w960dp-h540dp-land-xhdpi", fontScale = 1.3f)
    fun largeFontAndWrappedFullTitleStillKeepSelectedCardTitleAndHeatFullyVisible() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val tools = TvTools(activity)
        try {
            val state = RankBrowserState()
            val longTitle = "万妖图录传之九霄神雷破苍穹\n无上仙帝归来逆天改命\n第十四季大结局"
            val items =
                listOf(Series("1", longTitle, tags = "玄幻")) +
                    (2..18).map { Series("$it", "大字号榜单作品\n第${it}季", tags = "古装") }
            val positions =
                items.mapIndexed { idx, s -> s.id to RankPosition(idx + 1, "${9437 - idx}万热度") }
                    .toMap()
            state.endRefresh(
                RankBoard.ALL,
                RankSnapshot(RankBoard.ALL, items, ComicRanking(positions, 1, "今日已更新")),
            )
            val nav =
                (0..6).map {
                    TextView(activity).apply {
                        id = View.generateViewId()
                        isFocusable = true
                    }
                }
            val screen =
                RankingScreen(
                    activity,
                    state,
                    nav,
                    tools,
                    { _, _ -> },
                    {},
                    { _, _ -> },
                ) { _, _ -> }
            val root =
                FrameLayout(activity).apply {
                    addView(screen, FrameLayout.LayoutParams(1728, 854))
                }
            activity.setContentView(root)
            val decor = activity.window.decorView
            fun layoutCompact() {
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                )
                decor.layout(0, 0, 1920, 1080)
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
            }
            screen.start(false, false)
            layoutCompact()
            val browser = views(screen).filterIsInstance<ContinuousCatalogView>().single()
            browser.enter()
            layoutCompact()
            layoutCompact()

            val firstCard = browser.grid.getChildAt(0) as CatalogPosterCard
            assertEquals(2, firstCard.title.lineCount)
            assertEquals("9437万热度", firstCard.badge.text.toString())
            assertEquals(
                "grid=${browser.grid.height}, card=${firstCard.height}, scrollY=${screen.scrollY}",
                firstCard.title.height,
                unclippedRectInRoot(firstCard.title, root).height(),
            )
            assertEquals(
                "grid=${browser.grid.height}, card=${firstCard.height}, scrollY=${screen.scrollY}",
                firstCard.badge.height,
                unclippedRectInRoot(firstCard.badge, root).height(),
            )

            controlButtons(screen)[0].requestFocus()
            layoutCompact()
            layoutCompact()
            assertEquals(0, screen.scrollY)
        } finally {
            tools.destroy()
            controller.pause().stop().destroy()
        }
    }
}
