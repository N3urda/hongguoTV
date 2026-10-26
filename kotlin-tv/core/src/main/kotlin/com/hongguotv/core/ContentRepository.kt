// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
// 官网页面里大量使用协议相对地址（`//p11-novel-seo.byteimg.com/...`）：
// OkHttp 解析没有 scheme 的 URL 会直接抛异常 → 封面加载失败 → 网格整排空白。
// 统一在这里补全 https:。
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
    .let { if(it.startsWith("//")) "https:$it" else it }
/**
 * 一部剧。[kind] 是内容类型（"comic"=漫剧 / "short"=短剧，空=未知），
 * [aspect] 是画幅档位（"16:9"=横版 / "9:16"=竖版，空=未知）——两者都是搜索结果的标注字段：
 * kind 来自漫画目录 id 集（权威边界），aspect 优先取本机实测（播放过就有），否则按类型给默认档。
 * 备份兼容：旧备份没有这两个字段，fromJson 按空串读。
 */
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "", val kind: String = "", val aspect: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
        .put("kind",kind).put("aspect",aspect)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags"),o.str("kind"),o.str("aspect")) }
}
data class Detail(val series: Series, val episodes: List<String>)
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String, val width: Int = 0, val height: Int = 0, val typeRaw: String = "")
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")
/** 播放资源端点拒绝（空响应等）：换设备也没用（fallback URL 自带 token），不值得轮换重试。 */
class PlayerResourceException(message: String): IOException(message)

/**
 * 播放响应类型原始值 → kind（"comic"/"short"）的标定表。
 * 标定流程：设备播放已知类型的剧各一部，从 logcat「stream typeRaw=」收集真实值回填。
 */
fun kindFromTypeRaw(raw: String): String? = when(raw.trim()) {
    else -> null
}
/**
 * 一条搜索提示词（联想词）。
 *
 * [series] 非空表示这一条指向某一部剧（`word_type=short_play_name`，带完整 video_data）；
 * 为 null 表示它只是一个查询词（`title` / `common_query` / `album` / `short_play_category` 等，
 * video_data 是空对象）。界面上一律当作"点它就搜这个词"，不直接跳简介页。
 */
data class SearchSuggestion(val name: String, val series: Series?, val wordType: String)

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS)
    .addInterceptor { chain ->
        // Attach the registered fqnovel device identity as a Cookie on every request.
        // Without this, fqnovel only grants a single "first request" grace play, then
        // flags the device (Code 110001). The cookie lets a registered device survive.
        val req = chain.request()
        val cookie = DeviceSession.cookie()
        val outgoing = if (cookie.isNotEmpty() && req.header("Cookie") == null) {
            req.newBuilder().header("Cookie", cookie).build()
        } else req
        chain.proceed(outgoing)
    }.build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    /**
     * App 搜索接口还能不能用：HTTP 200 + 空响应体 = 平台对这类设备不给数据（2026-10-24 实测
     * 所有线路、多组设备参数都一样）。null=还没试过；第 1 页仍给一次机会（平台恢复能自动接回），
     * 确认死后的页数不再空跑。账号登录态变化时重置（登录身份的结论独立）。
     */
    @Volatile internal var appSearchAlive: Boolean?=null
    /**
     * 导入的账号登录态（`Cookie:` 头整段串）；空串 = 匿名设备。
     * 由上层（设置页「导入账号参数」）写入；只进本地索引、不进备份。
     */
    @Volatile var accountCookie: String = ""
        set(value) { field=value; appSearchAlive=null }
    private val site = "https://hongguoduanju.com"
    /**
     * 提示词接口要求的应用号（缺了它服务端返回 500）。
     * 8662 就是「红果短剧」在番茄系里的 aid（与 [VendorConstants.device] 里的 `aid` 同值）。
     */
    private val SUGGEST_APP_ID = "8662"
    /**
     * 提示词接口的 `web_id`：官网上是每次页面加载生成的一个随机标识。
     * 这里整个进程生成一次即可（服务端并不校验它与会话的对应关系）。
     */
    private val webId = java.util.UUID.randomUUID().toString()
    /**
     * 热搜词的种子词（见 [hotSearches]）：都是题材向的泛词，联想接口对它们返回的候选里
     * 混着一批真正的查询词（`30集短剧` / `都市逆袭` / `甜宠` 这类）。
     */
    private val SUGGEST_SEEDS = listOf("短剧","都市","逆袭","甜宠","战神")
    /**
     * 热搜词的词类型优先级（见 [hotSearches]）。
     *
     * 题材 / 分类这类（`short_play_category` / `category`）读起来最像「大家在搜的题材」，排最前；
     * `common_query` 是平台自己的搜索建议排其次；`title` / `album` 更像是书名 / 专辑名，放最后。
     */
    private val HOT_WORD_ORDER = listOf("short_play_category","category","common_query","title","album")
    /**
     * 热搜词的并发池：种子词彼此独立，串行五个要 1.8s，并发之后是单次请求的时间。
     * 池子很小且只在取热搜词时用一下，线程是守护线程（不挡进程退出）。
     */
    private val SUGGEST_POOL = java.util.concurrent.Executors.newFixedThreadPool(SUGGEST_SEEDS.size) { runnable ->
        Thread(runnable,"hongguotv-hotword").apply { isDaemon=true }
    }
    /** 热搜词的兜底表：提示接口整体不可用时用（搜索页不能空着）。 */
    private val FALLBACK_HOT = listOf(
        "短剧最热榜前十名","AI短剧","都市逆袭","甜宠","战神归来","穿越剧大全",
        "萌宝助攻","玄幻修仙系列","悬疑","古装","逆袭人生","豪门"
    )
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    // —— 片库目录补页（移植自 pan8664716/HGTV 1.1.x，按类型独立改造）——
    /** 每个关键词已回给界面的目录条目 id；同一关键词翻页只回没见过的，滚到底后 hasMore 收掉。 */
    private val catalogSeen=linkedMapOf<String,MutableSet<String>>()
    private fun text(url: String, signed: Signer.Request? = null): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8)
        }
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    fun comicRanking(page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/hot-comic-drama?page=$page"),page)
    }
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        if(type==ContentType.COMIC) return comicRanking(page)
        val data=router("$site/category?tab=1&sort_type=1"+if(page>1) "&page=$page" else "")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("首页数据结构已变化")
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("首页数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100))
    }
    /**
     * 搜索提示词（联想词）：官网 PC 端搜索框用的就是这个接口。
     *
     * ```
     * GET https://hongguoduanju.com/incent_resource/suggestion?app_id=8662&web_id={UUID}&query={关键词}&count=10
     * ```
     *
     * **不需要签名、不需要登录态**，只要求 `app_id=8662`（缺了它服务端返回 500）；`web_id` 可省。
     * `count` 有效上限 20（超过按 20 算），不传按 10；空关键词返回 500，因此调用前先判空。
     *
     * 响应里每条的 `video_data` 与卡片是同一套字段，因此直接复用 [card] 解析。
     * `word_type` 实测见过 `short_play_name`（剧名，带完整 video_data）、`title`、`common_query`、
     * `album`、`short_play_category`、`short_play_kol_user`——后几种的 `video_data` 是空对象，
     * 只能当查询词用。
     */
    fun suggestions(keyword: String, count: Int = 10): List<SearchSuggestion> {
        val clean=keyword.trim()
        if(clean.isEmpty()) return emptyList()
        val wanted=count.coerceIn(1,20)
        val url="$site/incent_resource/suggestion?app_id=$SUGGEST_APP_ID&web_id=$webId&query=${Signer.encode(clean)}&count=$wanted"
        val rows=JSONObject(text(url)).optJSONArray("suggest_list")?.objects() ?: return emptyList()
        return rows.mapNotNull { row ->
            val name=row.str("name").ifEmpty { row.optJSONArray("display_words")?.optString(0).orEmpty() }.trim()
            if(name.isEmpty()) return@mapNotNull null
            // video_data 为空对象（title / common_query 这类）时不算剧集，只能当查询词。
            val video=row.optJSONObject("video_data")?.takeIf { it.length()>0 }
            val series=video?.let(::card)?.takeIf { it.id.matches(Regex("[0-9]{1,30}")) }
            SearchSuggestion(name,series,row.str("word_type"))
        }.distinctBy { it.name }.take(wanted)
    }
    /**
     * 热搜词：搜索页空输入时铺在输入框下面那一行。
     *
     * 平台**没有**热搜榜接口，但 [suggestions] 这个联想词接口本身就是「按热度排的候选」：
     * 拿几个题材词当种子各问一次，把返回里**不是某一部剧**的那些收集起来去重，
     * 就是一份现成的热词表。
     *
     * 只取种子词里靠前的若干条：联想词是按相关度排的，越靠前越像「大家在搜的」。
     * 分层 + 层内轮询：先按词类型分层（题材 / 分类 > 平台搜索建议 > 书名）；
     * 同一层里**按种子轮询**交替取——一个种子单独就能填满整张表，顺着一个种子取的话
     * 整排热搜词都是同一个题材的变体。全部种子词都失败时退回 [FALLBACK_HOT]：
     * 搜索页不能因为一个提示接口而空着。
     */
    fun hotSearches(limit: Int = 12): List<String> {
        val wanted=limit.coerceIn(1,30)
        // 种子词**并发**问（串行五个要 1.8s，并发后是单次请求的时间）。
        val pools=SUGGEST_SEEDS.map { seed -> java.util.concurrent.CompletableFuture.supplyAsync({ suggestionsPool(seed) }, SUGGEST_POOL) }
            .map { future -> runCatching { future.join() }.getOrDefault(emptyList()) }
        val seen=linkedSetOf<String>()
        HOT_WORD_ORDER.forEach { type ->
            if(seen.size>=wanted) return@forEach
            val lists=pools.map { pool -> pool.filter { it.second==type }.map { it.first } }
            var round=0
            while(seen.size<wanted && lists.any { it.size>round }) {
                lists.forEach { list -> list.getOrNull(round)?.let { if(seen.size<wanted) seen += it } }
                round++
            }
        }
        return if(seen.isEmpty()) FALLBACK_HOT.take(wanted) else seen.toList()
    }
    /** 一个种子词的候选：`(词, word_type)`，过滤掉剧名、过长过短的与重复的。 */
    private fun suggestionsPool(seed: String): List<Pair<String,String>> =
        runCatching { suggestions(seed,20) }.getOrDefault(emptyList())
            .filter { it.series==null && it.name.length in 2..12 }
            .map { it.name to it.wordType }
            .distinctBy { it.first }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        // 2026-10-24 实测：fqnovel 的搜索接口对匿名与已注册设备都返回**空响应体**，官网搜索页
        // 也改成了纯 SPA 壳（HTML 里没有任何数据）。活着的来源只有两个：
        // **suggestion 联想接口**（相关度排序、条目自带 series_id/封面，但**不带类型**）和
        // **片库目录 SSR**（类型天然隔离，但只能按片名筛）。
        //
        // 回退链（每一级都严格按 [type] 隔离，绝不拿另一类的内容来填）：
        //   1. App 搜索（按 tab_type）：第 1 页总给一次机会（平台恢复时能自动接回来）；
        //      空响应体判死之后（[appSearchAlive]）就不再空跑，两型一致。
        //   2. suggestion 分型搜索：拿漫画目录全量 id 集（[comicIdSet]，目录是类型隔离的
        //      权威边界）给联想条目分型——只回属于本型的剧。相关度最好、封面齐全。
        //   3. 官网搜索：只试一次（它现在是死路，多试只是白等）；若哪天恢复数据仍优先。
        //   4. 片库目录扫描：各型扫各自的目录，按片名筛；第 2 页起从 suggestion 结果之后接续。
        if(page==1 || appSearchAlive!=false) {
            try {
                val result=appSearch(keyword,page,type)
                if(result.items.isNotEmpty() || !result.hasMore) return result
            } catch (_: Exception) {
                // 游标过期 / 接口不可用都一样：静默落到下一级。每一级都按类型隔离，
                // 无论走到哪一级都不会拿另一类的内容来填，因此这里不需要区分类型。
            }
        }
        if(page==1) {
            // suggestion 分型搜索：1 个 RTT、相关度排序；分型依赖漫画 id 集（失败则跳过本
            // 级，绝不在无类型信息时放行——那样会回到「混排」老路）。
            comicIdSet()?.let { comicIds ->
                val entries=runCatching { suggestions(keyword,20) }.getOrDefault(emptyList())
                val items=entries.mapNotNull { it.series }.distinctBy { it.id }
                    .filter { (it.id in comicIds)==(type==ContentType.COMIC) }
                if(items.isNotEmpty()) {
                    synchronized(catalogSeen) {
                        if(catalogSeen.size>=20) catalogSeen.keys.firstOrNull()?.let(catalogSeen::remove)
                        catalogSeen[catalogKey(type,keyword)]=items.map { it.id }.toMutableSet()
                    }
                    System.err.println("HongguoTV: suggestionSearch type=${type.storedValue} keyword=$keyword items=${items.size}")
                    // hasMore=true：第 2 页起由目录扫描接续（catalogSeen 已去重）。
                    return CatalogPage(items,true)
                }
            }
            // 官网搜索快路径：一次机会，不成立刻转目录（一次失败 ≈ 1 个 RTT，不再重试）。
            try {
                val data=router("$site/search/${Signer.encode(keyword)}?page=1")
                val section=data.optJSONObject("search_(keyword)/page") ?: data.optJSONObject("search_page") ?: throw IOException("搜索数据暂不可用")
                val rows=section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
                val items=valid(rows.objects().map(::card))
                synchronized(catalogSeen) {
                    if(catalogSeen.size>=20) catalogSeen.keys.firstOrNull()?.let(catalogSeen::remove)
                    catalogSeen[catalogKey(type,keyword)]=items.map { it.id }.toMutableSet()
                }
                return CatalogPage(items,items.isNotEmpty())
            } catch (_: IOException) { /* 官网 SSR 已死：落到目录扫描 */ }
        }
        return catalogSearch(keyword,page,type)
    }


    /**
     * 画幅默认档：**用户口径（2026-10-27）——短剧类大多竖屏 9:16，漫剧横屏 16:9**。
     * 注意「旧梦落成灰」实测为横屏 16:9 且用户确认是漫剧——但它是漫剧不是短剧，
     * 与「短剧→9:16」不冲突。本机实测永远优先于默认档。
     */
    private val DEFAULT_ASPECT_COMIC = "16:9"
    private val DEFAULT_ASPECT_SHORT = "9:16"

    /** 类型 + 画幅 → 搜索卡片的标注文案。 */
    private fun badgeOf(kind: String,aspect: String): String {
        val label=when(kind) { "comic" -> "漫剧"; "ai" -> "AI 剧"; "short" -> "短剧"; else -> "" }
        val ratio=when(aspect) { "16:9" -> "横屏 16:9"; "9:16" -> "竖屏 9:16"; else -> "" }
        return listOf(label,ratio).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /**
     * 统一搜索（**不分漫剧/短剧**，结果里逐条标注类型与画幅）。
     *
     * 数据源与分型搜索同源：suggestion 联想接口（相关度排序、封面齐全）出第 1 页，
     * 两本片库目录按片名筛接续后页。类型标注来自 [comicIdSet]（漫画目录权威边界）；
     * 画幅标注优先用 [measuredAspects]（本机播放实测），没有就按类型默认档。
     */
    fun searchAll(keyword: String, page: Int = 1, measuredAspects: Map<String,String> = emptyMap(), measuredKinds: Map<String,String> = emptyMap()): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        val key="all:$keyword"
        if(page==1) {
            // 类型优先级：本机实测 > 漫画目录边界 > AI 剧目录边界 > 默认「短剧」（平台上真人短剧占多数）。
            val comicIds=comicIdSet()
            val aiIds=aiIdSet()
            val entries=runCatching { suggestions(keyword,20) }.getOrDefault(emptyList())
            val items=entries.mapNotNull { it.series }.distinctBy { it.id }
                .map { s ->
                    val kind=measuredKinds[s.id] ?: when {
                        comicIds?.contains(s.id)==true -> "comic"
                        aiIds?.contains(s.id)==true -> "ai"
                        else -> "short"
                    }
                    val aspect=measuredAspects[s.id] ?: when(kind) { "comic","ai" -> DEFAULT_ASPECT_COMIC; else -> DEFAULT_ASPECT_SHORT }
                    s.copy(kind=kind,aspect=aspect,badge=badgeOf(kind,aspect))
                }
            if(items.isNotEmpty()) {
                synchronized(catalogSeen) {
                    if(catalogSeen.size>=20) catalogSeen.keys.firstOrNull()?.let(catalogSeen::remove)
                    catalogSeen[key]=items.map { it.id }.toMutableSet()
                }
                System.err.println("HongguoTV: searchAll keyword=$keyword items=${items.size} comic=${items.count { it.kind=="comic" }} short=${items.count { it.kind=="short" }}")
                return CatalogPage(items,true)
            }
        }
        return catalogSearchAll(keyword,page,measuredAspects,measuredKinds)
    }

    /**
     * 统一搜索的目录接续：每个搜索页扫两本目录各 [SEARCH_SCAN_PAGES_ALL] 页，
     * 类型直接由来源目录决定（目录本身就是类型边界），画幅同 [searchAll] 的规则。
     */
    private fun catalogSearchAll(keyword: String,page: Int,measuredAspects: Map<String,String>,measuredKinds: Map<String,String> = emptyMap()): CatalogPage {
        val start=maxOf(1,1+(page-2)*SEARCH_SCAN_PAGES_ALL)
        val needle=keyword.filterNot(Char::isWhitespace)
        val items=mutableListOf<Series>()
        for(offset in 0 until SEARCH_SCAN_PAGES_ALL) {
            val index=start+offset
            if(index>MAX_CATALOG_PAGE) break
            // 三本目录各扫：类型直接由来源目录决定（目录本身就是类型边界）。
            for(scan in listOf("comic-drama" to "comic","real-drama" to "short",AI_CATALOG to "ai")) {
                val (catalog,kind)=scan
                try {
                    val data=router("$site/category/$catalog?page=$index")
                    val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("片库目录暂不可用")
                    val rows=section.optJSONArray("recommendList") ?: throw IOException("片库目录结构已变化")
                    items+=valid(rows.objects().map(::card))
                        .filter { it.title.filterNot(Char::isWhitespace).contains(needle,ignoreCase=true) }
                        .map { s -> val k=measuredKinds[s.id] ?: kind
                               val aspect=measuredAspects[s.id] ?: if(k=="short") DEFAULT_ASPECT_SHORT else DEFAULT_ASPECT_COMIC
                               s.copy(kind=k,aspect=aspect,badge=badgeOf(k,aspect)) }
                } catch (_: IOException) { /* 单页失败不影响整页 */ }
            }
        }
        val seen=synchronized(catalogSeen) { catalogSeen.getOrPut("all:$keyword") { mutableSetOf() } }
        val fresh=synchronized(catalogSeen) { items.distinctBy { it.id }.filter { seen.add(it.id) } }
        System.err.println("HongguoTV: catalogScanAll page=$page start=$start matched=${items.size} fresh=${fresh.size}")
        return CatalogPage(fresh,fresh.isNotEmpty() || page<=2)
    }


    /** 目录 id 集缓存：10 分钟有效期 + 30 秒失败负缓存（连续搜索不从头重扫）。 */
    private class CatalogIdCache {
        @Volatile var ids: Set<String>? = null
        @Volatile var at: Long = 0
        @Volatile var failedAt: Long = 0
    }
    private val idCaches=java.util.concurrent.ConcurrentHashMap<String,CatalogIdCache>()
    /** AI 剧目录（平台独立分类，/category/ai-drama，34 页）。 */
    private val AI_CATALOG="ai-drama"

    /**
     * 片库目录全量 id 集：给 suggestion 条目分型用的权威边界（漫剧 / AI 剧各一份）。
     *
     * 实现要点（两个都用血换来的）：
     * - **豁免导航取消**（[RequestScope.runUnscoped]）：遍历跑在搜索的 work 线程上，
     *   连续两次搜索就会 `scope.cancel()` 把遍历杀到一半，缓存永远建不起来。
     * - **并行抓取**（8 线程分片）：串行 34 页 ≈10s，搜索等不起；并行后 ≈2s。
     * 失败返回 null——调用方必须跳过该分级（宁可用目录慢路径，也不在无类型信息时乱标）。
     */
    private fun catalogIdSet(catalog: String): Set<String>? {
        val cache=idCaches.getOrPut(catalog){ CatalogIdCache() }
        cache.ids?.takeIf { clock()-cache.at<600_000 }?.let { return it }
        if(clock()-cache.failedAt<30_000) return null   // 刚失败过：短负缓存，别再拖累这次搜索
        val ids=java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        val failure=java.util.concurrent.atomic.AtomicReference<Exception?>()
        var firstPageTotal=1
        // 豁免导航取消（见 RequestScope.runUnscoped）：连续搜索不应该把遍历杀到一半。
        RequestScope.runUnscoped {
            try {
                // 第 1 页拿 totalPages，决定并行分片范围。
                val data=router("$site/category/$catalog?page=1")
                val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("片库目录暂不可用")
                val rows=section.optJSONArray("recommendList") ?: throw IOException("片库目录结构已变化")
                firstPageTotal=section.optJSONObject("pagination")?.optInt("totalPages",1) ?: 1
                valid(rows.objects().map(::card)).forEach { ids.add(it.id) }
            } catch (e: Exception) { failure.set(e) }
        }
        val last=minOf(firstPageTotal,40)
        if(failure.get()==null && last>1) {
            // 并行分片：8 线程轮页抓取，串行 34 页 ≈10s → 并行 ≈2s。
            val segments=8
            val threads=(0 until segments).map { seg ->
                Thread({
                    var p=1+seg+1
                    while(p<=last && failure.get()==null) {
                        try {
                            val data=router("$site/category/$catalog?page=$p")
                            val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("片库目录暂不可用")
                            val rows=section.optJSONArray("recommendList") ?: throw IOException("片库目录结构已变化")
                            valid(rows.objects().map(::card)).forEach { ids.add(it.id) }
                        } catch (e: Exception) { failure.compareAndSet(null,e); return@Thread }
                        p+=segments
                    }
                },"hongguotv-idwalk-$catalog-$seg").apply { isDaemon=true }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(30_000) }
            threads.forEach { if(it.isAlive) it.interrupt() }
        }
        if(failure.get()!=null) {
            System.err.println("HongguoTV: idSet[$catalog] walk failed: ${failure.get()?.message}")
            cache.failedAt=clock()
            return null
        }
        System.err.println("HongguoTV: idSet[$catalog] walked pages=$last ids=${ids.size}")
        cache.ids=ids.toSet(); cache.at=clock()
        return cache.ids
    }
    private fun comicIdSet(): Set<String>? = catalogIdSet(ContentType.COMIC.catalog)
    private fun aiIdSet(): Set<String>? = catalogIdSet(AI_CATALOG)

    /** 去重表的键：**必须带类型**——同一关键词漫剧与短剧各自扫各自的目录，互不吞并。 */
    private fun catalogKey(type: ContentType,keyword: String)="${type.storedValue}:$keyword"

    /**
     * 目录补页（短剧专用；漫剧的接口结果是权威结论，不做目录兜底）。
     *
     * 官网搜索的翻页参数服务端不生效，因此第 2 页起改为往后扫 [SEARCH_SCAN_PAGES] 页
     * 片库目录，把片名命中的接上；只回界面还没见过的条目，一个新条目都没有时只再给
     * 一次机会（第 2 页），之后把 hasMore 收掉——不然关键词在片库里只有两三条时，
     * 用户会一直往下滚却什么都没有。
     */
    private fun catalogSearch(keyword: String,page: Int,type: ContentType): CatalogPage {
        // page=1 官网快路径失败时也从目录扫起（夹到 1）；page≥2 继续往后接。
        val start=maxOf(1,1+(page-2)*SEARCH_SCAN_PAGES)
        val items=mutableListOf<Series>()
        var morePagesLeft=false
        var total=0
        for(offset in 0 until SEARCH_SCAN_PAGES) {
            val index=start+offset
            if(index>MAX_CATALOG_PAGE || (total>0 && index>total)) break
            val (matched,pages)=catalogPage(keyword,index,type)
            total=pages
            items+=matched
        }
        if(total>0 && start+SEARCH_SCAN_PAGES<=total) morePagesLeft=true
        val seen=synchronized(catalogSeen) { catalogSeen.getOrPut(catalogKey(type,keyword)) { mutableSetOf() } }
        val fresh=synchronized(catalogSeen) { items.distinctBy { it.id }.filter { seen.add(it.id) } }
        System.err.println("HongguoTV: catalogScan type=${type.storedValue} page=$page start=$start matched=${items.size} fresh=${fresh.size}")
        return CatalogPage(fresh,morePagesLeft && (fresh.isNotEmpty() || page<=2))
    }

    /** 片库目录第 [index] 页里片名含 [keyword] 的剧，外加这本目录一共多少页。 */
    private fun catalogPage(keyword: String,index: Int,type: ContentType): Pair<List<Series>,Int> {
        val data=router("$site/category/${type.catalog}?page=$index")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("片库目录暂不可用")
        val rows=section.optJSONArray("recommendList") ?: throw IOException("片库目录结构已变化")
        val total=section.optJSONObject("pagination")?.optInt("totalPages",1) ?: 1
        val needle=keyword.filterNot(Char::isWhitespace)
        val matched=valid(rows.objects().map(::card)).filter { it.title.filterNot(Char::isWhitespace).contains(needle,ignoreCase=true) }
        return matched to minOf(total,MAX_CATALOG_PAGE)
    }

    // One attempt per search, on purpose.
    //
    // Retrying here does not help either failure mode: a blank response body means this
    // device was never registered server-side (resending is answered identically), and a
    // network error means the API is unreachable. Both are cheaper to answer from the
    // website fallback in search() than to burn another RTT — the old 4-attempt loop with a
    // device rotation per attempt was what made a search take ten-odd seconds.
    // (Playback still rotates per attempt; that path has no website fallback.)
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage =
        appSearchAttempt(keyword, page, type)
    private fun appSearchAttempt(keyword: String, page: Int, type: ContentType): CatalogPage {
        val values=VendorConstants.device.toMutableMap()
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[k]="72232"
        values["version_name"]="7.2.2.32"
        // Ask for the tab that belongs to THIS content type. fqnovel splits search results
        // into per-type tabs (tab_type 11 = 短剧/真人剧, 19 = 漫剧); the comprehensive
        // "feed"/综合 tab mixes both and must never be requested for a typed search.
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to type.searchTab.toString(),"bookshelf_search_plan" to "4","use_correct" to "true",
            // 导入了账号登录态时按登录身份问（否则一律匿名设备）。
            "user_is_login" to if(accountCookie.isNotBlank()) "1" else "0"))
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        if(page>1 && cursor==null) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",values,
            extraHeaders=if(accountCookie.isNotBlank()) mapOf("Cookie" to accountCookie) else emptyMap())
        val body=text(signed.url,signed)
        // HTTP 200 + empty body means this device is not registered server-side. Answering
        // that as an IOException (instead of letting JSONObject choke on "") keeps the
        // "App 搜索不可用 → 官网搜索" fallback in search() reachable.
        // HTTP 200 + 空响应体 = 平台不给这类设备数据（重发结论一致，设备轮换也没用）：
        // 记下结论让后续页数直接走目录兜底，不再白白等一个 RTT。
        if(body.isBlank()) { appSearchAlive=false; System.err.println("HongguoTV: appSearch blank body tab=${type.searchTab} page=$page"); throw IOException("搜索接口暂无响应") }
        val data=JSONObject(body)
        if(data.optInt("code",-1)!=0) throw IOException("搜索接口暂不可用")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        // Take the tab for THIS content type, and only that one. Falling back to the first
        // tab when the typed one is missing is precisely how 短剧 results came to overwrite
        // 漫剧 search: the response also carries the 综合/feed and 短剧 tabs, and any of them
        // would satisfy a 漫剧 request if we merely grabbed tabs[0].
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        // 接口答出了带本型 tab 的完整响应：它是活的（下次照常先问它）。
        appSearchAlive=true
        System.err.println("HongguoTV: appSearch OK tab=${type.searchTab} page=$page keyword=$keyword")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val video=when(val raw=cell.opt("video_data")) {
                is JSONArray -> raw.objects().firstOrNull()
                is JSONObject -> raw
                else -> null
            } ?: JSONObject()
            val detail=cell.optJSONObject("video_detail") ?: JSONObject()
            val inner=video.optJSONObject("video_detail") ?: JSONObject()
            val id=cell.str("book_id").ifEmpty { cell.str("search_result_id").ifEmpty { video.str("series_id") } }
            val title=cell.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty()
                .ifEmpty { detail.str("series_title") }.ifEmpty { video.str("title") }.ifEmpty { inner.str("series_title") }
            val count=detail.optInt("episode_cnt").takeIf { it>0 } ?: video.optInt("episode_cnt").takeIf { it>0 } ?: inner.optInt("episode_cnt")
            val cover=video.str("cover").ifEmpty { inner.str("series_cover") }
            val intro=inner.str("series_intro").ifEmpty { detail.str("series_intro") }
            val badge=if(count>0) "全 $count 集" else video.str("rec_text")
            list+=Series(id,title,cleanUrl(cover),intro,badge,video.str("sub_title"))
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock())
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val o=router("$site/detail?series_id=$id").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        return Detail(card(o).copy(id=id),episodes)
    }
    // fqnovel grants each device_id exactly ONE successful playback ("first request grace"),
    // then flags it (Code 110001 / "播放接口暂不可用"). The real client rotates to a fresh
    // registered device identity per play. We mirror that: on failure, register a new
    // device_id/install_id/device_token, re-apply it (and refresh the Cookie), then retry.
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        var last: Exception? = null
        for (attempt in 1..5) {
            try { return tryStream(id, maxQuality) }
            catch (e: PlayerResourceException) {
                // 资源端点拒绝：换设备也不会变（URL 自带 token），原样上抛不轮换。
                throw e
            }
            catch (e: Exception) {
                // 注意必须 catch Exception：org.json.JSONException 继承的是 Exception 而
                // 非 IOException——播放接口返回空响应体时抛的就是它，只 catch IOException
                // 会直接穿透（轮换与重试全部失效，用户只看到一句"播放失败"）。
                last = e
                System.err.println("HongguoTV: stream($id) attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}; rotating device")
                runCatching { rotateDevice() }
                runCatching { Thread.sleep(700) }
            }
        }
        throw last ?: IOException("播放失败")
    }
    private fun tryStream(id: String, maxQuality: Int): StreamInfo {
        val payload="""{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}""".toByteArray()
        val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/",VendorConstants.device,payload)
        val data=JSONObject(text(signed.url,signed))
        val pbCode=data.optInt("Code",data.optInt("code",0))
        if(pbCode!=0) throw IOException("播放接口暂不可用")
        val map=data.optJSONObject("data") ?: throw IOException("播放信息为空")
        val entry=map.optJSONObject(id) ?: map.keys().asSequence().mapNotNull { map.optJSONObject(it) }.firstOrNull() ?: throw IOException("播放信息为空")
        val model=entry.optJSONObject("video_model") ?: JSONObject(entry.str("video_model"))
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        // 2026-10-27 实测：这一跳开始返回空响应体（与搜索接口收紧同款）。换设备也没用
        // （fallback URL 自带 token），轮换 5 次只会白烧注册配额——识别后直接给出可读的错。
        val fallbackBody=runCatching { text(fallback) }.getOrElse { throw PlayerResourceException("播放资源请求失败（${it.message}），请稍后重试") }
        if(fallbackBody.isBlank()) throw PlayerResourceException("播放资源暂未返回（平台可能临时收紧播放），请稍后重试，或在设置中导入账号参数")
        val info=JSONObject(fallbackBody).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        val videoList=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val rows=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }.filter {
            it.second.str("main_url").isNotEmpty() && it.second.str("codec_type").lowercase() !in setOf("bytevc2","bvc2")
        }.map { (k,o) -> Triple(qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } }),k,o) }.sortedByDescending { it.first }.toList()
        val selected=rows.firstOrNull { it.first<=maxQuality } ?: rows.lastOrNull() ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val item=selected.third; var url=cleanUrl(item.str("main_url")); val seed=info.str("key_seed")
        if(seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(seed))
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val key=item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>maxQuality) "（兼容资源）" else "") else "自动"
        // 视频元数据里的真实宽高：画幅标注（16:9 / 9:16）的本机实测来源。
        val w=item.optInt("vwidth",item.optInt("width",0))
        val h=item.optInt("vheight",item.optInt("height",0))
        // 类型字段探测：播放响应里找 book_type / series_type / video_type 一类字段
        //（目录边界对新剧有滞后，类型实测回写后搜索标注就能纠正）。
        val typeRaw=sequenceOf(entry,model,info).flatMap { o ->
            sequenceOf("book_type","series_type","video_type","drama_type","content_type","category_type","is_comic","content_kind").mapNotNull { k -> o.takeIf { it.has(k) && !it.isNull(k) }?.opt(k)?.toString() }
        }.firstOrNull().orEmpty()
        if(typeRaw.isNotEmpty()) System.err.println("HongguoTV: stream typeRaw=$typeRaw vid=$id")
        else System.err.println("HongguoTV: stream keys entry=${entry.keys().asSequence().toList()} model=${model.keys().asSequence().toList()}")
        System.err.println("HongguoTV: stream vid=$id ${w}x$h quality=$quality")
        return StreamInfo(url,key,quality,w,h,typeRaw)
    }
    private fun rotateDevice() {
        try {
            val reg = DeviceRegistrar.register(http)
            val rid = reg.installId; val rdid = reg.deviceId
            if (rid != null && rdid != null) {
                applyDeviceIdentity(rid, rdid)
                DeviceSession.set(rid, rdid, reg.deviceToken)
                System.err.println("HongguoTV: rotateDevice: new iid=$rid device_id=$rdid tokenLen=${reg.deviceToken?.length ?: 0}")
            } else {
                System.err.println("HongguoTV: rotateDevice: register returned no ids (${reg.raw.take(120)}); keep current identity")
            }
        } catch (e: Exception) { System.err.println("HongguoTV: rotateDevice failed ${e.javaClass.simpleName}: ${e.message}") }
    }
    /**
     * 整集弹幕。
     *
     * 接口是播放器自己的评论接口：`POST /novel/commentapi/comment/list/{vid}/v1/`，
     * 请求体带 `comment_source=601`（短剧）、`server_channel=1000`、`group_type=30`、`comment_type=20`，
     * `business_param` 里再给剧集 ID 与本集时长；响应按 30 秒一个窗口返回，
     * `extra.next_query_danmaku_list_time` 是下一个窗口的起点，`common_list_info.has_more` 表示还没到片尾。
     *
     * **匿名可用**：签名只用设备参数，不需要账号登录态。
     *
     * [durationSeconds] 是这一集的时长（秒），用来判断何时停：为 0 时按窗口数量上限兜底。
     * [onWindow] 每拉到一个窗口就被回调一次（传当前已收集的全部弹幕，按时间排好），
     * 让播放界面边拉边画：整集拉完要十几秒，等全量再上屏等于前十几秒一条弹幕都没有。
     * 回调跑在调用线程上，调用方自己负责切回主线程。
     * 网络失败或平台不再返回弹幕时返回空列表，播放界面不显示任何弹幕，也不弹错误。
     */
    fun danmaku(vid: String, seriesId: String, durationSeconds: Long = 0, shouldStop: () -> Boolean = { false }, onWindow: (List<Danmaku>) -> Unit = {}): List<Danmaku> {
        require(vid.matches(Regex("[0-9]{1,30}")) && seriesId.matches(Regex("[0-9]{1,30}")))
        val durationMs = (durationSeconds.coerceIn(0, 4 * 60 * 60)) * 1000
        val collected = linkedMapOf<String, Danmaku>()
        var startMs = 0L
        var cursor = ""
        // 知道时长就按时长算窗口；不知道时先按上限走——平台的 has_more 会在片尾自己翻成 false，
        // 所以「先多要几个窗口」不会把整部剧拉下来，只是让长剧也能取全。
        val planned = if (durationMs > 0) (durationMs + COMMENT_WINDOW_MS - 1) / COMMENT_WINDOW_MS + 4L else MAX_DANMAKU_WINDOWS.toLong()
        val windows = planned.coerceIn(1L, MAX_DANMAKU_WINDOWS.toLong()).toInt()
        for (index in 0 until windows) {
            if (shouldStop()) break
            val page = runCatching { commentWindow(vid, seriesId, durationMs, startMs, cursor) }.getOrNull() ?: break
            var added = false
            page.items.forEach { if (collected.putIfAbsent("${it.offsetMs}:${it.text}", it) == null) added = true }
            if (added) onWindow(collected.values.sortedBy { it.offsetMs })
            if (collected.size >= MAX_DANMAKU) break
            if (page.nextStart <= startMs) break
            if (durationMs > 0 && page.nextStart >= durationMs) break
            if (durationMs <= 0 && !page.hasMore) break
            startMs = page.nextStart; cursor = page.cursor
            // 弹幕接口对匿名设备有频控：连发会返回风控错误，窗口之间留一点间隔。
            if (index < windows - 1) runCatching { Thread.sleep(COMMENT_THROTTLE_MS) }
        }
        return collected.values.sortedBy { it.offsetMs }
    }

    /** 一个 30 秒窗口的弹幕；所有线路都失败时抛 IOException，由 [danmaku] 决定是继续还是停下。 */
    private fun commentWindow(vid: String, seriesId: String, durationMs: Long, startMs: Long, cursor: String): DanmakuPage {
        val body = JSONObject()
            .put("comment_source", COMMENT_SOURCE).put("server_channel", SERVER_CHANNEL)
            .put("group_id", vid).put("group_type", 30).put("comment_type", 20).put("sort", 1)
            .put("business_param", JSONObject().put("book_id", seriesId).put("start_offset_time", startMs)
                .put("playlet_item_duration", durationMs)
                .put("need_danmaku_guide_type", JSONArray(listOf(1, 3, 4, 2))))
            .put("count", COMMENT_COUNT).put("cursor", cursor).put("aid", 8662).put("compliance_status", 0)
            .toString().toByteArray(Charsets.UTF_8)
        var failure: IOException? = null
        for (host in commentHosts) {
            try {
                val signed = signer.sign("https://$host/novel/commentapi/comment/list/$vid/v1/", VendorConstants.device, body)
                val headers = signed.headers.toMutableMap()
                // 这三个头是弹幕接口专用的，签名与普通接口共用同一套 App 参数。
                headers["comment-source"] = COMMENT_SOURCE.toString()
                headers["server-channel"] = SERVER_CHANNEL.toString()
                headers["x-ss-stub"] = ""
                val raw = text(signed.url, Signer.Request(signed.url, headers, body))
                if (raw.isBlank()) throw IOException("弹幕接口暂无响应")
                val data = JSONObject(raw)
                if (data.optInt("code", -1) != 0) throw IOException("弹幕接口暂不可用（${data.optInt("code")}）")
                val payload = data.optJSONObject("data") ?: throw IOException("弹幕数据为空")
                val rows = payload.optJSONArray("data_list")
                val items = parseDanmakuRows(rows)
                val info = payload.optJSONObject("common_list_info")
                val next = payload.optJSONObject("extra")?.optLong("next_query_danmaku_list_time", -1L)?.takeIf { it > startMs }
                    ?: (startMs + COMMENT_WINDOW_MS)
                return DanmakuPage(items, next, info?.str("cursor").orEmpty(), info?.optBoolean("has_more", false) ?: false)
            } catch (e: Exception) {
                failure = e as? IOException ?: IOException(e.message ?: "弹幕请求失败")
            }
        }
        throw failure ?: IOException("弹幕接口暂不可用")
    }

    /** 弹幕接口的域名：评论服务在多个 normal 域名后面，按次序试。 */
    private val commentHosts = listOf(
        "api5-normal-sinfonlinea.fqnovel.com",
        "api5-normal-sinfonlineb.fqnovel.com"
    )

    private data class DanmakuPage(val items: List<Danmaku>, val nextStart: Long, val cursor: String, val hasMore: Boolean)

    companion object {
        /** 翻一次页往后扫几页目录（24 部/页）：扫得太少页与页之间接不上，太多则一次等太久。 */
        private const val SEARCH_SCAN_PAGES=3
        /** 统一搜索每页扫两本目录各几页（每页 4 个请求）。 */
        private const val SEARCH_SCAN_PAGES_ALL=2
        /** 目录最深翻到第几页，防恶意翻页。 */
        private const val MAX_CATALOG_PAGE=100
        /** 弹幕窗口固定 30 秒一个，与平台的 `next_query_danmaku_list_time` 一致。 */
        private const val COMMENT_WINDOW_MS = 30_000L
        private const val COMMENT_SOURCE = 601
        private const val SERVER_CHANNEL = 1000
        private const val COMMENT_COUNT = 90
        /** 一集最多拉多少个窗口（按 30 秒一窗，40 窗 = 20 分钟）。 */
        private const val MAX_DANMAKU_WINDOWS = 40
        /** 一集最多保留多少条弹幕：低端设备的绘制预算，超出直接截断。 */
        private const val MAX_DANMAKU = 3000
        /** 窗口之间的最小间隔，避开平台的弹幕频控。 */
        private const val COMMENT_THROTTLE_MS = 180L
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}
