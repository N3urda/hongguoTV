package com.hongguotv.nativeapp

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 26])
@LooperMode(LooperMode.Mode.PAUSED)
class GradualPreloaderTest {
    private fun advance(ms: Long) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)
    private fun finished(w: GradualPreloader) {
        repeat(200) { ShadowLooper.idleMainLooper(); if (!w.busy) return; Thread.sleep(5) }
        fail("worker did not finish")
    }
    @Test fun startupDelaySerialSpacingAndDeduplication() {
        val w = GradualPreloader({ true }, 8000, 2500)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val second = CountDownLatch(1)
        try {
            w.enqueue("a") { started.countDown(); release.await(2, TimeUnit.SECONDS) }
            w.enqueue("a") { fail("duplicate") }
            w.enqueue("b") { second.countDown() }
            w.resume(); advance(7999)
            assertEquals(1L, started.count)
            advance(1); assertTrue(started.await(2, TimeUnit.SECONDS))
            advance(2500); assertEquals(1L, second.count)
            release.countDown(); finished(w)
            advance(2499); assertEquals(1L, second.count)
            advance(1); assertTrue(second.await(2, TimeUnit.SECONDS)); finished(w)
        } finally { release.countDown(); w.close() }
    }
    @Test fun inputAndBackgroundDeferWorkAndFailureRetriesAreBounded() {
        var allowed = false
        val w = GradualPreloader({ allowed }, 8000, 2500)
        var attempts = 0
        try {
            w.enqueue("failing") { attempts++; error("offline") }
            w.resume(); advance(8000); assertEquals(0, attempts)
            allowed = true; w.yieldToUser(); advance(2499); assertEquals(0, attempts)
            w.pause(); advance(10000); assertEquals(0, attempts)
            w.resume(); advance(8000); finished(w); assertEquals(1, attempts)
            advance(2500); finished(w); assertEquals(2, attempts)
            advance(10000); assertEquals(2, attempts)
        } finally { w.close() }
    }
}
