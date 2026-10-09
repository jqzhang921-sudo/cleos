package com.cleo.cleos.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.ImportException
import com.cleo.cleos.data.LoreKeys
import com.cleo.cleos.data.db.LoreEntity
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * A TA's 设定: the entries of a world book, or of a few of them, brought over from another app
 * or written here. They are not in front of the model with every message — the TA looks one up
 * with the lore tool when the talk turns to it (ai/Lore.kt), which is what keeps a long book
 * from filling every prompt.
 */
@Composable
fun LoreScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    val ta by remember { c.companions.current }.collectAsStateWithLifecycle(null)
    val entries by remember(ta?.id) { ta?.let { c.db.lore().observeFor(it.id) } ?: flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList())
    val name = ta?.name?.trim()?.ifEmpty { null } ?: "TA"
    var query by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = ta?.id
        if (uri != null && id != null) {
            importing = true
            note = null
            scope.launch {
                note = try {
                    // The same door as 设置 › 数据与备份: a card here makes a TA and switches to them,
                    // and this screen then follows that TA.
                    c.imports.import(uri, id).said()
                } catch (e: ImportException) {
                    e.message
                } catch (e: Exception) {
                    "出错了：${e.message ?: e.javaClass.simpleName}"
                }
                importing = false
            }
        }
    }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val book = query.trim()
    val shown = if (book.isEmpty()) {
        entries
    } else {
        entries.filter { e ->
            e.title.contains(book, ignoreCase = true) ||
                e.book.contains(book, ignoreCase = true) ||
                e.content.contains(book, ignoreCase = true) ||
                LoreKeys.decode(e.keys).any { it.contains(book, ignoreCase = true) }
        }
    }
    // Already in the order the book keeps (book, position, id), so the groups come out in that order.
    val grouped = shown.groupBy { it.book.trim() }

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = "${name}的设定",
                subtitle = if (entries.isEmpty()) null else "${entries.size} 条 · 点开能改",
                backdrop = page,
                leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) },
                trailing = { GlassIconButton(Icons.Rounded.Add, "自己加一条", { onOpen(0) }, page) },
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .fadeUnderTopBar(statusTop + TopBarHeight),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = statusTop + TopBarHeight + 10.dp, bottom = navBottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (entries.isEmpty()) {
                item(key = "empty") {
                    Notice(
                        "还没有设定。世界观、人物、地点这类，一段就是一条：点右上角自己加，或者" +
                            "在「设置 › 数据与备份」里从别的 app 搬一个世界书过来。",
                    )
                }
            } else {
                item(key = "find") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("找一条") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (entries.isNotEmpty() && shown.isEmpty()) {
                item(key = "none") { Notice("设定里没有和「${query.trim()}」有关的条目。") }
            }
            for ((title, list) in grouped) {
                item(key = "h-$title") {
                    Text(
                        (if (title.isEmpty()) "没归到书里" else "《$title》") + " · ${list.size} 条",
                        color = palette.contentSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 6.dp, top = 6.dp),
                    )
                }
                items(list, key = { it.id }) { e ->
                    LoreRow(e, onOpen = { onOpen(e.id) }, onToggle = { on ->
                        scope.launch { c.db.lore().update(e.copy(enabled = on, updatedAt = System.currentTimeMillis())) }
                    })
                }
            }
            item(key = "foreign") {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = GlassShape.Rounded(22.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "从别的 app 搬过来：选一个文件，角色卡（PNG 图片卡也认）、世界书、记忆库、聊天记录都认。" +
                                "文件里有角色卡，一张就新开一个 TA，里面的东西都归第一个；只是设定的话，搬进现在这个 TA。" +
                                "已经有的条目不会重复进来。普通截图需要先提取文字；可直接导入的 PNG 角色卡要内嵌角色资料。",
                            color = palette.contentSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                        Box(
                            Modifier
                                .background(palette.content.copy(alpha = 0.07f), CircleShape)
                                .clickable(interactionSource = null, indication = null) { if (!importing) importPicker.launch(arrayOf("*/*")) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(if (importing) "正在导入…" else "导入文件", color = palette.content, fontSize = 14.sp)
                        }
                        note?.let { Text(it, color = palette.content, fontSize = 13.sp, lineHeight = 19.sp) }
                    }
                }
            }
        }
    }
}

/** One entry: what it is called, the words that lead to it, and whether the TA may find it at all. */
@Composable
private fun LoreRow(e: LoreEntity, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val palette = LocalGlassPalette.current
    val keys = LoreKeys.decode(e.keys)
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "打开", onClick = onOpen),
        shape = GlassShape.Rounded(22.dp),
        contentPadding = PaddingValues(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    e.title.ifBlank { "没起名字" },
                    color = palette.content,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (keys.isEmpty()) "没有关键词，只有说到「${e.title.ifBlank { "它" }}」时才可能查到" else keys.joinToString("、"),
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = e.enabled, onCheckedChange = onToggle, colors = glassSwitchColors())
        }
    }
}

@Composable
private fun Notice(text: String) {
    val palette = LocalGlassPalette.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        style = palette.notice,
        shape = GlassShape.Rounded(20.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(text, color = palette.content, fontSize = 14.sp, lineHeight = 21.sp)
    }
}
