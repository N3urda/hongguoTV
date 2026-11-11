package com.hongguotv.core

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContentRepositoryTest {
    private val playbackModel =
        """{"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.test/video","video_meta":{"definition":"720p","codec_type":"h264","vwidth":720,"vheight":1280},"encrypt_info":{"encrypt":false}}]}}}}"""

    @Test
    fun previewModelCanBeHandedToAnotherRepositoryOnceWithoutDuplicatingRequest() {
        var reads = 0
        val session = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            reads++
            fixtureResponse(chain.request(), videoTabModel)
        }.build()
        val preview = ContentRepository(http, playbackSession = session)
        val fullscreen = ContentRepository(http, playbackSession = session)
        preview.streamLadder("123")
        assertEquals(1, reads)
        fullscreen.streamLadder("123")
        assertEquals(1, reads)
        fullscreen.streamLadder("123")
        assertEquals(2, reads)
        preview.streamLadder("123")
        assertEquals(3, reads)
    }

    @Test
    fun modelHandoffExpiresAndDoesNotHideSameRepositoryManualRefresh() {
        var reads = 0
        var now = 1000L
        val session = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            reads++
            fixtureResponse(chain.request(), videoTabModel)
        }.build()
        val preview = ContentRepository(http, clock = { now }, playbackSession = session)
        preview.streamLadder("123")
        preview.streamLadder("123")
        assertEquals(2, reads)
        now += 15_001
        ContentRepository(http, clock = { now }, playbackSession = session).streamLadder("123")
        assertEquals(3, reads)
    }

    @Test
    fun sharedSessionPrefersWorkingPostBeforeRepeatingAnEmptyGet() {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            fixtureResponse(request, if (request.method == "GET") "" else batchPlaybackFixture(playbackRequestIds(request)))
        }.build()
        val session = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }
        ContentRepository(http, playbackSession = session).streamLadder("123")
        ContentRepository(http, playbackSession = session).streamLadder("456")
        assertEquals(listOf("GET", "POST", "POST"), requests.map { it.method })
        assertEquals("456", playbackRequestIds(requests.last()).single())
    }

    @Test
    fun learnsFailingPathsAndRecoversWithinTheSameForegroundBudget() {
        var registrations = 0
        var calls = 0
        val hits = mutableMapOf<String, Int>()
        val session = PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered((333 + registrations).toString(), "444")
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request()
            calls++
            val key = r.url.queryParameter("device_id") + r.url.host + r.url.encodedPath
            val hit = (hits[key] ?: 0) + 1
            hits[key] = hit
            val body = if (hit > 1 || r.url.host == PlayRetryPlan.host(2)) ""
                else if (r.method == "GET") videoTabModel else playbackModel
            fixtureResponse(r, body)
        }.build()
        val repo = ContentRepository(http, playbackSession = session)
        repeat(4) { assertEquals(1, repo.streamLadder("123").renditions.size) }
        assertEquals(2, registrations)
        assertEquals(8, calls)
    }

    @Test
    fun backgroundOnlySamplesOnceAndStopsAtCooldownWithoutRegisteringAgain() {
        var registrations = 0
        var calls = 0
        val session = PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered((333 + registrations).toString(), "444")
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            fixtureResponse(chain.request(), if (registrations > 1) videoTabModel else "")
        }.build()
        val repo = ContentRepository(http, playbackSession = session)
        repeat(5) { assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123", speculative = true) } }
        assertEquals(4, calls)
        assertEquals(1, registrations)
        assertEquals(1, repo.streamLadder("123").renditions.size)
        assertEquals(5, calls)
        assertEquals(2, registrations)
    }

    @Test
    fun foregroundCallsShareOneDeadlineAndBackgroundHasSmallerBudget() {
        val budgets = mutableListOf<Long>()
        val deadlines = mutableListOf<Long>()
        val session = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            budgets += chain.call().timeout().timeoutNanos()
            assertTrue(chain.call().timeout().hasDeadline())
            deadlines += chain.call().timeout().deadlineNanoTime()
            Thread.sleep(5)
            fixtureResponse(chain.request(), "")
        }.build()
        val repo = ContentRepository(http, playbackSession = session)
        assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") }
        assertEquals(4, budgets.size)
        assertEquals(1, deadlines.distinct().size)
        assertTrue(budgets.first() <= java.util.concurrent.TimeUnit.SECONDS.toNanos(8))
        assertTrue(budgets.zipWithNext().all { (a, b) -> b < a })
        budgets.clear()
        val fresh = ContentRepository(http, playbackSession = PlaybackDeviceSession { DeviceRegister.Registered("555", "666") })
        assertThrows(EmptyPlaybackResponseException::class.java) { fresh.streamLadder("123", speculative = true) }
        assertEquals(1, budgets.size)
        assertTrue(budgets.single() <= java.util.concurrent.TimeUnit.SECONDS.toNanos(2))
    }

    @Test
    fun mixedTimeoutAndEmptyRoundsDoNotRotateAsAllEmpty() {
        var registrations = 0
        var now = 1000L
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/service/2/device_register/" -> {
                    registrations++
                    fixtureResponse(request, """{"device_id":"333","install_id":"444"}""")
                }
                "/reading/bookapi/video_tab/multi_video_model/v1/" -> fixtureResponse(request, "")
                else -> {
                    if (request.url.host == PlayRetryPlan.host(0)) throw java.net.SocketTimeoutException("test-only")
                    fixtureResponse(request, "")
                }
            }
        }.build()
        val repo = ContentRepository(http, clock = { now })
        repeat(4) {
            val error = assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") }
            assertTrue(error.detail.orEmpty().contains("G:b=200/0B/h1"))
            assertTrue(error.detail.orEmpty().contains("P:b=timeout"))
            assertFalse(error.detail.orEmpty().contains("rotated"))
            now += 31_000
        }
        assertEquals(1, registrations)
    }

    @Test
    fun failedRegistrationIsDistinguishedAndNotRepeatedInOneFailurePeriod() {
        var registrations = 0
        val session = PlaybackDeviceSession {
            if (++registrations == 1) DeviceRegister.Registered("333", "444") else null
        }
        val http = OkHttpClient.Builder().addInterceptor { chain -> fixtureResponse(chain.request(), "") }.build()
        val repo = ContentRepository(http, playbackSession = session, sleep = {})
        repeat(2) { assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") } }
        repeat(2) {
            val error = assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") }
            assertTrue(error.detail.orEmpty().contains("rotated=1 reg=failed"))
        }
        assertEquals(2, registrations)
    }

    @Test
    fun failedRotationCanRecoverAfterCooldownWithoutRecreatingRepository() {
        var now = 1000L
        var registrations = 0
        val session = PlaybackDeviceSession {
            registrations++
            if (registrations == 2) null else DeviceRegister.Registered("$registrations", "444")
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            fixtureResponse(chain.request(), if (registrations >= 3) videoTabModel else "")
        }.build()
        val repo = ContentRepository(http, clock = { now }, playbackSession = session)
        repeat(4) { assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") } }
        assertEquals(2, registrations)
        now += 4999
        assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") }
        assertEquals(2, registrations)
        now++
        assertEquals(1, repo.streamLadder("123").renditions.size)
        assertEquals(3, registrations)
    }

    @Test
    fun cancelledRotationDoesNotConsumeNextForegroundRecovery() {
        var registrations = 0
        val scope = RequestScope()
        val session = PlaybackDeviceSession {
            registrations++
            if (registrations == 2) { scope.cancel(); null }
            else DeviceRegister.Registered("$registrations", "444")
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            fixtureResponse(chain.request(), if (registrations >= 3) videoTabModel else "")
        }.build()
        val repo = ContentRepository(http, playbackSession = session)
        assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") }
        assertThrows(java.io.InterruptedIOException::class.java) { scope.run { repo.streamLadder("123") } }
        assertEquals(1, repo.streamLadder("123").renditions.size)
        assertEquals(3, registrations)
    }

    @Test
    fun successfulRegistrationWithEmptyModelsCanRearmOnlyAfterThirtySeconds() {
        var now = 1000L
        var registrations = 0
        val session = PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered("$registrations", "444")
        }
        val http = OkHttpClient.Builder().addInterceptor { chain -> fixtureResponse(chain.request(), "") }.build()
        val repo = ContentRepository(http, clock = { now }, playbackSession = session)
        repeat(3) { assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") } }
        assertEquals(2, registrations)
        now += 29999
        assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") }
        assertEquals(2, registrations)
        now++
        assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") }
        assertEquals(3, registrations)
    }

    @Test
    fun rotatedIdentityRetriesPreferredEndpointAndSuccessResetsFailurePeriod() {
        var registrations = 0
        var allowPreferred = true
        var legacyCalls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/service/2/device_register/" -> {
                    registrations++
                    fixtureResponse(request, """{"device_id":"${333 + registrations}","install_id":"${444 + registrations}"}""", setCookie = true)
                }
                "/reading/bookapi/video_tab/multi_video_model/v1/" ->
                    fixtureResponse(request, if (registrations == 2 && allowPreferred) videoTabModel else "")
                else -> { legacyCalls++; fixtureResponse(request, "") }
            }
        }.build()
        val repo = ContentRepository(http, playbackSession = PlaybackDeviceSession(), sleep = {})
        assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") }
        assertEquals(1, registrations)
        assertEquals(1, repo.streamLadder("123").renditions.size)
        assertEquals(2, registrations)
        assertEquals(3, legacyCalls)
        allowPreferred = false
        repeat(2) { assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") } }
        assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") }
        assertEquals(3, registrations)
    }

    @Test
    fun twoConsecutiveEmptyRoundsRotateIdentityOnceAndTagSubsequentFailures() {
        val registrations = AtomicInteger()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    if (request.url.encodedPath == "/service/2/device_register/") {
                        registrations.incrementAndGet()
                        return@addInterceptor fixtureResponse(request, """{"device_id":"333","install_id":"444"}""", setCookie = true)
                    }
                    fixtureResponse(request, "")
                }
                .build()
        val repo = ContentRepository(http, playbackSession = PlaybackDeviceSession(), sleep = {})
        val first = assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") }
        assertFalse(first.detail.orEmpty().contains("rotated"))
        val second = assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") }
        assertTrue(second.detail.orEmpty().contains("rotated=1"))
        assertEquals(2, registrations.get())
        // 全路径已知失效时提前轮换；此后同一失败期不再重复注册。
        val third = assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") }
        assertTrue(third.detail.orEmpty().contains("rotated=1"))
        assertEquals(2, registrations.get())
        val fourth = assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") }
        assertTrue(fourth.detail.orEmpty().contains("rotated=1"))
        assertEquals(2, registrations.get())
    }

    @Test
    fun successfulPlaybackResetsIdentityRotationStateForTheNextIncident() {
        val registrations = AtomicInteger()
        var now = 1000L
        var available = false
        val session = PlaybackDeviceSession {
            registrations.incrementAndGet()
            DeviceRegister.Registered("333", "444")
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            fixtureResponse(chain.request(), if (available) videoTabModel else "")
        }.build()
        val repo = ContentRepository(http, clock = { now }, playbackSession = session)
        repeat(2) { assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") } }
        assertEquals(2, registrations.get())
        available = true
        now += 31_000
        assertEquals(1, repo.streamLadder("123").renditions.size)
        assertEquals(3, registrations.get())
        available = false
        repeat(2) { assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") } }
        assertEquals(4, registrations.get())
    }

    private val videoTabModel =
        """{"code":0,"message":"SUCCESS","data":{"video_model_data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.test/video","video_meta":{"definition":"720p","codec_type":"h264","vwidth":720,"vheight":1280},"encrypt_info":{"encrypt":false}}]}}}}}"""

    @Test
    fun preferredVideoModelSignsAllParametersBeforeSending() {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            fixtureResponse(request, videoTabModel)
        }.build()
        val session = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }
        ContentRepository(http, playbackSession = session).streamLadder("123")
        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("123", request.url.queryParameter("video_id"))
        assertEquals("333", request.url.queryParameter("device_id"))
        assertEquals("444", request.url.queryParameter("iid"))
        assertEquals(request.url.queryParameter("_rticket"), request.header("x-ss-req-ticket"))
        assertNull(request.url.queryParameter("ts"))
            assertTrue(kotlin.math.abs(request.header("x-khronos")!!.toLong() -
                request.url.queryParameter("_rticket")!!.toLong() / 1000) <= 1)
        for (header in listOf("x-gorgon", "x-medusa", "x-argus", "x-ladon", "x-khronos", "x-helios"))
            assertFalse(request.header(header).isNullOrBlank())
        val ts = request.header("x-khronos")!!.toInt()
        assertEquals(0, Signer().branch(request.url.encodedQuery!!, null, ts))
    }

    private fun fixtureResponse(request: Request, body: String, setCookie: Boolean = false) =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("fixture")
            .apply { if (setCookie) addHeader("Set-Cookie", "ttreq=test-only; Path=/") }
            .body(body.toResponseBody())
            .build()

    /** 2026-11-02 起官方 App 走 video_tab GET 接口；命中后不应再打旧 /novel/player 接口。 */
    @Test
    fun videoTabModelIsPreferredAndSkipsLegacyEndpoint() {
        val tabCalls = AtomicInteger()
        val legacyCalls = AtomicInteger()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    when (request.url.encodedPath) {
                        "/service/2/device_register/" ->
                            fixtureResponse(
                                request,
                                """{"device_id":"333","install_id":"444"}""",
                                setCookie = true,
                            )
                        "/reading/bookapi/video_tab/multi_video_model/v1/" -> {
                            tabCalls.incrementAndGet()
                            // 官方 GetVideoModelRequest 的 snake_case 参数缺一即 100103。
                            assertEquals("123", request.url.queryParameter("video_id"))
                            assertEquals("0", request.url.queryParameter("video_id_type"))
                            assertEquals("1", request.url.queryParameter("device_level"))
                            assertEquals("1", request.url.queryParameter("need_all_video_definition"))
                            assertEquals("1024", request.url.queryParameter("video_platform"))
                            fixtureResponse(request, videoTabModel)
                        }
                        "/novel/player/multi_video_model/v1/" -> {
                            legacyCalls.incrementAndGet()
                            fixtureResponse(request, playbackModel)
                        }
                        else -> fixtureResponse(request, "")
                    }
                }
                .build()
        val repo = ContentRepository(http, playbackSession = PlaybackDeviceSession())
        val ladder = repo.streamLadder("123")
        assertEquals(1, ladder.renditions.size)
        assertEquals(1, tabCalls.get())
        assertEquals(0, legacyCalls.get())
    }

    /** 新接口参数不合法或降级时回退旧接口，不改变既有空响应恢复路径。 */
    @Test
    fun videoTabParamInvalidFallsBackToLegacyEndpoint() {
        val legacyCalls = AtomicInteger()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    when (request.url.encodedPath) {
                        "/service/2/device_register/" ->
                            fixtureResponse(
                                request,
                                """{"device_id":"333","install_id":"444"}""",
                                setCookie = true,
                            )
                        "/reading/bookapi/video_tab/multi_video_model/v1/" ->
                            fixtureResponse(
                                request,
                                """{"code":100103,"message":"PARAM_INVALID","data":null}""",
                            )
                        "/novel/player/multi_video_model/v1/" -> {
                            legacyCalls.incrementAndGet()
                            fixtureResponse(request, playbackModel)
                        }
                        else -> fixtureResponse(request, "")
                    }
                }
                .build()
        val repo = ContentRepository(http, playbackSession = PlaybackDeviceSession())
        val ladder = repo.streamLadder("123")
        assertEquals(1, ladder.renditions.size)
        assertEquals(1, legacyCalls.get())
    }

    @Test
    fun inlinePlaybackModelUsesItsOwnMetadataAndKeyWithoutFallbackRequest() {
        val requests = mutableListOf<Request>()
        // Synthetic obfuscation fixture for 000102030405060708090a0b0c0d0e0f.
        val spade = "08AThlTBE4hbwRyKVMYciFXFEopa2hKjW9oToVrdE6NZ3yRftNQ5Xkc="
        val meta =
            JSONObject()
                .put("definition", "720p")
                .put("codec_type", "h264")
                .put("vwidth", 720)
                .put("vheight", 1280)
        val row =
            JSONObject()
                .put("main_url", "https://example.com/inline")
                .put("video_meta", meta)
                .put("encrypt_info", JSONObject().put("encrypt", true).put("spade_a", spade))
        val repo = repository { request ->
            requests += request
            check(request.url.host != "example.com")
            JSONObject()
                .put(
                    "data",
                    JSONObject()
                        .put(
                            "123",
                            JSONObject()
                                .put(
                                    "video_model",
                                    JSONObject()
                                        .put("video_list", JSONArray().put(row))
                                        .put("fallback_api", "https://example.com/fallback"),
                                ),
                        ),
                )
                .toString()
        }
        val tier = repo.streamLadder("123").renditions.single()
        assertEquals(1, requests.size)
        assertEquals("https://example.com/inline", tier.url)
        assertEquals(720, tier.quality)
        assertEquals(1280, tier.height)
        assertArrayEquals(ByteArray(16) { it.toByte() }, tier.key)
    }

    @Test
    fun unusableInlineEncryptedOrUnsupportedEntriesKeepLegacyFallback() {
        for (encryption in
            listOf(
                JSONObject().put("encrypt", true),
                JSONObject().put("encrypt", true).put("spade_a", "broken"),
            )) {
            var calls = 0
            val repo = repository { request ->
                calls++
                if (request.url.host == "example.com")
                    """{"video_info":{"data":{"video_list":{"v":{"quality_desc":"480p","codec_type":"h264","main_url":"https://example.com/valid"}}}}}"""
                else
                    JSONObject()
                        .put(
                            "data",
                            JSONObject()
                                .put(
                                    "123",
                                    JSONObject()
                                        .put(
                                            "video_model",
                                            JSONObject()
                                                .put(
                                                    "video_list",
                                                    JSONArray()
                                                        .put(
                                                            JSONObject()
                                                                .put(
                                                                    "main_url",
                                                                    "https://example.com/encrypted",
                                                                )
                                                                .put(
                                                                    "video_meta",
                                                                    JSONObject()
                                                                        .put("definition", "720p")
                                                                        .put("codec_type", "h264")
                                                                        .put("vwidth", 720)
                                                                        .put("vheight", 1280),
                                                                )
                                                                .put("encrypt_info", encryption)
                                                        ),
                                                )
                                                .put("fallback_api", "https://example.com/fallback"),
                                        ),
                                ),
                        )
                        .toString()
            }
            assertEquals(
                "https://example.com/valid",
                repo.streamLadder("123").renditions.single().url,
            )
            assertEquals(2, calls)
        }
    }

    @Test
    fun allSearchUsesPublicJsonEndpointAndKeepsBothContentTypes() {
        var calls = 0
        val repo = repository { request ->
            calls++
            assertEquals("hongguoduanju.com", request.url.host)
            assertEquals("/api/search/result", request.url.encodedPath)
            assertEquals("大佬", request.url.queryParameter("query"))
            assertEquals("2", request.url.queryParameter("page"))
            assertNull(request.url.queryParameter("tab_type"))
            assertNull(request.header("x-gorgon"))
            """{"pageNum":2,"hasMore":true,"searchList":[{"video_data":{"series_id":"123","series_title":"大佬真人短剧","episode_cnt":80}},{"video_data":{"series_id":"456","series_title":"大佬动画漫剧","episode_cnt":100}}]}"""
        }
        val page = repo.searchAll("大佬", 2)
        assertEquals(listOf("123", "456"), page.items.map { it.id })
        assertEquals(1, calls)
        assertTrue(page.hasMore)
    }

    @Test
    fun jsonSearchHandlesLastPageEmptyResultsAndChangedResponses() {
        assertFalse(
            repository {
                    """{"pageNum":2,"totalPages":2,"searchList":[{"video_data":{"series_id":"1","series_title":"最后一页"}}]}"""
                }
                .searchAll("测试", 2)
                .hasMore
        )
        val empty =
            repository { """{"pageNum":1,"totalPages":0,"hasMore":false,"searchList":[]}""" }
                .searchAll("无结果")
        assertTrue(empty.items.isEmpty())
        assertFalse(empty.hasMore)
        assertThrows(IOException::class.java) {
            repository { "<html>client rendered page</html>" }.searchAll("测试")
        }
        assertThrows(IOException::class.java) {
            repository { """{"pageNum":1,"searchList":[]}""" }.searchAll("测试", 2)
        }
        assertThrows(IOException::class.java) {
            repository { """{"code":"search_verification_required"}""" }.searchAll("测试")
        }
    }

    @Test
    fun allSearchDoesNotTurnNetworkFailureIntoEmptyResults() {
        val repo = repository { throw IOException("offline") }
        assertThrows(IOException::class.java) { repo.searchAll("大佬") }
    }

    @Test
    fun playbackSignsRegisteredIdentifiersAndKeepsThemAcrossEpisodes() {
        val requests = mutableListOf<Request>()
        val repo = repository { request ->
            requests += request
            """{"Code":110001}"""
        }
        repeat(2) {
            val error = assertThrows(IOException::class.java) { repo.stream("123") }
            assertTrue(error.message.orEmpty().contains("110001"))
        }
        for (request in requests) {
            assertEquals("POST", request.method)
            for (header in listOf("x-gorgon", "x-medusa", "x-argus", "x-ladon", "x-khronos", "x-helios")) assertNotNull(
                request.header(header)
            )
            assertTrue(request.header("User-Agent").orEmpty().startsWith("com.phoenix.read/"))
            for (key in listOf("iid", "device_id")) {
                val value = request.url.queryParameter(key)!!
                assertEquals(if (key == "iid") "444" else "333", value)
                assertNotEquals(VendorConstants.device[key], value)
                assertEquals(requests.first().url.queryParameter(key), value)
            }
            assertEquals(
                requests.first().url.queryParameter("cdid"),
                request.url.queryParameter("cdid"),
            )
            val body = okio.Buffer()
            request.body!!.writeTo(body)
            assertEquals(
                "123",
                JSONObject(body.readUtf8())
                    .getJSONObject("mixed_video_id_map")
                    .getJSONArray("1004")
                    .getString(0),
            )
        }
    }

    @Test
    fun emptyPlaybackResponseReportsActionableError() {
        var calls = 0
        val repo = repository {
            calls++
            "   "
        }
        val error = assertThrows(IOException::class.java) { repo.stream("123") }
        assertTrue(error.message.orEmpty().contains("空响应"))
        assertFalse(error.message.orEmpty().contains("更新应用"))
        // 首选已由测试桩拒绝，备用接口各读取一条不同路径，不重复撞 b。
        assertEquals(3, calls)
    }

    private fun inlinePlaybackFixture(): String =
        """{"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.com/video","video_meta":{"definition":"720p","codec_type":"h264","vwidth":720,"vheight":1280},"encrypt_info":{"encrypt":false}}]}}}}"""

    private fun batchPlaybackFixture(ids: List<String>): String {
        val data = JSONObject()
        ids.forEach { id ->
            val entry =
                JSONObject(inlinePlaybackFixture()).getJSONObject("data").getJSONObject("123")
            entry
                .getJSONObject("video_model")
                .getJSONArray("video_list")
                .getJSONObject(0)
                .put("main_url", "https://example.com/video/$id")
            data.put(id, entry)
        }
        return JSONObject().put("data", data).toString()
    }

    private fun playbackRequestIds(request: Request): List<String> {
        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        val ids = JSONObject(body).getJSONObject("mixed_video_id_map").getJSONArray("1004")
        return (0 until ids.length()).map { ids.getString(it) }
    }

    @Test
    fun batchPlaybackInformationFeedsTheNextTwoEpisodesWithoutAnotherModelRequest() {
        val requests = mutableListOf<Request>()
        val repo = repository { request ->
            requests += request
            batchPlaybackFixture(playbackRequestIds(request))
        }
        assertEquals(
            "https://example.com/video/123",
            repo.streamLadder("123", listOf("456", "789")).renditions.single().url,
        )
        assertEquals(
            "https://example.com/video/456",
            repo.streamLadder("456").renditions.single().url,
        )
        assertEquals(
            "https://example.com/video/789",
            repo.streamLadder("789").renditions.single().url,
        )
        assertEquals(1, requests.size)
        assertEquals(listOf("123", "456", "789"), playbackRequestIds(requests.single()))
        // 领取后即移除；重试同一集时必须重新获取，避免反复复用失效地址。
        repo.streamLadder("456")
        assertEquals(2, requests.size)
    }

    @Test
    fun expiredUpcomingModelIsNotUsedAfterSixtySeconds() {
        var now = 1000L
        var calls = 0
        val repo =
            repository(clock = { now }) { request ->
                calls++
                batchPlaybackFixture(playbackRequestIds(request))
            }
        repo.streamLadder("123", listOf("456"))
        now += 60_001
        repo.streamLadder("456")
        assertEquals(2, calls)
    }

    @Test
    fun partialBatchFallsBackToIndividualReadingButNeverPlaysAnotherEpisodesModel() {
        var calls = 0
        val repo = repository { request ->
            calls++
            batchPlaybackFixture(listOf(playbackRequestIds(request).first()))
        }
        repo.streamLadder("123", listOf("456"))
        assertEquals(
            "https://example.com/video/456",
            repo.streamLadder("456").renditions.single().url,
        )
        assertEquals(2, calls)
        val missingCurrent = repository { batchPlaybackFixture(listOf("456")) }
        assertThrows(IOException::class.java) { missingCurrent.streamLadder("123", listOf("456")) }
    }

    @Test
    fun twoEmptyModelsRecoverOnTheThirdReadAndStillPrimeTheNextEpisode() {
        var calls = 0
        val repo = repository { request ->
            if (++calls < 3) "" else batchPlaybackFixture(playbackRequestIds(request))
        }
        repo.streamLadder("123", listOf("456"))
        assertEquals(
            "https://example.com/video/456",
            repo.streamLadder("456").renditions.single().url,
        )
        assertEquals(3, calls)
    }

    @Test
    fun playbackModelRecoversFromOneEmptyResponseOnADifferentHostWithoutChangingIdentity() {
        val requests = mutableListOf<Request>()
        var now = 1000L
        val repo =
            repository(clock = { now }) { request ->
                requests += request
                now += 1000
                if (requests.size == 1) " \n " else inlinePlaybackFixture()
            }
        assertEquals("https://example.com/video", repo.streamLadder("123").renditions.single().url)
        assertEquals(2, requests.size)
        // 换路重试：host 轮换到备选，设备身份查询参数保持一致；
        // ts/_rticket 参与签名，随整请求重签一并刷新。
        assertNotEquals(requests[0].url.host, requests[1].url.host)
        assertEquals(
            requests[0].url.newBuilder().host(requests[1].url.host).removeAllQueryParameters("_rticket").removeAllQueryParameters("ts").build(),
            requests[1].url.newBuilder().removeAllQueryParameters("_rticket").removeAllQueryParameters("ts").build(),
        )
        assertNotEquals(
            requests[0].url.queryParameter("_rticket"),
            requests[1].url.queryParameter("_rticket"),
        )
        for (request in requests) {
            assertEquals(request.url.queryParameter("_rticket"), request.header("x-ss-req-ticket"))
            assertNull(request.url.queryParameter("ts"))
            assertTrue(kotlin.math.abs(request.header("x-khronos")!!.toLong() -
                request.url.queryParameter("_rticket")!!.toLong() / 1000) <= 1)
        }
        assertNotEquals(requests[0].header("x-gorgon"), requests[1].header("x-gorgon"))
        assertNotEquals(requests[0].header("x-medusa"), requests[1].header("x-medusa"))
        // HTTP/2 重试通过独立连接池隔离，不依赖 Connection 请求头。
        // 实际连接序号由 PlaybackRetryConnectionTest 验证。
        assertNull(requests[0].header("Connection"))
        assertNull(requests[1].header("Connection"))
        val first = okio.Buffer().also { requests[0].body!!.writeTo(it) }.readUtf8()
        val second = okio.Buffer().also { requests[1].body!!.writeTo(it) }.readUtf8()
        assertEquals(first, second)
        assertEquals("POST", requests[1].method)
    }

    @Test
    fun playbackModelRetriesOneTemporaryConnectionFailure() {
        var calls = 0
        val repo = repository {
            if (++calls == 1) throw java.net.SocketTimeoutException("fixture")
            inlinePlaybackFixture()
        }
        assertEquals(720, repo.streamLadder("123").renditions.single().quality)
        assertEquals(2, calls)
    }

    @Test
    fun validPlaybackErrorsAndChangedJsonDoNotTriggerAnInternalRetry() {
        for (body in listOf("""{"Code":110001}""", "<html>changed response</html>")) {
            var calls = 0
            val repo = repository {
                calls++
                body
            }
            assertThrows(Exception::class.java) { repo.streamLadder("123") }
            assertEquals(1, calls)
        }
    }

    @Test
    fun cancelledPlaybackScopeDoesNotSendTheEmptyResponseRetry() {
        val scope = RequestScope()
        var calls = 0
        val repo = repository {
            calls++
            scope.cancel()
            ""
        }
        assertThrows(IOException::class.java) { scope.run { repo.streamLadder("123") } }
        assertEquals(1, calls)
    }

    private fun streamRepository(vararg variants: Triple<Int, String, String>) =
        repository { request ->
            if (request.url.host == "example.com") {
                val rows = JSONObject()
                variants.forEachIndexed { index, (height, codec, url) ->
                    rows.put(
                        "video_$index",
                        JSONObject()
                            .put("vheight", height)
                            .put("codec_type", codec)
                            .put("main_url", url),
                    )
                }
                JSONObject()
                    .put(
                        "video_info",
                        JSONObject().put("data", JSONObject().put("video_list", rows)),
                    )
                    .toString()
            } else
                """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
        }

    @Test
    fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo =
            streamRepository(
                Triple(720, "bytevc2", "https://example.com/unsupported"),
                Triple(1080, "bytevc1", "https://example.com/hevc"),
            )
        val result = repo.stream("123", 720)
        assertEquals("https://example.com/hevc", result.url)
        assertEquals("1080P（兼容资源）", result.quality)
    }

    @Test
    fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo =
            streamRepository(
                Triple(720, "bytevc2", "https://example.com/unsupported"),
                Triple(480, "h264", "https://example.com/avc"),
                Triple(1080, "bytevc1", "https://example.com/hevc"),
            )
        assertEquals("480P", repo.stream("123", 720).quality)
    }

    @Test
    fun byteVc2OnlyFallbackRequestsOneCompatibleModelAndPreservesOtherParameters() {
        val fallbackRequests = mutableListOf<Request>()
        val repo = repository { request ->
            if (request.url.host != "example.com")
                """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream?codec_type=4&token=fixture"}}}}"""
            else {
                fallbackRequests += request
                val compatible = request.url.queryParameter("codec_type") == "0" &&
                    request.url.queryParameter("force_codec") == "h264"
                val codec = if (compatible) "h265" else "bytevc2"
                """{"video_info":{"data":{"video_list":{"video_1":{"vwidth":1254,"vheight":720,"codec_type":"$codec","main_url":"https://example.com/video"}}}}}"""
            }
        }
        val tier = repo.streamLadder("123").renditions.single()
        assertEquals("h265", tier.codec)
        assertEquals(2, fallbackRequests.size)
        assertEquals("fixture", fallbackRequests.last().url.queryParameter("token"))
    }

    @Test
    fun compatibleModelRequestIsBoundedAndDoesNotRetryUnknownOrPlayableCodecs() {
        for ((codec, expectedCalls) in listOf("bytevc2" to 2, "bvc2" to 2, "unknown" to 1, "h265" to 1)) {
            var calls = 0
            val repo = repository { request ->
                if (request.url.host != "example.com")
                    """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
                else {
                    calls++
                    """{"video_info":{"data":{"video_list":{"video_1":{"vheight":720,"codec_type":"$codec","main_url":"https://example.com/video"}}}}}"""
                }
            }
            if (expectedCalls == 2) assertThrows(IOException::class.java) { repo.streamLadder("123") }
            else assertEquals(codec, repo.streamLadder("123").renditions.single().codec)
            assertEquals(expectedCalls, calls)
        }
    }

    @Test
    fun entirelyIncompatibleSourceFailsBeforeStartingAudio() {
        val repo =
            streamRepository(
                Triple(720, "ByteVC2", "https://example.com/unsupported"),
                Triple(480, "bvc2", "https://example.com/unsupported2"),
            )
        assertThrows(IOException::class.java) { repo.stream("123", 720) }
    }

    @Test
    fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo =
            streamRepository(
                Triple(720, "", "https://example.com/legacy"),
                Triple(1080, "bytevc1", "https://example.com/hevc"),
            )
        assertEquals("720P", repo.stream("123", 720).quality)
    }

    @Test
    fun capabilityPredicateDropsUndecodableTiers() {
        val repo =
            streamRepository(
                Triple(1080, "bytevc1", "https://example.com/hevc"),
                Triple(480, "h264", "https://example.com/avc"),
            )
        val info = repo.stream("123", 1080) { it.codec == "h264" }
        assertEquals("480P", info.quality)
        assertEquals("https://example.com/avc", info.url)
    }

    @Test
    fun streamLadderCarriesCodedDimensionsAndCodecs() {
        val repo = repository { request ->
            if (request.url.host == "example.com") {
                val rows =
                    JSONObject()
                        .put(
                            "video_0",
                            JSONObject()
                                .put("quality_desc", "1080p")
                                .put("codec_type", "h264")
                                .put("vwidth", 1080)
                                .put("vheight", 1920)
                                .put("main_url", "https://example.com/portrait"),
                        )
                        .put(
                            "video_1",
                            JSONObject()
                                .put("quality_desc", "540p")
                                .put("codec_type", "h264")
                                .put("vwidth", 540)
                                .put("vheight", 960)
                                .put("main_url", "https://example.com/small"),
                        )
                JSONObject()
                    .put(
                        "video_info",
                        JSONObject().put("data", JSONObject().put("video_list", rows)),
                    )
                    .toString()
            } else
                """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
        }
        val ladder = repo.streamLadder("123")
        val portrait = ladder.renditions.first { it.url.endsWith("/portrait") }
        assertEquals("h264", portrait.codec)
        assertEquals(1080, portrait.width)
        assertEquals(1920, portrait.height)
        // A portrait 1080 tier is rejected by size while the smaller tier stays playable.
        assertEquals(
            540,
            ladder.select(1080) { !(it.width == 1080 && it.height == 1920) }!!.quality,
        )
    }

    private fun repository(
        clock: () -> Long = System::currentTimeMillis,
        respond: (Request) -> String,
    ) =
        ContentRepository(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    // 本轮起播放先读 video_tab 新接口；只用例覆盖旧接口行为，统一让它回 100103
                    // 让调用方回退，且不进入用例自己的请求计数。
                    val body =
                        if (request.url.encodedPath.startsWith("/reading/bookapi/video_tab/"))
                            """{"code":100103,"message":"PARAM_INVALID","data":null}"""
                        else respond(request)
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody())
                        .build()
                }
                .build(),
            clock,
            PlaybackDeviceSession { DeviceRegister.Registered("333", "444") },
        )

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) =
        JSONObject()
            .put("isSuccess", true)
            .put(
                "rankList",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("seriesId", "123")
                            .put("title", "测试漫剧")
                            .put("cover", "https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
                            .put("episodeCount", 12)
                            .put("tags", JSONArray().put("玄幻"))
                    ),
            )
            .put("pagination", JSONObject().put("pageNum", page).put("totalPages", totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun currentRank(payload: JSONObject) =
        script(
            "mergeLoaderData",
            JSONArray()
                .put("rank_hot-comic-drama/page")
                .put(
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("key", "content")
                                .put("routerDataFnName", "p")
                                .put("routerDataFnArgs", JSONArray().put(payload.toString()))
                        )
                ),
        )

    @Test
    fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload = rankPayload(2, 5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank", 23).put("heatText", "5746万热度")
        val repo = repository { request ->
            assertEquals("/rank/hot-comic-drama", request.url.encodedPath)
            assertEquals("2", request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>""" +
                currentRank(payload)
        }
        val result = repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23, "5746万热度"), result.ranking!!.positions["123"])
        assertEquals(5, result.ranking.totalPages)
        assertEquals("9月22日已更新", result.ranking.updatedText)
        assertTrue(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test
    fun missingRankIsNotInventedAndLastPageStops() {
        val result = repository { currentRank(rankPayload(5, 5)) }.comicRanking(5)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(null, ""), result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test
    fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo = repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=5">5</a></nav>"""
        }
        val result = repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21, "5746万热度"), result.ranking!!.positions["123"])
        assertEquals(5, result.ranking.totalPages)
        assertEquals("今日已更新", result.ranking.updatedText)
    }

    @Test
    fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo = repository { currentRank(rankPayload(1, 5)) }
        assertThrows(IOException::class.java) { repo.comicRanking(2) }
        assertThrows(IllegalArgumentException::class.java) { repo.comicRanking(0) }
    }

    @Test
    fun rankPageRetriesTransientFailureInsteadOfFailingTheBoard() {
        // CDN 软拦截表现为超时或空响应：同页一次有界重试应恢复读取。
        var calls = 0
        val repo = repository { _ ->
            calls += 1
            if (calls == 1) throw java.net.SocketTimeoutException("stalled stream")
            else currentRank(rankPayload(1, 2))
        }
        val result = repo.rankPage(RankBoard.COMIC)
        assertEquals(2, calls)
        assertEquals(listOf("123"), result.items.map { it.id })
    }

    @Test
    fun rankPageSurfacesPersistentFailureAfterOneRetry() {
        var calls = 0
        val repo = repository { _ ->
            calls += 1
            throw java.net.SocketTimeoutException("still stalled")
        }
        assertThrows(IOException::class.java) { repo.rankPage(RankBoard.ALL) }
        assertEquals(2, calls)
    }

    @Test
    fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val repo = repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result = repo.comicRanking(5)
        assertEquals(5, result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test
    fun comicHomeUsesDedicatedRankAndRealPagination() {
        val repo = repository { request ->
            assertEquals("/rank/hot-comic-drama", request.url.encodedPath)
            assertEquals("2", request.url.queryParameter("page"))
            currentRank(rankPayload(2, 2))
        }
        val page = repo.home(2, ContentType.COMIC)
        assertEquals("测试漫剧", page.items.single().title)
        assertEquals("https://example.com/cover.jpg", page.items.single().cover)
        assertEquals("全 12 集", page.items.single().badge)
        assertFalse(page.hasMore)
    }

    @Test
    fun legacyRankIsStillSupported() {
        val repo = repository {
            script(
                "r",
                JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload()),
            )
        }
        assertTrue(repo.home(1, ContentType.COMIC).hasMore)
    }

    @Test
    fun comicHomeDoesNotUseUnrelatedRankData() {
        val repo = repository {
            script(
                "r",
                JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload()),
            )
        }
        assertThrows(IOException::class.java) { repo.home(1, ContentType.COMIC) }
    }

    @Test
    fun rejectedComicRankRemainsAnError() {
        val repo = repository { currentRank(rankPayload().put("isSuccess", false)) }
        assertThrows(IOException::class.java) { repo.home(1, ContentType.COMIC) }
    }

    @Test
    fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo = repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page = repo.home(2, ContentType.COMIC)
        assertEquals("测试 & 漫剧", page.items.single().title)
        assertEquals("https://example.com/a.jpg", page.items.single().cover)
        assertTrue(page.hasMore)
    }

    @Test
    fun comicSearchCarriesCursorAndKeepsTypesAndQueriesSeparate() {
        val requests = mutableListOf<Request>()
        val repo = repository { request ->
            requests += request
            JSONObject()
                .put("code", 0)
                .put(
                    "search_tabs",
                    JSONArray()
                        .put(
                            searchTab(19, "999")
                                .put("search_id", "comic-session")
                                .put("passback", "comic-cursor")
                                .put("next_offset", 25)
                        ),
                )
                .toString()
        }
        repo.search("修仙", 1, ContentType.COMIC)
        repo.search("修仙", 2, ContentType.COMIC)
        assertEquals("comic-session", requests[1].url.queryParameter("search_id"))
        assertEquals("comic-cursor", requests[1].url.queryParameter("passback"))
        assertEquals("25", requests[1].url.queryParameter("offset"))
        assertThrows(SearchSessionExpiredException::class.java) {
            repo.search("重生", 2, ContentType.COMIC)
        }
        assertEquals(2, requests.size)
    }

    @Test
    fun expiredCursorRequestsFreshSearchInsteadOfDuplicateOffsetPage() {
        var now = 0L
        var requests = 0
        val repo =
            repository(clock = { now }) {
                requests++
                JSONObject()
                    .put("code", 0)
                    .put(
                        "search_tabs",
                        JSONArray()
                            .put(
                                searchTab(19, "999")
                                    .put("search_id", "session")
                                    .put("passback", "cursor")
                            ),
                    )
                    .toString()
            }
        repo.search("修仙", 1, ContentType.COMIC)
        now = 300001L
        assertThrows(SearchSessionExpiredException::class.java) {
            repo.search("修仙", 2, ContentType.COMIC)
        }
        assertEquals(1, requests)
        repo.search("修仙", 1, ContentType.COMIC)
        repo.search("修仙", 2, ContentType.COMIC)
        assertEquals(3, requests)
    }

    @Test
    fun shortHomeStillUsesCategoryAndStopsOnLastPage() {
        val repo = repository { request ->
            assertEquals("1", request.url.queryParameter("tab"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试短剧"}],"pagination":{"totalPages":1}}}};</script>"""
        }
        val result = repo.home(1, ContentType.SHORT)
        assertEquals("456", result.items.single().id)
        assertFalse(result.hasMore)
    }

    private fun searchTab(type: Int, id: String) =
        JSONObject()
            .put("tab_type", type)
            .put("has_more", true)
            .put(
                "data",
                JSONArray()
                    .put(
                        JSONObject()
                            .put(
                                "video_data",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("series_id", id)
                                            .put("title", "测试剧集")
                                            .put("episode_cnt", 8)
                                    ),
                            )
                    ),
            )

    @Test
    fun comicSearchRequestsAndSelectsOnlyComicTab() {
        var requests = 0
        val repo = repository { request ->
            requests++
            assertNotNull(request.header("x-gorgon"))
            assertEquals(
                VendorConstants.device["device_id"],
                request.url.queryParameter("device_id"),
            )
            assertEquals("19", request.url.queryParameter("tab_type"))
            assertEquals(if (requests == 1) "0" else "20", request.url.queryParameter("offset"))
            assertEquals("修仙", request.url.queryParameter("query"))
            JSONObject()
                .put("code", 0)
                .put("search_tabs", JSONArray().put(searchTab(11, "111")).put(searchTab(19, "999")))
                .toString()
        }
        repo.search("修仙", 1, ContentType.COMIC)
        assertEquals(listOf("999"), repo.search("修仙", 2, ContentType.COMIC).items.map { it.id })
    }

    @Test
    fun emptyComicSearchDoesNotFallBackToShortResults() {
        var requests = 0
        val repo = repository {
            requests++
            """{"code":0,"search_tabs":[{"tab_type":19,"data":[],"has_more":false}]}"""
        }
        assertTrue(repo.search("无结果", 1, ContentType.COMIC).items.isEmpty())
        assertEquals(1, requests)
    }

    @Test
    fun missingComicTabDoesNotFallBackToShortResults() {
        var requests = 0
        val repo = repository {
            requests++
            """{"code":0,"search_tabs":[{"tab_type":11,"data":[]}]}"""
        }
        assertThrows(IOException::class.java) { repo.search("修仙", 1, ContentType.COMIC) }
        assertEquals(1, requests)
    }

    @Test
    fun shortSearchKeepsWebsiteFallback() {
        val paths = mutableListOf<String>()
        val repo = repository { request ->
            paths += request.url.encodedPath
            if (paths.size == 1) throw IOException("offline")
            """{"searchList":[{"series_id":"456","series_name":"测试短剧"}]}"""
        }
        assertEquals("456", repo.search("短剧", 1, ContentType.SHORT).items.single().id)
        assertEquals(2, paths.size)
    }
}
