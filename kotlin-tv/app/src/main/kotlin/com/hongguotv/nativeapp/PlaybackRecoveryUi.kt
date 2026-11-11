// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/**
 * 播放页恢复状态的文案构建（issue #38）：快段给倒计时，慢段说明仍在自动恢复，终局诚实引导。
 * 两段文案都带逐秒倒计时（秒数向上取整），等待期间屏幕持续变化，避免「画面死了」的错觉。
 * 全部为纯函数，便于单测；诊断行只含公开设备信息与重试计数，不含 Cookie、地址与凭据。
 */

internal fun recoveryStatusText(
    failure: String,
    autoplay: Boolean,
    slow: Boolean,
    attempt: Int,
    total: Int,
    remainingMillis: Long,
    positionText: String,
): String {
    val seconds = ((remainingMillis + 999) / 1000).coerceAtLeast(0)
    return if (slow)
        "$failure\n服务暂时没有响应，正在自动恢复 · $seconds 秒后第 $attempt/$total 次尝试 · $positionText"
    else
        "$failure\n${if (autoplay) "准备恢复播放" else "准备恢复暂停位置"} · $seconds 秒后重试 $attempt/$total · $positionText"
}

internal fun recoveryExhaustedText(failure: String) =
    "$failure\n本集暂时无法播放，可以先看其他内容，稍后再回来试试。"

internal fun diagnosticLine(
    model: String,
    androidVersion: String,
    appVersion: String,
    failure: String,
    recoveryAttempts: Int,
    total: Int,
    detail: String?,
): String {
    val head =
        "诊断: ${model.take(24)} / Android ${androidVersion.take(16)} / v${appVersion.take(16)} / ${failure.take(40)}"
    val rest = buildList {
        if (recoveryAttempts > 0) add("恢复$recoveryAttempts/$total")
        if (!detail.isNullOrBlank()) add(detail.take(180))
    }
    return if (rest.isEmpty()) head else "$head · ${rest.joinToString(" · ")}"
}

/** 初次准备与恢复期间保持阶段和失败原因，直到首帧到达。 */
internal fun preparingPlaybackText(recovering: Boolean, failure: String, hasVideo: Boolean): String {
    val phase = if (hasVideo) "正在缓冲，等待画面…" else "正在获取播放信息…"
    return if (recovering) "$failure\n正在恢复播放 · $phase" else phase
}
