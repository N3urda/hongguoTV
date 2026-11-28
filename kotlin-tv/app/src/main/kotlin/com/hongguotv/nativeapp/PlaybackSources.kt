// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.SystemClock
import android.util.Log
import com.hongguotv.core.ContentHttpException
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.EmptyPlaybackResponseException
import com.hongguotv.core.PlaybackDiagnosticException
import com.hongguotv.core.PlaybackSourceException
import com.hongguotv.core.PreparedSlot
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.Rendition
import com.hongguotv.core.RequestScope
import com.hongguotv.core.StreamLadder
import com.hongguotv.core.VideoTracks
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okhttp3.OkHttpClient

private fun Throwable.isPlaybackTimeout(): Boolean =
    this is SocketTimeoutException ||
        this is InterruptedIOException && (message == "timeout" || message == "deadline reached")

/** 只展示已知错误类别，不把响应正文、媒体地址或查询凭据带到界面和日志。 */
internal fun playbackFailureSummary(problem: Throwable): String {
    val causes = generateSequence(problem) { it.cause }.take(8).toList()
    if (causes.any { it is com.hongguotv.core.PlaybackDeviceRegistrationException })
        return "播放设备注册失败"
    if (causes.any { it is NoPlayableQualityException })
        return "本集没有符合当前清晰度和解码能力的片源，可尝试其他集数或解码方式"
    if (causes.any { it is EmptyPlaybackResponseException }) return "播放信息接口返回空响应"
    causes.filterIsInstance<ContentHttpException>().firstOrNull()?.let {
        return "播放信息请求失败（HTTP ${it.statusCode}）"
    }
    causes
        .mapNotNull { it.message }
        .firstOrNull { it.matches(Regex("播放接口暂不可用（错误码 \\d+）")) }
        ?.let {
            return it
        }
    if (causes.any { it.isPlaybackTimeout() }) return "网络请求超时"
    if (causes.any { it is java.net.UnknownHostException }) return "无法解析服务器域名"
    if (causes.any { it is java.io.EOFException }) return "视频分段提前断开"
    val known =
        listOf(
            "播放请求已取消",
            "播放准备已取消",
            "视频分段范围不匹配",
            "视频资源已变化，请重试",
            "备用视频资源的索引与当前集不一致",
            "播放信息为空",
            "播放地址格式异常",
            // 前缀匹配：这两类消息带变量后缀（清晰度建议语、HTTP 码），完整文案原样透出。
            "没有电视可播放的清晰度",
            "视频服务器不支持分段读取",
            // 伴机交互文案原样透出（RelayException 的全部消息以「伴机」开头）。
            "伴机",
            "本集没有 bytevc2 档位",
        )
    causes
        .mapNotNull { it.message }
        .firstOrNull { message -> known.any { message.startsWith(it) } }
        ?.let {
            return it
        }
    if (problem is androidx.media3.common.PlaybackException) return "播放器错误：${problem.errorCodeName}"
    return if (causes.any { it is java.io.IOException }) "播放数据读取失败" else "播放准备失败"
}

/**
 * 错误页诊断行的结构化 detail（issue #38 后续）：从异常链提取紧凑键值对，让用户截图自带可定位的
 * 错误类别与关键参数。只含类别代号、状态码与既有脱敏元数据，不含 Cookie、地址与凭据。
 */
private fun diagnosticProblems(problem: Throwable): Sequence<Throwable> = sequence {
    val pending = java.util.ArrayDeque<Throwable>()
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    pending.add(problem)
    while (pending.isNotEmpty() && seen.size < 16) {
        val next = pending.removeFirst()
        if (!seen.add(next)) continue
        yield(next)
        next.cause?.let { pending.add(it) }
        next.suppressed.take(8).forEach { pending.add(it) }
    }
}

internal fun hasEmptyPlaybackModels(problem: Throwable): Boolean =
    diagnosticProblems(problem).any { it is EmptyPlaybackResponseException && it.allPlaybackModelsEmpty }

internal fun diagnosticDetail(problem: Throwable): String? =
    diagnosticProblems(problem)
        .mapNotNull { cause ->
            when {
                cause is java.net.SocketTimeoutException -> when (cause.message) {
                    "播放准备超时" -> "timeout=preparation"
                    "预加载等待超时" -> "timeout=prefetch_wait"
                    else -> "timeout=transport"
                }
                cause is java.io.InterruptedIOException &&
                    cause.message in setOf("timeout", "deadline reached") -> "timeout=deadline"
                cause is com.hongguotv.core.PlaybackDiagnosticException -> cause.detail
                cause is NoPlayableQualityException -> cause.track?.let {
                    "coded=${it.width}x${it.height} codec=${it.codecs ?: it.fourcc}"
                }
                cause is EmptyPlaybackResponseException -> cause.detail?.takeIf { it.isNotBlank() }
                cause is ContentHttpException -> "http=${cause.statusCode}"
                cause is androidx.media3.common.PlaybackException ->
                    "exo=${cause.errorCodeName.removePrefix("ERROR_CODE_")}"
                else -> null
            }
        }
        .distinct()
        .joinToString(" · ")
        .take(640)
        .ifEmpty { null }

internal class OpenedStream(
    val video: RemoteVideo,
    val rendition: Rendition?,
    val tiers: List<Rendition>,
    val track: VideoTracks.Track?,
) : java.io.Closeable {
    override fun close() = video.close()
}

/** Shares previously opened renditions between the feed and full-screen player in one Activity. */
internal class PlaybackSourceCache(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private data class Entry(val rendition: Rendition, val openedAt: Long, val requestedMaxQuality: Int,
        val allowQualityFallback: Boolean)
    private val entries = linkedMapOf<String, Entry>()

    @Synchronized
    fun find(id: String, maxQuality: Int, allowQualityFallback: Boolean = true): Rendition? {
        prune(clock())
        val entry = entries[id] ?: return null
        return entry.rendition.takeIf { it.quality <= maxQuality &&
            (allowQualityFallback || !entry.allowQualityFallback && entry.requestedMaxQuality == maxQuality) }
    }

    @Synchronized
    fun remember(id: String, rendition: Rendition, maxQuality: Int, allowQualityFallback: Boolean = true) {
        val now = clock()
        prune(now)
        entries.remove(id)
        entries[id] = Entry(rendition, now, maxQuality, allowQualityFallback)
        while (entries.size > 6) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun forget(id: String, rendition: Rendition? = null) {
        if (rendition == null || entries[id]?.rendition === rendition) entries.remove(id)
    }

    @Synchronized fun clear() { entries.clear() }

    private fun prune(now: Long) {
        entries.entries.removeAll { now - it.value.openedAt !in 0 until TimeUnit.MINUTES.toMillis(5) }
    }
}

private class RecentSourceValidation(
    private val id: String,
    val rendition: Rendition,
    private val deadline: Long,
    executor: Executor,
    prepare: () -> OpenedStream?,
) : java.io.Closeable {
    private val scope = RequestScope()
    private val ready = PreparedSlot<OpenedStream>(SystemClock::elapsedRealtime)
    private val ticket = ready.begin(id)
    private val job = FutureTask {
        scope.run {
            RequestScope.withDeadline(deadline) {
                prepare()?.let { ready.complete(ticket, id, it) }
            }
        }
    }

    init {
        executor.execute(job)
    }

    fun take(): OpenedStream? {
        try {
            job.get((deadline - System.nanoTime()).coerceAtLeast(0L), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            return null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("播放准备已取消")
        } catch (failure: ExecutionException) {
            val cause = failure.cause
            if (cause is IOException) return null
            throw cause ?: failure
        }
        RequestScope.ensureActive()
        return ready.take(id)
    }

    override fun close() {
        ready.clear()
        scope.cancel()
        job.cancel(true)
    }
}

/**
 * 阶梯非空但没有任何一档满足清晰度和解码约束：重试只会得到同一结果，
 * 上层不应再进入自动恢复预算；手动重试保留（服务端转码推进后该集供给可能翻转为 bytevc1）。
 */
internal class NoPlayableQualityException(val track: VideoTracks.Track? = null) :
    java.io.IOException("本集没有符合当前清晰度和解码能力的片源")

/**
 * 试播后移换集：候选集固定为前三集（绝对上限，试播兜底不无限后移），从 start 集起依次尝试，
 * 跳过「该集无电视可解码档」的确定性失败，返回首个打开成功的集内序号（0 起）与结果；全部
 * 不可解返回 null。只吞 NoPlayableQualityException，网络类异常原样上抛。
 */
internal fun <T> firstPlayableEpisode(
    episodes: List<String>,
    start: Int,
    open: (String) -> T,
): Pair<T, Int>? {
    for (index in start..minOf(2, episodes.size - 1)) {
        try {
            return open(episodes[index]) to index
        } catch (problem: NoPlayableQualityException) {
            // 该集在电视上无可解码档：尝试下一集。
        }
    }
    return null
}

/**
 * Selects a rendition and checks its MP4 header. A transient model failure can reuse a rendition
 * for the same episode, provided a new media request still validates it.
 */
internal fun openStream(
    http: OkHttpClient,
    repository: ContentRepository,
    id: String,
    maxQuality: Int,
    supportsRendition: (Rendition) -> Boolean,
    recentSources: PlaybackSourceCache,
    initialBytes: Int = 256 * 1024,
    metadataAhead: List<String> = emptyList(),
    speculative: Boolean = false,
    allowQualityFallback: Boolean = true,
    supportsTrack: (VideoTracks.Track?) -> Boolean,
): OpenedStream? {
    fun prepare(ladder: StreamLadder): OpenedStream? {
        var rejectedTrack: VideoTracks.Track? = null
        // Choose the source tier BEFORE device filtering. A rejected high tier must not silently
        // become a lower tier when the user disabled automatic quality changes.
        val highest = ladder.renditions.filter { it.quality <= maxQuality }.maxOfOrNull { it.quality }
        for (rendition in ladder.playbackOrder(maxQuality) {
            (allowQualityFallback || it.quality == highest) && supportsRendition(it)
        }) {
            val video = RemoteVideo(http, rendition.toStreamInfo())
            try {
                video.prepare(initialBytes)
                RequestScope.ensureActive()
                val track = video.trackInfo()
                rejectedTrack = track ?: rejectedTrack
                val withinQuality = track == null || minOf(track.width, track.height) <= maxQuality
                if (withinQuality && supportsTrack(track)) {
                    val openedRendition = if (video.info.url == rendition.url) rendition
                    else rendition.copy(url = video.info.url, backupUrl = video.info.backupUrl)
                    return OpenedStream(video, openedRendition, ladder.renditions, track)
                }
                Log.i(
                    "HongguoTV",
                    "Skipped ${rendition.quality}P ${rendition.codec}: coded=${track?.width}x${track?.height} " +
                        "codecs=${track?.codecs}; incompatible with decoder or quality limit ($maxQuality P)",
                )
                video.close()
            } catch (error: Exception) {
                video.close()
                throw error
            }
        }
        if (ladder.renditions.isNotEmpty()) throw NoPlayableQualityException(rejectedTrack)
        return null
    }

    var validation: RecentSourceValidation? = null
    val prepareDeadline = RequestScope.deadlineAtMost(Long.MAX_VALUE)
    val ladder = try {
        repository.streamLadderWithCompatibility(id, metadataAhead, speculative,
            acceptable = { it.quality <= maxQuality && supportsRendition(it) }) {
            val cached = recentSources.find(id, maxQuality, allowQualityFallback)
            // Session recovery can invoke this callback twice; share and close one validation job.
            if (cached != null && validation == null) {
                val deadline = minOf(prepareDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(2))
                try {
                    validation = RecentSourceValidation(id, cached, deadline, http.dispatcher.executorService) {
                        prepare(StreamLadder(listOf(cached)))
                    }
                } catch (_: RejectedExecutionException) {
                    // The playback transport may already be closing.
                }
            }
        }
    } catch (problem: IOException) {
        RequestScope.ensureActive()
        if (problem is PlaybackSourceException) recentSources.forget(id)
        val modelsEmpty = problem is EmptyPlaybackResponseException && problem.allPlaybackModelsEmpty
        val backupTimedOut = problem.isPlaybackTimeout() &&
            problem.suppressed.any { it is EmptyPlaybackResponseException }
        if (speculative || (!modelsEmpty && !backupTimedOut)) throw problem
        val pending = validation ?: throw problem
        if (recentSources.find(id, maxQuality, allowQualityFallback) !== pending.rendition) throw problem
        val recovered = pending.take()
        if (recovered == null) {
            recentSources.forget(id, pending.rendition)
            problem.addSuppressed(PlaybackDiagnosticException("recent-source=unavailable"))
            throw problem
        }
        if (recentSources.find(id, maxQuality, allowQualityFallback) !== pending.rendition) {
            recovered.close()
            throw problem
        }
        Log.i("HongguoTV", "Opened recently verified playback source")
        return recovered
    } finally {
        validation?.close()
    }
    return prepare(ladder)?.also { opened ->
        opened.rendition?.let { recentSources.remember(id, it, maxQuality, allowQualityFallback) }
    }
}

/**
 * Lowest ladder tier strictly below the attempted quality — the manual button always plays exactly
 * the tier it names. Null when the ladder has nothing lower, so no dead-end button is offered.
 */
internal fun nextLowerQuality(attempted: Int, tiers: List<Rendition>): Int? =
    tiers.map { it.quality }.filter { it in 1 until attempted }.minOrNull()

/**
 * Auto-downgrade is worth another attempt while budget remains and a strictly lower tier exists.
 */
internal fun shouldAutoDowngrade(
    attempts: Int,
    maxAttempts: Int,
    tiers: List<Rendition>,
    currentQuality: Int,
): Boolean =
    attempts < maxAttempts &&
        currentQuality > 0 &&
        tiers.any { it.quality in 1 until currentQuality }
