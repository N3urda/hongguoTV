// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import com.hongguotv.core.TimedComments
import kotlinx.coroutines.*

/** Bounded read-only timed-comment windows; network work never blocks playback polling. */
class DesktopDanmaku(private val scope: CoroutineScope, private val display: (String) -> Unit, private val status: (String) -> Unit) {
    private val source = TimedComments(DesktopNetwork.client())
    private var job: Job? = null
    private var generation = 0
    private var window = ""
    private var rows = emptyList<Pair<Long, String>>()
    private var shown = mutableSetOf<Int>()
    private var lastPosition = 0L
    fun reset() { generation++; job?.cancel(); window = ""; rows = emptyList(); shown.clear(); lastPosition = 0 }
    fun tick(series: String, episode: String, position: Long, duration: Long) {
        if (duration <= 0) return
        val start = position / 30000 * 30000
        val key = "$episode/$start"
        if (window != key) {
            reset(); window = key
            val ticket = generation
            job = scope.launch {
                try {
                    val result = runInterruptible(Dispatchers.IO) { source.read(series, episode, start, duration) }
                    if (ticket != generation) return@launch
                    val items = result.getJSONArray("items")
                    rows = (0 until items.length()).map { items.getJSONObject(it).let { row -> row.getLong("timeMS") to row.getString("text") } }
                    status(if (rows.isEmpty()) "当前时段暂无弹幕" else "弹幕已开启")
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (ticket == generation) status("弹幕暂不可用；视频继续播放") }
            }
        }
        if (position < lastPosition - 1000) shown.clear()
        lastPosition = position
        val due = rows.indices.filter { it !in shown && rows[it].first in (position - 1500)..position }.take(2)
        if (due.isNotEmpty()) { shown.addAll(due); display(due.joinToString("\n") { safeText(rows[it].second) }) }
    }
    companion object {
        // mpv show-text expands ${...}; never let remote comments expand local properties.
        fun safeText(value: String) = value.replace('$', '＄').replace('{', '｛').replace('}', '｝').replace('\\', '＼').replace(Regex("[\\p{Cc}\\u2028\\u2029]"), " ").take(180)
    }
}
