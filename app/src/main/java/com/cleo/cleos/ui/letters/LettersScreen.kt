package com.cleo.cleos.ui.letters

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import com.cleo.cleos.ui.common.SlidingCapsuleTabs
import com.cleo.cleos.ui.common.systemMotionEnabled
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.ai.ToolGroup
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.LetterEntity
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** The letters between the person and the current TA. Long press deletes one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LettersScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val ta by remember { c.companions.current }.collectAsStateWithLifecycle(null)
    val letters by remember(ta?.id) { ta?.let { c.db.letters().observeFor(it.id) } ?: flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList())
    val hasKey by remember(ta?.apiBaseUrl) { ta?.let { c.secrets.hasKey(it.apiBaseUrl) } ?: flowOf(true) }
        .collectAsStateWithLifecycle(true)
    val now by rememberNow()
    var confirm by remember { mutableStateOf<LetterEntity?>(null) }
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val name = ta?.name?.trim()?.ifEmpty { null } ?: "TA"
    var source by remember { mutableStateOf<String?>(null) }
    val shown = Mailbox.shown(letters, now).filter { source == null || it.sourceType == source }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = "和${name}的信",
                subtitle = if (shown.isEmpty()) null else "${shown.size} 封 · 长按可以删除",
                backdrop = page,
                leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) },
                trailing = { GlassIconButton(Icons.Rounded.EditNote, "写信", { onOpen(0) }, page) },
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
            item(key = "sources") {
                val sources = listOf(null, LetterEntity.RETROSPECTIVE, LetterEntity.INSTANT)
                SlidingCapsuleTabs(listOf("全部", "回忆信件", "即时信件"), sources.indexOf(source), systemMotionEnabled()) {
                    source = sources[it]
                }
            }
            if (Mailbox.replyOnTheWay(letters, now)) {
                item(key = "on-the-way") {
                    Notice(if (hasKey) "你的信寄出了，回信在路上。" else "你的信寄出了。${name}还没接上模型，接上了才回得了信。")
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    val own = if (ToolGroup.Letters in settings.tools) "$name 有时也会自己写来。" else ""
                    Notice("还没有信。右上角写一封给$name，寄的时候可以选回信什么时候到。$own")
                }
            }
            items(shown, key = { it.id }) { letter ->
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onOpen(letter.id) }, onLongClick = { confirm = letter }),
                    shape = GlassShape.Rounded(22.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val who = when {
                                    letter.draft -> "草稿"
                                    letter.author == LetterEntity.AUTHOR_ME -> "你寄出的"
                                    else -> "${name}写来的"
                                }
                                Text(who, color = palette.accentContent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.weight(1f))
                                Text(Dates.chatStamp(letter.deliverAt ?: letter.createdAt), color = palette.contentSecondary, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            // One run of text: a letter's blank lines would leave the second preview line empty.
                            Text(
                                if (letter.sourceType == LetterEntity.INSTANT && !letter.draft)
                                    letter.title.ifBlank { if (letter.author == LetterEntity.AUTHOR_AI && letter.readAt == null) "一封未拆开的信" else "点开信封阅读" }
                                else letter.content.trim().replace(Regex("\\s+"), " ").ifEmpty { "（还没写）" },
                                color = palette.content,
                                fontSize = 15.sp,
                                lineHeight = 22.sp,
                                fontFamily = FontFamily.Serif,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (letter.author == LetterEntity.AUTHOR_AI && letter.readAt == null) {
                            Spacer(Modifier.width(12.dp))
                            Box(Modifier.size(9.dp).background(palette.accentContent, CircleShape))
                        }
                    }
                }
            }
        }
    }

    confirm?.let { letter ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("删除这封信？") },
            text = { Text("删了找不回来。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    c.appScope.launch { c.letters.delete(letter.id) }
                }) { Text("删除", color = palette.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
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
