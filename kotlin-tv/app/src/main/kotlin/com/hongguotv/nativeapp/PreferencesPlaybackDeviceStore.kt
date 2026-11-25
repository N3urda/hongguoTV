// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.SharedPreferences
import android.os.Build
import com.hongguotv.core.PlaybackDeviceStore
import java.io.IOException

/** Private installation state; separate from the legacy player device and library backups. */
internal class PreferencesPlaybackDeviceStore(private val preferences: () -> SharedPreferences) : PlaybackDeviceStore {
    override fun load(): String? = preferences().getString("context-v1", null)
    override fun save(value: String) {
        if (!preferences().edit().putString("context-v1", value).commit()) throw IOException("保存播放设备失败，请检查存储空间")
    }
}

internal fun androidPlaybackDevice(context: android.content.Context): Map<String, String> = mapOf(
    "device_type" to Build.MODEL, "device_brand" to Build.BRAND, "device_manufacturer" to Build.MANUFACTURER,
    "os_version" to Build.VERSION.RELEASE, "os_api" to Build.VERSION.SDK_INT.toString(),
    "host_abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "armeabi-v7a"), "rom_version" to Build.DISPLAY,
    "dpi" to context.resources.displayMetrics.densityDpi.toString(),
    "resolution" to "${context.resources.displayMetrics.widthPixels}*${context.resources.displayMetrics.heightPixels}",
)
