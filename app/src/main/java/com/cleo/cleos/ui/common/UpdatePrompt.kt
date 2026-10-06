package com.cleo.cleos.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.AppContainer
import com.cleo.cleos.Releases
import com.cleo.cleos.glass.*
import kotlinx.coroutines.launch

@Composable
fun UpdatePrompt(c: AppContainer) {
    val info by c.updates.available.collectAsStateWithLifecycle()
    val update = info ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalGlassPalette.current
    var opening by remember(update.version) { mutableStateOf(false) }
    var error by remember(update.version) { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (!opening) c.updates.dismiss() }) {
        GlassSurface(modifier = Modifier.fillMaxWidth(), shape = GlassShape.Rounded(28.dp), contentPadding = PaddingValues(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("发现新版本 ${update.version}", color = palette.content, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text(update.notes.ifBlank { "有新版本可用，可以去蓝奏云查看更新。" },
                    color = palette.contentSecondary, fontSize = 14.sp, lineHeight = 22.sp,
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()))
                Text("直接覆盖安装，保留聊天和日记；提取码会自动复制。", color = palette.contentSecondary, fontSize = 12.sp)
                GlassButton(onClick = {
                    opening = true
                    scope.launch {
                        try {
                            val url = Releases.reachableUrl()
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("提取码", Releases.CODE))
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            c.updates.dismiss()
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { error = "没能打开浏览器，请在关于页使用蓝奏云入口。" }
                        finally { opening = false }
                    }
                }, backdrop = LocalWallpaperBackdrop.current, enabled = !opening, modifier = Modifier.fillMaxWidth()) {
                    Text(if (opening) "正在打开…" else "去蓝奏云更新", color = palette.content)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    GlassButton({ c.updates.dismiss() }, LocalWallpaperBackdrop.current, enabled = !opening) { Text("稍后") }
                    GlassButton({ c.updates.dismiss(skip = true) }, LocalWallpaperBackdrop.current, enabled = !opening) { Text("跳过此版本") }
                }
                error?.let { Text(it, color = palette.error, fontSize = 12.sp) }
            }
        }
    }
}
