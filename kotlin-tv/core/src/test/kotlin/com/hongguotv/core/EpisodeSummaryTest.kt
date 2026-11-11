package com.hongguotv.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class EpisodeSummaryTest {
    @Test
    fun labelsUseOneBasedResumePositionRatherThanClaimingWatchedEpisodeCount() {
        assertEquals("共 80 集\n看到第 12 集", EpisodeSummary.label(80, 11))
        assertEquals("共 80 集\n暂无本机进度", EpisodeSummary.label(80))
        assertTrue(EpisodeSummary.label(80, account = true, ready = true).contains("近50条无观看记录"))
        assertTrue(EpisodeSummary.label(80, account = true, ready = false).contains("待刷新"))
        assertEquals("待上线\n暂不可观看", EpisodeSummary.label(0, pending = true))
        assertTrue(EpisodeSummary.label(0).startsWith("总集数待更新"))
        assertTrue(EpisodeSummary.label(80, markedWatched = true).contains("本机已标记看完"))
        assertEquals(80, EpisodeSummary.total("全 80 集"))
        assertEquals(90, EpisodeSummary.total("更新至 90 集"))
        assertEquals(0, EpisodeSummary.total("80万热度"))
    }

    @Test
    fun reservationTotalsUseOptionalBatchMetadataWithoutHidingListOnFailure() {
        for (fail in listOf(false, true)) {
            var calls = 0
            val http =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        calls++
                        val request = chain.request()
                        val body =
                            if (request.url.encodedPath.contains("subscribe/list"))
                                """{"code":0,"data":{"subscribe_items":[{"video_data":{"series_id":"123","title":"剧集"}}]}}"""
                            else {
                                assertEquals(
                                    "/reading/bookapi/multi-detail/v1/",
                                    request.url.encodedPath,
                                )
                                assertEquals("123", request.url.queryParameter("book_id"))
                                if (fail) throw java.io.IOException("offline")
                                """{"code":0,"data":[{"book_id":"123","drama_chapter_number":"80"}]}"""
                            }
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(body.toResponseBody())
                            .build()
                    }
                    .build()
            val rows = CloudAccountClient(http).reservations(AccountCredentials("test-only"), true)
            assertEquals(2, calls)
            assertEquals(1, rows.size)
            assertEquals(if (fail) 0 else 80, EpisodeSummary.total(rows.single().series.badge))
        }
    }

    @Test
    fun historyPreservesReportedSeriesEpisodeCount() {
        val row =
            CloudAccountClient.parseProgress(
                    JSONArray("""[{"series_id":"1","vid":"2","vid_index":12,"episode_count":80}]""")
                )
                .single()
        assertEquals(80, row.episodeCount)
        assertEquals(11, row.episodeIndex)
    }
}
