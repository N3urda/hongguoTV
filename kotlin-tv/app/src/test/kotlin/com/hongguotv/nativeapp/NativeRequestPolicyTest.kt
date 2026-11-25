// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.AppSigningException
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Protocol/state fixtures; these do not claim to be an independent native SDK oracle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeRequestPolicyTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val reports = mutableListOf<String>()
    private val flags = mutableMapOf(1 to 0, 2 to 0, 3 to 0, 4 to 1)
    private val strings = mapOf(
        "1e308f" to "/passport/", "5dc544" to "/unused-two/", "f6976b" to "/unused-one/",
        "2fe1e0" to "sync", "d31e48" to "sync", "2a9b23" to "sync",
        "662c86" to "version", "30d97d" to "rules", "6ea09b" to "1", "c8b0d1" to "\\|", "83e6ff" to ";",
        "c2d261" to "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/", "a2114b" to "=",
        "4cb4e7" to "==", "7f0f6c" to "-", "7dd9d7" to "+", "f32615" to "_", "957588" to "/",
    )
    @Before fun clear() {
        context.getSharedPreferences("native_signing_policy", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun policy() = NativeRequestPolicy(context, { op, arg, _, text, _ ->
        when (op) {
            0x3000003 -> -42L // An opaque non-sentinel negative handle is valid.
            0x200000d -> flags[arg]
            0x2000001 -> { reports += text!!; null }
            else -> error("Unexpected operation")
        }
    }, { key, _ -> strings.getValue(key) })

    private fun control(pattern: String, mode: String): Map<String, List<String>> {
        val encodedPattern = Base64.getEncoder().encodeToString(pattern.toByteArray())
        val json = JSONObject().put("version", "1").put("rules", "$encodedPattern;$mode").toString()
        return mapOf("x-bdms-ctrl" to listOf(Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())))
    }

    @Test fun syncOnlyRejectsMatchingRequests() {
        flags[3] = 1
        val policy = policy()
        policy.beforeRequest("https://example.test/novel/player/", -30L)
        assertTrue(runCatching { policy.beforeRequest("https://example.test/passport/mobile/", -30L) }
            .exceptionOrNull() is AppSigningException)
    }

    @Test fun responseControlSurvivesRestartAndChangesMatchingRule() {
        val first = policy()
        first.observeResponse("https://example.test/novel/player/", control("/novel/player/", "sync"), -2L)
        val restarted = policy()
        assertTrue(runCatching { restarted.beforeRequest("https://example.test/novel/player/", -30L) }
            .exceptionOrNull() is AppSigningException)
        restarted.observeResponse("https://example.test/novel/player/", control("/novel/player/", "off"), -2L)
        policy().beforeRequest("https://example.test/novel/player/", -30L)
    }

    @Test fun asyncReportsOnlyWhenRuleMatches() {
        val policy = policy()
        policy.observeResponse("https://example.test/novel/player/", control("/novel/player/", "async"), -2L)
        policy.beforeRequest("https://example.test/reading/user/", -30L)
        assertTrue(reports.isEmpty())
        policy.beforeRequest("https://example.test/novel/player/", -30L)
        assertEquals(1, reports.size)
        assertTrue(reports.single().matches(Regex("report-async_\\d+")))
    }

    @Test fun unsupportedDynamicCodeBlocksCurrentResponseAndSurvivesRestart() {
        assertTrue(runCatching {
            policy().observeResponse("https://example.test/novel/player/", mapOf("X-Bdms-Dy" to listOf("synthetic")), -2L)
        }.exceptionOrNull() is AppSigningException)
        assertTrue(runCatching { policy().beforeRequest("https://example.test/novel/player/", -1L) }
            .exceptionOrNull() is AppSigningException)
        assertFalse(context.getSharedPreferences("native_signing_policy", 0).all.values.contains("synthetic"))
    }
}
