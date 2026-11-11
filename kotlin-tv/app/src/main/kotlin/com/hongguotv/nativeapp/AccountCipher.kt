// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The stored format is IV + authenticated ciphertext; legacy Android 5 used a 16-byte IV. */
internal object AccountCipher {
    fun encrypt(plain: ByteArray, key: SecretKey, legacyKey: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (legacyKey) {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        } else {
            // Android Keystore generates its own IV; caller-supplied IVs are prohibited.
            cipher.init(Cipher.ENCRYPT_MODE, key)
        }
        check(cipher.iv.size == 12) { "账号加密格式不兼容" }
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(bytes: ByteArray, key: SecretKey, legacyKey: Boolean): ByteArray {
        require(bytes.size >= 28) { "登录态数据不完整" }
        fun open(ivSize: Int): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, ivSize)))
            return cipher.doFinal(bytes.copyOfRange(ivSize, bytes.size))
        }
        return try { open(12) } catch (error: GeneralSecurityException) {
            if (!legacyKey || bytes.size < 32) throw error
            // Authentication still has to succeed; never accept unauthenticated plaintext.
            open(16)
        }
    }
}
