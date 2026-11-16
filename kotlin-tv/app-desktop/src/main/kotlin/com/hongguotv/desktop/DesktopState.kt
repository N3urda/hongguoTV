// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import androidx.compose.runtime.*
import com.hongguotv.core.*
import kotlinx.coroutines.*

enum class Section(val label: String) { DISCOVER("发现短剧"), RANKING("排行榜"), FAVORITES("我的收藏"), HISTORY("最近观看"), RESERVATIONS("我的预约"), RECOMMENDED("为你推荐") }

interface CatalogSource {
    suspend fun list(section: Section, query: String, page: Int): CatalogPage
    suspend fun detail(series: Series): Detail
}

class LiveCatalogSource : CatalogSource {
    var category = DramaCategory.REAL
    var topic = ""
    var board = RankBoard.ALL
    val repository = ContentRepository(DesktopNetwork.client())
    override suspend fun list(section: Section, query: String, page: Int) = runInterruptible(Dispatchers.IO) {
        when {
            query.isNotBlank() -> repository.searchAll(query, page)
            section == Section.RANKING -> repository.rankPage(board, page)
            else -> repository.discover(category, topic, page)
        }
    }
    override suspend fun detail(series: Series) = runInterruptible(Dispatchers.IO) { repository.detail(series.id) }
}

object DemoCatalogSource : CatalogSource {
    private val titles = listOf("风起时，与你重逢", "长街灯火", "她的第二人生", "春日来信", "山海有归期", "恰好是你", "逆风而行", "小城故事未完", "月落长安", "今天也想见你", "重返二十岁", "人间烟火正好")
    val rows = titles.mapIndexed { i, title -> Series("demo-$i", title, description = "一次意外的重逢，让原本平行的生活有了交集。在新的选择面前，他们重新认识彼此，也找回了属于自己的答案。", badge = "共 ${36 + i * 4} 集", tags = listOf("都市 · 重逢", "年代 · 成长", "都市 · 逆袭", "爱情 · 治愈")[i % 4]) }
    override suspend fun list(section: Section, query: String, page: Int): CatalogPage {
        delay(180)
        val items = if (section == Section.RANKING) rows.reversed() else rows
        return CatalogPage(items.filter { query.isBlank() || it.title.contains(query) || it.tags.contains(query) }, false)
    }
    override suspend fun detail(series: Series): Detail {
        delay(100)
        return Detail(series, List(EpisodeSummary.total(series.badge)) { "${series.id}-episode-$it" })
    }
}

/** All observable state is confined to the UI dispatcher; stale network results are discarded. */
class DesktopState(private val scope: CoroutineScope, private val live: CatalogSource, private val demoSource: CatalogSource = DemoCatalogSource, private val library: DesktopLibrary? = null, val playback: DesktopPlayback? = null, initialDemo: Boolean = false, val account: DesktopAccount? = null) {
    var category by mutableStateOf(DramaCategory.REAL); private set
    var topics by mutableStateOf<List<DramaTopic>>(emptyList()); private set
    var topic by mutableStateOf(""); private set
    fun chooseTopic(value: String) { topic = value; (live as? LiveCatalogSource)?.topic = value; reload() }
    var suggestions by mutableStateOf<List<String>>(emptyList()); private set
    private var suggestionJob: Job? = null
    fun suggest(value: String) {
        suggestionJob?.cancel(); suggestions = emptyList()
        if (demo || value.isBlank()) return
        val source = live as? LiveCatalogSource ?: return
        suggestionJob = scope.launch {
            delay(300)
            try { suggestions = runInterruptible(Dispatchers.IO) { source.repository.searchSuggestions(value.take(80)) }.take(5) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { suggestions = emptyList() }
        }
    }
    var board by mutableStateOf(RankBoard.ALL); private set
    var related by mutableStateOf<List<RelatedWork>>(emptyList()); private set
    var producer by mutableStateOf<Producer?>(null); private set
    var comments by mutableStateOf<List<Pair<String, String>>>(emptyList()); private set
    var commentStatus by mutableStateOf(""); private set
    private var commentCursor = ""
    private var commentJob: Job? = null
    var commentsMore by mutableStateOf(false); private set
    fun chooseCategory(value: DramaCategory) { category = value; topic = ""; topics = emptyList(); (live as? LiveCatalogSource)?.let { it.category = value; it.topic = "" }; reload() }
    fun chooseBoard(value: RankBoard) { board = value; (live as? LiveCatalogSource)?.board = value; reload() }
    fun producerWorks() {
        val owner = producer ?: return
        val source = live as? LiveCatalogSource ?: return
        launch {
            val result = runInterruptible(Dispatchers.IO) { source.repository.producerWorks(owner) }
            val apply: () -> Unit = { selected = null; detail = null; items = result.items; page = 1; hasMore = false }
            apply
        }
    }
    fun readComments(more: Boolean = false) {
        val current = detail ?: return
        if (demo || current.episodes.isEmpty()) return
        val part = episode ?: 0
        val ticket = revision
        commentJob?.cancel()
        commentJob = scope.launch {
            commentStatus = "正在读取第 ${part + 1} 集评论…"
            try {
                val result = runInterruptible(Dispatchers.IO) { AppCommentPages().read(current.series.id, current.episodes[part], if (more) commentCursor else "") }
                if (ticket != revision || detail != current || (episode ?: 0) != part) return@launch
                val rows = result.getJSONArray("items")
                val next = (0 until rows.length()).map { rows.getJSONObject(it).let { row -> row.optString("author") to row.optString("content") } }
                comments = if (more) comments + next else next
                commentCursor = result.optString("nextCursor"); commentsMore = result.optBoolean("hasMore")
                commentStatus = "第 ${part + 1} 集评论 · ${result.optLong("total")} 条"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (ticket == revision) commentStatus = "评论暂不可用，请重试" }
        }
    }
    var demo by mutableStateOf(initialDemo); private set
    var section by mutableStateOf(Section.DISCOVER); private set
    var query by mutableStateOf(""); private set
    var items by mutableStateOf<List<Series>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var selected by mutableStateOf<Series?>(null); private set
    var detail by mutableStateOf<Detail?>(null); private set
    var episode by mutableStateOf<Int?>(null); private set
    var page by mutableStateOf(1); private set
    var hasMore by mutableStateOf(false); private set
    private var favorites by mutableStateOf<Map<String, Series>>(library?.favorites?.values?.associateBy { "false:${it.id}" } ?: emptyMap())
    private var job: Job? = null
    private var revision = 0
    private fun key(series: Series) = "${demo}:${series.id}"
    fun favorite(series: Series) = if (!demo && account?.profile != null) account.favorites.any { it.series.id == series.id } else favorites.containsKey(key(series))
    fun toggleFavorite(series: Series) {
        if (!demo && account?.profile != null) { account.toggle(series); return }
        val key = key(series)
        favorites = if (favorite(series)) favorites - key else favorites + (key to series)
        if (!demo) library?.toggle(series)
        if (section == Section.FAVORITES && selected == null) reload()
    }
    fun navigate(next: Section) { section = next; query = ""; items = emptyList(); selected = null; detail = null; episode = null; reload() }
    fun search(value: String) { suggestionJob?.cancel(); suggestions = emptyList(); query = value.trim().take(80); items = emptyList(); selected = null; detail = null; episode = null; reload() }
    fun mode(value: Boolean) { playback?.stop(); demo = value; navigate(Section.DISCOVER) }
    fun back() { selected = null; detail = null; episode = null; reload() }
    fun selectEpisode(index: Int) {
        val current = detail ?: return
        if (index !in current.episodes.indices) return
        episode = index
        if (!demo) playback?.play(current, index)
    }
    fun resume() { if (!demo) detail?.let { current ->
        val cloud = account?.history?.firstOrNull { it.series.id == current.series.id }
        val local = library?.history?.get(current.series.id)
        if (cloud != null && cloud.updatedAt > (local?.updatedAt ?: 0)) {
            val target = ResumePlayback.target(current.episodes, cloud.episodeId, cloud.position, false)
            playback?.play(current, target.index, target.position / 1000.0)
        } else playback?.resume(current)
    } }
    fun storageWarning() = library?.warning
    fun reload(nextPage: Int = 1) {
        val source = if (demo) demoSource else live
        val currentSection = section
        val currentQuery = query
        launch {
            val result = if (!demo && currentSection == Section.RECOMMENDED && account != null) account.recommendations(nextPage)
                else if (!demo && account?.profile != null && currentSection == Section.FAVORITES) CatalogPage(account.favorites.map { it.series }, false)
                else if (!demo && currentSection == Section.RESERVATIONS) CatalogPage(account?.reservations.orEmpty().map { it.series }, false)
                else if (!demo && account?.profile != null && currentSection == Section.HISTORY) CatalogPage((account.history.map { it.series to it.updatedAt } + library?.history?.values.orEmpty().map { it.series to it.updatedAt }).sortedByDescending { it.second }.distinctBy { it.first.id }.map { it.first }, false)
                else if (currentSection == Section.FAVORITES) CatalogPage(favorites.filterKeys { it.startsWith("$demo:") }.values.filter { currentQuery.isBlank() || it.title.contains(currentQuery) }, false)
                else if (currentSection == Section.HISTORY) CatalogPage(if (demo) emptyList() else library?.history?.values?.toList()?.asReversed()?.map { it.series }?.filter { currentQuery.isBlank() || it.title.contains(currentQuery) }.orEmpty(), false)
                else source.list(currentSection, currentQuery, nextPage)
            val apply: () -> Unit = { items = result.items; page = nextPage; hasMore = result.hasMore; topics = result.discovery?.topics.orEmpty() }
            apply
        }
    }
    fun open(series: Series) {
        selected = series; detail = null; episode = null; related = emptyList(); producer = null; comments = emptyList(); commentStatus = ""; commentsMore = false; commentJob?.cancel()
        val source = if (demo) demoSource else live
        launch {
            val result = source.detail(series)
            val sourceLive = if (demo) null else live as? LiveCatalogSource
            val ticket = revision
            val apply: () -> Unit = {
                detail = result
                if (sourceLive != null) scope.launch {
                    val works = runInterruptible(Dispatchers.IO) { runCatching { sourceLive.repository.related(series.id) }.getOrDefault(emptyList()) }
                    if (ticket != revision) return@launch
                    related = works
                    val owner = runInterruptible(Dispatchers.IO) { runCatching { sourceLive.repository.producer(series.id) }.getOrNull() }
                    if (ticket == revision) producer = owner
                }
            }
            apply
        }
    }
    fun retry() { selected?.let(::open) ?: reload(page) }
    private fun launch(block: suspend () -> (() -> Unit)) {
        job?.cancel()
        val ticket = ++revision
        loading = true; error = null
        job = scope.launch {
            try {
                val apply = block()
                if (ticket == revision) apply()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (ticket == revision) error = "内容暂时无法加载，请检查网络后重试。" }
            finally { if (ticket == revision) loading = false }
        }
    }
}
