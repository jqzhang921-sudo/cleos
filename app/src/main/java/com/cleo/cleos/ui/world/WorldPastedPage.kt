package com.cleo.cleos.ui.world

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.WorldPastedText
import com.cleo.cleos.data.WorldReading
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun WorldPastedPage(ta: CompanionEntity, navigation: WorldNavigationModel, top: Dp, bottom: Dp, backdrop: Backdrop, saved: (Long) -> Unit) {
    val c = appContainer(); val p = LocalGlassPalette.current; val keyboard = LocalSoftwareKeyboardController.current
    var title by navigation.pasteTitle; var source by navigation.pasteSource; var body by navigation.pasteBody
    var preview by navigation.pastePreview; var saving by navigation.pasteSaving; var error by navigation.pasteError
    val prepared = remember(title, source, body) { runCatching { WorldPastedText.prepare(title, source, body) } }
    val list = rememberLazyListState()
    LaunchedEffect(preview) { list.scrollToItem(0) }
    fun edited() { preview = false; error = null }
    LazyColumn(Modifier.fillMaxSize().imePadding().fadeUnderTopBar(top, bottom), state = list, contentPadding = worldPadding(top, bottom), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(if (preview) "确认这篇正文" else "把找到的正文带回来", color = p.content, fontSize = 19.sp) }
        item { Text("在浏览器选中并复制文章正文，再粘贴到这里。保存后放在${ta.name.ifBlank { "TA" }}的「书房 → 文章」，阅读笔记进入随手本。这里只保存你提供的文字，不会抓取链接或图片。", color = p.contentSecondary, fontSize = 13.sp, lineHeight = 22.sp) }
        if (!preview) {
            item { OutlinedTextField(title, { if (it.length <= 120) { title = it; edited() } }, Modifier.fillMaxWidth(), label = { Text("文章标题") }, singleLine = true, enabled = !saving) }
            item { OutlinedTextField(source, { if (it.length <= WorldPastedText.MAX_SOURCE) { source = it; edited() } }, Modifier.fillMaxWidth(), label = { Text("作者 / 来源链接（选填）") }, enabled = !saving) }
            item { OutlinedTextField(body, { if (it.length <= WorldReading.MAX_TEXT) { body = it; edited() } }, Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 400.dp), label = { Text("粘贴文章正文") }, enabled = !saving, maxLines = 14,
                supportingText = { Text("${body.length} / ${WorldReading.MAX_TEXT} 字符") }) }
            item { GlassButton(onClick = {
                keyboard?.hide()
                prepared.fold(onSuccess = { error = null; preview = true }, onFailure = { error = it.message })
            }, backdrop = backdrop, enabled = !saving && title.isNotBlank() && body.isNotBlank()) { Text("预览正文") } }
        } else {
            val text = prepared.getOrNull()
            if (text != null) {
                item { WorldPanel {
                    Text(text.title, color = p.content, fontSize = 18.sp)
                    Text(text.source, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp)
                    Text("${text.body.length} 字符 · 尚未保存、尚未阅读", color = p.contentSecondary, fontSize = 12.sp)
                } }
                val end = minOf(2400, text.body.length).let { if (WorldReading.boundary(text.body, it)) it else it - 1 }
                item { WorldPanel { SelectionContainer { Text(text.body.take(end), color = p.content, fontSize = 15.sp, lineHeight = 25.sp) } } }
                if (end < text.body.length) item { Text("预览显示开头，保存时保留全部粘贴文字；TA 每次阅读一段。", color = p.contentSecondary, fontSize = 12.sp) }
                item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(onClick = {
                        val revision = navigation.pasteRevision
                        saving = true; error = null
                        c.appScope.launch {
                            try {
                                val id = c.world.addPasted(ta.id, ta.createdAt, revision, title, source, body)
                                if (navigation.selectedId.value == ta.id && c.world.revision() == revision) {
                                    navigation.clearPaste(); saved(id)
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                if (navigation.selectedId.value == ta.id) error = e.message ?: "没有保存，请稍后重试"
                            } finally { if (navigation.selectedId.value == ta.id) saving = false }
                        }
                    }, backdrop = backdrop, enabled = !saving) { Text(if (saving) "正在保存…" else "保存到书房") }
                    TextButton(onClick = { preview = false }, enabled = !saving) { Text("返回修改") }
                } }
            }
        }
        error?.let { item { Text(it, color = p.error, fontSize = 13.sp, lineHeight = 22.sp) } }
        item { Text("导入和预览不调用模型、不占阅读次数。保存后再点「让 TA 读这一篇」，才将角色设定、少量近期笔记、标题、来源和本次正文片段交给 TA 的模型。TA 可以暂停或不记笔记，未完成的阅读不会提前计入进度。相同正文会复用已有读物。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp) }
        item { Text("未保存的草稿只暂存在本次 App 进程中；关闭 App 后可能丢失。切换 TA 会清空草稿。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp) }
    }
}

@Composable
internal fun WorldStudyReadAction(ta: CompanionEntity, article: com.cleo.cleos.data.db.WorldArticleEntity) {
    val c = appContainer(); val p = LocalGlassPalette.current
    val busy by c.world.busy.collectAsStateWithLifecycle()
    val state by remember(ta.id) { c.db.world().observeState(ta.id) }.collectAsStateWithLifecycle(null)
    val working = ta.id in busy
    val used = state?.takeIf { it.day == java.time.LocalDate.now().toEpochDay() }?.let { (it.attempts - if (it.token != null) 1 else 0).coerceAtLeast(0) } ?: 0
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { c.world.startArticle(ta.id, ta.createdAt, article) },
            enabled = !working && article.readOffset < article.content.length && used < WorldReading.DAILY) {
            Text(if (working) "TA 正在阅读" else if (article.readOffset >= article.content.length) "TA 已读完这篇" else "让 TA 读这一篇")
        }
        if (working && state?.space == "study") TextButton(onClick = { c.world.cancel(ta.id) }) { Text("停止阅读") }
    }
    Text("只阅读这篇已保存文字的一段，使用当前 TA 的模型；与书房、窗外共用今日 $used / ${WorldReading.DAILY} 次，失败或停止退回次数。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
    if (state?.space == "study" && state?.status?.isNotBlank() == true) Text(state!!.status, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp)
}
