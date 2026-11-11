package com.hongguotv.nativeapp

import java.net.InetAddress
import java.net.ServerSocket
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class FeedRedirectTest {
    @Test
    fun contentTransportFollowsRedirectInsteadOfSurfacing301() {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val errors = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val serving =
            Thread {
                    try {
                        repeat(2) {
                            server.accept().use { socket ->
                                socket.soTimeout = 5000
                                val reader = socket.getInputStream().bufferedReader()
                                val first = reader.readLine()
                                while (!reader.readLine().isNullOrEmpty()) {}
                                val response =
                                    if (first.contains("/start"))
                                        "HTTP/1.1 301 Moved Permanently\r\nLocation: /video\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    else
                                        "HTTP/1.1 200 OK\r\nContent-Length: 14\r\nConnection: close\r\n\r\nvideo-response"
                                socket.getOutputStream().apply {
                                    write(response.toByteArray())
                                    flush()
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        errors.set(error)
                    }
                }
                .apply { start() }
        val backend = PublicFeedBackend(720)
        try {
            val http =
                PublicFeedBackend::class
                    .java
                    .getDeclaredField("http")
                    .apply { isAccessible = true }
                    .get(backend) as OkHttpClient
            http
                .newCall(
                    Request.Builder().url("http://127.0.0.1:${server.localPort}/start").build()
                )
                .execute()
                .use {
                    assertEquals(200, it.code)
                    assertEquals("video-response", it.body!!.string())
                    assertEquals(301, it.priorResponse?.code)
                }
        } finally {
            backend.close()
            server.close()
            serving.join(5000)
        }
        assertNull(errors.get())
    }
}
