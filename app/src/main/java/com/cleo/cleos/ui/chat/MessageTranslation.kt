package com.cleo.cleos.ui.chat

import android.content.ClipData
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.*
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.launch

internal class TranslationUi(val key: TranslationKey, val stamp: String, val open: MutableState<Boolean>)

@Composable
internal fun rememberTranslationUi(key: TranslationKey, stamp: String?): TranslationUi? {
    val open = remember(key, stamp) { mutableStateOf(false) }
    return remember(key, stamp) { stamp?.let { TranslationUi(key, it, open) } }
}

/** Local caption below the original bubble. Nothing here writes a chat message or memory. */
@Composable
internal fun MessageTranslation(ui: TranslationUi?) {
    if (ui == null) return
    val c = appContainer(); val p = LocalGlassPalette.current; val clipboard = LocalClipboard.current; val scope = rememberCoroutineScope()
    val saved by remember(ui.key) { c.translations.observe(ui.key) }.collectAsStateWithLifecycle(emptyList())
    val states by c.translations.states.collectAsStateWithLifecycle()
    val task = states[ui.key]?.takeIf { it.sourceStamp == ui.stamp }
    var language by rememberSaveable(ui.key.group, ui.key.id, ui.stamp) { mutableStateOf("zh-CN") }
    var expanded by rememberSaveable(ui.key.group, ui.key.id, ui.stamp) { mutableStateOf(true) }
    var picking by remember(ui.key) { mutableStateOf("zh-CN") }
    var restored by rememberSaveable(ui.key.group, ui.key.id, ui.stamp) { mutableStateOf(false) }
    LaunchedEffect(saved, ui.stamp) {
        if (!restored && task?.busy != true) saved.firstOrNull { it.sourceStamp == ui.stamp }?.let { language = it.language; picking = it.language; restored = true }
    }
    val cached = saved.firstOrNull { it.sourceStamp == ui.stamp && it.language == language }
    var menu by remember(ui.key, ui.stamp) { mutableStateOf(false) }
    if (task?.busy == true || task?.error?.isNotBlank() == true || cached != null) {
        Box(Modifier.widthIn(max = bubbleMaxWidth()).padding(start = 8.dp, end = 8.dp, top = 4.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (cached != null && expanded) {
                    Text(cached.text, color = p.content, fontSize = 14.sp, lineHeight = 22.sp,
                        modifier = Modifier.combinedClickable(onClick = { menu = true }, onLongClickLabel = "译文操作", onLongClick = { menu = true }))
                }
                if (cached != null) {
                    Text(if (expanded) "译文 · ${MessageTranslationRules.languages[language]}  收起" else "展开译文 · ${MessageTranslationRules.languages[language]}",
                        color = p.contentSecondary, fontSize = 11.sp, lineHeight = 16.sp,
                        modifier = Modifier.combinedClickable(onClickLabel = if (expanded) "收起译文" else "展开译文", onClick = { expanded = !expanded },
                            onLongClickLabel = "译文操作", onLongClick = { menu = true }).padding(vertical = 2.dp))
                }
                if (task?.busy == true) Text("正在翻译…  停止", color = p.contentSecondary, fontSize = 11.sp, lineHeight = 16.sp,
                    modifier = Modifier.combinedClickable(onClickLabel = "停止翻译", onClick = { c.translations.cancel(ui.key) }, onLongClick = { c.translations.cancel(ui.key) }).padding(vertical = 2.dp))
                task?.error?.takeIf(String::isNotBlank)?.let { error ->
                    Text(error, color = p.error, fontSize = 12.sp, lineHeight = 19.sp)
                    Text("重试翻译", color = p.contentSecondary, fontSize = 11.sp, lineHeight = 16.sp,
                        modifier = Modifier.combinedClickable(onClick = { picking = language; ui.open.value = true }, onLongClick = { picking = language; ui.open.value = true }).padding(vertical = 2.dp))
                }
            }
            MessageActionMenu(menu, { menu = false }, buildList {
                if (cached != null) {
                    add(MessageMenuAction("复制译文") { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("translation", cached.text))) } })
                    add(MessageMenuAction(if (expanded) "收起译文" else "展开译文") { expanded = !expanded })
                    if (task?.busy != true) add(MessageMenuAction("其他语言") { picking = language; ui.open.value = true })
                }
            }, reactions = null, onReact = {})
        }
    }
    if (ui.open.value) AlertDialog(onDismissRequest = { ui.open.value = false }, title = { Text("翻译这条消息") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("只把这条消息的文字发给对应 TA 当前配置的模型，可能产生接口用量。不会发送其他聊天、图片或声音。译文只在本机显示，不加入 TA 的上下文或记忆。",
                fontSize = 12.sp, lineHeight = 19.sp)
            MessageTranslationRules.languages.forEach { (code, name) ->
                TextButton({ picking = code }, modifier = Modifier.fillMaxWidth()) {
                    Text((if (picking == code) "✓ " else "") + name, fontWeight = if (picking == code) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
            Text("同一条消息、同一语言已有译文时直接显示，不重复请求。译文是本地缓存，不随备份迁移。", fontSize = 11.sp, lineHeight = 17.sp)
        } }, confirmButton = { TextButton({
            language = picking; restored = true; expanded = true; ui.open.value = false
            c.translations.request(ui.key, ui.stamp, language)
        }, enabled = task?.busy != true) { Text("翻译") } }, dismissButton = { TextButton({ ui.open.value = false }) { Text("取消") } })
}
