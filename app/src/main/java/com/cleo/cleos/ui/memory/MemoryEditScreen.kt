package com.cleo.cleos.ui.memory

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.rememberCoroutineScope
import com.cleo.cleos.data.MemoryRules
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.ai.MemoryKinds
import com.cleo.cleos.data.MemoryDetails
import com.cleo.cleos.data.MemoryContext
import com.cleo.cleos.data.db.MemoryEntity
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.appViewModel
import kotlinx.coroutines.launch

/**
 * One memory, as the person edits it. Saved on leaving; a new one is kept only once it has
 * a name and a line. Details that are left empty are dropped.
 */
class MemoryEditViewModel(private val c: AppContainer, startId: Long) : ViewModel() {
    var id by mutableLongStateOf(startId)
        private set
    var companionId by mutableLongStateOf(0L)
        private set
    var kind by mutableStateOf("profile")
    var name by mutableStateOf("")
    var summary by mutableStateOf("")
    val details = mutableStateListOf<String>()
    var pinned by mutableStateOf(false)
    var temporalState by mutableStateOf("current")
    var validFrom by mutableStateOf("")
    var validUntil by mutableStateOf("")
    var loaded by mutableStateOf(false)
        private set
    private var original: MemoryEntity? = null
    private var deleted = false
    var busy by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            val m = if (startId == 0L) null else c.db.memories().get(startId)
            if (m == null) {
                companionId = c.companions.current().id
            } else {
                original = m
                companionId = m.companionId
                kind = m.kind
                name = m.name
                summary = m.summary
                details.addAll(MemoryDetails.decode(m.details))
                pinned = m.pinned
                temporalState = m.temporalState
                validFrom = MemoryContext.date(m.validFromDay)
                validUntil = MemoryContext.date(m.validUntilDay)
            }
            loaded = true
        }
    }

    fun delete(then: () -> Unit) {
        if(busy || !loaded)return
        busy=true;problem=null
        viewModelScope.launch {
            try {
                original?.let { c.memoryStore.forget(companionId,it) }
                deleted=true;then()
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {problem=e.message ?: "未删除记忆"}
            finally {busy=false}
        }
    }

    fun draft(): MemoryEntity {
        val base = original ?: MemoryEntity(companionId = companionId, kind = kind, name = name, summary = summary, createdAt = 0, updatedAt = 0)
        val next = base.copy(kind = kind, name = name, summary = summary,
            details = MemoryDetails.encode(details.map(String::trim).filter(String::isNotEmpty)), pinned = pinned, temporalState = temporalState)
        val dated = runCatching { next.copy(validFromDay = MemoryContext.day(validFrom), validUntilDay = MemoryContext.day(validUntil)) }
            .getOrNull()?.takeIf(MemoryContext::valid)
        return MemoryContext.revised(original, dated ?: next)
    }
    fun dateProblem(): Boolean = runCatching {
        val from = MemoryContext.day(validFrom); val until = MemoryContext.day(validUntil)
        from != null && until != null && from > until
    }.getOrDefault(true)

    fun save(then:()->Unit) {
        if(busy || !loaded || deleted)return
        if(original==null && id!=0L) {then();return}
        val n=name.trim();val line=summary.trim()
        if(original==null && n.isEmpty() && line.isEmpty()) {then();return}
        if(n.isEmpty() || line.isEmpty()) {problem="话题和一句话不能为空";return}
        val base=draft().copy(name=n,summary=line,details=MemoryDetails.encode(MemoryRules.unique(details.toList())))
        val previous=original
        if(previous!=null && base==previous) {then();return}
        busy=true;problem=null
        viewModelScope.launch {
            try {
                val now=System.currentTimeMillis()
                val next=base.copy(source=MemoryEntity.SOURCE_ME,createdAt=previous?.createdAt ?: now,updatedAt=now)
                val event=c.memoryStore.write(companionId,listOfNotNull(previous),listOf(next),"manual","user")
                original=MemoryRules.decode(event.afterRows).single();id=original!!.id
                then()
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {problem=e.message ?: "未保存记忆"}
            finally {busy=false}
        }
    }

}

@Composable
fun MemoryEditScreen(id: Long, onBack: () -> Unit, onFound: () -> Unit = {}) {
    val vm = appViewModel(key = "memory-$id") { MemoryEditViewModel(it, id) }
    val c = appContainer()
    val scope = rememberCoroutineScope()
    BackHandler { vm.save(onBack) }
    val palette = LocalGlassPalette.current
    val density = LocalDensity.current
    val companions by remember { c.companions.all }.collectAsStateWithLifecycle(emptyList())
    val ta = companions.firstOrNull { it.id == vm.companionId }?.name?.trim()?.ifEmpty { null } ?: "TA"
    var confirmDelete by remember { mutableStateOf(false) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val bottom = if (imeBottom > navBottom) imeBottom else navBottom

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = if (id == 0L) "自己加一条" else vm.name.ifBlank { "记忆" },
                subtitle = vm.problem ?: if (id == 0L) "要有名字和一句话才会存" else if(vm.busy) "正在保存…" else "离开时保存",
                backdrop = page,
                leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", { vm.save(onBack) }, page) },
                trailing = {
                    if (id != 0L) GlassIconButton(Icons.Rounded.DeleteOutline, "删除", { confirmDelete = true }, page)
                },
            )
        },
    ) {
        if (!vm.loaded) return@GlassPage
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = statusTop + TopBarHeight + 10.dp, bottom = bottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            vm.problem?.let { problem ->
                Text(problem,color=palette.error,fontSize=13.sp)
                TextButton(onClick=onBack,enabled=!vm.busy) {Text("放弃本页未保存修改")}
            }
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = GlassShape.Rounded(24.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Wrapping, not scrolling sideways: all five kinds in sight, none hidden past the edge.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (k in MemoryKinds.all) {
                            val label = if (k.key == "self") "${ta}自己的事" else k.label
                            Pill(label, selected = vm.kind == k.key) { vm.kind = k.key }
                        }
                    }
                    OutlinedTextField(
                        value = vm.name,
                        onValueChange = { vm.name = it.take(30) },
                        label = { Text("话题") },
                        placeholder = { Text("比如：怎么称呼") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = vm.summary,
                        onValueChange = { vm.summary = it.take(120) },
                        label = { Text("一句话") },
                        placeholder = { Text("这条讲什么；$ta 每次都看得到这一句") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = GlassShape.Rounded(24.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("细节", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("$ta 要用时才会打开看。", color = palette.contentSecondary, fontSize = 12.sp)
                    vm.details.forEachIndexed { i, d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = d,
                                onValueChange = { vm.details[i] = it.take(300) },
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { vm.details.removeAt(i) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "去掉这条", tint = palette.contentSecondary)
                            }
                        }
                    }
                    if (vm.details.size < MemoryKinds.DETAILS) {
                        Pill("加一条细节", selected = false) { vm.details.add("") }
                    }
                }
            }
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = GlassShape.Rounded(24.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("时间与来源", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("当前情况", vm.temporalState == "current") { vm.temporalState = "current" }
                        Pill("过去经历", vm.temporalState == "past") { vm.temporalState = "past" }
                    }
                    Text("已结束的事情可以留作经历；有明确日期再填写，到期后作为历史参考。", color = palette.contentSecondary, fontSize = 12.sp)
                    OutlinedTextField(vm.validFrom, { vm.validFrom = it.take(10) }, label = { Text("适用起始日（可空）") },
                        placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(vm.validUntil, { vm.validUntil = it.take(10) }, label = { Text("适用结束日（含当天，可空）") },
                        placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (vm.dateProblem()) Text("日期格式或先后顺序有误，离开时会保留原日期。", color = palette.error, fontSize = 12.sp)
                    Text(MemoryContext.timing(vm.draft()), color = palette.contentSecondary, fontSize = 12.sp)
                    MemorySources(vm.draft()) { message -> vm.save {
                        scope.launch {
                            c.settings.setCurrentCompanion(vm.companionId)
                            c.chat.show(message)
                            c.opening.value=com.cleo.cleos.Opening.Chat(message.conversationId)
                            onFound()
                        }
                    } }
                }
            }
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = GlassShape.Rounded(24.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = vm.pinned, interactionSource = null, indication = null, role = Role.Switch, onValueChange = { vm.pinned = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("钉住", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("钉住的这条，$ta 只能看，不能改，也不能删。", color = palette.contentSecondary, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = vm.pinned,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = palette.accent,
                            checkedBorderColor = palette.accent,
                            uncheckedThumbColor = palette.contentSecondary,
                            uncheckedTrackColor = palette.content.copy(alpha = 0.07f),
                            uncheckedBorderColor = palette.contentSecondary.copy(alpha = 0.6f),
                        ),
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删掉这条记忆？") },
            text = { Text("会删除这条记忆及相关改动历史，防止相同内容原样自动写回；聊天原文保留。删除后不能从改动记录撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(onBack)
                }) { Text("删掉", color = palette.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/** A plain pill, not glass: it sits on a glass card (see the settings screen's chips). */
@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .background(if (selected) palette.accent else palette.content.copy(alpha = 0.07f), CircleShape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (selected) Color.White else palette.content, fontSize = 14.sp)
    }
}
