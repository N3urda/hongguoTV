package com.hongguotv.nativeapp

import com.hongguotv.core.DeviceRegister
import com.hongguotv.core.PlaybackDeviceSession

internal fun testPlaybackDeviceSession() = PlaybackDeviceSession {
    DeviceRegister.Registered("333", "444")
}
