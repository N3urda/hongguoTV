// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.util.Base64
import java.security.MessageDigest

/** Debug-test-only inspection. Returns a field length, never the signature or its device/token data. */
internal object NativeEnvelopeProbe {
    fun f16Length(headers: Map<String, String>): Int? {
        fun header(name: String) = headers.entries.first { it.key.equals(name, true) }.value
        val timestamp = header("x-khronos").toInt()
        val raw = Base64.decode(header("x-medusa"), Base64.DEFAULT)
        require(raw.size in 273..16_384)
        fun word(bytes: ByteArray, offset: Int = 0) = (0..3).fold(0) { a, i -> a or (bytes[offset + i].u() shl (i * 8)) }
        require(word(raw) xor timestamp == 5)
        val constants = Class.forName("com.hongguotv.core.V5Constants")
        val key = constants.getDeclaredMethod("getSignKey").invoke(constants.getField("INSTANCE").get(null)) as ByteArray
        fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes)
        val carrierType = Class.forName("com.hongguotv.core.AesV3")
        val carrier = carrierType.getDeclaredConstructor(ByteArray::class.java, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).newInstance(md5(key.copyOfRange(0, 16)), timestamp, true)
        fun field(name: String) = carrierType.getDeclaredField(name).apply { isAccessible = true }.get(carrier)
        @Suppress("UNCHECKED_CAST") val keys = field("keys") as Array<IntArray>
        val con = field("con") as IntArray
        val order = field("order") as IntArray
        @Suppress("UNCHECKED_CAST") val box = field("box") as List<Int>
        val inverseBox = IntArray(256) { box.indexOf(it) }
        fun inverse(value: ByteArray): ByteArray {
            var rows = Array(4) { i -> IntArray(4) { j -> value[i * 4 + j].u() } }
            fun add(offset: Int) { for (i in 0..3) for (j in 0..3) rows[i][j] = rows[i][j] xor keys[offset + i][con[j]] }
            for (i in 0..3) for (j in 0..3) rows[i][j] = rows[i][j] xor keys[4 + i][j]
            for (round in 2 downTo 1) {
                add(round * 4)
                if (round == 1) {
                    for (col in 0..3) {
                        val a = rows[0][col]; val b = rows[1][col]; val c = rows[2][col]; val d = rows[3][col]
                        rows[0][col] = mul(a, 14) xor mul(b, 11) xor mul(c, 13) xor mul(d, 9)
                        rows[1][col] = mul(a, 9) xor mul(b, 14) xor mul(c, 11) xor mul(d, 13)
                        rows[2][col] = mul(a, 13) xor mul(b, 9) xor mul(c, 14) xor mul(d, 11)
                        rows[3][col] = mul(a, 11) xor mul(b, 13) xor mul(c, 9) xor mul(d, 14)
                    }
                    for (i in 0..3) { val old = rows[i].copyOf(); for (j in 0..3) rows[i][con[j]] = old[j] }
                }
                val flat = rows.flatMap { it.toList() }
                val prior = IntArray(16)
                for (i in 0..15) prior[order[i]] = flat[i]
                val reordered = Array(4) { IntArray(4) }
                for (i in 0..3) for (j in 0..3) reordered[con[i]][j] = inverseBox[prior[i * 4 + j]]
                rows = reordered
            }
            add(0)
            return rows.flatMap { it.toList() }.map { it.toByte() }.toByteArray()
        }
        val packed = raw.copyOfRange(25, raw.size)
        val wire = intArrayOf(6, 5, 3, 4, 2, 7, 1, 0)
        val compact = intArrayOf(1, 7, 3, 6, 2, 0, 4, 5)
        val bits = ByteArray(32)
        for (i in 0 until 31) for (j in 0..7)
            bits[i] = (bits[i].u() or (((packed[i * 8 + j].u() ushr wire[j]) and 1) shl compact[j])).toByte()
        bits[31] = raw[24]
        fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].u() xor b[it].u()).toByte() }
        val plain = xor(inverse(bits.copyOfRange(0, 16)), md5(key.copyOfRange(16, 32))) +
            xor(inverse(bits.copyOfRange(16, 32)), bits.copyOfRange(0, 16))
        require(plain[31].u() == 1)
        for (i in 0 until 31) for (j in 0..7) {
            val at = i * 8 + j
            packed[at] = ((packed[at].u() and (1 shl wire[j]).inv()) or
                (((plain[i].u() ushr compact[j]) and 1) shl wire[j])).toByte()
        }
        val nonce = raw[20].u() or (raw[21].u() shl 8) or (packed[packed.size - 2].u() shl 16) or (packed.last().u() shl 24)
        fun little(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }
        val high = (nonce ushr 16) and 255
        val seed = little((((high shl 11) or (nonce ushr 24)) xor (high ushr 5) xor high).inv())
        val transformed = packed.copyOfRange(9, packed.size - 2)
        for (i in transformed.indices) transformed[i] = (transformed[i].u() xor seed[i.inv() and 3].u()).toByte()
        val encoded = transformed.reversedArray().drop(8).map { it.u() }.toIntArray()
        val hash = Class.forName("com.hongguotv.core.BytesKt").getDeclaredMethod("sm3", ByteArray::class.java)
            .invoke(null, key + little(nonce) + key) as ByteArray
        encoded[0] = ((encoded[0] - encoded.drop(1).sum()) and 255) xor encoded[1]
        encoded[encoded.lastIndex] = encoded.last() xor encoded[encoded.lastIndex - 1]
        for (i in encoded.size - 5 downTo 0)
            encoded[i + 3] = (encoded[i + 3] - (rol(encoded[i + 2], 3) xor encoded[i + 1] xor (i + 3)).inv()) and 255
        val last = encoded.last() xor encoded[encoded.lastIndex - 1]
        val first = (encoded[0] + last + 1) and 255
        encoded[2] = (encoded[2] - ((last - first) xor rol(encoded[1], 3) xor 2)) and 255
        encoded[1] = (encoded[1] - (encoded[0] xor encoded.last() xor 254)) and 255
        encoded[0] = first
        val message = ByteArray(encoded.size) { i ->
            val at = (i * 4) and 28
            var value = (encoded[encoded.lastIndex - i].inv() and 255) xor hash[at].u()
            value = (value - hash[at + 1].u()) and 255
            value = rol(value, 5) xor hash[at + 1].u()
            rol((value.inv() - hash[at].u()) and 255, 4).toByte()
        }
        var index = 0
        fun varint(): Long {
            var value = 0L; var shift = 0
            while (true) {
                require(index < message.size && shift < 70)
                val byte = message[index++].u()
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
                shift += 7
            }
        }
        var length: Int? = null
        var aidMatched = false
        while (index < message.size) {
            val tag = varint().toInt(); require(tag ushr 3 > 0)
            when (tag and 7) {
                0 -> varint()
                1 -> index += 8
                5 -> index += 4
                2 -> {
                    val count = varint().toInt(); require(count >= 0 && index + count <= message.size)
                    if (tag ushr 3 == 4) aidMatched = message.copyOfRange(index, index + count).contentEquals("8662".toByteArray())
                    if (tag ushr 3 == 16) length = count
                    index += count
                }
                else -> error("Invalid native envelope")
            }
            require(index <= message.size)
        }
        require(aidMatched)
        return length
    }

    private fun Byte.u() = toInt() and 255
    private fun rol(value: Int, bits: Int) = ((value shl bits) or (value ushr (8 - bits))) and 255
    private fun mul(value: Int, factor: Int): Int {
        var a = value; var b = factor; var out = 0
        while (b != 0) {
            if (b and 1 != 0) out = out xor a
            a = ((a shl 1) xor if (a and 128 != 0) 0x11b else 0) and 255
            b = b ushr 1
        }
        return out
    }
}
