// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hongguotv.core.RemoteVideo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * 离线缓存：把已打开的视频按绝对偏移顺序落盘（RemoteVideo 负责解密），
 * 完成后以本地 MP4 播放，断网也能看。
 */
internal class OfflineDownloads(private val context: Context, private val http: OkHttpClient) {
    enum class State { IDLE, DOWNLOADING, DONE, FAILED }

    data class Status(val state: State, val bytes: Long, val total: Long, val message: String? = null) {
        val percent: Int get() = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
    }

    private val directory = File(context.filesDir, "offline").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "offline-download") }
    private val states = ConcurrentHashMap<String, Status>()
    private val active = ConcurrentHashMap<String, RemoteVideo>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /** 下载完成后回调（主线程）。 */
    var onComplete: ((String) -> Unit)? = null

    /** 已完成、可直接本地播放的文件；未下完的 .part 不算。 */
    fun file(id: String): File? = File(directory, "$id.mp4").takeIf { it.isFile && it.length() > 0 }

    fun status(id: String): Status =
        states[id] ?: file(id)?.let { Status(State.DONE, it.length(), it.length()) } ?: Status(State.IDLE, 0, 0)

    fun start(id: String, video: RemoteVideo, meta: Map<String, String> = emptyMap()) {
        if (status(id).state == State.DOWNLOADING) return
        val copy = video.preparedSource()?.open(http) ?: run {
            states[id] = Status(State.FAILED, 0, 0, "视频尚未就绪")
            return
        }
        states[id] = Status(State.DOWNLOADING, 0, copy.total)
        if (meta.isNotEmpty()) writeMeta(id, meta)
        // 复用旧分片前先确认远程资源长度没变，避免拼出损坏文件。
        if (recordedSize(id) != copy.total) part(id).delete()
        recordSize(id, copy.total)
        cancelled.remove(id)
        active[id] = copy
        worker.execute {
            try {
                download(id, copy)
                states[id] = Status(State.DONE, copy.total, copy.total)
                main.post { onComplete?.invoke(id) }
            } catch (error: Exception) {
                states[id] = Status(
                    if (cancelled.remove(id)) State.IDLE else State.FAILED,
                    part(id).length(),
                    copy.total,
                    error.message,
                )
            } finally {
                active.remove(id)
                copy.close()
            }
        }
    }

    fun cancel(id: String) {
        cancelled += id
        active.remove(id)?.close()
        states[id] = Status(State.IDLE, part(id).length(), 0, "已取消")
    }

    fun remove(id: String) {
        cancelled += id
        active.remove(id)?.close()
        File(directory, "$id.mp4").delete()
        part(id).delete()
        File(directory, "$id.size").delete()
        File(directory, "$id.json").delete()
        states[id] = Status(State.IDLE, 0, 0)
    }

    /** 该系列已缓存的一集；断网打不开详情页时用它直接播放。 */
    fun cachedEpisode(seriesId: String): String? =
        directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.mapNotNull { file ->
                val id = file.name.removeSuffix(".json")
                val owner = runCatching { JSONObject(file.readText()).optString("seriesId") }.getOrNull()
                if (owner == seriesId && file(id) != null) file.lastModified() to id else null
            }
            ?.maxByOrNull { it.first }
            ?.second

    /** 下载时记录的集数；没有元数据时返回 0。 */
    fun episodeNumber(id: String): Int =
        runCatching { JSONObject(File(directory, "$id.json").readText()).optInt("episode") }.getOrDefault(0)

    private fun writeMeta(id: String, meta: Map<String, String>) {
        runCatching { File(directory, "$id.json").writeText(JSONObject(meta as Map<*, *>).toString()) }
    }

    private fun download(id: String, video: RemoteVideo) {
        if (video.total <= 0) throw IOException("视频长度未知")
        val partial = part(id)
        var position = partial.length().coerceAtMost(video.total)
        FileOutputStream(partial, position > 0).use { output ->
            val buffer = ByteArray(CHUNK)
            while (position < video.total) {
                if (cancelled.contains(id)) throw IOException("下载已取消")
                val want = minOf(buffer.size.toLong(), video.total - position).toInt()
                val bytes = video.read(position, want)
                if (bytes.isEmpty()) throw IOException("视频读取意外结束")
                output.write(bytes)
                position += bytes.size
                states[id] = Status(State.DOWNLOADING, position, video.total)
            }
            output.fd.sync()
        }
        if (partial.length() != video.total) throw IOException("离线文件长度不完整")
        if (!partial.renameTo(File(directory, "$id.mp4"))) throw IOException("无法保存离线文件")
        File(directory, "$id.size").delete()
    }

    private fun part(id: String) = File(directory, "$id.part")

    private fun recordedSize(id: String) = File(directory, "$id.size").takeIf { it.isFile }
        ?.readText()?.toLongOrNull()

    private fun recordSize(id: String, total: Long) {
        File(directory, "$id.size").writeText(total.toString())
    }

    private companion object {
        const val CHUNK = 512 * 1024
    }
}
