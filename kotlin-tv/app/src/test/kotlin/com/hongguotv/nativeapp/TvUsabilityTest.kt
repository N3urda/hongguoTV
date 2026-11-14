package com.hongguotv.nativeapp

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hongguotv.core.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
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
class TvUsabilityTest {
    private fun get(target: Any, name: String) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun set(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun texts(view: View): List<TextView> =
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else if (view is TextView) listOf(view) else emptyList()

    @Test
    fun nextSeasonBannerDoesNotOpenDialogAndIsRemovedOnCancelAndBackground() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val library = get(activity, "library") as Library
            (get(library, "loader") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            val account = get(activity, "cloudAccount") as AccountController
            (get(account, "worker") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            (get(activity, "io") as ExecutorService).shutdownNow()
            set(activity, "io", worker)
            library.seasonAutoplay = true
            library.autoNext = true
            set(activity, "repository", ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body("""{"code":0,"data":[{"show_type":547,"video_data":[{"series_id":"222","title":"长剧第二季","content_type":1004,"episode_cnt":10,"season_index":2}]}]}""".toResponseBody()).build()
            }.build()))
            val current = Detail(Series("111", "长剧第一季"), listOf("123"))
            set(activity, "detail", current)
            set(activity, "episodeIndex", 0)
            set(activity, "foreground", true)
            set(activity, "screen", "player")
            set(activity, "pausedForLifecycle", false)
            val offer = MainActivity::class.java.getDeclaredMethod("offerNextSeason", Detail::class.java).apply { isAccessible = true }
            offer.invoke(activity, current)
            worker.submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            val banner = get(activity, "seasonBanner") as TextView
            assertTrue(banner.text.contains("本季结束后播放《长剧第二季》"))
            assertFalse(banner.isFocusable)
            MainActivity::class.java.getDeclaredMethod("cancelNextSeason").apply { isAccessible = true }.invoke(activity)
            assertNull(get(activity, "seasonBanner"))
            assertNull(banner.parent)
            assertFalse(get(activity, "seasonPending") as Boolean)
            offer.invoke(activity, current)
            worker.submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            val second = get(activity, "seasonBanner") as TextView
            MainActivity::class.java.getDeclaredMethod("onPause").apply { isAccessible = true }.invoke(activity)
            assertNull(second.parent)
            assertEquals("111", (get(activity, "detail") as Detail).series.id)
        } finally {
            worker.shutdownNow()
            controller.destroy()
        }
    }

    @Test
    fun startupLocalHistoryRendersRecordsWithoutSearchingAnEmptyKeyword() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val library = get(activity, "library") as Library
            (get(library, "loader") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            val account = get(activity, "cloudAccount") as AccountController
            (get(account, "worker") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            library.save(WatchProgress(Series("123", "启动历史回归"), "456", 0, 1000, 90000, false, 1000))
            set(activity, "tab", 4)
            set(activity, "query", "")
            activity.javaClass.getDeclaredMethod("showCatalog", Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, true, false, false)
            ShadowLooper.idleMainLooper()
            val root = get(activity, "root") as View
            val labels = texts(root).map { it.text.toString() }
            assertTrue(labels.contains("观看历史"))
            assertFalse(labels.contains("本机历史"))
            assertFalse(labels.any { it.contains("Failed requirement") || it.contains("正在加载…") })
            assertEquals(listOf("123"), (get(activity, "catalog") as List<*>).filterIsInstance<Series>().map { it.id })
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun detailKeepsPlaybackCommentsAndSeparateSecondaryActions() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val library = get(activity, "library") as Library
            (get(library, "loader") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            val account = get(activity, "cloudAccount") as AccountController
            (get(account, "worker") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            set(
                activity,
                "repository",
                ContentRepository(
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(404)
                                .message("fixture")
                                .body("".toResponseBody())
                                .build()
                        }
                        .build()
                ),
            )
            val series = Series("1", "详情测试剧", description = "很长的简介。".repeat(50))
            val data = Detail(series, (1..40).map { "$it" })
            library.save(WatchProgress(series, "25", 24, 12000, 90000, false, 1000))
            set(activity, "detail", data)
            set(activity, "episodeIndex", 24)
            set(activity, "group", 1)
            MainActivity::class
                .java
                .getDeclaredMethod("showDetail", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(activity, false)
            val root = activity.findViewById<View>(android.R.id.content)
            val rows = texts(root)
            val play = rows.first { it.text == "继续第 25 集" }
            val comments = rows.first { it.text == "评论 · 第 25 集" }
            assertFalse(rows.any { it.text.toString().startsWith("选集 · 共") })
            assertSame(play.parent, comments.parent)
            assertEquals(comments.id, play.nextFocusRightId)
            assertEquals(play.id, comments.nextFocusLeftId)
            assertNotSame(play.parent, rows.first { it.text == "加入本机收藏" }.parent)
            assertEquals(2, rows.first { it.text.toString().startsWith("很长的简介") }.maxLines)
            assertEquals(1, get(activity, "group"))
            val selected = texts(root).first { it.text == "25\n当前" }
            assertTrue(selected.isSelected)
            assertTrue(rows.any { it.text == "跳转集数" })
            assertEquals("detail", get(activity, "screen"))
            val favorite = rows.single { it.text.toString().contains("收藏") }
            val synopsis = rows.single { it.text == "完整简介" }
            assertEquals(synopsis.id, favorite.nextFocusRightId)
            assertEquals(favorite.id, synopsis.nextFocusLeftId)
            favorite.performClick()
            assertTrue(library.favorite(series.id))
            assertEquals("取消本机收藏", favorite.text.toString())
            favorite.performClick()
            assertFalse(library.favorite(series.id))

            // Switch the existing button in place; account actions must not write local favorites.
            set(account, "credentials", AccountCredentials("test-only"))
            set(account, "favorites", FavoriteState(listOf(CloudFavorite(series, 1000)), loaded = true))
            account.onHistoryChanged?.invoke()
            assertEquals("取消账户收藏", favorite.text.toString())
            assertEquals(1, texts(root).count { it.text.toString().contains("收藏") })
            set(account, "favorites", FavoriteState(loading = true))
            account.onHistoryChanged?.invoke()
            favorite.performClick()
            assertFalse(library.favorite(series.id))
            set(account, "credentials", null)
            account.onHistoryChanged?.invoke()
            assertEquals("加入本机收藏", favorite.text.toString())
            favorite.performClick()
            assertTrue(library.favorite(series.id))

        } finally {
            controller.destroy()
        }
    }

    @Test
    fun settingsSeparatesAccountDataAndUpdatesWithValidFocusLinks() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val library = get(activity, "library") as Library
            (get(library, "loader") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            (get(get(activity, "cloudAccount")!!, "worker") as ExecutorService)
                .submit {}
                .get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            MainActivity::class
                .java
                .getDeclaredMethod("switchTab", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(activity, 6)
            val root = activity.findViewById<View>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 960, 540)
            val quality = texts(root).first { it.text.toString().startsWith("默认清晰度") }
            assertTrue(quality.hasFocus())
            val card = quality.parent as View
            assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, card.layoutParams.height)
            val rows = texts(root)
            listOf("播放", "外观与操作", "账户", "应用与更新").forEach { title ->
                assertTrue(rows.any { it.text == title })
            }
            val appearance = root.findViewWithTag<View>("settings-category-1")
            appearance.requestFocus()
            appearance.performClick()
            val startup = texts(root).first { it.text.toString().startsWith("启动页面") }
            assertTrue(startup.hasFocus())
            assertEquals(appearance.id, startup.nextFocusLeftId)
            val clock = texts(root).first { it.text.toString().startsWith("播放时钟") }
            clock.requestFocus()
            root.findViewWithTag<View>("settings-category-0").requestFocus()
            assertTrue(texts(root).any { it.text.toString().startsWith("默认清晰度") })
            appearance.requestFocus()
            appearance.performClick()
            assertTrue(texts(root).first { it.text.toString().startsWith("播放时钟") }.hasFocus())
            val switches = mutableListOf<android.widget.Switch>()
            fun findSwitches(view: View) {
                if (view is android.widget.Switch) switches += view
                if (view is ViewGroup) for (i in 0 until view.childCount) findSwitches(view.getChildAt(i))
            }
            findSwitches(root)
            val preview = switches.single()
            val previous = library.homeAutoplay
            (preview.parent as View).performClick()
            assertEquals(!previous, library.homeAutoplay)
            assertEquals(!previous, preview.isChecked)
            assertFalse(preview.isFocusable)
            root.findViewWithTag<View>("settings-category-2").requestFocus()
            val account = texts(root).first { it.text == "登录与账户管理" }
            assertEquals(root.findViewWithTag<View>("settings-category-2").id, account.nextFocusLeftId)
            assertFalse(texts(root).any { it.text.toString().startsWith("版本与更新") })
            root.findViewWithTag<View>("settings-category-3").requestFocus()
            assertTrue(texts(root).any { it.text.toString().startsWith("版本与更新") })
            assertFalse(texts(root).any { it.text == "登录与账户管理" })
        } finally {
            controller.destroy()
        }
    }
}
