package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class RankBrowserStateTest {
    private var now = 1_000_000L
    private fun state(ttl: Long = 10 * 60_000) = RankBrowserState(clock = { now }, ttlMillis = ttl)
    private fun snapshot(board: RankBoard = RankBoard.ALL) =
        RankSnapshot(board, listOf(Series("1", "作品")), ComicRanking(emptyMap(), 1, ""))

    @Test
    fun noCacheIsNeverFresh() {
        assertFalse(state().isFresh(RankBoard.ALL))
    }

    @Test
    fun successfulRefreshMakesBoardFreshUntilTtlExpires() {
        val state = state()
        state.beginRefresh(RankBoard.REAL)
        state.endRefresh(RankBoard.REAL, snapshot(RankBoard.REAL))
        assertTrue(state.isFresh(RankBoard.REAL))
        now += 10 * 60_000 + 1
        assertFalse(state.isFresh(RankBoard.REAL))
        // 过期不等于丢失：旧快照仍在，供静默续期期间展示。
        assertNotNull(state.snapshots[RankBoard.REAL])
    }

    @Test
    fun sameBoardRefreshIsDeduplicatedWhileBoardsRunInParallel() {
        val state = state()
        assertTrue(state.beginRefresh(RankBoard.ALL))
        assertFalse(state.beginRefresh(RankBoard.ALL)) // 同榜在途：去重
        assertTrue(state.beginRefresh(RankBoard.COMIC)) // 跨榜：并行不受影响
        state.endRefresh(RankBoard.ALL, snapshot())
        assertTrue(state.beginRefresh(RankBoard.ALL)) // 结束后可再次拉取
    }

    @Test
    fun failedRefreshKeepsOldSnapshotAndClearsInFlightOnly() {
        val state = state()
        state.endRefresh(RankBoard.ALL, snapshot())
        now += 10 * 60_000 + 1 // 旧数据过期
        state.beginRefresh(RankBoard.ALL)
        state.endRefresh(RankBoard.ALL, null)
        assertNotNull(state.snapshots[RankBoard.ALL]) // 旧榜单保留展示
        assertFalse(state.isRefreshing(RankBoard.ALL))
        assertFalse(state.isFresh(RankBoard.ALL)) // 仍过期，等待下次续期
    }
}
