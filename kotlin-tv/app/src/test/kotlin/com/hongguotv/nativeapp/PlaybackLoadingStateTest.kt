// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.RecoveryBudget
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
class PlaybackLoadingStateTest {
    private fun set(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private fun call(target: Any, name: String) {
        target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)
    }

    @Test fun pendingFirstFrameRetainsCauseAndCannotHideStatusEvenWhenPlayerIsReady() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val status = TextView(activity)
            val hud = LinearLayout(activity)
            val controls = LinearLayout(activity)
            set(activity, "screen", "player")
            set(activity, "playbackText", status)
            set(activity, "hud", hud)
            set(activity, "controls", controls)
            set(activity, "waitingForFirstFrame", true)
            set(activity, "recoveryDisplayActive", true)
            set(activity, "playbackReady", true)
            set(activity, "lastPlaybackFailure", "播放信息接口返回空响应")
            call(activity, "updatePlaybackText")
            assertTrue(status.text.contains("播放信息接口返回空响应"))
            assertTrue(status.text.contains("正在恢复播放"))
            call(activity, "hidePlaybackOverlay")
            assertEquals(View.VISIBLE, hud.visibility)
            set(activity, "waitingForFirstFrame", false)
            set(activity, "recoveryDisplayActive", false)
            set(activity, "playbackReady", false)
            call(activity, "updatePlaybackText")
            assertFalse(status.text.contains("空响应"))
            call(activity, "hidePlaybackOverlay")
            assertEquals(View.GONE, hud.visibility)
        } finally { controller.destroy() }
    }

    @Test fun quickRecoveryStopsBeforeSlowWaitAndOffersExplicitContinuation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val status = TextView(activity)
            val action = TextView(activity)
            set(activity, "screen", "player")
            set(activity, "playbackText", status)
            set(activity, "recoveryAction", action)
            set(activity, "foreground", true)
            set(activity, "playError", true)
            set(activity, "autoRecovery", true)
            val budget = activity.javaClass.getDeclaredField("recovery").apply { isAccessible = true }.get(activity) as RecoveryBudget
            repeat(RecoveryBudget.FAST_ATTEMPTS) { budget.consume() }
            call(activity, "scheduleRecovery")
            assertTrue(status.text.contains("快速恢复暂未成功"))
            assertEquals("继续等待恢复", action.text.toString())
            assertTrue(action.isEnabled)
            repeat(5) { budget.consume() }
            set(activity, "autoRecovery", true)
            set(activity, "allowSlowRecovery", true)
            call(activity, "scheduleRecovery")
            assertFalse(action.isEnabled)
            assertEquals("恢复次数已用尽", action.text.toString())
        } finally { controller.destroy() }
    }
}
