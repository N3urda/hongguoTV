package com.hongguotv.nativeapp

import android.app.Activity
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AccountLibraryTest {
    private fun get(target: Any, name: String) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun flush(account: AccountController) {
        (get(account, "worker") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun texts(view: View): List<TextView> =
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else if (view is TextView) listOf(view) else emptyList()

    private fun method(activity: MainActivity, name: String, value: Int) =
        MainActivity::class
            .java
            .getDeclaredMethod(name, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(activity, value)

    @Test
    fun sourceTabsKeepCollectionsSeparateRememberSelectionAndReachHeaderActions() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val local = Series("111", "本机收藏测试")
            val remote = Series("222", "账户收藏测试", badge = "全 12 集")
            val library = get(activity, "library") as Library
            Library::class
                .java
                .getDeclaredField("index")
                .apply { isAccessible = true }
                .set(
                    library,
                    LibraryIndex(
                        BackupData(
                            listOf(local),
                            emptyList(),
                            emptySet(),
                            emptyList(),
                            BackupSettings(),
                        )
                    ),
                )
            val account = get(activity, "cloudAccount") as AccountController
            account.onHistoryChanged = null
            set(account, "credentials", AccountCredentials("test-only"))
            set(account, "busy", true)
            set(
                account,
                "client",
                CloudAccountClient(
                    OkHttpClient.Builder()
                        .addInterceptor { throw java.io.IOException("test offline") }
                        .build()
                ),
            )
            set(
                account,
                "favorites",
                FavoriteState(listOf(CloudFavorite(remote, 1000)), loaded = true),
            )
            method(activity, "switchTab", 3)
            val root = activity.findViewById<View>(android.R.id.content)
            assertTrue(texts(root).any { it.text.toString() == "本机收藏测试" })
            assertFalse(texts(root).any { it.text.toString() == "账户收藏测试" })
            texts(root).single { it.text.toString() == "账户收藏" }.performClick()
            assertTrue(texts(root).any { it.text.toString() == "账户收藏测试" })
            assertFalse(texts(root).any { it.text.toString() == "本机收藏测试" })
            val source = texts(root).single { it.text.toString() == "账户收藏" }
            val refresh = texts(root).single { it.text.toString() == "刷新收藏" }
            assertEquals(refresh.id, source.nextFocusUpId)
            @Suppress("UNCHECKED_CAST")
            val cards = get(activity, "catalogCards") as Map<String, View>
            assertEquals(source.id, cards.getValue("222").nextFocusUpId)
            cards.getValue("222").performLongClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val choices = texts(dialog.findViewById(android.R.id.content))
                .filter { (it.parent as? View)?.tag?.toString()?.startsWith("choice-") == true && it.text.isNotEmpty() }
                .map { it.text.toString() }
            assertTrue("加入本机收藏" in choices)
            assertTrue(choices[2].contains("账户收藏"))
            assertEquals("加入本机收藏", choices[3])
            assertEquals("查看剧集详情", choices[1])
            assertFalse(choices.any { it.contains("稍后看") })
            dialog.findViewById<View>(android.R.id.content).findViewWithTag<View>("choice-3").performClick()
            assertTrue(library.favorite("222"))
            cards.getValue("222").performLongClick()
            val again = ShadowAlertDialog.getLatestAlertDialog()
            val remove = again.findViewById<View>(android.R.id.content).findViewWithTag<android.widget.LinearLayout>("choice-3")
            assertEquals("取消本机收藏", (remove.getChildAt(0) as TextView).text.toString())
            remove.performClick()
            assertFalse(library.favorite("222"))
            method(activity, "switchTab", 4)
            method(activity, "switchTab", 3)
            assertTrue((get(activity, "librarySources") as LibrarySources).accountFavorites)
            assertTrue(LibrarySources(activity).accountFavorites)
            assertEquals(listOf("111"), library.favorites().map { it.id })
            @Suppress("UNCHECKED_CAST") val nav = get(activity, "nav") as List<View>
            assertEquals(7, nav.size)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun favoriteAndReservationBadgesUseTheirOwnProgressSources() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val series = Series("111", "同一部剧", badge = "全 80 集")
            val local = BackupProgress(series, "112", 3, 1000, 90000, false, 1000)
            val library = get(activity, "library") as Library
            Library::class
                .java
                .getDeclaredField("index")
                .apply { isAccessible = true }
                .set(
                    library,
                    LibraryIndex(
                        BackupData(
                            listOf(series),
                            listOf(local),
                            emptySet(),
                            emptyList(),
                            BackupSettings(),
                        )
                    ),
                )
            val account = get(activity, "cloudAccount") as AccountController
            account.onHistoryChanged = null
            set(account, "credentials", AccountCredentials("test-only"))
            set(account, "busy", true)
            set(
                account,
                "favorites",
                FavoriteState(listOf(CloudFavorite(series, 1000)), loaded = true, loading = true),
            )
            set(
                account,
                "history",
                listOf(CloudProgress(series, "222", 11, 45000, 90000, 2000, 80)),
            )
            set(
                account,
                "reservations",
                mapOf(
                    true to
                        ReservationState(
                            listOf(CloudReservation(series, true, 0)),
                            loaded = true,
                            loading = true,
                        ),
                    false to
                        ReservationState(
                            listOf(CloudReservation(Series("reservation:333", "待上线作品"), false, 0)),
                            loaded = true,
                            loading = true,
                        ),
                ),
            )
            val root = activity.findViewById<View>(android.R.id.content)
            method(activity, "switchTab", 3)
            assertTrue(texts(root).any { it.text.toString() == "共 80 集\n看到第 4 集" })
            texts(root).single { it.text.toString() == "账户收藏" }.performClick()
            assertTrue(texts(root).any { it.text.toString() == "共 80 集\n看到第 12 集" })
            assertFalse(texts(root).any { it.text.toString() == "共 80 集\n看到第 4 集" })
            method(activity, "switchTab", 5)
            assertTrue(texts(root).any { it.text.toString().startsWith("共 80 集\n看到第 12 集\n已上线") })
            texts(root).single { it.text.toString() == "待上线 · 1" }.performClick()
            assertTrue(texts(root).any { it.text.toString().startsWith("待上线\n暂不可观看\n待上线") })
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun unsignedSourceShowsLoginAndKeepsLocalRecordsAvailable() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val library = get(activity, "library") as Library
            val row = Series("111", "本机依然存在")
            Library::class
                .java
                .getDeclaredField("index")
                .apply { isAccessible = true }
                .set(
                    library,
                    LibraryIndex(
                        BackupData(
                            listOf(row),
                            emptyList(),
                            emptySet(),
                            emptyList(),
                            BackupSettings(),
                        )
                    ),
                )
            method(activity, "switchTab", 3)
            val root = activity.findViewById<View>(android.R.id.content)
            texts(root).single { it.text.toString() == "账户收藏" }.performClick()
            val login = texts(root).single { it.text.toString() == "登录红果" }
            assertTrue(texts(root).any { it.text.contains("登录红果后查看账户收藏") })
            assertEquals(
                login.id,
                texts(root).single { it.text.toString() == "账户收藏" }.nextFocusUpId,
            )
            texts(root).single { it.text.toString() == "本机收藏" }.performClick()
            assertTrue(texts(root).any { it.text.toString() == "本机依然存在" })
            assertFalse(LibrarySources(activity).accountFavorites)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun failedRefreshKeepsRowsAndLogoutDiscardsLateFavoriteResponses() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val account = AccountController(controller.get()) {}
        try {
            flush(account)
            ShadowLooper.idleMainLooper()
            set(account, "credentials", AccountCredentials("test-only"))
            val old = CloudFavorite(Series("1", "旧收藏"), 1000)
            set(account, "favorites", FavoriteState(listOf(old), loaded = true))
            set(
                account,
                "client",
                CloudAccountClient(
                    OkHttpClient.Builder()
                        .addInterceptor { throw java.io.IOException("offline") }
                        .build()
                ),
            )
            account.refreshFavorites()
            flush(account)
            ShadowLooper.idleMainLooper()
            assertEquals(old, account.favoriteState().rows.single())
            assertTrue(account.favoriteState().message.contains("读取失败"))
            set(
                account,
                "client",
                CloudAccountClient(
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(
                                    """{"code":0,"data":{"video_shelf_info":[{"series_title":"延迟结果","video_data":{"series_id":"2"}}]}}"""
                                        .toResponseBody()
                                )
                                .build()
                        }
                        .build()
                ),
            )
            account.refreshFavorites()
            flush(account)
            AccountController::class
                .java
                .getDeclaredMethod("logout")
                .apply { isAccessible = true }
                .invoke(account)
            ShadowLooper.idleMainLooper()
            assertFalse(account.signedIn)
            assertTrue(account.favoriteState().rows.isEmpty())
            assertFalse(account.favoriteState().loaded)
            set(account, "credentials", AccountCredentials("test-only-b"))
            assertTrue(account.favoriteState().rows.isEmpty())
        } finally {
            account.destroy()
            controller.pause().stop().destroy()
        }
    }
}
