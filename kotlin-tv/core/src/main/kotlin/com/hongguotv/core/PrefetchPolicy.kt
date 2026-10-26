// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

object PrefetchPolicy {
    /** Wall-clock time to the end matters: long episodes must not outlive the slot TTL. */
    fun nearEnd(position: Long, duration: Long, speed: Float): Boolean {
        if (duration <= 0 || position < 0 || position >= duration || !speed.isFinite() || speed <= 0) return false
        // 45 秒：慢网络上抓流头+预热 1MB 可能要十几秒，30 秒窗口经常来不及。
        return (duration - position) / speed <= 45_000f
    }
}
