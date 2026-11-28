package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class VideoTracksTest {
    private fun u16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())

    private fun u32(value: Long) =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    private fun box(type: String, payload: ByteArray = ByteArray(0)): ByteArray {
        val head = u32(payload.size + 8L) + type.toByteArray(Charsets.US_ASCII)
        return if (payload.isEmpty()) head else head + payload
    }

    /** VisualSampleEntry: coded width/height are 16-bit fields at offsets 32/34. */
    private fun sampleEntry(fourcc: String, width: Int, height: Int, config: ByteArray? = null): ByteArray {
        val body =
            ByteArray(24) + u16(width) + u16(height) + u16(0x0018) + u16(0xFFFF) + u32(0) + u32(0)
        return box(fourcc, if (config == null) body else body.copyOf(78) + box("hvcC", config))
    }

    private fun videoTrak(fourcc: String, width: Int, height: Int, config: ByteArray? = null): ByteArray {
        val stsd = box("stsd", u32(0) + u32(1) + sampleEntry(fourcc, width, height, config))
        val stbl = box("stbl", stsd)
        val minf = box("minf", box("vmhd") + stbl)
        return box("trak", box("tkhd") + box("mdia", box("hdlr") + minf))
    }

    private fun audioTrak(): ByteArray {
        val stsd = box("stsd", u32(0) + u32(1) + box("mp4a"))
        val minf = box("minf", box("smhd") + box("stbl", stsd))
        return box("trak", box("tkhd") + box("mdia", box("hdlr") + minf))
    }

    private fun header(vararg traks: ByteArray): ByteArray {
        val ftyp = box("ftyp", "isom".toByteArray(Charsets.US_ASCII))
        return ftyp + box("moov", traks.reduce { acc, bytes -> acc + bytes })
    }

    @Test
    fun readsCodedSizeFromVideoTrack() {
        val track = VideoTracks.videoTrack(header(videoTrak("avc1", 1920, 1080)))!!
        assertEquals("avc1", track.fourcc)
        assertEquals(1920, track.width)
        assertEquals(1080, track.height)
    }

    @Test
    fun portraitCodedSizeIsWidthByHeight() {
        val track = VideoTracks.videoTrack(header(videoTrak("hvc1", 1080, 1920)))!!
        assertEquals(1080, track.width)
        assertEquals(1920, track.height)
    }

    @Test
    fun audioTracksAreSkipped() {
        val track = VideoTracks.videoTrack(header(audioTrak(), videoTrak("avc1", 720, 1280)))!!
        assertEquals("avc1", track.fourcc)
        assertEquals(720, track.width)
        assertEquals(1280, track.height)
    }

    @Test
    fun headerWithoutVideoTrackReturnsNull() {
        assertNull(VideoTracks.videoTrack(header(audioTrak())))
        assertNull(VideoTracks.videoTrack(box("ftyp", "isom".toByteArray())))
    }

    @Test fun readsTheDeclaredHevcLevelWithoutChangingTheBitstream() {
        val config = ByteArray(23).apply { this[0] = 1; this[1] = 1; this[2] = 0x60; this[12] = 186.toByte() }
        val bytes = header(videoTrak("hvc1", 480, 854, config))
        val original = bytes.copyOf()
        assertEquals(VideoTracks.Track("hvc1", 480, 854, "hvc1.1.6.L186"), VideoTracks.videoTrack(bytes))
        assertArrayEquals(original, bytes)
    }

    @Test fun preservesProfileSpaceTierAndNonzeroConstraints() {
        val config = ByteArray(23).apply {
            this[0] = 1; this[1] = 0xa2.toByte(); this[2] = 0x60
            this[6] = 0x80.toByte(); this[12] = 156.toByte()
        }
        assertEquals("hev1.B2.6.H156.80", VideoTracks.videoTrack(header(videoTrak("hev1", 720, 1280, config)))!!.codecs)
    }

    @Test fun malformedConfigurationDoesNotInventACompatibleLevel() {
        for (config in listOf(ByteArray(12).apply { this[0] = 1 }, ByteArray(23).apply { this[0] = 2 })) {
            val track = VideoTracks.videoTrack(header(videoTrak("hvc1", 360, 642, config)))!!
            assertNull(track.codecs)
            assertEquals(360, track.width)
        }
    }
}
