package com.cleo.cleos.ui.world

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.WorldDocument
import com.cleo.cleos.data.WorldDocumentImport
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.LocalPageShown
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.*

/** Large extracted text stays in memory, never in saved-instance-state / Binder bundles. */
@Composable
internal fun WorldArticleForm(ta: CompanionEntity, top: Dp, bottom: Dp, backdrop: Backdrop, onOnline: () -> Unit, saved: (Long) -> Unit) {
    val c = appContainer(); val context = LocalContext.current; val p = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    var paste by rememberSaveable(ta.id) { mutableStateOf(false) }
    var document by remember(ta.id) { mutableStateOf<WorldDocument?>(null) }
    var title by remember(ta.id) { mutableStateOf("") }
    var source by remember(ta.id) { mutableStateOf("") }
    var error by remember(ta.id) { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val loading = job != null
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !loading && !saving) {
            error = null; document = null; progress = "正在打开文件…"
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val parsed = withTimeout(90_000) { WorldDocumentImport.read(context, uri) { progress = it } }
                    document = parsed; title = parsed.title; source = parsed.source
                } catch (_: TimeoutCancellationException) { error = "读取时间较长，请将文件拆分后重试；本次没有保存" }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = if (e is IllegalArgumentException || e is IllegalStateException) e.message else "无法读取这份文件，可能损坏或格式不受支持；本次没有保存" }
                finally { job = null }
            }.also { it.start() }
        }
    }
    BackHandler(LocalPageShown.current && (loading || paste)) { if (loading) job?.cancel() else paste = false }
    if (paste) { WorldPasteForm(ta, top, bottom, backdrop, saved); return }
    LazyColumn(Modifier.fillMaxSize().imePadding().fadeUnderTopBar(top, bottom),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 22.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("给${ta.name.ifBlank { "TA" }}的书架添一本", color = p.content, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
        item {
            GlassSurface(Modifier.fillMaxWidth(), shape = GlassShape.Rounded(28.dp), contentPadding = PaddingValues(22.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (document == null) {
                        Icon(Icons.Rounded.UploadFile, null, tint = p.accentContent, modifier = Modifier.size(36.dp))
                        Text("从文件里选一本", color = p.content, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text("PDF · EPUB · TXT · Markdown\n每份最多 20 MB、50 万个字符。先预览，再放进书架。", color = p.contentSecondary, lineHeight = 23.sp)
                        GlassButton(onClick = { picker.launch(WorldDocumentImport.mimeTypes) }, backdrop = backdrop, enabled = !loading && !saving) { Text("选择文件") }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("读物已准备好", color = p.content, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { picker.launch(WorldDocumentImport.mimeTypes) }, enabled = !saving) { Text("换一本") }
                        }
                    }
                    Text("在手机上提取文字，导入不会调用模型。开始阅读时，才把标题、来源和本次片段发给这位 TA 的模型接口。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
                }
            }
        }
        if (loading) item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(22.dp)); Text(progress, color = p.content) }
            TextButton(onClick = { job?.cancel() }) { Text("取消读取") }
        }
        error?.let { message -> item { Text(message, color = p.error, lineHeight = 23.sp) } }
        document?.let { doc ->
            item { OutlinedTextField(title, { if (it.length <= 120) title = it }, Modifier.fillMaxWidth(), label = { Text("书名 / 标题") }, singleLine = true, enabled = !saving) }
            item { OutlinedTextField(source, { if (it.length <= 500) source = it }, Modifier.fillMaxWidth(), label = { Text("作者 / 来源") }, enabled = !saving) }
            item { Text(doc.detail, color = p.contentSecondary, lineHeight = 23.sp) }
            item {
                GlassSurface(Modifier.fillMaxWidth(), shape = GlassShape.Rounded(24.dp), contentPadding = PaddingValues(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("内容预览", color = p.content, fontWeight = FontWeight.SemiBold)
                        Text(doc.content.take(1_200) + if (doc.content.length > 1_200) "\n……" else "", color = p.contentSecondary, lineHeight = 25.sp)
                    }
                }
            }
            item {
                GlassButton(onClick = {
                    saving = true; error = null
                    scope.launch {
                        try { saved(c.world.add(ta.id, ta.createdAt, title, source, doc.content)) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "没有保存，请重试" }
                        finally { saving = false }
                    }
                }, backdrop = backdrop, enabled = !saving && title.isNotBlank()) { Text(if (saving) "正在放入…" else "放入书架") }
            }
        }
        if (document == null && !loading) item {
            TextButton(onClick = onOnline) { Text("从在线文库找读物") }
            TextButton(onClick = { paste = true }) { Text("也可以粘贴一篇短文章") }
            Text("从 GitHub 等地方下载的书，也可以通过「选择文件」导入。书架保存提取后的文字，原文件请自行保留。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        }
    }
}
