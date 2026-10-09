package com.cleo.cleos.ui.memory

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
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
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
import com.cleo.cleos.ai.MemoryKinds
import com.cleo.cleos.data.ImportException
import com.cleo.cleos.data.MemoryDetails
import com.cleo.cleos.data.db.MemoryEntity
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
 * What the current TA keeps in mind, kind by kind, as the TA reads it: the one line of each,
 * and how many details are behind it. The person can open any to change it, pin it (then
 * the TA can't change or delete it), or add one of their own.
 */
@Composable
fun MemoryScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    val ta by remember { c.companions.current }.collectAsStateWithLifecycle(null)
    val memories by remember(ta?.id) { ta?.let { c.db.memories().observeFor(it.id) } ?: flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList())
    val name = ta?.name?.trim()?.ifEmpty { null } ?: "TA"
    var importing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = ta?.id
        if (uri != null && id != null) {
            importing = true
            note = null
            scope.launch {
                note = try {
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
    val grouped = MemoryKinds.all.mapNotNull { kind ->
        memories.filter { it.kind == kind.key }.sortedBy { it.createdAt }.takeIf { it.isNotEmpty() }?.let { kind to it }
    }

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = "${name}记着的",
                subtitle = if (memories.isEmpty()) null else "${memories.size} 件 · 点开能改",
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
            if (memories.isEmpty()) {
                item(key = "empty") {
                    Notice("还没有记下什么。聊着聊着，$name 会自己记；你也可以点右上角自己加一条。")
                }
            }
            for ((kind, list) in grouped) {
                item(key = "h-${kind.key}") {
                    Text(
                        if (kind.key == "self") "${name}自己的事" else kind.label,
                        color = palette.contentSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 6.dp, top = 6.dp),
                    )
                }
                items(list, key = { it.id }) { m -> MemoryCard(m) { onOpen(m.id) } }
            }
            item(key = "foreign") {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = GlassShape.Rounded(22.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "从别的地方搬东西过来：选一个文件，角色卡（PNG 图片卡也认）、世界书、记忆库、历史聊天都认。" +
                                "一张角色卡新开一个 TA，文件里几张就开几个，别的东西都归第一个，世界书进他的「设定」；" +
                                "只搬记忆的话，JSON、纯文本、Markdown、Word（.docx）都行（一段或一行一件，「名字: 内容」拆成两半）。Word 只读取正文和表格文字，普通截图需要先提取文字；" +
                                "导进当前这个 TA，同名的并进已有那件，缺的细节补上；一类最多记 10 件，多的放进「设定」。",
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
                            Text(if (importing) "正在导入…" else "选个文件", color = palette.content, fontSize = 14.sp)
                        }
                        note?.let { Text(it, color = palette.content, fontSize = 13.sp, lineHeight = 19.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(m: MemoryEntity, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    val details = MemoryDetails.decode(m.details).size
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "打开", onClick = onClick),
        shape = GlassShape.Rounded(22.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The name and its pin take the room left; the count stays at the right edge.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        m.name,
                        color = palette.content,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (m.pinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.PushPin, contentDescription = "钉住了", tint = palette.accentContent, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(if (details == 0) "没有细节" else "$details 条细节", color = palette.contentSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(m.summary, color = palette.content, fontSize = 14.sp, lineHeight = 20.sp)
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
