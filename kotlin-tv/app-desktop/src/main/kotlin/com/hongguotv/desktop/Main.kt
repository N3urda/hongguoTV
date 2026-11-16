// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.Dimension

fun main(args: Array<String>) {
    args.firstOrNull { it.startsWith("--smoke-file=") }?.let { DesktopSmoke.runLocal(java.nio.file.Path.of(it.substringAfter("="))); return }
    if ("--smoke-controller" in args) { DesktopSmoke.controller(); return }
    if ("--smoke" in args) { DesktopSmoke.run(); return }
    launchDesktop()
}

private fun launchDesktop() = application {
    Window(onCloseRequest = ::exitApplication, title = "红果 · 桌面版", state = rememberWindowState(width = 1280.dp, height = 820.dp)) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(900, 640) }
        val scope = rememberCoroutineScope()
        val library = remember { DesktopLibrary() }
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
        LaunchedEffect(Unit) { state.reload(); account.restore() }
        DesktopShell(state)
    }
}
