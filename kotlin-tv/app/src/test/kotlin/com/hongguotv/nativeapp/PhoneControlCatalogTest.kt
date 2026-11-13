package com.hongguotv.nativeapp

import android.app.Activity
import com.hongguotv.core.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 26])
@LooperMode(LooperMode.Mode.PAUSED)
class PhoneControlCatalogTest {
    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private fun get(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun fixture(test: (PhoneControlCatalog, Library, AccountController, MutableList<Pair<Series, CloudProgress?>>) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val library = Library(activity.get())
        val account = AccountController(activity.get()) {}
        try {
            (get(account, "worker") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            set(library, "index", LibraryIndex(BackupData(emptyList(), emptyList(), emptySet(), emptyList(), BackupSettings())))
            val played = mutableListOf<Pair<Series, CloudProgress?>>()
            test(PhoneControlCatalog(library, account) { series, progress -> played += series to progress }, library, account, played)
        } finally { account.destroy(); library.close(); activity.pause().stop().destroy() }
    }
    private fun list(kind: String, page: Int = 1) = JSONObject().put("kind", kind).put("page", page)
    private fun action(name: String, id: String, scope: Int) = JSONObject().put("action", name).put("id", id).put("scope", scope)

    @Test fun mergedListsDeduplicateBeforePaginationAndKeepGuestRecords() = fixture { catalog, library, account, _ ->
        val local = (1..45).map { Series("$it", "本机 $it", cover = "https://example.com/large.jpg") }
        set(library, "index", LibraryIndex(BackupData(local, emptyList(), emptySet(), emptyList(), BackupSettings())))
        val first = catalog.list(list("favorites"))
        assertEquals(20, first.getJSONArray("items").length())
        assertTrue(first.getBoolean("hasMore"))
        assertEquals("https://example.com/large.jpg", first.getJSONArray("items").getJSONObject(0).getString("cover"))
        set(account, "credentials", AccountCredentials("private-cookie"))
        set(account, "favorites", FavoriteState(listOf(CloudFavorite(local[0], 1), CloudFavorite(Series("99", "云端"), 0)), loaded = true))
        val last = catalog.list(list("favorites", 3))
        assertEquals(6, last.getJSONArray("items").length())
        assertEquals("99", last.getJSONArray("items").getJSONObject(5).getString("id"))
        assertFalse(last.getBoolean("hasMore"))
        assertFalse(last.toString().contains("private-cookie"))
        set(account, "credentials", null)
        assertEquals(5, catalog.list(list("favorites", 3)).getJSONArray("items").length())
    }

    @Test fun mergedHistoryUsesNewestProgressAndFillsMissingCover() = fixture { catalog, library, account, played ->
        val local = Series("1", "同一部剧")
        val remote = local.copy(cover = "https://example.com/poster.jpg")
        set(library, "index", LibraryIndex(BackupData(emptyList(),
            listOf(BackupProgress(local, "ep-1", 0, 1000, 90000, false, 2000)), emptySet(), emptyList(), BackupSettings())))
        set(account, "credentials", AccountCredentials("test-only"))
        val older = CloudProgress(remote, "ep-2", 1, 5000, 90000, 1000)
        val other = CloudProgress(Series("2", "最近观看"), "ep-3", 2, 6000, 90000, 3000)
        set(account, "history", listOf(older, other))
        val rows = catalog.list(list("history")).getJSONArray("items")
        assertEquals(2, rows.length())
        assertEquals("2", rows.getJSONObject(0).getString("id"))
        assertEquals(remote.cover, rows.getJSONObject(1).getString("cover"))
        catalog.act(action("play", "1", account.historyScope).put("source", "history"))
        assertNull(played.last().second)
        val newer = older.copy(updatedAt = 4000)
        set(account, "history", listOf(newer, other))
        assertEquals("1", catalog.list(list("history")).getJSONArray("items").getJSONObject(0).getString("id"))
        catalog.act(action("play", "1", account.historyScope).put("source", "history"))
        assertEquals(newer, played.last().second)
    }

    @Test fun coverRelayPreservesTelevisionUrlAndRejectsExpiredCardsAndScope() = fixture { catalog, _, _, _ ->
        val items = catalog.result(listOf(RelatedWork(Series("123", "封面", cover = "http://example.com/a b.jpg"), "", false)))
            .getJSONArray("items")
        val scope = items.getJSONObject(0).getInt("scope")
        assertEquals("http://example.com/a%20b.jpg", catalog.cover("123", scope))
        assertThrows(IllegalArgumentException::class.java) { catalog.cover("123", scope + 1) }
        assertThrows(IllegalArgumentException::class.java) { catalog.cover("unlisted", scope) }
        catalog.clear()
        assertThrows(IllegalArgumentException::class.java) { catalog.cover("123", scope) }
    }

    @Test fun coversOnlyExposeBoundedHttpsUrls() = fixture { catalog, _, _, _ ->
        val urls = listOf("javascript:alert(1)", "file:///secret", "https://user:pass@example.com/a", "https://example.com/" + "a".repeat(8192), "https://example.com/poster.jpg")
        val rows = catalog.result(urls.mapIndexed { i, url -> RelatedWork(Series("$i", "剧", cover = url), "", false) }).getJSONArray("items")
        (0..3).forEach { assertEquals("", rows.getJSONObject(it).getString("cover")) }
        assertEquals(urls.last(), rows.getJSONObject(4).getString("cover"))
    }

    @Test fun discoveryAndUpcomingCoversKeepUsableUrlsAndSignedQueries() = fixture { catalog, _, _, _ ->
        val signed = "https://example.com/cover.webp?signature=" + "a".repeat(2300) + "&expires=123"
        val inputs = listOf("http://example.com/cover.jpg", "//example.com/cover.jpg", "https://example.com/a b.jpg?size=100&amp;v=2", signed)
        for (upcoming in listOf(false, true)) {
            val items = catalog.result(inputs.mapIndexed { i, url -> RelatedWork(Series("${i+1}", "作品", cover = url), "", upcoming) }).getJSONArray("items")
            assertEquals("https://example.com/cover.jpg", items.getJSONObject(0).getString("cover"))
            assertEquals("https://example.com/cover.jpg", items.getJSONObject(1).getString("cover"))
            assertEquals("https://example.com/a%20b.jpg?size=100&v=2", items.getJSONObject(2).getString("cover"))
            assertEquals(signed, items.getJSONObject(3).getString("cover"))
            assertEquals(!upcoming, items.getJSONObject(0).getBoolean("playable"))
        }
    }

    @Test fun onlyCurrentBoundedCardsCanTriggerPlayback() = fixture { catalog, _, account, played ->
        repeat(4) { page -> catalog.result((1..40).map { RelatedWork(Series("${page * 40 + it}", "标题"), "", false) }) }
        assertEquals(80, (get(catalog, "entries") as Map<*, *>).size)
        assertTrue(catalog.act(action("play", "1", account.historyScope)).has("error"))
        assertFalse(catalog.act(action("play", "160", account.historyScope)).has("error"))
        assertEquals("160", played.single().first.id)
        assertEquals("", played.single().first.cover)
    }

    @Test fun accountSwitchInvalidatesOldCardsAndMutations() = fixture { catalog, _, account, played ->
        val old = account.historyScope
        catalog.result(listOf(RelatedWork(Series("123", "剧"), "", false)))
        set(account, "generation", old + 1)
        assertTrue(catalog.act(action("play", "123", old)).getString("error").contains("切换"))
        assertTrue(catalog.act(action("favorite", "123", old).put("add", true)).has("error"))
        assertTrue(played.isEmpty())
        assertTrue((get(catalog, "entries") as Map<*, *>).isEmpty())
    }

    @Test fun unpublishedReservationsNeverPlayAndOnlyVerifiedRelatedIdsAreReservable() = fixture { catalog, _, account, played ->
        set(account, "credentials", AccountCredentials("test-only"))
        set(account, "reservations", mapOf(false to ReservationState(listOf(CloudReservation(Series("reservation:123", "未上线"), false, 0)), loaded = true)))
        val item = catalog.list(list("upcoming")).getJSONArray("items").getJSONObject(0)
        assertFalse(item.getBoolean("playable"))
        assertFalse(item.getBoolean("reservable"))
        assertTrue(runCatching { catalog.act(action("play", item.getString("id"), account.historyScope)) }.isFailure)
        assertTrue(played.isEmpty())
        val related = catalog.result(listOf(RelatedWork(Series("456", "下一季"), "第二季", true)))
        assertTrue(related.getJSONArray("items").getJSONObject(0).getBoolean("reservable"))
        assertTrue(runCatching { catalog.act(action("play", "456", account.historyScope)) }.isFailure)
    }

    @Test fun cloudHistoryPassesExactProgressAndLocalPlaybackDoesNotUseIt() = fixture { catalog, _, account, played ->
        val series = Series("123", "历史")
        val progress = CloudProgress(series, "episode-3", 2, 42000, 80000, 1)
        set(account, "credentials", AccountCredentials("test-only"))
        set(account, "history", listOf(progress))
        catalog.list(list("history"))
        catalog.act(action("play", "123", account.historyScope).put("source", "history"))
        assertEquals(progress, played.single().second)
        catalog.act(action("play", "123", account.historyScope).put("source", "localHistory"))
        assertNull(played.last().second)
    }

    @Test fun loadingStatesAreExposedWithoutStartingRepeatedRefreshes() = fixture { catalog, _, account, _ ->
        set(account, "credentials", AccountCredentials("test-only"))
        set(account, "favorites", FavoriteState(loading = true, message = "正在读取"))
        assertTrue(catalog.list(list("favorites")).getBoolean("loading"))
        set(account, "favorites", FavoriteState(loaded = true))
        assertFalse(catalog.list(list("favorites")).getBoolean("loading"))
    }
}
