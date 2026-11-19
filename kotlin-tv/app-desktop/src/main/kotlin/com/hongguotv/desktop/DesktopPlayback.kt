// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import androidx.compose.runtime.*
import com.hongguotv.core.*
import kotlinx.coroutines.*
import java.io.Closeable

import org.openani.mediamp.MediaStatus
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData

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
    var engine by mutableStateOf<MpvMediampPlayer?>(null); private set
    var fullscreenRequested by mutableStateOf(false); private set
    var showVideo by mutableStateOf(true)
    private var bridge: PlaybackBridge? = null
    private val handle: MPVHandle? get() = engine?.impl as? MPVHandle

    private fun releaseSession() {
        val old = engine
        engine = null
        try { old?.close() } finally { bridge?.close(); bridge = null }
    }
    private var job: Job? = null
    private var generation = 0
    private var episodeFinished = false
    var danmakuEnabled by mutableStateOf(library.preference("danmaku", "false").toBoolean()); private set
    var danmakuStatus by mutableStateOf(""); private set
    private val danmaku = DesktopDanmaku(scope, { command("show-text", it, 3500) }, { danmakuStatus = it })
    fun changeDanmaku(enabled: Boolean) { danmakuEnabled = enabled; library.preference("danmaku", enabled); danmaku.reset(); if (!enabled) command("show-text", "", 0) }
    internal var playbackDiagnostics: ((String) -> Unit)? = null
    var progressSaved: (BackupProgress, Int, Boolean) -> Unit = { _, _, _ -> }
    private val sleepTimer = SleepTimer { System.nanoTime() / 1_000_000 }
    var sleepLabel by mutableStateOf("定时关闭：关闭"); private set
    var frame by mutableStateOf(VideoFrameMode.fromStored(library.preference("frame", "FIT"))); private set
    var rotation by mutableStateOf(0); private set
    fun sleep(minutes: Int) { sleepTimer.afterMinutes(minutes); sleepLabel = sleepTimer.label() }
    fun sleepEpisode() { sleepTimer.afterEpisodes(1); sleepLabel = sleepTimer.label() }
    fun cancelSleep() { sleepTimer.cancel(); sleepLabel = sleepTimer.label() }
    fun chooseFrame(value: VideoFrameMode) { frame = value; library.preference("frame", value.name); property("keepaspect", value != VideoFrameMode.FILL); property("panscan", if (value == VideoFrameMode.ZOOM) 1.0 else 0.0) }
    fun rotate() { rotation = (rotation + 90) % 360; property("video-rotate", rotation) }

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
        releaseSession()
        showVideo = true
        episodeFinished = false
        active = detail; index = episode; position = start; duration = 0.0
        preparing = true; running = false; message = null; paused = false
        job = scope.launch {
            var pendingBridge: PlaybackBridge? = null
            try {
                withContext(Dispatchers.IO) {
                    playbackDiagnostics?.invoke("Preparing media session")
                    repository.preparePlaybackSession()
                    playbackDiagnostics?.invoke("Resolving episode rendition")
                    val rendition = repository.streamLadderWithCompatibility(detail.episodes[episode]).select(quality)
                        ?: error("没有可播放的资源，请换一集或清晰度。")
                    val video = RemoteVideo(repository.http, rendition.toStreamInfo())
                    try {
                        playbackDiagnostics?.invoke("Opening media ranges")
                        video.prepare()
                        ensureActive()
                        pendingBridge = PlaybackBridge(video)
                    } catch (e: Throwable) { video.close(); throw e }
                }
                ensureActive()
                if (ticket != generation) return@launch
                val source = checkNotNull(pendingBridge)
                bridge = source
                pendingBridge = null
                playbackDiagnostics?.invoke("Loading MediaMP native runtime")
                val current = MpvMediampPlayer(Any(), scope.coroutineContext) { handle ->
                    // Hosted Windows runners have no WASAPI endpoint. Keep decoding the real audio track.
                    if (playbackDiagnostics != null && System.getenv("HONGGUO_SMOKE_NULL_AUDIO") == "1") {
                        handle.option("ao", "null")
                    }
                }
                engine = current
                playbackDiagnostics?.invoke("Opening libmpv stream")
                current.setMediaData(UriMediaData(source.url, emptyMap(), MediaExtraFiles.EMPTY), true, (start * 1000).toLong())
                var lastSave = 0L
                var started = false
                var presentationApplied = false
                while (isActive && ticket == generation) {
                    sleepLabel = sleepTimer.label()
                    if (sleepTimer.poll()) { stop(); break }
                    val snapshot = current.state.value
                    val status = snapshot.mediaStatus
                    if (status is MediaStatus.Error) throw status.error
                    if (status == MediaStatus.Ready) {
                        preparing = false
                        running = true
                        started = true
                        position = current.currentPositionMillis.value / 1000.0
                        duration = (current.mediaProperties.value?.durationMillis ?: 0L) / 1000.0
                    }
                    if (started && !presentationApplied) {
                        property("speed", speed)
                        property("keepaspect", frame != VideoFrameMode.FILL)
                        property("panscan", if (frame == VideoFrameMode.ZOOM) 1.0 else 0.0)
                        property("video-rotate", rotation)
                        presentationApplied = true
                    }
                    paused = !snapshot.playWhenReady
                    if (danmakuEnabled && !paused) danmaku.tick(detail.series.id, detail.episodes[episode], (position * 1000).toLong(), (duration * 1000).toLong())
                    val now = System.currentTimeMillis()
                    if (now - lastSave > 5000 && started) { saveProgress(); lastSave = now }
                    if (status == MediaStatus.Ended) {
                        position = duration
                        episodeFinished = true
                        saveProgress()
                        if (sleepTimer.episodeEnded()) { stop(); break }
                        if (autoNext && episode < detail.episodes.lastIndex) {
                            if (ticket == generation) play(detail, episode + 1)
                        } else if (autoNext) {
                            val next = withContext(Dispatchers.IO) { runCatching { RelatedWorks.nextSeason(detail.series, repository.related(detail.series.id))?.let { repository.detail(it.series.id) } }.getOrNull() }
                            if (next != null && next.episodes.isNotEmpty() && ticket == generation) play(next, 0)
                            else message = "本剧已播放完毕"
                        } else message = "本集已播放完毕"
                        break
                    }
                    delay(500)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Throwable) {
                if (e !is Exception && e !is LinkageError) throw e
                playbackDiagnostics?.invoke(generateSequence(e) { it.cause }.joinToString(" caused by ") {
                    // Keep stream URLs, playback keys and credentials out of acceptance reports.
                    val detail = if (it is UnsatisfiedLinkError) it.message?.substringAfterLast('\\')?.take(200) else null
                    "${it.javaClass.simpleName} ${detail.orEmpty()} at ${it.stackTrace.firstOrNull()}"
                })
                if (ticket == generation) {
                    running = false
                    releaseSession()
                    message = "播放失败，请重试或更换清晰度。"
                }
            } finally {
                pendingBridge?.close()
                if (ticket == generation) preparing = false
            }
        }
    }
    fun fullscreen() { fullscreenRequested = !fullscreenRequested; showVideo = true }
    fun mute() = command("cycle", "mute")
    fun togglePause() { engine?.let { if (it.state.value.playWhenReady) it.pause() else it.play() } }
    fun seek(seconds: Double) { engine?.seekTo((seconds.coerceIn(0.0, duration) * 1000).toLong()) }
    fun setSpeed(value: Float) { speed = PlaybackSpeed.normalize(value).toDouble(); library.preference("speed", speed); property("speed", speed) }
    fun chooseQuality(value: Int) { quality = PlaybackQuality.normalize(value); library.preference("quality", quality); active?.let { play(it, index, position) } }
    fun next(delta: Int) { active?.let { play(it, index + delta) } }
    fun retry() { active?.let { play(it, index, position) } }
    private fun command(vararg arguments: Any) {
        handle?.command(*arguments.map { it.toString() }.toTypedArray())
    }
    private fun property(name: String, value: Any) {
        when (value) {
            is Boolean -> handle?.setPropertyBoolean(name, value)
            is Int -> handle?.setPropertyInt(name, value)
            is Double -> handle?.setPropertyDouble(name, value)
        }
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
        releaseSession()
        fullscreenRequested = false
        running = false; preparing = false; active = null
    }
    override fun close() = stop()
}
