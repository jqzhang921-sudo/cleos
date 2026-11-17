package com.cleo.cleos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.ai.InstantLetterRules
import com.cleo.cleos.ai.ToolGroup
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.appContainer

@Composable
internal fun InstantLetterSection(vm: SettingsViewModel) {
    val settings by appContainer().settings.settings.collectAsStateWithLifecycle(AppSettings())
    val color = LocalGlassPalette.current.contentSecondary
    var themes by remember { mutableStateOf(false) }
    if(themes) com.cleo.cleos.ui.letters.EnvelopeThemePicker(onDismiss={themes=false})
    Section("即时写信") {
        Text("这位 TA 什么时候可以写信", color = LocalGlassPalette.current.content, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(InstantLetterRules.REQUESTED to "仅请求时", InstantLetterRules.AUTO to "允许主动写", InstantLetterRules.OFF to "关闭").forEach { (mode,label) ->
                Chip(label, selected = vm.instantLetterMode == mode) { vm.chooseInstantLetterMode(mode) }
            }
        }
        Text(when (vm.instantLetterMode) {
            InstantLetterRules.AUTO -> "TA 可以在重要或适合完整表达的情境中寄信，也可以继续普通聊天。自动写信至少隔 6 小时，最近 24 小时最多 2 封；你明确请求时不受这两项限制。同一轮只寄一封，删除信件不会重置次数。"
            InstantLetterRules.OFF -> "这位 TA 不会生成即时信件。你仍可自己写信，原有回忆来信与延时回信保持原设置。"
            else -> "默认只在你明确请 TA 写信时寄出；也可以在聊天「＋」里点击「请 TA 写信」。请求不受自动写信冷却限制，同一轮只寄一封。"
        }, color = color, fontSize = 12.sp, lineHeight = 18.sp)
        if (ToolGroup.Letters !in settings.tools) Text("工具权限中的信件功能目前关闭；开启后 TA 才能即时写信。", color=color,fontSize=12.sp)
        Text("信封沿用你的默认主题，寄出后样式固定。即时信也会保存在「和 TA 的信」里。",color=color,fontSize=12.sp,lineHeight=18.sp)
        TextButton(onClick={themes=true}) {Text("信封主题与默认样式")}
    }
}
