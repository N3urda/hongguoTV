package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class LegacyBase64Test {
    @Test
    fun protocolAndQrBytesDecodeWithPaddingAndWithoutIt() {
        assertEquals("f", String(decode64("Zg==")))
        assertEquals("fo", String(decode64("Zm8")))
        assertEquals("foo", String(decode64("Zm9v")))
        val binary = byteArrayOf(0xfb.toByte(), 0xff.toByte())
        assertArrayEquals(binary, decode64("-_8"))
        assertEquals("+/8=", binary.base64())
        assertThrows(IllegalArgumentException::class.java) { decode64("a") }
    }
}
