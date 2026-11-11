// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.util.Log
import com.hongguotv.core.ContentHttpException
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.EmptyPlaybackResponseException
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.Rendition
import com.hongguotv.core.VideoTracks
import okhttp3.OkHttpClient

/** 只展示已知错误类别，不把响应正文、媒体地址或查询凭据带到界面和日志。 */
internal fun playbackFailureSummary(problem: Throwable): String {
    val causes = generateSequence(problem) { it.cause }.take(8).toList()
    if (causes.any { it is com.hongguotv.core.PlaybackDeviceRegistrationException })
        return "播放设备注册失败"
    if (causes.any { it is NoPlayableQualityException })
        return "本集低档为 VVC（bytevc2）编码，电视无法解码，可尝试其他集数"
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
    if (causes.any { it is java.net.SocketTimeoutException ||
        it is java.io.InterruptedIOException && it.message in setOf("timeout", "deadline reached") }) return "网络请求超时"
    if (causes.any { it is java.net.UnknownHostException }) return "无法解析服务器域名"
    if (causes.any { it is java.io.EOFException }) return "视频分段提前断开"
    val known =
        listOf(
            "播放请求已取消",
            "播放准备已取消",
            "视频分段范围不匹配",
            "视频资源已变化，请重试",
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
internal fun diagnosticDetail(problem: Throwable): String? =
    generateSequence(problem) { it.cause }
        .take(8)
        .mapNotNull { cause ->
            when {
                cause is EmptyPlaybackResponseException -> cause.detail?.takeIf { it.isNotBlank() }
                cause is ContentHttpException -> "http=${cause.statusCode}"
                cause is androidx.media3.common.PlaybackException ->
                    "exo=${cause.errorCodeName.removePrefix("ERROR_CODE_")}"
                else -> null
            }
        }
        .distinct()
        .joinToString(" · ")
        .take(180)
        .ifEmpty { null }

internal class OpenedStream(
    val video: RemoteVideo,
    val rendition: Rendition?,
    val tiers: List<Rendition>,
    val track: VideoTracks.Track?,
) : java.io.Closeable {
    override fun close() = video.close()
}

/**
 * 阶梯非空但没有任何一档能被本机解码（bytevc2 锁定集的确定性结果）：重试只会得到同一结果，
 * 上层不应再进入自动恢复预算；手动重试保留（服务端转码推进后该集供给可能翻转为 bytevc1）。
 */
internal class NoPlayableQualityException :
    java.io.IOException("本集低档为 VVC（bytevc2）编码，电视无法解码，可尝试其他集数")

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
 * Opens the first rendition the device can actually decode: select by API metadata, then verify the
 * coded size read from the MP4 header and step down the ladder on mismatch. Throws
 * [NoPlayableQualityException] when the ladder has renditions but none is decodable on this device
 * (deterministic — retrying yields the same result); returns null only for an empty ladder.
 */
internal fun openStream(
    http: OkHttpClient,
    repository: ContentRepository,
    id: String,
    maxQuality: Int,
    supportsRendition: (Rendition) -> Boolean,
    initialBytes: Int = 256 * 1024,
    metadataAhead: List<String> = emptyList(),
    speculative: Boolean = false,
    supportsTrack: (VideoTracks.Track?) -> Boolean,
): OpenedStream? {
    val ladder = repository.streamLadder(id, metadataAhead, speculative)
    for (rendition in ladder.playbackOrder(maxQuality, supportsRendition)) {
        val video = RemoteVideo(http, rendition.toStreamInfo())
        try {
            video.prepare(initialBytes)
            val track = video.trackInfo()
            if (supportsTrack(track))
                return OpenedStream(video, rendition, ladder.renditions, track)
            Log.i(
                "HongguoTV",
                "Skipped ${rendition.quality}P ${rendition.codec}: coded ${track?.width}x${track?.height} beyond decoder capability",
            )
            video.close()
        } catch (error: Exception) {
            video.close()
            throw error
        }
    }
    // 空阶梯维持 null（供给异常语义，由调用方提示）；非空阶梯走到这里说明每一档都被
    // 能力判定（metadata 过滤或 moov 复核）拒掉，属确定性失败。
    if (ladder.renditions.isNotEmpty()) throw NoPlayableQualityException()
    return null
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
