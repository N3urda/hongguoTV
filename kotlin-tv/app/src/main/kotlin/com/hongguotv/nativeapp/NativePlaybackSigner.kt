// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.SharedPreferences
import com.hongguotv.core.AppRequestSigner
import com.hongguotv.core.AppSigningException
import com.hongguotv.core.AppSigningInput
import com.hongguotv.core.AppSigningResult
import com.hongguotv.core.SigningBackend
import java.security.MessageDigest

/** Optional ARM64 SDK backend. The caller must finish the URL, body and all headers first. */
class NativePlaybackSigner internal constructor(private val runtime: NativeSigningRuntime) : AppRequestSigner {
    constructor(context: Context) : this(sharedRuntime(context.applicationContext))

    override val backend = SigningBackend.NATIVE_SDK

    override fun sign(input: AppSigningInput): AppSigningResult = runtime.sign(input)
    override fun observeResponse(url: String, status: Int, headers: Map<String, List<String>>) =
        runtime.observeResponse(url, status, headers)

    companion object {
        private var shared: NativeSigningRuntime? = null

        @Synchronized
        private fun sharedRuntime(context: Context): NativeSigningRuntime = shared ?: NativeSigningRuntime(
            NativeSdkEngine(context),
            context.getSharedPreferences("native_signing_lifecycle", Context.MODE_PRIVATE),
        ).also { shared = it }
    }
}

/** The SDK owns process-global state, so binding, lifecycle events and signing share one lock. */
internal class NativeSigningRuntime(
    private val engine: NativeSigningEngine,
    private val preferences: SharedPreferences,
) {
    private val lock = Any()
    private var initialized = false
    private var binding: Pair<String, String>? = null

    fun observeResponse(url: String, status: Int, headers: Map<String, List<String>>) = synchronized(lock) {
        if (!initialized) throw AppSigningException("原生签名状态尚未就绪，请稍后重试")
        try {
            engine.observeResponse(url, status, headers)
        } catch (error: AppSigningException) {
            throw error
        } catch (_: Throwable) {
            throw AppSigningException("原生签名响应策略无效，请稍后重试")
        }
    }

    fun sign(input: AppSigningInput): AppSigningResult = synchronized(lock) {
        try {
            val context = input.context
            val identity = context.deviceId to context.installId
            if (identity.first.isBlank() || identity.second.isBlank()) {
                throw AppSigningException("原生签名需要有效的已登记设备，请先初始化设备")
            }
            if (!initialized) {
                engine.initialize()
                initialized = true
            }
            if (!preferences.getBoolean("first_time_reported", false)) {
                engine.report("first_time")
                // A returned native call is local acknowledgement, not server acceptance.
                if (!preferences.edit().putBoolean("first_time_reported", true).commit()) {
                    throw AppSigningException("无法保存签名状态，请稍后重试")
                }
            }
            if (binding != identity) {
                engine.bind(identity.first, identity.second)
                engine.report("did-iid-update")
                binding = identity
            }
            context.accountGeneration?.let { generation ->
                if (generation.isBlank()) throw AppSigningException("账号签名状态无效，请重新登录")
                val fingerprint = MessageDigest.getInstance("SHA-256")
                    .digest(generation.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                val reported = preferences.getString("last_login_generation", null)
                if (fingerprint != reported) {
                    engine.report("login")
                    if (!preferences.edit().putString("last_login_generation", fingerprint).commit()) {
                        throw AppSigningException("无法保存签名状态，请稍后重试")
                    }
                }
            }
            val headers = engine.sign(input.url, input.headers)
            val normalized = headers.entries.associate { it.key.lowercase() to it.value }
            val required = listOf("x-khronos", "x-helios", "x-medusa")
            if (normalized.keys != required.toSet() || required.any { normalized[it].isNullOrBlank() } ||
                normalized.values.any { '\r' in it || '\n' in it }) {
                throw AppSigningException("原生签名结果不完整，请稍后重试")
            }
            AppSigningResult(input.url, required.associateWith { normalized.getValue(it) })
        } catch (error: AppSigningException) {
            throw error
        } catch (_: Throwable) {
            // Never propagate native exceptions containing request URLs, Cookies or tokens.
            throw AppSigningException("原生签名失败，请稍后重试")
        }
    }
}

internal interface NativeSigningEngine {
    fun initialize()
    fun bind(deviceId: String, installId: String)
    fun report(event: String)
    fun sign(url: String, headers: Map<String, List<String>>): Map<String, String>
    fun observeResponse(url: String, status: Int, headers: Map<String, List<String>>) = Unit
}
