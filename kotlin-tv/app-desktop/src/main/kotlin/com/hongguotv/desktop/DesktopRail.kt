// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

private val destinations = listOf(
    Section.DISCOVER to "◉", Section.RANKING to "≡", Section.RECOMMENDED to "✧",
    Section.HISTORY to "◷", Section.FAVORITES to "♡", Section.LATER to "◴", Section.RESERVATIONS to "☆"
)

@Composable
internal fun DesktopRail(state: DesktopState, account: () -> Unit, library: () -> Unit) {
    var settings by remember { mutableStateOf(false) }
    val rail = if (state.dark) MaterialTheme.colorScheme.surface else Color(0xFFEEEFEA)
    Column(Modifier.width(174.dp).fillMaxHeight().background(rail).padding(14.dp)) {
        Text("▶  红果", fontSize = 25.sp, color = Brand, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 20.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            destinations.forEach { (section, icon) ->
                if (section == Section.HISTORY) Text("我的片单", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, top = 22.dp, bottom = 6.dp))
                val active = state.section == section && state.query.isBlank() && state.browsingProducer == null
                TextButton(onClick = { state.navigate(section) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.textButtonColors(containerColor = if (active) MaterialTheme.colorScheme.background else Color.Transparent, contentColor = if (active) Brand else Ink)) {
                    Text(icon, Modifier.width(26.dp))
                    Text(section.label, Modifier.weight(1f))
                }
            }
        }
        Column(horizontalAlignment = Alignment.Start) {
            TextButton(onClick = account) { Text(state.account?.profile?.nickname ?: "登录 / 我的账号") }
            Box {
                TextButton(onClick = { settings = true }) { Text("桌面设置") }
                DropdownMenu(settings, { settings = false }) {
                    DropdownMenuItem(text = { Text("本机资料备份") }, onClick = { settings = false; library() })
                    DropdownMenuItem(text = { Text(if (state.dark) "切换浅色外观" else "切换深色外观") }, onClick = { settings = false; state.changeTheme(!state.dark) })
                    DropdownMenuItem(text = { Text(if (state.demo) "退出演示模式" else "体验演示内容") }, onClick = { settings = false; state.mode(!state.demo) })
                }
            }
        }
    }
}
