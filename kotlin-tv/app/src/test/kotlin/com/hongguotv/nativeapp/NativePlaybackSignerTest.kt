// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.AppSigningContext
import com.hongguotv.core.AppSigningException
import com.hongguotv.core.AppSigningInput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativePlaybackSignerTest {
    private val preferences get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("native_signing_test", Context.MODE_PRIVATE)

    @Before fun clear() { preferences.edit().clear().commit() }

    private fun input(device: String = "synthetic-device", generation: String? = "synthetic-generation") =
        AppSigningInput("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/?_rticket=1",
            "POST", linkedMapOf("Cookie" to listOf("sessionid=synthetic-cookie"),
                "X-Tt-Token" to listOf("synthetic-token"), "x-ss-stub" to listOf("synthetic-stub")),
            byteArrayOf(1, 2, 3), AppSigningContext(mapOf("device_id" to device, "iid" to "synthetic-install"), generation))

    private open class Engine : NativeSigningEngine {
        val events = mutableListOf<String>()
        var headers: Map<String, List<String>>? = null
        var bound = ""
        override fun initialize() { events += "init" }
        override fun bind(deviceId: String, installId: String) { bound = deviceId; events += "bind" }
        override fun report(event: String) { events += event }
        override fun sign(url: String, headers: Map<String, List<String>>): Map<String, String> {
            this.headers = headers
            events += "sign"
            return mapOf("X-Khronos" to "1", "X-Helios" to "synthetic-h", "X-Medusa" to "synthetic-m")
        }
    }

    @Test fun preservesFinalHeadersAndReportsInOrder() {
        val engine = Engine()
        val request = input()
        val result = NativePlaybackSigner(NativeSigningRuntime(engine, preferences)).sign(request)
        assertEquals(request.url, result.url)
        assertEquals(request.headers, engine.headers)
        assertEquals(listOf("init", "first_time", "bind", "did-iid-update", "login", "sign"), engine.events)
        assertEquals(setOf("x-khronos", "x-helios", "x-medusa"), result.headers.keys)
    }

    @Test fun processRestartRebindsButDoesNotRepeatPersistedEvents() {
        NativeSigningRuntime(Engine(), preferences).sign(input())
        val engine = Engine()
        val runtime = NativeSigningRuntime(engine, preferences)
        runtime.sign(input())
        runtime.sign(input(generation = null))
        runtime.sign(input(generation = "new-generation"))
        assertEquals(0, engine.events.count { it == "first_time" })
        assertEquals(1, engine.events.count { it == "bind" })
        assertEquals(1, engine.events.count { it == "login" })
        assertFalse(engine.events.contains("logout"))
        assertFalse(preferences.all.values.any { it == "new-generation" })
    }

    @Test fun failedEventIsRetriedAndNativeExceptionCannotExposeCredentials() {
        var fail = true
        val engine = object : Engine() {
            override fun report(event: String) {
                super.report(event)
                if (event == "first_time" && fail) throw IllegalStateException("Cookie=secret; https://secret")
            }
        }
        val runtime = NativeSigningRuntime(engine, preferences)
        val error = runCatching { runtime.sign(input()) }.exceptionOrNull()
        assertTrue(error is AppSigningException)
        assertFalse(error!!.message.orEmpty().contains("secret"))
        assertNull(error.cause)
        assertFalse(preferences.getBoolean("first_time_reported", false))
        fail = false
        runtime.sign(input())
        assertEquals(2, engine.events.count { it == "first_time" })
        assertEquals(1, engine.events.count { it == "sign" })
    }

    @Test fun bindingCannotChangeInsideAnotherRequestsSignature() {
        val active = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = object : Engine() {
            override fun sign(url: String, headers: Map<String, List<String>>): Map<String, String> {
                assertEquals(1, active.incrementAndGet())
                val before = bound
                if (before == "one") { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
                assertEquals(before, bound)
                active.decrementAndGet()
                return super.sign(url, headers)
            }
        }
        val runtime = NativeSigningRuntime(engine, preferences)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit { runtime.sign(input("one", null)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = pool.submit { runtime.sign(input("two", null)) }
            release.countDown()
            first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS)
            assertEquals(2, engine.events.count { it == "did-iid-update" })
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun rejectsIncompleteSignatureWithoutFallback() {
        val engine = object : Engine() {
            override fun sign(url: String, headers: Map<String, List<String>>) = mapOf("x-khronos" to "1")
        }
        assertTrue(runCatching { NativeSigningRuntime(engine, preferences).sign(input()) }.exceptionOrNull()
            is AppSigningException)
    }
}
