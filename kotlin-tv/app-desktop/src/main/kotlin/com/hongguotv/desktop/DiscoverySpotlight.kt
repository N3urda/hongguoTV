// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.hongguotv.core.Series

@Composable
internal fun SeriesBackdrop(series: Series, content: @Composable ColumnScope.() -> Unit) {
    val cover by produceState<ImageBitmap?>(null, series.cover) {
        value = null
        value = DesktopArtwork.load(series.cover)
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 235.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF38494B))) {
        cover?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .8f), Color.Black.copy(alpha = .53f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun DiscoverySpotlight(state: DesktopState, featured: Series) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(2.3f)) {
            SeriesBackdrop(featured) {
                Text("精选短剧", color = Color.White, fontSize = 13.sp)
                Text(featured.title, color = Color.White, fontSize = 32.sp, lineHeight = 38.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(featured.tags, featured.badge).filter(String::isNotBlank).joinToString("　"), color = Color.White, maxLines = 1)
                FilledTonalButton(onClick = { state.open(featured) }) { Text("查看剧集　›") }
            }
        }
        Column(Modifier.weight(1f).heightIn(min = 235.dp).background(if (state.dark) MaterialTheme.colorScheme.surface else Color(0xFFE8ECE6), RoundedCornerShape(14.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("继续观看", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            val recent = state.recentSeries.take(2)
            if (recent.isEmpty()) Text("观看后，可以从这里接着看。", color = Muted, fontSize = 13.sp)
            recent.forEach { series ->
                Row(Modifier.fillMaxWidth().clickable { state.open(series) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val image by produceState<ImageBitmap?>(null, series.cover) { value = DesktopArtwork.load(series.cover) }
                    Box(Modifier.size(40.dp, 56.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF596B63)), contentAlignment = Alignment.Center) {
                        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Text("▶", color = Color.White)
                    }
                    Column {
                        Text(series.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(state.progressLabel(series), fontSize = 12.sp, color = Muted, maxLines = 2)
                    }
                }
            }
        }
    }
}
