package com.hongguotv.nativeapp

import android.os.Looper
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackAudioRecoveryTest {
    private val period = MediaPeriodId("current")
    private fun event(time: Long, source: MediaPeriodId = period) =
        EventTime(time, Timeline.EMPTY, 0, source, 0, Timeline.EMPTY, 0, period, 0, 5000)

    private class Fixture {
        var playing = true
        var buffered = 5000L
        var episode = "101"
        var allowed = true
        var audio = true
        var video = true
        val seeks = mutableListOf<Long>()
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "getApplicationLooper" -> Looper.getMainLooper()
                "isPlaying" -> playing
                "getPlaybackState" -> Player.STATE_READY
                "getCurrentMediaItem" -> MediaItem.Builder().setMediaId(episode).build()
                "getTotalBufferedDuration" -> buffered
                "getCurrentPosition" -> 14_000L
                "getAudioFormat" -> if (audio) Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build() else null
                "getVideoFormat" -> if (video) Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build() else null
                "seekTo" -> { seeks += args!![0] as Long; null }
                else -> null
            }
        } as ExoPlayer
        val listener = PlaybackAudioRecovery(player, enabled = true) { allowed }
    }

    @Test fun longBufferedUnderrunResetsAtTheCurrentPositionOnce() {
        val f = Fixture()
        f.listener.onAudioUnderrun(event(1000), 114660, 650, 910)
        f.listener.onAudioUnderrun(event(1001), 114660, 650, 910)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(13_999L), f.seeks)
    }

    @Test fun pauseSpeedChangeOrUserActionCancelsAPendingRecovery() {
        val f = Fixture()
        f.listener.onAudioUnderrun(event(1000), 114660, 650, 910)
        f.playing = false
        f.listener.onIsPlayingChanged(event(1001), false)
        shadowOf(Looper.getMainLooper()).idle()
        f.playing = true
        f.listener.onAudioUnderrun(event(2000), 114660, 650, 910)
        f.listener.onPlaybackParametersChanged(event(2001), PlaybackParameters(1.25f))
        shadowOf(Looper.getMainLooper()).idle()
        f.listener.onAudioUnderrun(event(3000), 114660, 650, 910)
        f.allowed = false
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(f.seeks.isEmpty())
    }

    @Test fun networkStarvationSmallGapsAndOldPeriodsDoNotCauseSeeks() {
        val f = Fixture()
        f.listener.onAudioUnderrun(event(1000, MediaPeriodId("previous")), 114660, 650, 910)
        f.listener.onAudioUnderrun(event(2000), 114660, 650, 720)
        f.listener.onAudioUnderrun(event(3000), 114660, -1, 910)
        f.buffered = 500
        f.listener.onAudioUnderrun(event(4000), 114660, 650, 910)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(f.seeks.isEmpty())
    }

    @Test fun neitherAudioOnlyNorMissingAudioShouldBeTreatedAsAvDrift() {
        val f = Fixture()
        f.audio = false
        f.listener.onAudioUnderrun(event(1000), 114660, 650, 910)
        f.audio = true; f.video = false
        f.listener.onAudioUnderrun(event(2000), 114660, 650, 910)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(f.seeks.isEmpty())
    }

    @Test fun repeatedUnderrunsHaveACooldownAndFinitePerEpisodeBudget() {
        val f = Fixture()
        for (t in listOf(1000L, 1500, 11000, 21000, 31000)) {
            f.listener.onAudioUnderrun(event(t), 114660, 650, 910)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(3, f.seeks.size)
        f.episode = "102"
        f.listener.onAudioUnderrun(event(32000), 114660, 650, 910)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(4, f.seeks.size)
    }

    @Test fun pendingEventCannotSeekTheNextEpisodeAndModernDevicesAreUnaffected() {
        val f = Fixture()
        f.listener.onAudioUnderrun(event(1000), 114660, 650, 910)
        f.episode = "102"
        shadowOf(Looper.getMainLooper()).idle()
        val modern = PlaybackAudioRecovery(f.player)
        modern.onAudioUnderrun(event(2000), 114660, 650, 910)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(f.seeks.isEmpty())
    }
}
