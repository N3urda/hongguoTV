package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
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
class AccountReservationsTest {
    private fun set(account: AccountController, name: String, value: Any?) {
        AccountController::class
            .java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(account, value)
    }

    private fun worker(account: AccountController) =
        AccountController::class
            .java
            .getDeclaredField("worker")
            .apply { isAccessible = true }
            .get(account) as ExecutorService

    private fun flush(account: AccountController) {
        worker(account).submit {}.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun independentCategoryFailuresAndLateResultsCannotRepopulateLoggedOutAccount() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val account = AccountController(activity.get()) {}
        try {
            flush(account)
            ShadowLooper.idleMainLooper()
            val http =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        if (chain.request().url.queryParameter("is_online") == "true")
                            throw java.io.IOException("test unavailable")
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(
                                """{"code":0,"data":{"subscribe_items":[{"subscribe_data":{"series_id":"123","title":"待上线测试剧"}}]}}"""
                                    .toResponseBody()
                            )
                            .build()
                    }
                    .build()
            set(account, "client", CloudAccountClient(http))
            set(account, "credentials", AccountCredentials("test-only"))
            account.refreshReservations()
            flush(account)
            ShadowLooper.idleMainLooper()
            assertFalse(account.reservationState(true).loaded)
            assertTrue(account.reservationState(true).message.contains("读取失败"))
            assertTrue(account.reservationState(false).loaded)
            assertEquals("123", account.reservationState(false).rows.single().series.id)
            account.refreshReservations()
            flush(account)
            // Both responses are now waiting on the UI thread. Logout must invalidate both.
            AccountController::class
                .java
                .getDeclaredMethod("logout")
                .apply { isAccessible = true }
                .invoke(account)
            ShadowLooper.idleMainLooper()
            for (online in listOf(true, false)) {
                assertFalse(account.reservationState(online).loading)
                assertFalse(account.reservationState(online).loaded)
                assertTrue(account.reservationState(online).rows.isEmpty())
            }
        } finally {
            account.destroy()
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun oneClickAutomaticallyConfirmsAfterRetryWithoutRepeatingWrite() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val account = AccountController(activity.get()) {}
        try {
            flush(account)
            ShadowLooper.idleMainLooper()
            var writes = 0
            var reads = 0
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val body = if (chain.request().method == "POST") {
                    writes++
                    """{"code":0}"""
                } else {
                    reads++
                    if (reads == 1) """{"code":0,"data":{"subscribe_items":[]}}"""
                    else """{"code":0,"data":{"subscribe_items":[{"subscribe_data":{"series_id":"123","title":"待上线测试剧"}}]}}"""
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body.toResponseBody()).build()
            }.build()
            set(account, "client", CloudAccountClient(http))
            set(account, "credentials", AccountCredentials("test-only"))
            val work = RelatedWork(Series("123", "待上线测试剧"), "第二季", true)
            var message = ""
            account.reserve(work) { message = it }
            assertEquals("预约中…", account.reservationLabel(work))
            account.reserve(work) {}
            flush(account)
            ShadowLooper.idleMainLooper()
            assertEquals(1, writes)
            assertEquals(2, reads)
            assertEquals("已预约", message)
            assertEquals("已预约", account.reservationLabel(work))
            account.reserve(work) { message = it }
            flush(account)
            assertEquals(1, writes)
            assertEquals(2, reads)
        } finally { account.destroy(); activity.pause().stop().destroy() }
    }

    @Test
    fun acceptedReservationDoesNotPretendAccountListHasConfirmedIt() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val account = AccountController(activity.get()) {}
        try {
            flush(account)
            ShadowLooper.idleMainLooper()
            var calls = 0
            var writes = 0
            val http =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        calls++
                        if (chain.request().method == "POST") writes++
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"code":0}""".toResponseBody())
                            .build()
                    }
                    .build()
            set(account, "client", CloudAccountClient(http))
            set(account, "credentials", AccountCredentials("test-only"))
            val work = RelatedWork(Series("123", "待上线测试剧"), "第3季", true)
            var message = ""
            account.reserve(work) { message = it }
            flush(account)
            ShadowLooper.idleMainLooper()
            assertEquals(3, calls)
            assertEquals("预约已提交", message)
            assertEquals("已提交", account.reservationLabel(work))
            assertTrue(account.reservationState(false).rows.isEmpty())
            account.reserve(work) { message = it }
            flush(account)
            ShadowLooper.idleMainLooper()
            assertEquals(5, calls)
            assertEquals(1, writes)
            assertEquals("预约已提交", message)
            set(
                account,
                "reservations",
                mapOf(
                    false to
                        ReservationState(
                            listOf(CloudReservation(Series("reservation:123", "待上线测试剧"), false, 0)),
                            loaded = true,
                        )
                ),
            )
            assertEquals("已预约", account.reservationLabel(work))
            AccountController::class
                .java
                .getDeclaredMethod("logout")
                .apply { isAccessible = true }
                .invoke(account)
            assertEquals("登录后预约", account.reservationLabel(work))
        } finally {
            account.destroy()
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun reservationFiltersShowSeparateCountsAndPendingCardsDoNotStartPlayback() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        fun get(name: String) =
            MainActivity::class
                .java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(activity)
        fun texts(view: View): List<TextView> =
            when (view) {
                is android.view.ViewGroup ->
                    (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
                is TextView -> listOf(view)
                else -> emptyList()
            }
        try {
            val library = get("library") as Library
            Library::class
                .java
                .getDeclaredField("index")
                .apply { isAccessible = true }
                .set(
                    library,
                    LibraryIndex(
                        BackupData(
                            emptyList(),
                            emptyList(),
                            emptySet(),
                            emptyList(),
                            BackupSettings(),
                        )
                    ),
                )
            val account = get("cloudAccount") as AccountController
            set(account, "credentials", AccountCredentials("test-only"))
            set(
                account,
                "reservations",
                mapOf(
                    true to
                        ReservationState(
                            listOf(
                                CloudReservation(Series("123", "已上线测试剧", badge = "已上线"), true, 0)
                            ),
                            loaded = true,
                        ),
                    false to
                        ReservationState(
                            listOf(
                                CloudReservation(
                                    Series("456", "待上线测试剧", badge = "待上线"),
                                    false,
                                    1792500000000,
                                )
                            ),
                            loaded = true,
                        ),
                ),
            )
            MainActivity::class
                .java
                .getDeclaredField("tab")
                .apply { isAccessible = true }
                .setInt(activity, 5)
            MainActivity::class
                .java
                .getDeclaredMethod(
                    "showCatalog",
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
                .apply { isAccessible = true }
                .invoke(activity, false, false, false)
            val root = activity.findViewById<View>(android.R.id.content)
            assertTrue(texts(root).any { it.text.toString() == "账户预约" })
            assertTrue(texts(root).any { it.text.toString() == "已上线 · 1" })
            assertTrue(texts(root).any { it.text.toString() == "待上线 · 1" })
            assertTrue(texts(root).any { it.text.toString() == "已上线测试剧" })
            assertFalse(texts(root).any { it.text.toString() == "待上线测试剧" })
            texts(root).single { it.text.toString() == "待上线 · 1" }.performClick()
            assertFalse(texts(root).any { it.text.toString() == "已上线测试剧" })
            assertTrue(texts(root).any { it.text.toString() == "待上线测试剧" })
            @Suppress("UNCHECKED_CAST") val cards = get("catalogCards") as Map<String, View>
            cards.getValue("456").performClick()
            assertEquals("catalog", get("screen"))
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
            ShadowAlertDialog.getLatestAlertDialog().dismiss()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 960, 540)
            val settings = texts(root).single { it.text.toString() == "设置" }
            val location = IntArray(2)
            settings.getLocationOnScreen(location)
            assertTrue(
                "All seven navigation tabs must fit on a 960dp TV",
                location[0] + settings.width <= 960,
            )
        } finally {
            controller.destroy()
        }
    }
}
