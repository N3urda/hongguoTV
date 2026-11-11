// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import com.hongguotv.core.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@LooperMode(LooperMode.Mode.PAUSED)
class FeedPreloadPlaybackTest {
    @Test
    fun realMedia3ManagerRegistersRemovesSelectsAndClearsSourcesOnBothSupportedApis() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val playback = Media3TrialPlayback(controller.get())
        fun clip(id: String) =
            TrialClip(
                Detail(Series(id, "测试"), listOf(id + "01")),
                RemoteVideo(OkHttpClient(), StreamInfo("https://example.test/video", null, "测试")),
            )
        val first = clip("1")
        val second = clip("2")
        try {
            playback.setCurrentIndex(0)
            assertFalse(playback.select(first, 0, false))
            playback.queue(first, 0)
            playback.queue(first, 0)
            playback.queue(second, 1)
            playback.remove("2")
            assertFalse(playback.select(second, 0, false))
            assertTrue(playback.select(first, 0, false))
            playback.pause()
            assertFalse(playback.canPrefetch())
            playback.clear()
            assertFalse(playback.select(first, 0, false))
        } finally {
            playback.release()
            first.close()
            second.close()
            controller.pause().stop().destroy()
        }
    }
}
