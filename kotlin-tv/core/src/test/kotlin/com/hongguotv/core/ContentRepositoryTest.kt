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
import java.io.IOException

class ContentRepositoryTest {
    private fun streamRepository(vararg variants: Triple<Int,String,String>) = repository { request ->
        if(request.url.host=="example.com") {
            val rows=JSONObject()
            variants.forEachIndexed { index,(height,codec,url) -> rows.put("video_$index",JSONObject().put("vheight",height).put("codec_type",codec).put("main_url",url)) }
            JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
        } else """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
    }

    @Test fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(1080,"bytevc1","https://example.com/hevc"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/hevc",result.url)
        assertEquals("1080P（兼容资源）",result.quality)
    }

    @Test fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(480,"h264","https://example.com/avc"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("480P",repo.stream("123",720).quality)
    }

    @Test fun entirelyIncompatibleSourceFailsBeforeStartingAudio() {
        val repo=streamRepository(Triple(720,"ByteVC2","https://example.com/unsupported"),Triple(480,"bvc2","https://example.com/unsupported2"))
        assertThrows(IOException::class.java) { repo.stream("123",720) }
    }

    @Test fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo=streamRepository(Triple(720,"","https://example.com/legacy"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("720P",repo.stream("123",720).quality)
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build(),clock
    )

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray().put(JSONObject().put("seriesId","123").put("title","测试漫剧")
            .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            .put("episodeCount",12).put("tags",JSONArray().put("玄幻"))))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun currentRank(payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-comic-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",23).put("heatText","5746万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(payload)
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("9月22日已更新",result.ranking.updatedText)
        assertTrue(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test fun missingRankIsNotInventedAndLastPageStops() {
        val result=repository { currentRank(rankPayload(5,5)) }.comicRanking(5)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(null,""),result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=5">5</a></nav>"""
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
    }

    @Test fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo=repository { currentRank(rankPayload(1,5)) }
        assertThrows(IOException::class.java) { repo.comicRanking(2) }
        assertThrows(IllegalArgumentException::class.java) { repo.comicRanking(0) }
    }

    @Test fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result=repo.comicRanking(5)
        assertEquals(5,result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test fun comicHomeUsesDedicatedRankAndRealPagination() {
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            currentRank(rankPayload(2,2))
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试漫剧",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("全 12 集",page.items.single().badge)
        assertFalse(page.hasMore)
    }

    @Test fun legacyRankIsStillSupported() {
        val repo=repository { script("r",JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload())) }
        assertTrue(repo.home(1,ContentType.COMIC).hasMore)
    }

    @Test fun comicHomeDoesNotUseUnrelatedRankData() {
        val repo=repository { script("r",JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload())) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun rejectedComicRankRemainsAnError() {
        val repo=repository { currentRank(rankPayload().put("isSuccess",false)) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试 & 漫剧",page.items.single().title)
        assertEquals("https://example.com/a.jpg",page.items.single().cover)
        assertTrue(page.hasMore)
    }

    @Test fun comicSearchCarriesCursorAndKeepsTypesAndQueriesSeparate() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            if(request.url.encodedPath.startsWith("/category/comic-drama")) catalogPagePayload(request.url.queryParameter("page")?.toInt() ?: 1,2,"661",title="仙尊漫画")
            else JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","comic-session").put("passback","comic-cursor").put("next_offset",25))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals("comic-session",requests[1].url.queryParameter("search_id"))
        assertEquals("comic-cursor",requests[1].url.queryParameter("passback"))
        assertEquals("25",requests[1].url.queryParameter("offset"))
        // 新词没有游标：第 2 页先试一次接口（不带旧词游标），再静默落到类型目录。
        repo.search("重生",2,ContentType.COMIC)
        assertEquals(null,requests[2].url.queryParameter("search_id"))
        assertEquals(null,requests[2].url.queryParameter("passback"))
    }

    @Test fun expiredCursorRequestsFreshSearchInsteadOfDuplicateOffsetPage() {
        var now=0L
        val requests=mutableListOf<Request>()
        val repo=repository(clock={ now }) { request ->
            requests+=request
            if(request.url.encodedPath.startsWith("/category/comic-drama")) catalogPagePayload(1,1,"661",title="修仙漫画")
            else JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","session").put("passback","cursor"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        now=300001L
        // 游标过期（>5 分钟）：过期在发请求**之前**就抛——fqnovel 只被问第 1 页这一次，
        // 第 2 页静默落到类型目录补页（comic-drama payload 的「仙尊漫画」命中）。
        val page2=repo.search("修仙",2,ContentType.COMIC)
        assertEquals(1,requests.count { it.url.host.endsWith("fqnovel.com") })
        assertEquals(listOf("661"),page2.items.map { it.id })
    }

    @Test fun shortHomeStillUsesCategoryAndStopsOnLastPage() {
        val repo=repository { request ->
            assertEquals("1",request.url.queryParameter("tab"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试短剧"}],"pagination":{"totalPages":1}}}};</script>"""
        }
        val result=repo.home(1,ContentType.SHORT)
        assertEquals("456",result.items.single().id)
        assertFalse(result.hasMore)
    }

    private fun suggestionPayload(vararg entries: Pair<String,String?>): String {
        // Pair<名称, series_id?>：series_id 为 null 表示纯查询词条目。
        val rows=JSONArray()
        entries.forEach { (name,sid) ->
            val row=JSONObject().put("name",name).put("word_type","short_play_name")
            if(sid!=null) row.put("video_data",JSONObject().put("series_id",sid)
                .put("series_name",name).put("series_cover","https://example.com/$sid.jpg").put("episode_cnt",8))
            rows.put(row)
        }
        return JSONObject().put("suggest_list",rows).toString()
    }

    @Test fun suggestionSearchIsTypedByComicCatalogMembership() {
        val repo=repository { request ->
            when {
                request.url.encodedPath.startsWith("/incent_resource/suggestion") ->
                    suggestionPayload("仙尊漫剧" to "111","仙尊短剧" to "456","随便一词" to null)
                request.url.encodedPath=="/category/comic-drama" ->
                    """<script>_ROUTER_DATA={"loaderData":{"category_${'$'}":{"recommendList":[{"series_id":"111","series_name":"仙尊漫剧"}],"pagination":{"totalPages":1}}}};</script>"""
                else -> throw IOException("unexpected ${request.url}")
            }
        }
        // 漫剧搜索：suggestion 的 111 在漫画目录里 → 收；456 不在 → 绝不混进漫剧结果。
        val comic=repo.search("仙尊",1,ContentType.COMIC)
        assertEquals(listOf("111"),comic.items.map { it.id })
        assertEquals("https://example.com/111.jpg",comic.items.single().cover)
        // 短剧搜索：456 不在漫画目录 → 收；111 在 → 绝不混进短剧结果。
        val short=repo.search("仙尊",1,ContentType.SHORT)
        assertEquals(listOf("456"),short.items.map { it.id })
    }

    @Test fun suggestionSearchSkippedWhenComicCatalogWalkFails() {
        // 漫画目录不可用 → 没有类型信息 → 宁可走目录慢路径，也不放行无类型条目（防混排回潮）。
        val repo=repository { request ->
            when {
                request.url.encodedPath.startsWith("/incent_resource/suggestion") ->
                    suggestionPayload("仙尊短剧" to "456")
                request.url.encodedPath=="/category/comic-drama" -> throw IOException("catalog down")
                request.url.encodedPath=="/category/real-drama" ->
                    """<script>_ROUTER_DATA={"loaderData":{"category_${'$'}":{"recommendList":[{"series_id":"771","series_name":"仙尊归来"}],"pagination":{"totalPages":1}}}};</script>"""
                else -> throw IOException("unexpected ${request.url}")
            }
        }
        val result=repo.search("仙尊",1,ContentType.SHORT)
        assertEquals(listOf("771"),result.items.map { it.id })
    }

    @Test fun searchAllTagsTypeAndAspectPerItem() {
        val repo=repository { request ->
            when {
                request.url.encodedPath.startsWith("/incent_resource/suggestion") ->
                    suggestionPayload("仙尊漫剧" to "111","仙尊短剧" to "456","随便一词" to null)
                request.url.encodedPath=="/category/comic-drama" ->
                    """<script>_ROUTER_DATA={"loaderData":{"category_${'$'}":{"recommendList":[{"series_id":"111","series_name":"仙尊漫剧"}],"pagination":{"totalPages":1}}}};</script>"""
                else -> throw IOException("unexpected ${request.url}")
            }
        }
        val page=repo.searchAll("仙尊",1)
        val comic=page.items.single { it.id=="111" }
        val short=page.items.single { it.id=="456" }
        assertEquals("comic",comic.kind); assertEquals("漫剧 · 横屏 16:9",comic.badge)
        assertEquals("short",short.kind); assertEquals("短剧 · 竖屏 9:16",short.badge)
        // 纯查询词条目（无 series）不进结果。
        assertEquals(2,page.items.size)
        // 本机实测优先于类型默认档（默认已是 9:16，实测 16:9 覆盖它）。
        val measured=repo.searchAll("仙尊",1,mapOf("456" to "16:9"))
        assertEquals("短剧 · 横屏 16:9",measured.items.single { it.id=="456" }.badge)
    }

    @Test fun searchAllCatalogContinuationCoversBothTypes() {
        val repo=repository { request ->
            when {
                request.url.encodedPath.startsWith("/incent_resource/suggestion") -> suggestionPayload()
                request.url.encodedPath=="/category/comic-drama" ->
                    """<script>_ROUTER_DATA={"loaderData":{"category_${'$'}":{"recommendList":[{"series_id":"111","series_name":"仙尊漫剧传"}],"pagination":{"totalPages":1}}}};</script>"""
                request.url.encodedPath=="/category/real-drama" ->
                    """<script>_ROUTER_DATA={"loaderData":{"category_${'$'}":{"recommendList":[{"series_id":"456","series_name":"仙尊短剧记"}],"pagination":{"totalPages":1}}}};</script>"""
                else -> throw IOException("unexpected ${request.url}")
            }
        }
        // 第 1 页 suggestion 命中不到（空 suggest_list）→ 落到目录扫描：两本目录都扫、各自标注。
        val page=repo.searchAll("仙尊",1)
        assertEquals(setOf("111","456"),page.items.map { it.id }.toSet())
        assertEquals("comic",page.items.single { it.id=="111" }.kind)
        assertEquals("short",page.items.single { it.id=="456" }.kind)
    }

    private fun searchTab(type: Int,id: String,title: String="测试剧集") = JSONObject().put("tab_type",type).put("has_more",true)
        .put("data",JSONArray().put(JSONObject().put("video_data",JSONArray().put(JSONObject()
            .put("series_id",id).put("title",title).put("episode_cnt",8)))))

    @Test fun comicSearchNeverReturnsShortDramaItems() {
        // 漫剧搜索只能得到漫剧条目：响应里同时带着短剧 tab，也绝不能拿来填。
        val repo=repository {
            JSONObject().put("code",0).put("search_tabs",JSONArray()
                .put(searchTab(11,"111","真人短剧甲")).put(searchTab(19,"999","漫剧乙"))).toString()
        }
        val result=repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("999"),result.items.map { it.id })
        assertEquals(listOf("漫剧乙"),result.items.map { it.title })
    }

    @Test fun shortSearchNeverReturnsComicItems() {
        // 反过来也一样，而且特意把漫剧 tab 放在第一位：证明不是"取第一个 tab"。
        val repo=repository {
            JSONObject().put("code",0).put("search_tabs",JSONArray()
                .put(searchTab(19,"999","漫剧乙")).put(searchTab(11,"111","真人短剧甲"))).toString()
        }
        val result=repo.search("修仙",1,ContentType.SHORT)
        assertEquals(listOf("111"),result.items.map { it.id })
        assertEquals(listOf("真人短剧甲"),result.items.map { it.title })
    }

    @Test fun sameKeywordStaysIndependentAcrossContentTypes() {
        // 同一个关键词在两类里各搜一次，结果集与游标都不串味。
        val repo=repository {
            JSONObject().put("code",0).put("search_tabs",JSONArray()
                .put(searchTab(11,"111","短剧·同一关键词")).put(searchTab(19,"999","漫剧·同一关键词"))).toString()
        }
        assertEquals(listOf("111"),repo.search("修仙",1,ContentType.SHORT).items.map { it.id })
        assertEquals(listOf("999"),repo.search("修仙",1,ContentType.COMIC).items.map { it.id })
    }

    @Test fun blankAppSearchBodyFallsBackToWebsiteImmediately() {
        // 空响应体（设备未在服务端注册）必须一次即判死，不再重发拖慢首屏；
        // 官网之前会先试一次 suggestion 分型（其漫画目录探测失败即跳过，不拖第二腿）。
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size==1) ""
            else """<script>_ROUTER_DATA={"loaderData":{"search_page":{"searchList":[{"series_id":"456","series_name":"测试短剧"}]}}}};</script>"""
        }
        assertEquals("456",repo.search("短剧",1,ContentType.SHORT).items.single().id)
        // appSearch(空) → 漫画目录探测(失败即弃) → 官网快路径。
        assertEquals(3,paths.size)
    }

    @Test fun comicSearchRequestsAndSelectsOnlyComicTab() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("19",request.url.queryParameter("tab_type"))
            assertEquals(if(requests==1) "0" else "20",request.url.queryParameter("offset"))
            assertEquals("修仙",request.url.queryParameter("query"))
            JSONObject().put("code",0).put("search_tabs",JSONArray()
                .put(searchTab(11,"111")).put(searchTab(19,"999"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("999"),repo.search("修仙",2,ContentType.COMIC).items.map { it.id })
    }

    @Test fun emptyComicSearchDoesNotFallBackToShortResults() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"search_tabs":[{"tab_type":19,"data":[],"has_more":false}]}""" }
        assertTrue(repo.search("无结果",1,ContentType.COMIC).items.isEmpty())
        assertEquals(1,requests)
    }

    @Test fun missingComicTabDoesNotFallBackToShortResults() {
        // 接口缺漫剧 tab、官网搜索也没有有效数据 → 落到漫画自己的目录（空目录），
        // 结果里绝不出现短剧条目（111 是接口响应里的短剧 id）。
        val repo=repository { request ->
            if(request.url.encodedPath.startsWith("/category/comic-drama")) catalogPagePayload(1,1,"999",title="测试漫画")
            else """{"code":0,"search_tabs":[{"tab_type":11,"data":[]}]}"""
        }
        val result=repo.search("仙尊",1,ContentType.COMIC)
        assertTrue(result.items.all { it.id != "111" })
    }

    @Test fun comicFallbackScansComicCatalogOnly() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(request.url.encodedPath.startsWith("/category/comic-drama")) catalogPagePayload(request.url.queryParameter("page")?.toInt() ?: 1,2,"66${request.url.queryParameter("page")}",title="仙尊漫画 第${request.url.queryParameter("page")}部")
            else """{"code":0,"search_tabs":[{"tab_type":11,"data":[]}]}"""
        }
        // 两类各扫各的目录：漫剧只允许碰 comic-drama，绝不去 real-drama 拿短剧内容来填。
        val page1=repo.search("仙尊",1,ContentType.COMIC)
        assertEquals(listOf("661","662"),page1.items.map { it.id })
        repo.search("仙尊",2,ContentType.COMIC)
        assertTrue(paths.any { it.startsWith("/category/comic-drama") })
        assertTrue(paths.none { it.startsWith("/category/real-drama") })
    }

    @Test fun blankAppSearchBodyIsMarkedDeadAndSkippedOnLaterPages() {
        var searchRequests=0
        val repo=repository { request ->
            if(request.url.host.endsWith("fqnovel.com")) { searchRequests++; "" }
            catalogPagePayload(1,2,"771",title="仙尊归来")
        }
        // 空响应体 = 接口判死：第 1 页仍试一次，之后翻页不再空跑 fqnovel。
        repo.search("仙尊",1,ContentType.SHORT)
        assertEquals(1,searchRequests)
        repo.search("仙尊",2,ContentType.SHORT)
        assertEquals(1,searchRequests)
    }

    @Test fun shortSearchKeepsWebsiteFallback() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size==1) throw IOException("offline")
            """<script>_ROUTER_DATA={"loaderData":{"search_page":{"searchList":[{"series_id":"456","series_name":"测试短剧"}]}}}};</script>"""
        }
        assertEquals("456",repo.search("短剧",1,ContentType.SHORT).items.single().id)
        // appSearch(断网) → 漫画目录探测(断网即弃) → 官网快路径兜住。
        assertEquals(3,paths.size)
    }

    private fun catalogPagePayload(page: Int,totalPages: Int,id: String,title: String="仙尊归来 第${page}部") =
        // 超出 totalPages 的页回空列表：与真实站点一致（页码越界不给数据）。
        if(page>totalPages) """<script>_ROUTER_DATA={"loaderData":{"category_${'$'}":{"recommendList":[],"pagination":{"totalPages":$totalPages}}}};</script>"""
        else """<script>_ROUTER_DATA={"loaderData":{"category_${'$'}":{"recommendList":[{"series_id":"$id","series_name":"$title"}],"pagination":{"totalPages":$totalPages}}}};</script>"""

    @Test fun shortSearchPaginationScansRealDramaCatalogWithoutRepeats() {
        val repo=repository { request ->
            when {
                request.url.encodedPath.startsWith("/search/") ->
                    """<script>_ROUTER_DATA={"loaderData":{"search_page":{"searchList":[{"series_id":"456","series_name":"测试短剧"}]}}}};</script>"""
                request.url.encodedPath=="/category/real-drama" ->
                    catalogPagePayload(request.url.queryParameter("page")?.toInt() ?: 1,3,"77${request.url.queryParameter("page")}")
                else -> throw IOException("unexpected ${request.url}")
            }
        }
        assertEquals("456",repo.search("仙尊",1,ContentType.SHORT).items.single().id)
        // 第 2 页起扫目录（real-drama），接上第 1 页没出现过的条目；目录只有 3 页，hasMore 收掉。
        val page2=repo.search("仙尊",2,ContentType.SHORT)
        assertEquals(listOf("771","772","773"),page2.items.map { it.id })
        assertFalse(page2.hasMore)
        // 第 3 页从目录第 4 页起扫：目录已尽，空页收场，不再重复已给过的条目。
        val page3=repo.search("仙尊",3,ContentType.SHORT)
        assertTrue(page3.items.isEmpty())
        assertFalse(page3.hasMore)
    }

    @Test fun catalogDedupKeyIsScopedPerType() {
        // 同一 repo 实例里两个关键词各自维护去重表：先翻完「甲乙」的目录页，
        // 「丙丁」的第 2 页不许被它的去重表吞掉（key 必须带关键词）。
        val repo=repository { request ->
            when {
                request.url.encodedPath.startsWith("/search/") ->
                    """<script>_ROUTER_DATA={"loaderData":{"search_page":{"searchList":[{"series_id":"456","series_name":"甲乙丙丁"}]}}}};</script>"""
                request.url.encodedPath=="/category/real-drama" -> {
                    val page=request.url.queryParameter("page")?.toInt() ?: 1
                    catalogPagePayload(page,2,"88$page",title="甲乙丙丁仙尊传 第${page}部")
                }
                else -> throw IOException("unexpected ${request.url}")
            }
        }
        assertEquals("456",repo.search("甲乙",1,ContentType.SHORT).items.single().id)
        assertEquals(listOf("881","882"),repo.search("甲乙",2,ContentType.SHORT).items.map { it.id })
        assertEquals("456",repo.search("丙丁",1,ContentType.SHORT).items.single().id)
        // 「丙丁」的第 2 页拿到的是它自己目录里的条目，没有被「甲乙」的去重表吞掉。
        assertEquals(listOf("881","882"),repo.search("丙丁",2,ContentType.SHORT).items.map { it.id })
    }
}
