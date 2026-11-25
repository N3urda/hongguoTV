// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

// 请求生命周期管理：跟踪当前任务的网络请求，在任务取消时释放对应请求。

import java.io.IOException
import okhttp3.Call
import okhttp3.Response

/** Cancels only the work owned by one navigation/playback intent, including body reads. */
class RequestScope {
    private var cancelled = false
    private val calls = mutableSetOf<Call>()

    @Synchronized
    fun cancel() {
        cancelled = true
        calls.forEach { it.cancel() }
        calls.clear()
    }

    @Synchronized
    private fun attach(call: Call) {
        if (cancelled || Thread.currentThread().isInterrupted) {
            call.cancel()
            throw IOException("请求已取消")
        }
        calls += call
    }

    @Synchronized
    private fun detach(call: Call) {
        calls -= call
    }

    fun <T> run(action: () -> T): T {
        val previous = current.get()
        current.set(this)
        try {
            synchronized(this) { if (cancelled) throw IOException("请求已取消") }
            return action()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    /** Add a session owner without replacing the navigation owner or extending its deadline. */
    internal fun <T> alongside(action: () -> T): T {
        val previous = additional.get().orEmpty()
        additional.set(previous + this)
        return try { ensureActive(); action() } finally {
            if (previous.isEmpty()) additional.remove() else additional.set(previous)
        }
    }

    companion object {
        private val current = ThreadLocal<RequestScope>()
        private val additional = ThreadLocal<List<RequestScope>>()
        private val deadline = ThreadLocal<Long>()

        fun deadlineAtMost(endNanos: Long): Long = minOf(deadline.get() ?: endNanos, endNanos)

        /** 同一轮内的注册、签名请求和正文读取共享绝对截止时间，嵌套调用不能延长预算。 */
        fun <T> withDeadline(endNanos: Long, action: () -> T): T {
            val previous = deadline.get()
            deadline.set(deadlineAtMost(endNanos))
            try {
                return action()
            } finally {
                if (previous == null) deadline.remove() else deadline.set(previous)
            }
        }

        /** 在签名、状态提交和重试前检查取消，避免旧任务污染下一次播放。 */
        fun ensureActive() {
            val owners = listOfNotNull(current.get()) + additional.get().orEmpty()
            if (Thread.currentThread().isInterrupted || owners.any { synchronized(it) { it.cancelled } })
                throw java.io.InterruptedIOException("请求已取消")
        }

        fun <T> execute(call: Call, consume: (Response) -> T): T {
            val owners = (listOfNotNull(current.get()) + additional.get().orEmpty()).distinct()
            try {
                owners.forEach { it.attach(call) }
                deadline.get()?.let { end ->
                    val timeout = call.timeout()
                    timeout.deadlineNanoTime(if (timeout.hasDeadline()) minOf(timeout.deadlineNanoTime(), end) else end)
                }
                return call.execute().use(consume)
            } finally {
                owners.forEach { it.detach(call) }
            }
        }
    }
}
