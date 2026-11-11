// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.Protocol
import org.junit.Assert.*
import org.junit.Test

class PlaybackRequestTraceTest {
    @Test fun recordsPreferredRejectionAndFallbackWithoutSensitiveInputs() {
        val trace = PlaybackRequestTrace()
        trace.response(PlaybackRequestTrace.Stage.GET, PlayRetryPlan.host(0), 200, 0, Protocol.HTTP_2)
        trace.response(PlaybackRequestTrace.Stage.POST, PlayRetryPlan.host(1), 503, null, Protocol.HTTP_1_1)
        trace.failed(PlaybackRequestTrace.Stage.POST, "private-host.test", IOException("cookie=secret https://private.test/?device_id=123"))
        assertEquals("G:b=200/0B/h2 P:hl=503/?B/h1 P:other=io", trace.summary())
        assertFalse(trace.summary().contains("secret"))
        assertFalse(trace.summary().contains("private"))
    }

    @Test fun includesOnlyNumericBusinessCodeAndCapsOneRound() {
        val trace = PlaybackRequestTrace()
        trace.response(PlaybackRequestTrace.Stage.GET, PlayRetryPlan.host(0), 200, 42, Protocol.HTTP_2)
        trace.rejected(100103)
        repeat(8) { trace.failed(PlaybackRequestTrace.Stage.POST, PlayRetryPlan.host(0), SocketTimeoutException("private")) }
        assertEquals(5, trace.summary().split(" ").size)
        assertTrue(trace.summary().startsWith("G:b=200/42B/h2/c100103"))
        assertTrue(trace.summary().endsWith("P:b=timeout"))
    }
}
