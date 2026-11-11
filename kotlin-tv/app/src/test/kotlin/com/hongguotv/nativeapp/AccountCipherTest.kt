package com.hongguotv.nativeapp

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.*
import org.junit.Test

class AccountCipherTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val plain = "synthetic account record".toByteArray()

    @Test fun android5WritesFixedNonceAndRestoresAcrossCipherInstances() {
        val first = AccountCipher.encrypt(plain, key, true)
        val second = AccountCipher.encrypt(plain, key, true)
        assertEquals(12 + plain.size + 16, first.size)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(plain, AccountCipher.decrypt(first, key, true))
        assertArrayEquals(plain, AccountCipher.decrypt(second, key, true))
    }

    @Test fun oldAndroid5SixteenByteNonceRecordRemainsReadable() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(16) { it.toByte() }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val oldRecord = iv + cipher.doFinal(plain)
        assertArrayEquals(plain, AccountCipher.decrypt(oldRecord, key, true))
        assertThrows(GeneralSecurityException::class.java) { AccountCipher.decrypt(oldRecord, key, false) }
    }

    @Test fun changedCiphertextAndWrongKeyNeverReturnPlaintext() {
        val record = AccountCipher.encrypt(plain, key, true)
        val changed = record.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { AccountCipher.decrypt(changed, key, true) }
        val wrong = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(GeneralSecurityException::class.java) { AccountCipher.decrypt(record, wrong, true) }
    }
}
