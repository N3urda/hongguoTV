// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context

/** Only UI preferences: never changes or migrates either library's records. */
internal class LibrarySources(context: Context) {
    private val prefs = context.getSharedPreferences("library_sources", Context.MODE_PRIVATE)
    var accountFavorites: Boolean
        get() = prefs.getBoolean("account_favorites", false)
        set(value) {
            prefs.edit().putBoolean("account_favorites", value).apply()
        }

    var accountHistory: Boolean
        get() = prefs.getBoolean("account_history", false)
        set(value) {
            prefs.edit().putBoolean("account_history", value).apply()
        }
}
