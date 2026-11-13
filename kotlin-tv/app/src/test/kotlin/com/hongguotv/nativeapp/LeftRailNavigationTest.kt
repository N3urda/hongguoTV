// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hongguotv.core.Series
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
class LeftRailNavigationTest {
    private fun layout(view: View, width: Int = 960, height: Int = 540) {
        ShadowLooper.idleMainLooper()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun repeatedUpKeepsSecondaryMenuReachableAndLeftStillOpensRail() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val shell = CatalogShell(activity) {}.apply { select(1) }
        fun control(label: String) = TextView(activity).apply {
            id = View.generateViewId(); text = label; isFocusable = true
        }
        val filters = android.widget.LinearLayout(activity)
        val first = control("类型")
        val second = control("排序")
        filters.addView(first, android.widget.LinearLayout.LayoutParams(120, 40))
        filters.addView(second, android.widget.LinearLayout.LayoutParams(120, 40))
        shell.body.addView(filters)
        val card = control("作品")
        shell.body.addView(card, android.widget.LinearLayout.LayoutParams(240, 180))
        // Reproduce legacy links that pointed above the page into the old navigation.
        first.nextFocusUpId = shell.navigation[1].id
        second.nextFocusUpId = shell.navigation[1].id
        card.nextFocusUpId = shell.navigation[1].id
        first.nextFocusRightId = second.id
        second.nextFocusDownId = card.id
        card.nextFocusLeftId = shell.navigation[1].id
        fun key(code: Int) {
            val handled = shell.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            // Direct dispatch has no ViewRootImpl traversal in Robolectric.
            if (!handled) {
                val direction = when(code) {
                    KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                    else -> View.FOCUS_RIGHT
                }
                shell.findFocus()?.focusSearch(direction)?.requestFocus()
            }
        }
        try {
            activity.setContentView(shell); layout(shell)
            card.requestFocus()
            key(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(first.hasFocus() || second.hasFocus())
            val selectedFilter = shell.findFocus()
            repeat(6) { key(KeyEvent.KEYCODE_DPAD_UP) }
            assertSame(selectedFilter, shell.findFocus())
            first.requestFocus()
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(second.hasFocus())
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(card.hasFocus())
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            assertTrue(shell.navigation[1].hasFocus())
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(card.hasFocus())
            shell.navigation[0].requestFocus()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(shell.navigation[8].hasFocus())
            assertEquals("随心看", shell.navigation[8].text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun gridRailRoundTripPreservesSelectionAndDoesNotSwitchOnFocus() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val navigated = mutableListOf<Int>()
        val shell = CatalogShell(activity) { navigated += it }.apply { select(1) }
        val upper = TextView(activity).apply { id = View.generateViewId(); isFocusable = true; text = "全部" }
        shell.body.addView(upper)
        val browser = ContinuousCatalogView(activity, 5, upper,
            { item, reused -> (reused as? CatalogPosterCard ?: CatalogPosterCard(activity)).apply { show(item) } },
            {}, {}, { _, _ -> }, {})
        shell.body.addView(browser, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        browser.setSideEntry(shell.navigation[1])
        try {
            activity.setContentView(shell)
            browser.show((1..40).map { Series("$it", "作品 $it") }, false)
            browser.enter()
            layout(shell)
            browser.grid.setSelection(10)
            layout(shell)
            assertEquals("11", browser.capture()?.id)
            shell.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
            assertTrue(shell.navigation[1].hasFocus())
            val contentLeft = shell.body.left
            shell.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            assertTrue(shell.navigation[7].hasFocus())
            assertTrue(navigated.isEmpty())
            shell.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            assertTrue(browser.grid.hasFocus())
            assertEquals("11", browser.capture()?.id)
            assertEquals(contentLeft, shell.body.left)
            shell.navigation[1].requestFocus()
            assertFalse(shell.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
            assertTrue(shell.hasNavigationFocus)
            assertTrue(shell.navigation[1].hasFocus())
            assertEquals("11", browser.capture()?.id)
            shell.navigation[2].performClick()
            assertEquals(listOf(2), navigated)
        } finally { browser.close(); controller.pause().stop().destroy() }
    }

    @Test fun everyPrimaryDestinationBackConfirmsExitAndCancelKeepsFocus() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        try {
            val library = field(activity, "library").get(activity) as Library
            (field(library, "loader").get(library) as java.util.concurrent.ExecutorService)
                .submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            val shell = CatalogShell(activity) {}
            activity.setContentView(shell)
            field(activity, "catalogShell").set(activity, shell)
            field(activity, "screen").set(activity, "catalog")
            @Suppress("UNCHECKED_CAST")
            val nav = field(activity, "nav").get(activity) as MutableList<View>
            nav.clear(); nav.addAll(shell.navigation)
            val content = TextView(activity).apply { isFocusable = true; text = "内容" }
            shell.body.addView(content)
            layout(shell)
            fun back() {
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                ShadowLooper.idleMainLooper()
            }
            for (tab in listOf(0, 8, 1, 7, 2, 3, 6)) {
                field(activity, "tab").setInt(activity, tab)
                shell.select(tab)
                shell.navigation[tab].requestFocus()
                back()
                val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                assertTrue("Exit confirmation for destination $tab", dialog.isShowing)
                assertFalse(activity.isFinishing)
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
                ShadowLooper.idleMainLooper()
                assertTrue("Cancel retains destination $tab", shell.navigation[tab].hasFocus())
                assertEquals(tab, field(activity, "tab").getInt(activity))
            }
            shell.navigation[6].requestFocus()
            shell.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
            assertEquals("扫码", (shell.findFocus() as TextView).text.toString())
            back()
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            field(activity, "tab").setInt(activity, 0)
            content.requestFocus()
            back()
            assertTrue("Home content returns to primary navigation first", shell.navigation[0].hasFocus())
            assertFalse(dialog.isShowing)
            back()
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            ShadowLooper.idleMainLooper()
            assertTrue(activity.isFinishing)
        } finally { controller.destroy() }
    }

    @Test fun moreHotRetainsReturnButtonAfterFailureAndBackRestoresHomeEvenFromRail() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun show() = MainActivity::class.java.getDeclaredMethod("showCatalog", Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false, false, false)
        try {
            val library = field(activity, "library").get(activity) as Library
            (field(library, "loader").get(library) as java.util.concurrent.ExecutorService).submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            field(activity, "repository").set(activity, com.hongguotv.core.ContentRepository(
                okhttp3.OkHttpClient.Builder().addInterceptor { throw java.io.IOException("offline fixture") }.build()))
            val homeItems = listOf(Series("123", "首页热门"))
            field(activity, "catalog").set(activity, homeItems)
            field(activity, "hasMore").setBoolean(activity, true)
            field(activity, "tab").setInt(activity, 0)
            field(activity, "page").setInt(activity, 1)
            show(); layout(activity.window.decorView)
            fun more() = views(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString().startsWith("更多热门") }
            fun labels() = views(activity.window.decorView).filterIsInstance<TextView>()
            assertFalse(labels().any { it.text.toString() in listOf("漫剧", "短剧", "刷新热门") })
            more().requestFocus(); more().performClick()
            (field(activity, "workJob").get(activity) as? java.util.concurrent.Future<*>)?.get(5, java.util.concurrent.TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper(); layout(activity.window.decorView)
            val back = views(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == "‹ 返回首页" }
            assertFalse(labels().any { it.text.toString() == "热门发现" })
            val comic = labels().single { it.text.toString() == "漫剧" }
            val short = labels().single { it.text.toString() == "短剧" }
            assertSame(back.parent, comic.parent)
            assertSame(back.parent, short.parent)
            short.performClick()
            (field(activity, "workJob").get(activity) as? java.util.concurrent.Future<*>)?.get(5, java.util.concurrent.TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper(); layout(activity.window.decorView)
            assertEquals("hotMore", field(activity, "collection").get(activity))
            assertEquals(1, field(activity, "page").getInt(activity))
            assertEquals(com.hongguotv.core.ContentType.SHORT, field(activity, "hotContentType").get(activity))
            labels().single { it.text.toString() == "‹ 返回首页" }.performClick()
            layout(activity.window.decorView)
            assertFalse(labels().any { it.text.toString() in listOf("漫剧", "短剧", "刷新热门") })
            assertEquals(1, field(activity, "page").getInt(activity))
            assertEquals(homeItems, field(activity, "catalog").get(activity))
            assertNull(field(activity, "collection").get(activity))
            assertTrue("Home retains the More focus", more().hasFocus())
            more().performClick()
            (field(activity, "workJob").get(activity) as? java.util.concurrent.Future<*>)?.get(5, java.util.concurrent.TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            field(activity, "page").setInt(activity, 3)
            field(activity, "catalog").set(activity, listOf(Series("456", "第三页热门")))
            show(); layout(activity.window.decorView)
            (field(activity, "catalogShell").get(activity) as CatalogShell).navigation[0].requestFocus()
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            layout(activity.window.decorView)
            assertNull(field(activity, "collection").get(activity))
            assertEquals(1, field(activity, "page").getInt(activity))
            assertEquals(homeItems, field(activity, "catalog").get(activity))
            assertFalse(activity.isFinishing)
        } finally { controller.destroy() }
    }

    @Test fun returningToEitherResumeActionRevealsTheWholeBanner() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            val top = TextView(activity).apply { id = View.generateViewId(); isFocusable = true }
            val home = HomeScreen(activity, top, { _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {})
            val spotlight = HomeSpotlight(activity, Series("1", "续播横幅测试"), "第 2 集", true, { _, _ -> }) {}
            home.setLeadingSection(spotlight)
            activity.setContentView(home)
            home.render(listOf(HomeScreen.Shelf("hot", "热门发现",
                (2..8).map { HomeScreen.Entry(Series("$it", "测试 $it"), "测试") })),
                "", false, null)
            repeat(3) { layout(home, 864, 420) }
            val scroll = views(home).filterIsInstance<android.widget.ScrollView>().single()
            val card = views(home).first { it.tag == "hot:2" }
            for (action in listOf(spotlight.play, spotlight.detail)) {
                card.requestFocus()
                scroll.scrollTo(0, spotlight.bottom)
                assertTrue("Fixture must scroll the banner away", scroll.scrollY > spotlight.top)
                action.requestFocus()
                layout(home, 864, 420)
                assertTrue(action.hasFocus())
                assertTrue("Banner top must be visible", scroll.scrollY <= spotlight.top)
                assertTrue("Banner bottom must be visible", spotlight.bottom <= scroll.scrollY + scroll.height)
                val position = home.capturePosition().copy(vertical = spotlight.bottom)
                home.restorePosition(position)
                layout(home, 864, 420)
                assertEquals("Restoration must not crop a focused banner", 0, scroll.scrollY)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun artworkAndTextNeverShareTheSameRectangle() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            val card = CatalogPosterCard(activity)
            card.show(Series("2", "长标题作品"))
            activity.setContentView(card)
            layout(card, 160, 360)
            val posterBottom = (card.image.parent as View).bottom
            views(card).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.forEach {
                assertTrue("Metadata must be below poster", it.top >= posterBottom)
            }
            val initialHeight = card.image.height
            layout(card, 210, 420)
            assertTrue("Artwork should grow with actual column width", card.image.height > initialHeight)
        } finally { controller.pause().stop().destroy() }
    }
}
