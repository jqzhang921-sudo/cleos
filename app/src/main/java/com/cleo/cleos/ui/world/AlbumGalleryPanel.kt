package com.cleo.cleos.ui.world

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.LocalPageShown
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AlbumGalleryPanel(ta: CompanionEntity, savedCount: Int) {
    val c = appContainer(); val repo = c.worldAlbumGallery; val p = LocalGlassPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by repo.state.collectAsStateWithLifecycle()
    val link = state.links.firstOrNull { it.owner == ta.id && it.ownerCreatedAt == ta.createdAt }
    var access by remember { mutableStateOf(repo.media.access()) }
    var albums by remember { mutableStateOf<List<PhoneAlbum>?>(null) }
    var selected by remember { mutableStateOf<PhoneAlbum?>(null) }
    var future by remember { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var pendingOwner by rememberSaveable { mutableLongStateOf(0) }
    var pendingCreated by rememberSaveable { mutableLongStateOf(0) }
    fun load() { scope.launch {
        busy = true; error = ""
        try {
            access = repo.media.access()
            albums = repo.media.albums()
            if (albums!!.isEmpty()) error = "没有可访问的本地图集。可以调整照片权限，或继续手动选择照片。"
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "图集暂时无法读取，请确认照片权限后重试。" }
        finally { busy = false }
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingOwner == ta.id && pendingCreated == ta.createdAt) load()
        pendingOwner = 0
    }
    fun request() {
        pendingOwner = ta.id; pendingCreated = ta.createdAt
        permission.launch(PhoneGallery.permissions())
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val shown = LocalPageShown.current
    LaunchedEffect(ta.id, shown, lifecycle) {
        if (shown) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            access = repo.media.access(); repo.reconcile(ta)
            kotlinx.coroutines.awaitCancellation()
        }
    }
    AlbumCard {
        Text("关联手机图集", color = p.content, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(link?.let { "${it.album.name} · " + when {
            !it.active || it.access != access -> "权限已变化，需重新关联"
            it.includeFuture -> "包含以后新增照片"
            else -> "仅关联时可访问的照片"
        } } ?: "为${ta.name.ifBlank { "TA" }}指定一个图集，不用逐张添加。", color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp)
        if (link != null) Text("下次自主翻看时，只从授权范围里挑一张；也可以暂时不看。关联本身不会开启自主翻看。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        if (savedCount >= WorldAlbumData.MAX_PHOTOS) Text("已保存 ${WorldAlbumData.MAX_PHOTOS} 张照片，请先整理一些，再接收图集中的新照片。", color = p.contentSecondary, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { explain = true }, enabled = !busy) { Text(if (busy) "正在读取…" else if (link == null) "选择手机图集" else "重新关联") }
            if (link != null) TextButton(onClick = { removing = true }, enabled = !busy) { Text("解除关联", color = p.error) }
        }
        if (error.isNotBlank()) Text(error, color = p.error, fontSize = 12.sp, lineHeight = 20.sp)
        if (access == GalleryAccess.None && error.isNotBlank()) TextButton(onClick = {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}")))
        }) { Text("打开系统权限设置") }
    }
    if (explain) AlertDialog(onDismissRequest = { explain = false }, title = { Text("选择可看的手机图集") },
        text = { Text("系统照片权限可能覆盖多个图集；Cleos 只按你接下来为这位 TA 选择的图集读取。你也可以在系统弹窗中只允许部分照片。\n\n先读取图集名称与照片日期，不会批量上传。自主翻看开启后，TA 选中时才保存并发送这一张照片。只支持系统提供的本地图集，云端或私密图集可能不会显示。", modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { explain = false; if (repo.media.access() == GalleryAccess.None) request() else load() }) { Text("继续选择") } },
        dismissButton = { TextButton(onClick = { explain = false }) { Text("取消") } })
    albums?.let { rows -> AlertDialog(onDismissRequest = { albums = null }, title = { Text("为${ta.name.ifBlank { "TA" }}选择图集") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (access == GalleryAccess.All) "系统已允许访问照片；这位 TA 只使用你选择的图集。" else "仅显示系统已授权的照片，不包含图集中其他照片。", fontSize = 13.sp)
            LazyColumn(Modifier.heightIn(max = 360.dp)) { items(rows, key = { "${it.volume}/${it.bucket}" }) { row ->
                TextButton(onClick = { selected = row; future = false; albums = null }, modifier = Modifier.fillMaxWidth()) { Text("${row.name} · ${row.count} 张") }
            } }
            TextButton(onClick = { albums = null; request() }) { Text("调整照片权限") }
        } }, confirmButton = { TextButton(onClick = { albums = null }) { Text("取消") } }) }
    selected?.let { album -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("关联「${album.name}」") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("只对${ta.name.ifBlank { "TA" }}生效。默认只使用关联时系统允许访问的照片。照片或权限变化后会重新核对。", fontSize = 14.sp)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("包含以后新增的照片", modifier = Modifier.weight(1f), fontSize = 14.sp)
                Switch(checked = future, onCheckedChange = { future = it }, enabled = access == GalleryAccess.All,
                    modifier = Modifier.semantics { contentDescription = "包含以后新增的照片" })
            }
            if (access != GalleryAccess.All) Text("仅授权部分照片时不能自动包含新照片；以后可以重新选择授权范围。", fontSize = 12.sp)
            Text("已有的自主翻看开关继续生效。若已开启，后续可能把选中的照片发送给这位 TA 当前的模型接口；相册最多保存 ${WorldAlbumData.MAX_PHOTOS} 张，满后提示整理。", fontSize = 12.sp)
        } }, confirmButton = { TextButton(onClick = { val includeFuture = future; selected = null; scope.launch {
            busy = true; error = ""
            try { repo.bind(ta, album, includeFuture) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "关联未完成。请确认图集不为空且不超过一万张，并检查照片权限后重新选择。" }
            finally { busy = false }
        } }) { Text("确认关联") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } }) }
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text("解除手机图集关联？") },
        text = { Text("停止后续读取与当前自主翻看。已保存在 Cleos 的照片和感想保留，关联照片不会再自动翻看，仍可手动邀请重看。手机原照片不会删除。") },
        confirmButton = { TextButton(onClick = { removing = false; scope.launch {
            try { repo.unlink(ta) } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "解除未完成，请重试。" }
        } }) { Text("解除关联") } }, dismissButton = { TextButton(onClick = { removing = false }) { Text("取消") } })
}
