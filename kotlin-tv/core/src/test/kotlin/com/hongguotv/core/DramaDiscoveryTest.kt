package com.hongguotv.core

import java.io.IOException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DramaDiscoveryTest {
    private fun section(category: DramaCategory, topic: String, page: Int) =
        JSONObject()
            .put("isSuccess", true)
            .put(
                "categoryRoute",
                JSONObject()
                    .put("contentType", category.route)
                    .put("enabled", true)
                    .put(
                        "canonicalPath",
                        "/category/${category.route}" + if (topic.isEmpty()) "" else "/$topic",
                    ),
            )
            .put(
                "selectorList",
                JSONArray()
                    .put(
                        JSONObject()
                            .put(
                                "items",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("selector_item_id", "fantasy")
                                            .put("show_name", "玄幻")
                                    ),
                            )
                    ),
            )
            .put(
                "recommendList",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("series_id", (100 + page).toString())
                            .put("series_name", "第$page 页作品")
                    ),
            )
            .put(
                "pagination",
                JSONObject()
                    .put("pageNum", page)
                    .put("pageSize", 24)
                    .put("totalPages", 2)
                    .put("total", 48),
            )

    private fun html(section: JSONObject) =
        "<script>_ROUTER_DATA=" +
            JSONObject().put("loaderData", JSONObject().put("category_$", section)).toString() +
            ";</script>"

    @Test
    fun categoryAndTopicAreSentToServerWithRealPagination() {
        for (category in DramaCategory.entries) {
            val http =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        assertEquals("/category/${category.route}/fantasy", request.url.encodedPath)
                        assertEquals("2", request.url.queryParameter("page"))
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(html(section(category, "fantasy", 2)).toResponseBody())
                            .build()
                    }
                    .build()
            val result = ContentRepository(http).discover(category, "fantasy", 2)
            assertEquals("102", result.items.single().id)
            assertFalse(result.hasMore)
            assertEquals(2, result.discovery!!.totalPages)
            assertEquals("玄幻", result.discovery!!.topics.single().label)
        }
    }

    @Test
    fun unconfirmedRoutesUnknownTopicsAndWrongPagesCannotMasqueradeAsFiltering() {
        val valid = section(DramaCategory.COMIC, "fantasy", 1)
        assertThrows(IOException::class.java) {
            DiscoveryParser.info(valid, DramaCategory.REAL, "fantasy", 1)
        }
        assertThrows(IOException::class.java) {
            DiscoveryParser.info(valid, DramaCategory.COMIC, "fantasy", 2)
        }
        assertThrows(IOException::class.java) {
            DiscoveryParser.info(
                section(DramaCategory.COMIC, "missing", 1),
                DramaCategory.COMIC,
                "missing",
                1,
            )
        }
        assertThrows(IOException::class.java) {
            DiscoveryParser.info(valid.put("isSuccess", false), DramaCategory.COMIC, "fantasy", 1)
        }
    }

    @Test
    fun topicOptionsAreValidatedAndCategoryChangesDiscardStaleTopicAndMemory() {
        val state = DramaDiscoveryState()
        state.select(DramaCategory.COMIC, "fantasy")
        state.info = DiscoveryInfo(listOf(DramaTopic("fantasy", "玄幻")), 2, 24, 48)
        state.browse = DiscoveryMemory(2, listOf(Series("1", "剧")), false, "1", state.info)
        state.select(DramaCategory.REAL)
        assertEquals("", state.topic)
        assertNull(state.info)
        assertNull(state.browse)
        assertThrows(IllegalArgumentException::class.java) {
            state.select(DramaCategory.COMIC, "../search")
        }
    }

    @Test
    fun searchUsesSourceHasMoreWithoutLocallyFilteringResults() {
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val section =
                        JSONObject()
                            .put("hasMore", false)
                            .put(
                                "searchList",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("series_id", "1")
                                            .put("series_name", "跨类型剧")
                                    ),
                            )
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(section.toString().toResponseBody())
                        .build()
                }
                .build()
        val result = ContentRepository(http).searchAll("测试")
        assertEquals(1, result.items.size)
        assertFalse(result.hasMore)
    }

    @Test
    fun transientSearchFailureRetriesTheSamePageAndThenSucceeds() {
        var calls = 0
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    calls++
                    assertEquals("2", chain.request().url.queryParameter("page"))
                    val body =
                        if (calls == 1) "Bad Gateway"
                        else
                            """{"pageNum":2,"hasMore":false,"searchList":[{"series_id":"2","series_name":"第二页"}]}"""
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (calls == 1) 502 else 200)
                        .message("test")
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        val result = ContentRepository(http).searchAll("测试", 2)
        assertEquals(2, calls)
        assertEquals("2", result.items.single().id)
    }

    @Test
    fun searchRetryIsBoundedAndPermanentOrMalformedResponsesAreNotRetried() {
        for (code in listOf(502, 403, 200)) {
            var calls = 0
            val http =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        calls++
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(code)
                            .message("test")
                            .body("invalid response".toResponseBody())
                            .build()
                    }
                    .build()
            assertThrows(IOException::class.java) { ContentRepository(http).searchAll("测试", 2) }
            assertEquals(if (code == 502) 2 else 1, calls)
        }
    }

    @Test
    fun cancelledSearchScopeDoesNotSendAnotherRequest() {
        var calls = 0
        val scope = RequestScope()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    calls++
                    scope.cancel()
                    throw java.net.SocketTimeoutException("test timeout")
                }
                .build()
        assertThrows(IOException::class.java) {
            scope.run { ContentRepository(http).searchAll("测试", 2) }
        }
        assertEquals(1, calls)
    }
}
