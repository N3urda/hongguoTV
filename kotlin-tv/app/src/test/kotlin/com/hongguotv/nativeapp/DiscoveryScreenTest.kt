// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.hongguotv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class DiscoveryScreenTest {
    private fun layout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 960, 420)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun returningFromDetailRestoresOffscreenChosenChipAndHeaderWithoutLayoutDrift() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val upper =
            TextView(activity).apply {
                id = View.generateViewId()
                isFocusable = true
            }
        val state =
            DiscoveryBrowserState().apply {
                expanded = true // This test restores an explicitly expanded filter panel.
                loaded = true
                selection = DiscoverySelection(mapOf("category_dim_role" to "cate_29"))
                groups =
                    listOf(
                        this@DiscoveryScreenTest.groups[0],
                        DiscoveryGroup(
                            "category_dim_role",
                            "设定",
                            (0..29).map { DiscoveryFilter("cate_$it", "测试设定$it") },
                        ),
                        this@DiscoveryScreenTest.groups[2],
                        DiscoveryGroup(
                            "category_dim_theme",
                            "主题",
                            listOf(DiscoveryFilter("cate_7", "玄幻")),
                        ),
                        DiscoveryGroup("sort", "推荐", listOf(DiscoveryFilter("hot_score", "最高热度"))),
                        DiscoveryGroup("gender", "受众", listOf(DiscoveryFilter("0", "女频"))),
                        DiscoveryGroup(
                            "online_time",
                            "时间",
                            listOf(DiscoveryFilter("days_7", "7天内上新")),
                        ),
                    )
                items = (1..60).map { Series("$it", "作品$it") }
            }
        fun screen() =
            DiscoveryScreen(
                activity,
                state,
                upper,
                { _, _ -> },
                {},
                { _, _ -> },
                { _, _, _ -> fail("Restoration fetched network") },
            )
        val first = screen()
        var returned: DiscoveryScreen? = null
        try {
            activity.setContentView(first)
            first.start(false)
            layout(first)
            val chosen =
                views(first).filterIsInstance<TextView>().single { it.text.toString() == "测试设定29" }
            assertTrue(chosen.isSelected)
            assertTrue(views(first).filterIsInstance<TextView>().any { it.text.toString().startsWith("全部 ·") })
            val grid = views(first).filterIsInstance<ContinuousCatalogView>().single()
            grid.enter()
            grid.grid.setSelection(6)
            layout(first)
            layout(first)
            assertTrue(first.scrollY > 0)
            val offset = first.scrollY
            repeat(3) { layout(first) }
            assertEquals(offset, first.scrollY)
            first.close()
            val savedStep = state.browseStep
            val next = screen()
            returned = next
            activity.setContentView(next)
            next.start(false)
            layout(next)
            assertEquals(savedStep, state.browseStep)
            assertTrue(
                views(next)
                    .filterIsInstance<TextView>()
                    .single { it.text.toString() == "测试设定29" }
                    .isSelected
            )
            assertTrue(views(next).filterIsInstance<TextView>().any { it.text.toString().startsWith("全部 ·") })
            assertEquals(offset, next.scrollY)
            next.backToTop()
            layout(next)
            layout(next)
            assertEquals(0, next.scrollY)
            assertEquals(
                "1",
                views(next).filterIsInstance<ContinuousCatalogView>().single().capture()?.id,
            )
        } finally {
            first.close()
            returned?.close()
            controller.pause().stop().destroy()
        }
    }

    private fun views(view: View): List<View> =
        listOf(view) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
            else emptyList())

    private val groups =
        listOf(
            DiscoveryGroup("genre", "类型", listOf(DiscoveryFilter("short_play", "真人剧"))),
            DiscoveryGroup("category_dim_role", "设定", listOf(DiscoveryFilter("cate_36", "重生"))),
            DiscoveryGroup("category_dim_epoch", "背景", listOf(DiscoveryFilter("cate_1", "都市"))),
        )

    private fun page(id: String) = FilteredDiscoveryPage(listOf(Series(id, "作品$id")), groups, null)

    @Test
    fun newerFilterRequestWinsAndClosedPageIgnoresCallbacks() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val callbacks = mutableListOf<(FilteredDiscoveryPage?, Throwable?) -> Unit>()
        val state = DiscoveryBrowserState()
        val upper =
            TextView(activity).apply {
                id = View.generateViewId()
                isFocusable = true
            }
        val screen =
            DiscoveryScreen(
                activity,
                state,
                upper,
                { _, _ -> },
                {},
                { _, _ -> },
                { _, _, done -> callbacks += done },
            )
        try {
            activity.setContentView(screen)
            screen.start(false)
            callbacks[0](page("1"), null)
            views(screen)
                .filterIsInstance<TextView>()
                .single { it.text.toString() == "重生" }
                .performClick()
            views(screen)
                .filterIsInstance<TextView>()
                .single { it.text.toString() == "都市" }
                .performClick()
            assertEquals(3, callbacks.size)
            callbacks[1](page("2"), null)
            assertTrue(state.items.isEmpty())
            callbacks[2](page("3"), null)
            assertEquals(listOf("3"), state.items.map { it.id })
            assertEquals(
                mapOf("category_dim_role" to "cate_36", "category_dim_epoch" to "cate_1"),
                state.selection.values,
            )
            views(screen)
                .filterIsInstance<TextView>()
                .single { it.text.toString() == "刷新" }
                .performClick()
            screen.close()
            callbacks[3](page("4"), null)
            assertEquals(listOf("3"), state.items.map { it.id })
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun gridEntryPreservesPanelAndEmptyResultsCanBeReset() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val state =
            DiscoveryBrowserState().apply {
                expanded = true // This test restores an explicitly expanded filter panel.
                loaded = true
                items = page("1").items
                groups = this@DiscoveryScreenTest.groups
            }
        val upper =
            TextView(activity).apply {
                id = View.generateViewId()
                isFocusable = true
            }
        val screen =
            DiscoveryScreen(
                activity,
                state,
                upper,
                { _, _ -> },
                {},
                { _, _ -> },
                { _, _, done -> done(FilteredDiscoveryPage(emptyList(), groups, null), null) },
            )
        try {
            activity.setContentView(screen)
            screen.measure(
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
            )
            screen.layout(0, 0, 960, 420)
            ShadowLooper.idleMainLooper()
            val browser = views(screen).filterIsInstance<ContinuousCatalogView>().single()
            browser.enter()
            assertTrue(state.expanded)
            views(screen)
                .filterIsInstance<TextView>()
                .single { it.text.toString() == "收起筛选" }
                .performClick()
            assertFalse(state.expanded)
            views(screen)
                .filterIsInstance<TextView>()
                .single { it.text.toString() == "重置" }
                .performClick()
            assertEquals(0, browser.grid.adapter.count)
            assertTrue(state.loaded)
            assertTrue(state.selection.values.isEmpty())
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
    }
    @Test
    fun allOptionsSelectsHiddenChoiceAndKeepsItVisible() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val options = (1..30).map { DiscoveryFilter("tag_$it", "题材$it") }
        val group = DiscoveryGroup("category_dim_role", "设定", options)
        val state = DiscoveryBrowserState().apply { loaded = true; groups = listOf(group) }
        val screen = DiscoveryScreen(activity, state, TextView(activity), { _, _ -> }, {}, { _, _ -> },
            { _, _, done -> done(FilteredDiscoveryPage(emptyList(), listOf(group), null), null) })
        try {
            activity.setContentView(screen)
            layout(screen)
            assertFalse(views(screen).filterIsInstance<TextView>().any { it.text == "题材30" })
            views(screen).filterIsInstance<TextView>().single { it.text.toString().startsWith("全部 ·") }.performClick()
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val panel = dialog.window!!.decorView
            views(panel).filterIsInstance<TextView>().single { it.text == "题材30" }.performClick()
            layout(screen)
            assertEquals("tag_30", state.selection.values[group.type])
            assertTrue(views(screen).filterIsInstance<TextView>().single { it.text == "题材30" }.isSelected)
            assertFalse(dialog.isShowing)
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun backToTopAndMovingFocusToTopNavFullyResetsExpandedHeaderOffset() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val nav =
            TextView(activity).apply {
                id = View.generateViewId()
                isFocusable = true
            }
        val fullGroups =
            listOf(
                groups[0],
                DiscoveryGroup(
                    "category_dim_role",
                    "设定",
                    (0..9).map { DiscoveryFilter("cate_$it", "测试设定$it") },
                ),
                groups[2],
                DiscoveryGroup("category_dim_theme", "主题", listOf(DiscoveryFilter("cate_7", "玄幻"))),
                DiscoveryGroup("sort", "推荐", listOf(DiscoveryFilter("hot_score", "最高热度"))),
                DiscoveryGroup("gender", "受众", listOf(DiscoveryFilter("0", "女频"))),
                DiscoveryGroup("online_time", "时间", listOf(DiscoveryFilter("days_7", "7天内上新"))),
            )
        val state =
            DiscoveryBrowserState().apply {
                loaded = true
                expanded = true
                groups = fullGroups
                items = (1..30).map { Series("$it", "作品$it") }
            }
        val screen =
            DiscoveryScreen(
                activity,
                state,
                nav,
                { _, _ -> },
                {},
                { _, _ -> },
                { _, _, _ -> },
            )
        val root =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(nav, LinearLayout.LayoutParams(960, 48))
                addView(screen, LinearLayout.LayoutParams(960, 420))
            }
        fun layoutRoot() {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(468, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 960, 468)
            ShadowLooper.idleMainLooper()
        }
        try {
            activity.setContentView(root)
            screen.start(false)
            layoutRoot()
            layoutRoot()
            assertEquals(0, screen.scrollY)

            val browser = views(screen).filterIsInstance<ContinuousCatalogView>().single()
            browser.enter()
            browser.grid.setSelection(6)
            layoutRoot()
            layoutRoot()
            assertTrue(browser.grid.hasFocus())
            assertTrue(screen.scrollY > 0)

            // Simulate MainActivity Back key: backToTop() followed by moving focus to top nav.
            screen.backToTop()
            assertTrue(nav.requestFocus())
            layoutRoot()
            layoutRoot()
            assertTrue(nav.hasFocus())
            assertFalse(browser.grid.hasFocus())
            assertEquals(0, screen.scrollY)
            assertEquals(0, state.headerOffset)

            // Re-entering row 0 in grid still reveals full first-row card, and Back resets again.
            browser.enter()
            layoutRoot()
            layoutRoot()
            assertTrue(browser.grid.hasFocus())
            assertTrue(screen.scrollY > 0)

            screen.backToTop()
            assertTrue(nav.requestFocus())
            layoutRoot()
            layoutRoot()
            assertEquals(0, screen.scrollY)
            assertEquals(0, state.headerOffset)
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
    }
}
