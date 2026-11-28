package com.hongguotv.nativeapp

import com.hongguotv.core.ContentRepository
import com.hongguotv.core.VideoTracks
import java.nio.ByteBuffer
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
class PlaybackCodedSizeTest {
    private fun box(type: String, bytes: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(8 + bytes.size).putInt(8 + bytes.size).put(type.toByteArray()).put(bytes).array()

    private fun video(width: Int, height: Int): ByteArray {
        val sample = ByteBuffer.allocate(40).apply { position(24); putShort(width.toShort()); putShort(height.toShort()) }.array()
        val stsd = box("stsd", ByteBuffer.allocate(8).putInt(0).putInt(1).array() + box("hvc1", sample))
        return box("ftyp") + box("moov", box("trak", box("mdia", box("minf", box("vmhd") + box("stbl", stsd)))))
    }

    @Test fun mislabeledHighResolutionIsSkippedBeforeItReachesTheDecoder() {
        val requestedMedia = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
            if (request.url.encodedPath.endsWith("/preload/v1")) {
                response.code(200).body("""{"data":{"101":{"video_model":{"video_list":[
                  {"main_url":"https://cdn.example.test/high","video_meta":{"definition":"720p","codec_type":"h265","vwidth":720,"vheight":1280}},
                  {"main_url":"https://cdn.example.test/low","video_meta":{"definition":"480p","codec_type":"h265","vwidth":480,"vheight":854}}
                ]}}}}""".toResponseBody()).build()
            } else {
                requestedMedia += request.url.encodedPath
                val bytes = if (request.url.encodedPath == "/high") video(1080, 1920) else video(480, 854)
                response.code(206).header("Content-Range", "bytes 0-${bytes.lastIndex}/${bytes.size}")
                    .body(bytes.toResponseBody()).build()
            }
        }.build()
        val repo = ContentRepository(http, playbackSession = testPlaybackDeviceSession())
        checkNotNull(openStream(http, repo, "101", 720, { true }, PlaybackSourceCache()) { true }).use {
            assertEquals(480, it.rendition?.quality)
            assertEquals(VideoTracks.Track("hvc1", 480, 854), it.track)
        }
        assertEquals(listOf("/high", "/low"), requestedMedia)
    }

    @Test fun cachedAboveLimitRenditionCannotBypassTheQualitySetting() {
        val cache = PlaybackSourceCache { 0L }
        cache.remember("101", com.hongguotv.core.Rendition(1080, "h265", 1080, 1920, "https://cdn.example.test/video", null), 720)
        assertNull(cache.find("101", 720))
        assertNotNull(cache.find("101", 1080))
    }
    private fun qualityFixture(
        highWidth: Int = 1080,
        run: (OkHttpClient, ContentRepository, MutableList<String>) -> Unit,
    ) {
        val requests = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
            if (request.url.encodedPath.endsWith("/preload/v1")) {
                response.code(200).body("""{"data":{"101":{"video_model":{"video_list":[
                  {"main_url":"https://cdn.example.test/high","video_meta":{"definition":"1080p","codec_type":"h265","vwidth":1080,"vheight":1920}},
                  {"main_url":"https://cdn.example.test/low","video_meta":{"definition":"540p","codec_type":"h265","vwidth":540,"vheight":960}}
                ]}}}}""".toResponseBody()).build()
            } else {
                requests += request.url.encodedPath
                val bytes = if (request.url.encodedPath == "/high") video(highWidth, 1920) else video(540, 960)
                response.code(206).header("Content-Range", "bytes 0-${bytes.lastIndex}/${bytes.size}")
                    .body(bytes.toResponseBody()).build()
            }
        }.build()
        run(http, ContentRepository(http, playbackSession = testPlaybackDeviceSession()), requests)
    }

    @Test fun disabledFallbackKeepsHighQualityAndUsesHighestAvailableWhenSourceIsLimited() = qualityFixture { http, repo, requests ->
        for ((maximum, expected) in listOf(1080 to 1080, 720 to 540)) {
            checkNotNull(openStream(http, repo, "101", maximum, { true }, PlaybackSourceCache(),
                allowQualityFallback = false) { true }).use { assertEquals(expected, it.rendition?.quality) }
        }
        assertEquals(listOf("/high", "/low"), requests)
    }

    @Test fun disabledFallbackDoesNotSkipTo540AfterRejectingThe1080Header() = qualityFixture { http, repo, requests ->
        assertThrows(NoPlayableQualityException::class.java) {
            openStream(http, repo, "101", 1080, { true }, PlaybackSourceCache(),
                allowQualityFallback = false) { it?.width == 540 }
        }
        assertEquals(listOf("/high"), requests)
        checkNotNull(openStream(http, repo, "101", 1080, { true }, PlaybackSourceCache()) {
            it?.width == 540
        }).use { assertEquals(540, it.rendition?.quality) }
        assertEquals(listOf("/high", "/high", "/low"), requests)
    }

    @Test fun disabledFallbackDoesNotChooseLowerTierBeforeCapabilityFiltering() = qualityFixture { http, repo, requests ->
        assertThrows(NoPlayableQualityException::class.java) {
            openStream(http, repo, "101", 1080, { it.quality == 540 }, PlaybackSourceCache(),
                allowQualityFallback = false) { true }
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun disabledFallbackStillHonorsTheExplicitMaximumCodedSize() = qualityFixture(2160) { http, repo, requests ->
        assertThrows(NoPlayableQualityException::class.java) {
            openStream(http, repo, "101", 1080, { true }, PlaybackSourceCache(),
                allowQualityFallback = false) { true }
        }
        assertEquals(listOf("/high"), requests)
    }

    @Test fun previouslyDowngradedCachedSourceCannotOverrideManualQuality() {
        val cache = PlaybackSourceCache { 0L }
        val low = com.hongguotv.core.Rendition(540, "h265", 540, 960, "https://cdn.example.test/low", null)
        cache.remember("101", low, 1080)
        assertNull(cache.find("101", 1080, allowQualityFallback = false))
        assertNotNull(cache.find("101", 1080))
        cache.remember("101", low, 720, allowQualityFallback = false)
        assertNotNull(cache.find("101", 720, allowQualityFallback = false))
        assertNull(cache.find("101", 1080, allowQualityFallback = false))
        cache.clear()
        assertNull(cache.find("101", 720))
    }

}
