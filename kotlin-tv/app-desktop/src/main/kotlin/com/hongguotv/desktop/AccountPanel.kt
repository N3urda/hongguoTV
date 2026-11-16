// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.jetbrains.skia.Image

@Composable
fun AccountPanel(account: DesktopAccount, close: () -> Unit) {
    var country by remember { mutableStateOf("86") }
    var number by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { account.cancelLogin(); close() }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.width(480.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(account.profile?.let { "你好，${it.nickname}" } ?: "登录红果账号", style = MaterialTheme.typography.headlineSmall)
                Text(account.status)
                if (account.profile == null) {
                    Row { Checkbox(account.rememberLogin, { account.rememberLogin = it }, enabled = !account.busy); Text("记住登录（系统钥匙串 / Windows 加密存储）") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = account::startQr, enabled = !account.busy) { Text("获取登录二维码") }
                        if (account.busy && account.qr != null) TextButton(onClick = account::cancelLogin) { Text("取消扫码 / 请求") }
                    }
                    val bitmap = remember(account.qr) { account.qr?.let { runCatching { Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() } }
                    bitmap?.let { Image(it, "使用红果 App 扫码登录", Modifier.size(220.dp)) }
                    if (account.qr == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(country, { country = it.filter(Char::isDigit).take(4) }, label = { Text("区号") }, modifier = Modifier.width(90.dp), singleLine = true, enabled = !account.busy && !account.codeSent)
                            OutlinedTextField(number, { number = it.filter(Char::isDigit).take(16) }, label = { Text("手机号") }, modifier = Modifier.weight(1f), singleLine = true, enabled = !account.busy && !account.codeSent)
                        }
                        Button(onClick = { account.sendCode(country, number) }, enabled = !account.busy && account.cooldown == 0) { Text(if (account.cooldown > 0) "${account.cooldown} 秒后可重发" else "发送验证码") }
                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("短信验证码") }, singleLine = true, enabled = !account.busy)
                        Button(onClick = { account.verify(code) }, enabled = account.codeSent && !account.busy) { Text("登录") }
                        if (account.codeSent) TextButton(onClick = { account.cancelLogin(); code = "" }) { Text("更换手机号") }
                        TextButton(onClick = { account.logout() }, enabled = !account.busy) { Text("清除已保存的登录态") }
                    }
                } else {
                    Text("云收藏 ${account.favorites.size} · 历史 ${account.history.size} · 预约 ${account.reservations.size}")
                    Button(onClick = account::refresh, enabled = !account.busy) { Text("刷新云端数据") }
                    OutlinedButton(onClick = { account.logout() }) { Text("退出登录") }
                }
                if (account.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { account.cancelLogin(); close() }) { Text("关闭") }
            }
        }
    }
}
