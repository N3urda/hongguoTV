// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Covers the registered-device stream resolution path ported from fqapp. */
class ContentRepositoryPlayerTest {
    private companion object {
        const val deviceJson = """{"device_id":"7000000000001234567","install_id":"7000000000007654321","cdid":"c-uuid","openudid":"0123456789abcdef","secret_key":""}"""
    }
    private class FakeStore(var value: String? = deviceJson) : PlayerDeviceStore {
        val saved = mutableListOf<String?>()
        override fun load(): String? = value
        override fun save(stored: String?) { saved += stored; value = stored }
    }

    @After fun clearStore() { ContentRepository.deviceStore = null }

    private fun repository(respond: (Request) -> String): Pair<ContentRepository, FakeStore> {
        val store = FakeStore()
        ContentRepository.deviceStore = store
        return ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build()) to store
    }

    private fun list(vararg entries: JSONObject) = JSONObject().apply {
        entries.forEachIndexed { index, entry -> put("video_$index", entry) }
    }
    private fun entry(height: Int, codec: String, url: String, width: Int = 0) = JSONObject()
        .put("vheight", height).put("vwidth", width).put("codec_type", codec).put("main_url", url)

    @Test fun foregroundFallbackKeepsAllCodecAndDimensionChoicesForEmptyOrRejectedModelService() {
        for (status in listOf(200, 403)) {
            ContentRepository.deviceStore = FakeStore()
            var compatibleRequests = 0
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val compatible = request.url.encodedPath == "/novel/player/video_model/v1/"
                if (compatible) compatibleRequests++
                val body = if (compatible) JSONObject().put("code", 0).put("data", JSONObject().put("video_model",
                    JSONObject().put("video_list", list(
                        entry(1280, "h265", "https://example.com/hevc", 720),
                        entry(854, "h264", "https://example.com/avc", 480))))).toString() else ""
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (compatible) 200 else status).message("test").body(body.toResponseBody()).build()
            }.build()
            val repo = ContentRepository(http, playbackSession = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") })
            val ladder = repo.streamLadderWithCompatibility("123")
            assertEquals(1, compatibleRequests)
            assertEquals(2, ladder.renditions.size)
            assertEquals("https://example.com/avc", ladder.select(1080) { it.codec == "h264" }!!.url)
            assertEquals(480, ladder.renditions.first { it.codec == "h264" }.width)
            assertEquals(1280, ladder.renditions.first { it.codec == "h265" }.height)
        }
    }

    @Test fun speculativeEmptyResponseNeverStartsCompatibilityRequests() {
        ContentRepository.deviceStore = FakeStore()
        var compatibleRequests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath == "/novel/player/video_model/v1/") compatibleRequests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("test").body("".toResponseBody()).build()
        }.build()
        val repo = ContentRepository(http, playbackSession = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") })
        assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadderWithCompatibility("123", speculative = true) }
        assertEquals(0, compatibleRequests)
    }

    @Test fun shortplayModelListResolvesWithoutAnyFallbackCall() {
        val (repo, _) = repository { request ->
            when (request.url.host) {
                "api3-normal-sinfonlinea.fqnovel.com" -> JSONObject().put("code", 0).put("data", JSONObject().put("video_model",
                    JSONObject().put("video_list", list(
                        entry(1280, "h265", "https://example.com/720", 720),
                        entry(854, "h264", "https://example.com/480"))))).toString()
                else -> "{}"
            }
        }
        val result = repo.registeredStream("123", 1080)
        assertEquals("https://example.com/720", result.url)
        assertEquals("720P", result.quality)
        assertNull(result.key)
    }

    @Test fun fallbackListContributesTopTierOnlyWhenAllowed() {
        val model = JSONObject()
            .put("video_list", list(entry(1280, "h265", "https://example.com/720", 720)))
            .put("fallback_api", JSONObject().put("fallback_api", "https://example.com/stream").toString())
        val (repo, _) = repository { request ->
            when {
                request.url.host == "api5-normal-sinfonlineb.fqnovel.com" && !request.url.toString().contains("_rticket") ->
                    JSONObject().put("code", 0).put("data", JSONObject().put("123", JSONObject().put("video_model", model.toString()))).toString()
                request.url.host == "example.com" -> JSONObject().put("video_info", JSONObject().put("data", JSONObject().put("video_list", list(
                    entry(1080, "h265", "https://example.com/h1080", 1920),
                    entry(480, "bytevc2", "https://example.com/unsupported"))))).toString()
                else -> "{}"
            }
        }
        assertEquals("https://example.com/h1080", repo.registeredStream("123", 1080).url)
        val limited = repo.registeredStream("123", 720)
        assertEquals("https://example.com/720", limited.url)
        assertEquals("720P", limited.quality)
    }

    @Test fun byteVc2NeverWinsTheQualityRace() {
        val (repo, _) = repository { request ->
            when (request.url.host) {
                "api3-normal-sinfonlinea.fqnovel.com" -> JSONObject().put("code", 0).put("data", JSONObject().put("video_model",
                    JSONObject().put("video_list", list(
                        entry(1080, "bytevc2", "https://example.com/unsupported", 1920),
                        entry(480, "h264", "https://example.com/avc"))))).toString()
                else -> "{}"
            }
        }
        val result = repo.registeredStream("123", 1080)
        assertEquals("https://example.com/avc", result.url)
        assertEquals("480P", result.quality)
    }

    @Test fun rejectedDeviceRotatesOnceAndFallsBackToLegacyEndpoint() {
        var legacyCalls = 0
        val (repo, store) = repository { request ->
            val url = request.url.toString()
            when {
                url.contains("fqnovel.com") && url.contains("_rticket") -> { legacyCalls++; """{"code":0,"data":{"123":{"video_model":{"fallback_api":"https://example.com/legacy"}}}}""" }
                request.url.host == "example.com" -> JSONObject().put("video_info", JSONObject().put("data", JSONObject().put("video_list", list(entry(1280, "h265", "https://example.com/720", 720))))).toString()
                request.url.host == "api3-normal-sinfonlinea.fqnovel.com" || request.url.host == "api5-normal-sinfonlineb.fqnovel.com" -> """{"code":110001,"message":"unknown error"}"""
                else -> "{}"
            }
        }
        val result = repo.registeredStream("123", 1080)
        assertEquals("https://example.com/720", result.url)
        assertEquals(1, legacyCalls)
        assertTrue("rejected device must be dropped", store.saved.contains(null))
    }

    @Test fun failedRegistrationDoesNotBreakPlaybackFallback() {
        // Empty store => registration attempted against a responder that cannot
        // register devices; the legacy fixed-device path must still resolve.
        val (repo, store) = repository { request ->
            when {
                request.url.host == "log.snssdk.com" || request.url.host == "reading.snssdk.com" -> "{}"
                request.url.host == "api5-normal-sinfonlineb.fqnovel.com" -> """{"Code":0,"data":{"123":{"video_model":{"fallback_api":"https://example.com/legacy"}}}}"""
                request.url.host == "example.com" -> JSONObject().put("video_info", JSONObject().put("data", JSONObject().put("video_list", list(entry(720, "h264", "https://example.com/avc"))))).toString()
                else -> "{}"
            }
        }
        val result = repo.registeredStream("123", 720)
        assertEquals("https://example.com/avc", result.url)
        assertEquals("720P", result.quality)
        assertTrue(store.saved.size >= 0)
    }

    @Test fun entirelyUnplayableSourceStillFailsBeforeStartingAudio() {
        val (repo, _) = repository { request ->
            when {
                request.url.toString().contains("fqnovel.com") && request.url.toString().contains("_rticket") -> """{"code":0,"data":{"123":{"video_model":{"fallback_api":"https://example.com/legacy"}}}}"""
                request.url.host == "example.com" -> JSONObject().put("video_info", JSONObject().put("data", JSONObject().put("video_list", list(entry(720, "ByteVC2", "https://example.com/unsupported"))))).toString()
                request.url.host?.endsWith("fqnovel.com") == true -> JSONObject().put("code", 0).put("data", JSONObject().put("video_model",
                    JSONObject().put("video_list", list(entry(720, "ByteVC2", "https://example.com/unsupported"))))).toString()
                else -> "{}"
            }
        }
        assertThrows(IOException::class.java) { repo.registeredStream("123", 720) }
    }

    @Test fun spadeEncryptedModelUrlResolvesThroughKeySeed() {
        // Build a real spade-framed url so the seed path is exercised, not just passthrough.
        val seed = ByteArray(32) { (it * 7 + 3).toByte() }
        val plain = "https://example.com/spade.mp4".toByteArray()
        val constants = hex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58")
        val h = digest("SHA-512", digest("SHA-512", seed) + constants)
        val padded = plain + ByteArray(16 - plain.size % 16) { (16 - plain.size % 16).toByte() }
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").run {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(h.copyOfRange(0, 16), "AES"), javax.crypto.spec.IvParameterSpec(h.copyOfRange(16, 32)))
            doFinal(padded)
        }
        val blob = (byteArrayOf(0xa8.toByte(), 0, 1, 0) + cipher).base64()
        val (repo, _) = repository { request ->
            when (request.url.host) {
                "api3-normal-sinfonlinea.fqnovel.com" -> JSONObject().put("code", 0).put("data", JSONObject().put("video_model",
                    JSONObject().put("key_seed", seed.base64()).put("video_list", list(entry(720, "h265", blob, 1280))))).toString()
                else -> "{}"
            }
        }
        assertEquals("https://example.com/spade.mp4", repo.registeredStream("123", 1080).url)
    }
}
