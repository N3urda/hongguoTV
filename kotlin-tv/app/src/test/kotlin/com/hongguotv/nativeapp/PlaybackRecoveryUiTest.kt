// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryUiTest {
    private val failure = "播放信息接口返回空响应"

    @Test
    fun `fast phase keeps the countdown wording with dynamic totals`() {
        val text =
            recoveryStatusText(
                failure = failure,
                autoplay = true,
                slow = false,
                attempt = 2,
                total = 8,
                remainingMillis = 2500L,
                positionText = "12:34",
            )
        assertEquals("$failure\n准备恢复播放 · 3 秒后重试 2/8 · 12:34", text)
    }

    @Test
    fun `remaining millis round up to whole seconds`() {
        val text =
            recoveryStatusText(failure, autoplay = true, slow = false, attempt = 1, total = 8, remainingMillis = 1L, positionText = "00:05")
        assertEquals("$failure\n准备恢复播放 · 1 秒后重试 1/8 · 00:05", text)
    }

    @Test
    fun `paused recovery keeps the pause wording`() {
        val text =
            recoveryStatusText(failure, autoplay = false, slow = false, attempt = 1, total = 8, remainingMillis = 1000L, positionText = "00:05")
        assertEquals("$failure\n准备恢复暂停位置 · 1 秒后重试 1/8 · 00:05", text)
    }

    @Test
    fun `slow phase keeps recovery wording with a visible countdown`() {
        val text =
            recoveryStatusText(failure, autoplay = true, slow = true, attempt = 5, total = 8, remainingMillis = 60000L, positionText = "12:34")
        assertEquals("$failure\n服务暂时没有响应，正在自动恢复 · 60 秒后第 5/8 次尝试 · 12:34", text)
    }

    @Test
    fun `exhausted budget guides the user elsewhere without fake promises`() {
        val text = recoveryExhaustedText(failure)
        assertEquals("$failure\n本集暂时无法播放，可以先看其他内容，稍后再回来试试。", text)
    }

    @Test
    fun `diagnostic line carries sanitized device and recovery metadata`() {
        val line =
            diagnosticLine(
                model = "GTV-42S",
                androidVersion = "9",
                appVersion = "0.1.12",
                failure = failure,
                recoveryAttempts = 6,
                total = 8,
                detail = "空响应x4 host=b,hl,lf,b",
            )
        assertEquals(
            "诊断: GTV-42S / Android 9 / v0.1.12 / $failure · 恢复6/8 · 空响应x4 host=b,hl,lf,b",
            line,
        )
    }

    @Test
    fun `diagnostic line omits recovery and detail parts when absent`() {
        val line = diagnosticLine("Box", "6.0", "0.1.12", failure, 0, 8, null)
        assertEquals("诊断: Box / Android 6.0 / v0.1.12 / $failure", line)
    }

    @Test
    fun `diagnostic line caps oversized detail segments`() {
        // detail 的脱敏由 core 构造处保证（见 PlayRetryPlanTest）；app 层契约是限长展示。
        val line =
            diagnosticLine("Box", "9", "0.1.12", failure, 2, 8, "x".repeat(200))
        assertTrue(line.endsWith("x".repeat(180)))
        assertTrue(line.length < 280)
    }
}
