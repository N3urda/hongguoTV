package com.hongguotv.nativeapp

import android.app.Activity
import com.hongguotv.core.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
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
class AccountFavoriteActionsTest {
    private fun get(account: AccountController, name: String) =
        account.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(account)

    private fun set(account: AccountController, name: String, value: Any?) {
        account.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(account, value)
    }

    private fun flush(account: AccountController) {
        (get(account, "worker") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun rows(present: Boolean) =
        """{"code":0,"data":{"video_shelf_info":${if(present) """[{"series_title":"测试剧","video_data":{"series_id":"123"}}]""" else "[]"}}}"""

    @Test
    fun confirmedAddRemoveAndIdempotentPrecheckUseFreshAccountList() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val account = AccountController(controller.get()) {}
        try {
            flush(account)
            ShadowLooper.idleMainLooper()
            set(account, "credentials", AccountCredentials("test-only"))
            var present = false
            var writes = 0
            val http =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        val body =
                            if (request.method == "POST") {
                                writes++
                                val buffer = okio.Buffer()
                                request.body!!.writeTo(buffer)
                                present =
                                    JSONObject(buffer.readUtf8())
                                        .getJSONArray("update_bookshelf_video_list")
                                        .getJSONObject(0)
                                        .getInt("video_shelf_operate_type") == 0
                                """{"code":0}"""
                            } else rows(present)
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(body.toResponseBody())
                            .build()
                    }
                    .build()
            set(account, "client", CloudAccountClient(http))
            var message = ""
            val series = Series("123", "测试剧")
            account.changeFavorite(series, true) { message = it }
            account.changeFavorite(series, true) {}
            flush(account)
            ShadowLooper.idleMainLooper()
            assertEquals(1, writes)
            assertTrue(account.accountFavorite("123"))
            assertEquals("已收藏", message)
            assertEquals("取消账户收藏", account.favoriteActionLabel("123"))
            account.changeFavorite(series, true) { message = it }
            flush(account)
            ShadowLooper.idleMainLooper()
            assertEquals(1, writes)
            assertEquals("已收藏", message)
            account.changeFavorite(series, false) { message = it }
            flush(account)
            ShadowLooper.idleMainLooper()
            assertEquals(2, writes)
            assertFalse(account.accountFavorite("123"))
            assertEquals("已取消收藏", message)
        } finally {
            account.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun acknowledgementWithoutListChangeIsUnconfirmedAndBlocksRepeatedWritesUntilRefresh() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val account = AccountController(controller.get()) {}
        try {
            flush(account)
            ShadowLooper.idleMainLooper()
            set(account, "credentials", AccountCredentials("test-only"))
            var writes = 0
            set(
                account,
                "client",
                CloudAccountClient(
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            val request = chain.request()
                            if (request.method == "POST") writes++
                            Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(
                                    (if (request.method == "POST") """{"code":0}"""
                                        else rows(false))
                                        .toResponseBody()
                                )
                                .build()
                        }
                        .build()
                ),
            )
            var message = ""
            account.changeFavorite(Series("123", "测试剧"), true) { message = it }
            flush(account)
            ShadowLooper.idleMainLooper()
            assertEquals("收藏已提交", message)
            assertFalse(account.accountFavorite("123"))
            assertTrue(account.favoriteNeedsRefresh("123"))
            assertEquals("收藏待同步", account.favoriteActionLabel("123"))
            account.changeFavorite(Series("123", "测试剧"), true) {}
            flush(account)
            ShadowLooper.idleMainLooper()
            assertEquals(1, writes)
            account.refreshFavorites()
            flush(account)
            ShadowLooper.idleMainLooper()
            assertTrue(account.favoriteNeedsRefresh("123"))
            account.changeFavorite(Series("123", "测试剧"), true) {}
            flush(account)
            ShadowLooper.idleMainLooper()
            assertEquals(1, writes)
        } finally {
            account.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun addFromTvRequiresNoConfirmationOrResultDialog() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val account = MainActivity::class.java.getDeclaredField("cloudAccount").apply { isAccessible = true }.get(activity) as AccountController
        try {
            account.onHistoryChanged = null
            flush(account); ShadowLooper.idleMainLooper()
            set(account, "credentials", AccountCredentials("test-only"))
            var writes = 0
            set(account, "client", CloudAccountClient(OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().method == "POST") writes++
                val body = if (chain.request().method == "POST") """{"code":0}""" else rows(writes > 0)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
            }.build()))
            MainActivity::class.java.getDeclaredMethod("accountFavoriteAction", Series::class.java, android.view.View::class.java)
                .apply { isAccessible = true }.invoke(activity, Series("123", "测试剧"), null)
            flush(account); ShadowLooper.idleMainLooper()
            assertEquals(1, writes)
            assertTrue(account.accountFavorite("123"))
            assertFalse(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
        } finally { controller.destroy() }
    }

    @Test
    fun delayedFavoriteConfirmationRetriesReadsNotWrites() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val account = AccountController(controller.get()) {}
        try {
            flush(account); ShadowLooper.idleMainLooper()
            set(account, "credentials", AccountCredentials("test-only"))
            var writes = 0
            var reads = 0
            set(account, "client", CloudAccountClient(OkHttpClient.Builder().addInterceptor { chain ->
                val body = if (chain.request().method == "POST") { writes++; """{"code":0}""" }
                    else { reads++; rows(reads >= 3) }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
            }.build()))
            var message = ""
            account.changeFavorite(Series("123", "测试剧"), true) { message = it }
            flush(account); ShadowLooper.idleMainLooper()
            assertEquals(1, writes)
            assertEquals(3, reads)
            assertEquals("已收藏", message)
            assertFalse(account.favoriteNeedsRefresh("123"))
        } finally { account.destroy(); controller.pause().stop().destroy() }
    }

    @Test
    fun confirmationDoesNotWriteUntilAcceptedAndExpiresOnAccountSwitch() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        fun field(name: String) =
            MainActivity::class
                .java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(activity)
        val account = field("cloudAccount") as AccountController
        try {
            account.onHistoryChanged = null
            set(account, "credentials", AccountCredentials("test-only"))
            set(
                account,
                "favorites",
                FavoriteState(listOf(CloudFavorite(Series("123", "测试剧"), 1000)), loaded = true),
            )
            var calls = 0
            set(
                account,
                "client",
                CloudAccountClient(
                    OkHttpClient.Builder()
                        .addInterceptor {
                            calls++
                            throw java.io.IOException("must not call")
                        }
                        .build()
                ),
            )
            val action =
                MainActivity::class
                    .java
                    .getDeclaredMethod(
                        "accountFavoriteAction",
                        Series::class.java,
                        android.view.View::class.java,
                    )
                    .apply { isAccessible = true }
            action.invoke(activity, Series("123", "测试剧"), null)
            val first = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(first.isShowing)
            assertEquals(0, calls)
            first.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals(0, calls)
            action.invoke(activity, Series("123", "测试剧"), null)
            val second = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            set(account, "generation", account.historyScope + 1)
            second.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            ShadowLooper.idleMainLooper()
            flush(account)
            ShadowLooper.idleMainLooper()
            assertEquals(0, calls)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun logoutDiscardsLateWriteConfirmationAndNeverRepopulatesOldAccount() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val account = AccountController(controller.get()) {}
        try {
            flush(account)
            ShadowLooper.idleMainLooper()
            set(account, "credentials", AccountCredentials("test-only"))
            var present = false
            var callback = false
            set(
                account,
                "client",
                CloudAccountClient(
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            val request = chain.request()
                            if (request.method == "POST") present = true
                            Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(
                                    (if (request.method == "POST") """{"code":0}"""
                                        else rows(present))
                                        .toResponseBody()
                                )
                                .build()
                        }
                        .build()
                ),
            )
            account.changeFavorite(Series("123", "测试剧"), true) { callback = true }
            flush(account)
            AccountController::class
                .java
                .getDeclaredMethod("logout")
                .apply { isAccessible = true }
                .invoke(account)
            ShadowLooper.idleMainLooper()
            assertFalse(callback)
            assertFalse(account.signedIn)
            assertTrue(account.favoriteState().rows.isEmpty())
            set(account, "credentials", AccountCredentials("test-only-b"))
            assertFalse(account.accountFavorite("123"))
        } finally {
            account.destroy()
            controller.pause().stop().destroy()
        }
    }
}
