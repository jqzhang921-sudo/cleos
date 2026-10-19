package com.cleo.cleos.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.ai.McpTool
import com.cleo.cleos.data.McpServer
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appViewModel
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID

/**
 * One MCP service, as the person sets it up. Saved on leaving, once it has an address. The
 * test lists what the service offers with the fields as they are, saved or not.
 */
class McpEditViewModel(private val c: AppContainer, startId: String) : ViewModel() {
    val isNew = startId.isEmpty()
    private val id = startId.ifEmpty { UUID.randomUUID().toString() }
    internal val httpOwner get() = id
    var name by mutableStateOf("")
    var url by mutableStateOf("")
    var token by mutableStateOf("")
    var header by mutableStateOf("")
    var askFirst by mutableStateOf(true)
    private var enabled = true
    val allowed = mutableStateListOf<String>()
    var loaded by mutableStateOf(false)
        private set
    var testing by mutableStateOf(false)
        private set
    var result by mutableStateOf<String?>(null)
        private set
    var tools by mutableStateOf<List<McpTool>>(emptyList())
        private set
    private var deleted = false

    init {
        viewModelScope.launch {
            c.mcp.servers.get(id)?.let { s ->
                name = s.name
                url = s.url
                token = s.token
                header = s.header
                askFirst = s.askFirst
                enabled = s.enabled
                allowed.addAll(s.allowed)
            }
            loaded = true
        }
    }

    private fun server() = McpServer(id, name.trim(), url.trim(), token.trim(), header.trim(), enabled, askFirst, allowed.toSet())

    fun test() {
        if (testing || url.isBlank()) return
        testing = true
        result = null
        viewModelScope.launch {
            c.mcp.test(server())
                .onSuccess { list ->
                    tools = list
                    result = if (list.isEmpty()) "连上了，但这个服务没有提供工具。" else "连上了，有 ${list.size} 个工具："
                }
                .onFailure {
                    tools = emptyList()
                    result = "没连上：${it.message ?: it.javaClass.simpleName}"
                }
            testing = false
        }
    }

    fun delete(then: () -> Unit) {
        deleted = true
        c.appScope.launch { c.mcp.servers.delete(id); c.httpPermissions.clearMcp(id) }
        then()
    }

    override fun onCleared() {
        if (!loaded || deleted) return
        if (url.isBlank()) {
            if (isNew) c.appScope.launch { c.httpPermissions.clearMcp(id) }
            return
        }
        val s = server().let { if (it.name.isEmpty()) it.copy(name = hostOf(it.url)) else it }
        c.appScope.launch { c.mcp.servers.save(s) }
    }

    private fun hostOf(url: String) = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: "外部服务"
}

@Composable
fun McpEditScreen(id: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "mcp-$id") { McpEditViewModel(it, id) }
    val palette = LocalGlassPalette.current
    val density = LocalDensity.current
    var confirmDelete by remember { mutableStateOf(false) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val bottom = if (imeBottom > navBottom) imeBottom else navBottom

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = if (vm.isNew) "添加一个服务" else vm.name.ifBlank { "外部服务" },
                subtitle = if (vm.isNew) "有地址才会存" else "离开时自动存",
                backdrop = page,
                leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) },
                trailing = {
                    if (!vm.isNew) GlassIconButton(Icons.Rounded.DeleteOutline, "删除", { confirmDelete = true }, page)
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
            Card {
                OutlinedTextField(
                    value = vm.name,
                    onValueChange = { vm.name = it.take(20) },
                    label = { Text("名字") },
                    placeholder = { Text("比如：瑞幸") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = vm.url,
                    onValueChange = { vm.url = it.trim() },
                    label = { Text("地址") },
                    placeholder = { Text("https://…/mcp") },
                    supportingText = { Text("key 写在地址里（?key=…）的服务，就填整条。") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = vm.token,
                    onValueChange = { vm.token = it },
                    label = { Text("Token") },
                    placeholder = { Text("有的服务要；粘整串「Bearer …」也行") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                HttpPermissionControl(vm.url, vm.httpOwner)
                OutlinedTextField(
                    value = vm.header,
                    onValueChange = { vm.header = it },
                    label = { Text("另一个请求头") },
                    placeholder = { Text("名字: 值；一般不用填") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Card {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = vm.askFirst, interactionSource = null, indication = null, role = Role.Switch, onValueChange = { vm.askFirst = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("调用前先问我", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "TA 每次要用这个服务的工具，先在聊天里弹一张卡片问你。服务自己标了「只读」的查询工具不问。能下单、叫车、付钱的服务，一直开着。",
                            color = palette.contentSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = vm.askFirst, onCheckedChange = null, colors = glassSwitchColors())
                }
                if (vm.allowed.isNotEmpty()) {
                    Text("说过「以后都允许」的工具，点一下改回每次都问：", color = palette.contentSecondary, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (t in vm.allowed.toList()) Pill("$t ×", selected = false) { vm.allowed.remove(t) }
                    }
                }
            }
            Card {
                Pill(if (vm.testing) "正在连接…" else "测试连接", selected = false) { vm.test() }
                vm.result?.let { Text(it, color = palette.content, fontSize = 13.sp, lineHeight = 19.sp) }
                for (t in vm.tools.take(40)) {
                    Column {
                        Text(t.title + if (t.readOnly) "（只读）" else "", color = palette.content, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        if (t.description.isNotBlank()) {
                            Text(t.description, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text(
                    "有的服务分测试地址和正式地址（比如滴滴）。正式地址是真下单、真扣钱，先拿测试地址试。",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删掉这个服务？") },
            text = { Text("TA 就不能再用它的工具了。") },
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

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = GlassShape.Rounded(24.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
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
