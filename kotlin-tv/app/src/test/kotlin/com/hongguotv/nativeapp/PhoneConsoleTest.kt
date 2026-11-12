package com.hongguotv.nativeapp

import android.app.Activity
import com.hongguotv.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
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
    private fun call(server: PhoneControlServer, body: String = "{\"action\":\"status\"}", expectedStatus: Int = 200): String {
        val uri = java.net.URI(server.url)
        return java.net.Socket("127.0.0.1", uri.port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(("POST ${uri.path}/api HTTP/1.1\r\nHost: ${uri.authority}\r\n" +
                "Origin: http://${uri.authority}\r\nX-Control-Request: 1\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray())
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            check(response.startsWith("HTTP/1.1 $expectedStatus")) { response }
            response.substringAfter("\r\n\r\n")
        }
    }
    private fun fixture(http: OkHttpClient? = null, test: (PhoneConsole, TvTools, AtomicInteger) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val tools = TvTools(activity.get())
        val library = Library(activity.get())
        val account = AccountController(activity.get()) {}
        val count = AtomicInteger()
        val catalog = PhoneControlCatalog(library, account) { _, _ -> }
        val console = PhoneConsole(activity.get(), tools, catalog,
            { count.incrementAndGet(); JSONObject().put("scope", 0) },
            addressProvider = { "127.0.0.1" }, httpProvider = { http ?: ContentRepository().http }, showSearch = { _, _, _ -> })
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
    @Test fun phoneLoginKeepsCredentialsOffWireAndLimitsSmsResends() {
        val requests = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.encodedPath
            val data = when (request.url.encodedPath) {
                "/passport/web/get_qrcode/" -> """{"token":"fixture","qrcode":"aW1hZ2U=","expire_time":60,"qrcode_index_url":"https://reading.snssdk.com/reading_offline/novelread/page/scan-code.html?token=fixture"}"""
                "/passport/web/check_qrconnect/" -> """{"status":"1"}"""
                "/passport/web/send_code/" -> """{"retry_time":60}"""
                else -> error("Unexpected upstream request")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .addHeader("Set-Cookie", "passport_csrf_token=synthetic; Path=/; Secure")
                .body("""{"message":"success","data":$data}""".toResponseBody()).build()
        }.build()
        fixture(http) { console, _, _ ->
            val client = Executors.newSingleThreadExecutor()
            try {
                console.resume(); console.show(null); waitFor { server(console) != null }
                fun request(body: String, expectedStatus: Int = 200): JSONObject {
                    val future = client.submit<String> { call(server(console)!!, body, expectedStatus) }
                    waitFor { future.isDone }
                    return JSONObject(future.get())
                }
                val qr = request("""{"action":"loginQr"}""")
                assertTrue(qr.getString("appUrl").startsWith("dragon8662://webview?url="))
                assertFalse(qr.toString().contains("synthetic"))
                assertTrue(request("""{"action":"loginPoll"}""").getBoolean("pending"))
                request("""{"action":"loginPoll"}""")
                assertEquals(1, requests.count { it.endsWith("check_qrconnect/") })
                assertEquals(60, request("""{"action":"loginSend","mobile":"13800138000"}""").getInt("retrySeconds"))
                assertTrue(request("""{"action":"loginSend","mobile":"13800138000"}""", 400).has("error"))
                assertEquals(1, requests.count { it.endsWith("send_code/") })
                assertFalse(request("""{"action":"loginPoll"}""", 400).optBoolean("submitted"))
            } finally { client.shutdownNow() }
        }
    }

}
