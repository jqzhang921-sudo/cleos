package com.cleo.cleos.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cleo.cleos.ai.FeedNewsSources
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A draft with an explicit save. Checking public sources never invokes a TA model. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FeedSourcesDialog(onClose: () -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    var loaded by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var interests by rememberSaveable { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (!loaded) {
            val saved = c.settings.current()
            selection = FeedNewsSources.encode(FeedNewsSources.selected(saved.feedNewsSources, saved.feedRssUrl)).ifBlank { "none" }
            address = saved.feedRssUrl
            interests = saved.feedInterests
            loaded = true
        }
    }
    val ids = FeedNewsSources.selected(selection, address)
    val valid = loaded && runCatching { FeedNewsSources.validate(ids, address) }.isSuccess
    val busy = checking || saving
    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = palette.content.copy(alpha = 0.07f), labelColor = palette.content,
        selectedContainerColor = palette.accent, selectedLabelColor = androidx.compose.ui.graphics.Color.White,
    )
    Dialog(onDismissRequest = { if (!saving) onClose() }) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
            Column(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("想看什么", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("勾选允许阅读的方向，TA 会按自己的性格和兴趣挑一篇，也可以不分享。所有 TA 共用这份阅读范围。", color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fun toggle(id: String) {
                            val next = if (id in ids) ids - id else ids + id
                            selection = FeedNewsSources.encode(next).ifBlank { "none" }
                            message = null
                        }
                        FeedNewsSources.GROUPS.forEach { group ->
                            FilterChip(selected = group.id in ids, onClick = { toggle(group.id) }, label = { Text(group.label) }, enabled = loaded && !busy,
                                shape = CircleShape, colors = chipColors, border = null)
                        }
                        FilterChip(selected = FeedNewsSources.CUSTOM in ids, onClick = { toggle(FeedNewsSources.CUSTOM) }, label = { Text("自己的订阅") }, enabled = loaded && !busy,
                            shape = CircleShape, colors = chipColors, border = null)
                    }
                    FeedNewsSources.GROUPS.filter { it.id in ids }.forEach { group ->
                        Text("${group.label}：${group.description}", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    if (FeedNewsSources.CUSTOM in ids) OutlinedTextField(address, { address = it.take(2000); message = null },
                        label = { Text("自选订阅地址（RSS / Atom）") }, placeholder = { Text("https://…") },
                        enabled = loaded && !busy, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                    OutlinedTextField(interests, { interests = it.take(300) }, label = { Text("兴趣提示（可留空）") },
                        placeholder = { Text("猫咪趣事、安静的小城、家常菜；留空跟随角色设定") },
                        enabled = loaded && !busy, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                    Text("部分来源是英文，TA 会用中文分享。阅读的是近期文章标题与摘要，话题会保留来源。", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                    TextButton(onClick = {
                        checking = true
                        message = null
                        scope.launch {
                            try {
                                val result = c.feedNews.check(address.trim(), selection)
                                message = result.statuses.joinToString("\n") { "${it.label}：" + if (it.count > 0) "读到 ${it.count} 篇近期内容" else "暂时读不到近期内容" }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { message = e.message ?: "来源检查没完成，请稍后重试" }
                            finally { checking = false }
                        }
                    }, enabled = valid && !busy) { Text(if (checking) "正在检查…" else "检查来源", color = palette.accentContent) }
                    Text("检查仅联网读取来源；保存后，手动逛逛和已开启的后台资讯都会使用这份选择。", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                    if (loaded && !valid) Text(if (ids.isEmpty()) "至少选一个想看的方向" else "请填写有效的 https:// 订阅地址", color = palette.error, fontSize = 12.sp)
                    message?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp) }
                }
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onClose, enabled = !saving) { Text("取消", color = palette.content) }
                    TextButton(onClick = {
                        saving = true
                        c.appScope.launch {
                            try {
                                FeedNewsSources.validate(ids, address)
                                c.settings.update { it.copy(feedNewsSources = FeedNewsSources.encode(ids), feedRssUrl = address.trim(), feedInterests = interests.trim()) }
                                onClose()
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { message = e.message ?: "选择没保存，请重试" }
                            finally { saving = false }
                        }
                    }, enabled = valid && !busy) { Text(if (saving) "保存中…" else "保存", color = palette.accentContent) }
                }
            }
        }
    }
}
