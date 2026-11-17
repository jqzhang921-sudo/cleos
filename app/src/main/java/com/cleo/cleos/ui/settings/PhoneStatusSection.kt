package com.cleo.cleos.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.cleo.cleos.ai.GuardedPhoneStatus
import com.cleo.cleos.ai.PhoneStatusReader
import com.cleo.cleos.ai.ToolGroup
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.glass.LocalGlassPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PhoneStatusSection(vm: SettingsViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(settings.tools)
    val phone = remember(context) { GuardedPhoneStatus(PhoneStatusReader(context)) { latest } }
    var usageAllowed by remember { mutableStateOf(PhoneStatusReader.usageAllowed(context)) }
    var consent by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUsage by rememberSaveable { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val usageAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        usageAllowed = PhoneStatusReader.usageAllowed(context)
        val group = pendingUsage?.let { name -> ToolGroup.entries.firstOrNull { it.name == name } }
        if (group != null && usageAllowed) vm.setTool(group, true)
        else if (group != null) hint = "没有授予使用情况访问权限，TA 目前查不到时长。"
        pendingUsage = null
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        usageAllowed = PhoneStatusReader.usageAllowed(context)
        preview = null
    }
    LaunchedEffect(settings.tools) { preview = null }
    fun openUsageAccess() {
        hint = null
        try { usageAccess.launch(PhoneStatusReader.accessIntent(context)) }
        catch (_: android.content.ActivityNotFoundException) {
            try { usageAccess.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            catch (_: Exception) { pendingUsage = null; hint = "无法打开授权页，请到系统设置里搜索「使用情况访问」。" }
        }
    }
    ListCard("手机状态") {
        Text("默认关闭 · 所有 TA 共用，仅限私聊", color = palette.contentSecondary, fontSize = 12.sp)
        Text("开启后，TA 查询的数据会发送到其配置的模型接口，并保存在这次聊天的工具记录里。应用明细需单独开启，不会读取屏幕或聊天内容。", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        ExplainedSwitch("让 TA 看电量", "剩余电量与充电状态", null, ToolGroup.PhoneBattery in settings.tools) { on ->
            if (on) consent = "battery" else vm.setTool(ToolGroup.PhoneBattery, false)
        }
        RowDivider(inset = 0.dp)
        ExplainedSwitch("让 TA 看今日使用时长", "亮屏且解锁的累计时长估算", null, ToolGroup.PhoneUsage in settings.tools) { on ->
            if (on) consent = "usage" else { if (pendingUsage == ToolGroup.PhoneUsage.name) pendingUsage = null; vm.setTool(ToolGroup.PhoneUsage, false) }
        }
        RowDivider(inset = 0.dp)
        ExplainedSwitch("让 TA 看应用使用时长", "今天各应用前台时长 · 独立分享", null, ToolGroup.PhoneAppUsage in settings.tools) { on ->
            if (on) consent = "apps" else { if (pendingUsage == ToolGroup.PhoneAppUsage.name) pendingUsage = null; vm.setTool(ToolGroup.PhoneAppUsage, false) }
        }
        if ((ToolGroup.PhoneUsage in settings.tools || ToolGroup.PhoneAppUsage in settings.tools) && !usageAllowed) {
            Text("系统使用情况访问权限尚未授予或已撤回，TA 目前查不到时长。", color = palette.contentSecondary, fontSize = 12.sp)
            TextButton(onClick = { pendingUsage = null; openUsageAccess() }) { Text("去系统授权") }
        }
        hint?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp) }
        if (ToolGroup.PhoneBattery in settings.tools || ToolGroup.PhoneUsage in settings.tools || ToolGroup.PhoneAppUsage in settings.tools) {
            TextButton(enabled = !busy, onClick = {
                busy = true; preview = null
                val chosen = latest
                scope.launch {
                    try {
                        val rows = mutableListOf<String>()
                        if (ToolGroup.PhoneBattery in chosen) rows += phone.battery()
                        if (ToolGroup.PhoneUsage in chosen) rows += try { phone.usage() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { e.message ?: "暂时无法统计时长。" }
                        if (ToolGroup.PhoneAppUsage in chosen) rows += try { phone.appUsage() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { e.message ?: "暂时无法统计应用时长。" }
                        if (chosen == latest) preview = rows.joinToString("\n\n")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (chosen == latest) preview = e.message ?: "暂时无法查询。" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "正在本机查询…" else "本机查看当前状态") }
            preview?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp) }
            Text("本机查看不调用模型。TA 只在需要时查询，不会因此常驻监测或增加主动消息频率。关闭后不再提供新数据，已经发出的聊天记录不会自动撤回。", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
    consent?.let { kind ->
        val battery = kind == "battery"
        val apps = kind == "apps"
        val usageGroup = if (apps) ToolGroup.PhoneAppUsage else ToolGroup.PhoneUsage
        AlertDialog(onDismissRequest = { consent = null }, title = { Text(if (battery) "分享手机电量？" else if (apps) "分享应用使用时长？" else "分享今日使用时长？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (battery) "允许 TA 在私聊和已有的主动消息流程中查询电量与充电状态。"
                    else if (apps) "允许 TA 在私聊和已有的主动消息流程中，按需查询今天各应用的前台使用时长，最多返回时长最多的 20 项，包含应用名称（无法识别时显示应用标识）。仅开总时长不会提供这些明细。需要系统使用情况访问权限；不读取屏幕、聊天内容或逐条打开记录，后台播放不算前台使用。统计可能不完整或与系统不同。"
                    else "允许 TA 在私聊和已有的主动消息流程中查询今天亮屏且解锁的累计时长。需要你另外授予系统使用情况访问权限；统计为估算，可能与手机自带统计不同。")
                Text("查询结果会发送到该 TA 配置的模型接口，并保存在聊天工具记录中。所有 TA 共用此开关，群聊不会获得这项能力。可以随时关闭。")
            } },
            confirmButton = { TextButton(onClick = {
                consent = null; hint = null
                if (battery) vm.setTool(ToolGroup.PhoneBattery, true)
                else if (PhoneStatusReader.usageAllowed(context)) vm.setTool(usageGroup, true)
                else { pendingUsage = usageGroup.name; openUsageAccess() }
            }) { Text(if (!battery && !usageAllowed) "同意并去授权" else "允许分享") } },
            dismissButton = { TextButton(onClick = { consent = null }) { Text("暂不开启") } })
    }
}
