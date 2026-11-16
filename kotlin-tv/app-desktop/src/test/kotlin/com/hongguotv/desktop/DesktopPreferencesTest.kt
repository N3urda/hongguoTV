package com.hongguotv.desktop

import com.hongguotv.core.*
import java.nio.file.Files
import kotlin.test.*

class DesktopPreferencesTest {
    @Test fun tvBackupMergesLaterAndPreservesNewerLocalProgress() {
        val directory = Files.createTempDirectory("desktop-backup-test")
        try {
            val file = directory.resolve("library.json")
            val library = DesktopLibrary(file)
            val series = Series("123456", "剧集")
            val progress = BackupProgress(series, "654321", 0, 20000, 60000, false, 200)
            library.remember(progress)
            val backup = LibraryBackup.encode(BackupData(listOf(series), listOf(progress.copy(position = 1000, updatedAt = 100)), emptySet(), emptyList(), BackupSettings(), listOf(series)))
            library.importBackup(backup)
            val reopened = DesktopLibrary(file)
            assertEquals(20000L, reopened.history[series.id]?.position)
            assertEquals(series.id, reopened.later.values.single().id)
            val exported = LibraryBackup.decode(reopened.exportBackup())
            assertEquals(20000L, exported.history.single().position)
            assertEquals(series.id, exported.favorites.single().id)
            val before = Files.readString(file)
            assertFails { reopened.importBackup("{broken}") }
            assertEquals(before, Files.readString(file))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun remoteDanmakuCannotExpandMpvPropertiesOrInjectControls() {
        val value = DesktopDanmaku.safeText("\u001b" + '$' + "{path} {\\b1}\nhello")
        assertFalse(value.contains('$'))
        assertFalse(value.contains('{'))
        assertFalse(value.contains('\\'))
        assertFalse(value.contains('\n'))
        assertFalse(value.contains('\u001b'))
        assertEquals(180, DesktopDanmaku.safeText("x".repeat(1000)).length)
    }

    @Test fun preferencesSurviveRestartWithoutReplacingLibrary() {
        val directory = Files.createTempDirectory("desktop-settings-test")
        try {
            val file = directory.resolve("library.json")
            val first = DesktopLibrary(file)
            val series = Series("123456", "保留收藏")
            first.toggle(series)
            first.preference("quality", 720)
            first.preference("speed", 1.25)
            first.preference("autoNext", false)
            val reopened = DesktopLibrary(file)
            assertEquals(series, reopened.favorites[series.id])
            assertEquals("720", reopened.preference("quality", "1080"))
            assertEquals("1.25", reopened.preference("speed", "1.0"))
            assertEquals("false", reopened.preference("autoNext", "true"))
        } finally { directory.toFile().deleteRecursively() }
    }
}
