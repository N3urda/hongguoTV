package com.hongguotv.nativeapp

import android.app.Activity
import com.hongguotv.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 26])
@LooperMode(LooperMode.Mode.PAUSED)
class PhoneConsoleTest {
    private fun get(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun waitFor(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < end) { ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        assertTrue(condition())
    }
    private fun server(console: PhoneConsole): PhoneControlServer? = get(console, "session")?.let { get(it, "server") as PhoneControlServer }
    private fun call(server: PhoneControlServer): String {
        val uri = java.net.URI(server.url)
        return java.net.Socket("127.0.0.1", uri.port).use { socket ->
            socket.soTimeout = 3000
            val body = "{\"action\":\"status\"}"
            socket.getOutputStream().write(("POST ${uri.path}/api HTTP/1.1\r\nHost: ${uri.authority}\r\n" +
                "Origin: http://${uri.authority}\r\nX-Control-Request: 1\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray())
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            check(response.startsWith("HTTP/1.1 200")) { response }
            response.substringAfter("\r\n\r\n")
        }
    }
    private fun fixture(test: (PhoneConsole, TvTools, AtomicInteger) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val tools = TvTools(activity.get())
        val library = Library(activity.get())
        val account = AccountController(activity.get()) {}
        val count = AtomicInteger()
        val catalog = PhoneControlCatalog(library, account) { _, _ -> }
        val console = PhoneConsole(activity.get(), tools, catalog,
            { count.incrementAndGet(); JSONObject().put("scope", 0) },
            addressProvider = { "127.0.0.1" }, showSearch = { _, _, _ -> })
        try { test(console, tools, count) }
        finally { console.stop(); tools.destroy(); library.close(); account.destroy(); activity.pause().stop().destroy() }
    }

    @Test fun closingQrKeepsSessionButStoppingRevokesItAndResumeRotatesToken() = fixture { console, tools, count ->
        val client = Executors.newSingleThreadExecutor()
        try {
            console.resume()
            assertNull(server(console)) // no listener before explicit opt-in
            console.show(null)
            waitFor { server(console) != null }
            val original = server(console)!!
            assertTrue(tools.dispatchPhoneKey(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK)))
            assertTrue(tools.dispatchPhoneKey(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK)))
            assertFalse(tools.showing)
            val request = client.submit<String> { call(original) }
            waitFor { request.isDone }
            assertEquals(0, JSONObject(request.get()).getInt("scope"))
            assertEquals(1, count.get())
            console.stop()
            assertTrue(runCatching { call(original) }.isFailure)
            assertNull(server(console))
            console.resume()
            waitFor { server(console) != null }
            assertNotEquals(original.url, server(console)!!.url)
            console.disconnect()
            console.resume()
            assertNull(server(console))
        } finally { client.shutdownNow() }
    }

    @Test fun stopDuringStartupDoesNotPublishLateServerOrDialog() = fixture { console, tools, _ ->
        console.resume()
        console.show(null)
        console.stop()
        // Drain the starter's completion; an expired generation must close its server.
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()
        assertNull(server(console))
        assertFalse(tools.showing)
    }
}
