package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class WarmPageCacheTest {
    @Test fun oneUseExpirationAndBoundedEviction() {
        var now = 0L
        val cache = WarmPageCache<String>(2, 100) { now }
        cache.put("a", "first"); cache.put("b", "second"); cache.put("c", "third")
        assertNull(cache.take("a"))
        assertEquals("second", cache.take("b"))
        assertNull("A manual refresh must not reuse warmed data", cache.take("b"))
        now = 100
        assertNull(cache.take("c"))
    }
    @Test fun cancelledPreloadCannotPublish() {
        val cache = WarmPageCache<String>(2)
        val scope = RequestScope()
        scope.run {
            scope.cancel()
            assertThrows(java.io.IOException::class.java) { cache.put("a", "late") }
        }
        assertNull(cache.take("a"))
    }
}
