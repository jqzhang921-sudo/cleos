package com.cleo.cleos.ui.call

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.ai.ApiEndpoint
import com.cleo.cleos.ai.CallCaption
import com.cleo.cleos.ai.MessageTranslator
import com.cleo.cleos.data.MessageTranslationRules
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadingEdges
import com.cleo.cleos.ui.common.GlassFloatingPanel
import com.cleo.cleos.ui.common.systemMotionEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun CallCaptionHint(modifier: Modifier = Modifier) {
    val pulse = if (systemMotionEnabled()) {
        rememberInfiniteTransition(label = "caption hint").animateFloat(
            initialValue = 1f,
            targetValue = .70f,
            animationSpec = infiniteRepeatable(tween(1050), RepeatMode.Reverse),
            label = "caption hint opacity",
        )
    } else null
    Text("轻点显示字幕", color = LocalGlassPalette.current.contentSecondary, fontSize = 12.sp,
        modifier = modifier.graphicsLayer { alpha = pulse?.value ?: 1f })
}

internal class CallCaptionUi(visibleState: MutableState<Boolean>) {
    var visible by visibleState
    var pickingLanguage by mutableStateOf(false)
    var language by mutableStateOf("en")
    var request by mutableStateOf<TranslationRequest?>(null)
    var translationProblem by mutableStateOf(false)
    var translating by mutableStateOf(false)
    val translations = mutableStateMapOf<Pair<String, Long>, String>()

    class TranslationRequest(val language: String, val firstLine: Long)

    fun startTranslation(lines: List<CallCaption>) {
        translationProblem = false
        request = TranslationRequest(language, lines.lastOrNull()?.id ?: 1)
        visible = true
    }

    fun stopTranslation() {
        request = null
        translationProblem = false
    }
}

@Composable
internal fun rememberCallCaptions(
    session: Long,
    lines: List<CallCaption>,
    ended: Boolean,
    companionId: suspend () -> Long?,
): CallCaptionUi {
    val container = appContainer()
    val visibility = rememberSaveable(session) { mutableStateOf(false) }
    val ui = remember(session, visibility) { CallCaptionUi(visibility) }
    val latestLines by rememberUpdatedState(lines)
    val findCompanion by rememberUpdatedState(companionId)
    val request = ui.request
    LaunchedEffect(session, request, ended) {
        if (request == null || ended) return@LaunchedEffect
        val translator = MessageTranslator(container.chatClient)
        var nextId = request.firstLine
        try {
            while (true) {
                val available = snapshotFlow { latestLines }.first { it.lastOrNull()?.id?.let { id -> id >= nextId } == true }
                val oldest = available.first().id
                ui.translations.keys.filter { it.second < oldest }.forEach { ui.translations.remove(it) }
                for (line in available) {
                    if (line.id < nextId) continue
                    nextId = line.id + 1
                    val key = request.language to line.id
                    if (key in ui.translations) continue
                    ui.translating = true
                    val model = findCompanion()?.let { container.companions.get(it) }?.modelFor(heard = true)
                    check(model != null && model.baseUrl.isNotBlank() && model.model.isNotBlank())
                    val secret = container.secrets.key(model.baseUrl)
                    check(!secret.isNullOrBlank())
                    val result = withTimeoutOrNull(20_000) {
                        translator.translate(ApiEndpoint(model.baseUrl, secret, model.model), line.text, request.language)
                    }
                    checkNotNull(result)
                    ui.translations[key] = result
                    ui.translating = false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ui.translationProblem = true
        } finally {
            ui.translating = false
        }
    }
    return ui
}

@Composable
internal fun CallTranslationButton(ui: CallCaptionUi, backdrop: Backdrop, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    GlassIconButton(
        Icons.Rounded.Translate, "字幕翻译", {
            focus.clearFocus()
            keyboard?.hide()
            ui.pickingLanguage = true
        }, backdrop,
        modifier = modifier,
        style = palette.topBar,
        tint = if (ui.request != null) palette.accentContent else palette.content,
        size = 44.dp,
    )
}

@Composable
internal fun CallTranslationDialog(
    ui: CallCaptionUi,
    lines: List<CallCaption>,
    group: Boolean,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    var selected by remember { mutableStateOf(ui.language) }
    LaunchedEffect(ui.pickingLanguage) { if (ui.pickingLanguage) selected = ui.language }
    val route = if (group) "第一位成员的通话模型" else "这位 TA 的通话模型"
    GlassFloatingPanel(
        visible = ui.pickingLanguage,
        backdrop = backdrop,
        title = "字幕翻译",
        onDismiss = { ui.pickingLanguage = false },
        modifier = modifier,
        width = 340.dp,
        maxPanelHeight = 540.dp,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("字幕翻译", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("从最近一句开始，只把字幕文字发给$route，可能产生接口用量。译文仅在本次通话显示。",
                    color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val shape = RoundedCornerShape(18.dp)
                    MessageTranslationRules.languages.forEach { (code, name) ->
                        val chosen = selected == code
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape)
                                .then(if (chosen) Modifier.background(palette.accentContent.copy(alpha = 0.13f)) else Modifier)
                                .selectable(chosen, enabled = ui.pickingLanguage, role = Role.RadioButton) { selected = code }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, modifier = Modifier.weight(1f), fontSize = 15.sp,
                                color = if (chosen) palette.accentContent else palette.content)
                            if (chosen) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = palette.accentContent)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(enabled = ui.pickingLanguage, onClick = {
                    if (ui.request != null) ui.stopTranslation()
                    ui.pickingLanguage = false
                }) { Text(if (ui.request != null) "关闭翻译" else "取消", color = palette.content) }
                FilledTonalButton(enabled = ui.pickingLanguage, onClick = {
                    ui.language = selected
                    ui.startTranslation(lines)
                    ui.pickingLanguage = false
                }, colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = palette.accentContent.copy(alpha = 0.15f), contentColor = palette.accentContent,
                )) { Text(if (ui.translationProblem) "重试" else "开启翻译") }
            }
        }
    }
}

@Composable
internal fun CallCaptionList(ui: CallCaptionUi, lines: List<CallCaption>, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    val request = ui.request
    val scroll = rememberLazyListState()
    var followLatest by remember { mutableStateOf(true) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collect { moving ->
            if (!moving) followLatest = !scroll.canScrollBackward
        }
    }
    LaunchedEffect(lines.lastOrNull()?.id) {
        if (lines.isNotEmpty() && followLatest && !scroll.isScrollInProgress) scroll.scrollToItem(0)
    }
    Column(modifier.fillMaxWidth()) {
        if (ui.translationProblem) {
            Text("翻译暂不可用，可点右上角重试", Modifier.align(Alignment.CenterHorizontally),
                color = palette.contentSecondary, fontSize = 11.sp)
        } else if (ui.translating) {
            Text("正在翻译…", Modifier.align(Alignment.CenterHorizontally),
                color = palette.contentSecondary, fontSize = 11.sp)
        }
        if (lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("说话后，字幕会出现在这里", color = palette.contentSecondary, fontSize = 13.sp)
            }
        } else {
            val topFade = if (scroll.canScrollForward) 20.dp else 0.dp
            val bottomFade = if (scroll.canScrollBackward) 20.dp else 0.dp
            val edges = if (topFade > 0.dp || bottomFade > 0.dp) {
                Modifier.fadingEdges(top = topFade, bottom = bottomFade, fade = 20.dp)
            } else Modifier
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().then(edges),
                state = scroll,
                reverseLayout = true,
                contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
            ) {
                items(lines.asReversed(), key = { it.id }) { line ->
                    val alignment = if (line.fromUser) Alignment.End else Alignment.Start
                    val textAlign = if (line.fromUser) TextAlign.End else TextAlign.Start
                    val color = if (line.fromUser) palette.accentContent else palette.content
                    Column(
                        Modifier.fillMaxWidth().padding(
                            start = if (line.fromUser) 40.dp else 0.dp,
                            end = if (line.fromUser) 0.dp else 40.dp,
                        ),
                        horizontalAlignment = alignment,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(line.text, color = color, fontSize = 14.sp, lineHeight = 20.sp, textAlign = textAlign)
                        request?.let { ui.translations[it.language to line.id] }
                            ?.takeUnless { it.trim() == line.text.trim() }
                            ?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp, textAlign = textAlign) }
                    }
                }
            }
        }
    }
}
