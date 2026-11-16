// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A separate process owns the native video window; only loopback URLs enter its command line. */
class MpvPlayer(url: String, title: String, startSeconds: Double = 0.0, executable: String = findExecutable(), extraArguments: List<String> = emptyList()) : Closeable {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val directory = if (windows) null else Files.createTempDirectory(Path.of("/tmp"), "hg-mpv-")
    private val endpoint = if (windows) "\\\\.\\pipe\\hg-${UUID.randomUUID()}" else directory!!.resolve("ipc").toString()
    private val process = ProcessBuilder(listOf(executable, "--no-config", "--no-terminal", "--idle=yes", "--keep-open=yes", "--force-window=yes", "--hwdec=auto-safe", "--input-ipc-server=$endpoint", "--title=$title", "--start=${startSeconds.coerceAtLeast(0.0)}") + extraArguments + listOf(url))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val alive get() = process.isAlive
    init {
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (true) {
                try { command("get_property", "idle-active"); break }
                catch (e: Exception) {
                    if (!alive || System.nanoTime() >= deadline) throw IllegalStateException("播放器未能启动，请检查 mpv 安装。", e)
                    Thread.sleep(100)
                }
            }
        } catch (e: Exception) { close(); throw e }
    }
    fun command(vararg arguments: Any): Any? {
        check(alive) { "播放窗口已关闭" }
        val line = JSONObject().put("command", JSONArray(arguments.toList())).put("request_id", 1).toString() + "\n"
        val reply = if (windows) pipeRequest(line) else socketRequest(line)
        if (reply.optString("error") != "success") return null
        return reply.opt("data").takeUnless { it == JSONObject.NULL }
    }
    private fun socketRequest(line: String): JSONObject = SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
        channel.configureBlocking(false)
        channel.connect(UnixDomainSocketAddress.of(endpoint))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        fun pause() {
            check(alive && System.nanoTime() < deadline) { "播放器响应超时" }
            Thread.sleep(5)
        }
        while (!channel.finishConnect()) pause()
        val request = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
        while (request.hasRemaining()) if (channel.write(request) == 0) pause()
        val buffer = ByteBuffer.allocate(65536)
        val text = StringBuilder()
        while (true) {
            val n = channel.read(buffer)
            check(n >= 0) { "播放器连接已关闭" }
            if (n == 0) { pause(); continue }
            buffer.flip()
            val bytes = ByteArray(buffer.remaining()); buffer.get(bytes); buffer.clear()
            text.append(bytes.toString(Charsets.UTF_8))
            check(text.length < 65536)
            while (text.indexOf("\n") >= 0) {
                val end = text.indexOf("\n")
                val row = JSONObject(text.substring(0, end)); text.delete(0, end + 1)
                if (row.optInt("request_id") == 1) return@use row
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    private fun pipeRequest(line: String): JSONObject = RandomAccessFile(endpoint, "rw").use { pipe ->
        val timeout = timeouts.schedule({ runCatching { pipe.close() } }, 3, TimeUnit.SECONDS)
        try {
            pipe.write(line.toByteArray(Charsets.UTF_8))
            while (true) {
                val row = JSONObject(pipe.readLine() ?: error("播放器连接已关闭"))
                if (row.optInt("request_id") == 1) return@use row
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        } finally { timeout.cancel(false) }
    }
    override fun close() {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        directory?.let { Files.deleteIfExists(it.resolve("ipc")); Files.deleteIfExists(it) }
    }
    companion object {
        private val timeouts = Executors.newSingleThreadScheduledExecutor { Thread(it, "mpv-ipc-timeout").apply { isDaemon = true } }
        fun findExecutable(): String {
            val name = if (System.getProperty("os.name").startsWith("Windows")) "mpv.exe" else "mpv"
            val candidates = listOfNotNull(System.getenv("HONGGUO_MPV"), "/opt/homebrew/bin/mpv", "/usr/local/bin/mpv", "C:/Program Files/mpv/mpv.exe") + System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name).path }
            return candidates.firstOrNull { File(it).isFile && File(it).canExecute() }
                ?: error("未找到 mpv。请安装 mpv，或用 HONGGUO_MPV 指定可执行文件路径后重新启动。")
        }
    }
}
