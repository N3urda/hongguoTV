// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hongguotv.core.LibraryBackup
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files

@Composable
fun LibraryPanel(state: DesktopState, close: () -> Unit) {
    var message by remember { mutableStateOf("") }
    fun choose(mode: Int, action: (java.nio.file.Path) -> Unit) {
        val dialog = FileDialog(null as Frame?, if (mode == FileDialog.SAVE) "导出资料库" else "导入电视端或桌面备份", mode)
        try {
            if (mode == FileDialog.SAVE) dialog.file = "hongguo-library.json"
            dialog.isVisible = true
            val file = dialog.file ?: return
            action(java.nio.file.Path.of(dialog.directory, file))
        } catch (_: Exception) { message = "操作未完成，请检查备份格式、文件权限或磁盘空间。" }
        finally { dialog.dispose() }
    }
    Dialog(onDismissRequest = close) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.width(440.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("本机资料备份", style = MaterialTheme.typography.headlineSmall)
                Text("导出收藏、观看历史和稍后看。可以导入电视端的资料库备份；合并时保留更新的观看进度。")
                Text("登录凭据不包含在备份中。导入不会修改云端收藏或覆盖当前播放设置。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { choose(FileDialog.SAVE) { Files.writeString(it, state.exportBackup()); message = "备份已导出" } }) { Text("导出备份") }
                OutlinedButton(onClick = { choose(FileDialog.LOAD) { require(Files.size(it) <= LibraryBackup.MAX_BYTES); state.importBackup(Files.readString(it)); message = state.libraryMessage } }) { Text("导入并合并") }
                if (message.isNotBlank()) Text(message)
                TextButton(onClick = close) { Text("完成") }
            }
        }
    }
}
