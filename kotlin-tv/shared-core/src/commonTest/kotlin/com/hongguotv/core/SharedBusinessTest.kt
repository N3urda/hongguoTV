// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import kotlin.test.*

class SharedBusinessTest {
    private val series = Series("1", "测试剧集")
    private val detail = Detail(series, listOf("a", "b", "c"))
    private val local = BackupProgress(series, "a", 0, 120, 1000, false, 20)
    private val cloud = CloudProgress(series, "b", 1, 300, 1000, 30)

    @Test fun newerProgressWinsAndTiesPreferLocal() {
        assertEquals("b", ResumePlayback.progress(detail, local, cloud)?.episodeId)
        assertEquals(local, ResumePlayback.progress(detail, local, cloud.copy(updatedAt = 20)))
        assertEquals("b", ResumePlayback.progress(detail, local, cloud.copy(updatedAt = 1), true)?.episodeId)
    }

    @Test fun progressCannotCrossSeriesAndFallsBackToValidCloudIndex() {
        assertNull(ResumePlayback.progress(detail, local.copy(series = Series("2", "其他")), null))
        assertEquals(local, ResumePlayback.progress(detail, local, cloud.copy(series = Series("2", "其他"))))
        val fallback = ResumePlayback.progress(detail, null, cloud.copy(episodeId = "old", position = -10))!!
        assertEquals("b", fallback.episodeId)
        assertEquals(0L, fallback.position)
        assertNull(ResumePlayback.progress(detail, null, cloud.copy(episodeId = "old", episodeIndex = 99)))
    }

    @Test fun resumeUsesEpisodeIdentityAndDoesNotSkipPastLastEpisode() {
        assertEquals(ResumeTarget(0, 0), ResumePlayback.target(detail.episodes, "gone", 999, false))
        assertEquals(ResumeTarget(2, 0), ResumePlayback.target(detail.episodes, "c", 999, true))
        assertEquals(ResumeTarget(1, 0), ResumePlayback.target(detail.episodes, "b", -1, false))
        assertFailsWith<IllegalArgumentException> { ResumePlayback.target(emptyList(), "a", 0, false) }
    }

    @Test fun automaticRecoveryIsBoundedUntilExplicitReset() {
        val budget = RecoveryBudget()
        val delays = mutableListOf<Long>()
        while (budget.remaining) {
            delays += budget.delayMillis!!
            assertTrue(budget.consume())
        }
        assertEquals(listOf(1000L, 2500L, 5000L, 15000L, 30000L, 60000L, 90000L, 105000L), delays)
        assertFalse(budget.consume())
        assertNull(budget.delayMillis)
        assertTrue(budget.slow)
        budget.reset()
        assertEquals(0, budget.attempts)
        assertEquals(1000L, budget.delayMillis)
    }

    @Test fun qualitySelectionRespectsCapabilityAndHasDeterministicFallback() {
        fun rendition(q: Int, codec: String) = Rendition(q, codec, null, null, "test-$q-$codec", null)
        val low = rendition(480, "h264")
        val high = rendition(1080, "h264")
        val hevc = rendition(1080, "hevc")
        val ladder = StreamLadder(listOf(hevc, high, low))
        assertEquals(high, ladder.select(1080))
        assertEquals(low, ladder.select(720))
        assertEquals(low, ladder.select(1080) { it.quality <= 480 })
        assertEquals(listOf(high, hevc, low), ladder.playbackOrder(1080))
        assertNull(StreamLadder(emptyList()).select(1080))
    }

    @Test fun prefetchDoesNotCompeteWithStarvedPlayback() {
        assertTrue(PrefetchPolicy.canStart(40_000, 60_000, 1f, 1500, 5000))
        assertFalse(PrefetchPolicy.canStart(40_000, 60_000, 1f, 1500, 1000))
        assertFalse(PrefetchPolicy.nearEnd(40_000, 60_000, Float.NaN))
        assertFalse(PrefetchPolicy.nearEnd(60_000, 60_000, 1f))
    }

    @Test fun timerFiresOnceAndReplacingModeDisarmsPreviousLimit() {
        var now = 0L
        val timer = SleepTimer { now }
        timer.afterMinutes(1)
        now = 60_000
        assertTrue(timer.poll())
        assertFalse(timer.poll())
        timer.afterMinutes(1)
        timer.afterEpisodes(2)
        now += 60_000
        assertFalse(timer.poll())
        assertFalse(timer.episodeEnded())
        assertTrue(timer.episodeEnded())
        assertFalse(timer.episodeEnded())
    }

    @Test fun favoriteHighWaterMarkSurvivesCatalogShrinkAndStaleChecks() {
        val initial = FavoriteUpdate.first(80, 10)
        val shrunk = initial.observe(70, 20)
        assertEquals(0, shrunk.added)
        assertEquals(80, shrunk.seen)
        assertEquals(shrunk, shrunk.observe(90, 15))
        assertEquals(10, shrunk.observe(90, 30).added)
        assertEquals(0, shrunk.observe(90, 30, acknowledge = true).added)
    }

    @Test fun invalidSavedPreferencesAndEpisodeInputRemainSafe() {
        assertEquals(ContentType.COMIC, ContentType.fromStored("unknown"))
        assertEquals(1f, PlaybackSpeed.normalize(Float.NaN))
        assertEquals(1080, PlaybackQuality.normalize(-1))
        assertEquals(0, EpisodeTarget.parse("1", 10))
        assertNull(EpisodeTarget.parse("11", 10))
        assertNull(EpisodeTarget.parse("-1", 10))
        assertEquals(VideoRotation.Canvas(20, 10), VideoRotation.canvas(10, 20, 90))
        assertEquals(1000, PlaybackProgressDisplay.value(200, 100))
        assertEquals(0, PlaybackProgressDisplay.value(10, 0))
    }

    @Test fun changingDiscoveryCategoryClearsPreviousBrowseState() {
        val state = DramaDiscoveryState()
        state.info = DiscoveryInfo(listOf(DramaTopic("old", "旧题材")), 2, 20, 40)
        state.browse = DiscoveryMemory(1, listOf(series), true, "1", state.info)
        state.select(DramaCategory.REAL, "new-topic")
        assertNull(state.info)
        assertNull(state.browse)
        assertEquals("全部题材", state.topicLabel())
        assertFailsWith<IllegalArgumentException> { state.select(DramaCategory.REAL, "../bad") }
    }
}
