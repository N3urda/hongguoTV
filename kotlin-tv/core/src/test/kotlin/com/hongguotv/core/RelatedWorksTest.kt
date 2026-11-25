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
    fun seasonTitleMayCarryAnEditionAfterItsNumber() {
        // From the live 相关作品 of a comic drama: the last season is 「…第三季完结篇」.
        val stem = "和离后我带女儿横扫魔域"
        val first = RelatedWork(Series("1", stem), "第1季", false, 1)
        val second = RelatedWork(Series("2", stem + "第二季"), "第2季", false, 2)
        val finale = RelatedWork(Series("3", stem + "第三季完结篇"), "第3季", false, 3)
        val otherIp = RelatedWork(Series("9", "隐居魔帝：和离后我不装了第1季"), "同IP", false, 1)
        assertEquals(finale, RelatedWorks.nextSeason(second.series, listOf(first, finale, otherIp)))
        assertNull(RelatedWorks.nextSeason(finale.series, listOf(first, second, otherIp)))
        assertEquals(listOf("1", "2", "9"), RelatedWorks.ordered(finale.series, listOf(otherIp, second, first)).map { it.series.id })
        // Still conservative: the edition never makes another title stem match.
        assertNull(RelatedWorks.nextSeason(second.series, listOf(finale.copy(series = Series("4", "其他剧第三季完结篇")))))
    }

    @Test
    fun seasonWordsInsideTheNameAreNotASeason() {
        val first = Series("1", "爱在第三季的我们")
        val second = RelatedWork(Series("2", "爱在第三季的我们第二季"), "第2季", false, 2)
        assertEquals(second, RelatedWorks.nextSeason(first, listOf(second)))
        val otherIp = RelatedWork(Series("3", "别的剧"), "同IP", false, 1)
        assertEquals(listOf("2", "3"), RelatedWorks.ordered(first, listOf(otherIp, second)).map { it.series.id })
    }

    @Test
    fun sameIpWorkWithSeasonWordsButNoIndexIsNotTheNextSeason() {
        val stem = "和离后我带女儿横扫魔域"
        val second = Series("2", stem + "第二季")
        val sideStory = RelatedWork(Series("5", stem + "第三季外传"), "同IP", false, 0)
        assertNull(RelatedWorks.nextSeason(second, listOf(sideStory)))
    }

    // Live 相关作品 (2026-11-24) of 《公路求生榜一说他要走到公路尽头》: seasons 1–2 say 他, season 3 says 她.
    // Seen from another drama of the same IP, all three are 「同IP」.
    private val roadHe1 = Series("7691938290202577944", "公路求生榜一说他要走到公路尽头")
    private val roadHe2 = Series("7700477891418524696", "公路求生榜一说他要走到公路尽头第二季")
    private val roadShe3 = Series("7708355462780947518", "公路求生榜一说她要走到公路尽头第三季")
    private val roadOtherDrama = Series("7710278988953832472", "公路求生榜一说她要走到公路尽头第一季")

    private fun tagged(series: Series, tag: String, index: Int) = RelatedWork(series, tag, false, index, tag)

    private val roadRelated = mapOf(
        roadHe1.id to listOf(tagged(roadHe2, "第2季", 2), tagged(roadShe3, "第3季", 3), tagged(roadOtherDrama, "同IP", 0)),
        roadHe2.id to listOf(tagged(roadHe1, "第1季", 1), tagged(roadShe3, "第3季", 3), tagged(roadOtherDrama, "同IP", 0)),
        roadShe3.id to listOf(tagged(roadHe1, "第1季", 1), tagged(roadHe2, "第2季", 2)),
        roadOtherDrama.id to listOf(tagged(roadHe1, "同IP", 1), tagged(roadHe2, "同IP", 2), tagged(roadShe3, "同IP", 3)),
    )

    private fun roadRelatedOf(id: String) = roadRelated.getValue(id)

    @Test
    fun differentlyWordedNextSeasonFollowsHongguoTagsInBothDirections() {
        // The title rule alone stops at season 2, because 他 and 她 make the stems differ.
        assertNull(RelatedWorks.nextSeason(roadHe2, roadRelatedOf(roadHe2.id)))
        assertEquals(roadShe3.id, RelatedWorks.nextSeason(roadHe2, roadRelatedOf(roadHe2.id), ::roadRelatedOf)?.series?.id)
        // The title rule still answers first where it can, and the last season has no next.
        assertEquals(roadHe2.id, RelatedWorks.nextSeason(roadHe1, roadRelatedOf(roadHe1.id), ::roadRelatedOf)?.series?.id)
        assertNull(RelatedWorks.nextSeason(roadShe3, roadRelatedOf(roadShe3.id), ::roadRelatedOf))
        // Another drama of the same IP only sees 「同IP」 tags, so nothing is followed from it.
        assertNull(RelatedWorks.nextSeason(roadOtherDrama, roadRelatedOf(roadOtherDrama.id), ::roadRelatedOf))
    }

    @Test
    fun seasonTagRuleNeedsTheNextSeasonToNameTheCurrentOneBack() {
        val forward = roadRelatedOf(roadHe2.id)
        val back = roadRelatedOf(roadShe3.id)
        fun next(reverse: (String) -> List<RelatedWork>) = RelatedWorks.nextSeason(roadHe2, forward, reverse)
        assertEquals(roadShe3, next { back }?.series)
        assertNull(next { back.filter { it.series.id != roadHe2.id } })
        assertNull(next { back.map { if (it.series.id == roadHe2.id) tagged(roadHe2, "同IP", 2) else it } })
        assertNull(next { back.map { if (it.series.id == roadHe2.id) tagged(roadHe2, "第1季", 2) else it } })
        assertNull(next { throw IOException("offline") })
    }

    @Test
    fun seasonTagRuleNeedsOneUnconflictedPlayableCandidate() {
        val third = tagged(roadShe3, "第3季", 3)
        val first = tagged(roadHe1, "第1季", 1)
        fun next(vararg candidates: RelatedWork) =
            RelatedWorks.nextSeason(roadHe2, listOf(first, *candidates), ::roadRelatedOf)
        assertEquals(third, next(third))
        // A 「第3季」 synthesised from season_index, or 「同IP」 with season_index 3, is not Hongguo naming a season.
        assertNull(next(third.copy(rawTag = "")))
        assertNull(next(third.copy(season = "同IP", rawTag = "同IP")))
        assertNull(next(third.copy(upcoming = true)))
        assertNull(next(third, tagged(Series("8", "另一部第三季"), "第3季", 3)))
        assertNull(next(third.copy(seasonIndex = 4)))
        assertNull(next(third.copy(series = Series(roadShe3.id, "公路求生榜一说她要走到公路尽头第四季"))))
    }

    @Test
    fun parseKeepsHongguoTagApartFromTheSynthesisedSeason() {
        val rows = RelatedWorks.parse(
            """{"code":0,"data":[{"show_type":547,"video_data":[
              {"series_id":"2","title":"甲第二季","content_type":1004,"episode_cnt":9,"season_index":2,"tag_info":{"text":"第2季"}},
              {"series_id":"3","title":"甲第三季","content_type":1004,"episode_cnt":9,"season_index":3},
              {"series_id":"4","title":"乙","content_type":1004,"episode_cnt":9,"season_index":1,"tag_info":{"text":"同IP"}}
            ]}]}""",
            "1",
        ).associateBy { it.series.id }
        assertEquals(listOf("第2季", "第3季", "同IP"), listOf("2", "3", "4").map { rows.getValue(it).season })
        assertEquals(listOf("第2季", "", "同IP"), listOf("2", "3", "4").map { rows.getValue(it).rawTag })
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
