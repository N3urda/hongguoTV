// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MobilePlaybackRecoveryTest {
    private val model = """{"code":0,"data":{"123":{"video_model":{"video_list":[{"codec_type":"h264","vwidth":720,"vheight":1280,"main_url":"https://example.org/video.mp4"}]}}}}"""

    private inner class Fixture {
        var registrations = 0
        var body: (Request) -> String = { model }
        var status = 200
        var retryAfter: String? = null
        var onRegistration: () -> Unit = {}
        val requests = mutableListOf<Request>()
        val deadlines = mutableListOf<Long>()
        val session = PlaybackDeviceSession {
            registrations++
            deadlines += RequestScope.deadlineAtMost(Long.MAX_VALUE)
            onRegistration()
            DeviceRegister.Registered((100 + registrations).toString(), (200 + registrations).toString())
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            deadlines += chain.call().timeout().deadlineNanoTime()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(body(request).toResponseBody()).apply {
                    retryAfter?.let { header("Retry-After", it) }
                }.build()
        }.build()
        fun repository() = ContentRepository(http, playbackSession = session)
        fun prime() { assertEquals(1, repository().streamLadderWithCompatibility("123").renditions.size) }
    }

    @Test fun previouslyWorkingSessionRecoversAfterBothModelsBecomeEmpty() {
        var now = 1_800_000_000_000L
        var registrations = 0
        var expired = false
        val requests = mutableListOf<Request>()
        val session = PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered((100 + registrations).toString(), (200 + registrations).toString())
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = if (expired && request.url.queryParameter("device_id") == "101") "" else model
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val repository = ContentRepository(http, clock = { now }, playbackSession = session)
        assertEquals(1, repository.streamLadderWithCompatibility("123").renditions.size)

        // Simulate an upstream session that stops serving models after overnight standby.
        // Advancing the local clock alone does not imply that upstream identities have a daily TTL.
        now += 24 * 60 * 60 * 1000L
        assertEquals(1, repository.streamLadderWithCompatibility("123").renditions.size)
        assertEquals(1, registrations)
        expired = true
        val resumed = ContentRepository(http, clock = { now }, playbackSession = session)
        assertEquals(1, resumed.streamLadderWithCompatibility("123").renditions.size)
        assertEquals(2, registrations)
        assertEquals(listOf("POST", "POST", "POST", "GET", "POST"), requests.map { it.method })
        assertEquals(listOf("101", "101", "101", "101", "102"), requests.map { it.url.queryParameter("device_id") })
        assertEquals(1, repository.streamLadderWithCompatibility("123").renditions.size)
        assertEquals(2, registrations)
    }

    @Test fun aSessionThatPreviouslyPlayedThroughTheTabFallbackCanRecover() {
        val fixture = Fixture().apply { body = { if (it.method == "POST") "" else model }; prime() }
        fixture.body = { if (it.url.queryParameter("device_id") == "101") "" else model }
        fixture.prime()
        assertEquals(2, fixture.registrations)
        assertEquals(listOf("POST", "GET", "POST", "GET", "POST"), fixture.requests.map { it.method })
    }

    @Test fun freshEmptyIdentityAndBackgroundFailuresNeverTriggerRegistration() {
        val fresh = Fixture().apply { body = { "" } }
        repeat(3) {
            assertThrows(EmptyPlaybackResponseException::class.java) {
                fresh.repository().streamLadderWithCompatibility("123")
            }
        }
        assertEquals(1, fresh.registrations)

        val working = Fixture().apply { prime(); requests.clear(); body = { "" } }
        assertThrows(EmptyPlaybackResponseException::class.java) {
            working.repository().streamLadderWithCompatibility("123", speculative = true)
        }
        assertEquals(1, working.registrations)
        assertEquals(listOf("POST"), working.requests.map { it.method })
    }

    @Test fun denialBackoffAndPartialFailuresDoNotRotateAWorkingIdentity() {
        for (scenario in listOf("denial", "http403", "http429", "backoff", "malformed", "timeout")) {
            val fixture = Fixture().apply { prime() }
            fixture.body = { request ->
                when (scenario) {
                    "denial" -> """{"code":110001}"""
                    "malformed" -> if (request.method == "POST") "" else "not-json"
                    "timeout" -> throw java.net.SocketTimeoutException("fixture")
                    else -> ""
                }
            }
            fixture.status = when (scenario) { "http403" -> 403; "http429" -> 429; else -> 200 }
            if (scenario == "backoff") fixture.retryAfter = "30"
            assertThrows(IOException::class.java) { fixture.repository().streamLadderWithCompatibility("123") }
            assertEquals(scenario, 1, fixture.registrations)
        }
    }

    @Test fun replacementIsAttemptedOnceWithinTheOriginalDeadlineAndRetainsDiagnostics() {
        val fixture = Fixture().apply { prime(); requests.clear(); deadlines.clear(); body = { "" } }
        val error = assertThrows(EmptyPlaybackResponseException::class.java) {
            fixture.repository().streamLadderWithCompatibility("123")
        }
        assertEquals(2, fixture.registrations)
        assertEquals(listOf("POST", "GET", "POST", "GET"), fixture.requests.map { it.method })
        assertEquals(1, fixture.deadlines.distinct().size)
        assertTrue(error.suppressed.filterIsInstance<EmptyPlaybackResponseException>().any { it.allPlaybackModelsEmpty })
        // An unproven replacement must not start a registration loop on subsequent retries.
        repeat(3) {
            assertThrows(EmptyPlaybackResponseException::class.java) {
                fixture.repository().streamLadderWithCompatibility("123")
            }
        }
        assertEquals(2, fixture.registrations)
    }

    @Test fun failedRegistrationKeepsTheOriginalIdentityAndSharesCooldownAcrossRepositories() {
        val fixture = Fixture().apply { prime(); body = { "" } }
        val original = fixture.session.existing()
        fixture.onRegistration = { throw IOException("fixture registration unavailable") }
        repeat(3) {
            assertThrows(EmptyPlaybackResponseException::class.java) {
                fixture.repository().streamLadderWithCompatibility("123")
            }
        }
        assertEquals(2, fixture.registrations)
        assertSame(original, fixture.session.existing())
    }

    @Test fun cancellationDuringReplacementDoesNotCacheOrRequestTheReplacement() {
        val fixture = Fixture().apply { prime(); requests.clear(); body = { "" } }
        val original = fixture.session.existing()
        val scope = RequestScope()
        fixture.onRegistration = scope::cancel
        assertThrows(IOException::class.java) {
            scope.run { fixture.repository().streamLadderWithCompatibility("123") }
        }
        assertSame(original, fixture.session.existing())
        assertEquals(listOf("POST", "GET"), fixture.requests.map { it.method })
        assertEquals(2, fixture.registrations)
    }

    @Test fun recoveryCooldownAlsoAppliesAfterAReplacementHasPlayed() {
        var registrations = 0
        val session = PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered((100 + registrations).toString(), (200 + registrations).toString())
        }
        val http = OkHttpClient()
        val original = session.profile(http).also { it.recordPlayableModel() }
        val start = TimeUnit.DAYS.toNanos(1)
        val replacement = session.recoverEmptyModels(http, original, start)!!
        replacement.recordPlayableModel()
        assertNull(session.recoverEmptyModels(http, replacement, start + TimeUnit.SECONDS.toNanos(30) - 1))
        assertEquals(2, registrations)
        assertNotNull(session.recoverEmptyModels(http, replacement, start + TimeUnit.SECONDS.toNanos(30)))
        assertEquals(3, registrations)
    }
}
