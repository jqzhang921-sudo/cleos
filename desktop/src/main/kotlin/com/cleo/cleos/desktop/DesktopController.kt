package com.cleo.cleos.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Path

class DesktopController(val store: LocalStore, private val vault: KeyVault, private val client: TextClient = TextClient()) {
    var data by mutableStateOf(store.load()); private set
    var busyThread by mutableStateOf<String?>(null); private set
    var activeTa by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf(""); private set
    var addingImages by mutableStateOf(false); private set
    internal val audio=AudioPlayback()
    internal var recorder=VoiceRecorder()
    internal var transcriber:VoiceTranscriber=VoiceClient()
    var transcribing by mutableStateOf(false);private set
    private var transcriptionJob:Job?=null
    val mediaBusy get()=addingImages || recorder.state.active || transcribing
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private var generation: Job? = null
    private var draftSave: Job? = null
    private var stopped = AtomicBoolean(false)
    private var closed = false
    val selected get() = data.conversations.find { it.id == data.selectedId } ?: data.conversations.first()

    // Writes are short and serialized on the UI dispatcher. This prototype never overwrites
    // the phone's database; its separate local library is also guarded by an OS file lock.
    private fun save(): Boolean = if (closed) false else try { store.save(data); true } catch (_: Exception) {
        notice = "本地保存失败，请检查资料库所在磁盘；当前窗口的内容仍保留。"; false
    }
    private fun editChat(id: String, change: (Conversation) -> Conversation) {
        data = data.copy(conversations = data.conversations.map { if (it.id == id) change(it) else it })
    }
    fun select(id: String) { if(mediaBusy){notice="请先完成录音、转写或附件处理，再切换对话。";return};if (data.conversations.any { it.id == id }) {if(id!=data.selectedId)audio.stop(); data = data.copy(selectedId = id); save() } }
    fun draft(text: String) {
        editChat(selected.id) { it.copy(draft = text) }
        draftSave?.cancel(); draftSave = scope.launch { delay(400); save() }
    }
    fun clearNotice() { notice = "" }
    fun imagePickerFailed() {notice="图片选择窗口暂时无法打开，请稍后重试。"}
    fun imageInputProblem(message:String) {notice=message}
    suspend fun attachImages(paths:List<Path>) = attachOffer(ImageOffer.Files(paths))
    internal suspend fun attachOffer(offer:ImageOffer,conversationId:String=selected.id) {
        if(closed || busyThread!=null || mediaBusy || selected.readOnly)return
        if(conversationId!=selected.id) {notice="对话已经切换，请在当前对话里重新添加图片。";return}
        val id=selected.id
        if(offer.count==0)return
        if(offer.count+selected.draftImages.size>4) {notice="每条消息最多附 4 张图片。";return}
        addingImages=true
        val added=mutableListOf<Attachment>()
        var committed=false
        try {
            when(offer) {
                is ImageOffer.Files -> for(path in offer.paths) {withContext(Dispatchers.IO) {added+=addLocalImage(store.directory,path)}}
                is ImageOffer.Pixels -> withContext(Dispatchers.IO) {added+=addRasterImage(store.directory,offer.image)}
            }
            if(!closed) {val before=data;editChat(id) {it.copy(draftImages=it.draftImages+added)};if(save())committed=true else data=before}
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {notice=(e as? TextFailure)?.message?:"图片没有添加成功，原文件未更改。"}
        finally {
            if(!committed)withContext(NonCancellable+Dispatchers.IO) {added.forEach {a->localMedia(store.directory,a.file)?.let {java.nio.file.Files.deleteIfExists(it)}}}
            addingImages=false
        }
    }
    fun removeDraftImage(file:String) {
        if(mediaBusy || busyThread!=null)return
        val before=data
        editChat(selected.id) {it.copy(draftImages=it.draftImages.filterNot {a->a.file==file})}
        if(!save()) {data=before;return}
        val used=data.conversations.any {c->c.draftImages.any {it.file==file} || c.messages.any {m->m.attachments.any {it.file==file} || m.speakerAvatar==file}} ||
            data.userAvatar==file || data.companions.any {it.avatar==file || it.userAvatar==file}
        if(!used && Regex("media/desktop-[a-f0-9-]{36}\\.jpg").matches(file))runCatching {localMedia(store.directory,file)?.let {java.nio.file.Files.deleteIfExists(it)}}
    }
    fun addCompanion(name: String): DesktopTa {
        val ta = DesktopTa(name = name.trim().ifBlank { "TA" }, tone = data.companions.size % 5)
        val chat = Conversation(memberIds = listOf(ta.id))
        data = data.copy(companions = data.companions + ta, conversations = data.conversations + chat, selectedId = chat.id)
        save(); return ta
    }
    fun createGroup(name: String, ids: List<String>): Boolean {
        if (busyThread != null || mediaBusy || ids.distinct().size !in 2..4 || ids.any { id -> data.companions.none { it.id == id } }) return false
        val chat = Conversation(title = name.trim().ifBlank { "一起聊聊" }, memberIds = ids.distinct(), group = true)
        data = data.copy(conversations = data.conversations + chat, selectedId = chat.id)
        return save()
    }
    fun hasKey(ta: DesktopTa): Boolean = runCatching { vault.get(ta.endpoint.baseUrl).isNotBlank() }.getOrDefault(false)
    fun configure(ta: DesktopTa, newKey: String, removeKey: Boolean): Boolean {
        if (busyThread != null || mediaBusy) { notice = "请先完成回复、录音或转写，再修改模型。"; return false }
        return try {
            chatUrl(ta.endpoint)
            require(ta.endpoint.model.isNotBlank()) { "请填写模型名。" }
            if (newKey.isNotBlank() || removeKey) vault.put(ta.endpoint.baseUrl, if (removeKey) "" else newKey)
            data = data.copy(companions = data.companions.map { if (it.id == ta.id) ta else it })
            save().also {if(it)notice=""}
        } catch (e: TextFailure) { notice = e.message.orEmpty(); false }
        catch (_: Exception) { notice = "配置未保存，请检查模型名、接口地址或 Windows 密钥存储。"; false }
    }
    fun notes(id: String, text: String) {
        data = data.copy(companions = data.companions.map { if (it.id == id) it.copy(notes = text) else it }); save()
    }
    fun hasVoiceKey()=runCatching {vault.get(voiceKeyAddress(data.transcription.baseUrl)).isNotBlank()}.getOrDefault(false)
    fun configureVoice(settings:TranscriptionSettings,key:String,remove:Boolean):Boolean {
        if(mediaBusy || busyThread!=null)return false
        return try {
            transcriptionUrl(settings)
            if(settings.model.isBlank())throw TextFailure("请填写语音转文字的模型名。")
            if(key.isNotBlank() || remove)vault.put(voiceKeyAddress(settings.baseUrl),if(remove)"" else key.trim())
            val before=data;data=data.copy(transcription=settings)
            if(save()){notice="";true}else{data=before;false}
        }catch(e:TextFailure){notice=e.message.orEmpty();false}catch(_:Exception){notice="语音配置未保存，请检查地址、模型名或密钥存储。";false}
    }
    fun startRecording() {
        if(closed || busyThread!=null || mediaBusy || selected.readOnly || selected.draftVoice!=null)return
        audio.stop();notice="";val id=selected.id
        recorder.start(store.directory,id){draft->
            if(closed){draft?.let {discardVoiceFile(store.directory,it.file)};return@start}
            if(draft==null){notice=recorder.state.error;return@start}
            val before=data;editChat(id){it.copy(draftVoice=draft)}
            if(!save()){data=before;discardVoiceFile(store.directory,draft.file)}
        }
    }
    fun stopRecording(discard:Boolean=false)=recorder.stop(discard)
    fun voiceTranscript(text:String) {
        if(transcribing)return
        editChat(selected.id){it.copy(draftVoice=it.draftVoice?.copy(transcript=text.take(20000)))}
        draftSave?.cancel();draftSave=scope.launch {delay(400);save()}
    }
    fun removeVoice() {
        if(mediaBusy || busyThread!=null)return
        val file=selected.draftVoice?.file?:return
        audio.stop();val before=data;editChat(selected.id){it.copy(draftVoice=null)}
        if(!save()){data=before;return}
        val used=data.conversations.any {it.draftVoice?.file==file || it.messages.any {m->m.attachments.any {a->a.file==file}}}
        if(!used)discardVoiceFile(store.directory,file)
    }
    fun cancelTranscription(){transcriptionJob?.cancel()}
    fun transcribeVoice() {
        if(closed || busyThread!=null || mediaBusy || selected.readOnly)return
        val draft=selected.draftVoice?:return;val id=selected.id;val settings=data.transcription
        val file=localMedia(store.directory,draft.file)
        if(file==null){notice="录音文件暂时找不到，文字草稿保留。";return}
        val key=runCatching {vault.get(voiceKeyAddress(settings.baseUrl))}.getOrDefault("")
        try {transcriptionUrl(settings);if(settings.model.isBlank() || key.isBlank())throw TextFailure("请先到「设置 → 语音转文字」配置接口、模型和 Key。")}
        catch(e:TextFailure){notice=e.message.orEmpty();return}
        notice="";transcribing=true
        transcriptionJob=scope.launch {
            try {
                val text=transcriber.transcribe(settings,key,file).trim()
                if(text.isBlank() || text.length>20000)throw TextFailure("转写没有可用文字或超过长度上限，录音草稿保留。")
                if(!closed && data.conversations.find {it.id==id}?.draftVoice?.file==draft.file){editChat(id){it.copy(draftVoice=it.draftVoice?.copy(transcript=text))};save()}
            }catch(e:CancellationException){if(!closed)notice="已取消转写，录音草稿保留。";throw e}
            catch(e:Exception){notice=(e as? TextFailure)?.message?:"转写未完成，录音草稿保留，可重试或自行填写。"}
            finally {transcribing=false}
        }
    }
    fun renameGroup(name: String) { editChat(selected.id) { it.copy(title = name.trim().ifBlank { "一起聊聊" }) }; save() }
    fun stop() { stopped.set(true); generation?.cancel() }
    fun send() {
        if (busyThread != null || mediaBusy || (selected.draft.isBlank() && selected.draftImages.isEmpty() && selected.draftVoice==null)) return
        if (selected.readOnly) { notice = "这个群保留为只读历史，已离开的成员不会重新加入。"; return }
        val chat = selected
        if(chat.draftVoice!=null && chat.draftVoice.transcript.isNullOrBlank()){notice="请先转写录音或填写对应文字，再确认发送。";return}
        val tas = chat.memberIds.mapNotNull { id -> data.companions.find { it.id == id } }
        if(chat.draftImages.isNotEmpty() && tas.none {it.endpoint.supportsImages}) {notice="这段对话的模型尚未开启看图。请在角色与模型设置确认模型支持后开启，或移除图片再发送文字。";return}
        if (tas.any { !hasKey(it) || it.endpoint.model.isBlank() }) { notice = "请先为${tas.filter { !hasKey(it) || it.endpoint.model.isBlank() }.joinToString("、") { it.name }}配置模型和 API Key。"; return }
        val before=data
        editChat(chat.id) { it.copy(draft = "", draftImages=emptyList(),draftVoice=null,messages = it.messages + Message(text = listOfNotNull(it.draft.trim().takeIf(String::isNotBlank),it.draftVoice?.transcript?.trim()?.takeIf(String::isNotBlank)).joinToString("\n"),attachments=it.draftImages+listOfNotNull(it.draftVoice?.let {voice->Attachment(voice.file,"audio","语音 · ${voice.millis/1000.0} 秒")}))) }
        if (!save()) {data=before;return}
        stopped = AtomicBoolean(false); busyThread = chat.id
        generation = scope.launch {
            try { for (ta in tas) { ensureActive(); reply(chat.id, ta) } }
            finally { withContext(NonCancellable) { activeTa = null; busyThread = null; save() } }
        }
    }
    fun retry(messageId: String) {
        if (busyThread != null || mediaBusy || selected.readOnly) return
        val chat = selected
        val index = chat.messages.indexOfFirst { it.id == messageId }
        if (index < 0 || index != chat.messages.lastIndex) { notice = "目前只支持重试最后一条回复，避免改写后续对话。"; return }
        val old = chat.messages[index]
        val ta = data.companions.find { it.id == old.speakerId } ?: return
        if (!hasKey(ta)) { notice = "请先填写这位 TA 的 API Key。"; return }
        // Keep partial text in history; a new response is clearly a separate attempt.
        stopped = AtomicBoolean(false); busyThread = chat.id
        generation = scope.launch {
            try { reply(chat.id, ta) }
            finally { withContext(NonCancellable) { activeTa = null; busyThread = null; save() } }
        }
    }
    private suspend fun reply(chatId: String, ta: DesktopTa) {
        activeTa = ta.name
        val chat = data.conversations.first { it.id == chatId }
        val messages = prompt(data, chat, ta)
        val response = Message(speakerId = ta.id, status = "streaming")
        editChat(chatId) { it.copy(messages = it.messages + response) }; save()
        fun update(change: (Message) -> Message) = editChat(chatId) { c -> c.copy(messages = c.messages.map { if (it.id == response.id) change(it) else it }) }
        var lastSave = 0L
        try {
            client.stream(ta.endpoint, vault.get(ta.endpoint.baseUrl), messages,store.directory).collect { event ->
                update { m -> when (event) {
                    is TextEvent.Text -> m.copy(text = m.text + event.value)
                    is TextEvent.Thought -> m.copy(reasoning = m.reasoning + event.value)
                } }
                if (System.currentTimeMillis() - lastSave > 350) { save(); lastSave = System.currentTimeMillis() }
            }
            update { if (it.text.isBlank()) it.copy(status = "error", error = "模型只返回了思考内容，没有收到正文。") else it.copy(status = "complete") }
        } catch (e: CancellationException) {
            update { it.copy(status = "interrupted", error = if (stopped.get()) "已停止，收到的内容已保留。" else "回复中断，收到的内容已保留。") }; throw e
        } catch (e: Exception) {
            val text = when (e) {
                is TextFailure -> e.message.orEmpty()
                is SocketTimeoutException -> "接口等待超时，收到的内容已保留。"
                is UnknownHostException -> "暂时找不到服务器，请检查网络和接口地址。"
                is IOException -> "网络连接中断，收到的内容已保留。"
                else -> "这次回复未能完成，请检查配置后重试。"
            }
            update { it.copy(status = "error", error = text) }
        } finally { save() }
    }
    fun close() { stop();recorder.close();audio.close();transcriptionJob?.cancel(); draftSave?.cancel(); save(); closed = true; scope.cancel(); store.close() }
}
