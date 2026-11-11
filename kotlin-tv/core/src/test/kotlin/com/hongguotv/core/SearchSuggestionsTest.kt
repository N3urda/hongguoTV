// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SearchSuggestionsTest {
    @Test
    fun pinyinIsSentUnmodifiedAndSuggestionsAreBoundedDistinctQueryTerms() {
        val requests = mutableListOf<Request>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requests += chain.request()
                    val body =
                        """{"suggest_list":[{"name":" 大佬，你该出手了 "},{"name":"大佬，你该出手了"},{"display_words":["大佬快醒醒"]},{"name":""},{"name":"${"长".repeat(81)}"}]}"""
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        val repository = ContentRepository(http)
        assertEquals(emptyList<String>(), repository.searchSuggestions("  "))
        assertTrue(requests.isEmpty())
        assertEquals(listOf("大佬，你该出手了", "大佬快醒醒"), repository.searchSuggestions(" dalao "))
        repository.searchSuggestions("大佬 & 重生")
        assertEquals("/incent_resource/suggestion", requests.first().url.encodedPath)
        assertEquals("dalao", requests.first().url.queryParameter("query"))
        assertEquals("大佬 & 重生", requests.last().url.queryParameter("query"))
        assertEquals("8", requests.first().url.queryParameter("count"))
        assertEquals(
            requests.first().url.queryParameter("web_id"),
            requests.last().url.queryParameter("web_id"),
        )
    }

    @Test
    fun errorResponseDoesNotBecomeASuggestion() {
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{}".toResponseBody())
                        .build()
                }
                .build()
        assertThrows(IOException::class.java) { ContentRepository(http).searchSuggestions("测试") }
    }
}
