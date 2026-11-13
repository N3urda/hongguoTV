// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.CloudProfile
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class AccountRecommendationStartupTest {
    private fun get(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun texts(view: View): List<String> =
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else if (view is TextView) listOf(view.text.toString()) else emptyList()

    @Test
    fun defaultHomepageShowsShelvesWithoutStartingAutoplay() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val library = get(activity, "library")!!
            (get(library, "loader") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            (library as Library).save(WatchProgress(com.hongguotv.core.Series("test-resume", "续播作品"), "episode-2", 1, 12000, 60000, false, 1))
            set(activity, "tab", 0)
            set(activity, "catalog", listOf(com.hongguotv.core.Series("test-home", "精选作品")))
            MainActivity::class.java.getDeclaredMethod("showCatalog", Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, false, false, false)
            val decor = activity.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 960, 540)
            ShadowLooper.idleMainLooper()
            assertNull("Browsing must not create a preview player", get(activity, "homeFeed"))
            assertNotNull(get(activity, "homeScreen"))
            val home = get(activity, "homeScreen") as HomeScreen
            assertTrue("Default focus stays in the scrolling shelves", home.hasFocus())
            val resumeCard = home.findViewWithTag<View>("resume:test-resume")
            val hotCard = home.findViewWithTag<View>("hot:test-home")
            assertNotNull(resumeCard)
            assertNotNull(hotCard)
            fun scrollParent(view: View): android.widget.ScrollView? {
                var parent = view.parent
                while (parent is View) {
                    if (parent is android.widget.ScrollView) return parent
                    parent = parent.parent
                }
                return null
            }
            assertNotNull(scrollParent(resumeCard))
            assertSame("Resume and popular shelves scroll together", scrollParent(resumeCard), scrollParent(hotCard))

            assertTrue(texts(decor).contains("接着上次的故事"))
            val independentResume = home.findViewWithTag<View>("home:spotlight:play")
            assertNotNull(independentResume)
            assertSame("Independent resume section must scroll with the shelves", scrollParent(hotCard), scrollParent(independentResume))
            assertTrue(independentResume.hasFocus())
            assertTrue(texts(decor).contains("随心看"))
            assertFalse(texts(decor).any { it.contains("今晚") || it == "随心看  ›" })
            assertTrue(texts(decor).contains("我的"))
        } finally { controller.destroy() }
    }

    @Test
    fun pendingAccountValidationMustNotStartAGuestHomepage() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val library = get(activity, "library")!!
            val account = get(activity, "cloudAccount") as AccountController
            (get(library, "loader") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            (get(account, "worker") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            set(account, "credentials", AccountCredentials("sessionid=test-only"))
            set(account, "profile", null)
            set(account, "busy", true)
            set(activity, "tab", 8)
            MainActivity::class.java.getDeclaredMethod("showCatalog", Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, false, false, false)
            assertNull("登录态尚未确认时不能创建 guest 首页清空账号续刷记录", get(activity, "homeFeed"))
            assertTrue(texts(activity.findViewById(android.R.id.content)).any { it.contains("正在恢复账号推荐") })
            set(account, "busy", false)
            account.onHistoryChanged!!.invoke()
            assertNotNull("恢复结束后须从等待页创建首页", get(activity, "homeFeed"))
        } finally { controller.destroy() }
    }
    @Test
    fun unresolvedAccountNeverTouchesSavedAccountCursor() {
        val activity = Robolectric.buildActivity(Activity::class.java).create()
        val account = AccountController(activity.get()) {}
        val cache = FeedStartupCache(activity.get())
        cache.saveCursor("test-account", "recommend", "saved-cursor")
        try {
            assertTrue(account.recommendationLoading)
            assertNull(account.recommendationStartupCache())
            set(account, "accountRestored", true)
            set(account, "credentials", AccountCredentials("sessionid=test-only"))
            set(account, "busy", true)
            assertTrue(account.recommendationLoading)
            assertNull(account.recommendationStartupCache())
            assertNull(account.recommendationCredentials())
            set(account, "busy", false) // 验证失败允许临时匿名浏览，但不能清除账号记录。
            assertFalse(account.recommendationLoading)
            assertNull(account.recommendationStartupCache())
            assertEquals("saved-cursor", cache.loadCursor("test-account", "recommend"))
            set(account, "profile", CloudProfile("test", "测试账号"))
            assertNotNull(account.recommendationCredentials())
            assertEquals("saved-cursor", account.recommendationStartupCache()!!.loadCursor("test-account", "recommend"))
            set(account, "credentials", null)
            set(account, "profile", null)
            set(account, "accountRestoreFailed", true)
            assertNull(account.recommendationStartupCache())
            set(account, "accountRestoreFailed", false)
            assertNotNull(account.recommendationStartupCache())
        } finally {
            account.destroy()
            activity.destroy()
        }
    }

}
