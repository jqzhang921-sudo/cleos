package com.cleo.cleos.ui.world

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.WorldShelfData
import com.cleo.cleos.data.WorldBookVolumes
import com.cleo.cleos.data.db.WorldArticleEntity
import com.cleo.cleos.data.db.WorldShelfEntity
import com.cleo.cleos.glass.LocalGlassPalette

/** Palette-only book shapes: no invented cover art, remote images or image permissions. */
@Composable
private fun BookShape(kind: String?, modifier: Modifier = Modifier) {
    val p = LocalGlassPalette.current
    Box(modifier.size(62.dp, 82.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.offset(x = 5.dp, y = 4.dp).size(52.dp, 72.dp)
            .clip(RoundedCornerShape(8.dp)).background(p.content.copy(alpha = .08f)))
        Row(Modifier.size(52.dp, 72.dp).clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(p.accentContent.copy(alpha = .18f), p.content.copy(alpha = .04f))))
            .border(1.dp, p.content.copy(alpha = .12f), RoundedCornerShape(8.dp))) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(p.accentContent.copy(alpha = .12f)))
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Icon(when (kind) { "magazine" -> Icons.AutoMirrored.Rounded.MenuBook; "article" -> Icons.AutoMirrored.Rounded.Article; else -> Icons.Rounded.AutoStories },
                    contentDescription = null, tint = p.accentContent.copy(alpha = .72f), modifier = Modifier.size(23.dp))
            }
        }
    }
}

@Composable
internal fun WorldShelfEntry(article: WorldArticleEntity, metadata: WorldShelfEntity?, onOpen: (Long, Int) -> Unit) {
    val p = LocalGlassPalette.current
    WorldPanel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            BookShape(metadata?.kind)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(metadata?.kind?.let { WorldShelfData.kinds[it] } ?: "未分类读物", color = p.contentSecondary, fontSize = 11.sp)
                Text(article.title, color = p.content, fontSize = 17.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (metadata?.kind == "magazine") Text("${metadata.series} · ${metadata.issue.ifBlank { "期号未填" }}",
                    color = p.contentSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(article.source.lineSequence().firstOrNull().orEmpty().ifBlank { "用户提供的文章" },
                    color = p.contentSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        val preview = WorldShelfData.preview(article.content, 0)
        if (preview.isNotBlank()) {
            Text("原文开头", color = p.contentSecondary, fontSize = 11.sp)
            Text(preview, color = p.content, fontSize = 14.sp, lineHeight = 23.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        ShelfProgress(article)
        TextButton(onClick = { onOpen(article.id, article.readOffset) }) { Text("查看原文") }
    }
}

@Composable
internal fun WorldShelfBook(book: WorldBookVolumes.Book, onOpen: (Long, Int) -> Unit) {
    val p = LocalGlassPalette.current
    var expanded by rememberSaveable(book.id) { mutableStateOf(false) }
    WorldPanel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            BookShape("book")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("书籍 · ${book.parts.size} 卷", color = p.contentSecondary, fontSize = 11.sp)
                Text(book.title, color = p.content, fontSize = 17.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(book.author, color = p.contentSecondary, fontSize = 12.sp)
            }
        }
        val opening = book.opening
        Text(if (book.readCharacters == 0L) "还没开始读 · ${book.characters} 字符"
            else if (book.readCharacters == book.characters) "文字已读完 · ${book.characters} 字符"
            else "已读 ${book.readCharacters} / ${book.characters} 字符", color = p.contentSecondary, fontSize = 12.sp)
        LinearProgressIndicator(progress = { (book.readCharacters.toFloat() / book.characters.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(3.dp), color = p.accentContent.copy(alpha = .65f), trackColor = p.content.copy(alpha = .08f))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onOpen(opening.id, opening.readOffset) }) {
                Text(if (book.nextUnread == null) "回看原文" else "查看原文 · 第 ${book.parts.indexOf(opening) + 1} 卷")
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起分卷" else "展开分卷 · ${book.parts.size}") }
        }
        if (expanded) {
            Text("按卷顺序接着读，原有阅读位置、笔记和书签都保留。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
            book.parts.forEachIndexed { index, part ->
                HorizontalDivider(color = p.content.copy(alpha = .10f))
                Text("第 ${index + 1} 卷", color = p.content, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                ShelfProgress(part)
                TextButton(onClick = { onOpen(part.id, part.readOffset) }) { Text("查看第 ${index + 1} 卷") }
            }
        }
    }
}

@Composable
internal fun WorldShelfRecent(article: WorldArticleEntity, onOpen: (Long, Int) -> Unit, book: WorldBookVolumes.Book? = null) {
    val p = LocalGlassPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("最近读到", color = p.contentSecondary, fontSize = 12.sp)
        Text(book?.title ?: article.title, color = p.content, fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium)
        book?.let {
            Text("整本已读 ${it.readCharacters} / ${it.characters} 字符", color = p.contentSecondary, fontSize = 12.sp)
            Text("最近读到第 ${it.parts.indexOfFirst { part -> part.id == article.id } + 1} 卷 · 共 ${it.parts.size} 卷", color = p.contentSecondary, fontSize = 12.sp)
        }
        ShelfProgress(article)
        if (article.readOffset < article.content.length) {
            val preview = WorldShelfData.preview(article.content, article.readOffset)
            if (preview.isNotBlank()) {
                Text("当前位置的原文", color = p.contentSecondary, fontSize = 11.sp)
                Text(preview, color = p.content, fontSize = 15.sp, lineHeight = 25.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = { onOpen(article.id, article.readOffset) }) { Text("从这里回看") }
        HorizontalDivider(color = p.content.copy(alpha = .12f))
    }
}

@Composable
private fun ShelfProgress(article: WorldArticleEntity) {
    val p = LocalGlassPalette.current
    val fraction = if (article.content.isEmpty()) 0f else (article.readOffset.toFloat() / article.content.length).coerceIn(0f, 1f)
    Text(if (article.readOffset == 0) "还没开始读 · ${article.content.length} 字符"
        else if (article.readOffset == article.content.length) "文字已读完 · ${article.content.length} 字符"
        else "读到 ${article.readOffset} / ${article.content.length} 字符", color = p.contentSecondary, fontSize = 12.sp)
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(3.dp),
        color = p.accentContent.copy(alpha = .65f), trackColor = p.content.copy(alpha = .08f))
}
