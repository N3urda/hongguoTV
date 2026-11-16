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
        val playback = remember { DesktopPlayback(scope, source.repository, library) }
        val state = remember { DesktopState(scope, source, library = library, playback = playback) }
        DisposableEffect(Unit) { onDispose { playback.close() } }
        LaunchedEffect(Unit) { state.reload() }
        DesktopShell(state)
    }
}
