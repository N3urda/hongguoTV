// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class ViewingPreferencesTest {
    @Test fun crossSeasonAutoplayIsOptInAndPersistsDecodedChoice() {
        assertFalse(ViewingPreferences.decode(emptyMap<String, Any>()).seasonAutoplay)
        assertTrue(ViewingPreferences.decode(mapOf("seasonAutoplay" to true)).seasonAutoplay)
        assertFalse(ViewingPreferences.decode(mapOf("seasonAutoplay" to "true")).seasonAutoplay)
    }
    @Test fun oldInstallKeepsHomeAndAutoplay() {
        val p = ViewingPreferences.decode(emptyMap<String, Any>())
        assertEquals(0, p.initialTab())
        assertTrue(p.homeAutoplay)
        assertEquals(1, p.clockMode)
    }
    @Test fun everyNavigationTabCanBeSelectedDirectly() {
        for (tab in 0..6) {
            assertEquals(tab, ViewingPreferences.decode(mapOf("startupTab" to tab)).initialTab())
        }
    }
    @Test fun previousPreviewHistoryChoiceIsPreservedAndRememberChoiceFallsBackHome() {
        assertEquals(4, ViewingPreferences.decode(mapOf("startupPage" to 1)).initialTab())
        assertEquals(0, ViewingPreferences.decode(mapOf("startupPage" to 2, "lastTab" to 3)).initialTab())
        assertEquals(1, ViewingPreferences.decode(mapOf("startupTab" to 1, "startupPage" to 1)).initialTab())
    }
    @Test fun invalidStorageFallsBackWithoutBreakingStartup() {
        val p = ViewingPreferences.decode(mapOf("startupTab" to 8, "clockMode" to -1, "homeAutoplay" to "false"))
        assertEquals(0, p.initialTab())
        assertEquals(1, p.clockMode)
        assertTrue(p.homeAutoplay)
    }
}
