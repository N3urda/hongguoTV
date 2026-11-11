package com.hongguotv.core

import java.io.IOException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProducersTest {
    private fun metadata(id: String = "123", media: String = "456", name: String = "制作方甲") =
        """{"code":0,"data":[{"book_id":"$id","media_id":"$media","author":"$name","book_name":"测试剧","final_chapter_number":"40","thumb_url":"https://example.com/cover.jpg"}]}"""

    @Test fun readsCreditWithoutTreatingMediaIdAsSocialUserId() {
        val credit = Producers.parse(JSONObject(metadata())).getValue("123")
        assertEquals(Producer("456", "制作方甲"), credit.producer)
        assertEquals("全 40 集", credit.series.badge)
        assertEquals("https://example.com/cover.jpg", credit.series.cover)
        assertTrue(Producers.parse(JSONObject("""{"code":0,"data":[{"book_id":"123","media_id":"456"}]}""")).isEmpty())
        assertThrows(IOException::class.java) { Producers.parse(JSONObject("""{"code":0,"data":{}}""")) }
        assertThrows(IOException::class.java) { Producers.parse(JSONObject("""{"code":-1,"data":[]}""")) }
    }

    @Test fun sameNameDoesNotMergeDifferentProvidersAndUnknownCreditsAreExcluded() {
        val candidates = listOf(Series("1", "一"), Series("2", "二"), Series("3", "未知"), Series("1", "重复"))
        val credits = mapOf(
            "1" to ProducerCredit(Producer("10", "同名"), candidates[0]),
            "2" to ProducerCredit(Producer("20", "同名"), candidates[1]),
        )
        assertEquals(listOf("1"), Producers.verified(Producer("10", "同名"), candidates, credits).map { it.id })
        assertFalse(Producers.matches(Producer("10", "同名"), Producer("", "同名")))
        assertTrue(Producers.matches(Producer("", "制作方甲"), Producer("10", "制作方甲")))
    }

    private fun repository(answer: (Request) -> String) = ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(answer(chain.request()).toResponseBody()).build()
    }.build())

    @Test fun searchCandidatesAreVerifiedAndEmptyFilteredPageKeepsPagination() {
        val repo = repository { request ->
            if (request.url.encodedPath == "/api/search/result") {
                assertEquals("制作方甲", request.url.queryParameter("query"))
                """{"pageNum":1,"hasMore":true,"searchList":[{"video_data":{"series_id":"123","series_name":"同名制作方的其他剧","genre":"205"}}]}"""
            } else {
                assertEquals("123", request.url.queryParameter("book_id"))
                metadata(media = "999")
            }
        }
        val page = repo.producerWorks(Producer("456", "制作方甲"))
        assertTrue(page.items.isEmpty())
        assertTrue(page.hasMore)
    }

    @Test fun rejectedSearchIsNotReportedAsAnEmptyCompleteFilmography() {
        val repo = repository { """{"code":"search_rejected","message":"Search is temporarily unavailable."}""" }
        assertThrows(IOException::class.java) { repo.producerWorks(Producer("456", "制作方甲")) }
    }

    @Test fun creditCacheAvoidsRepeatedRequestsAndDoesNotCacheFailures() {
        var requests = 0
        val repo = repository { requests++; if (requests == 1) """{"code":-1}""" else metadata() }
        assertThrows(IOException::class.java) { repo.producer("123") }
        assertEquals(Producer("456", "制作方甲"), repo.producer("123"))
        assertEquals(Producer("456", "制作方甲"), repo.producer("123"))
        assertEquals(2, requests)
    }
}
