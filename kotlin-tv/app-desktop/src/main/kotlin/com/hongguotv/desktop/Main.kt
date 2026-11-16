// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.Dimension

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "红果 · 桌面原型", state = rememberWindowState(width = 1280.dp, height = 820.dp)) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(900, 640) }
        val scope = rememberCoroutineScope()
        val state = remember { DesktopState(scope, LiveCatalogSource()) }
        LaunchedEffect(Unit) { state.reload() }
        DesktopShell(state)
    }
}
