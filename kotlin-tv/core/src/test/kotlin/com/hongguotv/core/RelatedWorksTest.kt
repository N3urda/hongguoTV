package com.hongguotv.core

import java.io.IOException
import java.util.zip.GZIPInputStream
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RelatedWorksTest {
    private val data =
        """{"code":0,"data":[{"show_type":547,"cell_name":"相关作品","video_data":[
      {"series_id":"111","title":"当前季","content_type":1004,"episode_cnt":80},
      {"series_id":"222","title":"下一季","content_type":1004,"episode_cnt":60,"season_index":2},
      {"series_id":"333","title":"第三季","content_type":1007,"episode_cnt":0,"season_index":3,"disable_play":false},
      {"series_id":"444","title":"原著小说","content_type":1},
      {"series_id":"555","title":"不可播放","content_type":1004,"episode_cnt":20,"disable_play":true},
      {"series_id":"222","title":"重复季","content_type":1004,"episode_cnt":60}
    ]}]}"""

    private fun http(answer: (Request) -> String) =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(answer(chain.request()).toResponseBody())
                    .build()
            }
            .build()

    @Test
    fun seasonSuffixMayOmitOrdinalMarkerWithoutSplittingSeriesOrSkippingSeasons() {
        val stem = "谁说没灵根不能修仙的？之无灵证道"
        val current = Series("39", stem + "第39季")
        val next = RelatedWork(Series("40", stem + "40季"), "第40季", false, 40)
        val fortyOne = RelatedWork(Series("41", stem + "41季"), "第41季", false, 41)
        val fortyTwo = RelatedWork(Series("42", stem + "第42季"), "第42季", false, 42)
        assertEquals(next, RelatedWorks.nextSeason(current, listOf(fortyTwo, next, fortyOne)))
        assertEquals(fortyOne, RelatedWorks.nextSeason(next.series, listOf(fortyTwo, fortyOne)))
        assertEquals(listOf("40", "41", "42"), RelatedWorks.ordered(current, listOf(fortyTwo, fortyOne, next)).map { it.series.id })
        assertNull(RelatedWorks.nextSeason(current, listOf(fortyOne)))
        assertNull(RelatedWorks.nextSeason(current, listOf(next, next.copy(series = Series("400", stem + "第四十季")))))
        assertNull(RelatedWorks.nextSeason(current, listOf(next.copy(series = Series("400", "其他剧40季")))))
        assertEquals(next.copy(series = Series("400", stem + "第四十季")),
            RelatedWorks.nextSeason(current, listOf(next.copy(series = Series("400", stem + "第四十季")))))
    }

    @Test
    fun currentSeriesStaysTogetherBeforeOtherIpSeriesRegardlessOfSeasonNumber() {
        val current = Series("1", "谁说没灵根不能修仙的？之无灵证道")
        val own3 = RelatedWork(Series("3", "谁说没灵根不能修仙的？之无灵证道第3季"), "第3季", false, 3)
        val own2 = RelatedWork(Series("2", "谁说没灵根不能修仙的？之无灵证道第二季"), "第2季", false, 2)
        val other = RelatedWork(Series("8", "砍仙"), "同IP", false, 1)
        val other2 = RelatedWork(Series("9", "砍仙第二季"), "同IP", false, 2)
        assertEquals(listOf("2", "3", "8", "9"), RelatedWorks.ordered(current, listOf(other, own3, other2, own2)).map { it.series.id })
    }

    @Test
    fun keepsMoreThanThirtySeasonsAndSortsNumericSeasonOrder() {
        val rows = (40 downTo 1).joinToString(",") {
            """{"series_id":"${1000 + it}","title":"长剧第${it}季","content_type":1004,"episode_cnt":10,"season_index":$it}"""
        }
        val parsed = RelatedWorks.parse("""{"code":0,"data":[{"show_type":547,"video_data":[$rows]}]}""", "999")
        assertEquals(40, parsed.size)
        assertEquals((1..40).toList(), parsed.map { it.seasonIndex })
    }

    @Test
    fun nextSeasonRequiresUniqueConsecutivePlayableTitleMatch() {
        val current = Series("1", "长剧第三十四季")
        val next = RelatedWork(Series("2", "长剧第35季"), "第35季", false, 35)
        val unrelated = next.copy(series = Series("3", "其他剧第35季"))
        assertEquals(next, RelatedWorks.nextSeason(current, listOf(unrelated, next)))
        assertNull(RelatedWorks.nextSeason(current, listOf(next.copy(upcoming = true))))
        assertNull(RelatedWorks.nextSeason(current, listOf(next.copy(seasonIndex = 36))))
        assertNull(RelatedWorks.nextSeason(current, listOf(next, next.copy(series = Series("4", "长剧第三十五季")))))
        assertNull(RelatedWorks.nextSeason(current, listOf(unrelated)))
        assertEquals(RelatedWork(Series("5", "短剧第二季"), "", false, 2),
            RelatedWorks.nextSeason(Series("6", "短剧"), listOf(RelatedWork(Series("5", "短剧第二季"), "", false, 2))))
    }

    @Test
    fun parsesOfficialSeasonTypesAndExcludesCurrentNovelBlockedAndDuplicates() {
        val rows = RelatedWorks.parse(data, "111")
        assertEquals(listOf("222", "333"), rows.map { it.series.id })
        assertEquals("第2季", rows[0].season)
        assertFalse(rows[0].upcoming)
        assertEquals("第3季", rows[1].season)
        assertTrue(rows[1].upcoming)
    }

    @Test
    fun sourceUsesOfficialRelatedCellAndEmptyResponseFailoverWithExplicitRefresh() {
        var calls = 0
        val repository =
            ContentRepository(
                http { request ->
                    assertEquals("/reading/bookapi/plan/v:version/", request.url.encodedPath)
                    assertEquals("10", request.url.queryParameter("scene"))
                    assertEquals("detail_page_more_related", request.url.queryParameter("from"))
                    assertEquals("111", request.url.queryParameter("book_id"))
                    calls++
                    if (calls == 1) "" else data
                }
            )
        assertEquals(2, repository.related("111").size)
        assertEquals(2, calls)
        repository.related("111")
        assertEquals(2, calls)
        repository.related("111", force = true)
        assertEquals(3, calls)
    }

    @Test
    fun unavailableSourceFailsAfterBoundedRetriesRatherThanPretendingNoRelatedWorks() {
        var calls = 0
        val repository =
            ContentRepository(
                http {
                    calls++
                    ""
                }
            )
        assertThrows(IOException::class.java) { repository.related("111") }
        assertEquals(3, calls)
    }

    @Test
    fun subscriptionOnlyWritesRequestedFutureSeriesWithNumericIdAndCompressedJson() {
        var calls = 0
        val client =
            CloudAccountClient(
                http { request ->
                    calls++
                    assertEquals("POST", request.method)
                    assertEquals(
                        "/reading/bookapi/search/uncover_subscribe/v1/",
                        request.url.encodedPath,
                    )
                    assertEquals("sessionid=test-only", request.header("Cookie"))
                    assertEquals("gzip", request.header("Content-Encoding"))
                    val buffer = okio.Buffer()
                    request.body!!.writeTo(buffer)
                    val bytes = buffer.readByteArray()
                    val json =
                        JSONObject(GZIPInputStream(bytes.inputStream()).bufferedReader().readText())
                    assertTrue(json.get("item_id") is Number)
                    assertEquals(333L, json.getLong("item_id"))
                    assertEquals(1, json.getInt("op_type"))
                    assertEquals(3, json.getInt("item_type"))
                    """{"code":0}"""
                }
            )
        client.reserve(
            AccountCredentials("sessionid=test-only"),
            RelatedWorks.parse(data, "111")[1],
        )
        assertEquals(1, calls)
        assertThrows(IllegalArgumentException::class.java) {
            client.reserve(AccountCredentials("test-only"), RelatedWorks.parse(data, "111")[0])
        }
        assertEquals(1, calls)
    }

    @Test
    fun rejectedReservationIsNeverAccepted() {
        val client = CloudAccountClient(http { """{"code":100103}""" })
        assertThrows(IOException::class.java) {
            client.reserve(AccountCredentials("test-only"), RelatedWorks.parse(data, "111")[1])
        }
    }
}
