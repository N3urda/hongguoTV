package com.hongguotv.core

import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobilePlaybackTest {
    private val device = mapOf("device_id" to "123456", "iid" to "654321", "cdid" to "test-device")
    private fun model(id: String = "123", typed: Boolean = false, stringModel: Boolean = false): String {
        val model = JSONObject("""{"video_list":[{"video_meta":{"codec_type":"bytevc2","vwidth":720,"vheight":1280},"main_url":"https://example.org/unsupported"},{"video_meta":{"codec_type":"h264","vwidth":720,"vheight":1280},"main_url":"https://example.org/video"}]}""")
        val entries = JSONObject().put(id, JSONObject().put("video_model", if (stringModel) model.toString() else model))
        return JSONObject().put("code", 0).put("data", if (typed) JSONObject().put("1004", entries) else entries).toString()
    }
    private fun http(body: String, inspect: (okhttp3.Request) -> Unit = {}) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
    private val signer = MobilePlaybackSigner { url, _, _ -> MobileSignedRequest(url, emptyMap()) }

    @Test fun supportsBothLiveResponseShapesWithoutSelectingOtherEpisodes() {
        for (typed in listOf(false, true)) for (stringModel in listOf(false, true)) {
            val rows = MobilePlayback.ladder("123", device, http(model(typed=typed, stringModel=stringModel)), signer, 1000).renditions
            assertEquals(1, rows.size)
            assertEquals(720, rows.single().quality)
            assertEquals("https://example.org/video", rows.single().url)
        }
        assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder("123", device, http(model("456")), signer, 1000)
        }
    }
    @Test fun signsAndSendsIdenticalCompressedBytesWithMatchingIdentityAndDigest() {
        var signed: ByteArray? = null
        var url: String? = null
        val signing = MobilePlaybackSigner { target, bytes, identity ->
            assertEquals(device, identity)
            signed = bytes.copyOf(); url = target
            MobileSignedRequest(target, mapOf("X-Argus" to "test-signature"))
        }
        MobilePlayback.ladder("123", device, http(model()) { request ->
            val bytes = Buffer().apply { request.body!!.writeTo(this) }.readByteArray()
            assertArrayEquals(signed, bytes)
            assertEquals(url, request.url.toString())
            assertEquals("/novel/player/multi_video_model/player/v1", request.url.encodedPath)
            assertEquals("123456", request.url.queryParameter("device_id"))
            assertEquals("654321", request.url.queryParameter("iid"))
            assertEquals("gzip", request.header("Content-Encoding"))
            assertEquals("test-signature", request.header("X-Argus"))
            assertNull(request.header("Cookie"))
            assertEquals(MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02X".format(it) }, request.header("x-ss-stub"))
            val body = JSONObject(GZIPInputStream(bytes.inputStream()).reader().readText())
            assertEquals("TV must not request the app-only bytevc2 capability tier", 1, body.getJSONObject("biz_param").getInt("device_level"))
            assertEquals("123", body.getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0))
        }, signing, 1000)
    }
    @Test fun rejectsBusinessErrorsAndOversizeResponse() {
        val error = assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder("123", device, http("""{"code":110001}"""), signer, 1000)
        }
        assertEquals(110001, error.upstreamCode)
        assertThrows(IOException::class.java) {
            MobilePlayback.ladder("123", device, http(" ".repeat(2 * 1024 * 1024 + 1)), signer, 1000)
        }
    }
    @Test fun cancellationBeforeOrDuringSigningNeverSendsRequest() {
        val transport = http(model()) { fail("cancelled request reached network") }
        for (before in listOf(false, true)) {
            val scope = RequestScope()
            if (before) scope.cancel()
            assertThrows(IOException::class.java) {
                scope.run { MobilePlayback.ladder("123", device, transport,
                    MobilePlaybackSigner { url, _, _ -> scope.cancel(); MobileSignedRequest(url, emptyMap()) }, 1000) }
            }
        }
    }
    @Test fun speculationCannotRegisterAnIdentity() {
        val session = PlaybackDeviceSession { throw AssertionError("background registration") }
            assertThrows(IOException::class.java) {
                ContentRepository(http = http(model()), playbackSession = session)
                    .streamLadderWithCompatibility("123", speculative = true)
            }
    }

    @Test fun emptyForegroundModelUsesTabWithTheSameRegisteredIdentity() {
        val requests = mutableListOf<Request>()
        val entries = JSONObject(model(stringModel = true)).getJSONObject("data")
        val tab = JSONObject().put("code", 0).put("data", JSONObject().put("video_model_data", entries))
        val repository = repositoryWithModels("", tab.toString(), requests)

        val rendition = repository.streamLadderWithCompatibility("123").renditions.single()
        assertEquals("https://example.org/video", rendition.url)
        assertEquals(720, rendition.quality)
        assertEquals(listOf("POST", "GET"), requests.map { it.method })
        assertEquals("123", requests[1].url.queryParameter("video_id"))
        for (parameter in listOf("iid", "device_id", "cdid")) {
            assertEquals(requests[0].url.queryParameter(parameter), requests[1].url.queryParameter(parameter))
        }
    }

    @Test fun tabFallbackDoesNotSelectAnotherEpisode() {
        val requests = mutableListOf<Request>()
        val repository = repositoryWithModels("", model(id = "456"), requests)

        val error = assertThrows(EmptyPlaybackResponseException::class.java) {
            repository.streamLadderWithCompatibility("123")
        }
        assertEquals("stage=mobile-model", error.detail)
        assertEquals(listOf("POST", "GET"), requests.map { it.method })
    }

    @Test fun healthyModelAndUpstreamBackoffDoNotUseTab() {
        val requests = mutableListOf<Request>()
        val healthy = repositoryWithModels(model(), requests = requests)
        assertEquals(1, healthy.streamLadderWithCompatibility("123").renditions.size)
        val busy = repositoryWithModels("", requests = requests, retryAfter = "30")
        assertThrows(PlaybackServiceBackoffException::class.java) {
            busy.streamLadderWithCompatibility("123")
        }
        assertEquals(listOf("POST", "POST"), requests.map { it.method })
    }

    private fun repositoryWithModels(
        mobile: String,
        tab: String = model(),
        requests: MutableList<Request>,
        retryAfter: String? = null,
    ): ContentRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = if (request.method == "POST") mobile else tab
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).apply {
                    retryAfter?.let { header("Retry-After", it) }
                }.build()
        }.build()
        val session = PlaybackDeviceSession { DeviceRegister.Registered("123456", "654321") }
        return ContentRepository(http = client, playbackSession = session)
    }
}
