package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackCodecNegotiationTest {
    private val model = JSONObject().put("fallback_api", JSONObject()
        .put("fallback_api", "https://vas.example.org/video/fplay?codec_type=4"))
    private val primary = Rendition(1080, "h265", 1080, 1920, "https://cdn.example.org/primary", null)

    private fun transport(requests: MutableList<String>, lowerAvc: Boolean) =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val type = request.url.queryParameter("codec_type")!!
            requests += type
            val low = lowerAvc && type == "3"
            val codec = if (low) "h264" else "h265"
            val width = if (low) 720 else 1080
            val height = if (low) 1280 else 1920
            val payload = """{"video_info":{"data":{"video_list":{"video_1":{"codec_type":"$codec","vwidth":$width,"vheight":$height,"main_url":"https://cdn.example.org/$type"}}}}}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody()).build()
        }.build()

    @Test fun highHevcOnlyPrimaryNegotiatesALowerCompatibleRendition() {
        val requests = mutableListOf<String>()
        val acceptable: (Rendition) -> Boolean = { it.quality <= 720 && it.codec == "h264" }
        val ladder = MobilePlayback.compatibleLadder(model, transport(requests, true), listOf(primary), acceptable)
        assertEquals(listOf("1", "3"), requests)
        assertEquals(listOf(720), ladder.playbackOrder(720, acceptable).map { it.quality })
        assertEquals("h264", ladder.playbackOrder(720, acceptable).single().codec)
    }

    @Test fun unavailableLowerQualityStopsAfterTwoNegotiationsWithoutExceedingLimit() {
        val requests = mutableListOf<String>()
        val ladder = MobilePlayback.compatibleLadder(model, transport(requests, false), listOf(primary)) { it.quality <= 720 }
        assertEquals(listOf("1", "3"), requests)
        assertTrue(ladder.playbackOrder(720).isEmpty())
    }

    @Test fun playablePrimaryAndMissingFallbackDoNotAddNetworkRequests() {
        val requests = mutableListOf<String>()
        val transport = transport(requests, true)
        assertEquals(listOf(primary), MobilePlayback.compatibleLadder(model, transport, listOf(primary)).renditions)
        assertEquals(listOf(primary), MobilePlayback.compatibleLadder(JSONObject(), transport, listOf(primary)) { false }.renditions)
        assertTrue(requests.isEmpty())
    }
}
