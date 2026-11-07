package com.cleo.cleos.ui.world

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.WorldOutside
import com.cleo.cleos.data.WorldOutsideSelection
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun WorldOutsideManagement(ta: CompanionEntity?, outside: List<WorldArticleEntity>, shown: List<WorldArticleEntity>,
    navigation: WorldNavigationModel, busy: Boolean) {
    val c = appContainer(); val p = LocalGlassPalette.current; val scope = rememberCoroutineScope()
    val deleting by c.world.removingOutside.collectAsStateWithLifecycle()
    val removing = ta?.id in deleting
    var managing by navigation.outsideManaging
    var filter by navigation.outsideFilter
    var selected by navigation.outsideSelected
    val revision = remember(ta?.id, ta?.createdAt) { c.world.revision() }
    var plan by remember(ta?.id, ta?.createdAt) { mutableStateOf<WorldOutsideSelection.Removal?>(null) }
    var preparing by remember(ta?.id) { mutableStateOf(false) }
    var notice by remember(ta?.id) { mutableStateOf<String?>(null) }
    val checked = WorldOutsideSelection.selected(shown, selected)
    WorldOutsideManagementFrame(managing) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("窗外 · ${outside.size} / ${WorldOutside.MAX_ITEMS} 条", fontSize = if (managing) 17.sp else 13.sp, fontWeight = FontWeight.Medium, color = p.content)
            TextButton(onClick = { managing = !managing; selected = arrayListOf(); plan = null; notice = null }, enabled = !removing && !preparing) {
                Text(if (managing) "完成管理" else "批量管理")
            }
        }
        if (managing || outside.size >= WorldOutside.MAX_ITEMS) Text(if (outside.size >= WorldOutside.MAX_ITEMS) "窗外已满，移除一些旧见闻后才能新增；已有内容不会自动覆盖。"
            else "每位 TA 最多保存 ${WorldOutside.MAX_ITEMS} 条；移除旧见闻可腾出位置。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        if (managing) {
            Text("仅整理这位 TA 的窗外。切换来源会清空选择；书房与私人相册不受影响。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("") + outside.map { it.source }.distinct().sorted()).forEach { source ->
                    FilterChip(selected = filter == source, enabled = !removing && !preparing,
                        onClick = { filter = source; selected = arrayListOf(); plan = null; notice = null },
                        label = { Text(source.ifEmpty { "全部来源" }) })
                }
            }
            Text("当前列表 ${shown.size} 条 · 已选 ${checked.size} 条", color = p.contentSecondary, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selected = ArrayList(shown.map { it.id }) }, enabled = shown.isNotEmpty() && !removing && !preparing) { Text("全选当前列表") }
                TextButton(onClick = { selected = ArrayList(WorldOutsideSelection.readIds(shown)) }, enabled = !removing && !preparing && WorldOutsideSelection.readIds(shown).isNotEmpty()) { Text("选择已读") }
                TextButton(onClick = { selected = arrayListOf() }, enabled = checked.isNotEmpty() && !removing && !preparing) { Text("清空选择") }
                TextButton(enabled = ta != null && checked.isNotEmpty() && !busy && !removing && !preparing, onClick = {
                    val target = ta ?: return@TextButton
                    val ids = checked.map { it.id }; preparing = true; notice = null
                    scope.launch {
                        try { plan = c.world.prepareOutsideRemoval(target.id, target.createdAt, revision, ids) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { notice = "见闻或角色已变化，请重新打开窗外后选择；没有删除。" }
                        finally { preparing = false }
                    }
                }) { Text("移除所选", color = p.error) }
            }
            if (busy && !removing) Text("正在刷新或阅读，完成或停止后再移除。", color = p.contentSecondary, fontSize = 12.sp)
            if (preparing || removing) Text(if (removing) "正在移除所选见闻…" else "正在核对关联内容…", color = p.contentSecondary, fontSize = 12.sp)
            notice?.let { Text(it, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp) }
        }
    }
    plan?.let { expected ->
        AlertDialog(onDismissRequest = { plan = null }, title = { Text("移除 ${expected.articles.size} 条见闻？") },
            text = { Text(expected.articles.take(3).joinToString("\n") { "《${it.title}》" } +
                (if (expected.articles.size > 3) "\n等 ${expected.articles.size} 条见闻" else "") +
                "\n\n将删除所选摘要、${expected.pictures.size} 张配图、${expected.notes.size} 条关联阅读笔记和 ${expected.looks.size} 条看图记录，无法恢复。\n\n书房、未选中的见闻和私人相册会保留。") },
            confirmButton = { TextButton(onClick = {
                plan = null
                c.appScope.launch {
                    try {
                        val count = c.world.removeOutside(expected)
                        if (navigation.selectedId.value == expected.owner && c.world.revision() == expected.revision) {
                            selected = arrayListOf(); notice = "已移除 $count 条见闻，已腾出位置。"
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        if (navigation.selectedId.value == expected.owner) notice = "这次移除未完成；见闻或关联笔记可能已变化，请重新核对后确认。"
                    }
                }
            }) { Text("确认移除", color = p.error) } },
            dismissButton = { TextButton(onClick = { plan = null }) { Text("取消") } })
    }
}

@Composable
private fun WorldOutsideManagementFrame(managing: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (managing) WorldPanel(content)
    else Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}
