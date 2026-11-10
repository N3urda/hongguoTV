// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.SharedPreferences
import com.hongguotv.core.PlayerDeviceStore

/** Keeps the registered upstream player device across app launches. */
class PreferencesPlayerDeviceStore(private val prefs: SharedPreferences): PlayerDeviceStore {
    override fun load(): String? = prefs.getString(KEY, null)
    override fun save(value: String?) = prefs.edit().apply {
        if (value == null) remove(KEY) else putString(KEY, value)
    }.apply()
    private companion object { const val KEY = "player-device" }
}
