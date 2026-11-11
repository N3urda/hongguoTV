// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackRoutesTest {
    @Test fun prefersRecentSuccessAndAvoidsEmptyPathsUntilCooldownEnds() {
        val routes = PlaybackRoutes()
        val initial = routes.plan(1000, false)
        assertEquals(4, initial.size)
        routes.failed(initial[0], 1000, true)
        routes.success(initial[2], 1000)
        val next = routes.plan(1001, false)
        assertEquals(initial[2], next.first())
        assertFalse(next.contains(initial[0]))
        assertTrue(routes.plan(31_001, false).contains(initial[0]))
    }

    @Test fun backgroundStopsWhenAllPathsAreCoolingAndForegroundOnlyProbesOne() {
        val routes = PlaybackRoutes()
        val initial = routes.plan(1000, false)
        initial.forEach { routes.failed(it, 1000, true) }
        assertTrue(routes.exhausted(1001))
        assertTrue(routes.plan(1001, true).isEmpty())
        assertEquals(1, routes.plan(1001, false).size)
        assertEquals(1, PlaybackRoutes().plan(1001, true).size)
    }

    @Test fun timeoutsDoNotMeanEveryPathWasEmptyAndFreshSessionHasNoOldCooldown() {
        val routes = PlaybackRoutes()
        val initial = routes.plan(1000, false)
        initial.forEach { routes.failed(it, 1000, true) }
        routes.failed(initial[1], 1000, false)
        assertFalse(routes.exhausted(1001))
        assertEquals(4, PlaybackRoutes().plan(1001, false).size)
    }
}
