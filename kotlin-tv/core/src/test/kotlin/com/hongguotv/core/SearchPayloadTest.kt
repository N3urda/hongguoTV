package com.hongguotv.core

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException

class SearchPayloadTest {
    private fun video(id: String, title: String) = JSONObject().put("series_id", id).put("title", title)
    private fun body(rows: JSONArray, more: Any = false) = JSONObject().put("code", 0)
        .put("search_tabs", JSONArray().put(JSONObject().put("tab_type", 19).put("data", rows).put("has_more", more)))

    @Test fun everySeasonInOneCellIsReturnedWithItsOwnTitle() {
        val row = JSONObject().put("search_high_light", JSONObject().put("title", JSONObject().put("text", "大佬，你该出手了")))
            .put("video_data", JSONArray().put(video("123", "大佬，你该出手了第四季")).put(video("124", "大佬，你该出手了第五季")))
        val result = SearchPayload.parse(body(JSONArray().put(row)).toString(), ContentType.COMIC, "大佬，你该出手了第五季")
        assertEquals(listOf("124", "123"), result.items.map { it.id })
        assertEquals("大佬，你该出手了第五季", result.items.first().title)
    }

    @Test fun objectVideoAndObjectLynxPayloadAreSupported() {
        val row = JSONObject().put("video_data", video("123", "测试漫剧"))
        val wrapper = JSONObject().put("lynx_data", JSONObject().put("cell_data", JSONArray().put(row)))
        assertEquals("123", SearchPayload.parse(body(JSONArray().put(wrapper)).toString(), ContentType.COMIC, "测试").items.single().id)
    }

    @Test fun stringLynxPayloadAndNestedTabsRemainSupported() {
        val row = JSONObject().put("video_data", JSONArray().put(video("123", "测试漫剧")))
        val wrapper = JSONObject().put("lynx_data", JSONObject().put("cell_data", JSONArray().put(row)).toString())
        val response = JSONObject().put("code", 0).put("data", body(JSONArray().put(wrapper)))
        assertEquals("123", SearchPayload.parse(response.toString(), ContentType.COMIC, "测试").items.single().id)
    }

    @Test fun malformedCellDoesNotHideTheRemainingValidSeasons() {
        val bad = JSONObject().put("lynx_data", "invalid")
        val row = JSONObject().put("video_data", JSONArray().put("not-an-object").put(video("bad", "无效"))
            .put(video("123", "有效标题")).put(video("123", "重复")))
        assertEquals(listOf("123"), SearchPayload.parse(body(JSONArray().put(bad).put(row)).toString(), ContentType.COMIC, "有效").items.map { it.id })
    }

    @Test fun highlightMarkupIsRemovedWithoutTruncatingSeasonSuffix() {
        val row = JSONObject().put("video_data", video("123", ""))
            .put("search_high_light", JSONObject().put("title", JSONObject().put("text", "<em>凡人百世书</em>第八季")))
        assertEquals("凡人百世书第八季", SearchPayload.parse(body(JSONArray().put(row)).toString(), ContentType.COMIC, "凡人").items.single().title)
    }

    @Test fun numericAndStringPaginationDoNotStopAtFirstPage() {
        for (more in listOf(1, "1", true, "true")) {
            val response = body(JSONArray(), more)
            response.getJSONArray("search_tabs").getJSONObject(0).put("search_id", "session").put("passback", "cursor").put("next_offset", "40")
            val result = SearchPayload.parse(response.toString(), ContentType.COMIC, "测试")
            assertTrue(result.hasMore)
            assertEquals("session", result.searchId)
            assertEquals("cursor", result.passback)
            assertEquals(40, result.nextOffset)
        }
    }

    @Test fun emptyMalformedOrRejectedResponseIsAnActionableError() {
        for (payload in listOf("", "  ", "<html>error</html>", "{\"code\":110001}")) {
            val error = assertThrows(SearchUnavailableException::class.java) { SearchPayload.parse(payload, ContentType.COMIC, "测试") }
            assertFalse(error.message.orEmpty().contains("End of input"))
            assertTrue(error.message.orEmpty().contains("搜索"))
        }
    }

    @Test fun comicRequestNeverReturnsTheShortDramaTab() {
        val response = body(JSONArray().put(JSONObject().put("video_data", video("123", "短剧"))))
        response.getJSONArray("search_tabs").getJSONObject(0).put("tab_type", 11)
        assertThrows(SearchUnavailableException::class.java) { SearchPayload.parse(response.toString(), ContentType.COMIC, "测试") }
    }

    @Test fun exactTitleWinsAndOtherSeasonsRemainWithoutUnrelatedRecommendations() {
        val items = listOf(Series("1", "其他推荐一"), Series("2", "大佬，你该出手了第四季"),
            Series("3", "大佬,你该出手了第五季"), Series("4", "其他推荐二"))
        assertEquals(listOf("3", "2"), SearchPayload.prioritize(items, "大佬，你该出手了第五季").map { it.id })
    }


    @Test fun websiteSearchFiltersGenresAndKeepsPagination() {
        val repo=ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/api/search/result",chain.request().url.encodedPath)
            assertNotNull(chain.request().header("Referer"))
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(okhttp3.ResponseBody.create(null,
                    """{"pageNum":1,"hasMore":true,"searchList":[{"video_data":{"series_id":"123","series_title":"漫剧","genre":"205"}},{"video_data":{"series_id":"456","series_title":"短剧","genre":"203"}}]}""")).build()
        }.build())
        assertEquals("123",repo.searchAll("剧",type=ContentType.COMIC).items.single().id)
        assertEquals("456",repo.searchAll("剧",type=ContentType.SHORT).items.single().id)
        val unrelated = repo.searchAll("测试")
        assertTrue(unrelated.items.isEmpty())
        assertTrue(unrelated.hasMore)
    }
}
