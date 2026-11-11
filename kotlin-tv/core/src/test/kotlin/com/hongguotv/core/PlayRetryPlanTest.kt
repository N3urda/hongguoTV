// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayRetryPlanTest {
    @Test
    fun `initial candidates contain one single-video and three distinct batch routes`() {
        val routes = PlaybackRoutes().plan(1000, false)
        assertEquals(4, routes.size)
        assertEquals(4, routes.distinct().size)
        assertTrue(routes.first().single)
        assertEquals(listOf("b", "hl", "lf"), routes.drop(1).map { PlayRetryPlan.tag(it.host) })
    }

    @Test
    fun `empty responses and connection failures are temporary but malformed replies are not`() {
        assertTrue(PlayRetryPlan.temporary(EmptyPlaybackResponseException()))
        assertTrue(PlayRetryPlan.temporary(java.net.SocketTimeoutException()))
        assertTrue(
            PlayRetryPlan.temporary(ContentHttpException(503)),
        )
        assertFalse(PlayRetryPlan.temporary(ContentHttpException(404)))
        assertFalse(PlayRetryPlan.temporary(java.io.EOFException("视频分段提前断开")))
    }

}
