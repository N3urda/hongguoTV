package com.hongguotv.nativeapp

import android.app.Activity
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 26], qualifiers = "w960dp-h540dp-land-mdpi")
class CatalogShellTest {
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    fun updateIsAnIndependentVisibleRailAction() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var anchor: android.view.View? = null
        var navigated = -1
        val shell = CatalogShell(controller.get(), update = { anchor = it }) { navigated = it }
        try {
            controller.get().setContentView(shell)
            shell.select(2)
            shell.measure(android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(360, android.view.View.MeasureSpec.EXACTLY))
            shell.layout(0, 0, 640, 360)
            shell.navigation[6].requestFocus()
            repeat(2) { shell.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP)) }
            val update = shell.findFocus() as TextView
            assertEquals("检查更新", update.text.toString())
            assertTrue(update.top >= 0 && update.bottom <= 360)
            update.performClick()
            assertSame(update, anchor)
            assertEquals(-1, navigated)
            assertTrue(shell.navigation[2].isSelected)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun upAtContentTopReturnsToSelectedColumnAndRightRestoresPosition() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var navigated = -1
        val shell = CatalogShell(controller.get()) { navigated = it }
        try {
            controller.get().setContentView(shell)
            shell.select(2)
            val content = TextView(controller.get()).apply {
                isFocusable = true
                id = android.view.View.generateViewId()
                nextFocusUpId = id
            }
            shell.body.addView(content)
            shell.measure(android.view.View.MeasureSpec.makeMeasureSpec(960, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(540, android.view.View.MeasureSpec.EXACTLY))
            shell.layout(0, 0, 960, 540)
            content.requestFocus()
            fun key(code: Int) { shell.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code)) }
            key(android.view.KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(shell.navigation[2].hasFocus())
            key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(content.hasFocus())
            key(android.view.KeyEvent.KEYCODE_DPAD_UP)
            key(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals(-1, navigated)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    fun compactRailKeepsSettingsAndScanVisible() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val shell = CatalogShell(controller.get()) {}
        try {
            controller.get().setContentView(shell)
            shell.measure(android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(360, android.view.View.MeasureSpec.EXACTLY))
            shell.layout(0, 0, 640, 360)
            val settings = shell.navigation[6]
            assertTrue(settings.bottom <= 360)
            settings.requestFocus()
            shell.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP))
            assertEquals("扫码", (shell.findFocus() as TextView).text.toString())
            assertTrue(shell.findFocus().bottom <= settings.top)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun scanAboveSettingsOpensPhoneControlWithoutChangingSelectedPage() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var anchor: android.view.View? = null
        var navigated = -1
        val shell = CatalogShell(controller.get(), phone = { anchor = it }) { navigated = it }
        try {
            controller.get().setContentView(shell)
            shell.select(7)
            shell.navigation[6].requestFocus()
            shell.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP))
            val scan = shell.findFocus() as TextView
            assertEquals("扫码", scan.text.toString())
            scan.performClick()
            assertSame(scan, anchor)
            assertEquals(-1, navigated)
            assertTrue(shell.navigation[7].isSelected)
            shell.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
            assertTrue(shell.navigation[6].hasFocus())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun switchingBodyPreservesNavigationIdentityFocusAndSharedSelectionStyle() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        var selected = -1
        val shell = CatalogShell(activity.get()) { selected = it }
        try {
            activity.get().setContentView(shell)
            shell.body.addView(TextView(activity.get()).apply { text = "首页内容" })
            val top = shell.getChildAt(0)
            assertNotSame("Discovery and search remain independent destinations", shell.navigation[1], shell.navigation[7])
            assertEquals("找剧", shell.navigation[1].text.toString())
            assertEquals("搜索", shell.navigation[7].text.toString())
            val tab = shell.navigation[2]
            val ids = shell.navigation.map { it.id }
            tab.requestFocus(); tab.performClick()
            assertEquals(2, selected)
            shell.clearBody(); shell.select(2)
            shell.body.addView(TextView(activity.get()).apply { text = "排行榜内容" })
            assertSame(top, shell.getChildAt(0))
            assertSame(tab, shell.navigation[2])
            assertEquals(ids, shell.navigation.map { it.id })
            assertTrue(tab.hasFocus())
            assertTrue(tab.isSelected)
            assertEquals(TvStyle.background, tab.currentTextColor)
            assertFalse(shell.navigation[0].isSelected)
        } finally { activity.pause().stop().destroy() }
    }
}
