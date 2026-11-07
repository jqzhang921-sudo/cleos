package com.cleo.cleos.ui.world

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.WorldWebSearch
import com.cleo.cleos.glass.LocalGlassPalette

@Composable
internal fun WorldBrowserSearch(query: String) {
    val p = LocalGlassPalette.current; val uri = LocalUriHandler.current
    val keyboard = LocalSoftwareKeyboardController.current
    val destination = remember(query) { runCatching { WorldWebSearch.bing(query) }.getOrNull() }
    var error by remember(query) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(enabled = destination != null, onClick = {
            val target = destination ?: return@TextButton
            keyboard?.hide()
            error = runCatching { uri.openUri(target) }.isFailure
        }) { Text("用 Bing 网页搜索") }
        Text("在浏览器查看结果，无需额外配置。Cleos 只带上这里的检索词，不会自动保存搜索结果或发给 TA。",
            color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        if (error) Text("没有打开浏览器，请确认已安装并启用浏览器后重试。", color = p.error, fontSize = 12.sp, lineHeight = 20.sp)
    }
}
