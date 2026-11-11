package com.hongguotv.core

import java.io.IOException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecommendationFeedTest {
    @Test
    fun displayedSnapshotDoesNotWaitForThePaginationNetworkLock() {
        val cursor = RecommendationCursor()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val network = pool.submit {
                synchronized(cursor) {
                    entered.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val display = pool.submit<String> { cursor.markSeen("101"); cursor.snapshot() }
            val saved = display.get(2, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(saved.contains("101"))
            assertFalse(network.isDone)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test
    fun concurrentDisplayedAndFetchedIdsKeepSnapshotsBoundedAndReadable() {
        val cursor = RecommendationCursor()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val display = pool.submit { repeat(1000) { cursor.markSeen(it.toString()); cursor.snapshot() } }
            val fetched = pool.submit { repeat(1000) { cursor.remember(listOf((1000 + it).toString())); cursor.seenIds() } }
            display.get(10, java.util.concurrent.TimeUnit.SECONDS)
            fetched.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(JSONObject(cursor.snapshot()).getJSONArray("seen").length() <= 540)
            assertTrue(RecommendationCursor().restore(cursor.snapshot()))
        } finally { pool.shutdownNow() }
    }
    private fun response(id: String = "101", tab: Int = 16, wrapped: Boolean = false): String {
        val row =
            JSONObject()
                .put("series_id", id)
                .put("series_title", "推荐作品$id")
                .put("episode_cnt", 80)
                .put("series_intro", "简介")
                .put("series_cover", "https://example.com/cover.jpg")
        val video = if (wrapped) JSONObject().put("video_detail", row) else row
        val cell =
            JSONObject()
                .put(
                    "cell_data",
                    JSONArray()
                        .put(JSONObject().put("video_data", JSONArray().put(video).put(video))),
                )
        val item =
            JSONObject()
                .put("tab_type", tab)
                .put("cell_data", JSONArray().put(cell))
                .put("session_id", "test-page-session")
                .put("has_more", false)
                .put("bottom_unlimited", true)
                .put("next_offset", 0)
        return JSONObject()
            .put("code", 0)
            .put("data", JSONObject().put("tab_item", JSONArray().put(item)))
            .toString()
    }

    @Test
    fun countsTheRecommendationTabEvenWhenAnotherTabComesFirst() {
        val data = JSONObject(response())
        val tabs = data.getJSONObject("data").getJSONArray("tab_item")
        data.getJSONObject("data").put("tab_item", JSONArray()
            .put(JSONObject().put("tab_type", 8).put("cell_data", JSONArray()))
            .put(tabs.getJSONObject(0)))
        assertEquals(2, RecommendationFeed.rawVideoCount(data))
        assertEquals(listOf("101"), RecommendationFeed.parse(data).first.items.map { it.id })
    }

    @Test
    fun oneLargeBatchDoesNotDiscardWorksAfterTheThirtieth() {
        val data = JSONObject(response())
        val rows = JSONArray()
        repeat(45) { rows.put(JSONObject().put("series_id", "${101 + it}").put("series_title", "作品$it")) }
        data.getJSONObject("data").getJSONArray("tab_item").getJSONObject(0)
            .getJSONArray("cell_data").getJSONObject(0).getJSONArray("cell_data")
            .getJSONObject(0).put("video_data", rows)
        assertEquals(45, RecommendationFeed.parse(data).first.items.size)
        assertEquals(45, RecommendationFeed.rawVideoCount(data))
    }

    @Test
    fun restartingAnExhaustedSourceKeepsDeduplicationButDropsItsServerSession() {
        val state = DramaFeedState()
        state.recommendationCursor.session = "old-session"
        state.recommendationCursor.offset = 30
        state.recommendationCursor.markSeen("101")
        state.refresh(restartCurrentSource = true)
        assertEquals("", state.recommendationCursor.session)
        assertEquals(0, state.recommendationCursor.offset)
        assertEquals(setOf("101"), state.recommendationCursor.seenIds())
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(response(if (calls == 1) "101" else "102").toResponseBody()).build()
        }.build()
        assertEquals("102", RecommendationFeed(http, cursor = state.recommendationCursor).page(1).items.single().id)
        assertEquals(2, calls)
        state.refresh(resetRecommendations = true)
        assertTrue(state.recommendationCursor.seenIds().isEmpty())
    }

    private fun infiniteResponse(): String {
        val result = JSONObject(response())
        val tab = result.getJSONObject("data").getJSONArray("tab_item").getJSONObject(0)
        tab.put("bookstore_id", "8001").put("client_template", 7).put("version_tag", "fixture-v7")
        tab.getJSONArray("cell_data").getJSONObject(0).put("cell_id", "9001")
        return result.toString()
    }

    private fun cellResponse(id: String, nextOffset: Int): String {
        val tab = JSONObject(response(id)).getJSONObject("data").getJSONArray("tab_item").getJSONObject(0)
        return JSONObject().put("code", 0).put("data", JSONObject()
            .put("cell_view", tab.getJSONArray("cell_data").getJSONObject(0))
            .put("next_offset", nextOffset).put("session_id", "cell-session")
            .put("has_more", false)).toString()
    }

    @Test
    fun signedRecommendationSharesRegisteredIdentityAndPreservesAccountCookieOnExplicitFallback() {
        var registrations = 0
        val session = PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered("333", "444", cookies = mapOf("install_id" to "444"))
        }
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request(); requests += req
            val payload = if (requests.size == 1) """{"code":110}""" else response("102")
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody()).build()
        }.build()
        val cursor = RecommendationCursor()
        val account = AccountCredentials("sessionid=test-only")
        val result = RecommendationFeed(http, account, cursor, playbackSession = session).page(1)
        assertEquals("102", result.items.single().id)
        assertEquals(1, registrations)
        assertEquals(2, requests.size)
        assertNotNull(requests[0].header("x-medusa"))
        assertEquals("333", requests[0].url.queryParameter("device_id"))
        assertEquals("74332", requests[0].url.queryParameter("version_code"))
        assertTrue(requests[0].header("User-Agent")!!.contains("74332"))
        assertNull(requests[1].header("x-medusa"))
        assertNull(requests[1].url.queryParameter("device_id"))
        requests.forEach { assertEquals(account.cookie, it.header("Cookie")) }
        assertTrue(cursor.unsignedFallback)
        val restored = RecommendationCursor()
        assertTrue(restored.restore(cursor.snapshot()))
        assertTrue(restored.unsignedFallback)
        assertFalse(restored.nextBatch(restartSession = true).unsignedFallback)
    }

    @Test
    fun signedRecommendationReusesOneRegistrationAcrossPagesAndKeepsAnonymousCookie() {
        var registrations = 0
        val session = PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered("333", "444", cookies = mapOf("install_id" to "444"))
        }
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request(); requests += req
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (requests.size == 1) infiniteResponse() else cellResponse("102", 6)).toResponseBody()).build()
        }.build()
        val feed = RecommendationFeed(http, playbackSession = session)
        feed.page(1);feed.page(2)
        assertEquals(1, registrations)
        requests.forEach {
            assertNotNull(it.header("x-medusa"))
            assertEquals("333", it.url.queryParameter("device_id"))
            assertEquals("444", it.url.queryParameter("iid"))
            assertEquals("install_id=444", it.header("Cookie"))
        }
        val profile = session.profile(http)
        assertSame(profile.recommendationSigner({ 1000L }, profile.values), profile.recommendationSigner({ 2000L }, profile.values))
        assertNotSame(profile.signer({ 1000L }, profile.values), profile.recommendationSigner({ 1000L }, profile.values))
    }

    @Test
    fun signedCursorRebuildsAfterIdentityChangeWithoutLosingSeenWorks() {
        var registrations = 0
        val session = PlaybackDeviceSession { DeviceRegister.Registered("${333 + ++registrations}", "444") }
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request(); requests += req
            val payload = when (requests.size) {
                1 -> infiniteResponse()
                2 -> cellResponse("102", 6)
                else -> infiniteResponse().replace("101", "103")
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody()).build()
        }.build()
        val cursor = RecommendationCursor()
        val feed = RecommendationFeed(http, cursor = cursor, playbackSession = session)
        feed.page(1);feed.page(2)
        assertTrue(session.rotate(http, session.profile(http)))
        assertEquals("103", feed.page(3).items.single().id)
        assertEquals(2, registrations)
        assertEquals("/reading/bookapi/bookmall/tab/v1/", requests[2].url.encodedPath)
        assertEquals("0", requests[2].url.queryParameter("offset"))
        assertNull(requests[2].url.queryParameter("session_id"))
        assertEquals(setOf("101", "102", "103"), cursor.seenIds())
    }

    @Test
    fun infiniteVideoFeedUsesCellContinuationAndPersistsItsActualServerCursor() {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            requests += req
            val payload = when (requests.size) {
                1 -> infiniteResponse()
                2 -> cellResponse("102", 6)
                else -> cellResponse("103", 12)
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(payload.toResponseBody()).build()
        }.build()
        val cursor = RecommendationCursor()
        val feed = RecommendationFeed(http, cursor = cursor)
        assertEquals("101", feed.page(1).items.single().id)
        assertEquals("102", feed.page(2).items.single().id)
        val restored = RecommendationCursor()
        assertTrue(restored.restore(cursor.snapshot()))
        assertEquals("103", RecommendationFeed(http, cursor = restored).page(1).items.single().id)
        assertEquals(listOf("/reading/bookapi/bookmall/tab/v1/", "/reading/bookapi/bookmall/cell/change/v1/",
            "/reading/bookapi/bookmall/cell/change/v1/"), requests.map { it.url.encodedPath })
        assertEquals(listOf("0", "0", "6"), requests.map { it.url.queryParameter("offset") })
        assertEquals("9001", requests[1].url.queryParameter("cell_id"))
        assertEquals("8001", requests[1].url.queryParameter("plan_id"))
        assertEquals("fixture-v7", requests[1].url.queryParameter("version_tag"))
        assertEquals("2", requests[1].url.queryParameter("client_req_type"))
        assertEquals("cell-session", requests[2].url.queryParameter("session_id"))
        assertEquals(setOf("101", "102"), requests[2].url.queryParameter("filter_ids")!!.split(",").toSet())
        assertNotNull(cursor.nextBatch().cellFlow)
        assertNull(cursor.nextBatch(restartSession = true).cellFlow)
    }

    @Test
    fun legacySnapshotRebuildsTheEntryButRetainsDeduplication() {
        val cursor = RecommendationCursor()
        assertTrue(cursor.restore("""{"session":"legacy-session","offset":51,"seen":["101"]}"""))
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request(); requests += req
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (requests.size == 1) infiniteResponse() else cellResponse("102", 6)).toResponseBody()).build()
        }.build()
        assertEquals("102", RecommendationFeed(http, cursor = cursor).page(1).items.single().id)
        assertNull(requests[0].url.queryParameter("session_id"))
        assertEquals("0", requests[0].url.queryParameter("offset"))
        assertTrue(requests[1].url.encodedPath.contains("cell/change"))
    }

    @Test
    fun cellContinuationRejectsStalledOffsetsAndInvalidSavedContext() {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (calls == 1) infiniteResponse() else cellResponse("102", 0)).toResponseBody()).build()
        }.build()
        val cursor = RecommendationCursor()
        val feed = RecommendationFeed(http, cursor = cursor)
        feed.page(1)
        val error = assertThrows(IOException::class.java) { feed.page(2) }
        assertTrue(error.message.orEmpty().contains("游标未前进"))
        assertEquals(2, calls)
        val saved = JSONObject(cursor.snapshot())
        saved.getJSONObject("cellFlow").put("cellId", "invalid")
        assertFalse(RecommendationCursor().restore(saved.toString()))
    }

    @Test
    fun parsesBothCardShapesAndDoesNotMistakeHasMoreFalseForFeedEnd() {
        for (wrapped in listOf(false, true)) {
            val data = JSONObject(response(wrapped = wrapped))
            if (wrapped) {
                val tab = data.getJSONObject("data").getJSONArray("tab_item").getJSONObject(0)
                tab.remove("has_more")
                tab.remove("bottom_unlimited")
            }
            val page = RecommendationFeed.parse(data).first
            assertEquals(listOf("101"), page.items.map { it.id })
            assertTrue(page.hasMore)
            assertEquals("共 80 集", page.items.single().badge)
        }
    }

    @Test
    fun firstEpisodeHintRequiresExplicitFirstEpisodeMetadata() {
        val data = JSONObject(response())
        val row =
            data
                .getJSONObject("data")
                .getJSONArray("tab_item")
                .getJSONObject(0)
                .getJSONArray("cell_data")
                .getJSONObject(0)
                .getJSONArray("cell_data")
                .getJSONObject(0)
                .getJSONArray("video_data")
                .getJSONObject(0)
        row.put("vid", "999") // Recommended clip is not proof of episode one.
        assertTrue(RecommendationFeed.parse(data).first.trialHints.isEmpty())
        row.put("first_vid", "10101")
        val hint = RecommendationFeed.parse(data).first.trialHints.getValue("101")
        assertEquals("10101", hint.episodeId)
        assertEquals(80, hint.episodeCount)
        row.remove("first_vid")
        row.put(
            "video_list",
            JSONArray()
                .put(JSONObject().put("vid_index", 2).put("vid", "10102"))
                .put(JSONObject().put("vid_index", 1).put("vid", "10101")),
        )
        assertEquals(
            "10101",
            RecommendationFeed.parse(data).first.trialHints.getValue("101").episodeId,
        )
    }

    @Test
    fun paginationRefreshAndCancelledResultRetryKeepServerCursor() {
        val requests = mutableListOf<Request>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requests += chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(response((101 + requests.size).toString()).toResponseBody())
                        .build()
                }
                .build()
        val cursor = RecommendationCursor()
        val feed = RecommendationFeed(http, cursor = cursor)
        feed.page(1)
        val second = feed.page(2)
        assertSame(second, RecommendationFeed(http, cursor = cursor).page(2))
        assertEquals(2, requests.size)
        RecommendationFeed(http, cursor = cursor.nextBatch()).page(1)
        // offset 按「一条响应实际下发的条数」前进（fixture 每轮 2 条），而不是固定 +1，
        // 否则会与上一页大面积重叠。
        assertEquals(listOf("0", "2", "4"), requests.map { it.url.queryParameter("offset") })
        assertNull(requests[0].header("Cookie"))
        assertNull(requests[0].url.queryParameter("device_id"))
        assertEquals("test-page-session", requests[1].url.queryParameter("session_id"))
        assertEquals("2", requests[2].url.queryParameter("client_req_type"))
        assertThrows(IOException::class.java) { feed.page(4) }
    }

    @Test
    fun repeatedBatchesAreSkippedWithinBoundedRetry() {
        var calls = 0
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    calls++
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(response(if (calls < 3) "101" else "102").toResponseBody())
                        .build()
                }
                .build()
        val feed = RecommendationFeed(http)
        assertEquals("101", feed.page(1).items.single().id)
        assertEquals("102", feed.page(2).items.single().id)
        assertEquals(3, calls)
        assertThrows(IOException::class.java) { feed.page(3) }
        assertEquals(6, calls)
    }

    @Test
    fun accountIdentityIsSentOnlyToOfficialFeedAndGuestCursorIsSeparate() {
        var requested: Request? = null
        val http =
            OkHttpClient.Builder()
                .followRedirects(false)
                .addInterceptor { chain ->
                    requested = chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(response().toResponseBody())
                        .build()
                }
                .build()
        val account = AccountCredentials("sessionid=test-only", "test-device", "test-install")
        RecommendationFeed(http, account).page(1)
        assertEquals("api5-normal-sinfonlinea.fqnovel.com", requested!!.url.host)
        assertEquals(account.cookie, requested!!.header("Cookie"))
        // 账号态只带 Cookie：再带 device_id 会被 feed 接口判 110 ILLEGAL_ACCESS。
        assertNull(requested!!.url.queryParameter("device_id"))
        assertNull(requested!!.url.queryParameter("iid"))
        RecommendationFeed(http).page(1)
        assertNull(requested!!.header("Cookie"))
        assertNull(requested!!.url.queryParameter("session_id"))
    }

    @Test
    fun categoryRecommendationsSendGenreWithoutFixedSortAndKeepCursor() {
        for (category in listOf(DramaCategory.COMIC, DramaCategory.REAL)) {
            val requests = mutableListOf<Request>()
            val payloads = mutableListOf<JSONObject>()
            val http =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        requests += req
                        val buffer = okio.Buffer()
                        req.body!!.writeTo(buffer)
                        val payload = JSONObject(buffer.readUtf8())
                        payloads += payload
                        val row =
                            JSONObject()
                                .put(
                                    "series_id",
                                    if (payload.optInt("offset") == 0) "201" else "202",
                                )
                                .put("series_title", "分类推荐作品")
                                .put("episode_cnt", 40)
                        val data =
                            JSONObject()
                                .put("video_data", JSONArray().put(row))
                                .put("has_more", true)
                                .put("next_offset", payload.optInt("offset") + 24)
                                .put("session_id", "test-category-session")
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(
                                JSONObject()
                                    .put("code", 0)
                                    .put("data", data)
                                    .toString()
                                    .toResponseBody()
                            )
                            .build()
                    }
                    .build()
            val cursor = RecommendationCursor()
            val feed = RecommendationFeed(http, cursor = cursor, category = category)
            assertEquals("201", feed.page(1).items.single().id)
            assertEquals("202", feed.page(2).items.single().id)
            val genre = if (category == DramaCategory.COMIC) "comic_series" else "short_play"
            assertEquals(
                genre,
                payloads[0].getJSONObject("select_items").getJSONArray("genre").getString(0),
            )
            assertEquals(0, payloads[0].getJSONObject("select_items").getJSONArray("sort").length())
            assertEquals(24, payloads[1].getInt("offset"))
            assertEquals("201", payloads[1].getString("filter_ids"))
            assertEquals("test-category-session", payloads[1].getString("session_id"))
            assertEquals("POST", requests[0].method)
            assertEquals("/reading/distribution/category/landpage/v/", requests[0].url.encodedPath)
            assertNotNull(requests[0].header("X-Argus"))
        }
    }

    @Test
    fun eachHomepageSourceRetainsItsOwnRefreshCursor() {
        val state = DramaFeedState()
        state.recommendationCursor.offset = 2
        state.select(DramaCategory.COMIC)
        assertEquals(0, state.recommendationCursor.offset)
        state.recommendationCursor.offset = 24
        state.select(DramaCategory.REAL)
        assertEquals(0, state.recommendationCursor.offset)
        state.recommendationCursor.offset = 48
        state.select(DramaCategory.COMIC)
        assertEquals(24, state.recommendationCursor.offset)
        state.recommend()
        assertEquals(2, state.recommendationCursor.offset)
        state.refresh(resetRecommendations = true)
        state.select(DramaCategory.REAL)
        assertEquals(0, state.recommendationCursor.offset)
    }

    @Test
    fun rejectsInvalidResponsesInsteadOfSilentlyFallingBackToCategories() {
        assertThrows(IOException::class.java) {
            RecommendationFeed.parse(JSONObject(response(tab = 8)))
        }
        assertThrows(IOException::class.java) {
            RecommendationFeed.parse(JSONObject().put("code", 1))
        }
        assertThrows(IOException::class.java) {
            RecommendationFeed.parse(JSONObject(response(id = "invalid")))
        }
        val value = JSONObject(response())
        value
            .getJSONObject("data")
            .getJSONArray("tab_item")
            .getJSONObject(0)
            .put("session_id", "bad\nsession")
        assertThrows(IOException::class.java) { RecommendationFeed.parse(value) }
    }

    @Test
    fun stateRefreshMovesToNextBatchAndAccountResetDropsSession() {
        val state = DramaFeedState()
        assertTrue(state.recommendation)
        state.recommendationCursor.session = "test-page-session"
        state.recommendationCursor.offset = 3
        state.refresh()
        assertEquals(3, state.recommendationCursor.offset)
        state.select(DramaCategory.REAL)
        assertFalse(state.recommendation)
        state.recommend()
        assertTrue(state.recommendation)
        state.refresh(resetRecommendations = true)
        assertEquals(0, state.recommendationCursor.offset)
        assertEquals("", state.recommendationCursor.session)
    }

    @Test
    fun oversizedPersonalizedFeedResponseIsAccepted() {
        // The signed-in mall tab returns multi-megabyte payloads; the old 2 MiB account
        // reader rejected them with "账号接口响应过大".
        val payload = response() + " ".repeat(3 * 1024 * 1024)
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(payload.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()
        val page = RecommendationFeed(http).page(1)
        assertEquals(listOf("101"), page.items.map { it.id })
    }

    /** 服务端判定旧分页会话不合法（如 110）时，丢弃 session 从头拉一次，而不是持续报错。 */
    @Test
    fun stalePagingSessionIsDroppedAndRetriedFromTheStart() {
        val requests = mutableListOf<Request>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    val body =
                        if (requests.size == 1) """{"code":110,"data":null}"""
                        else response("301")
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        val cursor = RecommendationCursor()
        cursor.session = "stale-session"
        cursor.offset = 3
        val page = RecommendationFeed(http, cursor = cursor).page(1)
        assertEquals(1, page.items.size)
        assertEquals(2, requests.size)
        assertEquals("stale-session", requests[0].url.queryParameter("session_id"))
        // 重试同时换到备用域名，避免反复打同一个被限流的节点。
        assertNotEquals(requests[0].url.host, requests[1].url.host)
        assertNull(requests[1].url.queryParameter("session_id"))
        assertEquals("0", requests[1].url.queryParameter("offset"))
        assertEquals("1", requests[1].url.queryParameter("client_req_type"))
    }

    /** 冷启动续刷依赖快照往返；异常输入必须被拒绝而不是污染游标。 */
    @Test
    fun cursorSnapshotRoundTripsAndRejectsGarbage() {
        val cursor = RecommendationCursor()
        cursor.session = "sess-1"
        cursor.offset = 18
        cursor.seen.addAll(listOf("a", "b", "c"))
        val saved = cursor.snapshot()
        val restored = RecommendationCursor()
        assertTrue(restored.restore(saved))
        assertEquals("sess-1", restored.session)
        assertEquals(18, restored.offset)
        assertTrue(restored.seen.containsAll(listOf("a", "b", "c")))
        assertFalse(RecommendationCursor().restore("not-json"))
        assertFalse(RecommendationCursor().restore("""{"session":"","offset":-5}"""))
        val tooLong = """{"session":"${"x".repeat(5000)}","offset":0}"""
        assertFalse(RecommendationCursor().restore(tooLong))
    }
}
