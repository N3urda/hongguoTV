// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import androidx.compose.runtime.*
import com.hongguotv.core.*
import kotlinx.coroutines.*
import java.io.Closeable

class PlaybackSession(val bridge: PlaybackBridge, val player: MpvPlayer) : Closeable {
    override fun close() { try { player.close() } finally { bridge.close() } }
}

class DesktopPlayback(private val scope: CoroutineScope, private val repository: ContentRepository, private val library: DesktopLibrary) : Closeable {
    var active by mutableStateOf<Detail?>(null); private set
    var index by mutableStateOf(0); private set
    var preparing by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var position by mutableStateOf(0.0); private set
    var duration by mutableStateOf(0.0); private set
    var paused by mutableStateOf(false); private set
    var running by mutableStateOf(false); private set
    var speed by mutableStateOf(1.0); private set
    var quality by mutableStateOf(1080); private set
    var autoNext by mutableStateOf(true)
    private var session: PlaybackSession? = null
    private var job: Job? = null
    private var generation = 0
    private var episodeFinished = false

    fun resume(detail: Detail) {
        if (detail.episodes.isEmpty()) return
        val saved = library.history[detail.series.id]
        val target = ResumePlayback.target(detail.episodes, saved?.episodeId, saved?.position ?: 0, saved?.completed ?: false)
        play(detail, target.index, target.position / 1000.0)
    }
    fun play(detail: Detail, episode: Int, start: Double = 0.0) {
        if (episode !in detail.episodes.indices) return
        saveProgress()
        job?.cancel()
        val ticket = ++generation
        val previous = session; session = null
        previous?.close()
        episodeFinished = false
        active = detail; index = episode; position = start; duration = 0.0
        preparing = true; running = false; message = null; paused = false
        job = scope.launch {
            var created: PlaybackSession? = null
            try {
                withContext(Dispatchers.IO) {
                    MpvPlayer.findExecutable() // Fail before requesting upstream when the runtime is absent.
                    repository.preparePlaybackSession()
                    val rendition = repository.streamLadderWithCompatibility(detail.episodes[episode]).select(quality)
                        ?: error("没有可播放的资源，请换一集或清晰度。")
                    val video = RemoteVideo(repository.http, rendition.toStreamInfo())
                    var bridge: PlaybackBridge? = null
                    try {
                        video.prepare()
                        ensureActive()
                        bridge = PlaybackBridge(video)
                        val player = MpvPlayer(bridge.url, "${detail.series.title} · 第 ${episode + 1} 集", start)
                        created = PlaybackSession(bridge, player)
                        player.command("set_property", "speed", speed)
                    } catch (e: Exception) { bridge?.close() ?: video.close(); throw e }
                }
                ensureActive()
                if (ticket != generation) return@launch
                session = created; created = null
                preparing = false; running = true
                val current = session!!
                var lastSave = 0L
                var started = false
                var idleTicks = 0
                while (isActive && ticket == generation) {
                    val values = withContext(Dispatchers.IO) {
                        if (!current.player.alive) null else listOf(
                            current.player.command("get_property", "time-pos"),
                            current.player.command("get_property", "duration"),
                            current.player.command("get_property", "pause"),
                            current.player.command("get_property", "eof-reached"),
                            current.player.command("get_property", "idle-active"),
                        )
                    } ?: break
                    (values[0] as? Number)?.toDouble()?.let { position = it; started = true }
                    (values[1] as? Number)?.toDouble()?.let { duration = it }
                    paused = values[2] == true
                    if (values[4] == true) idleTicks++ else idleTicks = 0
                    if (idleTicks >= 8) error("视频加载失败，请重试或切换清晰度。")
                    val now = System.currentTimeMillis()
                    if (now - lastSave > 5000 && started) { saveProgress(); lastSave = now }
                    if (values[3] == true && started) {
                        episodeFinished = true
                        saveProgress()
                        if (autoNext && episode < detail.episodes.lastIndex) {
                            scope.launch { play(detail, episode + 1) }
                        } else message = "本集已播放完毕"
                        break
                    }
                    delay(500)
                }
                if (ticket == generation && !current.player.alive) { saveProgress(); running = false; message = "播放窗口已关闭，可点击继续播放。" }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (ticket == generation) {
                    running = false
                    val failed = session; session = null
                    withContext(NonCancellable + Dispatchers.IO) { failed?.close() }
                    message = if (e is IllegalStateException && e.message?.contains("mpv") == true) e.message else "播放失败，请重试或更换清晰度。"
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { created?.close() }
                if (ticket == generation) preparing = false
            }
        }
    }
    fun togglePause() = command("cycle", "pause")
    fun seek(seconds: Double) = command("seek", seconds.coerceAtLeast(0.0), "absolute")
    fun setSpeed(value: Float) { speed = PlaybackSpeed.normalize(value).toDouble(); command("set_property", "speed", speed) }
    fun chooseQuality(value: Int) { quality = PlaybackQuality.normalize(value); active?.let { play(it, index, position) } }
    fun next(delta: Int) { active?.let { play(it, index + delta) } }
    fun retry() { active?.let { play(it, index, position) } }
    private fun command(vararg arguments: Any) {
        val current = session ?: return
        scope.launch { runCatching { withContext(Dispatchers.IO) { current.player.command(*arguments) } }.onFailure { if (session === current) message = "播放器控制失败，请重新播放。" } }
    }
    private fun saveProgress(completed: Boolean = episodeFinished) {
        val detail = active ?: return
        if (preparing || duration <= 0) return
        library.remember(BackupProgress(detail.series, detail.episodes[index], index, (position * 1000).toLong(), (duration * 1000).toLong(), completed, System.currentTimeMillis()))
    }
    fun stop() {
        saveProgress(); generation++; job?.cancel(); job = null
        val old = session; session = null
        old?.close()
        running = false; preparing = false; active = null
    }
    override fun close() { saveProgress(); generation++; job?.cancel(); session?.close(); session = null }
}
