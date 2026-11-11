// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import android.os.Looper
import com.hongguotv.core.RequestScope
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** UI-thread owned queue: one bounded request at a time, yielding immediately to input. */
internal class GradualPreloader(private val allowed: () -> Boolean,
    private val initialDelay: Long = 8000, private val gap: Long = 2500) : Closeable {
    private data class Task(val key: String, val action: () -> Unit, var attempts: Int = 0)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hongguotv-gradual-preload").apply { priority = Thread.MIN_PRIORITY }
    }
    private val queue = java.util.ArrayDeque<Task>()
    private val known = mutableSetOf<String>()
    private var active = false
    private var closed = false
    private var scope: RequestScope? = null
    private var running: Task? = null
    val busy get() = running != null
    fun enqueue(key: String, action: () -> Unit) {
        if (closed || known.size >= 64 || !known.add(key)) return
        queue.add(Task(key, action))
    }
    private val tick = object : Runnable {
        override fun run() {
            if (closed || !active) return
            if (!allowed()) { scope?.cancel(); schedule(gap); return }
            if (running != null) { schedule(gap); return }
            val task = queue.poll() ?: return
            val request = RequestScope()
            scope = request
            running = task
            android.util.Log.i("HongguoTV", "Gradual preload start kind=${task.key.substringBefore(':')}")
            worker.execute {
                val result = runCatching { request.run {
                    RequestScope.withDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(4)) { task.action() }
                } }
                main.post {
                    android.util.Log.i("HongguoTV", "Gradual preload finished success=${result.isSuccess}")
                    scope = null
                    running = null
                    if (!closed && result.isFailure && task.attempts++ == 0) queue.addLast(task)
                    if (!closed && active) schedule(gap)
                }
            }
        }
    }
    private fun schedule(delay: Long) { main.removeCallbacks(tick); main.postDelayed(tick, delay) }
    fun resume() { if (!closed) { active = true; schedule(initialDelay) } }
    fun yieldToUser() { scope?.cancel(); if (active && !closed) schedule(gap) }
    fun pause() { active = false; main.removeCallbacks(tick); scope?.cancel() }
    override fun close() { closed = true; pause(); queue.clear(); worker.shutdownNow() }
}
