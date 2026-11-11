// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RelayClientTest {
    private lateinit var context: android.content.Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = RelayClient(OkHttpClient(), "127.0.0.1:${server.port}")

    @Test
    fun `地址规范化补默认端口并校验形态`() {
        assertEquals("192.168.1.100:46521", RelayClient.normalizeHost("192.168.1.100"))
        assertEquals("192.168.1.100:8080", RelayClient.normalizeHost(" 192.168.1.100:8080 "))
        assertNull(RelayClient.normalizeHost(""))
        assertNull(RelayClient.normalizeHost("192.168.1.100:80"))
        assertNull(RelayClient.normalizeHost("192.168.1.100:99999"))
        assertNull(RelayClient.normalizeHost("http://192.168.1.100:46521"))
        assertNull(RelayClient.normalizeHost("192.168.1.100:端口"))
    }

    @Test
    fun `伴机失败文案经摘要原样透出`() {
        assertEquals(
            "伴机不可达（1.2.3.4:46521）",
            playbackFailureSummary(RelayException("伴机不可达（1.2.3.4:46521）")),
        )
        assertEquals(
            "本集没有 bytevc2 档位，伴机无用武之地",
            playbackFailureSummary(RelayException("本集没有 bytevc2 档位，伴机无用武之地")),
        )
    }

    @Test
    fun `health 解析正常响应`() {
        server.enqueue(
            MockResponse().setBody(
                JSONObject().put("ok", true).put("version", "0.1.0-mvp").put("ffmpegOk", true).toString(),
            ),
        )
        val health = client().health()!!
        assertTrue(health.optBoolean("ffmpegOk"))
        assertEquals("0.1.0-mvp", health.optString("version"))
    }

    @Test
    fun `不可达与非 2xx 一律降级为 null`() {
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        val client = client()
        assertNull(client.health())
        assertNull(client.createSession("https://cdn/x", null, "720P"))
        assertFalse(client.stop("abc"))
        // 已关端口：伴机不可达
        val port = server.port
        server.shutdown()
        assertNull(RelayClient(OkHttpClient(), "127.0.0.1:$port").health())
    }

    @Test
    fun `createSession 返回会话与播放地址`() {
        server.enqueue(
            MockResponse().setBody(
                JSONObject()
                    .put("sessionId", "abc123")
                    .put("hlsUrl", "http://192.168.1.100:46521/hls/abc123/index.m3u8")
                    .toString(),
            ),
        )
        val session = client().createSession("https://cdn/x", "a2V5", "720P")!!
        assertEquals("abc123", session.first)
        assertEquals("http://192.168.1.100:46521/hls/abc123/index.m3u8", session.second)
        val recorded = server.takeRequest()
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("https://cdn/x", body.optString("url"))
        assertEquals("a2V5", body.optString("keyB64"))
        assertEquals("720P", body.optString("quality"))
        assertEquals("/session", recorded.path)
    }

    @Test
    fun `409 占用时自动结束占用会话并重试一次`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody(JSONObject().put("error", "伴机正被其他播放占用").put("sessionId", "old1").toString()),
        )
        server.enqueue(MockResponse().setResponseCode(200)) // 旧会话 DELETE 的应答
        server.enqueue(
            MockResponse().setBody(
                JSONObject()
                    .put("sessionId", "new1")
                    .put("hlsUrl", "http://192.168.1.100:46521/hls/new1/index.m3u8")
                    .toString(),
            ),
        )
        val session = client().createSession("https://cdn/x", null, "720P")!!
        assertEquals("new1", session.first)
        assertEquals("/session", server.takeRequest().path)
        assertEquals("/session/old1", server.takeRequest().path) // 主动结束占用会话
        assertEquals("/session", server.takeRequest().path) // 重试
    }

    @Test
    fun `令牌随请求头透出`() {
        server.enqueue(
            MockResponse().setBody(JSONObject().put("ok", true).put("ffmpegOk", true).toString()),
        )
        RelayClient(OkHttpClient(), "127.0.0.1:${server.port}", "abcd1234").health()
        assertEquals("abcd1234", server.takeRequest().getHeader("X-Relay-Token"))
    }

    @Test
    fun `设置持久化开关与地址`() {
        val store = RelayStore(context)
        assertFalse(store.enabled)
        store.enabled = true
        store.host = "192.168.1.100:46521"
        assertTrue(RelayStore(context).enabled)
        assertEquals("192.168.1.100:46521", RelayStore(context).host)
    }
}
