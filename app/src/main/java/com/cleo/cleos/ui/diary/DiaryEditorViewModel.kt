package com.cleo.cleos.ui.diary

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.data.DiaryBlock
import com.cleo.cleos.data.DiaryBlocks
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.ui.common.Dates
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** One editable block. Text blocks own their field state so the cursor survives recomposition. */
sealed interface EditorBlock {
    val key: Long

    class Text(override val key: Long, initial: TextFieldValue) : EditorBlock {
        var value by mutableStateOf(initial)
    }

    class Image(override val key: Long, val image: DiaryBlock.Image) : EditorBlock
}

/**
 * Editing one entry. Saves quietly while typing (after a short pause) and once more
 * when the screen goes away, from the app-wide scope, so leaving mid-sentence loses
 * nothing. A new entry is not written until it has some content; an entry emptied out
 * completely is removed when the editor closes.
 */
class DiaryEditorViewModel(private val c: AppContainer, initialId: Long, startSecret: Boolean = false) : ViewModel() {
    var entryId = initialId
        private set
    var day by mutableStateOf(Dates.today())
    var title by mutableStateOf(TextFieldValue(""))

    /** Locked: the model never reads it, and can only ask to be shown it once. */
    var secret by mutableStateOf(startSecret)
    var author by mutableStateOf(DiaryEntryEntity.AUTHOR_ME)
        private set

    /** For a TA's entry, which TA. */
    var companionId by mutableStateOf<Long?>(null)
        private set

    /** The model's own entries are read, not edited: they are its words. */
    val readOnly: Boolean get() = author == DiaryEntryEntity.AUTHOR_AI
    val blocks = mutableStateListOf<EditorBlock>()
    var lockedForUser by mutableStateOf(false)
        private set
    var publicHint by mutableStateOf("")
        private set
    var sharedExcerpt by mutableStateOf("")
        private set
    var requesting by mutableStateOf(false)
        private set
    var requestError by mutableStateOf<String?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    var importing by mutableStateOf(false)
        private set
    var focusedIndex by mutableIntStateOf(-1)

    /** Key of the text block that should take focus next (after an image went in above it). */
    var focusRequest by mutableStateOf<Long?>(null)

    private var createdAt = System.currentTimeMillis()
    private var nextKey = 1L
    private var deleted = false
    private val removedImages = mutableSetOf<String>()
    private val saveLock = Mutex()

    init {
        viewModelScope.launch {
            if (initialId != 0L) {
                c.db.diary().get(initialId)?.let { e ->
                    day = LocalDate.ofEpochDay(e.day)
                    lockedForUser = e.lockedForUser
                    publicHint = e.publicHint
                    sharedExcerpt = e.sharedExcerpt
                    title = TextFieldValue(if (e.lockedForUser) "" else e.title)
                    createdAt = e.createdAt
                    secret = e.secret
                    author = e.author
                    companionId = e.companionId
                    (if (e.lockedForUser) emptyList() else DiaryBlocks.decode(e.blocks)).forEach { b ->
                        when (b) {
                            is DiaryBlock.Text -> blocks += EditorBlock.Text(nextKey++, TextFieldValue(b.text))
                            is DiaryBlock.Image -> {
                                // Every picture gets a text block above it, or there would
                                // be nowhere to put the cursor between two pictures, or
                                // above one that opens the entry.
                                if (blocks.lastOrNull() !is EditorBlock.Text) {
                                    blocks += EditorBlock.Text(nextKey++, TextFieldValue(""))
                                }
                                blocks += EditorBlock.Image(nextKey++, b)
                            }
                        }
                    }
                }
            }
            if (blocks.lastOrNull() !is EditorBlock.Text) blocks += EditorBlock.Text(nextKey++, TextFieldValue(""))
            loaded = true
            autosave()
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun autosave() {
        snapshotFlow { signature() }
            .drop(1)
            .debounce(800)
            .collect { save(final = false) }
    }

    private fun signature(): List<Any> = buildList {
        add(day)
        add(title.text)
        add(secret)
        blocks.forEach {
            when (it) {
                is EditorBlock.Text -> add(it.value.text)
                is EditorBlock.Image -> add(it.image.file)
            }
        }
    }

    private fun content(): List<DiaryBlock> {
        val out = mutableListOf<DiaryBlock>()
        for (b in blocks) {
            when (b) {
                is EditorBlock.Text -> out += DiaryBlock.Text(b.value.text)
                is EditorBlock.Image -> out += b.image
            }
        }
        // Empty text blocks only exist as places to put the cursor; they are not content.
        return out.filterIndexed { i, b -> b !is DiaryBlock.Text || b.text.isNotEmpty() || i == out.lastIndex }
    }

    private fun isEmpty(blocks: List<DiaryBlock>) =
        title.text.isBlank() && blocks.all { it is DiaryBlock.Text && it.text.isBlank() }

    // NonCancellable: if leaving the screen cancelled an insert after the row was written
    // but before its id came back, the closing save would insert the entry a second time.
    private suspend fun save(final: Boolean) = withContext(NonCancellable) {
        saveLock.withLock {
            if (deleted || !loaded || readOnly) return@withLock
            val content = content()
            if (isEmpty(content)) {
                if (final && entryId != 0L) {
                    c.db.diary().delete(entryId)
                    entryId = 0L
                }
                return@withLock
            }
            val entity = DiaryEntryEntity(
                id = entryId,
                day = day.toEpochDay(),
                title = title.text.trim(),
                blocks = DiaryBlocks.encode(content),
                createdAt = createdAt,
                updatedAt = System.currentTimeMillis(),
                author = author,
                secret = secret,
                companionId = companionId,
            )
            if (entryId == 0L) entryId = c.db.diary().insert(entity) else c.db.diary().update(entity)
        }
    }

    fun askToSee() {
        if (requesting || !lockedForUser) return
        requesting = true
        requestError = null
        viewModelScope.launch {
            try {
                val entry = c.db.diary().get(entryId) ?: error("这篇日记已经不在了")
                val ta = entry.companionId?.let { c.companions.get(it) } ?: error("这位 TA 已经不在了")
                val conversation = c.chat.resolveConversation(null, ta.id)
                c.companions.select(ta.id)
                c.settings.setCurrentConversation(conversation)
                check(c.chat.send(conversation, "我有点好奇你在 ${Dates.full(day)} 写的小秘密（日记 #${entry.id}），可以让我看看吗？")) { "暂时发送不了，请稍后再试" }
                c.opening.value = com.cleo.cleos.Opening.Chat(conversation)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                requestError = e.message ?: "请求没发出去，请再试一次"
            } finally { requesting = false }
        }
    }

    fun insertImages(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            importing = true
            val stored = uris.mapNotNull { uri: Uri ->
                runCatching { c.images.import(uri) }
                    .onFailure { Log.w("DiaryEditor", "could not import $uri", it) }
                    .getOrNull()
            }
            importing = false
            if (stored.isEmpty()) return@launch

            val at = focusedIndex.takeIf { it in blocks.indices && blocks[it] is EditorBlock.Text }
                ?: blocks.indexOfLast { it is EditorBlock.Text }
            val block = blocks[at] as EditorBlock.Text
            val v = block.value
            val cursor = v.selection.start.coerceIn(0, v.text.length)
            val before = v.text.substring(0, cursor).trimEnd('\n')
            val after = v.text.substring(cursor).trimStart('\n')

            block.value = TextFieldValue(before, TextRange(before.length))
            val inserted = mutableListOf<EditorBlock>()
            stored.forEach { inserted += EditorBlock.Image(nextKey++, DiaryBlock.Image(it.file, it.width, it.height)) }
            val tail = EditorBlock.Text(nextKey++, TextFieldValue(after, TextRange(0)))
            inserted += tail
            blocks.addAll(at + 1, inserted)
            focusRequest = tail.key
        }
    }

    fun removeImage(key: Long) {
        val i = blocks.indexOfFirst { it.key == key }
        if (i < 0) return
        val removed = blocks.removeAt(i) as? EditorBlock.Image ?: return
        removedImages += removed.image.file
        val prev = blocks.getOrNull(i - 1) as? EditorBlock.Text
        val next = blocks.getOrNull(i) as? EditorBlock.Text
        if (prev != null && next != null) {
            val joined = listOf(prev.value.text, next.value.text).filter { it.isNotEmpty() }.joinToString("\n")
            prev.value = TextFieldValue(joined, TextRange(prev.value.text.length))
            blocks.removeAt(i)
        }
    }

    fun delete(onDone: () -> Unit) {
        deleted = true
        val id = entryId
        val files = blocks.filterIsInstance<EditorBlock.Image>().map { it.image.file } + removedImages
        c.appScope.launch {
            if (id != 0L) c.db.diary().delete(id)
            c.images.delete(files)
        }
        onDone()
    }

    override fun onCleared() {
        val stillUsed = blocks.filterIsInstance<EditorBlock.Image>().map { it.image.file }.toSet()
        val orphans = removedImages - stillUsed
        c.appScope.launch {
            save(final = true)
            // Images taken out during this session belonged only to this entry.
            if (!deleted) c.images.delete(orphans)
        }
    }
}
