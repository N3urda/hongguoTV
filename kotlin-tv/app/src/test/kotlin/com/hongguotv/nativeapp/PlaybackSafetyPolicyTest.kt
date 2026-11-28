// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.Rendition
import com.hongguotv.core.VideoTracks
import org.junit.Assert.*
import org.junit.Test

class PlaybackSafetyPolicyTest {
    private val projector = PlaybackSafetyPolicy(true, false, 984L * 1024 * 1024)

    @Test fun oneGigabyteProjectorIsLimitedBeforePlayback() {
        assertEquals(720, projector.maxQuality(1080))
        assertEquals(480, projector.maxQuality(480))
        assertFalse(projector.accepts(VideoTracks.Track("hvc1", 1080, 1920)))
        assertTrue(projector.accepts(VideoTracks.Track("hvc1", 720, 1280)))
        assertTrue(projector.accepts(VideoTracks.Track("hvc1", 1280, 720)))
    }

    @Test fun metadataCannotDisguiseLargerOrUnknownCodedDimensions() {
        assertFalse(projector.accepts(Rendition(720, "h265", 1080, 1920, "video", null)))
        assertFalse(projector.accepts(VideoTracks.Track("hvc1", 3840, 480)))
        assertFalse(projector.accepts(null as VideoTracks.Track?))
        assertTrue(projector.accepts(Rendition(720, "h265", null, null, "video", null)))
    }

    @Test fun notEvery32BitOr64BitDeviceIsRestricted() {
        assertEquals(1080, PlaybackSafetyPolicy(true, false, 2L * 1024 * 1024 * 1024).maxQuality(1080))
        assertEquals(1080, PlaybackSafetyPolicy(false, true, 1024L * 1024 * 1024).maxQuality(1080))
        assertEquals(1080, PlaybackSafetyPolicy(true).maxQuality(1080))
        assertEquals(720, PlaybackSafetyPolicy(true, true).maxQuality(1080))
    }
}
