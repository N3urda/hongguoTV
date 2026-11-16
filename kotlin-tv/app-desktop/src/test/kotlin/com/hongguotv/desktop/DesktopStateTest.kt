package com.hongguotv.desktop

import com.hongguotv.core.*
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopStateTest {
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
