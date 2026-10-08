package com.cleo.cleos.ui.chat

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.ai.ChatRepository
import com.cleo.cleos.ai.Recap
import com.cleo.cleos.data.MessageAudio
import com.cleo.cleos.ai.Prompt
import com.cleo.cleos.ai.Speech
import com.cleo.cleos.ai.StreamingReply
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.MessageQuote
import com.cleo.cleos.data.MessageQuotes
import com.cleo.cleos.data.Pats
import com.cleo.cleos.data.PickedSticker
import com.cleo.cleos.data.StickerException
import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.data.db.StickerEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversationId: Long? = null,
    val messages: List<MessageEntity> = emptyList(),
    /** What to draw live below the stored messages; text already stored is blanked out. */
    val streaming: StreamingReply? = null,
    /** A reply is under way here (the stop button), including while its tools run. */
    val replying: Boolean = false,
    val hasApiKey: Boolean = true,
    /** The TA this conversation is with. */
    val companionId: Long = 0,
    val aiName: String = "",
    val userName: String = "",
    val aiAvatar: String? = null,
    val aiAvatarEmoji: String? = null,
    val userAvatar: String? = null,
    val chatAvatars: Boolean = false,
    val avatarEachMessage: Boolean = false,
    /** How big the chat's text is (ChatType). */
    val chatTextSize: Int = ChatType.DEFAULT,
    /** 拍一拍 (Pats): the verb, what follows the TA's name, and whether the phone buzzes. */
    val patVerb: String = Pats.VERB,
    val patSuffix: String = "",
    val patBuzz: Boolean = true,
    val model: String = "",
    /** What the TA keeps of the messages no longer sent verbatim. */
    val recap: String? = null,
    /** The last message folded into [recap]: its time and id. */
    val recapUntil: Pair<Long, Long>? = null,
    /** Voice messages being turned into text. */
    val transcribing: Set<Long> = emptySet(),
    /** A transcription service is set up, so the microphone can be used. */
    val voiceReady: Boolean = false,
    /** The TA has a voice (TA 的声音), so it can talk on the phone. */
    val speechReady: Boolean = false,
    val loaded: Boolean = false,
)

/** A sticker in the naming dialog: a picture just picked, or one already in the collection. */
sealed interface StickerDraft {
    data class New(val picked: PickedSticker) : StickerDraft

    data class Edit(val sticker: StickerEntity) : StickerDraft
}

class ChatViewModel(private val c: AppContainer) : ViewModel() {
    private val conversationId = MutableStateFlow<Long?>(null)

    init {
        // The conversation shown is always the current TA's: switching TA opens their latest.
        viewModelScope.launch {
            combine(c.settings.currentConversation, c.companions.current.map { it.id }.distinctUntilChanged()) { remembered, ta ->
                remembered to ta
            }.collect { (remembered, ta) ->
                val id = c.chat.resolveConversation(remembered, ta)
                if (id != remembered) c.settings.setCurrentConversation(id)
                // A quote belongs to the conversation it was picked in, and typing to the one it was typed in.
                conversationId.value?.takeIf { it != id }?.let {
                    quoting = null
                    c.chat.typing(it, false)
                }
                conversationId.value = id
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ChatUiState> = conversationId.filterNotNull().flatMapLatest { id ->
        // The TA of the conversation on screen. Right after a switch that is still the
        // previous TA, until their latest conversation has been found.
        val here = combine(c.db.conversations().observe(id), c.companions.all) { conversation, list ->
            (list.firstOrNull { it.id == conversation?.companionId } ?: list.firstOrNull())?.let { conversation to it }
        }.filterNotNull()
        combine(
            c.db.messages().observe(id),
            combine(c.chat.streaming, c.chat.transcribing) { streaming, transcribing -> streaming to transcribing },
            here,
            here.map { it.second.apiBaseUrl }.distinctUntilChanged().flatMapLatest { c.secrets.hasKey(it) },
            c.settings.settings,
        ) { messages, (streaming, transcribing), (conversation, ta), hasKey, s ->
            val live = streaming[id]
            // Once the stored copy of the live text is in the list, the live one steps
            // aside: all of it when the reply is over, only the text while tools still run.
            val stored = live?.savedId != null && messages.any { it.id == live.savedId }
            ChatUiState(
                conversationId = id,
                messages = messages,
                streaming = when {
                    live == null -> null
                    stored && live.finished -> null
                    stored -> live.copy(text = "")
                    else -> live
                },
                replying = live != null && !live.finished,
                hasApiKey = hasKey,
                companionId = ta.id,
                aiName = ta.name,
                userName = s.userName,
                aiAvatar = ta.avatar,
                aiAvatarEmoji = ta.avatarEmoji,
                userAvatar = s.userAvatar,
                chatAvatars = s.chatAvatars,
                avatarEachMessage = s.avatarEachMessage,
                chatTextSize = s.chatTextSize,
                patVerb = s.patVerb,
                patSuffix = s.patSuffix,
                patBuzz = s.patBuzz,
                model = ta.apiModel,
                recap = conversation?.recap,
                recapUntil = conversation?.let { cv -> cv.recapUntilAt?.let { at -> at to (cv.recapUntilId ?: Long.MAX_VALUE) } },
                transcribing = transcribing,
                voiceReady = s.voiceBaseUrl.isNotBlank() && s.voiceModel.isNotBlank(),
                speechReady = Speech.ready(s),
                loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    /** Pictures picked for the next message, already copied into the app's storage. */
    val attachments = mutableStateListOf<MessageImage>()
    var attaching by mutableStateOf(false)
        private set

    fun attach(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            attaching = true
            for (uri in uris.take(Prompt.MAX_IMAGES - attachments.size)) {
                runCatching { c.images.import(uri, maxEdge = 2048, prefix = "chat-") }
                    .onSuccess { attachments += MessageImage(it.file, it.width, it.height) }
            }
            attaching = false
        }
    }

    fun detach(image: MessageImage) {
        attachments.remove(image)
        c.appScope.launch { c.images.delete(listOf(image.file)) }
    }

    /** The message the next one answers, picked with 引用; shown above the input until it goes or is dropped. */
    var quoting by mutableStateOf<MessageQuote?>(null)
        private set

    fun quote(message: MessageEntity) {
        quoting = MessageQuotes.of(message)
    }

    fun unquote() {
        quoting = null
    }

    /** Whether something is being written in the input: the TA waits for it, a while. */
    fun typing(now: Boolean, processingMedia: Boolean = false) {
        conversationId.value?.let { c.chat.typing(it, now, processingMedia) }
    }

    /** False when nothing went out: the text and pictures stay where they are. */
    fun send(text: String, diaryRequestId: Long? = null, feedShare: com.cleo.cleos.data.FeedShare? = null): Boolean {
        val id = conversationId.value ?: return false
        if (!c.chat.send(id, text, attachments.toList(), quoting, diaryRequestId, feedShare)) return false
        attachments.clear()
        quoting = null
        return true
    }

    /**
     * A sticker from the drawer, at once, on its own, the way chat apps send them: with the quote
     * waiting if there is one, while typed words and picked pictures stay for the next message.
     */
    fun sendSticker(sticker: StickerEntity): Boolean {
        val id = conversationId.value ?: return false
        if (!c.chat.sendSticker(id, sticker.name, quoting)) return false
        quoting = null
        return true
    }

    /** 拍一拍: pats the TA ([ai]) or the person themself. A line in the chat, and no answer. */
    fun pat(ai: Boolean) {
        val s = state.value
        val id = s.conversationId ?: return
        c.chat.pat(id, if (ai) Pats.AI else Pats.ME, s.patVerb, s.patSuffix)
    }

    fun savePat(verb: String, suffix: String, buzz: Boolean) {
        viewModelScope.launch {
            c.settings.update { it.copy(patVerb = Pats.cleanVerb(verb), patSuffix = Pats.cleanSuffix(suffix), patBuzz = buzz) }
        }
    }

    /** Puts [emoji] on one of the TA's messages, or takes it off again. */
    fun react(messageId: Long, emoji: String) = c.chat.react(messageId, emoji)

    /** A sticker being named (one just picked) or renamed; null when none is. */
    var stickerDraft by mutableStateOf<StickerDraft?>(null)
        private set

    /** What is wrong with the name tried last, shown in the dialog. */
    var stickerProblem by mutableStateOf<String?>(null)
        private set

    /** A picture from the gallery to become a sticker: copied in, then named ([saveSticker]). */
    fun pickSticker(uri: Uri, onFail: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val picked = c.stickers.import(uri)
                stickerProblem = null
                stickerDraft = StickerDraft.New(picked)
            } catch (e: StickerException) {
                onFail(e.message ?: "这张图用不了")
            }
        }
    }

    fun renameSticker(sticker: StickerEntity) {
        stickerProblem = null
        stickerDraft = StickerDraft.Edit(sticker)
    }

    fun saveSticker(name: String, description: String) {
        val draft = stickerDraft ?: return
        viewModelScope.launch {
            val problem = when (draft) {
                is StickerDraft.New -> c.stickers.add(draft.picked, name, description)
                is StickerDraft.Edit -> c.stickers.edit(draft.sticker.id, name, description)
            }
            stickerProblem = problem
            if (problem == null && stickerDraft == draft) stickerDraft = null
        }
    }

    /** The dialog closed without saving: a picture picked for it goes again. */
    fun dropStickerDraft() {
        (stickerDraft as? StickerDraft.New)?.let { c.stickers.discard(it.picked) }
        stickerDraft = null
        stickerProblem = null
    }

    fun deleteSticker(sticker: StickerEntity) {
        viewModelScope.launch { c.stickers.delete(sticker.id) }
    }

    override fun onCleared() {
        conversationId.value?.let { c.chat.typing(it, false) }
        // Picked but never sent: nothing will ever point at these files.
        val unsent = attachments.map { it.file }
        if (unsent.isNotEmpty()) c.appScope.launch { c.images.delete(unsent) }
        (stickerDraft as? StickerDraft.New)?.let { c.stickers.discard(it.picked) }
    }

    fun stop() {
        conversationId.value?.let { c.chat.stop(it) }
    }

    fun retry(messageId: Long) {
        conversationId.value?.let { c.chat.retry(it, messageId) }
    }

    fun editMessage(message: MessageEntity, text: String, done: (String?) -> Unit) {
        if (conversationId.value != message.conversationId) { done("已切换聊天，请重新打开编辑"); return }
        c.chat.editMessage(message, text) { branch, problem -> viewModelScope.launch {
            if (branch != null && conversationId.value == message.conversationId) c.settings.setCurrentConversation(branch)
            done(problem)
        } }
    }

    fun delete(messageId: Long) = c.chat.deleteMessage(messageId)

    /** Sends a recording, with the quote waiting if there is one. Without a conversation it is thrown away, and false. */
    fun sendVoice(clip: MessageAudio): Boolean {
        val id = state.value.conversationId
        if (id == null) {
            c.images.delete(listOf(clip.file))
            return false
        }
        c.chat.sendVoice(id, clip, quoting)
        quoting = null
        return true
    }

    /** Rings the TA of this conversation; its screen comes up by itself (CleosNavHost). */
    fun call() {
        state.value.conversationId?.let { c.calls.start(it) }
    }

    fun retryVoice(messageId: Long) {
        val id = state.value.conversationId ?: return
        c.chat.retryVoice(id, messageId)
    }

    /** The person's answer to a card asking whether the TA may use an outside service's tool. */
    fun answerAsk(answer: ChatRepository.Answer) {
        val id = state.value.conversationId ?: return
        c.chat.answer(id, answer)
    }

    /** The person's own version of the recap. Emptied, the TA keeps nothing of what came before. */
    fun saveRecap(text: String) {
        val id = state.value.conversationId ?: return
        viewModelScope.launch { c.db.conversations().editRecap(id, text.trim().take(Recap.MAX_STORED).ifEmpty { null }) }
    }

    fun answerSecret(requestMessageId: Long, grant: Boolean) {
        conversationId.value?.let { c.chat.answerSecretRequest(it, requestMessageId, grant) }
    }

    /** An empty conversation is reused instead of stacking up blank ones. */
    fun newConversation() {
        viewModelScope.launch {
            if (state.value.messages.isEmpty() && conversationId.value != null) return@launch
            c.settings.setCurrentConversation(c.chat.newConversation(c.companions.current.first().id))
        }
    }

    fun switchTo(companionId: Long) {
        viewModelScope.launch { c.companions.select(companionId) }
    }

    /** A new TA, chosen right away; [then] opens their settings to name them and pick a model. */
    fun addCompanion(then: () -> Unit) {
        viewModelScope.launch {
            c.companions.add()
            then()
        }
    }
}
