// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

/** Golden-vector regression for the device registration crypto, matching the
 * vectors shipped with fqapp (rust/testdata/device_vectors.json). */
class DeviceCryptoTest {
    @Test fun reverseHexMatchesGoVectors() {
        assertEquals("34024f6df4e6fb640000000000000000", DeviceRegistrar.reverseHex("7276663560427471412"))
        assertEquals("40e20100000000000000000000000000", DeviceRegistrar.reverseHex("123456"))
        assertThrows(IllegalArgumentException::class.java) { DeviceRegistrar.reverseHex("abc") }
        assertThrows(IllegalArgumentException::class.java) { DeviceRegistrar.reverseHex("-1") }
    }

    @Test fun registerKeyRequestMatchesGoVector() {
        val content = DeviceRegistrar.encryptRegisterKeyRequest("7276663560427471412", "0123456789abcdef".toByteArray())
        assertEquals("MDEyMzQ1Njc4OWFiY2RlZvVb0lAf40euDsfvj1ILUK9jAhLCs8plve4qa3UqkHsj", content)
    }

    @Test fun registerKeyResponseDecrypts() {
        val plain = DeviceRegistrar.decryptRegisterKey("ABEiM0RVZneImaq7zN3u//NnA1PPLklvy0VWJ2Qnd45A/zarUCjsToTdr+Zm34ab")
        assertEquals("SECRETKEY_16byte", String(plain, Charsets.US_ASCII))
        assertEquals("5345435245544b45595f313662797465", plain.hex())
    }

    @Test fun ttEncryptMatchesGoVector() {
        val random32 = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val out = DeviceRegistrar.ttEncrypt("hello-tt-encrypt-payload".toByteArray(), random32)
        assertEquals("746305100000000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f9c2938fa4a012faf0ecd9d31020e00f9748841c73838f76c10a9f9084bcfc717bc3cb6085a81a491f6e9b3d47a574b96bd818707ca911fc89bfad9a68edc174aa2f2dbc4ea60415c1e06b7525320a6451dbebd0a552028b5e70653d538c39842", out.hex())
    }

    @Test fun goQueryEscapeMatchesUrlValuesEncode() {
        assertEquals("a=b&c=d_e~f", DeviceRegistrar.sortedQuery(listOf("c" to "d_e~f", "a" to "b")))
        assertEquals("q=a+b", DeviceRegistrar.sortedQuery(listOf("q" to "a b")))
        assertEquals("k=p%3D%25%26", DeviceRegistrar.sortedQuery(listOf("k" to "p=%&")))
    }

    @Test fun playerDeviceRoundTripsJson() {
        val device = PlayerDevice("7000000000001234567", "7000000000007654321", "cdid-uuid", "0123456789abcdef", "ABCD")
        val restored = PlayerDevice.fromJson(device.toJson())
        assertEquals(device, restored)
        assertNull(PlayerDevice.fromJson("{}"))
        assertNull(PlayerDevice.fromJson("not json"))
    }
}
