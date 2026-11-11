package com.hongguotv.core

import java.io.IOException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudReservationsTest {
    @Test
    fun categoriesUseCurrentAccountAndDistinctOfficialQueries() {
        val categories = mutableListOf<String>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    assertEquals("GET", request.method)
                    assertEquals("/reading/user/subscribe/list/v0/", request.url.encodedPath)
                    assertEquals("13", request.url.queryParameter("tab_type"))
                    assertEquals("20", request.url.queryParameter("limit"))
                    assertEquals("0", request.url.queryParameter("offset"))
                    assertEquals("sessionid=test-account", request.header("Cookie"))
                    categories += request.url.queryParameter("is_online")!!
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"code":0,"data":{"subscribe_items":[]}}""".toResponseBody())
                        .build()
                }
                .build()
        val client = CloudAccountClient(http)
        for (online in listOf(true, false)) assertTrue(
            client.reservations(AccountCredentials("sessionid=test-account"), online).isEmpty()
        )
        assertEquals(listOf("true", "false"), categories)
    }

    @Test
    fun reservationsUseSeriesIdNotSubscriptionIdAndKeepReleaseTime() {
        val raw =
            JSONObject(
                """{"data":{"subscribe_items":[
          {"item_id":"999","is_online":false,"schedule_publish_time":1792500000,"subscribe_data":{"series_id":"123","title":"待上线测试剧","cover":"https://example.com/a.jpg"}},
          {"item_id":"998","is_online":false,"subscribe_data":{"series_id":"123","title":"重复条目"}},
          {"is_online":true,"subscribe_data":{"series_id":"456","title":"另一个分类"}},
          {"item_id":"997","subscribe_data":{"title":"没有剧集编号"}}
        ]}}"""
            )
        val row = CloudAccountClient.parseReservations(raw, false).single()
        assertEquals("123", row.series.id)
        assertEquals("待上线测试剧", row.series.title)
        assertEquals("待上线", row.series.badge)
        assertEquals(1792500000000L, row.publishAt)
        assertFalse(row.online)
    }

    @Test
    fun categoryCanSupplyOnlineStateAndMillisecondsAreNotMultiplied() {
        val raw =
            JSONObject(
                """{"data":{"subscribe_items":[{"schedule_publish_time":1792500000000,"video_data":{"series_id":"123","title":"已上线测试剧"}}]}}"""
            )
        val row = CloudAccountClient.parseReservations(raw, true).single()
        assertTrue(row.online)
        assertEquals("已上线", row.series.badge)
        assertEquals(1792500000000L, row.publishAt)
    }

    @Test
    fun explicitEmptyIsDifferentFromMalformedResponses() {
        assertTrue(
            CloudAccountClient.parseReservations(
                    JSONObject("""{"data":{"subscribe_items":null}}"""),
                    false,
                )
                .isEmpty()
        )
        for (raw in
            listOf(
                "{}",
                """{"data":{}}""",
                """{"data":{"subscribe_items":"invalid"}}""",
                """{"data":{"subscribe_items":[{"other":"changed"}]}}""",
            )) {
            assertThrows(IOException::class.java) {
                CloudAccountClient.parseReservations(JSONObject(raw), true)
            }
        }
    }

    @Test
    fun unpublishedFlatItemsKeepMetadataWithoutInventingPlayableSeriesIds() {
        val raw =
            JSONObject(
                """{"data":{"subscribe_items":[{"is_online":false,"item_id":"777","name":"待上线示例","item_desc":"剧集简介","cover":"https://example.com/poster.jpg","expected_rec_text":"明天上线","category":"漫剧"}]}}"""
            )
        val item = CloudAccountClient.parseReservations(raw, false).single()
        assertEquals("reservation:777", item.series.id)
        assertEquals("待上线示例", item.series.title)
        assertEquals("https://example.com/poster.jpg", item.series.cover)
        assertEquals("剧集简介", item.series.description)
        assertEquals("明天上线", item.publishText)
        assertFalse(item.online)
        assertThrows(IOException::class.java) { CloudAccountClient.parseReservations(raw, true) }
    }

    @Test
    fun rejectedSessionIsNotReportedAsNoReservations() {
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"code":100103}""".toResponseBody())
                        .build()
                }
                .build()
        assertThrows(IOException::class.java) {
            CloudAccountClient(http).reservations(AccountCredentials("test-only"), true)
        }
    }
}
