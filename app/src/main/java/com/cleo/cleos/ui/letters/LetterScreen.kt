package com.cleo.cleos.ui.letters

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.ai.LetterTiming
import com.cleo.cleos.ai.ReplyWhen
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.LetterEntity
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.appViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * One letter. The person's draft is written here and saved as it is typed; once sent, it
 * reads like the others and can't be changed. Opening a TA's letter marks it read.
 */
class LetterViewModel(private val c: AppContainer, startId: Long) : ViewModel() {
    var id by mutableLongStateOf(startId)
        private set
    var companionId by mutableLongStateOf(0L)
        private set
    var letter by mutableStateOf<LetterEntity?>(null)
        private set
    var text by mutableStateOf("")
    var loaded by mutableStateOf(false)
        private set
    private var done = false

    /** A new letter, or a draft; a sent letter and a TA's are only read. */
    val editable: Boolean get() = letter?.draft ?: true

    init {
        viewModelScope.launch {
            if (startId == 0L) {
                companionId = c.companions.current().id
            } else {
                c.db.letters().get(startId)?.let { l ->
                    letter = l
                    companionId = l.companionId
                    text = l.content
                    c.letters.markRead(l.id)
                }
            }
            loaded = true
            if (editable) watch()
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun watch() {
        snapshotFlow { text }.drop(1).debounce(600).collect { save() }
    }

    private suspend fun save() {
        if (!loaded || done || !editable) return
        if (text.isBlank() && id == 0L) return
        id = c.letters.saveDraft(companionId, id, text)
    }

    /** Sends it; the reply is written now and arrives when [choice] says. The choice is kept for next time. */
    fun send(choice: ReplyWhen, then: () -> Unit) {
        viewModelScope.launch {
            save()
            if (id != 0L && text.isNotBlank()) {
                done = true
                c.letters.send(id, choice)
                c.settings.update { it.copy(letterReply = choice) }
            }
            then()
        }
    }

    fun delete(then: () -> Unit) {
        done = true
        val gone = id
        c.appScope.launch { if (gone != 0L) c.letters.delete(gone) }
        then()
    }

    override fun onCleared() {
        if (done || !loaded || !editable) return
        val t = text
        val draft = id
        val to = companionId
        // A draft left empty is not kept: an empty envelope in the mailbox says nothing.
        c.appScope.launch {
            if (t.isBlank()) {
                if (draft != 0L) c.letters.delete(draft)
            } else {
                c.letters.saveDraft(to, draft, t)
            }
        }
    }
}

@Composable
fun LetterScreen(id: Long, onBack: () -> Unit) {
    val c = appContainer()
    if (id > 0) {
        val loaded by androidx.compose.runtime.produceState<Pair<Boolean, LetterEntity?>>(false to null, id) {
            value = true to c.db.letters().get(id)
        }
        if (!loaded.first) return
        val letter = loaded.second
        if (letter == null) {
            AlertDialog(onDismissRequest = onBack, title = { Text("这封信已不存在") }, confirmButton = { TextButton(onClick=onBack) { Text("返回") } })
            return
        }
        if (letter.sourceType == LetterEntity.INSTANT) {
            InstantLetterDialog(letter.companionId, letterId = id, onDismiss = onBack)
            return
        }
    }
    LegacyLetterScreen(id, onBack)
}

@Composable
private fun LegacyLetterScreen(id: Long, onBack: () -> Unit) {
    val vm = appViewModel(key = "letter-$id") { LetterViewModel(it, id) }
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val density = LocalDensity.current
    val companions by remember { c.companions.all }.collectAsStateWithLifecycle(emptyList())
    val name = companions.firstOrNull { it.id == vm.companionId }?.name?.trim()?.ifEmpty { null } ?: "TA"
    val letter = vm.letter
    val fromTa = letter?.author == LetterEntity.AUTHOR_AI
    var confirmSend by remember { mutableStateOf(false) }
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    var confirmDelete by remember { mutableStateOf(false) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val bottom = if (imeBottom > navBottom) imeBottom else navBottom
    val body = TextStyle(color = palette.content, fontSize = 17.sp, lineHeight = 29.sp, fontFamily = FontFamily.Serif)

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = when {
                    vm.editable -> "写给$name"
                    fromTa -> "${name}写来的"
                    else -> "你寄出的"
                },
                subtitle = when {
                    !vm.loaded -> null
                    vm.editable -> "草稿会自动存着"
                    else -> letter?.let { Dates.chatStamp(it.deliverAt ?: it.createdAt) }
                },
                backdrop = page,
                leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) },
                trailing = {
                    if (vm.editable) {
                        GlassIconButton(Icons.AutoMirrored.Rounded.Send, "寄出", { if (vm.text.isNotBlank()) confirmSend = true }, page)
                    } else {
                        GlassIconButton(Icons.Rounded.DeleteOutline, "删除", { confirmDelete = true }, page)
                    }
                },
            )
        },
    ) {
        Box(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = statusTop + TopBarHeight + 8.dp, bottom = bottom + 16.dp)) {
            GlassSurface(modifier = Modifier.fillMaxSize(), shape = GlassShape.Rounded(28.dp)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 22.dp),
                ) {
                    if (vm.editable) {
                        BasicTextField(
                            value = vm.text,
                            onValueChange = { vm.text = it },
                            textStyle = body,
                            cursorBrush = SolidColor(palette.accentContent),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                Box {
                                    if (vm.text.isEmpty()) {
                                        Text("写给$name……", style = body.copy(color = palette.contentSecondary.copy(alpha = 0.45f)))
                                    }
                                    inner()
                                }
                            },
                        )
                    } else if (letter != null) {
                        Text(letter.content, style = body)
                        Spacer(Modifier.height(28.dp))
                        // The signature and date the letter itself leaves out.
                        val footer = listOfNotNull(if (fromTa) name else null, Dates.full(Dates.dateOf(letter.deliverAt ?: letter.createdAt)))
                        for (line in footer) {
                            Text(line, style = body.copy(color = palette.contentSecondary, fontSize = 15.sp), textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }

    if (confirmSend) {
        // What was picked last time, each time the dialog opens.
        var choice by remember { mutableStateOf(settings.letterReply) }
        AlertDialog(
            onDismissRequest = { confirmSend = false },
            title = { Text("寄给$name？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("寄出后就改不了了。回信什么时候到？")
                    // Two by two, all the same width: four in a row don't fit, and three and one look dropped.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (pair in ReplyWhen.entries.chunked(2)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (w in pair) Pill(w.label, selected = choice == w, modifier = Modifier.weight(1f)) { choice = w }
                            }
                        }
                    }
                    Text(LetterTiming.describe(choice, System.currentTimeMillis(), ZoneId.systemDefault()), fontSize = 13.sp, lineHeight = 19.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSend = false
                    vm.send(choice, onBack)
                }) { Text("寄出") }
            },
            dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("再看看") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这封信？") },
            text = { Text("删了找不回来。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(onBack)
                }) { Text("删除", color = palette.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/** A plain pill, not glass: it sits in a dialog (see the settings screen's chips). */
@Composable
private fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        modifier
            .background(if (selected) palette.accent else palette.content.copy(alpha = 0.07f), CircleShape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Color.White else palette.content, fontSize = 14.sp)
    }
}
