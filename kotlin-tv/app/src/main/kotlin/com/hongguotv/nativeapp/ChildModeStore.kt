// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import com.hongguotv.core.Series
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.json.JSONArray
import org.json.JSONObject

/** Local TV policy, separate from accounts/library backups. Call credential and disk operations off main. */
internal class ChildModeStore(
    context: Context,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "child-mode-v1.json"))
    private val random = SecureRandom()
    private var state = JSONObject().put("version", 1).put("enabled", false).put("revision", 0L)
        .put("entries", JSONArray()).put("failures", 0).put("lockedUntil", 0L)
    private var allowedIds: Set<String> = emptySet()
    private var damaged = false
    private var monotonicLock = 0L
    private data class Session(val started: Long, var used: Long)
    private val sessions = mutableMapOf<String, Session>()
    @Volatile var onChanged: (() -> Unit)? = null

    init {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            try {
                val loaded = JSONObject(file.openRead().bufferedReader().use { it.readText() })
                require(loaded.getInt("version") == 1)
                loaded.getBoolean("enabled")
                loaded.getLong("revision")
                val rows = loaded.getJSONArray("entries")
                for (i in 0 until rows.length()) require(rows.getJSONObject(i).getString("id").isNotBlank())
                if (loaded.optBoolean("enabled")) require(loaded.has("credential"))
                if (loaded.has("credential")) {
                    val credential = loaded.getJSONObject("credential")
                    require(credential.getInt("iterations") in 10000..1000000)
                    require(unhex(credential.getString("salt")).size == 16)
                    require(unhex(credential.getString("digest")).size == 32)
                    require(unhex(loaded.getString("recovery")).size == 32)
                }
                state = loaded
                allowedIds = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet()
                val remaining = (state.optLong("lockedUntil") - wallClock()).coerceIn(0, 60000)
                if (remaining > 0) monotonicLock = clock() + remaining
            } catch (_: Exception) { damaged = true }
        }
    }

    val enabled: Boolean @Synchronized get() = damaged || state.optBoolean("enabled")
    val configured: Boolean @Synchronized get() = state.has("credential")
    val broken: Boolean @Synchronized get() = damaged
    val revision: Long @Synchronized get() = state.optLong("revision")
    @Synchronized fun entries(): List<Series> {
        if (damaged) return emptyList()
        val rows = state.getJSONArray("entries")
        return (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
            Series(row.getString("id"), row.optString("title"), cover = row.optString("cover"))
        } }
    }
    @Synchronized fun contains(id: String) = !damaged && id in allowedIds
    @Synchronized fun allows(id: String) = !damaged && (!enabled || contains(id))
    @Synchronized fun authorized(token: String): Boolean {
        val session = sessions[token] ?: return false
        val now = clock()
        if (now < session.started || now - session.used >= 300000 || now - session.started >= 1800000) {
            sessions.remove(token)
            return false
        }
        return !damaged
    }
    @Synchronized fun touch(token: String) { requireParent(token); sessions[token]?.used = clock() }
    @Synchronized fun lock(token: String) { sessions.remove(token) }
    @Synchronized fun revokeSessions() { sessions.clear() }
    private fun requireParent(token: String) { check(authorized(token)) { "家长验证已过期，请重新输入密码" } }
    private fun healthy() { check(!damaged) { "儿童模式配置损坏，已限制播放；请保留数据并联系维护者" } }
    private fun validPin(pin: String, confirm: String) {
        require(pin.matches(Regex("[0-9]{6}"))) { "请输入 6 位数字密码" }
        require(pin == confirm) { "两次密码不一致" }
    }
    private fun copyState() = JSONObject(state.toString())
    private fun save(next: JSONObject, changed: Boolean = false) {
        healthy()
        if (changed) next.put("revision", revision + 1)
        val out = file.startWrite()
        try {
            out.write(next.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw IllegalStateException("儿童模式保存失败，请重试", e)
        }
        state = next
        val rows = next.getJSONArray("entries")
        allowedIds = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet()
        if (changed) onChanged?.invoke()
    }
    private fun checkLock() {
        healthy()
        check(clock() >= monotonicLock) { "密码错误次数过多，请稍后再试" }
    }
    private fun verified(ok: Boolean) {
        if (!ok) {
            val count = state.optInt("failures") + 1
            val next = copyState().put("failures", if (count >= 5) 0 else count)
            if (count >= 5) {
                monotonicLock = clock() + 60000
                next.put("lockedUntil", wallClock() + 60000)
            }
            save(next)
            throw IllegalArgumentException(if (count >= 5) "密码错误次数过多，请等待 60 秒" else "密码或恢复码不正确")
        }
        save(copyState().put("failures", 0).put("lockedUntil", 0L))
        monotonicLock = 0
    }
    private fun verifyPin(pin: String) {
        checkLock()
        check(configured) { "请先在电视设置家长密码" }
        val c = state.getJSONObject("credential")
        val actual = if (pin.matches(Regex("[0-9]{6}"))) derive(pin, unhex(c.getString("salt")), c.getInt("iterations")) else ByteArray(0)
        verified(MessageDigest.isEqual(actual, unhex(c.getString("digest"))))
    }
    private fun credentials(next: JSONObject, pin: String): String {
        val salt = bytes(16)
        val iterations = 120000
        next.put("credential", JSONObject().put("salt", hex(salt)).put("iterations", iterations)
            .put("digest", hex(derive(pin, salt, iterations))))
        val recovery = hex(bytes(16)).uppercase().chunked(4).joinToString("-")
        next.put("recovery", recoveryDigest(recovery))
        next.put("failures", 0).put("lockedUntil", 0L)
        return recovery
    }
    @Synchronized fun setup(pin: String, confirm: String): String {
        healthy(); check(!configured) { "已设置家长密码" }; validPin(pin, confirm)
        val next = copyState()
        val recovery = credentials(next, pin)
        save(next, true)
        return recovery
    }
    @Synchronized fun unlock(pin: String): String {
        verifyPin(pin)
        sessions.entries.removeAll { clock() - it.value.used >= 300000 || clock() - it.value.started >= 1800000 }
        check(sessions.size < 32) { "家长连接过多，请稍后重试" }
        val token = hex(bytes(24))
        sessions[token] = Session(clock(), clock())
        return token
    }
    @Synchronized fun enable(token: String) {
        requireParent(token)
        save(copyState().put("enabled", true), true)
        revokeSessions()
    }
    @Synchronized fun disable(pin: String) {
        verifyPin(pin)
        save(copyState().put("enabled", false), true)
        revokeSessions()
    }
    @Synchronized fun changePin(old: String, pin: String, confirm: String): String {
        validPin(pin, confirm); verifyPin(old)
        val next = copyState()
        val recovery = credentials(next, pin)
        save(next, true); revokeSessions()
        return recovery
    }
    @Synchronized fun recover(code: String, pin: String, confirm: String): String {
        validPin(pin, confirm); checkLock()
        check(configured) { "尚未设置家长密码" }
        verified(MessageDigest.isEqual(recoveryDigest(code).toByteArray(), state.getString("recovery").toByteArray()))
        val next = copyState()
        val recovery = credentials(next, pin)
        save(next, true); revokeSessions()
        return recovery
    }
    @Synchronized fun add(token: String, series: Series) {
        requireParent(token)
        require(series.id.isNotBlank() && series.id.length <= 64) { "作品无效，请刷新列表" }
        if (contains(series.id)) { touch(token); return }
        val rows = copyState().getJSONArray("entries")
        check(rows.length() < 1000) { "儿童片单最多保存 1000 部作品" }
        rows.put(JSONObject().put("id", series.id).put("title", series.title.take(200))
            .put("cover", series.cover.take(8192)).put("addedAt", wallClock()))
        save(copyState().put("entries", rows), true); touch(token)
    }
    @Synchronized fun remove(token: String, id: String) {
        requireParent(token)
        val old = state.getJSONArray("entries")
        val rows = JSONArray()
        for (i in 0 until old.length()) old.getJSONObject(i).let { if (it.getString("id") != id) rows.put(it) }
        if (rows.length() != old.length()) save(copyState().put("entries", rows), true)
        touch(token)
    }
    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun unhex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun recoveryDigest(code: String) = hex(MessageDigest.getInstance("SHA-256")
        .digest(code.replace("-", "").replace(" ", "").uppercase().toByteArray(Charsets.UTF_8)))
}
