package com.cleo.cleos.ui.world

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cleo.cleos.data.WorldPictureSource
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun WorldPicturePanel(ta: CompanionEntity, article: WorldArticleEntity) {
    val c = appContainer(); val p = LocalGlassPalette.current; val scope = rememberCoroutineScope(); val uri = LocalUriHandler.current
    val image by remember(article.id) { c.db.world().observePicture(article.id) }.collectAsStateWithLifecycle(null)
    val states by c.worldPictures.states.collectAsStateWithLifecycle()
    val state = states[article.id]
    var credits by rememberSaveable(article.id) { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<WorldPictureEntity?>(null) }
    var error by remember(article.id) { mutableStateOf<String?>(null) }
    var failed by remember(image?.file) { mutableStateOf(false) }
    val pic = image?.takeIf { it.companionId == ta.id && it.fingerprint == article.fingerprint }
    HorizontalDivider(color = p.contentSecondary.copy(alpha = .18f))
    Text("从条目里看一眼", color = p.content, fontSize = 17.sp)
    if (pic == null) {
        Text("手动获取一张条目关联的公开配图，最多 2 MB，保留作者和许可。获取本身不发送模型；之后可以单独让 TA 看图，不改变摘要阅读进度。", color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp)
        TextButton(onClick = { if (state?.busy == true) c.worldPictures.cancel(article.id) else c.worldPictures.fetch(ta.id, ta.createdAt, article.id, article.fingerprint) }) {
            Text(if (state?.busy == true) "停止获取配图" else "获取条目配图")
        }
    } else {
        if (!failed) AsyncImage(model = c.images.file(pic.file), contentDescription = "条目关联图片：${pic.title}；作者 ${pic.author}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio((pic.width.toFloat() / pic.height).coerceIn(.75f, 2f)).clip(RoundedCornerShape(18.dp)),
            onError = { failed = true })
        else Text("本地配图暂时无法显示，可查看来源或移除后重新获取。", color = p.contentSecondary, fontSize = 13.sp)
        pic.description.takeIf { it.isNotBlank() }?.let { Text(it, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp) }
        Text("${pic.author} · ${pic.license}", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 19.sp)
        Text("条目关联配图 · 已缩放转存", color = p.contentSecondary, fontSize = 12.sp)
        WorldPictureLookAction(ta, pic)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { credits = !credits }) { Text(if (credits) "收起署名" else "署名与来源") }
            TextButton(onClick = { confirm = pic }) { Text("移除配图", color = p.error) }
        }
        if (credits) {
            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pic.title, color = p.contentSecondary, fontSize = 12.sp)
                if (pic.credit.isNotBlank()) Text(pic.credit, color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
                Text(pic.sourceUrl, color = p.contentSecondary, fontSize = 12.sp)
                Text(pic.licenseUrl, color = p.contentSecondary, fontSize = 12.sp)
            } }
            TextButton(onClick = { error = if (runCatching { check(WorldPictureSource.validSource(pic.sourceUrl)); uri.openUri(pic.sourceUrl) }.isFailure) "没有打开来源，可以复制上方链接" else null }) { Text("在浏览器查看图片来源") }
        }
    }
    state?.message?.takeIf { it.isNotBlank() && (pic == null || state.busy) }?.let { Text(it, color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp) }
    error?.let { Text(it, color = p.error, fontSize = 13.sp) }
    confirm?.let { expected -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("移除这张配图？") },
        text = { Text("配图和由它产生的看图记录会一起移除；原有摘要、摘要阅读位置和文字阅读笔记保留。") },
        confirmButton = { TextButton(onClick = { confirm = null; scope.launch {
            try { c.worldPictures.remove(ta.id, ta.createdAt, expected); error = null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "配图已变化，请重新打开后操作" }
        } }) { Text("移除", color = p.error) } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
}
