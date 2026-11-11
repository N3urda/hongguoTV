package com.hongguotv.core

import java.io.IOException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudFavoritesTest {
    @Test
    fun videoShelfUsesCurrentCredentialsAndKeepsMetadataAndCollectionOrder() {
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    assertEquals("GET", request.method)
                    assertEquals(
                        "/reading/bookapi/bookshelf/video/info/v1/",
                        request.url.encodedPath,
                    )
                    assertEquals("test-only", request.header("Cookie"))
                    assertEquals("123", request.url.queryParameter("device_id"))
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"code":0,"data":{"video_shelf_info":[
              {"video_data":{"series_id":"1","episode_cnt":12},"series_title":"第一部","series_cover_url":"https://example.com/a.jpg","series_intro":"简介","collect_time":100},
              {"video_data":{"series_id":"2","title":"第二部","category":"系统"},"collect_time":200},
              {"video_data":{"series_id":"1"},"series_title":"较早的重复条目","collect_time":50}
            ]}}"""
                                .toResponseBody()
                        )
                        .build()
                }
                .build()
        val rows = CloudAccountClient(http).favorites(AccountCredentials("test-only", "123", "456"))
        assertEquals(listOf("2", "1"), rows.map { it.series.id })
        assertEquals(200000L, rows.first().collectedAt)
        assertEquals("全 12 集", rows.last().series.badge)
        assertEquals("https://example.com/a.jpg", rows.last().series.cover)
        assertEquals("简介", rows.last().series.description)
    }

    @Test
    fun malformedShelfNeverLooksLikeAnEmptyCollection() {
        for (raw in
            listOf(
                "{}",
                """{"data":{}}""",
                """{"data":{"video_shelf_info":{}}}""",
                """{"data":{"video_shelf_info":[{"series_title":"missing id"}]}}""",
            )) assertThrows(IOException::class.java) {
            CloudAccountClient.parseFavorites(JSONObject(raw))
        }
        assertTrue(
            CloudAccountClient.parseFavorites(JSONObject("""{"data":{"video_shelf_info":[]}}"""))
                .isEmpty()
        )
        assertTrue(
            CloudAccountClient.parseFavorites(JSONObject("""{"data":{"video_shelf_info":null}}"""))
                .isEmpty()
        )
    }
}
