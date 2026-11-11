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
