package com.cleo.cleos.ui.world

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.WorldReading
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.appContainer
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
internal fun WorldPictureLookAction(ta: CompanionEntity, picture: WorldPictureEntity) {
    val c = appContainer(); val p = LocalGlassPalette.current
    val busy by c.world.busy.collectAsStateWithLifecycle()
    val state by remember(ta.id) { c.db.world().observeState(ta.id) }.collectAsStateWithLifecycle(null)
    val looks by remember(ta.id) { c.db.world().observePictureLooks(ta.id) }.collectAsStateWithLifecycle(emptyList())
    val latest = looks.firstOrNull { it.articleId == picture.articleId && it.pictureSha256 == picture.sha256 }
    val looking = ta.id in busy && c.world.lookingAt(ta.id, picture.articleId)
    val used = state?.takeIf { it.day == LocalDate.now().toEpochDay() }?.let { (it.attempts - if (it.token != null) 1 else 0).coerceAtLeast(0) } ?: 0
    Text(latest?.let { "TA 看过这张图 · " + Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) }
        ?: "TA 尚未确认看过这张图", color = p.contentSecondary, fontSize = 12.sp)
    Text("让 TA 看时，这一张图片、来源说明和角色设定会发送到这位 TA 的模型接口；需要支持看图的模型。与阅读共用每天 ${WorldReading.DAILY} 次机会，今天已用 $used 次。失败或停止会退回。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { if (looking) c.world.cancel(ta.id) else c.world.startPicture(ta.id, ta.createdAt, picture) },
            enabled = looking || ta.id !in busy && used < WorldReading.DAILY) {
            Text(if (looking) "停止看图" else if (latest != null) "让 TA 再看一会" else "让 TA 看看这张图")
        }
    }
    if (ta.id in busy && !looking) Text("TA 正在阅读其他内容，结束后再看这张图。", color = p.contentSecondary, fontSize = 12.sp)
    if (state?.space == "picture" && state?.pictureArticleId == picture.articleId) state?.status?.takeIf(String::isNotBlank)?.let { Text(it, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp) }
}
