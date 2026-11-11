package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WatchingTest {
    @Test
    fun `favorite baseline is quiet and multiple checks accumulate unread episodes`() {
        val first = FavoriteUpdate.first(80, 100)
        assertEquals(0, first.added)
        val one = first.observe(82, 200)
        assertEquals(2, one.added)
        val two = FavoriteUpdate.decode(one.json())!!.observe(85, 300)
        assertEquals(5, two.added)
        assertEquals(0, two.observe(85, 400, acknowledge = true).added)
        assertEquals(1, two.observe(85, 400, true).observe(86, 500).added)
    }

    @Test
    fun `temporary shrink and stale requests do not invent updates or undo acknowledgements`() {
        val first = FavoriteUpdate.first(80, 100)
        assertEquals(0, first.observe(75, 200).observe(80, 300).added)
        val acknowledged = first.observe(85, 400, true)
        assertEquals(acknowledged, acknowledged.observe(83, 300))
        assertEquals(0, acknowledged.observe(70, 500, true).observe(85, 600).added)
    }

    @Test
    fun `corrupt update metadata falls back to a fresh baseline`() {
        assertNull(FavoriteUpdate.decode(null))
        assertNull(FavoriteUpdate.decode(JSONObject("""{"total":0,"seen":1,"checkedAt":1}""")))
        assertNull(FavoriteUpdate.decode(JSONObject("""{"total":10,"seen":-1,"checkedAt":1}""")))
        assertEquals(0, FavoriteUpdate.first(100, 20).added)
    }

    @Test
    fun `resume follows episode identity after reordering and completed episode advances`() {
        val episodes = listOf("new", "a", "b", "c")
        assertEquals(ResumeTarget(2, 52000), ResumePlayback.target(episodes, "b", 52000, false))
        assertEquals(ResumeTarget(3, 0), ResumePlayback.target(episodes, "b", 52000, true))
        assertEquals(ResumeTarget(3, 0), ResumePlayback.target(episodes, "c", 52000, true))
        assertEquals(ResumeTarget(0, 0), ResumePlayback.target(episodes, "removed", 52000, false))
        assertEquals(ResumeTarget(1, 0), ResumePlayback.target(episodes, "a", -100, false))
    }

    @Test
    fun cloudEpisodeIndexResolvesDifferentIdentifiersWithoutImportingHistory() {
        val data = Detail(Series("1", "续播测试"), listOf("101", "102", "103"))
        val cloud = CloudProgress(data.series, "999", 2, 45000, 90000, 2000)
        val progress = ResumePlayback.progress(data, null, cloud)!!
        assertEquals("103", progress.episodeId)
        assertEquals(2, progress.episodeIndex)
        assertEquals(
            ResumeTarget(2, 45000),
            ResumePlayback.target(
                data.episodes,
                progress.episodeId,
                progress.position,
                progress.completed,
            ),
        )
        assertEquals("999", cloud.episodeId)
    }

    @Test
    fun matchingCloudIdentityTakesPriorityOverItsStaleEpisodeNumber() {
        val data = Detail(Series("1", "续播测试"), listOf("103", "101", "102"))
        val cloud = CloudProgress(data.series, "103", 2, 45000, 90000, 2000)
        val progress = ResumePlayback.progress(data, null, cloud)!!
        assertEquals(0, progress.episodeIndex)
        assertEquals("103", progress.episodeId)
        assertEquals(45000, progress.position)
    }

    @Test
    fun invalidCloudEpisodeOrDifferentSeriesCannotCreateAFalseResumePosition() {
        val data = Detail(Series("1", "续播测试"), listOf("101", "102", "103"))
        val cloud = CloudProgress(data.series, "999", 20, 45000, 90000, 2000)
        assertNull(ResumePlayback.progress(data, null, cloud))
        assertNull(ResumePlayback.progress(data, null, cloud.copy(episodeIndex = -1)))
        assertNull(
            ResumePlayback.progress(
                data,
                null,
                cloud.copy(series = Series("2", "其他剧"), episodeIndex = 2),
            )
        )
    }

    @Test
    fun genericDetailUsesNewestProgressWhileExplicitCloudHistoryHonorsItsSource() {
        val data = Detail(Series("1", "续播测试"), listOf("101", "102", "103"))
        val local = BackupProgress(data.series, "102", 1, 12000, 90000, false, 3000)
        val cloud = CloudProgress(data.series, "999", 2, 45000, 90000, 2000)
        assertEquals(local, ResumePlayback.progress(data, local, cloud))
        assertEquals(
            "103",
            ResumePlayback.progress(data, local, cloud.copy(updatedAt = 4000))!!.episodeId,
        )
        assertEquals(
            "103",
            ResumePlayback.progress(data, local, cloud, preferCloud = true)!!.episodeId,
        )
        assertEquals(local, ResumePlayback.progress(data, local, cloud.copy(updatedAt = 3000)))
    }

    @Test
    fun unavailableCloudProgressKeepsLocalCompletionAndFirstEpisodeFallback() {
        val data = Detail(Series("1", "续播测试"), listOf("101", "102", "103"))
        val local = BackupProgress(data.series, "102", 1, 12000, 90000, true, 3000)
        assertEquals(local, ResumePlayback.progress(data, local, null))
        assertNull(ResumePlayback.progress(data, null, null))
        val progress = ResumePlayback.progress(data, local, null)!!
        assertEquals(
            ResumeTarget(2, 0),
            ResumePlayback.target(
                data.episodes,
                progress.episodeId,
                progress.position,
                progress.completed,
            ),
        )
    }

    @Test
    fun `automatic recovery is bounded until the user starts a new attempt`() {
        val budget = RecoveryBudget()
        // 快段三次紧跟失败现场。
        assertEquals(
            listOf(1000L, 2500L, 5000L),
            (1..3).map {
                val d = budget.delayMillis
                assertFalse(budget.slow)
                budget.consume()
                d
            },
        )
        // 慢段五次退避累计恰五分钟，覆盖分钟级空响应窗口。
        assertEquals(
            listOf(15000L, 30000L, 60000L, 90000L, 105000L),
            (1..5).map {
                val d = budget.delayMillis
                assertTrue(budget.slow)
                budget.consume()
                d
            },
        )
        repeat(10) {
            assertFalse(budget.consume())
            assertNull(budget.delayMillis)
        }
        assertEquals(8, budget.attempts)
        assertEquals(8, budget.total)
        budget.reset()
        assertEquals(1000L, budget.delayMillis)
        assertTrue(budget.consume())
    }

    @Test
    fun `legacy global defaults stay valid and invalid values have safe defaults`() {
        assertEquals(720, PlaybackQuality.normalize(720))
        assertEquals(1080, PlaybackQuality.normalize(1080))
        assertEquals(1080, PlaybackQuality.normalize(-1))
        assertEquals(480, PlaybackQuality.normalize(480))
        assertEquals(1.5f, PlaybackSpeed.normalize(1.5f))
        assertEquals(1f, PlaybackSpeed.normalize(Float.NaN))
    }
}
