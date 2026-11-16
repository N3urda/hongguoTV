package com.hongguotv.desktop

import com.hongguotv.core.*
import java.nio.file.Files
import kotlin.test.*

class DesktopLibraryTest {
    @Test fun favoritesAndProgressSurviveRestart() {
        val file = Files.createTempDirectory("hg-library-test").resolve("library.json")
        try {
            val series = Series("123", "测试剧集")
            val library = DesktopLibrary(file)
            library.toggle(series)
            library.remember(BackupProgress(series, "episode-2", 1, 12345, 60000, false, 99))
            val reopened = DesktopLibrary(file)
            assertEquals(series, reopened.favorites[series.id])
            assertEquals(12345L, reopened.history[series.id]?.position)
            reopened.toggle(series)
            assertTrue(DesktopLibrary(file).favorites.isEmpty())
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(file.parent) }
    }
    @Test fun corruptFileIsNeverOverwritten() {
        val file = Files.createTempDirectory("hg-library-test").resolve("library.json")
        try {
            Files.writeString(file, "broken data")
            val library = DesktopLibrary(file)
            assertNotNull(library.warning)
            library.toggle(Series("123", "测试"))
            assertEquals("broken data", Files.readString(file))
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(file.parent) }
    }
}
