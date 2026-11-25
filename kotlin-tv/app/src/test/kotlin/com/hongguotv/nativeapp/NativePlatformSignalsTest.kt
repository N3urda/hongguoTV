// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import com.hongguotv.core.AppSigningException
import java.security.Provider
import java.security.Security
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativePlatformSignalsTest {
    private class Host(private val available: Boolean) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var binds = 0
        var unbinds = 0
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            assertEquals("vendor.sdk", service.component!!.packageName)
            assertEquals(Context.BIND_AUTO_CREATE, flags)
            binds++
            return available
        }
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }

    private fun signals(host: Context, providerDevice: Boolean = false) = NativePlatformSignals(host) { key, _ ->
        when (key) {
            "d4fb9b" -> if (providerDevice) Build.MANUFACTURER else "different-vendor"
            "926bc7", "a24d85", "492d4b" -> "different-vendor"
            "99a783" -> "nonexistent.sdk.test.property"
            "692eec" -> "unknown"
            "8f9dc0" -> "vendor.sdk"
            "2723b9" -> "vendor.sdk.ProofService"
            "3da5ca" -> "vendor.sdk.PROOF"
            "42383f" -> "vendor.sdk.MissingProvider"
            "96265e" -> "install"
            "c66d58" -> "HongguoSdkTestProvider"
            "a01a55" -> "binder-unavailable"
            "1815ed" -> "provider-unavailable"
            else -> error("Unexpected constant in capability test")
        }
    }

    @Test fun reportsMissingBinderOnlyAfterRealBindFailsAndCachesTheResult() {
        val host = Host(false)
        val signals = signals(host)
        assertEquals("binder-unavailable", signals.value(0x10000012))
        assertEquals("binder-unavailable", signals.value(0x10000012))
        assertNull(signals.value(0x1000000c))
        assertNull(signals.value(0x10000010))
        assertEquals(1, host.binds)
        assertEquals(0, host.unbinds)
    }

    @Test fun existingBinderIsReleasedAndCannotBeReportedAsUnavailable() {
        val host = Host(true)
        assertThrows(AppSigningException::class.java) { signals(host).value(0x10000012) }
        assertEquals(1, host.binds)
        assertEquals(1, host.unbinds)
    }

    @Test fun reportsMissingVendorProviderWithoutProbingUnrelatedBinder() {
        val host = Host(false)
        assertEquals("provider-unavailable", signals(host, providerDevice = true).value(0x10000012))
        assertEquals(0, host.binds)
    }

    @Test fun existingVendorProviderCannotBeReplacedWithMissingStatus() {
        val provider = object : Provider("HongguoSdkTestProvider", 1.0, "Synthetic test capability") { }
        Security.addProvider(provider)
        try {
            assertThrows(AppSigningException::class.java) { signals(Host(false), providerDevice = true).value(0x10000012) }
        } finally { Security.removeProvider(provider.name) }
    }
}
