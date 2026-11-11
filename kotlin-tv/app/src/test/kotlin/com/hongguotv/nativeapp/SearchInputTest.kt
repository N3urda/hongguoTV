package com.hongguotv.nativeapp

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SearchInputTest {
    @Test
    fun privateV4AddressesExcludeLoopbackIpv6AndPublicAndRemoveDuplicates() {
        val values =
            listOf(
                "127.0.0.1",
                "192.168.1.20",
                "10.0.0.2",
                "192.168.1.20",
                "8.8.8.8",
                "169.254.1.1",
                "::1",
                "fd00::1",
            )
        assertEquals(
            listOf("192.168.1.20", "10.0.0.2"),
            LanInputAddresses.privateV4(values.map(InetAddress::getByName)),
        )
    }
}
