package com.cleo.cleos.ui.world

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.db.WorldArticleEntity
import com.cleo.cleos.glass.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
internal fun WorldOutsideCard(entry: WorldArticleEntity, data: WorldOutsideCardData, expanded: Boolean,
    onExpand: () -> Unit, managing: Boolean, backdrop: Backdrop, onJournal: () -> Unit, onSource: () -> Unit,
) {
    val p = LocalGlassPalette.current
    var overflow by remember(entry.id, entry.fingerprint) { mutableStateOf(false) }
    val kind = if (entry.searchQuery.isEmpty()) "订阅摘要" else "百科导言摘要"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${entry.source.ifBlank { "来源未注明" }} · $kind", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 19.sp)
        Text("来源发布：${WorldOutsideCardData.published(entry.published)}", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 19.sp)
    }
    Text(entry.title, color = p.content, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 27.sp)
    if (data.preview.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("来源摘要", color = p.contentSecondary, fontSize = 12.sp)
        Text(data.preview, color = p.content, fontSize = 14.sp, lineHeight = 23.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow })
        if (overflow || expanded) TextButton(onClick = onExpand,
            modifier = Modifier.semantics { contentDescription = "${if (expanded) "收起" else "展开"}摘要：${entry.title}" }) {
            Text(if (expanded) "收起摘要" else "展开摘要", fontSize = 13.sp)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val status = when (data.reading) {
            WorldOutsideCardData.Reading.Empty -> "暂无可读摘要"
            WorldOutsideCardData.Reading.Unread -> "TA 尚未阅读这份摘要"
            WorldOutsideCardData.Reading.Partial -> "TA 已读过部分摘要"
            WorldOutsideCardData.Reading.Complete -> "TA 已读完这份摘要"
            WorldOutsideCardData.Reading.Unconfirmed -> "阅读进度暂不可确认"
        }
        Text(status, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp)
        if (data.noteCount > 0) Text("已留下 ${data.noteCount} 条摘要笔记 · 可在见闻中回看", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 19.sp)
        Text("获取于 ${Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}",
            color = p.contentSecondary, fontSize = 12.sp, lineHeight = 19.sp)
        if (entry.searchQuery.isNotBlank()) Text("最初检索词：${entry.searchQuery}", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 19.sp)
    }
    if (!managing) {
        GlassButton(onClick = onJournal, backdrop = backdrop) { Text("打开图文见闻") }
        TextButton(onClick = onSource) { Text("查看摘要与来源", fontSize = 13.sp) }
    }
}
