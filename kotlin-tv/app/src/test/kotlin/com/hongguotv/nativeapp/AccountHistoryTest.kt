package com.hongguotv.nativeapp

import android.app.Activity
import com.hongguotv.core.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "land")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AccountHistoryTest {
    @Test fun uploadNotifiesAfterCheckpointIsQueuedWithoutReenteringViewTransition() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val account = AccountController(activity.get()) {}
        try {
            field(account, "generation", 1) // Ignore the asynchronous initial store read.
            field(account, "credentials", AccountCredentials("test-only"))
            field(account, "profile", CloudProfile("a", "测试账户"))
            field(account, "draining", true) // No network in this notification regression.
            val progress = CloudProgress(Series("123", "测试作品"), "456", 0, 12000, 60000, 1)
            var notifications = 0
            account.onHistoryChanged = {
                notifications++
                assertEquals(progress.copy(episodeCount = 10), account.progress("123"))
            }
            account.upload(progress, 10, true)
            assertEquals(0, notifications)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(1, notifications)
        } finally {
            field(account, "lastProgress", null)
            account.destroy()
            activity.pause().stop().destroy()
        }
    }

    private fun field(account: AccountController, name: String, value: Any?) {
        AccountController::class
            .java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(account, value)
    }

    @Test
    fun historyMergesLocalAndCloudRows() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        fun get(name: String) =
            MainActivity::class
                .java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(activity)
        fun texts(view: android.view.View): List<String> =
            when (view) {
                is android.view.ViewGroup ->
                    (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
                is android.widget.TextView -> listOf(view.text.toString())
                else -> emptyList()
            }
        try {
            val library = get("library") as Library
            val local = Series("111", "本机独有记录")
            val data =
                BackupData(
                    emptyList(),
                    listOf(BackupProgress(local, "112", 0, 1000, 90000, false, 1000)),
                    emptySet(),
                    emptyList(),
                    BackupSettings(),
                )
            Library::class
                .java
                .getDeclaredField("index")
                .apply { isAccessible = true }
                .set(library, LibraryIndex(data))
            val account = get("cloudAccount") as AccountController
            val switch =
                MainActivity::class
                    .java
                    .getDeclaredMethod("switchTab", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
            switch.invoke(activity, 4)
            val root = activity.findViewById<android.view.View>(android.R.id.content)
            assertTrue(texts(root).any { it.contains("登录后在这里合并显示") })
            field(account, "credentials", AccountCredentials("test-only"))
            field(account, "profile", CloudProfile("a", "账号 A"))
            field(
                account,
                "history",
                listOf(
                    CloudProgress(Series("221", "云端独有记录"), "222", 3, 45000, 90000, 2000),
                    CloudProgress(local, "113", 2, 2000, 90000, 500),
                ),
            )
            field(account, "busy", true)
            switch.invoke(activity, 4)
            assertTrue(texts(root).contains("云端独有记录"))
            assertTrue(texts(root).contains("本机独有记录"))
            assertTrue(texts(root).any { it.contains("第 4 集") })
            assertEquals(listOf("221", "111"), (get("catalog") as List<*>).filterIsInstance<Series>().map { it.id })
            assertEquals(1, (get("catalog") as List<*>).filterIsInstance<Series>().count { it.id == local.id })
            val selectedCloud = MainActivity::class.java
                .getDeclaredMethod("mergedCloudProgress", String::class.java).apply { isAccessible = true }
            assertNull(selectedCloud.invoke(activity, local.id))
            val newer = CloudProgress(local, "114", 4, 3000, 90000, 3000)
            field(account, "history", listOf(newer))
            switch.invoke(activity, 4)
            assertEquals(newer, selectedCloud.invoke(activity, local.id))
            assertEquals(1, (get("catalog") as List<*>).filterIsInstance<Series>().count { it.id == local.id })
            assertTrue(texts(root).any { it.contains("第 5 集") })
            field(account, "credentials", null)
            switch.invoke(activity, 4)
            assertNull(selectedCloud.invoke(activity, local.id))
            assertTrue(texts(root).contains("本机独有记录"))
            assertFalse(texts(root).any { it.contains("第 5 集") })
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun failedHistoryRefreshKeepsPreviouslyLoadedRowsAndReportsItsStage() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val account = AccountController(controller.get()) {}
        fun drain() {
            val worker = AccountController::class.java.getDeclaredField("worker").apply { isAccessible = true }
                .get(account) as java.util.concurrent.ExecutorService
            worker.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
        try {
            drain()
            val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").body("".toResponseBody()).build()
            }.build()
            field(account, "client", CloudAccountClient(http))
            field(account, "credentials", AccountCredentials("test-only"))
            field(account, "profile", CloudProfile("a", "账号 A"))
            val rows = listOf(CloudProgress(Series("123", "已读取的记录"), "456", 2, 12000, 90000, 1000))
            field(account, "history", rows)
            field(account, "historyLoaded", true)
            account.refreshHistory()
            drain()
            assertEquals(rows, account.historyRows())
            assertTrue(account.historyReady)
            assertTrue(account.historyStatus.contains("账号资料接口返回空响应"))
        } finally {
            account.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun switchingAccountsAndLoggingOutImmediatelyDiscardPreviousHistory() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val account = AccountController(activity.get()) {}
        val row = CloudProgress(Series("123", "账号 A 的记录"), "456", 2, 45000, 90000, 1000)
        try {
            field(account, "credentials", AccountCredentials("test-only"))
            field(account, "profile", CloudProfile("a", "账号 A"))
            field(account, "history", listOf(row))
            field(
                account,
                "reservations",
                mapOf(
                    true to
                        ReservationState(
                            listOf(CloudReservation(row.series, true, 0)),
                            loaded = true,
                        )
                ),
            )
            field(
                account,
                "favorites",
                FavoriteState(listOf(CloudFavorite(row.series, 1000)), loaded = true),
            )
            assertEquals(1, account.favoriteState().rows.size)
            assertEquals(1, account.reservationState(true).rows.size)
            assertEquals(row, account.progress("123"))
            var notifications = 0
            account.onHistoryChanged = { notifications++ }
            // Keep network disabled while exercising the real login state transition.
            field(account, "busy", true)
            AccountController::class
                .java
                .getDeclaredMethod(
                    "acceptAccount",
                    AccountCredentials::class.java,
                    CloudProfile::class.java,
                )
                .apply { isAccessible = true }
                .invoke(account, AccountCredentials("test-only-b"), CloudProfile("b", "账号 B"))
            assertTrue(account.signedIn)
            assertEquals("账号 B", account.accountLabel)
            assertTrue(account.reservationState(true).rows.isEmpty())
            assertTrue(account.historyRows().isEmpty())
            assertTrue(account.favoriteState().rows.isEmpty())
            assertNull(account.progress("123"))
            assertEquals(1, notifications)
            field(account, "history", listOf(row))
            field(
                account,
                "reservations",
                mapOf(
                    false to
                        ReservationState(
                            listOf(CloudReservation(row.series, false, 0)),
                            loaded = true,
                        )
                ),
            )
            val scope = account.historyScope
            AccountController::class
                .java
                .getDeclaredMethod("logout")
                .apply { isAccessible = true }
                .invoke(account)
            assertFalse(account.signedIn)
            assertTrue(account.reservationState(false).rows.isEmpty())
            assertTrue(account.historyScope > scope)
            assertTrue(account.historyRows().isEmpty())
            assertTrue(account.favoriteState().rows.isEmpty())
            assertNull(account.progress("123"))
            assertEquals(2, notifications)
        } finally {
            account.destroy()
            activity.pause().stop().destroy()
        }
    }
}
