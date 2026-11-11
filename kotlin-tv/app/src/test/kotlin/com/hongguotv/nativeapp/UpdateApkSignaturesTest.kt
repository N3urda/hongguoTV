// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 29])
@Suppress("DEPRECATION")
class UpdateApkSignaturesTest {
    private fun info(vararg certificates: String) = PackageInfo().apply {
        signatures = certificates.map { Signature(it) }.toTypedArray()
    }

    private fun modern(info: PackageInfo, vararg certificates: String): PackageInfo {
        info.signingInfo = SigningInfo().apply {
            shadowOf(this).setSignatures(certificates.map { Signature(it) }.toTypedArray())
        }
        return info
    }

    private fun rejected(installed: PackageInfo, candidate: PackageInfo, message: String) {
        val error = assertThrows(IOException::class.java) { UpdateApkSignatures.verify(installed, candidate) }
        assertTrue(error.message.orEmpty().contains(message))
    }

    @Test fun archiveFlagsAlwaysRequestLegacyCertificateCollection() {
        val flags = UpdateApkSignatures.flags()
        assertTrue(flags and PackageManager.GET_SIGNATURES != 0)
        assertEquals(Build.VERSION.SDK_INT >= 28, flags and PackageManager.GET_SIGNING_CERTIFICATES != 0)
    }

    @Test fun legacyOnlyArchiveCanMatchInstalledCertificate() {
        val installed = info("aabb")
        if (Build.VERSION.SDK_INT >= 28) modern(installed, "aabb")
        UpdateApkSignatures.verify(installed, info("aabb"))
    }

    @Test fun missingCertificatesNeverPass() {
        rejected(info("aabb"), info(), "无法读取")
        rejected(info(), info("aabb"), "无法读取")
        rejected(info(), info(), "无法读取")
    }

    @Test fun differentOrPartialSignerSetsNeverPass() {
        rejected(info("aabb"), info("ccdd"), "不匹配")
        rejected(info("aabb", "ccdd"), info("aabb"), "不匹配")
        UpdateApkSignatures.verify(info("aabb", "ccdd"), info("ccdd", "aabb"))
    }

    @Test fun modernMismatchCannotBeHiddenByMatchingLegacyField() {
        if (Build.VERSION.SDK_INT < 28) return
        rejected(modern(info("aabb"), "aabb"), modern(info("aabb"), "ccdd"), "不匹配")
        UpdateApkSignatures.verify(modern(info(), "aabb"), modern(info(), "aabb"))
    }
}
