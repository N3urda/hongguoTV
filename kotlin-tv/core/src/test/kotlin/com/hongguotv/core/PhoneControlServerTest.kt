package com.hongguotv.core

import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneControlServerTest {
    private fun call(server: PhoneControlServer, body: String? = "{\"action\":\"status\"}",
        path: String? = null, origin: String? = URI(server.url).let { "http://${it.authority}" },
        host: String = URI(server.url).authority, extra: String = "", marker: Boolean = true): String {
        val uri = URI(server.url)
        return Socket("127.0.0.1", uri.port).use { socket ->
            socket.soTimeout = 3000
            val bytes = body?.toByteArray() ?: ByteArray(0)
            val headers = "${if (body == null) "GET" else "POST"} ${path ?: (uri.path + if (body == null) "" else "/api")} HTTP/1.1\r\nHost: $host\r\n" +
                (if (origin == null) "" else "Origin: $origin\r\n") +
                (if (marker) "X-Control-Request: 1\r\n" else "") + extra +
                "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n"
            socket.getOutputStream().write(headers.toByteArray() + bytes)
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test fun repeatedRequestsKeepSessionAndOnlyImagesAllowExternalHttps() {
        val calls = AtomicInteger()
        PhoneControlServer("127.0.0.1") { calls.incrementAndGet(); JSONObject().put("title", "测试") }.use { server ->
            val page = call(server, null, origin = null, marker = false)
            assertTrue(page.startsWith("HTTP/1.1 200"))
            assertTrue(page.contains("手机控制台"))
            assertTrue(page.contains("frame-ancestors 'none'"))
            assertTrue(page.contains("img-src data: https:;"))
            assertTrue(page.contains("Referrer-Policy: no-referrer"))
            assertTrue(page.contains("connect-src 'self'"))
            assertFalse(page.contains("<video"))
            assertFalse(page.contains("src=\"http"))
            repeat(30) { assertTrue(call(server).startsWith("HTTP/1.1 200")) }
            assertEquals(30, calls.get())
        }
    }

    @Test fun credentialsAndOriginsAreRequiredBeforeCallingApplication() {
        val calls = AtomicInteger()
        PhoneControlServer("127.0.0.1") { calls.incrementAndGet(); JSONObject() }.use { server ->
            assertTrue(call(server, path = "/guess/api").contains("404 Response"))
            assertTrue(call(server, host = "evil.example").contains("404 Response"))
            assertTrue(call(server, origin = "http://evil.example").contains("403 Response"))
            assertTrue(call(server, origin = "null").contains("403 Response"))
            assertTrue(call(server, origin = null).contains("403 Response"))
            assertTrue(call(server, marker = false).contains("403 Response"))
            assertTrue(call(server, extra = "Sec-Fetch-Site: cross-site\r\n").contains("403 Response"))
            assertTrue(call(server, extra = "Transfer-Encoding: chunked\r\n").contains("400 Response"))
            assertTrue(call(server, extra = "Content-Length: 2\r\n").contains("400 Response"))
            assertTrue(call(server, body = "x".repeat(4097)).contains("413 Response"))
            assertTrue(call(server, body = "[]").contains("400 Response"))
            assertEquals(0, calls.get())
        }
    }

    @Test fun slowBrowserDoesNotBlockControlAndCloseInterruptsReads() {
        val server = PhoneControlServer("127.0.0.1") { JSONObject().put("ok", true) }
        val uri = URI(server.url)
        val slow = Socket("127.0.0.1", uri.port)
        try {
            slow.getOutputStream().write("POST ".toByteArray())
            assertTrue(call(server).contains("200 Response"))
            server.close()
            assertTrue(server.awaitClosed())
            assertTrue(runCatching { call(server) }.isFailure)
        } finally { slow.close(); server.close() }
    }

    @Test fun workerCountIsBoundedAndBlockedCallbacksAreInterrupted() {
        val entered = CountDownLatch(2)
        val hold = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val server = PhoneControlServer("127.0.0.1") {
            val current = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, current) }
            entered.countDown()
            try { hold.await(5, TimeUnit.SECONDS) } finally { active.decrementAndGet() }
            JSONObject()
        }
        val callers = (1..10).map { Thread { runCatching { call(server) } }.apply { start() } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(2, maximum.get())
            server.close()
            assertTrue(server.awaitClosed())
        } finally { hold.countDown(); server.close(); callers.forEach { it.join(4000) } }
        assertEquals(0, active.get())
    }

    @Test fun restartRevokesOldTokenAndOversizedResponsesAreRejected() {
        var oldPath = ""
        PhoneControlServer("127.0.0.1") { JSONObject() }.use { oldPath = URI(it.url).path }
        PhoneControlServer("127.0.0.1") { JSONObject().put("data", "x".repeat(270000)) }.use { server ->
            assertNotEquals(oldPath, URI(server.url).path)
            assertTrue(call(server, path = "$oldPath/api").contains("404 Response"))
            assertTrue(call(server).contains("413 Response"))
        }
    }
}
