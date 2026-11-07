package com.cleo.cleos.ui.world

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.WorldOutside
import com.cleo.cleos.data.WorldReading
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.LocalGlassPalette

@Composable
internal fun WorldOutsideSourceSettings(sourceId: String, enabled: Boolean, used: Int, onSource: (String) -> Unit) {
    val p = LocalGlassPalette.current
    val source = WorldOutside.sources.firstOrNull { it.id == sourceId } ?: WorldOutside.sources.first()
    WorldPanel {
        Text("订阅来源与阅读说明", color = p.content, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorldOutside.sources.forEach { entry -> FilterChip(selected = sourceId == entry.id, enabled = enabled,
                onClick = { onSource(entry.id) }, label = { Text(entry.name) }) }
        }
        Text(source.description, color = p.contentSecondary, fontSize = 13.sp)
        Text("刷新只从所选来源获取文字摘要，不调用模型。阅读时，标题、来源和本次摘要会发到这位 TA 配置的模型接口；这里不读取网页全文。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        Text("公开配图与实际看图另有许可；取得或显示图片，不代表 TA 已经看过。私人相册不参与窗外。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        Text("与书房共用每天 ${WorldReading.DAILY} 次阅读机会，今天已用 $used 次；失败或停止会退回。刷新不扣阅读次数。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
    }
}

@Composable
internal fun WorldOutsideScheduleSettings(ta: CompanionEntity?, schedule: WorldOutsideScheduleEntity?, sourceId: String,
    changing: Boolean, error: String?, onEnabled: (Boolean) -> Unit,
) {
    val p = LocalGlassPalette.current
    val source = WorldOutside.sources.firstOrNull { it.id == sourceId } ?: WorldOutside.sources.first()
    WorldPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("让 TA 自己看看", color = p.content, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(if (schedule == null) "为这位 TA 开启自主浏览" else "已开启，不需要一直停留在窗外", color = p.contentSecondary, fontSize = 13.sp)
            }
            Switch(checked = schedule != null, enabled = ta != null && !changing,
                modifier = Modifier.semantics { contentDescription = "窗外自主浏览" }, onCheckedChange = onEnabled)
        }
        Text("开启后约 15 分钟给第一次机会，之后约每 4 小时从「${source.name}」获取摘要，让 TA 自己挑选。更换上方来源会同步修改后台来源，并重新安排首次机会。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        Text("与书房共用每日额度，不发聊天消息或朋友圈。网络、省电或正在通话可能让它晚一些；没有完成的机会不会连续补跑。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        schedule?.let { Text("下次机会：${outsideTime(it.nextAt)} 起，由系统安排。停止本次活动只结束本次；关闭这里才取消后续机会。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp) }
        Text("仅对这位 TA 和这台设备生效；恢复备份后需重新开启。", color = p.contentSecondary, fontSize = 12.sp)
        error?.let { Text(it, color = p.error, fontSize = 13.sp) }
    }
}
