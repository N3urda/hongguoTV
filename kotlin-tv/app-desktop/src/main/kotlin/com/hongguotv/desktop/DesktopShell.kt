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

private val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
private val Brand: Color @Composable get() = MaterialTheme.colorScheme.primary
private val CoverColors = listOf(Color(0xFFB9D0D4), Color(0xFFCBC2B6), Color(0xFFC5C8DB), Color(0xFFD7C6C9))

@Composable
fun DesktopShell(state: DesktopState) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.section, state.query, state.page, state.browsingProducer) { gridState.scrollToItem(0) }
    val searchFocus = remember { FocusRequester() }
    var input by remember { mutableStateOf("") }
    var libraryOpen by remember { mutableStateOf(false) }
    var accountOpen by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    LaunchedEffect(state.query) { input = state.query }
    MaterialTheme(colorScheme = if (state.dark) darkColorScheme(primary = Color(0xFFFF9AAA), background = Color(0xFF141820), surface = Color(0xFF202630), onSurface = Color(0xFFE9EDF4), onSurfaceVariant = Color(0xFFAFB8C8)) else lightColorScheme(primary = Color(0xFFC83D50), onPrimary = Color.White, background = Color(0xFFF3F5F8), surface = Color.White, onSurface = Color(0xFF17243B), onSurfaceVariant = Color(0xFF66748A))) {
        Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
        if (libraryOpen) LibraryPanel(state) { libraryOpen = false }
        if (accountOpen) state.account?.let { AccountPanel(it) { accountOpen = false } }
        Row(Modifier.fillMaxSize().background(Paper).onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false
            else when {
                (it.isCtrlPressed || it.isMetaPressed) && it.key == Key.K -> { searchFocus.requestFocus(); true }
                !accountOpen && !libraryOpen && it.key == Key.Spacebar && !searchActive && state.playback?.running == true -> { state.playback.togglePause(); true }
                it.key == Key.Escape && state.selected != null -> { state.back(); true }
                else -> false
            }
        }) {
            Column(Modifier.width(196.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("▣  红果", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Brand, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                Text("把故事带到桌面", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Section.entries.forEachIndexed { index, section ->
                    val active = state.section == section
                    TextButton(onClick = { state.navigate(section) }, modifier = Modifier.fillMaxWidth().height(40.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.textButtonColors(containerColor = if (active) Brand.copy(alpha = .12f) else Color.Transparent, contentColor = if (active) Brand else Muted)) {
                        Text(listOf("◈", "≡", "♡", "◷", "◴", "✧", "+")[index], fontSize = 20.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(section.label, modifier = Modifier.weight(1f), fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = Paper)
                TextButton(onClick = { libraryOpen = true }) { Text("本机资料备份") }
                TextButton(onClick = { state.changeTheme(!state.dark) }) { Text(if (state.dark) "切换浅色外观" else "切换深色外观") }
                TextButton(onClick = { accountOpen = true }) { Text(state.account?.profile?.nickname ?: "登录 / 我的账号") }
                Text(if (state.account?.profile == null) "收藏与进度保存在本机" else "已连接云端账号", color = Muted, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.demo, { state.mode(it) })
                    Text("演示内容", fontSize = 13.sp)
                }
                Text("Ctrl / ⌘ K 搜索\nEsc 返回详情前一页", color = Muted, fontSize = 11.sp, lineHeight = 20.sp)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 28.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it.take(80); state.suggest(input) }, modifier = Modifier.weight(1f).widthIn(max = 640.dp).focusRequester(searchFocus).onFocusChanged { searchActive = it.isFocused }.onPreviewKeyEvent { event -> if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) { state.search(input); true } else false }, placeholder = { Text("搜索剧名，找到想看的故事") }, singleLine = true, shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { state.search(input) }))
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { state.search(input) }, enabled = input.isNotBlank()) { Text("搜索") }
                    Spacer(Modifier.width(24.dp))
                    Text(if (state.demo) "演示模式" else "在线内容", color = Muted, fontSize = 12.sp)
                }
                if (searchActive && state.suggestions.isNotEmpty()) Row(Modifier.padding(horizontal = 28.dp)) {
                    state.suggestions.forEach { suggestion -> TextButton(onClick = { state.search(suggestion) }) { Text(suggestion, maxLines = 1) } }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand)
                Column(Modifier.weight(1f).padding(horizontal = 28.dp, vertical = 24.dp)) {
                    if (state.selected != null) DetailPane(state)
                    else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(state.heading, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                                Text(if (state.demo) "虚构示例内容，用于体验桌面布局与交互" else "浏览短剧，打开详情查看简介与选集", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            }
                            TextButton(onClick = state::refreshLibrary, enabled = !state.loading) { Text("刷新") }
                        }
                        if (!state.demo && state.query.isBlank() && state.browsingProducer == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.section == Section.DISCOVER) DramaCategory.entries.forEach { category -> FilterChip(state.category == category, { state.chooseCategory(category) }, label = { Text(category.label) }) }
                            if (state.section == Section.RANKING) RankBoard.entries.forEach { board -> FilterChip(state.board == board, { state.chooseBoard(board) }, label = { Text(board.label) }) }
                        }
                        if (!state.demo && state.query.isBlank() && state.browsingProducer == null && state.section == Section.DISCOVER && state.topics.isNotEmpty()) {
                            var topicMenu by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { topicMenu = true }) { Text(state.topics.firstOrNull { it.id == state.topic }?.label ?: "全部题材") }
                                DropdownMenu(topicMenu, { topicMenu = false }) {
                                    DropdownMenuItem(text = { Text("全部题材") }, onClick = { state.chooseTopic(""); topicMenu = false })
                                    state.topics.forEach { topic -> DropdownMenuItem(text = { Text(topic.label) }, onClick = { state.chooseTopic(topic.id); topicMenu = false }) }
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        if (state.error != null) ErrorNotice(state)
                        else if (state.items.isEmpty() && !state.loading) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (state.section == Section.FAVORITES) "还没有收藏，打开剧集详情即可添加。" else if (state.section == Section.RESERVATIONS) { if (state.account?.profile == null) "登录后查看已预约的剧集。" else "还没有预约，可在相关作品中预约新一季。" } else if (state.section == Section.HISTORY) "播放一部短剧后，可以在这里继续观看。" else "没有找到内容，试试其他关键词。", color = Muted) }
                        } else {
                            LazyVerticalGrid(state = gridState, columns = GridCells.Adaptive(160.dp), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                items(state.items, key = { it.id }) { series -> SeriesCard(series, state.section == Section.RANKING, (state.page - 1) * state.items.size + state.items.indexOf(series) + 1, state.progressLabel(series)) { state.open(series) } }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                Text("第 ${state.page} 页", color = Muted, fontSize = 12.sp)
                                TextButton(onClick = { state.reload(state.page - 1) }, enabled = state.section != Section.RECOMMENDED && state.page > 1 && !state.loading) { Text("上一页") }
                                TextButton(onClick = { state.reload(state.page + 1) }, enabled = state.hasMore && !state.loading) { Text("下一页") }
                            }
                        }
                    }
                }
                state.account?.status?.takeIf { state.account.profile != null }?.let { Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp)) }
                state.storageWarning()?.let { Text(it, color = Brand, modifier = Modifier.padding(12.dp)) }
                state.playback?.let { PlaybackControls(it) }
            }
        }
    }
}

}

@Composable private fun SeriesCard(series: Series, ranked: Boolean, rank: Int, progress: String = "", open: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.onFocusChanged { focused = it.isFocused }.border(if (focused) 2.dp else 0.dp, if (focused) Brand else Color.Transparent, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).clickable(onClick = open).padding(4.dp)) {
        Box {
            Poster(series, Modifier.fillMaxWidth().aspectRatio(0.72f))
            if (ranked) Text("$rank", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.background(Brand, RoundedCornerShape(bottomEnd = 10.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
        }
        Text(series.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(progress.ifBlank { series.badge.ifBlank { "查看剧集" } }, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable private fun Poster(series: Series, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, series.cover) {
        value = null
        value = DesktopArtwork.load(series.cover)
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
    var episodeGroup by remember(series.id) { mutableStateOf(0) }
    var episodeInput by remember(series.id) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TextButton(onClick = state::back) { Text("‹ 返回列表") }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Poster(series, Modifier.width(160.dp).height(224.dp))
            Column(Modifier.weight(1f)) {
                Text(series.title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(series.tags, color = Muted, modifier = Modifier.padding(top = 12.dp))
                Text(series.description.ifBlank { "暂无剧集简介" }, fontSize = 15.sp, lineHeight = 25.sp, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 20.dp))
                Spacer(Modifier.height(12.dp))
                Text(state.progressLabel(series), color = Muted, fontSize = 13.sp)
                Button(onClick = state::resume, enabled = !state.demo && state.detail?.episodes?.isNotEmpty() == true) { Text(if (state.reservation(series)?.online == false) "尚未上线" else state.progressLabel(series).let { if (it.isBlank()) "开始观看" else "继续观看" }) }
                if (!state.demo) TextButton(onClick = { state.toggleLater(series) }) { Text(if (state.later(series)) "已加入稍后看 · 移除" else "＋ 稍后再看") }
                OutlinedButton(onClick = { state.toggleFavorite(series) }, enabled = state.demo || state.account?.profile == null || (state.account.favoritesLoaded && !state.account.busy)) { Text(if (state.favorite(series)) "已收藏 · 点击移除" else "♡ 加入收藏") }
            }
        }
        state.producer?.let { owner -> TextButton(onClick = state::producerWorks) { Text("制作方：${owner.name} · 查看作品") } }
        if (state.related.isNotEmpty()) {
            Text("相关作品 / 季数", style = MaterialTheme.typography.titleMedium)
            state.related.forEach { work -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${work.series.title} · ${work.label}", Modifier.weight(1f))
                TextButton(onClick = { if (work.upcoming) state.account?.reserve(work) else state.open(work.series) }, enabled = !work.upcoming || (state.account?.profile != null && !state.account.busy)) {
                    Text(if (!work.upcoming) "查看" else if (state.account?.reservations?.any { it.series.id == work.series.id } == true) "已预约" else "预约（需登录）")
                }
            } }
        }
        Spacer(Modifier.height(28.dp))
        if (state.error != null) ErrorNotice(state)
        val detail = state.detail
        if (detail != null) {
            Text("选集  /  共 ${detail.episodes.size} 集", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("点击集数在原生窗口播放；底部可暂停、跳转和切换清晰度。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.episodes.indices.toList().chunked(40).forEachIndexed { group, indices ->
                    FilterChip(episodeGroup == group, { episodeGroup = group }, label = { Text("${indices.first() + 1}–${indices.last() + 1}") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(episodeInput, { episodeInput = it.filter(Char::isDigit).take(4) }, label = { Text("跳到第几集") }, modifier = Modifier.width(150.dp), singleLine = true)
                TextButton(onClick = { val index = (episodeInput.toIntOrNull() ?: 0) - 1; if (index in detail.episodes.indices) { episodeGroup = index / 40; state.selectEpisode(index) } }, enabled = !state.demo && (episodeInput.toIntOrNull() ?: 0) in 1..detail.episodes.size) { Text("播放这一集") }
            }
            Spacer(Modifier.height(8.dp))
            detail.episodes.indices.drop(episodeGroup * 40).take(40).chunked(8).forEach { indices ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    indices.forEach { index ->
                        OutlinedButton(onClick = { state.selectEpisode(index) }, enabled = !state.demo, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = if (state.playback?.active?.series?.id == series.id && state.playback.index == index) Brand.copy(alpha = .12f) else MaterialTheme.colorScheme.surface)) { Text("${index + 1}") }
                    }
                    repeat(8 - indices.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (!state.demo && detail != null) {
            TextButton(onClick = { state.readComments() }) { Text("读取当前选集评论") }
            Text(state.commentStatus, color = Muted)
            state.comments.forEach { (author, content) -> Text("$author：$content", modifier = Modifier.padding(vertical = 6.dp)) }
            if (state.commentsMore) TextButton(onClick = { state.readComments(true) }) { Text("更多评论") }
        }
    }
}

@Composable private fun PlaybackControls(player: DesktopPlayback) {
    val active = player.active ?: return
    var seeking by remember(active.series.id, player.index) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${active.series.title} · 第 ${player.index + 1} 集", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = player::fullscreen, enabled = player.running) { Text("全屏") }
            TextButton(onClick = player::stop) { Text("停止播放") }
        }
        if (player.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
        player.message?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, color = Brand, modifier = Modifier.weight(1f))
                TextButton(onClick = player::retry) { Text("重新播放") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { player.next(-1) }, enabled = player.index > 0 && !player.preparing) { Text("上一集") }
            Button(onClick = player::togglePause, enabled = player.running && !player.preparing) { Text(if (player.paused) "继续" else "暂停") }
            TextButton(onClick = { player.next(1) }, enabled = player.index < active.episodes.lastIndex && !player.preparing) { Text("下一集") }
            TextButton(onClick = { player.seek(player.position - 10) }, enabled = player.running) { Text("−10秒") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Slider(value = seeking ?: player.position.toFloat().coerceIn(0f, maxOf(1f, player.duration.toFloat())), onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { player.seek(it.toDouble()) }; seeking = null }, valueRange = 0f..maxOf(1f, player.duration.toFloat()), enabled = player.running && player.duration > 0, modifier = Modifier.weight(1f))
            Text("${clock(player.position)} / ${clock(player.duration)}", fontSize = 12.sp, color = Muted)
            var speedMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { speedMenu = true }) { Text("${player.speed}×") }
                DropdownMenu(speedMenu, { speedMenu = false }) { PlaybackSpeed.options.forEach { speed -> DropdownMenuItem(text = { Text(PlaybackSpeed.label(speed)) }, onClick = { player.setSpeed(speed); speedMenu = false }) } }
            }
            var qualityMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { qualityMenu = true }, enabled = !player.preparing) { Text("${player.quality}P") }
                DropdownMenu(qualityMenu, { qualityMenu = false }) { PlaybackQuality.options.forEach { quality -> DropdownMenuItem(text = { Text("${quality}P") }, onClick = { player.chooseQuality(quality); qualityMenu = false }) } }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(player.autoNext, { player.changeAutoNext(it) })
            Text("自动连播", fontSize = 12.sp)
            TextButton(onClick = player::mute, enabled = player.running) { Text("静音 / 恢复") }
            Spacer(Modifier.width(16.dp))
            var settingsMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { settingsMenu = true }) { Text("画面 / 定时关闭") }
                DropdownMenu(settingsMenu, { settingsMenu = false }) {
                    VideoFrameMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.label) }, onClick = { player.chooseFrame(mode); settingsMenu = false }) }
                    DropdownMenuItem(text = { Text("旋转 90°") }, onClick = { player.rotate(); settingsMenu = false })
                    listOf(15, 30, 60).forEach { minutes -> DropdownMenuItem(text = { Text("$minutes 分钟后停止") }, onClick = { player.sleep(minutes); settingsMenu = false }) }
                    DropdownMenuItem(text = { Text("播完本集停止") }, onClick = { player.sleepEpisode(); settingsMenu = false })
                    DropdownMenuItem(text = { Text("取消定时关闭") }, onClick = { player.cancelSleep(); settingsMenu = false })
                }
            }
            Text(player.sleepLabel, fontSize = 12.sp, color = Muted)
            Checkbox(player.danmakuEnabled, player::changeDanmaku)
            Text("弹幕", fontSize = 12.sp)
            Text(player.danmakuStatus, fontSize = 11.sp, color = Muted)
        }
    }
}

private fun clock(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
