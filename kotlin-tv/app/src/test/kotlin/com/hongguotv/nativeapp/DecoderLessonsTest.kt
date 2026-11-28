package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class DecoderLessonsTest {
    private val scope = "101/AUTOMATIC"
    @Test
    fun remembersExactCodecAndSize() {
        val lessons = DecoderLessons()
        lessons.remember("hvc1", 1080, 1920, scope)
        assertTrue(lessons.fails("hvc1", 1080, 1920, scope))
        assertFalse(lessons.fails("hvc1", 1920, 1080, scope))
        assertFalse(lessons.fails("avc1", 1080, 1920, scope))
        assertFalse(lessons.fails("hvc1", 1080, 1920, "102/AUTOMATIC"))
        assertFalse(lessons.fails("hvc1", 1080, 1920, "101/SOFTWARE_ONLY"))
    }

    @Test
    fun codecNamesNormalizeCaseAndWhitespace() {
        val lessons = DecoderLessons()
        assertEquals("$scope|hevc|1080|1920", lessons.remember(" HVC1 ", 1080, 1920, scope))
        for (codec in listOf("hvc1", "hev1", "h265", "bytevc1", "hevc"))
            assertTrue(lessons.fails(codec, 1080, 1920, scope))
    }

    @Test
    fun incompleteVerdictCannotBanAnEntireCodec() {
        val lessons = DecoderLessons()
        assertNull(lessons.remember("bytevc1", null, null, scope))
        assertNull(lessons.remember("bytevc1", 1080, 1920, null))
        assertFalse(lessons.fails("bytevc1", 1920, 1080, scope))
        assertFalse(lessons.fails("bytevc1", null, null, scope))
    }

    @Test
    fun blankCodecIsIgnored() {
        val lessons = DecoderLessons()
        assertNull(lessons.remember("", 1080, 1920, scope))
        assertFalse(lessons.fails("", 1080, 1920, scope))
    }

    @Test
    fun snapshotRestoresAndResetClears() {
        val lessons = DecoderLessons()
        lessons.remember("avc1", 1080, 1920, scope)
        val revived = DecoderLessons().also { it.restore(lessons.keys() + setOf("hevc", "hvc1|1080|1920")) }
        assertTrue(revived.fails("h264", 1080, 1920, scope))
        assertFalse(revived.fails("hevc", 1080, 1920, scope))
        revived.reset()
        assertEquals(0, revived.size())
        assertFalse(revived.fails("avc1", 1080, 1920, scope))
    }
}
