// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * Reads the coded video size from a decrypted MP4 header. MediaCodec checks the coded dimensions,
 * so a portrait 720P stream is 720x1280 even when displayed differently.
 */
object VideoTracks {
    data class Track(val fourcc: String, val width: Int, val height: Int, val codecs: String? = null)

    fun videoTrack(header: ByteArray): Track? {
        if (header.size < 32 || String(header, 4, 4, Charsets.US_ASCII) != "ftyp") return null
        val moov = MediaCrypto.box(header, header.u32(0).toInt()) ?: return null
        if (moov.type != "moov") return null
        var cursor = moov.body
        while (cursor < moov.end) {
            val trak = MediaCrypto.box(header, cursor, moov.end) ?: break
            cursor = trak.end
            if (trak.type != "trak") continue
            val mdia = MediaCrypto.find(header, "mdia", trak.body, trak.end) ?: continue
            val minf = MediaCrypto.find(header, "minf", mdia.body, mdia.end) ?: continue
            if (MediaCrypto.find(header, "vmhd", minf.body, minf.end) == null)
                continue // audio track
            val stbl = MediaCrypto.find(header, "stbl", minf.body, minf.end) ?: continue
            val stsd = MediaCrypto.find(header, "stsd", stbl.body, stbl.end) ?: continue
            // VisualSampleEntry: coded width/height are 16-bit fields at offsets 32/34.
            val entry = MediaCrypto.box(header, stsd.body + 8, stsd.end) ?: continue
            if (entry.end - entry.start < 36) continue
            val width = (header[entry.start + 32].u() shl 8) or header[entry.start + 33].u()
            val height = (header[entry.start + 34].u() shl 8) or header[entry.start + 35].u()
            if (width <= 0 || height <= 0) continue
            val fourcc = String(header, entry.start + 4, 4, Charsets.US_ASCII)
            val codecs = if (fourcc == "hvc1" || fourcc == "hev1") {
                // VisualSampleEntry has 78 fixed payload bytes before its child boxes.
                MediaCrypto.find(header, "hvcC", entry.start + 86, entry.end)?.let { config ->
                    hevcCodecs(header, config.body, config.end, fourcc)
                }
            } else null
            return Track(fourcc, width, height, codecs)
        }
        return null
    }

    /** RFC 6381 codec string from HEVCDecoderConfigurationRecord; never rewrites the bitstream. */
    private fun hevcCodecs(data: ByteArray, start: Int, end: Int, fourcc: String): String? {
        if (end - start < 23 || data[start].u() != 1) return null
        val profile = data[start + 1].u()
        val space = listOf("", "A", "B", "C")[profile ushr 6]
        val compatibility = Integer.toHexString(Integer.reverse(data.u32(start + 2).toInt())).uppercase()
        val tier = if (profile and 0x20 != 0) "H" else "L"
        val constraints = (6..11).map { data[start + it].u() }.dropLastWhile { it == 0 }
        return buildString {
            append("$fourcc.$space${profile and 31}.$compatibility.$tier${data[start + 12].u()}")
            constraints.forEach { append(".%02X".format(it)) }
        }
    }
}
