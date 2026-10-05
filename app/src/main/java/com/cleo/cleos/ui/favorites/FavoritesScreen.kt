package com.cleo.cleos.ui.favorites

import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.FavoriteEntity
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun FavoritesHomeCard(companionId: Long, onOpen: () -> Unit) {
    val c = appContainer()
    val p = LocalGlassPalette.current
    val entries by remember(companionId) { c.db.favorites().observeFor(companionId) }.collectAsStateWithLifecycle(emptyList())
    GlassSurface(Modifier.fillMaxWidth().clickable(onClickLabel = "打开收藏", onClick = onOpen),
        shape = GlassShape.Rounded(24.dp), contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.BookmarkBorder, null, tint = p.contentSecondary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("收藏", color = p.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(if (entries.isEmpty()) "想留下的话和声音" else "留下了 ${entries.size} 段", color = p.contentSecondary, fontSize = 12.sp)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = p.contentSecondary)
        }
    }
}

@Composable
fun FavoritesScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val c = appContainer()
    val p = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    val ta by c.companions.current.collectAsStateWithLifecycle(null)
    val entries by remember(ta?.id) { ta?.let { c.db.favorites().observeFor(it.id) } ?: flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable(ta?.id) { mutableStateOf("") }
    val shown = remember(entries, query) { entries.filter { FavoriteContent.matches(it, query) } }
    val snack = remember { SnackbarHostState() }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    GlassPage(overlay = { page ->
        GlassTopBar("收藏", page, subtitle = ta?.name?.ifBlank { "TA" },
            leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) })
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = bottom))
    }) {
        LazyColumn(Modifier.fillMaxSize().fadeUnderTopBar(top),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = top + 10.dp, bottom = bottom + 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("search") {
                GlassSurface(Modifier.fillMaxWidth(), shape = GlassShape.Rounded(22.dp), contentPadding = PaddingValues(6.dp)) {
                    TextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索原文和备注") }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent))
                }
            }
            if (shown.isEmpty()) item("empty") {
                GlassSurface(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp)) {
                    Text(if (entries.isEmpty()) "还没有收藏。\n聊天里长按一句话，可以留下文字和语音；多选可以收起一段对话。"
                        else "没有找到，换个词试试。", color = p.contentSecondary, lineHeight = 24.sp)
                }
            }
            items(shown, key = { it.id }) { entry ->
                FavoriteCard(entry, onOpen = { onOpen(entry.id) }, onRemove = {
                    scope.launch {
                        c.favorites.remove(entry.id)
                        if (snack.showSnackbar("已取消收藏", "撤销", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                            c.favorites.undo(entry.id)
                        }
                    }
                })
            }
        }
    }
}

@Composable
private fun FavoriteCard(entry: FavoriteEntity, onOpen: () -> Unit, onRemove: () -> Unit) {
    val p = LocalGlassPalette.current
    val parts = remember(entry.parts) { FavoriteContent.decode(entry.parts) }
    var menu by remember { mutableStateOf(false) }
    GlassSurface(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = GlassShape.Rounded(24.dp), contentPadding = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(parts.firstOrNull()?.let { Dates.chatStamp(it.at) }.orEmpty(), Modifier.weight(1f), color = p.contentSecondary, fontSize = 12.sp)
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreVert, "收藏选项", tint = p.contentSecondary) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("取消收藏") }, onClick = { menu = false; onRemove() })
                    }
                }
            }
            for (part in parts.take(3)) {
                if (part.gapBefore) Text("···", color = p.contentSecondary)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(part.name, color = p.accentContent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(preview(part), color = p.content, fontSize = 15.sp, lineHeight = 23.sp, maxLines = if (parts.size == 1) 5 else 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (parts.size > 1 || parts.any { it.audio != null }) {
                Text("共 ${parts.size} 条" + if (parts.any { it.audio != null }) " · 含语音" else "", color = p.contentSecondary, fontSize = 12.sp)
            }
            if (entry.note.isNotBlank()) Text(entry.note, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun preview(part: FavoritePart): String = buildString {
    part.audio?.let { append("语音 · ${(it.ms / 1000).coerceAtLeast(1)} 秒  ") }
    if (part.images.isNotEmpty()) append("[图片] ")
    append(StickerText.plain(part.text))
}

@Composable
fun FavoriteScreen(id: Long, onBack: () -> Unit, onFound: () -> Unit, onImage: (String) -> Unit) {
    val c = appContainer()
    val p = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    val entry by remember(id) { c.db.favorites().observe(id) }.collectAsStateWithLifecycle(null)
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val ta by remember { c.companions.current }.collectAsStateWithLifecycle(null)
    val parts = remember(entry?.parts) { entry?.let { FavoriteContent.decode(it.parts) }.orEmpty() }
    var editing by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    val snack = remember { SnackbarHostState() }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    var playing by remember { mutableStateOf<String?>(null) }
    fun stop() { player[0]?.release(); player[0] = null; playing = null }
    LifecycleResumeEffect(id) { onPauseOrDispose { stop() } }
    fun play(file: String) {
        val same = playing == file
        stop()
        if (same) return
        try {
            val audio = MediaPlayer()
            player[0] = audio
            audio.setDataSource(c.images.file(file).path)
            audio.setOnPreparedListener { playing = file; it.start() }
            audio.setOnCompletionListener { stop() }
            audio.setOnErrorListener { _, _, _ -> stop(); scope.launch { snack.showSnackbar("这段声音暂时播不了") }; true }
            audio.prepareAsync()
        } catch (_: Exception) { stop(); scope.launch { snack.showSnackbar("声音文件已丢失，暂时播不了") } }
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    GlassPage(overlay = { page ->
        GlassTopBar("留下的对话", page,
            leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) },
            trailing = { TextButton(onClick = { note = entry?.note.orEmpty(); editing = true }, enabled = entry != null) { Text("备注", color = p.content) } })
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = bottom))
    }) {
        LazyColumn(Modifier.fillMaxSize().fadeUnderTopBar(top),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = top + 16.dp, bottom = bottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item("date") {
                Text(parts.firstOrNull()?.let { Dates.chatStamp(it.at) }.orEmpty(), color = p.contentSecondary, fontSize = 12.sp)
            }
            items(parts, key = { it.messageId }) { part ->
                Column {
                    if (part.gapBefore) Text("···", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp), color = p.contentSecondary)
                    val mine = part.role == "user"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        if (settings.chatAvatars && !mine) {
                            Avatar(ta?.avatar, ta?.avatarEmoji ?: avatarLetter(part.name, "TA"), 32.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.fillMaxWidth(0.78f), horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(part.name, color = p.contentSecondary, fontSize = 11.sp)
                            GlassSurface(shape = GlassShape.Rounded(20.dp), style = if (mine) p.bubbleMine else p.bubble,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    part.audio?.let { audio ->
                                        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "播放收藏语音") { play(audio.file) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(if (playing == audio.file) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                                if (playing == audio.file) "停止" else "播放", tint = if (mine) p.mineContent else p.content)
                                            Text("${(audio.ms / 1000).coerceAtLeast(1)} 秒", color = if (mine) p.mineContent else p.content)
                                        }
                                    }
                                    part.images.forEach { pic ->
                                        AsyncImage(c.images.file(pic.file), "收藏的图片", contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp)).clickable { onImage(pic.file) })
                                    }
                                    if (part.text.isNotBlank()) Text(StickerText.plain(part.text), color = if (mine) p.mineContent else p.content, fontSize = 15.sp, lineHeight = 24.sp)
                                }
                            }
                        }
                        if (settings.chatAvatars && mine) {
                            Spacer(Modifier.width(8.dp))
                            Avatar(settings.userAvatar, avatarLetter(part.name, "我"), 32.dp)
                        }
                    }
                }
            }
            entry?.let { e ->
                item("note") {
                    GlassSurface(Modifier.fillMaxWidth().clickable { note = e.note; editing = true }, contentPadding = PaddingValues(18.dp)) {
                        Text(e.note.ifBlank { "写一句备注，记下为什么想留下它" }, color = p.contentSecondary, fontSize = 14.sp, lineHeight = 22.sp)
                    }
                }
                item("source") {
                    TextButton(onClick = {
                        scope.launch {
                            val conversation = c.db.conversations().get(e.conversationId)
                            val source = if (conversation?.companionId == e.companionId) parts.firstNotNullOfOrNull { part ->
                                c.db.messages().get(part.messageId)?.takeIf { it.conversationId == e.conversationId && it.createdAt == part.at }
                            } else null
                            if (source == null) snack.showSnackbar("原对话已不存在，收藏仍然留在这里") else {
                                c.settings.setCurrentCompanion(e.companionId)
                                c.chat.show(source)
                                c.opening.value = com.cleo.cleos.Opening.Chat(source.conversationId)
                                onFound()
                            }
                        }
                    }) { Text("回到原对话", color = p.accentContent) }
                }
            }
        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text("写一句备注") },
        text = { OutlinedTextField(note, { note = it }, minLines = 3, maxLines = 6, placeholder = { Text("为什么想留下这段对话") }) },
        confirmButton = { TextButton(onClick = {
            c.appScope.launch { c.favorites.note(id, note) }
            editing = false
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } })
}
