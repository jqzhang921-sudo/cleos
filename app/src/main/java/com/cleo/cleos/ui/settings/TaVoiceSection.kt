package com.cleo.cleos.ui.settings

import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cleo.cleos.ai.*
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun TaVoiceSection(ta: CompanionEntity, settings: AppSettings) {
    val palette = LocalGlassPalette.current
    val service = Speech.service(settings)
    val targetScope = TaVoices.scope(settings)
    Section("${ta.name.ifBlank { "这个 TA" }}的音色") {
        Text("平台和 Key 共用，音色可以各自选。保存后用于这位 TA 的私聊、群聊、朗读和下一次电话；正在进行的电话保持本次声音。",
            color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        if (service == null || targetScope == null) {
            Text("先在下面配好共用的声音平台，再为这位 TA 挑音色。", color = palette.contentSecondary, fontSize = 13.sp)
        } else key(ta.id, ta.createdAt, targetScope) { TaVoiceEditor(ta, settings, service, targetScope) }
    }
}

@Composable
private fun TaVoiceEditor(ta: CompanionEntity, settings: AppSettings, service: VoiceService, targetScope: String) {
    val palette = LocalGlassPalette.current
    val c = appContainer()
    val context = LocalContext.current
    val work = rememberCoroutineScope()
    val original = TaVoices.choice(ta.voiceChoices, settings)
    var expected by rememberSaveable { mutableStateOf(ta.voiceChoices) }
    var shared by rememberSaveable { mutableStateOf(original == null) }
    var voice by rememberSaveable { mutableStateOf(original ?: Speech.voice(settings, service)) }
    var saving by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var voices by remember { mutableStateOf<List<VoiceOption>?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var preview by remember { mutableStateOf<Job?>(null) }
    val busy = saving || listing || preparing
    val chosen = if (shared) null else voice.trim()
    val dirty = chosen != original

    fun stopPreview() { preview?.cancel(); preview = null; player?.release(); player = null; preparing = false }
    fun choose(id: String) { stopPreview(); shared = false; voice = id.take(TaVoices.MAX_VOICE); result = null }
    DisposableEffect(Unit) { onDispose { preview?.cancel(); player?.release() } }

    Text("当前平台：${service.label}", color = palette.contentSecondary, fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("跟随共用音色", selected = shared) { if (!busy) { stopPreview(); shared = true; result = null } }
        Chip("单独选音色", selected = !shared) { if (!busy) { stopPreview(); shared = false; result = null } }
    }
    if (shared) {
        Text("这位 TA 跟随共用音色；以后修改默认音色时也会跟着变。", color = palette.contentSecondary, fontSize = 12.sp)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Speech.builtIn(service).forEach { option -> Chip(option.label, selected = voice == option.id) { if (!busy) choose(option.id) } }
        }
        OutlinedTextField(voice, { if (!busy) choose(it) }, label = { Text("这位 TA 的音色 ID") },
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        if (service in setOf(VoiceService.MiniMax, VoiceService.Mossland, VoiceService.ElevenLabs)) {
            Chip(if (listing) "正在列出…" else "从音色列表里选", selected = false) { if (!busy) {
                stopPreview(); listing = true; result = null
                work.launch {
                    try { voices = c.speaker.voices(settings, service).distinctBy { it.id } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { result = "没有读到音色列表，请检查共用平台的 Key 或稍后重试。" }
                    finally { listing = false }
                }
            } }
        }
    }
    if (dirty) Text("尚未保存 · 试听不会改变配置", color = palette.contentSecondary, fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(if (preparing) "正在合成…" else "试听这个音色", selected = false) { if (!busy) {
            stopPreview(); result = null; preparing = true
            preview = work.launch {
                try {
                    val trial = TaVoices.withVoice(settings, chosen)
                    check(Speech.ready(trial)) { "先选好音色，再试听" }
                    val file = c.speaker.reading(trial, Speech.SAMPLE, File(context.cacheDir, "voice-preview"))
                    ensureActive()
                    player = MediaPlayer().apply {
                        setDataSource(file.path)
                        setOnCompletionListener { finished -> if (player === finished) player = null; finished.release() }
                        setOnErrorListener { failed, _, _ -> if (player === failed) player = null; failed.release(); result = "这段试听没有播放成功"; true }
                        prepare(); start()
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { result = "试听未成功，音色没有保存；请检查共用平台和音色 ID。" }
                finally { preparing = false }
            }
        } }
        Chip(if (saving) "正在保存…" else "保存音色", selected = dirty) { if (!busy && dirty) {
            stopPreview(); saving = true; result = null
            val id = ta.id; val before = expected; val value = chosen
            work.launch {
                try { expected = c.companions.setVoiceChoice(id, ta.createdAt, before, targetScope, value); result = "音色已保存" }
                catch (e: CancellationException) { throw e }
                catch (e: IllegalStateException) { result = e.message }
                catch (e: IllegalArgumentException) { result = e.message }
                catch (_: Exception) { result = "音色没有保存，请稍后重试" }
                finally { saving = false }
            }
        } }
        if (dirty) Chip("撤销修改", selected = false) { if (!busy) {
            stopPreview(); expected = ta.voiceChoices; shared = original == null
            voice = original ?: Speech.voice(settings, service); result = null
        } }
    }
    result?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp) }
    voices?.let { choices -> TaVoiceList(choices, { choose(it.id); voices = null }, { voices = null }) }
}

@Composable
private fun TaVoiceList(voices: List<VoiceOption>, onChoose: (VoiceOption) -> Unit, onDismiss: () -> Unit) {
    val palette = LocalGlassPalette.current
    var query by remember { mutableStateOf("") }
    val shown = remember(query, voices) { voices.filter { it.label.contains(query, true) || it.id.contains(query, true) } }
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
            Column(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选专属音色", color = palette.content, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                OutlinedTextField(query, { query = it }, label = { Text("搜索名字或 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(shown, key = { it.id }) { option ->
                        Column(Modifier.fillMaxWidth().clickable { onChoose(option) }.padding(vertical = 10.dp)) {
                            Text(option.label, color = palette.content, fontSize = 14.sp)
                            if (option.label != option.id) Text(option.id, color = palette.contentSecondary, fontSize = 11.sp)
                        }
                    }
                    if (shown.isEmpty()) item { Text("没有匹配的音色", color = palette.contentSecondary) }
                }
                TextButton(onDismiss) { Text("关闭", color = palette.content) }
            }
        }
    }
}
