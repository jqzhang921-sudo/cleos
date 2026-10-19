package com.cleo.cleos.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage
import java.nio.file.Files
import java.nio.file.Path

/** All media is local. No imported URL is fetched or file path opened as a program. */
internal fun localMedia(root: Path, relative: String?): Path? {
    if (relative == null || !relative.startsWith("media/") || !safeMediaName(relative.removePrefix("media/"))) return null
    if(!Files.isDirectory(root.resolve("media"),java.nio.file.LinkOption.NOFOLLOW_LINKS))return null
    val target = root.resolve(relative).normalize()
    return target.takeIf { it.startsWith(root.resolve("media").normalize()) && Files.isRegularFile(it,java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
}

@Composable internal fun rememberMedia(root: Path, relative: String?): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, root, relative) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = localMedia(root, relative) ?: return@runCatching null
                if (Files.size(file) > 20L * 1024 * 1024) return@runCatching null
                val bytes = Files.readAllBytes(file)
                Data.makeFromBytes(bytes).use { data -> Codec.makeFromData(data).use { codec ->
                    val info = codec.imageInfo
                    if (info.width <= 0 || info.height <= 0 || info.width.toLong() * info.height > 24_000_000) return@runCatching null
                } }
                SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
            }.getOrNull()
        }
    }
    return image
}

@Composable internal fun AttachmentView(root: Path, attachment: Attachment) {
    val bitmap = if (attachment.kind == "image") rememberMedia(root, attachment.file) else null
    var expanded by remember { mutableStateOf(false) }
    if (bitmap != null) {
        Image(bitmap, attachment.label.ifBlank { "从手机导入的图片" }, Modifier.fillMaxWidth().heightIn(max = 240.dp)
            .clip(RoundedCornerShape(14.dp)).clickable { expanded = true }, contentScale = ContentScale.Fit)
    if (attachment.credit.isNotBlank()) Text(attachment.credit, fontSize = 10.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if(attachment.kind=="audio") AudioAttachment(root,attachment)
    else Text(if (localMedia(root, attachment.file) == null) "附件缺失：原备份没有这个文件" else "图片已保留，当前无法预览", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    if (expanded && bitmap != null) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 900.dp).heightIn(max = 760.dp).padding(18.dp), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(16.dp)) {
                TextButton({ expanded = false }, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) { Text("关闭图片") }
                Image(bitmap, "图片大图", Modifier.weight(1f, fill = false).fillMaxWidth(), contentScale = ContentScale.Fit)
                if (attachment.credit.isNotBlank()) Text(attachment.credit, fontSize = 11.sp)
            }
        }
    }
}

internal val LocalAudioPlayback=staticCompositionLocalOf<AudioPlayback?> {null}
@Composable private fun AudioAttachment(root:Path,attachment:Attachment) {
    val player=LocalAudioPlayback.current
    val current=player?.state?.takeIf {it.file==attachment.file}
    val exists=remember(root,attachment.file) {localMedia(root,attachment.file)!=null}
    Column {
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton({player?.toggle(root,attachment.file)},enabled=exists && player!=null && current?.loading!=true) {
                Text(when {current?.loading==true->"正在读取录音…";current?.playing==true && !current.paused->"暂停录音";current?.paused==true->"继续播放";else->"播放录音"})
            }
            if(current?.playing==true) TextButton({player?.stop()}) {Text("停止")}
        }
        val seconds=current?.seconds?:0
        Text(when { !exists->"录音文件缺失";!current?.error.isNullOrBlank()->current!!.error;current?.playing==true->"%02d:%02d · 本地播放".format(seconds/60,seconds%60);else->"本地录音 · 不上传到模型"},fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun ImportedRecordsDialog(title: String, memories: List<ImportedMemory>, lore: List<ImportedLore>, dismiss: () -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(540.dp).heightIn(max = 680.dp), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(title, fontSize = 20.sp)
                Text("从手机保留的记录，当前以只读方式查看。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    memories.forEach { m -> Column {
                        Text((if (m.pinned) "置顶 · " else "") + m.title, fontSize = 15.sp)
                        androidx.compose.foundation.text.selection.SelectionContainer { Text((listOf(m.summary) + m.details).joinToString("\n"), fontSize = 13.sp) }
                    } }
                    lore.forEach { entry -> Column {
                        Text(entry.title + if (!entry.enabled) "（未启用）" else "", fontSize = 15.sp)
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(entry.content, fontSize = 13.sp) }
                    } }
                }
                TextButton(dismiss, Modifier.align(androidx.compose.ui.Alignment.End)) { Text("关闭记录") }
            }
        }
    }
}
