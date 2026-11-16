// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.hongguotv.core.*
import kotlinx.coroutines.*
import java.net.URI

private val Ink = Color(0xFF17243B)
private val Muted = Color(0xFF66748A)
private val Paper = Color(0xFFF3F5F8)
private val Brand = Color(0xFFC83D50)
private val CoverColors = listOf(Color(0xFFB9D0D4), Color(0xFFCBC2B6), Color(0xFFC5C8DB), Color(0xFFD7C6C9))

@Composable
fun DesktopShell(state: DesktopState) {
    val searchFocus = remember { FocusRequester() }
    var input by remember { mutableStateOf("") }
    LaunchedEffect(state.query) { input = state.query }
    MaterialTheme(colorScheme = lightColorScheme(primary = Brand, onPrimary = Color.White, background = Paper, surface = Color.White, onSurface = Ink, secondary = Muted)) {
        Row(Modifier.fillMaxSize().background(Paper).onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false
            else when {
                (it.isCtrlPressed || it.isMetaPressed) && it.key == Key.K -> { searchFocus.requestFocus(); true }
                it.key == Key.Escape && state.selected != null -> { state.back(); true }
                else -> false
            }
        }) {
            Column(Modifier.width(196.dp).fillMaxHeight().background(Color.White).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("▣  红果", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Brand, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                Text("把故事带到桌面", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(36.dp))
                Section.entries.forEachIndexed { index, section ->
                    val active = state.section == section
                    TextButton(onClick = { state.navigate(section) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.textButtonColors(containerColor = if (active) Color(0xFFF9E9EC) else Color.Transparent, contentColor = if (active) Brand else Muted)) {
                        Text(listOf("◈", "≡", "♡")[index], fontSize = 20.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(section.label, modifier = Modifier.weight(1f), fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                Spacer(Modifier.weight(1f))
                HorizontalDivider(color = Paper)
                Text("桌面预览版", color = Ink, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
                Text("收藏仅保留在本次会话", color = Muted, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.demo, { state.mode(it) })
                    Text("演示内容", fontSize = 13.sp)
                }
                Text("Ctrl / ⌘ K 搜索\nEsc 返回详情前一页", color = Muted, fontSize = 11.sp, lineHeight = 20.sp)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 28.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it.take(80) }, modifier = Modifier.weight(1f).widthIn(max = 640.dp).focusRequester(searchFocus), placeholder = { Text("搜索剧名，找到想看的故事") }, singleLine = true, shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { state.search(input) }))
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { state.search(input) }, enabled = input.isNotBlank()) { Text("搜索") }
                    Spacer(Modifier.width(24.dp))
                    Text(if (state.demo) "演示模式" else "在线内容", color = Muted, fontSize = 12.sp)
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand)
                Column(Modifier.weight(1f).padding(horizontal = 28.dp, vertical = 24.dp)) {
                    if (state.selected != null) DetailPane(state)
                    else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (state.query.isBlank()) state.section.label else "“${state.query}”的搜索结果", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                                Text(if (state.demo) "虚构示例内容，用于体验桌面布局与交互" else "浏览短剧，打开详情查看简介与选集", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            }
                            TextButton(onClick = { state.reload() }, enabled = !state.loading) { Text("刷新") }
                        }
                        Spacer(Modifier.height(20.dp))
                        if (state.error != null) ErrorNotice(state)
                        else if (state.items.isEmpty() && !state.loading) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (state.section == Section.FAVORITES) "还没有收藏，打开剧集详情即可添加。" else "没有找到内容，试试其他关键词。", color = Muted) }
                        } else {
                            LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                items(state.items, key = { it.id }) { series -> SeriesCard(series, state.section == Section.RANKING, state.items.indexOf(series) + 1) { state.open(series) } }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                Text("第 ${state.page} 页", color = Muted, fontSize = 12.sp)
                                TextButton(onClick = { state.reload(state.page - 1) }, enabled = state.page > 1 && !state.loading) { Text("上一页") }
                                TextButton(onClick = { state.reload(state.page + 1) }, enabled = state.hasMore && !state.loading) { Text("下一页") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SeriesCard(series: Series, ranked: Boolean, rank: Int, open: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.onFocusChanged { focused = it.isFocused }.border(if (focused) 2.dp else 0.dp, if (focused) Brand else Color.Transparent, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).clickable(onClick = open).padding(4.dp)) {
        Box {
            Poster(series, Modifier.fillMaxWidth().aspectRatio(0.72f))
            if (ranked) Text("$rank", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.background(Brand, RoundedCornerShape(bottomEnd = 10.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
        }
        Text(series.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(series.badge.ifBlank { "查看剧集" }, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable private fun Poster(series: Series, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, series.cover) {
        value = null
        if (series.cover.startsWith("https://")) {
            value = withContext(Dispatchers.IO) {
                try {
                    val connection = URI(series.cover).toURL().openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }
                    val bytes = connection.getInputStream().use { it.readNBytes(4 * 1024 * 1024 + 1) }
                    if (bytes.size > 4 * 1024 * 1024) null else org.jetbrains.skia.Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            }
        }
    }
    val tint = CoverColors[(series.id.hashCode() and Int.MAX_VALUE) % CoverColors.size]
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(tint)) {
        if (image != null) Image(image!!, series.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(series.tags.substringBefore(" · ").ifBlank { "短剧" }, color = Ink.copy(alpha = .65f), fontSize = 12.sp)
                Text(series.title, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink, lineHeight = 36.sp)
                Text(if (series.id.startsWith("demo-")) "演示剧集" else "封面暂不可用", color = Ink.copy(alpha = .65f), fontSize = 11.sp)
            }
        }
    }
}

@Composable private fun ErrorNotice(state: DesktopState) {
    Row(Modifier.fillMaxWidth().background(Color(0xFFFBEAEC), RoundedCornerShape(10.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(state.error.orEmpty(), color = Brand, modifier = Modifier.weight(1f))
        TextButton(onClick = state::retry) { Text("重试") }
    }
}

@Composable private fun DetailPane(state: DesktopState) {
    val series = state.detail?.series ?: state.selected ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TextButton(onClick = state::back) { Text("‹ 返回列表") }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Poster(series, Modifier.width(200.dp).height(278.dp))
            Column(Modifier.weight(1f)) {
                Text(series.title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(series.tags, color = Muted, modifier = Modifier.padding(top = 12.dp))
                Text(series.description.ifBlank { "暂无剧集简介" }, fontSize = 15.sp, lineHeight = 25.sp, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 20.dp))
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { state.toggleFavorite(series) }) { Text(if (state.favorite(series)) "已收藏 · 点击移除" else "♡ 加入本次收藏") }
            }
        }
        Spacer(Modifier.height(28.dp))
        if (state.error != null) ErrorNotice(state)
        val detail = state.detail
        if (detail != null) {
            Text("选集  /  共 ${detail.episodes.size} 集", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("选择剧集预览播放区域，桌面播放器尚未接入。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
            detail.episodes.indices.toList().chunked(8).forEach { indices ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    indices.forEach { index ->
                        OutlinedButton(onClick = { state.selectEpisode(index) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = if (state.episode == index) Color(0xFFF9E9EC) else Color.White)) { Text("${index + 1}") }
                    }
                    repeat(8 - indices.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (state.episode != null) {
            Box(Modifier.fillMaxWidth().height(200.dp).padding(top = 16.dp).background(Ink, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("第 ${state.episode!! + 1} 集", color = Color.White, fontSize = 22.sp)
                    Text("播放区域预览 · 暂不支持视频播放", color = Color(0xFFCBD3E1), modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}
