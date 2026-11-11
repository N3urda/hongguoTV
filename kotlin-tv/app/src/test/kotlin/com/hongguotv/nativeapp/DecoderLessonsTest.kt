package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class DecoderLessonsTest {
    @Test
    fun remembersExactCodecAndSize() {
        val lessons = DecoderLessons()
        lessons.remember("hvc1", 1080, 1920)
        assertTrue(lessons.fails("hvc1", 1080, 1920))
        assertFalse(lessons.fails("hvc1", 1920, 1080)) // landscape unaffected
        assertFalse(lessons.fails("avc1", 1080, 1920)) // other codec unaffected
    }

    @Test
    fun codecNamesNormalizeCaseAndWhitespace() {
        val lessons = DecoderLessons()
        assertEquals("hvc1|1080|1920", lessons.remember(" HVC1 ", 1080, 1920))
        assertTrue(lessons.fails("hvc1", 1080, 1920))
    }

    @Test
    fun sizelessVerdictCoversWholeCodec() {
        val lessons = DecoderLessons()
        lessons.remember("bytevc1", null, null)
        assertTrue(lessons.fails("bytevc1", 1920, 1080))
        assertTrue(lessons.fails("bytevc1", null, null))
    }

    @Test
    fun blankCodecIsIgnored() {
        val lessons = DecoderLessons()
        assertNull(lessons.remember("", 1080, 1920))
        assertFalse(lessons.fails("", 1080, 1920))
    }

    @Test
    fun snapshotRestoresAndResetClears() {
        val lessons = DecoderLessons()
        lessons.remember("avc1", 1080, 1920)
        lessons.remember("h264", null, null)
        val revived = DecoderLessons().also { it.restore(lessons.keys()) }
        assertTrue(revived.fails("avc1", 1080, 1920))
        assertTrue(revived.fails("h264", 720, 1280))
        revived.reset()
        assertEquals(0, revived.size())
        assertFalse(revived.fails("avc1", 1080, 1920))
    }
}
