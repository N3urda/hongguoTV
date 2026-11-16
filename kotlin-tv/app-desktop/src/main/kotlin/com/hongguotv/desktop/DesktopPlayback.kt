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
    var speed by mutableStateOf(PlaybackSpeed.normalize(library.preference("speed", "1.0").toFloatOrNull() ?: 1f).toDouble()); private set
    var quality by mutableStateOf(PlaybackQuality.normalize(library.preference("quality", "1080").toIntOrNull() ?: 1080)); private set
    var autoNext by mutableStateOf(library.preference("autoNext", "true").toBoolean())
    fun changeAutoNext(value: Boolean) { autoNext = value; library.preference("autoNext", value) }
    private var session: PlaybackSession? = null
    private var job: Job? = null
    private var generation = 0
    private var episodeFinished = false
    var danmakuEnabled by mutableStateOf(library.preference("danmaku", "false").toBoolean()); private set
    var danmakuStatus by mutableStateOf(""); private set
    private val danmaku = DesktopDanmaku(scope, { command("show-text", it, 3500) }, { danmakuStatus = it })
    fun changeDanmaku(enabled: Boolean) { danmakuEnabled = enabled; library.preference("danmaku", enabled); danmaku.reset(); if (!enabled) command("show-text", "", 0) }
    var progressSaved: (BackupProgress, Int, Boolean) -> Unit = { _, _, _ -> }
    private val sleepTimer = SleepTimer { System.nanoTime() / 1_000_000 }
    var sleepLabel by mutableStateOf("定时关闭：关闭"); private set
    var frame by mutableStateOf(VideoFrameMode.fromStored(library.preference("frame", "FIT"))); private set
    var rotation by mutableStateOf(0); private set
    fun sleep(minutes: Int) { sleepTimer.afterMinutes(minutes); sleepLabel = sleepTimer.label() }
    fun sleepEpisode() { sleepTimer.afterEpisodes(1); sleepLabel = sleepTimer.label() }
    fun cancelSleep() { sleepTimer.cancel(); sleepLabel = sleepTimer.label() }
    fun chooseFrame(value: VideoFrameMode) { frame = value; library.preference("frame", value.name); command("set_property", "keepaspect", value != VideoFrameMode.FILL); command("set_property", "panscan", if (value == VideoFrameMode.ZOOM) 1.0 else 0.0) }
    fun rotate() { rotation = (rotation + 90) % 360; command("set_property", "video-rotate", rotation) }

    fun resume(detail: Detail) {
        if (detail.episodes.isEmpty()) return
        val saved = library.history[detail.series.id]
        val target = ResumePlayback.target(detail.episodes, saved?.episodeId, saved?.position ?: 0, saved?.completed ?: false)
        play(detail, target.index, target.position / 1000.0)
    }
    fun play(detail: Detail, episode: Int, start: Double = 0.0) {
        if (episode !in detail.episodes.indices) return
        saveProgress(force = true)
        danmaku.reset()
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
                var presentationApplied = false
                var idleTicks = 0
                while (isActive && ticket == generation) {
                    sleepLabel = sleepTimer.label()
                    if (sleepTimer.poll()) { stop(); message = "定时播放已结束"; break }
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
                    if (started && !presentationApplied) {
                        withContext(Dispatchers.IO) {
                            current.player.command("set_property", "keepaspect", frame != VideoFrameMode.FILL)
                            current.player.command("set_property", "panscan", if (frame == VideoFrameMode.ZOOM) 1.0 else 0.0)
                            current.player.command("set_property", "video-rotate", rotation)
                        }
                        presentationApplied = true
                    }
                    paused = values[2] == true
                    if (danmakuEnabled && !paused) danmaku.tick(detail.series.id, detail.episodes[episode], (position * 1000).toLong(), (duration * 1000).toLong())
                    if (values[4] == true) idleTicks++ else idleTicks = 0
                    if (idleTicks >= 8) error("视频加载失败，请重试或切换清晰度。")
                    val now = System.currentTimeMillis()
                    if (now - lastSave > 5000 && started) { saveProgress(); lastSave = now }
                    if (values[3] == true && started) {
                        episodeFinished = true
                        saveProgress()
                        if (sleepTimer.episodeEnded()) { stop(); break }
                        if (autoNext && episode < detail.episodes.lastIndex) {
                            scope.launch { play(detail, episode + 1) }
                        } else if (autoNext) {
                            val next = withContext(Dispatchers.IO) { runCatching { RelatedWorks.nextSeason(detail.series, repository.related(detail.series.id))?.let { repository.detail(it.series.id) } }.getOrNull() }
                            if (next != null && next.episodes.isNotEmpty() && ticket == generation) scope.launch { play(next, 0) }
                            else message = "本剧已播放完毕"
                        } else message = "本集已播放完毕"
                        break
                    }
                    delay(500)
                }
                if (ticket == generation && !current.player.alive) { saveProgress(); running = false; session = null; withContext(Dispatchers.IO) { current.close() }; message = "播放窗口已关闭，可点击继续播放。" }
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
    fun setSpeed(value: Float) { speed = PlaybackSpeed.normalize(value).toDouble(); library.preference("speed", speed); command("set_property", "speed", speed) }
    fun chooseQuality(value: Int) { quality = PlaybackQuality.normalize(value); library.preference("quality", quality); active?.let { play(it, index, position) } }
    fun next(delta: Int) { active?.let { play(it, index + delta) } }
    fun retry() { active?.let { play(it, index, position) } }
    private fun command(vararg arguments: Any) {
        val current = session ?: return
        scope.launch { runCatching { withContext(Dispatchers.IO) { current.player.command(*arguments) } }.onFailure { if (session === current) message = "播放器控制失败，请重新播放。" } }
    }
    private fun saveProgress(completed: Boolean = episodeFinished, force: Boolean = false) {
        val detail = active ?: return
        if (preparing || duration <= 0) return
        val progress = BackupProgress(detail.series, detail.episodes[index], index, (position * 1000).toLong(), (duration * 1000).toLong(), completed, System.currentTimeMillis())
        library.remember(progress)
        progressSaved(progress, detail.episodes.size, force || completed)
    }
    fun stop() {
        saveProgress(force = true); danmaku.reset(); generation++; job?.cancel(); job = null
        val old = session; session = null
        old?.close()
        running = false; preparing = false; active = null
    }
    override fun close() { saveProgress(force = true); danmaku.reset(); generation++; job?.cancel(); session?.close(); session = null }
}
