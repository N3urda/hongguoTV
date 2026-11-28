package com.hongguotv.nativeapp

import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SoftwareVideoFallbackTest {
    private val period = MediaPeriodId("current")
    private fun event(time: Long, source: MediaPeriodId = period) =
        EventTime(time, Timeline.EMPTY, 0, source, 0, Timeline.EMPTY, 0, period, 0, 5000)

    private class Fixture {
        var playing = true
        var mime = MimeTypes.VIDEO_H265
        var recoveries = 0
        val diagnostics = PlaybackDiagnostics("fixture").apply {
            videoDecoder = ActiveVideoDecoder("software.hevc", false, true)
        }
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
            when (method.name) {
                "isPlaying" -> playing
                "getVideoFormat" -> Format.Builder().setSampleMimeType(mime).build()
                else -> null
            }
        } as ExoPlayer
        val listener = SoftwareVideoFallback(player, diagnostics) { recoveries++ }
    }

    @Test fun sustainedHevcSoftwareDropsTriggerRecoveryButHardwareAndOtherCodecsDoNot() {
        val f = Fixture()
        f.listener.onDroppedVideoFrames(event(2000), 50, 2000)
        f.listener.onDroppedVideoFrames(event(4000), 50, 2000)
        assertEquals(1, f.recoveries)
        f.diagnostics.videoDecoder = ActiveVideoDecoder("hardware.hevc", true, false)
        f.listener.onDroppedVideoFrames(event(6000), 50, 2000)
        assertEquals(1, f.recoveries)
        f.diagnostics.videoDecoder = ActiveVideoDecoder("software.avc", false, true)
        f.mime = MimeTypes.VIDEO_H264
        f.listener.onDroppedVideoFrames(event(8000), 50, 2000)
        assertEquals(1, f.recoveries)
    }

    @Test fun pauseSpeedChangesAndPrefetchEventsCannotCombineIntoFalseOverload() {
        val f = Fixture()
        f.listener.onDroppedVideoFrames(event(2000), 50, 2000)
        f.listener.onPlaybackParametersChanged(event(2500), PlaybackParameters(2f))
        f.listener.onDroppedVideoFrames(event(4000), 50, 2000)
        assertEquals(0, f.recoveries)
        f.playing = false
        f.listener.onIsPlayingChanged(event(4500), false)
        f.listener.onDroppedVideoFrames(event(6000), 50, 2000)
        f.playing = true
        f.listener.onDroppedVideoFrames(event(8000, MediaPeriodId("prefetch")), 50, 2000)
        f.listener.onDroppedVideoFrames(event(10000), 50, 2000)
        assertEquals(0, f.recoveries)
        f.listener.onDroppedVideoFrames(event(12000), 50, 2000)
        assertEquals(1, f.recoveries)
    }

    @Test fun audioDiagnosticsExposeMissingTrackAndPlayerMuteWithoutClaimingAudibleSound() {
        val diagnostics = PlaybackDiagnostics("fixture")
        val missing = diagnostics.audioInfo(null, false, 0f)
        assertTrue(missing.contains("音轨 未选择"))
        assertTrue(missing.contains("音频输出 未创建"))
        assertTrue(missing.contains("播放器音量 0%"))
        diagnostics.onAudioDecoderInitialized(event(1), "c2.android.aac.decoder", 1, 1)
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setSampleRate(44100).setChannelCount(2).build()
        val info = diagnostics.audioInfo(format, true, 1f)
        assertTrue(info.contains("音轨 已选择"))
        assertTrue(info.contains("44100 Hz · 2 声道"))
        assertTrue(info.contains("c2.android.aac.decoder"))
        assertTrue(info.contains("音频输出 未创建"))
    }

    @Test fun releasingAnOldAudioTrackDoesNotHideItsAlreadyInitializedReplacement() {
        val diagnostics = PlaybackDiagnostics("fixture")
        val old = androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig(C.ENCODING_PCM_16BIT, 44100, 12, false, false, 65536)
        val replacement = androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig(C.ENCODING_PCM_16BIT, 48000, 12, false, false, 65536)
        diagnostics.onAudioTrackInitialized(event(1), old)
        diagnostics.onAudioTrackInitialized(event(2), replacement)
        diagnostics.onAudioTrackReleased(event(3), old)
        assertSame(replacement, diagnostics.audioTrackConfig)
        diagnostics.onAudioTrackReleased(event(4), replacement)
        assertNull(diagnostics.audioTrackConfig)
    }
}
