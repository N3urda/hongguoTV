// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.hongguotv.core.Series
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class ChildModeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun cleanPolicy() {
        listOf("child-mode-v1.json", "child-mode-v1.json.bak", "child-mode-v1.json.new").forEach {
            File(context.noBackupFilesDir, it).delete()
        }
    }
    @Test fun whitelistIsByIdSurvivesRestartAndRequiresParentToChange() {
        val policy = ChildModeStore(context)
        policy.setup("123456", "123456")
        val parent = policy.unlock("123456")
        policy.add(parent, Series("100", "同名作品"))
        policy.enable(parent)
        assertFalse(policy.authorized(parent))
        assertTrue(policy.allows("100"))
        assertFalse(policy.allows("101"))
        assertFalse(runCatching { policy.add("", Series("101", "同名作品")) }.isSuccess)
        val restarted = ChildModeStore(context)
        assertTrue(restarted.enabled)
        assertTrue(restarted.allows("100"))
        assertFalse(restarted.allows("101"))
        val token = restarted.unlock("123456")
        restarted.remove(token, "100")
        assertFalse(restarted.allows("100"))
        assertFalse(runCatching { restarted.disable("999999") }.isSuccess)
        assertTrue(restarted.enabled)
        restarted.disable("123456")
        assertFalse(restarted.enabled)
        assertTrue(restarted.allows("101"))
    }
    @Test fun sessionsExpireIndependentlyAndRecoveryDoesNotExitChildMode() {
        var now = 100L
        val policy = ChildModeStore(context, { now })
        val recovery = policy.setup("123456", "123456")
        policy.enable(policy.unlock("123456"))
        val first = policy.unlock("123456")
        now += 200000
        val second = policy.unlock("123456")
        now += 100001
        assertFalse(policy.authorized(first))
        assertTrue(policy.authorized(second))
        val replacement = policy.recover(recovery, "654321", "654321")
        assertNotEquals(recovery, replacement)
        assertTrue(policy.enabled)
        assertFalse(policy.authorized(second))
        assertFalse(runCatching { policy.recover(recovery, "123456", "123456") }.isSuccess)
        assertTrue(policy.authorized(policy.unlock("654321")))
    }
    @Test fun lockoutSurvivesRestartAndCorruptionDoesNotUnlock() {
        var now = 1000L
        var wall = 1000000L
        val policy = ChildModeStore(context, { now }, { wall })
        policy.setup("123456", "123456")
        repeat(5) { assertFalse(runCatching { policy.unlock("999999") }.isSuccess) }
        val restarted = ChildModeStore(context, { now }, { wall })
        assertFalse(runCatching { restarted.unlock("123456") }.isSuccess)
        now += 60001; wall += 60001
        assertTrue(restarted.authorized(restarted.unlock("123456")))
        File(context.noBackupFilesDir, "child-mode-v1.json").writeText("broken")
        val damaged = ChildModeStore(context)
        assertTrue(damaged.broken)
        assertTrue(damaged.enabled)
        assertFalse(damaged.allows("100"))
        assertFalse(runCatching { damaged.setup("123456", "123456") }.isSuccess)
    }
    @Test fun phoneCannotBrowseOrChangeWhitelistWithoutItsOwnParentSession() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val policy = ChildModeStore(activity)
        policy.setup("123456", "123456")
        val tvToken = policy.unlock("123456")
        policy.add(tvToken, Series("100", "允许作品"))
        policy.enable(tvToken)
        val tools = TvTools(activity)
        val library = Library(activity)
        val account = AccountController(activity) {}
        val catalog = PhoneControlCatalog(library, account) { _, _ -> }
        val console = PhoneConsole(activity, tools, catalog,
            { org.json.JSONObject().put("scope", 0) }, addressProvider = { "127.0.0.1" },
            childMode = policy, showSearch = { _, _, _ -> fail("Child search must not reach TV") })
        val client = java.util.concurrent.Executors.newSingleThreadExecutor()
        fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        fun waitFor(done: () -> Boolean) {
            val end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            while (!done() && System.nanoTime() < end) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(10)
            }
            assertTrue(done())
        }
        try {
            console.resume(); console.show(null)
            waitFor { field(console, "session") != null }
            val server = field(field(console, "session")!!, "server") as com.hongguotv.core.PhoneControlServer
            val uri = java.net.URI(server.url)
            fun request(action: String, token: String = "", extra: (org.json.JSONObject) -> Unit = {}): org.json.JSONObject {
                val body = org.json.JSONObject().put("action", action).put("parentToken", token).also(extra).toString()
                val future = client.submit<String> {
                    java.net.Socket("127.0.0.1", uri.port).use { socket ->
                        socket.soTimeout = 10000
                        val head = "POST ${uri.path}/api HTTP/1.1\r\nHost: ${uri.authority}\r\nOrigin: http://${uri.authority}\r\nX-Control-Request: 1\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n"
                        socket.getOutputStream().write((head + body).toByteArray())
                        socket.getInputStream().readBytes().toString(Charsets.UTF_8).substringAfter("\r\n\r\n")
                    }
                }
                waitFor { future.isDone }
                return org.json.JSONObject(future.get())
            }
            assertTrue(request("search") { it.put("query", "其他作品") }.has("error"))
            assertTrue(request("childRemove") { it.put("id", "100") }.has("error"))
            val token = request("parentUnlock") { it.put("pin", "123456") }.getString("parentToken")
            assertTrue(request("search", token) { it.put("query", "其他作品").put("sync", true) }.has("error"))
            assertTrue(request("play", token) { it.put("id", "101").put("scope", 0) }.has("error"))
            assertTrue(policy.contains("100"))
            assertFalse(request("childRemove", token) { it.put("id", "100") }.has("error"))
            assertFalse(policy.contains("100"))
            assertTrue(policy.enabled)
        } finally {
            console.stop(); client.shutdownNow(); tools.destroy(); library.close(); account.destroy()
            controller.pause().stop().destroy()
        }
    }
    @Test fun oldDanmakuPreferenceMigratesOffAndChildModeCannotEnableIt() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val prefs = activity.getSharedPreferences("social", 0)
        prefs.edit().clear().putBoolean("enabled", true).commit()
        var child = false
        val social = TvSocial(activity, { null }, { null }, childRestricted = { child })
        fun labels(v: View): List<TextView> = (if (v is TextView) listOf(v) else emptyList()) +
            (if (v is ViewGroup) (0 until v.childCount).flatMap { labels(v.getChildAt(it)) } else emptyList())
        try {
            assertFalse(prefs.getBoolean("enabled", true))
            social.settings(Button(activity))
            val dialog = ShadowDialog.getLatestDialog()
            val toggle = labels(dialog.window!!.decorView).first { it.text.startsWith("弹幕：") }
            toggle.performClick()
            assertTrue(prefs.getBoolean("enabled", false))
            child = true
            social.enforceChildMode()
            toggle.performClick() // Even a retained old control cannot re-enable it.
            assertFalse(prefs.getBoolean("enabled", true))
            social.settings(Button(activity))
            assertFalse(dialog.isShowing)
            child = false
            assertFalse(prefs.getBoolean("enabled", true))
        } finally { social.destroy(); controller.pause().stop().destroy() }
    }
}
