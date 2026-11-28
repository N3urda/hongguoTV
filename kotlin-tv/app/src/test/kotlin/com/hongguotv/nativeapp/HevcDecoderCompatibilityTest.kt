package com.hongguotv.nativeapp

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HevcDecoderCompatibilityTest {
    private val highLevel = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
        .setCodecs("hvc1.1.6.L186").setWidth(1920).setHeight(1080).setFrameRate(30f).build()

    private fun codec(software: Boolean): MediaCodecInfo {
        val capabilities = CodecCapabilities.createFromProfileLevel(
            MimeTypes.VIDEO_H265, CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCMainTierLevel41)
        return MediaCodecInfo.newInstance(if (software) "c2.android.hevc.decoder" else "OMX.vendor.hevc.decoder",
            MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H265, capabilities, !software, software, true, false, false)
    }

    @Test fun missingHeaderCannotBypassThe32BitSoftwareSizeLimit() {
        val safety = PlaybackSafetyPolicy(true, false, 2L * 1024 * 1024 * 1024)
        assertFalse(acceptsPlaybackTrack(null, safety, VideoDecoderMode.SOFTWARE_ONLY, "episode/SOFTWARE_ONLY"))
        assertTrue(acceptsPlaybackTrack(null, safety, VideoDecoderMode.AUTOMATIC, "episode/AUTOMATIC"))
        assertTrue(acceptsPlaybackTrack(null, PlaybackSafetyPolicy(), VideoDecoderMode.SOFTWARE_ONLY, "episode/SOFTWARE_ONLY"))
    }

    @Test fun overstatedLevelUsesCompatibleSoftwareWithoutRelaxingHardwareChecks() {
        val hardware = codec(false)
        val software = codec(true)
        assertFalse(software.isFormatSupported(highLevel))
        assertEquals(listOf(software), videoDecoderCandidates(listOf(hardware, software), highLevel, VideoDecoderMode.AUTOMATIC))
        assertEquals(emptyList<MediaCodecInfo>(), videoDecoderCandidates(listOf(hardware), highLevel, VideoDecoderMode.AUTOMATIC))
        assertEquals(listOf(hardware, software), videoDecoderCandidates(listOf(hardware, software), highLevel, VideoDecoderMode.COMPATIBLE_HARDWARE))
        assertEquals(listOf(software), videoDecoderCandidates(listOf(hardware, software), highLevel, VideoDecoderMode.SOFTWARE_ONLY))
    }

    @Test fun softwareLevelToleranceDoesNotAlsoIgnoreProfileAndSizeMismatches() {
        val software = codec(true)
        assertTrue(supportsSoftwareFormat(software, highLevel))
        assertFalse(supportsSoftwareFormat(software, highLevel.buildUpon().setCodecs("hvc1.2.4.L186").build()))
        assertFalse(supportsSoftwareFormat(software, highLevel.buildUpon().setWidth(7680).setHeight(4320).build()))
        assertTrue("A software retry can request a smaller rendition; the oversized one still cannot be selected",
            supportsSoftwareProfile(software, highLevel.buildUpon().setWidth(7680).setHeight(4320).build()))
        assertFalse(supportsSoftwareProfile(software, highLevel.buildUpon().setCodecs("hvc1.2.4.L186").build()))
        assertFalse(supportsSoftwareFormat(codec(false), highLevel))
    }

    @Test fun levelMismatchCanTryHardwareWhenProfileDimensionsAndFrameRateAreSupported() {
        val capabilities = CodecCapabilities.createFromProfileLevel(
            MimeTypes.VIDEO_H265, CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCMainTierLevel41,
        )
        val hardware = MediaCodecInfo.newInstance(
            "OMX.vendor.hevc.decoder", MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H265,
            capabilities, true, false, true, false, false,
        )

        assertFalse(hardware.isFormatSupported(highLevel))
        assertTrue(supportsHevcWithoutLevel(hardware, highLevel))
        assertFalse(supportsHevcWithoutLevel(hardware, highLevel.buildUpon().setCodecs("hvc1.2.4.L186").build()))
        assertFalse(supportsHevcWithoutLevel(hardware, highLevel.buildUpon().setWidth(3840).setHeight(2160).build()))
        assertFalse(supportsHevcWithoutLevel(hardware, highLevel.buildUpon().setFrameRate(120f).build()))
    }

    @Test fun playbackInfoReportsTheSourceFormatAndActualDecoder() {
        val software = ActiveVideoDecoder("c2.android.hevc.decoder", hardwareAccelerated = false, softwareOnly = true)
        assertEquals(
            "播放档位 1080P\n片源尺寸 1920 × 1080\n视频编码 HEVC · hvc1.1.6.L186\n解码器 软解 · c2.android.hevc.decoder",
            playbackVideoInfo("1080P", highLevel, software),
        )
        assertEquals("解码器 尚未初始化", playbackVideoInfo("", null, null))
    }
}
