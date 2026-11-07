package com.cleo.cleos.ui.world

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.LocalGlassPalette
import java.time.*
import java.time.format.DateTimeFormatter

internal fun outsideDate(at: Long): String = if (at > 0) Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy年M月d日")) else "时间未提供"
internal fun outsideTime(at: Long): String = if (at > 0) Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) else "时间未提供"

@Composable
internal fun WorldOutsideNoteCard(note: WorldOutsideViews.Note, onJournal: () -> Unit, onLookup: (String) -> Unit) {
    val p = LocalGlassPalette.current
    WorldPanel {
        Text("${note.kind} · ${outsideTime(note.at)}", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        Text(note.article.title, color = p.content, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
        if (note.content.isNotBlank()) SelectionContainer { Text(note.content, color = p.content, fontSize = 14.sp, lineHeight = 23.sp) }
        if (note.quote.isNotBlank()) {
            Text("来源摘录", color = p.contentSecondary, fontSize = 12.sp)
            SelectionContainer { Text("“${note.quote}”", color = p.contentSecondary, fontSize = 14.sp, lineHeight = 23.sp) }
        }
        if (note.question.isNotBlank()) {
            Text("还想知道", color = p.contentSecondary, fontSize = 12.sp)
            Text(note.question, color = p.accentContent, fontSize = 14.sp, lineHeight = 23.sp)
        }
        if (note.historicalImage) Text("这条感想对应的配图已变化或不在本地，不能表示 TA 看过当前图片。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onJournal) { Text("查看关联见闻") }
            if (note.question.isNotBlank()) TextButton(onClick = { onLookup(note.question) }) { Text("继续这个问题") }
        }
    }
}

@Composable
internal fun WorldOutsideStepCard(step: WorldOutsideViews.Step, onJournal: () -> Unit) {
    val p = LocalGlassPalette.current
    WorldPanel {
        Text("${step.label} · ${outsideTime(step.at)}", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        Text(step.article.title, color = p.content, fontSize = 16.sp, lineHeight = 24.sp)
        Text(step.detail, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp)
        TextButton(onClick = onJournal) { Text("查看这则见闻") }
    }
}
