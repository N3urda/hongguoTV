// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.ContentRepository
import com.hongguotv.core.DeviceRegister
import com.hongguotv.core.EmptyPlaybackResponseException
import com.hongguotv.core.PlaybackDeviceSession
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PlaybackRetryConnectionTest {
    @Test fun http2RetriesActuallyUseFreshConnectionsAndLeaveSharedTransportUsable() {
        val server = MockWebServer()
        server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        server.start()
        val http = OkHttpClient.Builder()
            .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            }.build()
        try {
            repeat(4) { server.enqueue(MockResponse().setBody("")) }
            val session = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }
            val repo = ContentRepository(http, playbackSession = session, sleep = {})
            val error = assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") }
            val requests = (1..4).map { server.takeRequest(5, TimeUnit.SECONDS)!! }
            assertEquals(listOf(0, 0, 0, 0), requests.map { it.sequenceNumber })
            assertTrue(requests[0].path!!.startsWith("/reading/bookapi/video_tab/"))
            assertTrue(requests.drop(1).all { it.path!!.startsWith("/novel/player/") })
            assertTrue(error.detail.orEmpty().contains("G:b=200/0B/h2"))
            assertTrue(error.detail.orEmpty().contains("P:hl=200/0B/h2"))
            assertEquals(4, error.detail.orEmpty().split(" ").size)
            server.enqueue(MockResponse().setBody("still usable"))
            http.newCall(Request.Builder().url(server.url("/other")).build()).execute().use {
                assertEquals("still usable", it.body!!.string())
            }
            assertEquals(1, server.takeRequest(5, TimeUnit.SECONDS)!!.sequenceNumber)
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }
}
