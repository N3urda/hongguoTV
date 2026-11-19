package com.hongguotv.desktop

import com.hongguotv.core.*
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopStateTest {
    @Test fun recentSeriesReflectsSavedProgressAndIsolatesDemo() = runBlocking {
        val library = DesktopLibrary(java.nio.file.Files.createTempDirectory("desktop-history").resolve("library.json"))
        val state = DesktopState(this, DemoCatalogSource, library = library)
        val first = Series("one", "第一部")
        val second = Series("two", "第二部")
        fun record(series: Series, time: Long) =
            library.remember(BackupProgress(series, "episode", 0, 1000, 60000, false, time))

        assertTrue(state.recentSeries.isEmpty())
        record(first, 1)
        record(second, 2)
        assertEquals(listOf(second, first), state.recentSeries)
        record(first, 3)
        assertEquals(listOf(first, second), state.recentSeries)
        state.mode(true)
        assertTrue(state.recentSeries.isEmpty())
    }

    @Test fun producerPagesKeepTheirSourceAfterOpeningDetails() = runBlocking {
        val owner = Producer("123", "制作方")
        val source = object : CatalogSource {
            override suspend fun list(section: Section, query: String, page: Int): CatalogPage = error("Must not load discovery")
            override suspend fun producerWorks(producer: Producer, page: Int): CatalogPage { assertEquals(owner, producer); return CatalogPage(listOf(Series("$page", "作品$page")), page < 3) }
            override suspend fun detail(series: Series) = Detail(series, listOf("episode"))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = DesktopState(scope, source)
            state.browseProducer(owner); assertEquals("1", state.items.single().id)
            state.reload(2); state.open(state.items.single()); state.back()
            assertEquals(owner, state.browsingProducer); assertEquals(2, state.page)
            state.reload(3); assertEquals("3", state.items.single().id); assertFalse(state.hasMore)
        } finally { scope.cancel() }
    }

    @Test fun detailBackKeepsPageAndDoesNotReloadFeed() = runBlocking {
        var calls = 0
        val source = object : CatalogSource {
            override suspend fun list(section: Section, query: String, page: Int): CatalogPage { calls++; return CatalogPage(listOf(Series("$page", "第 $page 页")), true) }
            override suspend fun detail(series: Series) = Detail(series, listOf("episode"))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = DesktopState(scope, source)
            state.reload(3); val rows = state.items
            state.open(rows.single()); state.back()
            assertEquals(3, state.page); assertEquals(rows, state.items); assertEquals(1, calls)
            assertNull(state.selected); assertFalse(state.loading)
        } finally { scope.cancel() }
    }
    @Test fun searchFromLibraryUsesGlobalSearch() = runBlocking {
        var searched = ""
        val source = object : CatalogSource {
            override suspend fun list(section: Section, query: String, page: Int): CatalogPage { searched = query; return CatalogPage(listOf(Series("match", query)), false) }
            override suspend fun detail(series: Series) = Detail(series, emptyList())
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = DesktopState(scope, source)
            state.navigate(Section.FAVORITES); assertTrue(state.items.isEmpty())
            state.search("新剧"); assertEquals("新剧", searched); assertEquals("match", state.items.single().id)
        } finally { scope.cancel() }
    }
    @Test fun backDiscardsLateDetailResponse() = runBlocking {
        val response = CompletableDeferred<Detail>()
        val series = Series("1", "剧集")
        val source = object : CatalogSource {
            override suspend fun list(section: Section, query: String, page: Int) = CatalogPage(listOf(series), false)
            override suspend fun detail(series: Series) = withContext(NonCancellable) { response.await() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = DesktopState(scope, source)
            state.reload(); state.open(series); state.back(); response.complete(Detail(series, listOf("episode")))
            assertNull(state.detail); assertNull(state.selected); assertFalse(state.loading)
        } finally { scope.cancel() }
    }

    @Test fun lateResponseCannotOverwriteNewSearch() = runBlocking {
        val first = CompletableDeferred<CatalogPage>()
        val second = CompletableDeferred<CatalogPage>()
        val source = object : CatalogSource {
            override suspend fun list(section: Section, query: String, page: Int) = withContext(NonCancellable) { if (query == "old") first.await() else second.await() }
            override suspend fun detail(series: Series) = Detail(series, emptyList())
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = DesktopState(scope, source, source, initialDemo = true)
            state.search("old")
            state.search("new")
            second.complete(CatalogPage(listOf(Series("new", "新的结果")), false))
            first.complete(CatalogPage(listOf(Series("old", "过期结果")), false))
            assertEquals("new", state.items.single().id)
            assertFalse(state.loading)
        } finally { scope.cancel() }
    }

    @Test fun favoritesAreSeparatedByModeAndEpisodeSelectionIsBounded() = runBlocking {
        val series = Series("1", "剧集")
        val source = object : CatalogSource {
            override suspend fun list(section: Section, query: String, page: Int) = CatalogPage(listOf(series), false)
            override suspend fun detail(series: Series) = Detail(series, listOf("a", "b"))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = DesktopState(scope, source, source, initialDemo = true)
            state.toggleFavorite(series)
            state.navigate(Section.FAVORITES)
            assertEquals(listOf(series), state.items)
            state.open(series)
            state.selectEpisode(5)
            assertNull(state.episode)
            state.selectEpisode(1)
            assertEquals(1, state.episode)
            state.mode(false)
            assertFalse(state.favorite(series))
            state.navigate(Section.FAVORITES)
            assertTrue(state.items.isEmpty())
            state.mode(true)
            assertTrue(state.favorite(series))
        } finally { scope.cancel() }
    }

    @Test fun failedOnlineRequestNeverFallsBackToDemoContent() = runBlocking {
        val source = object : CatalogSource {
            override suspend fun list(section: Section, query: String, page: Int): CatalogPage = error("offline")
            override suspend fun detail(series: Series): Detail = error("offline")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = DesktopState(scope, source)
            state.mode(false)
            assertNotNull(state.error)
            assertFalse(state.loading)
            assertTrue(state.items.isEmpty())
        } finally { scope.cancel() }
    }
}
