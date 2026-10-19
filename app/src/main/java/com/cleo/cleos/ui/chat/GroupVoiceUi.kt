package com.cleo.cleos.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cleo.cleos.ai.GroupVoiceDraft
import com.cleo.cleos.ai.GroupVoiceRules
import com.cleo.cleos.ai.Voice
import com.cleo.cleos.data.MessageAudio
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.delay

internal data class GroupRecording(val recording: Boolean, val ms: Long, val level: Float, val cancelling: Boolean,
    val start: () -> Boolean, val move: (Boolean) -> Unit, val end: (Boolean) -> Unit)

@Composable
internal fun rememberGroupRecording(groupId: Long, epoch: Long, enabled: Boolean, configured: Boolean,
    onStart: () -> Unit, onClip: (MessageAudio) -> Unit, onHint: (String) -> Unit): GroupRecording {
    val c = appContainer()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val recorder = remember(groupId, epoch) { VoiceRecorder(c.images.dir, GroupVoiceRules.prefix(groupId)) }
    var recording by remember(recorder) { mutableStateOf(false) }
    var cancelling by remember(recorder) { mutableStateOf(false) }
    var ms by remember(recorder) { mutableLongStateOf(0L) }
    var level by remember(recorder) { mutableFloatStateOf(0f) }
    val currentClip by rememberUpdatedState(onClip)
    val hint by rememberUpdatedState(onHint)
    val currentStart by rememberUpdatedState(onStart)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hint(if (it) "可以了，再按住发群语音" else "需要话筒权限才能录制群语音")
    }
    fun cancel() { recorder.cancel(); recording = false; cancelling = false }
    fun finish(drop: Boolean) {
        if (!recording) return
        recording = false; cancelling = false
        if (drop) recorder.cancel() else recorder.stop()?.let(currentClip) ?: hint("说话时间太短了，请重新录制")
    }
    DisposableEffect(owner, recorder) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancel() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); cancel() }
    }
    LaunchedEffect(enabled) { if (!enabled && recording) cancel() }
    LaunchedEffect(recording) {
        if (recording) {
            val started = System.nanoTime()
            while (recording) {
                ms = (System.nanoTime() - started) / 1_000_000
                level = recorder.level
                if (ms >= Voice.MAX_MS) { finish(false); break }
                delay(100)
            }
        }
    }
    return GroupRecording(recording, ms, level, cancelling, {
        when {
            !enabled || recording -> false
            !configured -> { hint("先在设置的「发语音」里配置转文字服务"); false }
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                permission.launch(Manifest.permission.RECORD_AUDIO); false
            }
            else -> {
                currentStart()
                if (!recorder.start()) { hint("话筒打不开，可能正被其他应用使用"); false }
                else { ms = 0; cancelling = false; recording = true; true }
            }
        }
    }, { cancelling = it }, ::finish)
}

internal data class GroupPlayback(val file: String?, val play: (String) -> Unit, val stop: () -> Unit)

@Composable
internal fun rememberGroupPlayback(groupId: Long, epoch: Long, onHint: (String) -> Unit): GroupPlayback {
    val c = appContainer()
    var playing by remember(groupId, epoch) { mutableStateOf<String?>(null) }
    val players = remember(groupId, epoch) { arrayOfNulls<MediaPlayer>(1) }
    val hint by rememberUpdatedState(onHint)
    val owner = LocalLifecycleOwner.current
    fun stop() { players[0]?.let { runCatching { it.stop() }; it.release() }; players[0] = null; playing = null }
    DisposableEffect(owner, players) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
    return GroupPlayback(playing, { file ->
        val same = playing == file
        stop()
        if (!same) {
            val p = MediaPlayer()
            players[0] = p; playing = file
            runCatching {
                p.setDataSource(c.images.file(file).path)
                p.setOnPreparedListener { if (players[0] === it) runCatching { it.start() }.onFailure { stop(); hint("这段语音暂时放不了") } }
                p.setOnCompletionListener { if (players[0] === it) stop() }
                p.setOnErrorListener { media, _, _ -> if (players[0] === media) { stop(); hint("这段语音暂时放不了") }; true }
                p.prepareAsync()
            }.onFailure { stop(); hint("这段语音读不到") }
        }
    }, ::stop)
}

@Composable
internal fun GroupRecordingCard(state: GroupRecording) {
    val palette = LocalGlassPalette.current
    GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(8.dp + 10.dp * state.level).background(if (state.cancelling) palette.error else palette.accent, CircleShape))
            Text(Voice.duration(state.ms), color = palette.content, fontSize = 14.sp)
            Text(if (state.cancelling) "松开取消" else "松开后转文字，上滑取消", color = palette.contentSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun GroupVoiceDraftCard(voice: GroupVoiceDraft, text: String, transcribing: Boolean, enabled: Boolean,
    playback: GroupPlayback, onText: (String) -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    val palette = LocalGlassPalette.current
    GlassSurface(Modifier.fillMaxWidth(), style = palette.card, shape = GlassShape.Rounded(20.dp), contentPadding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("群语音 · ${Voice.duration(voice.ms)}", color = palette.content, fontSize = 14.sp, modifier = Modifier.weight(1f))
                GlassIconButton(if (playback.file == voice.file) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    if (playback.file == voice.file) "停止群语音草稿" else "播放群语音草稿",
                    { playback.play(voice.file) }, LocalWallpaperBackdrop.current)
                GlassIconButton(Icons.Rounded.Close, "移除群语音草稿", onRemove, LocalWallpaperBackdrop.current, enabled = enabled)
            }
            if (transcribing) Text("正在转文字，可以播放或移除这段录音…", color = palette.contentSecondary, fontSize = 12.sp)
            else {
                OutlinedTextField(text, onText, enabled = enabled, label = { Text("转写文字，可修改后发送") }, maxLines = 3,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "群语音转写文字" })
                voice.error?.let { Text(it, color = palette.error, fontSize = 12.sp) }
                if (text.isBlank()) TextButton(onRetry, enabled = enabled) { Text("重新转文字", color = palette.accentContent) }
            }
        }
    }
}
