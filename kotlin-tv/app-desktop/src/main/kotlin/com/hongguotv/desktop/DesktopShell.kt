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
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface

internal val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
internal val Brand: Color @Composable get() = MaterialTheme.colorScheme.primary
private val CoverColors = listOf(Color(0xFFB9D0D4), Color(0xFFCBC2B6), Color(0xFFC5C8DB), Color(0xFFD7C6C9))

@Composable
fun DesktopShell(state: DesktopState) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.section, state.query, state.page, state.browsingProducer, state.category, state.topic, state.board) { gridState.scrollToItem(0) }
    val searchFocus = remember { FocusRequester() }
    var input by remember { mutableStateOf("") }
    var libraryOpen by remember { mutableStateOf(false) }
    var accountOpen by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    LaunchedEffect(state.query) { input = state.query }
    MaterialTheme(colorScheme = if (state.dark) darkColorScheme(primary = Color(0xFFFF9AAA), background = Color(0xFF141820), surface = Color(0xFF202630), onSurface = Color(0xFFE9EDF4), onSurfaceVariant = Color(0xFFAFB8C8)) else lightColorScheme(primary = Color(0xFFAB3043), secondaryContainer = Color(0xFFE8EAE4), onSecondaryContainer = Color(0xFF282D27), onPrimary = Color.White, background = Color(0xFFF5F5F2), surface = Color.White, onSurface = Color(0xFF272A26), onSurfaceVariant = Color(0xFF60655F))) {
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
            val player = state.playback
            if (player?.fullscreenRequested != true) DesktopRail(state, { accountOpen = true }, { libraryOpen = true })
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (player?.fullscreenRequested != true) Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.selected != null) "剧集详情" else state.heading, Modifier.weight(1f), fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    OutlinedTextField(input, { input = it.take(80); state.suggest(input) }, modifier = Modifier.width(235.dp).focusRequester(searchFocus).onFocusChanged { searchActive = it.isFocused }.onPreviewKeyEvent { event -> if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) { state.search(input); true } else false }, placeholder = { Text("搜索剧名") }, singleLine = true, shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { state.search(input) }))
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { state.search(input) }, enabled = input.isNotBlank()) { Text("搜索") }
                    Spacer(Modifier.width(24.dp))
                    Text(if (state.demo) "演示模式" else "在线内容", color = Muted, fontSize = 12.sp)
                }
                if (searchActive && state.suggestions.isNotEmpty()) Row(Modifier.padding(horizontal = 28.dp)) {
                    state.suggestions.forEach { suggestion -> TextButton(onClick = { state.search(suggestion) }) { Text(suggestion, maxLines = 1) } }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand)
                Column(Modifier.weight(1f).padding(horizontal = 28.dp, vertical = 8.dp)) {
                    if (player?.active != null && player.showVideo) {
                        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                            player.engine?.let { MpvMediampPlayerSurface(it, Modifier.fillMaxSize()) }
                            if (player.preparing) CircularProgressIndicator()
                        }
                    } else if (state.selected != null) DetailPane(state)
                    else {
                        val discovery = state.section == Section.DISCOVER && state.query.isBlank() && state.browsingProducer == null
                        if (state.error != null) ErrorNotice(state)
                        LazyVerticalGrid(state = gridState, columns = GridCells.Adaptive(150.dp), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (discovery && state.page == 1 && state.items.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                                DiscoverySpotlight(state, state.items.first())
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                CatalogToolbar(state, discovery)
                            }
                            if (state.error == null && state.items.isEmpty() && !state.loading) item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(if (state.section == Section.FAVORITES) "还没有收藏，打开剧集详情即可添加。" else if (state.section == Section.RESERVATIONS) { if (state.account?.profile == null) "登录后查看已预约的剧集。" else "还没有预约，可在相关作品中预约新一季。" } else if (state.section == Section.HISTORY) "播放一部短剧后，可以在这里继续观看。" else "没有找到内容，试试其他关键词。", color = Muted) }
                            }
                            val cards = if (discovery && state.page == 1) state.items.drop(1) else state.items
                            if (state.error == null) itemsIndexed(cards, key = { _, series -> series.id }) { index, series ->
                                SeriesCard(series, state.section == Section.RANKING, (state.page - 1) * state.items.size + index + 1, state.progressLabel(series)) { state.open(series) }
                            }
                        }
                        if (state.items.isNotEmpty()) {
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

@Composable
private fun CatalogToolbar(state: DesktopState, discovery: Boolean) {
    val filterable = !state.demo && state.query.isBlank() && state.browsingProducer == null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (discovery) Text("发现片库", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filterable && discovery) {
                DramaCategory.entries.forEach { category ->
                    FilterChip(state.category == category, { state.chooseCategory(category) }, label = { Text(category.label) })
                }
                if (state.topics.isNotEmpty()) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { open = true }) { Text(state.topics.firstOrNull { it.id == state.topic }?.label ?: "全部题材") }
                        DropdownMenu(open, { open = false }) {
                            DropdownMenuItem(text = { Text("全部题材") }, onClick = { state.chooseTopic(""); open = false })
                            state.topics.forEach { topic ->
                                DropdownMenuItem(text = { Text(topic.label) }, onClick = { state.chooseTopic(topic.id); open = false })
                            }
                        }
                    }
                }
            }
            if (filterable && state.section == Section.RANKING) RankBoard.entries.forEach { board ->
                FilterChip(state.board == board, { state.chooseBoard(board) }, label = { Text(board.label) })
            }
        }
        TextButton(onClick = state::refreshLibrary, enabled = !state.loading) { Text("刷新") }
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
        SeriesBackdrop(series) {
            Text(series.tags, color = Color.White)
            Text(series.title, fontSize = 34.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontWeight = FontWeight.Bold, color = Color.White)
            Text(series.description.ifBlank { "暂无剧集简介" }, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val progress = state.progressLabel(series)
            if (progress.isNotEmpty()) Text(progress, color = Color.White, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = state::resume, enabled = !state.demo && state.detail?.episodes?.isNotEmpty() == true) { Text(if (state.reservation(series)?.online == false) "尚未上线" else if (progress.isBlank()) "开始观看" else "继续观看") }
                FilledTonalButton(onClick = { state.toggleFavorite(series) }, enabled = state.demo || state.account?.profile == null || (state.account.favoritesLoaded && !state.account.busy)) { Text(if (state.favorite(series)) "已收藏" else "♡ 收藏") }
                if (!state.demo) FilledTonalButton(onClick = { state.toggleLater(series) }) { Text(if (state.later(series)) "已加入稍后看" else "＋ 稍后再看") }
            }
        }
        Spacer(Modifier.height(28.dp))
        if (state.error != null) ErrorNotice(state)
        val detail = state.detail
        if (detail != null) {
            Text("选集  /  共 ${detail.episodes.size} 集", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("点击集数在当前窗口播放。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
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
    var expanded by remember(active.series.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${active.series.title} · 第 ${player.index + 1} 集", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (player.fullscreenRequested) player.fullscreen(); player.showVideo = !player.showVideo }) { Text(if (player.showVideo) "浏览" else "看视频") }
            Button(onClick = player::togglePause, enabled = player.running && !player.preparing) { Text(if (player.paused) "继续" else "暂停") }
            TextButton(onClick = { player.next(1) }, enabled = player.index < active.episodes.lastIndex && !player.preparing) { Text("下一集") }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "更多") }
            TextButton(onClick = player::stop) { Text("停止播放") }
        }
        if (player.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
        player.message?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, color = Brand, modifier = Modifier.weight(1f))
                TextButton(onClick = player::retry) { Text("重新播放") }
            }
        }
        if (!expanded) return@Column
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { player.next(-1) }, enabled = player.index > 0 && !player.preparing) { Text("上一集") }
            TextButton(onClick = player::fullscreen, enabled = player.running) { Text("全屏") }
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
