package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SuggestionTest {
    private fun repository(respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build()
    )

    /** 一条联想词；[series] 为 true 时带一份可用的 video_data（表示它指向某一部剧）。 */
    private fun row(name: String, type: String, series: Boolean = false) = JSONObject()
        .put("name", name).put("word_type", type)
        .put("video_data", if (series) JSONObject().put("series_id", "456").put("series_name", name) else JSONObject())

    private fun suggestJson(rows: List<JSONObject>) = JSONObject().put("suggest_list", JSONArray(rows)).toString()

    @Test fun suggestionsReadsNameWordTypeAndSeries() {
        val paths = mutableListOf<String>()
        val repo = repository { request ->
            paths += request.url.encodedPath
            suggestJson(listOf(row("都市逆袭", "common_query"), row("我的漫剧", "short_play_name", series = true)))
        }
        val result = repo.suggestions("短剧", 10)
        assertEquals(listOf("/incent_resource/suggestion"), paths)
        assertEquals(2, result.size)
        assertEquals("都市逆袭", result[0].name)
        assertEquals("common_query", result[0].wordType)
        assertNull("video_data 是空对象的条目不算剧集", result[0].series)
        assertEquals("456", result[1].series?.id)
    }

    @Test fun suggestionsSkipsTheRequestForAnEmptyKeyword() {
        // 空关键词服务端返回 500，因此调用前必须判空、一个请求都不该发。
        var requests = 0
        val repo = repository { requests++; "" }
        assertTrue(repo.suggestions("   ", 10).isEmpty())
        assertTrue(repo.suggestions("", 10).isEmpty())
        assertEquals(0, requests)
    }

    @Test fun suggestionsClampsCountAndDeduplicatesByName() {
        val repo = repository { request ->
            val count = request.url.queryParameter("count")!!.toInt()
            assertTrue("count 必须在 1..20", count in 1..20)
            suggestJson(listOf(row("同一个词", "title"), row("同一个词", "common_query"), row("另一个词", "title")))
        }
        assertEquals(2, repo.suggestions("词", 3).size)
        // count 夹到 1..20：0 / 负数按 1 算，超上限按 20 算（此时只有两条可返回）。
        assertEquals(1, repo.suggestions("词", 0).size)
        assertEquals(1, repo.suggestions("词", -5).size)
        assertEquals(2, repo.suggestions("词", 99).size)
    }

    @Test fun suggestionsFallsBackToDisplayWordsWhenNameIsMissing() {
        val repo = repository {
            JSONObject().put("suggest_list", JSONArray().put(JSONObject()
                .put("display_words", JSONArray().put("备用词")).put("word_type", "title"))).toString()
        }
        assertEquals(listOf("备用词"), repo.suggestions("词", 5).map { it.name })
    }

    @Test fun suggestionsReturnsEmptyWhenThePayloadHasNoList() {
        val repo = repository { """{"suggest_list":null}""" }
        assertTrue(repo.suggestions("词", 5).isEmpty())
    }

    @Test fun hotSearchesLayersByWordTypeAndRotatesAcrossSeeds() {
        val repo = repository { request ->
            when (request.url.queryParameter("query")) {
                "短剧" -> suggestJson(listOf(
                    row("30集短剧", "common_query"),
                    row("都市题材", "short_play_category"),
                    row("某一部剧", "short_play_name", series = true)))
                "都市" -> suggestJson(listOf(
                    row("逆袭题材", "category"),
                    row("都市剧名", "title")))
                else -> suggestJson(emptyList())
            }
        }
        val hot = repo.hotSearches(12)
        // 指向具体某部剧的条目（short_play_name）不进热搜表。
        assertFalse(hot.contains("某一部剧"))
        // 题材 / 分类 排在 common_query 之前。
        assertTrue("题材类应最靠前", hot.indexOf("都市题材") < hot.indexOf("30集短剧"))
        assertTrue("category 应排在 common_query 之前", hot.indexOf("逆袭题材") < hot.indexOf("30集短剧"))
        // 两个种子都供了同一层时按种子轮询，不把一个种子的词全排在前头。
        assertTrue(hot.contains("都市题材") && hot.contains("逆袭题材"))
    }

    @Test fun hotSearchesFallsBackToAStaticTableWhenEverySeedFails() {
        val repo = repository { throw IllegalStateException("接口整体不可用") }
        val hot = repo.hotSearches(12)
        assertEquals(12, hot.size)
        assertTrue(hot.isNotEmpty())
        // 兜底表同样不能含有指向具体剧集的词。
        assertTrue(hot.all { it.length in 2..12 })
    }

    @Test fun hotSearchesDropsOverlongAndTooShortCandidates() {
        val repo = repository { request ->
            when (request.url.queryParameter("query")) {
                "短剧" -> suggestJson(listOf(
                    row("一个字的超长候选词不该进表", "common_query"),
                    row("剧", "common_query"),
                    row("正常候选", "common_query")))
                else -> suggestJson(emptyList())
            }
        }
        val hot = repo.hotSearches(12)
        assertTrue("超长词应被过滤", hot.none { it.length > 12 })
        assertTrue("过短词应被过滤", hot.none { it.length < 2 })
        assertTrue(hot.contains("正常候选"))
    }
}
