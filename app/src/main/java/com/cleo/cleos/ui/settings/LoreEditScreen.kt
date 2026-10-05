package com.cleo.cleos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.data.LoreKeys
import com.cleo.cleos.data.db.LoreEntity
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.appViewModel
import kotlinx.coroutines.launch

/**
 * One 设定 entry, as the person writes it: what it is called, the words that lead the TA to it,
 * and the text itself. Saved on leaving; a new one is kept only once it has a title and a body.
 */
class LoreEditViewModel(private val c: AppContainer, startId: Long) : ViewModel() {
    var id by mutableLongStateOf(startId)
        private set
    var companionId by mutableLongStateOf(0L)
        private set
    var book by mutableStateOf("")
    var title by mutableStateOf("")
    var keys by mutableStateOf("")
    var content by mutableStateOf("")
    var enabled by mutableStateOf(true)
    var loaded by mutableStateOf(false)
        private set
    private var original: LoreEntity? = null
    private var deleted = false

    init {
        viewModelScope.launch {
            val e = if (startId == 0L) null else c.db.lore().get(startId)
            if (e == null) {
                // A new entry belongs to the TA being talked to, as a new memory does.
                companionId = c.companions.current().id
            } else {
                original = e
                companionId = e.companionId
                book = e.book
                title = e.title
                keys = LoreKeys.line(LoreKeys.decode(e.keys))
                content = e.content
                enabled = e.enabled
            }
            loaded = true
        }
    }

    fun delete(then: () -> Unit) {
        deleted = true
        val gone = id
        c.appScope.launch { if (gone != 0L) c.db.lore().delete(gone) }
        then()
    }

    override fun onCleared() {
        if (!loaded || deleted) return
        val t = title.trim()
        val body = content.trim()
        if (t.isEmpty() || body.isEmpty()) return
        val o = original
        val to = companionId
        val b = book.trim().take(BOOK_MAX)
        val k = LoreKeys.encode(LoreKeys.parse(keys))
        val on = enabled
        c.appScope.launch {
            val now = System.currentTimeMillis()
            if (o == null) {
                c.db.lore().insert(
                    LoreEntity(
                        companionId = to,
                        book = b,
                        title = t,
                        keys = k,
                        content = body.take(CONTENT_MAX),
                        enabled = on,
                        source = LoreEntity.SOURCE_ME,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } else {
                val next = o.copy(book = b, title = t, keys = k, content = body.take(CONTENT_MAX), enabled = on)
                if (next != o) c.db.lore().update(next.copy(updatedAt = now))
            }
        }
    }

    companion object {
        /** A book's name is a heading. */
        private const val BOOK_MAX = 60

        /** Past this, one entry is a document rather than something a reply can quote. */
        private const val CONTENT_MAX = 20_000
    }
}

@Composable
fun LoreEditScreen(id: Long, onBack: () -> Unit) {
    val vm = appViewModel(key = "lore-$id") { LoreEditViewModel(it, id) }
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val density = LocalDensity.current
    val companions by remember { c.companions.all }.collectAsStateWithLifecycle(emptyList())
    val ta = companions.firstOrNull { it.id == vm.companionId }?.name?.trim()?.ifEmpty { null } ?: "TA"
    var confirmDelete by remember { mutableStateOf(false) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val bottom = if (imeBottom > navBottom) imeBottom else navBottom

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = if (id == 0L) "自己加一条" else vm.title.ifBlank { "设定" },
                subtitle = if (id == 0L) "要有标题和内容才会存" else "离开时自动存",
                backdrop = page,
                leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) },
                trailing = {
                    if (id != 0L) GlassIconButton(Icons.Rounded.DeleteOutline, "删除", { confirmDelete = true }, page)
                },
            )
        },
    ) {
        if (!vm.loaded) return@GlassPage
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 14.dp, end = 14.dp, top = statusTop + TopBarHeight + 10.dp, bottom = bottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = GlassShape.Rounded(24.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field("哪本书", vm.book, onChange = { vm.book = it.take(60) })
                    Field("标题", vm.title, onChange = { vm.title = it.take(60) })
                    Field("关键词（用、隔开）", vm.keys, onChange = { vm.keys = it })
                    Text(
                        "聊天里说到这些词，$ta 才可能查到这条；没写关键词，就只有标题被说到时才查得到。" +
                            "关键词最多 ${LoreKeys.KEY_MAX} 个。",
                        color = palette.contentSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = GlassShape.Rounded(24.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.content,
                        onValueChange = { vm.content = it.take(20_000) },
                        label = { Text("内容") },
                        placeholder = { Text("照原文写：$ta 查到这条时，看到的就是这一段") },
                        minLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${vm.content.length} 字",
                        color = palette.contentSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = GlassShape.Rounded(24.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = vm.enabled, interactionSource = null, indication = null, role = Role.Switch, onValueChange = { vm.enabled = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("查得到", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("关掉只是 $ta 查不到这条，内容还在。", color = palette.contentSecondary, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = vm.enabled, onCheckedChange = null, colors = glassSwitchColors())
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删掉这条设定？") },
            text = { Text("$ta 就查不到它了。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(onBack)
                }) { Text("删掉", color = palette.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}
