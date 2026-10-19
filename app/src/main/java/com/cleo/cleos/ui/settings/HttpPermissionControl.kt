package com.cleo.cleos.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.glass.*
import com.cleo.cleos.network.EndpointTransport
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun HttpPermissionControl(url: String, mcpId: String? = null) {
    val origin = EndpointTransport.httpOrigin(url) ?: return
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val approvals by c.httpPermissions.approvals.collectAsStateWithLifecycle(null)
    val allowed = approvals?.contains(c.httpPermissions.key(origin, mcpId)) == true
    val scope = rememberCoroutineScope()
    var confirming by remember(origin, mcpId) { mutableStateOf(false) }
    var saving by remember(origin, mcpId) { mutableStateOf(false) }
    var problem by remember(origin, mcpId) { mutableStateOf<String?>(null) }
    fun change(on: Boolean) {
        if (saving) return
        saving = true; problem = null
        scope.launch {
            try { c.httpPermissions.set(origin, on, mcpId); confirming = false }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { problem = "HTTP 授权没能保存，请再试一次" }
            finally { saving = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("允许 HTTP 明文连接", color = palette.content, fontSize = 14.sp)
                Text(if (allowed) "已授权此主机和端口" else "尚未授权，连接前需要确认风险", color = palette.contentSecondary, fontSize = 12.sp)
            }
            Switch(allowed, { if (it) confirming = true else change(false) }, enabled = !saving && approvals != null,
                colors = glassSwitchColors(), modifier = Modifier.semantics { contentDescription = "允许 HTTP 明文连接" })
        }
        Text(origin, color = palette.contentSecondary, fontSize = 12.sp)
        Text("关闭后阻止新发起的请求；进行中的请求不会自动中止。", color = palette.contentSecondary, fontSize = 11.sp)
        problem?.let { Text(it, color = palette.error, fontSize = 12.sp) }
    }
    if (confirming) Dialog(onDismissRequest = { if (!saving) confirming = false }) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(22.dp)) {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("允许这个 HTTP 地址？", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(origin, color = palette.content, fontSize = 14.sp)
                Text(if (mcpId == null) "聊天内容、记忆、图片和 API Key 可能被网络中间人读取或篡改。Key 在手机上加密保存，也不能保护 HTTP 传输。"
                    else "MCP 的 Token、请求头、工具参数和返回结果可能被网络中间人读取或篡改，工具描述也可能被替换。下单、付款等服务尤其需要谨慎。",
                    color = palette.content, fontSize = 14.sp, lineHeight = 21.sp)
                Text("建议仅用于你信任的自建或局域网服务；公网接口优先使用 HTTPS。授权范围是这个主机和端口的所有路径，新的主机或端口需另行授权。",
                    color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                Text(if (mcpId == null) "同一地址上的聊天和通话模型共用此授权。" else "本次只授权这个 MCP 服务，不替其他服务开启。",
                    color = palette.contentSecondary, fontSize = 12.sp)
                problem?.let { Text(it, color = palette.error, fontSize = 12.sp) }
                Row(Modifier.align(Alignment.End)) {
                    TextButton({ confirming = false }, enabled = !saving) { Text("取消", color = palette.content) }
                    TextButton({ change(true) }, enabled = !saving) { Text(if (saving) "正在保存…" else "了解风险，允许", color = palette.error) }
                }
            }
        }
    }
}
