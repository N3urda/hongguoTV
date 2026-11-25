package com.hongguotv.core

import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    private fun model(
        id: String = "123",
        typed: Boolean = false,
        stringModel: Boolean = false,
        onlyBytevc2: Boolean = false,
    ): String {
        val model = JSONObject("""{"video_list":[{"video_meta":{"codec_type":"bytevc2","vwidth":720,"vheight":1280},"main_url":"https://example.org/unsupported"},{"video_meta":{"codec_type":"h264","vwidth":720,"vheight":1280},"main_url":"https://example.org/video"}]}""")
        if (onlyBytevc2) model.getJSONArray("video_list").remove(1)
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

    @Test fun currentAppSignerNegotiatesCompatibleCodecWithoutForwardingSigningHeaders() {
        var requests = 0
        var signedBody = byteArrayOf()
        val native = object : MobilePlaybackSigner {
            override val currentAppProtocol = true
            override fun sign(url: String, body: ByteArray, device: Map<String, String>): MobileSignedRequest {
                signedBody = body
                return MobileSignedRequest(url, mapOf("X-Medusa" to "local-test-only"))
            }
        }
        val apiModel = JSONObject(model(onlyBytevc2 = true))
        apiModel.getJSONObject("data").getJSONObject("123").getJSONObject("video_model")
            .put("fallback_api", JSONObject().put("fallback_api", "https://vas.example.org/video/fplay?codec_type=4"))
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests++
            val payload = if (requests == 1) {
                assertEquals("reading.snssdk.com", request.url.host)
                assertEquals("74532", request.url.queryParameter("version_code"))
                assertEquals("local-test-only", request.header("X-Medusa"))
                assertArrayEquals(signedBody, Buffer().apply { request.body!!.writeTo(this) }.readByteArray())
                val body = JSONObject(GZIPInputStream(signedBody.inputStream()).reader().readText())
                assertEquals("preload", body.getString("dr_scene"))
                assertEquals(4, body.getJSONObject("biz_param").getInt("device_level"))
                assertEquals(1024, body.getJSONObject("biz_param").getInt("video_platform"))
                apiModel.toString()
            } else {
                assertEquals("vas.example.org", request.url.host)
                assertEquals("1", request.url.queryParameter("codec_type"))
                assertNull(request.header("X-Medusa"))
                assertNull(request.header("Cookie"))
                """{"video_info":{"data":{"video_list":{"video_1":{"codec_type":"h265","vwidth":1920,"vheight":1080,"main_url":"https://example.org/cdn.mp4"}}}}}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody()).build()
        }.build()
        val ladder = MobilePlayback.ladder("123", device, transport, native, 1000)
        assertEquals(2, requests)
        assertEquals("https://example.org/cdn.mp4", ladder.renditions.single().url)
    }

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
        val recovery = requests[1]
        for (parameter in listOf("version_name", "version_code", "channel", "device_type", "os_version")) {
            assertEquals(requests[0].url.queryParameter(parameter), recovery.url.queryParameter(parameter))
        }
        // The previous fallback passed all response fixtures while still emitting v3 signatures.
        val ts = recovery.header("x-khronos")!!.toInt()
        val medusa = decode64(recovery.header("x-medusa")!!)
        assertEquals(5, medusa.i32(0, true) xor ts)
        assertEquals(0, Signer(mobileV5 = true).branch(recovery.url.encodedQuery!!, ByteArray(0), ts))
        assertNull(recovery.body)
        for (header in listOf("Cookie", "Content-Encoding", "Content-Type", "x-ss-stub", "x-argus", "x-gorgon")) {
            assertNull(header, recovery.header(header))
        }
    }

    @Test fun videoTabSignsTheFinalEpisodeQueryWithEmptyBytesAndHonorsCancellation() {
        val request = MobilePlayback.videoTabRequest("123", device, "example.test", 1000,
            MobilePlaybackSigner { url, bytes, identity ->
                assertTrue(bytes.isEmpty())
                assertEquals(device, identity)
                val parsed = url.toHttpUrl()
                assertEquals("123", parsed.queryParameter("video_id"))
                assertEquals("", parsed.queryParameter("from_video_id"))
                MobileSignedRequest(parsed.newBuilder().setQueryParameter("_rticket", "1001").build().toString(),
                    mapOf("x-medusa" to "signed-query", "x-ss-stub" to "empty-body-digest"))
            }).build()
        assertEquals("1001", request.url.queryParameter("_rticket"))
        assertEquals("signed-query", request.header("x-medusa"))
        assertNull(request.header("x-ss-stub"))

        val scope = RequestScope()
        val requests = mutableListOf<Request>()
        val repo = repositoryWithModels("", requests = requests)
        assertThrows(IOException::class.java) {
            scope.run { repo.streamLadderWithCompatibility("123", onPrimaryEmpty = scope::cancel) }
        }
        assertEquals(listOf("POST"), requests.map { it.method })
    }

    @Test fun bothEmptyModelsRetainResponseDiagnosticsForRecovery() {
        val requests = mutableListOf<Request>()
        val repository = repositoryWithModels("", "", requests)

        val error = assertThrows(EmptyPlaybackResponseException::class.java) {
            repository.streamLadderWithCompatibility("123")
        }

        assertTrue(error.allPlaybackModelsEmpty)
        assertEquals("stage=mobile-model", error.detail)
        val primary = error.suppressed.filterIsInstance<EmptyPlaybackResponseException>().single()
        assertEquals("mobile-model=200/0B", (primary.suppressed.single() as PlaybackDiagnosticException).detail)
        val tab = error.suppressed.filterIsInstance<PlaybackDiagnosticException>().single()
        assertTrue(tab.detail.startsWith("video-tab-empty ") && tab.detail.contains("200/0B"))
        assertEquals(listOf("POST", "GET"), requests.map { it.method })
    }

    @Test fun emptyPrimaryDefersBackgroundWorkAcrossRepositoriesUntilRecoveryOrExpiry() {
        val session = PlaybackDeviceSession { DeviceRegister.Registered("123456", "654321") }
        var now = 1000L
        val empty = repositoryWithModels("", "", mutableListOf(), session = session, clock = { now })
        val healthy = repositoryWithModels(model(), requests = mutableListOf(), session = session, clock = { now })

        assertFalse(healthy.shouldDeferPlaybackPrefetch())
        assertThrows(EmptyPlaybackResponseException::class.java) { empty.streamLadderWithCompatibility("123") }
        assertTrue(healthy.shouldDeferPlaybackPrefetch())
        now += 4_999
        assertTrue(healthy.shouldDeferPlaybackPrefetch())
        now++
        assertFalse(healthy.shouldDeferPlaybackPrefetch())

        assertThrows(EmptyPlaybackResponseException::class.java) {
            empty.streamLadderWithCompatibility("123", speculative = true)
        }
        assertTrue(healthy.shouldDeferPlaybackPrefetch())
        assertEquals(1, healthy.streamLadderWithCompatibility("123").renditions.size)
        assertFalse(empty.shouldDeferPlaybackPrefetch())
    }

    @Test fun tabTimeoutPreservesTheEmptyPrimaryResponse() {
        val requests = mutableListOf<Request>()
        val timeout = java.io.InterruptedIOException("deadline reached")
        val repository = repositoryWithModels("", requests = requests, tabFailure = timeout)

        val error = assertThrows(java.io.InterruptedIOException::class.java) {
            repository.streamLadderWithCompatibility("123")
        }

        assertSame(timeout, error)
        val primary = error.suppressed.filterIsInstance<EmptyPlaybackResponseException>().single()
        assertFalse(primary.allPlaybackModelsEmpty)
        assertEquals("stage=mobile-model", primary.detail)
        assertEquals("mobile-model=200/0B", (primary.suppressed.single() as PlaybackDiagnosticException).detail)
        val tab = error.suppressed.filterIsInstance<PlaybackDiagnosticException>().single()
        assertTrue(tab.detail.startsWith("video-tab ") && tab.detail.endsWith("=timeout"))
        assertEquals(listOf("POST", "GET"), requests.map { it.method })
    }

    @Test fun tabFallbackDoesNotSelectAnotherEpisode() {
        val requests = mutableListOf<Request>()
        val repository = repositoryWithModels("", model(id = "456"), requests)

        val error = assertThrows(EmptyPlaybackResponseException::class.java) {
            repository.streamLadderWithCompatibility("123")
        }
        assertEquals("stage=mobile-model", error.detail)
        assertFalse(error.allPlaybackModelsEmpty)
        assertEquals(listOf("POST", "GET"), requests.map { it.method })
    }

    @Test fun bytevc2OnlyTabModelUsesAlternateMobileHostWithTheSameIdentity() {
        val requests = mutableListOf<Request>()
        val repository = repositoryWithModels(
            mobile = "",
            tab = model(stringModel = true, onlyBytevc2 = true),
            requests = requests,
            alternateMobile = model(typed = true),
        )

        val rendition = repository.streamLadderWithCompatibility("123").renditions.single()
        assertEquals("https://example.org/video", rendition.url)
        assertEquals(
            listOf(
                "POST" to "reading.snssdk.com",
                "GET" to "api5-normal-sinfonlineb.fqnovel.com",
                "POST" to "api5-normal-sinfonlineb.fqnovel.com",
            ),
            requests.map { it.method to it.url.host },
        )
        assertEquals(requests[0].url.encodedPath, requests[2].url.encodedPath)
        for (parameter in listOf("iid", "device_id", "cdid")) {
            assertEquals(listOf(requests[0].url.queryParameter(parameter)), requests.map { it.url.queryParameter(parameter) }.distinct())
        }
    }

    @Test fun emptyAlternateModelRetainsTheUnsupportedTabAndPrimaryDiagnostics() {
        val requests = mutableListOf<Request>()
        val repository = repositoryWithModels(
            mobile = "",
            tab = model(onlyBytevc2 = true),
            requests = requests,
            alternateMobile = "",
        )

        val error = assertThrows(EmptyPlaybackResponseException::class.java) {
            repository.streamLadderWithCompatibility("123")
        }
        assertFalse(error.allPlaybackModelsEmpty)
        assertEquals("stage=mobile-model", error.suppressed.filterIsInstance<EmptyPlaybackResponseException>().single().detail)
        assertTrue(error.suppressed.filterIsInstance<PlaybackDiagnosticException>().any {
            it.detail.startsWith("video-tab-no-compatible-source ") && it.detail.endsWith("alternate-model=b")
        })
        assertEquals(listOf("POST", "GET", "POST"), requests.map { it.method })
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
        session: PlaybackDeviceSession = PlaybackDeviceSession { DeviceRegister.Registered("123456", "654321") },
        clock: () -> Long = System::currentTimeMillis,
        tabFailure: IOException? = null,
        alternateMobile: String? = null,
    ): ContentRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            if (request.method == "GET" && tabFailure != null) throw tabFailure
            val body = when {
                request.method == "GET" -> tab
                request.url.host == "api5-normal-sinfonlineb.fqnovel.com" ->
                    checkNotNull(alternateMobile) { "Unexpected alternate mobile model request" }
                else -> mobile
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).apply {
                    retryAfter?.let { header("Retry-After", it) }
                }.build()
        }.build()
        return ContentRepository(http = client, clock = clock, playbackSession = session)
    }
}
