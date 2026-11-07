package com.cleo.cleos.ui.world

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cleo.cleos.data.WorldAlbumData
import com.cleo.cleos.data.WorldReading
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
internal fun WorldAlbumPage(ta: CompanionEntity?, top: Dp, bottom: Dp, options: Boolean, onOptions: (Boolean) -> Unit, expanded: Long, onExpanded: (Long) -> Unit, navigation: WorldNavigationModel) {
    if (ta == null) return
    key(ta.id, ta.createdAt) { AlbumContents(ta, top, bottom, options, onOptions, expanded, onExpanded, navigation) }
}

@Composable
private fun AlbumContents(ta: CompanionEntity, top: Dp, bottom: Dp, options: Boolean, onOptions: (Boolean) -> Unit, expanded: Long, onExpanded: (Long) -> Unit, navigation: WorldNavigationModel) {
    val c = appContainer(); val p = LocalGlassPalette.current; val scope = rememberCoroutineScope()
    val revision = remember(ta.id, ta.createdAt) { c.worldAlbum.revision() }
    val photoRows by remember(ta.id) { c.db.worldAlbum().observePhotos(ta.id) }.collectAsStateWithLifecycle<List<WorldAlbumPhotoEntity>?>(null)
    val photos = photoRows.orEmpty()
    val schedule by remember(ta.id) { c.db.worldAlbum().observeSchedule(ta.id) }.collectAsStateWithLifecycle(null)
    val lookRows by remember(ta.id) { c.db.worldAlbum().observeLooks(ta.id) }.collectAsStateWithLifecycle<List<WorldAlbumLookEntity>?>(null)
    val looks = lookRows.orEmpty()
    val state by remember(ta.id) { c.db.world().observeState(ta.id) }.collectAsStateWithLifecycle(null)
    val busy by c.world.busy.collectAsStateWithLifecycle()
    var importing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<WorldAlbumPhotoEntity?>(null) }
    var removing by remember { mutableStateOf<WorldAlbumPhotoEntity?>(null) }
    var sending by remember { mutableStateOf<WorldAlbumPhotoEntity?>(null) }
    var caption by rememberSaveable { mutableStateOf("") }
    // Capture the owner and restore generation before opening the picker, not when it returns.
    var pickerTa by rememberSaveable { mutableStateOf(0L) }
    var pickerCreated by rememberSaveable { mutableStateOf(0L) }
    var pickerRevision by rememberSaveable { mutableStateOf(-1L) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && pickerTa == ta.id && pickerCreated == ta.createdAt) {
            val owner = pickerTa; val incarnation = pickerCreated; val generation = pickerRevision
            scope.launch {
                importing = true
                try { c.worldAlbum.add(owner, incarnation, generation, uri); notice = if (schedule == null) "照片已添加，仅保存在这位 TA 的相册；还没有发给模型。" else "照片已添加，这位 TA 可在后续自主翻看机会选择它。" }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { notice = "照片未添加。相册可能已变化、已满，或文件无法读取；可以重新选择。" }
                finally { importing = false }
            }
        }
        pickerTa = 0L
    }
    val used = state?.takeIf { it.day == LocalDate.now().toEpochDay() }?.let { (it.attempts - if (it.token != null) 1 else 0).coerceAtLeast(0) } ?: 0
    val list = worldPageScroll(navigation, "album", ta.id, photoRows != null && lookRows != null)
    LazyColumn(Modifier.fillMaxSize().fadeUnderTopBar(top, bottom = bottom - 10.dp), state = list,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 22.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item(key = "intro") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("留几张照片，慢慢看", color = p.content, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(if (schedule == null) "添加后先留在本机。点「让 TA 看」并确认后，才发送这一张照片和说明。" else "自主翻看已开启，也包括以后为这位 TA 新添加的照片；翻看时会发给当前模型。", color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp)
                Text("${photos.size} / ${WorldAlbumData.MAX_PHOTOS} 张", color = p.contentSecondary, fontSize = 12.sp)
                TextButton(onClick = {
                    pickerTa = ta.id; pickerCreated = ta.createdAt; pickerRevision = revision
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, enabled = !importing && photos.size < WorldAlbumData.MAX_PHOTOS) { Text(if (importing) "正在保存照片…" else "选择一张照片") }
                if (notice.isNotBlank()) Text(notice, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp)
            }
        }
        item(key = "arrangements") { WorldAlbumAutonomy(ta, revision, photos.isNotEmpty(), state, options, onOptions) }
        if (photos.isEmpty()) item { Text("相册还空着。不同 TA 的照片分别选择，互相不会看到。", color = p.contentSecondary, fontSize = 15.sp, modifier = Modifier.padding(12.dp)) }
        items(photos, key = { it.id }) { photo ->
            val records = looks.filter { it.companionId == ta.id && it.photoId == photo.id && it.photoSha256 == photo.sha256 }
            val looking = ta.id in busy && c.world.lookingAtAlbum(ta.id, photo.id)
            var failed by remember(photo.file, photo.sha256) { mutableStateOf(false) }
            AlbumCard {
                Text("添加于 " + Instant.ofEpochMilli(photo.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")), color = p.contentSecondary, fontSize = 11.sp)
                if (!failed) AsyncImage(model = c.images.file(photo.file), contentDescription = "给${ta.name}的相册照片",
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth()
                        .aspectRatio((photo.width.toFloat() / photo.height).coerceIn(.7f, 1.8f)).clip(RoundedCornerShape(20.dp)), onError = { failed = true })
                else Text("本地照片暂时无法显示，说明和操作仍可使用。", color = p.contentSecondary, fontSize = 14.sp, lineHeight = 23.sp)
                if (photo.caption.isNotBlank()) SelectionContainer { Text(photo.caption, color = p.content, fontSize = 16.sp, lineHeight = 25.sp) }
                Text(if (records.isEmpty()) "TA 尚未确认看过" else "看过 ${records.size} 次 · 感想仅留在这位 TA 的相册", color = p.contentSecondary, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { if (looking) c.world.cancel(ta.id) else sending = photo },
                        enabled = looking || ta.id !in busy && used < WorldReading.DAILY) { Text(if (looking) "停止看图" else "让 TA 看") }
                    TextButton(onClick = { editing = photo; caption = photo.caption }) { Text("写说明") }
                    TextButton(onClick = { removing = photo }) { Text("移除", color = p.error) }
                }
                if (state?.space == "album" && state?.albumPhotoId == photo.id) state?.status?.takeIf(String::isNotBlank)?.let { Text(it, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp) }
                if (records.isNotEmpty()) {
                    if (expanded != photo.id) {
                        val recent = records.first()
                        HorizontalDivider(color = p.contentSecondary.copy(alpha = .14f))
                        Text("最近的感想", color = p.contentSecondary, fontSize = 11.sp)
                        Text(recent.content.ifBlank { recent.question.ifBlank { "看过了，没有留下感想。" } }, color = p.content, fontSize = 15.sp, lineHeight = 24.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onExpanded(if (expanded == photo.id) 0 else photo.id) }) { Text(if (expanded == photo.id) "收起感想" else "展开感想 · ${records.size}") }
                    if (expanded == photo.id) records.forEach { record ->
                        HorizontalDivider(color = p.contentSecondary.copy(alpha = .18f))
                        Text(Instant.ofEpochMilli(record.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")), color = p.contentSecondary, fontSize = 12.sp)
                        SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(record.content.ifBlank { if (record.question.isBlank()) "看过了，没有留下感想。" else "" }, color = p.content, fontSize = 15.sp, lineHeight = 24.sp)
                            if (record.question.isNotBlank()) Text(record.question, color = p.contentSecondary, fontSize = 15.sp, lineHeight = 24.sp)
                        } }
                    }
                }
            }
        }
    }
    sending?.let { photo -> AlertDialog(onDismissRequest = { sending = null }, title = { Text("让${ta.name.ifBlank { "TA" }}看看这张？") },
        text = { Text("这一张照片、你写的说明和 TA 的角色设定会发送到 TA 当前配置的模型接口，需要支持看图。感想保存在相册，与阅读共用每天 ${WorldReading.DAILY} 次机会，今天已用 $used 次。失败或停止会退回。") },
        confirmButton = { TextButton(onClick = { sending = null; scope.launch {
            try { c.worldAlbum.look(ta.id, ta.createdAt, revision, photo) { c.world.startAlbum(ta.id, ta.createdAt, photo) } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { notice = "相册已变化，请重新打开后再看。" }
        } }) { Text("发送这张照片") } },
        dismissButton = { TextButton(onClick = { sending = null }) { Text("取消") } }) }
    editing?.let { photo -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("照片说明") },
        text = { OutlinedTextField(caption, { caption = it.take(500) }, label = { Text("想告诉 TA 的事") }, minLines = 3) },
        confirmButton = { TextButton(onClick = { editing = null; scope.launch {
            try { c.worldAlbum.caption(ta.id, ta.createdAt, revision, photo, caption); notice = "说明已保存，未发送模型。" }
            catch (e: CancellationException) { throw e } catch (_: Exception) { notice = "说明未保存，请重新打开相册。" }
        } }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }) }
    removing?.let { photo -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除这张照片？") },
        text = { Text("这位 TA 相册里的照片和相关感想会一起移除，正在进行的看图会停止。手机原照片保留。") },
        confirmButton = { TextButton(onClick = { removing = null; scope.launch {
            try { c.worldAlbum.remove(ta.id, ta.createdAt, revision, photo); notice = "已从这位 TA 的相册移除。" }
            catch (e: CancellationException) { throw e } catch (_: Exception) { notice = "相册已变化，请重新打开后操作。" }
        } }) { Text("移除", color = p.error) } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
}

@Composable
internal fun AlbumCard(content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), shape = GlassShape.Rounded(28.dp), contentPadding = PaddingValues(22.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
