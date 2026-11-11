package com.hongguotv.core

import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PhoneSearchServerTest {
    private fun request(
        server: PhoneSearchServer,
        method: String = "GET",
        path: String? = null,
        body: String = "",
        headers: String = "",
        host: String? = null,
    ): String {
        val uri = URI(server.url)
        return Socket("127.0.0.1", uri.port).use { socket ->
            socket.soTimeout = 3000
            val bytes = body.toByteArray(Charsets.UTF_8)
            socket
                .getOutputStream()
                .write(
                    ("$method ${path ?: uri.path} HTTP/1.1\r\nHost: ${host ?: uri.authority}\r\n" +
                            headers +
                            if (method == "POST")
                                "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${bytes.size}\r\n\r\n"
                            else "\r\n")
                        .toByteArray() + bytes
                )
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test
    fun `phone form accepts one unicode search without reflecting it in HTML`() {
        val received = CountDownLatch(1)
        var query: String? = ""
        PhoneSearchServer(
                "127.0.0.1",
                { value, _ ->
                    query = value
                    received.countDown()
                },
            )
            .use { server ->
                val page = request(server)
                assertTrue(page.startsWith("HTTP/1.1 200"))
                assertTrue(page.contains("Cache-Control: no-store"))
                assertTrue(page.contains("form-action 'self'"))
                val value = "测试 <script>alert(1)</script>"
                val reply =
                    request(
                        server,
                        "POST",
                        URI(server.url).path + "/submit",
                        "query=" + URLEncoder.encode(value, "UTF-8"),
                    )
                assertTrue(reply.startsWith("HTTP/1.1 200"))
                assertFalse(reply.contains(value))
                assertTrue(received.await(2, TimeUnit.SECONDS))
                assertEquals(value, query)
                assertTrue(runCatching { request(server) }.isFailure)
            }
    }

    @Test
    fun `same origin browser form can submit while opaque and foreign origins stay blocked`() {
        val received = CountDownLatch(1)
        PhoneSearchServer("127.0.0.1", { _, _ -> received.countDown() }).use { server ->
            val uri = URI(server.url)
            val page = request(server)
            // no-referrer makes native form POST origins opaque (Origin: null).
            assertTrue(page.contains("Referrer-Policy: same-origin\r\n"))
            assertFalse(page.contains("Referrer-Policy: no-referrer"))
            for (origin in listOf("null", "http://evil.example")) {
                assertTrue(
                    request(
                            server,
                            "POST",
                            uri.path + "/submit",
                            "query=test",
                            "Origin: $origin\r\n",
                        )
                        .startsWith("HTTP/1.1 403")
                )
            }
            assertTrue(
                request(
                        server,
                        "POST",
                        uri.path + "/submit",
                        "query=test",
                        "Origin: http://${uri.authority}\r\n",
                    )
                    .startsWith("HTTP/1.1 200")
            )
            assertTrue(received.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `connection status only reports a valid phone page request`() {
        var connections = 0
        PhoneSearchServer("127.0.0.1", { _, _ -> }, onConnected = { connections++ }).use { server ->
            request(server, path = "/wrong")
            assertEquals(0, connections)
            val page = request(server)
            assertEquals(1, connections)
            assertTrue(page.contains("全部短剧和漫剧"))
            assertFalse(page.contains("按当前选择"))
        }
    }

    @Test
    fun `wrong token host and cross site posts cannot submit`() {
        var submitted = false
        PhoneSearchServer("127.0.0.1", { _, _ -> submitted = true }).use { server ->
            assertTrue(request(server, path = "/wrong").startsWith("HTTP/1.1 404"))
            assertTrue(request(server, host = "evil.example").startsWith("HTTP/1.1 404"))
            assertTrue(
                request(
                        server,
                        "POST",
                        URI(server.url).path + "/submit",
                        "query=test",
                        "Origin: http://evil.example\r\n",
                    )
                    .startsWith("HTTP/1.1 403")
            )
            assertFalse(submitted)
        }
    }

    @Test
    fun `invalid and oversized input does not consume the session`() {
        PhoneSearchServer("127.0.0.1", { _, _ -> }).use { server ->
            for (body in
                listOf(
                    "query=",
                    "query=" + "x".repeat(81),
                    "query=a&query=b",
                    "query=hello%0Aworld",
                    "query=%zz",
                )) {
                val response = request(server, "POST", URI(server.url).path + "/submit", body)
                assertTrue(response, response.startsWith("HTTP/1.1 400"))
            }
            assertTrue(
                request(
                        server,
                        "POST",
                        URI(server.url).path + "/submit",
                        "query=" + "x".repeat(4096),
                    )
                    .startsWith("HTTP/1.1 413")
            )
            assertTrue(request(server).startsWith("HTTP/1.1 200"))
        }
    }

    @Test
    fun `closing or expiry makes a session unreachable`() {
        val closed = PhoneSearchServer("127.0.0.1", { _, _ -> })
        closed.close()
        assertTrue(runCatching { request(closed) }.isFailure)
        val expired = CountDownLatch(1)
        PhoneSearchServer(
                "127.0.0.1",
                { _, _ -> fail("expired session submitted") },
                { expired.countDown() },
                1,
            )
            .use { server ->
                assertTrue(expired.await(2, TimeUnit.SECONDS))
                assertTrue(runCatching { request(server) }.isFailure)
            }
    }
}
