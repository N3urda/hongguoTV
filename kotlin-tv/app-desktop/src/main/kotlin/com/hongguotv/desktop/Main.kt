// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.Dimension

fun main(args: Array<String>) {
    val smoke = "--smoke" in args || "--smoke-controller" in args
    val smokeFile = if (smoke) java.nio.file.Files.createTempDirectory("hongguo-embedded-smoke").resolve("library.json") else null
    try {
        launchDesktop(smokeFile)
    } finally {
        smokeFile?.let { java.nio.file.Files.deleteIfExists(it); java.nio.file.Files.deleteIfExists(it.parent) }
    }
}

private fun launchDesktop(smokeFile: java.nio.file.Path?) = application(exitProcessOnExit = false) {
    val windowState = rememberWindowState(width = 1280.dp, height = 820.dp)
    Window(onCloseRequest = ::exitApplication, title = "红果 · 桌面版", state = windowState) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(900, 640) }
        val scope = rememberCoroutineScope()
        val library = remember { if (smokeFile != null) DesktopLibrary(smokeFile) else DesktopLibrary() }
        val source = remember { LiveCatalogSource() }
        val account = remember { DesktopAccount(scope) }
        val playback = remember { DesktopPlayback(scope, source.repository, library) }
        val state = remember { DesktopState(scope, source, library = library, playback = playback, account = account) }
        var lastAccount by remember { mutableStateOf<String?>(null) }
        account.changed = {
            val switched = lastAccount != account.profile?.id
            lastAccount = account.profile?.id
            if (state.selected == null && state.query.isBlank() && state.browsingProducer == null &&
                (state.section in listOf(Section.FAVORITES, Section.HISTORY, Section.RESERVATIONS) || (switched && state.section == Section.RECOMMENDED))) state.reload()
        }
        playback.progressSaved = { progress, count, force -> account.upload(progress, count, force) }
        DisposableEffect(Unit) { onDispose { playback.close(); account.logout(clearStored = false) } }
        LaunchedEffect(Unit) {
            if (smokeFile == null) {
                state.reload()
                account.restore()
            } else {
                try {
                    DesktopSmoke.verify(source, playback, state, smokeFile, window)
                } catch (failure: Throwable) {
                    failure.printStackTrace()
                    kotlin.system.exitProcess(1)
                }
                exitApplication()
            }
        }
        var previousPlacement by remember { mutableStateOf(WindowPlacement.Floating) }
        LaunchedEffect(playback.fullscreenRequested) {
            if (playback.fullscreenRequested) {
                previousPlacement = windowState.placement
                windowState.placement = WindowPlacement.Fullscreen
            } else if (windowState.placement == WindowPlacement.Fullscreen) windowState.placement = previousPlacement
        }
        DesktopShell(state)
    }
}
