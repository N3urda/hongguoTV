// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.ContentRepository
import com.hongguotv.core.EmptyPlaybackResponseException
import com.hongguotv.core.PlaybackSourceException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PlaybackSourceRecoveryTest {
    private class Fixture {
        private val mp4 = ByteBuffer.allocate(16)
            .putInt(8).put("ftyp".toByteArray())
            .putInt(8).put("moov".toByteArray()).array()
        var now = 0L
        var primaryEmpty = false
        var backupTimesOut = false
        var backupWaitsForCdn = false
        var backupStatus = 200
        var cdnStatus = 206
        private val cachedCdnStarted = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<String>()
        val recentSources = PlaybackSourceCache { now }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
            when (request.url.encodedPath) {
                "/novel/player/multi_video_model/preload/v1" -> {
                    requests += "primary"
                    val model = if (primaryEmpty) "" else
                        """{"data":{"10101":{"video_model":{"video_list":[{"main_url":"https://cdn.example.test/episode.mp4","height":720,"codec_type":"h264"}]}}}}"""
                    response.code(200).body(model.toResponseBody()).build()
                }
                "/reading/bookapi/video_tab/multi_video_model/v1/" -> {
                    requests += "backup"
                    if (backupWaitsForCdn) assertTrue(cachedCdnStarted.await(1, TimeUnit.SECONDS))
                    if (backupTimesOut) throw InterruptedIOException("deadline reached")
                    response.code(backupStatus).body("".toResponseBody()).build()
                }
                "/episode.mp4" -> {
                    requests += "cdn"
                    if (primaryEmpty) cachedCdnStarted.countDown()
                    response.code(cdnStatus)
                        .header("Content-Range", "bytes 0-${mp4.lastIndex}/${mp4.size}")
                        .body(mp4.toResponseBody()).build()
                }
                else -> error("Unexpected playback request")
            }
        }.build()
        val session = testPlaybackDeviceSession()
        val repository = ContentRepository(http, playbackSession = session)

        fun open(
            id: String = "10101",
            quality: Int = 720,
            speculative: Boolean = false,
            repo: ContentRepository = repository,
        ): OpenedStream =
            checkNotNull(openStream(http, repo, id, quality, { true }, recentSources, speculative = speculative) { true })

        fun warm(quality: Int = 720) {
            open(quality = quality).close()
            requests.clear()
            primaryEmpty = true
        }

        fun assertWarmRecoveryRequests(modelAttempts: Int = 2) {
            assertEquals("primary", requests.first())
            assertEquals(mapOf("primary" to modelAttempts, "backup" to modelAttempts, "cdn" to 1), requests.groupingBy { it }.eachCount())
        }
    }

    @Test
    fun usesSameCdnWhenBothModelsReturnEmptyAndDoesNotExtendTheCacheLifetime() {
        val fixture = Fixture().apply { warm() }
        val playerRepository = ContentRepository(fixture.http, playbackSession = fixture.session)
        fixture.now = 299_999
        fixture.open(repo = playerRepository).use { assertEquals(16L, it.video.total) }
        fixture.assertWarmRecoveryRequests()

        fixture.now = 300_000
        fixture.requests.clear()
        val error = assertThrows(EmptyPlaybackResponseException::class.java) { fixture.open(repo = playerRepository) }
        assertTrue(error.allPlaybackModelsEmpty)
        assertEquals(listOf("primary", "backup"), fixture.requests)
    }

    @Test
    fun validatesTheRecentCdnWhileTheBackupModelIsStillPending() {
        val fixture = Fixture().apply {
            warm()
            backupWaitsForCdn = true
            backupTimesOut = true
        }
        fixture.open().use { assertEquals(16L, it.video.total) }
        fixture.assertWarmRecoveryRequests(modelAttempts = 1)
    }

    @Test
    fun refusesAnAboveLimitTierEvenWhenItIsTheOnlyAvailableSource() {
        val fixture = Fixture()
        assertThrows(NoPlayableQualityException::class.java) { fixture.open(quality = 480) }
        assertEquals(listOf("primary"), fixture.requests)
    }

    @Test
    fun skipsCacheForAnotherEpisodeBackgroundRetryOrAfterSelectingALowerQuality() {
        val fixture = Fixture().apply { warm() }
        assertThrows(EmptyPlaybackResponseException::class.java) { fixture.open("10102") }
        assertThrows(EmptyPlaybackResponseException::class.java) { fixture.open(speculative = true) }
        assertThrows(EmptyPlaybackResponseException::class.java) { fixture.open(quality = 480) }
        assertEquals(listOf("primary", "backup", "primary", "backup", "primary", "primary", "backup"), fixture.requests)
    }

    @Test
    fun evictsAnExpiredCdnAddressAndKeepsTheMetadataDiagnostics() {
        val fixture = Fixture().apply { warm(); cdnStatus = 403 }
        val error = assertThrows(EmptyPlaybackResponseException::class.java) { fixture.open() }
        assertTrue(diagnosticDetail(error)!!.contains("recent-source=unavailable"))
        assertTrue(error.allPlaybackModelsEmpty)
        fixture.assertWarmRecoveryRequests()

        fixture.requests.clear()
        assertThrows(EmptyPlaybackResponseException::class.java) { fixture.open() }
        assertEquals(listOf("primary", "backup"), fixture.requests)
    }

    @Test
    fun anExplicitModelRejectionInvalidatesThePreviouslyOpenedSource() {
        val fixture = Fixture().apply {
            warm()
            backupWaitsForCdn = true
            backupStatus = 403
        }
        val error = assertThrows(PlaybackSourceException::class.java) { fixture.open() }
        assertEquals(403, error.upstreamCode)
        fixture.assertWarmRecoveryRequests(modelAttempts = 1)

        fixture.backupWaitsForCdn = false
        fixture.backupStatus = 200
        fixture.requests.clear()
        assertThrows(EmptyPlaybackResponseException::class.java) { fixture.open() }
        assertEquals(listOf("primary", "backup", "primary", "backup"), fixture.requests)
    }
}
