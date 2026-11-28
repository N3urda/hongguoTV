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
}
