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
    private fun sampleEntry(fourcc: String, width: Int, height: Int): ByteArray {
        val body =
            ByteArray(24) + u16(width) + u16(height) + u16(0x0018) + u16(0xFFFF) + u32(0) + u32(0)
        return box(fourcc, body)
    }

    private fun videoTrak(fourcc: String, width: Int, height: Int): ByteArray {
        val stsd = box("stsd", u32(0) + u32(1) + sampleEntry(fourcc, width, height))
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
}
