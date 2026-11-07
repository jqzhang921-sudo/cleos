package com.cleo.cleos.ui.world

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.WorldSerialData
import com.cleo.cleos.data.WorldSerials
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** In the navigation ViewModel, not a Bundle of private prose; survives configuration changes. */
internal class NotebookSerialDraft(val before: WorldSerialEntity?, original: List<WorldWritingEntity>, added: WorldWritingEntity? = null) {
    private val initial = original.map { it.id }
    var title by mutableStateOf(before?.title.orEmpty())
    var chapters by mutableStateOf(original + listOfNotNull(added?.takeIf { a -> original.none { it.id == a.id } }))
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val dirty get() = title != before?.title.orEmpty() || chapters.map { it.id } != initial
    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (index !in chapters.indices || target !in chapters.indices) return
        chapters = chapters.toMutableList().also { val row = it.removeAt(index); it.add(target, row) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotebookSerialEditor(owner: CompanionEntity, draft: NotebookSerialDraft, writings: List<WorldWritingEntity>,
    links: List<WorldSerialChapterEntity>, onSaved: (Long) -> Unit, onDismiss: () -> Unit,
) {
    val c = appContainer(); val p = LocalGlassPalette.current; val scope = rememberCoroutineScope()
    val repository = remember(c.db) { WorldSerials(c.db) }
    var discarding by remember { mutableStateOf(false) }
    var dissolving by remember { mutableStateOf(false) }
    val dismiss = { if (!draft.saving) { if (draft.dirty) discarding = true else onDismiss() } }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { target ->
        if (draft.saving) false
        else if (target == SheetValue.Hidden && draft.dirty) { discarding = true; false }
        else true
    })
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, containerColor = p.card.tint.copy(alpha = .98f), contentColor = p.content) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (draft.before == null) "新建连载" else "整理连载", fontSize = 19.sp, fontWeight = FontWeight.Medium)
            Text("只整理目录，原文各自保留。用上下箭头调整章节顺序。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            OutlinedTextField(draft.title, { value ->
                val limit = minOf(value.length, WorldSerialData.MAX_TITLE)
                draft.title = value.take(if (limit > 0 && limit < value.length && value[limit - 1].isHighSurrogate()) limit - 1 else limit)
            }, Modifier.fillMaxWidth(), label = { Text("连载名称") }, enabled = !draft.saving, singleLine = true)
            draft.error?.let { Text(it, color = p.error, fontSize = 12.sp) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { Text("章节 · ${draft.chapters.size} 篇", color = p.contentSecondary, fontSize = 12.sp) }
                items(draft.chapters, key = { "chosen:${it.id}" }) { row ->
                    val index = draft.chapters.indexOfFirst { it.id == row.id }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${row.title.ifBlank { "一段随笔" }}", Modifier.weight(1f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(enabled = !draft.saving && index > 0, onClick = { draft.move(index, -1) }) { Icon(Icons.Rounded.ArrowUpward, "上移第 ${index + 1} 章") }
                        IconButton(enabled = !draft.saving && index < draft.chapters.lastIndex, onClick = { draft.move(index, 1) }) { Icon(Icons.Rounded.ArrowDownward, "下移第 ${index + 1} 章") }
                        IconButton(enabled = !draft.saving, onClick = { draft.chapters = draft.chapters.filterNot { it.id == row.id } }) { Icon(Icons.Rounded.Close, "拆出：${row.title}") }
                    }
                }
                val occupied = links.filter { it.serialId != draft.before?.id }.map { it.writingId }.toSet()
                val available = writings.filter { it.companionId == owner.id && it.id !in occupied && draft.chapters.none { s -> s.id == it.id } }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text("可加入的随笔", color = p.contentSecondary, fontSize = 12.sp) }
                if (available.isEmpty()) item { Text("没有其他可加入的随笔。其他连载里的篇目可先拆出。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp) }
                items(available, key = { "available:${it.id}" }) { row ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !draft.saving) { draft.chapters = draft.chapters + row }
                        .semantics { contentDescription = "加入章节：${row.title}" }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = false, enabled = !draft.saving, onCheckedChange = { draft.chapters = draft.chapters + row })
                        Text(row.title.ifBlank { "一段随笔" }, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !draft.saving && WorldSerialData.validTitle(draft.title.trim()) && draft.chapters.isNotEmpty(), onClick = {
                    draft.saving = true; draft.error = null
                    scope.launch {
                        try { onSaved(repository.save(owner, draft.before, draft.title.trim(), draft.chapters)) }
                        catch (e: CancellationException) { throw e }
                        catch (e: IllegalArgumentException) { draft.error = e.message ?: "资料已经变化，请重新整理。" }
                        catch (_: Exception) { draft.error = "没有保存，原文和原目录保留，请稍后重试。" }
                        finally { draft.saving = false }
                    }
                }) { Text(if (draft.saving) "正在保存…" else "保存连载") }
                TextButton(enabled = !draft.saving, onClick = dismiss) { Text("取消") }
                if (draft.before != null) TextButton(enabled = !draft.saving, onClick = { dissolving = true }) { Text("解散连载", color = p.error) }
            }
        }
    }
    if (discarding) AlertDialog(onDismissRequest = { discarding = false }, title = { Text("放弃这次整理？") },
        text = { Text("未保存的名称和章节调整会丢弃，原文和已保存目录保持。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { discarding = false }) { Text("继续整理") } })
    if (dissolving) AlertDialog(onDismissRequest = { if (!draft.saving) dissolving = false }, title = { Text("解散这本连载？") },
        text = { Text("只移除名称和归册关系，所有篇目原文都会回到散页。") },
        confirmButton = { TextButton(enabled = !draft.saving, onClick = {
            draft.saving = true
            scope.launch {
                try { repository.dissolve(owner, requireNotNull(draft.before)); onSaved(0) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { draft.error = "连载已经变化，尚未解散，请重新打开整理。"; dissolving = false }
                finally { draft.saving = false }
            }
        }) { Text("仅解散目录", color = p.error) } },
        dismissButton = { TextButton(enabled = !draft.saving, onClick = { dissolving = false }) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotebookSerialPicker(row: WorldWritingEntity, books: List<WorldSerialEntity>, onPick: (WorldSerialEntity?) -> Unit, onDismiss: () -> Unit) {
    val p = LocalGlassPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.card.tint.copy(alpha = .98f), contentColor = p.content) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp).padding(horizontal = 24.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text("把这一篇归入连载", fontSize = 18.sp); Text(row.title, color = p.contentSecondary, fontSize = 13.sp, maxLines = 2) }
            item { TextButton(onClick = { onPick(null) }) { Text("新建连载") } }
            items(books, key = { it.id }) { book ->
                TextButton(onClick = { onPick(book) }) { Text("加入《${book.title}》", maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}
