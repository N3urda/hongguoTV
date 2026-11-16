// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import androidx.compose.runtime.*
import com.hongguotv.core.*
import kotlinx.coroutines.*

enum class Section(val label: String) { DISCOVER("发现短剧"), RANKING("排行榜"), FAVORITES("我的收藏"), HISTORY("最近观看") }

interface CatalogSource {
    suspend fun list(section: Section, query: String, page: Int): CatalogPage
    suspend fun detail(series: Series): Detail
}

class LiveCatalogSource : CatalogSource {
    val repository = ContentRepository(DesktopNetwork.client())
    override suspend fun list(section: Section, query: String, page: Int) = runInterruptible(Dispatchers.IO) {
        when {
            query.isNotBlank() -> repository.searchAll(query, page)
            section == Section.RANKING -> repository.rankPage(RankBoard.ALL, page)
            else -> repository.discover(DramaCategory.REAL, page = page)
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
class DesktopState(private val scope: CoroutineScope, private val live: CatalogSource, private val demoSource: CatalogSource = DemoCatalogSource, private val library: DesktopLibrary? = null, val playback: DesktopPlayback? = null, initialDemo: Boolean = false) {
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
    fun favorite(series: Series) = favorites.containsKey(key(series))
    fun toggleFavorite(series: Series) {
        val key = key(series)
        favorites = if (favorite(series)) favorites - key else favorites + (key to series)
        if (!demo) library?.toggle(series)
        if (section == Section.FAVORITES && selected == null) reload()
    }
    fun navigate(next: Section) { section = next; query = ""; items = emptyList(); selected = null; detail = null; episode = null; reload() }
    fun search(value: String) { query = value.trim().take(80); items = emptyList(); selected = null; detail = null; episode = null; reload() }
    fun mode(value: Boolean) { playback?.stop(); demo = value; navigate(Section.DISCOVER) }
    fun back() { selected = null; detail = null; episode = null; reload() }
    fun selectEpisode(index: Int) {
        val current = detail ?: return
        if (index !in current.episodes.indices) return
        episode = index
        if (!demo) playback?.play(current, index)
    }
    fun resume() { if (!demo) detail?.let { playback?.resume(it) } }
    fun storageWarning() = library?.warning
    fun reload(nextPage: Int = 1) {
        val source = if (demo) demoSource else live
        val currentSection = section
        val currentQuery = query
        launch {
            val result = if (currentSection == Section.FAVORITES) CatalogPage(favorites.filterKeys { it.startsWith("$demo:") }.values.filter { currentQuery.isBlank() || it.title.contains(currentQuery) }, false)
                else if (currentSection == Section.HISTORY) CatalogPage(if (demo) emptyList() else library?.history?.values?.toList()?.asReversed()?.map { it.series }?.filter { currentQuery.isBlank() || it.title.contains(currentQuery) }.orEmpty(), false)
                else source.list(currentSection, currentQuery, nextPage)
            val apply: () -> Unit = { items = result.items; page = nextPage; hasMore = result.hasMore }
            apply
        }
    }
    fun open(series: Series) {
        selected = series; detail = null; episode = null
        val source = if (demo) demoSource else live
        launch {
            val result = source.detail(series)
            val apply: () -> Unit = { detail = result }
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
