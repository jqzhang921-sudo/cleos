package com.cleo.cleos.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable internal fun DesktopSettingsPage(vm:DesktopController,libraryName:String,
    receivePhone:(()->Unit)?,importBackup:(()->Unit)?,desktopBackup:(()->Unit)?,modifier:Modifier=Modifier,back:()->Unit) {
    var voiceSettings by remember {mutableStateOf(false)}
        Surface(modifier.fillMaxSize(),shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.98f)) {
            Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("设置",Modifier.weight(1f),fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                    TextButton(back){Text("返回聊天")}
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text("数据与备份",fontSize=18.sp,fontWeight=FontWeight.Medium)
                    Text("当前资料库 · $libraryName",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    if(receivePhone!=null)SettingsAction("扫码接收手机","手机与电脑连接同一 Wi-Fi，用手机扫描二维码带入资料。","↔",receivePhone)
                    if(importBackup!=null)SettingsAction("导入手机备份","选择手机导出的 ZIP，先预览，再建立单独资料库。","↓",importBackup)
                    if(desktopBackup!=null)SettingsAction("电脑备份","导出当前资料库，或从电脑备份中恢复资料。","▤",desktopBackup)
                    Text("导入后可以从个人菜单切换资料库。模型配置在各位 TA 的「角色与模型设置」中。",fontSize=12.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingsAction("语音转文字","配置识别服务地址、模型和单独保存的 Key。录音不会自动上传。","◉",{voiceSettings=true})
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outline.copy(alpha=.25f))
                Text("Cleos · 电脑版预览 $DESKTOP_VERSION",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(voiceSettings)VoiceSettingsDialog(vm){voiceSettings=false}
}
@Composable private fun SettingsAction(title:String,detail:String,symbol:String,action:()->Unit) {
    Surface(onClick=action,modifier=Modifier.fillMaxWidth().semantics {contentDescription=title},shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.primary.copy(alpha=.055f)) {
        Row(Modifier.padding(18.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(symbol,fontSize=24.sp,color=MaterialTheme.colorScheme.primary,modifier=Modifier.width(28.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(title,fontSize=15.sp,fontWeight=FontWeight.Medium)
                Text(detail,fontSize=12.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›",fontSize=22.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
