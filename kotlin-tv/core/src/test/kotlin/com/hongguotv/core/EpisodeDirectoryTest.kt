package com.hongguotv.core

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EpisodeDirectoryTest {
    private val seriesId = "7709114780467858456"
    private val ids = listOf("104", "102", "101", "105", "103")

    private fun web(episodes: List<String>? = listOf("v1_a", "v1_b", "v1_c"), count: Int = 5): String {
        val detail = JSONObject().put("series_id", seriesId).put("series_name", "目录测试")
            .put("series_cover", "https://example.test/cover.jpg").put("series_intro", "简介")
            .put("episode_cnt", count)
        episodes?.let { detail.put("vid_list", JSONArray(it)) }
        return "<script>_ROUTER_DATA=" + JSONObject().put("loaderData",
            JSONObject().put("detail_page", JSONObject().put("seriesDetail", detail))) + ";</script>"
    }

    private fun directory(values: List<String> = ids, code: Int? = 0): String =
        JSONObject().apply { code?.let { put("code", it) } }.put("message", "SUCCESS")
            .put("data", JSONObject().put("item_data_list", JSONArray(values.map {
                JSONObject().put("item_id", it)
            }))).toString()

    private fun repository(requests: MutableList<Request>, reply: (Request) -> String): ContentRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply(request).toResponseBody()).build()
        }.build()
        return ContentRepository(http, playbackSession = PlaybackDeviceSession {
            error("Browsing the episode directory must not register a playback device")
        })
    }

    @Test fun completeNumericWebDirectoryKeepsItsOrderWithoutExtraRequests() {
        val requests = mutableListOf<Request>()
        val detail = repository(requests) { web(ids) }.detail(seriesId)
        assertEquals(ids, detail.episodes)
        assertEquals(1, requests.size)
        assertEquals("/detail", requests.single().url.encodedPath)
    }

    @Test fun obfuscatedWebPreviewsUseSignedAppDirectoryAndPreserveMetadata() {
        val requests = mutableListOf<Request>()
        val detail = repository(requests) {
            if (it.url.encodedPath == "/detail") web() else directory()
        }.detail(seriesId)
        assertEquals(ids, detail.episodes)
        assertEquals("目录测试", detail.series.title)
        assertEquals("https://example.test/cover.jpg", detail.series.cover)
        assertEquals("简介", detail.series.description)
        assertEquals("全 5 集", detail.series.badge)
        val request = requests.last()
        assertEquals(2, requests.size)
        assertEquals("GET", request.method)
        assertEquals("/reading/bookapi/directory/all_items/v/", request.url.encodedPath)
        assertEquals(seriesId, request.url.queryParameter("book_id"))
        assertEquals("true", request.url.queryParameter("need_version"))
        assertNotNull(request.header("x-medusa"))
        assertNull(request.body)
    }

    @Test fun numericButTruncatedWebDirectoryIsAlsoReplaced() {
        val requests = mutableListOf<Request>()
        val detail = repository(requests) {
            if (it.url.encodedPath == "/detail") web(ids.take(3)) else directory()
        }.detail(seriesId)
        assertEquals(ids, detail.episodes)
        assertEquals(2, requests.size)
    }

    @Test fun absentEmptyAndDuplicateWebIdsRecoverTheWholeDirectory() {
        for (values in listOf(null, emptyList(), listOf("104", "104", "101", "105", "103"))) {
            val requests = mutableListOf<Request>()
            val detail = repository(requests) {
                if (it.url.encodedPath == "/detail") web(values) else directory()
            }.detail(seriesId)
            assertEquals(ids, detail.episodes)
            assertEquals(2, requests.size)
        }
    }

    @Test fun nestedReleasedCountDetectsTruncationButPlannedTotalDoesNot() {
        val detail = JSONObject().put("vid_list", JSONArray(ids.take(3)))
            .put("series_episode_info", JSONObject().put("episode_cnt", 5).put("episode_total_cnt", 100))
        assertNull(EpisodeDirectory.fromWeb(detail))
        detail.put("vid_list", JSONArray(ids))
        assertEquals(ids, EpisodeDirectory.fromWeb(detail))
        detail.put("episode_cnt", 6)
        assertNull(EpisodeDirectory.fromWeb(detail))
    }

    @Test fun acceptsSuccessWithoutCodeAndKeepsOriginalOrder() {
        assertEquals(ids, EpisodeDirectory.parse(directory(code = null), 5))
        assertEquals(ids, EpisodeDirectory.parse(directory().replace("\"code\"", "\"Code\""), 5))
    }

    @Test fun rejectsFailureResponsesAndIncompleteOrAmbiguousDirectories() {
        val invalid = listOf("", "<html>error</html>", "{}", directory(code = 100103),
            directory(code = null).replace("SUCCESS", "FAIL"), directory(emptyList()),
            directory(ids.take(3)), directory(listOf("104", "104", "101", "105", "103")),
            directory(listOf("104", "v1_invalid", "101", "105", "103")),
            """{"code":0,"data":{"item_data_list":[null,{"item_id":"1"}]}}""")
        for (body in invalid) {
            try {
                EpisodeDirectory.parse(body, 5)
                fail("Accepted invalid directory: $body")
            } catch (_: IOException) { }
        }
    }

    @Test fun emptyPrimaryResponseTriesOnlyOneAlternateHost() {
        val requests = mutableListOf<Request>()
        val detail = repository(requests) {
            when {
                it.url.encodedPath == "/detail" -> web()
                it.url.host == "api5-normal-sinfonlinea.fqnovel.com" -> ""
                else -> directory()
            }
        }.detail(seriesId)
        assertEquals(ids, detail.episodes)
        assertEquals(listOf("api5-normal-sinfonlinea.fqnovel.com", "api5-normal-sinfonlineb.fqnovel.com"),
            requests.drop(1).map { it.url.host })
    }

    @Test fun refusesToPresentOnlyThreeEpisodesWhenBothDirectoryHostsFail() {
        val requests = mutableListOf<Request>()
        val repo = repository(requests) {
            if (it.url.encodedPath == "/detail") web(ids.take(3)) else directory(ids.take(3))
        }
        try {
            repo.detail(seriesId)
            fail("A partial directory must not be shown as complete")
        } catch (error: IOException) {
            assertEquals("完整分集暂不可用，请稍后重试", error.message)
        }
        assertEquals(3, requests.size)
    }

    @Test fun cancellationStopsBeforeTryingAnotherDirectoryHost() {
        val scope = RequestScope()
        val requests = mutableListOf<Request>()
        val repo = repository(requests) {
            if (it.url.encodedPath == "/detail") web() else {
                scope.cancel()
                ""
            }
        }
        try {
            scope.run { repo.detail(seriesId) }
            fail("Cancellation was swallowed")
        } catch (error: IOException) {
            assertEquals("请求已取消", error.message)
        }
        assertEquals(2, requests.size)
    }

    @Test fun warmedDetailHandsOffTheCompleteDirectory() {
        val requests = mutableListOf<Request>()
        val repo = repository(requests) {
            if (it.url.encodedPath == "/detail") web() else directory()
        }
        repo.warmDetail(seriesId)
        assertEquals(ids, repo.detail(seriesId).episodes)
        assertEquals(2, requests.size)
    }
}
