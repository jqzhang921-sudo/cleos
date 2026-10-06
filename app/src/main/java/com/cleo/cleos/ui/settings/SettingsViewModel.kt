package com.cleo.cleos.ui.settings

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.ai.ChatException
import com.cleo.cleos.ai.Habits
import com.cleo.cleos.ai.ToolGroup
import android.media.MediaPlayer
import com.cleo.cleos.ai.Speech
import com.cleo.cleos.ai.SpeechException
import com.cleo.cleos.ai.Voice
import com.cleo.cleos.ai.VoiceOption
import com.cleo.cleos.ai.VoiceService
import kotlinx.coroutines.CancellationException
import java.io.File
import com.cleo.cleos.ai.VoicePreset
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.McpServer
import com.cleo.cleos.data.Companions
import com.cleo.cleos.data.GlassMode
import com.cleo.cleos.data.ImportException
import com.cleo.cleos.data.Pats
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.LaterEntity
import com.cleo.cleos.data.db.LoreEntity
import com.cleo.cleos.data.db.WakeEntity
import com.cleo.cleos.ui.wallpaper.WallpaperAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PERSONA_LIMIT = Companions.PERSONA_LIMIT

/**
 * Text fields are edited locally and written back after a short pause (and once more
 * on leaving), so typing does not hit the disk on every keystroke. The API key is the
 * exception: it is only ever written, encrypted, and never read back into a field.
 *
 * The model and the TA's name belong to the TA that was current when the screen opened (or
 * the one just imported); the rest is the person's and the app's. The persona is shown here
 * but written on its own page (PersonaScreen), which saves it.
 */
class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    var companionId by mutableLongStateOf(0L)
        private set
    var companionCount by mutableIntStateOf(1)
        private set
    private var deleted = false

    /** The TA's model, as edited. */
    val chat = EndpointFields(c, viewModelScope)

    /** The model the TA has for words that are heard (CompanionEntity.spokenModelOn), and whether it is on. */
    val spoken = EndpointFields(c, viewModelScope)
    var spokenOn by mutableStateOf(false)
    var aiName by mutableStateOf("")
    var userName by mutableStateOf("")

    /** The TA's persona as saved, kept up to date: PersonaScreen writes it, never this. */
    var persona by mutableStateOf("")
        private set
    var replyWaitSeconds by mutableStateOf(3)
    var followUpEnabled by mutableStateOf(false)
    var followUpDelaySeconds by mutableStateOf(60)
    var freeTopicEnabled by mutableStateOf(false)
    var freeTopicLevel by mutableStateOf(1)
    var freeTopicQuietOn by mutableStateOf(true)
    var freeTopicQuietStart by mutableStateOf(1380)
    var freeTopicQuietEnd by mutableStateOf(480)
        private set
    var deepThinking by mutableStateOf(false)
        private set
    var proactive by mutableStateOf(true)
        private set

    /** The person's usual day as the greetings read it (ai/Routine.kt); null until worked out. */
    var habits by mutableStateOf<Habits?>(null)
        private set
    var historySize by mutableIntStateOf(40)
    var weatherCity by mutableStateOf("")
    var voiceBaseUrl by mutableStateOf("")
    var voiceModel by mutableStateOf("")
    var voiceKeyInput by mutableStateOf("")
    var voiceTesting by mutableStateOf(false)
        private set
    var voiceResult by mutableStateOf<String?>(null)
        private set
    /** The service the TA's voice comes from; null while none is picked. */
    var voiceService by mutableStateOf<VoiceService?>(null)
        private set

    /** The voice picked on each service with a list, by VoiceService key. */
    val speechVoices = mutableStateMapOf<String, String>()
    var minimaxGlobal by mutableStateOf(false)
    var speechBaseUrl by mutableStateOf("")
    var speechModel by mutableStateOf("")
    var speechVoice by mutableStateOf("")
    var elevenVoice by mutableStateOf("")
    var elevenModel by mutableStateOf("")
    var speechKeyInput by mutableStateOf("")

    /** Every voice the picked service has for this account, while the list of them is open. */
    var listedVoices by mutableStateOf<List<VoiceOption>?>(null)
        private set
    var listingVoices by mutableStateOf(false)
        private set
    var listProblem by mutableStateOf<String?>(null)
        private set
    var speechBusy by mutableStateOf(false)
        private set
    var speechResult by mutableStateOf<String?>(null)
        private set
    private var speechPlayer: MediaPlayer? = null
    var loaded by mutableStateOf(false)
        private set

    var wallpaperBusy by mutableStateOf(false)
        private set
    var wallpaperError by mutableStateOf<String?>(null)
        private set

    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** What came of this TA's last note coming due (ai/Later.kt), for the line under its switch. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lastWake: StateFlow<WakeEntity?> = snapshotFlow { companionId }
        .flatMapLatest { c.db.wakes().observeLatest(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val wakeActivity: StateFlow<List<com.cleo.cleos.data.db.WakeActivityEntity>> = snapshotFlow { companionId }
        .flatMapLatest { c.db.wakeActivities().observe(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** What this TA has noted and is waiting on: how many, and when the first comes due. Not what. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val waiting: StateFlow<List<LaterEntity>> = snapshotFlow { companionId }
        .flatMapLatest { c.db.later().observeFor(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** This TA's 设定, for what the list of groups says; the 设定 page reads them itself. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lore: StateFlow<List<LoreEntity>> = snapshotFlow { companionId }
        .flatMapLatest { c.db.lore().observeFor(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The address the voice service's key is filed under (Speech.keyAddress), as the fields stand. */
    private fun speechKeyAddress(): String {
        val service = voiceService ?: return ""
        return Speech.keyAddress(AppSettings(minimaxGlobal = minimaxGlobal, speechBaseUrl = speechBaseUrl), service)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val hasSpeechKey: StateFlow<Boolean> = snapshotFlow { speechKeyAddress() }
        .flatMapLatest { c.secrets.hasKey(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether the transcription service's address has a key yet: filed by address, like the chat keys. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val hasVoiceKey: StateFlow<Boolean> = snapshotFlow { voiceBaseUrl }
        .flatMapLatest { c.secrets.hasKey(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val mcpServers: StateFlow<List<McpServer>> = c.mcp.servers.all.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Every TA: the one being edited is shown with its picture, and another can be picked. */
    val companions: StateFlow<List<CompanionEntity>> = c.companions.all.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            c.companions.all.collect { all ->
                companionCount = all.size
                all.firstOrNull { it.id == companionId }?.let { persona = it.persona }
            }
        }
        viewModelScope.launch { habits = c.later.habits() }
        viewModelScope.launch {
            val s = c.settings.current()
            load(c.companions.current())
            userName = s.userName
            historySize = s.historySize
            weatherCity = s.weatherCity
            voiceBaseUrl = s.voiceBaseUrl
            voiceModel = s.voiceModel
            voiceService = VoiceService.of(s.speechEngine)
            speechVoices.putAll(s.speechVoices)
            minimaxGlobal = s.minimaxGlobal
            speechBaseUrl = s.speechBaseUrl
            speechModel = s.speechModel
            speechVoice = s.speechVoice
            elevenVoice = s.elevenVoice
            elevenModel = s.elevenModel
            loaded = true
            watch()
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun watch() {
        snapshotFlow {
            listOf(
                chat.baseUrl, chat.model, spokenOn, spoken.baseUrl, spoken.model,
                aiName, userName, historySize, weatherCity, voiceBaseUrl, voiceModel,
                voiceService, speechVoices.toMap(), minimaxGlobal, speechBaseUrl, speechModel, speechVoice, elevenVoice, elevenModel,
            )
        }
            .drop(1)
            .debounce(500)
            .collect { persist() }
    }

    /** Which TA the fields edit. Everything is set in one go, with nothing in between. */
    private fun load(ta: CompanionEntity) {
        companionId = ta.id
        chat.load(ta.apiBaseUrl, ta.apiModel)
        spokenOn = ta.spokenModelOn
        spoken.load(ta.spokenApiBaseUrl, ta.spokenApiModel)
        aiName = ta.name
        persona = ta.persona
        replyWaitSeconds = com.cleo.cleos.ai.ReplyWaitRules.seconds(ta.replyWaitSeconds)
        followUpEnabled = ta.followUpEnabled
        followUpDelaySeconds = com.cleo.cleos.ai.FollowUpRules.seconds(ta.followUpDelaySeconds)
        freeTopicEnabled = ta.freeTopicEnabled
        freeTopicLevel = com.cleo.cleos.ai.FreeTopicRules.level(ta.freeTopicLevel).id
        freeTopicQuietOn = ta.freeTopicQuietOn
        freeTopicQuietStart = com.cleo.cleos.ai.FreeTopicRules.minute(ta.freeTopicQuietStart, 1380)
        freeTopicQuietEnd = com.cleo.cleos.ai.FreeTopicRules.minute(ta.freeTopicQuietEnd, 480)
        deepThinking = ta.deepThinking
        proactive = ta.proactive
    }

    private suspend fun persist() {
        if (!loaded || deleted) return
        // Read before suspending: the fields can be loaded with another TA meanwhile (an
        // import), and the old TA must not get the new one's values.
        val id = companionId
        val url = chat.baseUrl.trim()
        val m = chat.model.trim()
        val heardOn = spokenOn
        val heardUrl = spoken.baseUrl.trim()
        val heardModel = spoken.model.trim()
        val name = aiName.trim()
        val user = userName.trim()
        val history = historySize
        val city = weatherCity.trim()
        val voiceUrl = voiceBaseUrl.trim()
        val voiceM = voiceModel.trim()
        // Not the persona: written on its own page, it must not be put back as it was when this one opened.
        c.companions.update(id) {
            it.copy(apiBaseUrl = url, apiModel = m, name = name, spokenModelOn = heardOn, spokenApiBaseUrl = heardUrl, spokenApiModel = heardModel)
        }
        val speech = speechSettings()
        c.settings.update {
            speech(it.copy(userName = user, historySize = history, weatherCity = city, voiceBaseUrl = voiceUrl, voiceModel = voiceM))
        }
    }

    /**
     * The settings of another TA, who also becomes the one being talked to (as picking them on the
     * home page does). What was typed for this one is saved first, a key left unsaved included.
     */
    fun switchCompanion(id: Long) {
        if (id == companionId) return
        val pending = listOfNotNull(chat.pendingKey(), spoken.pendingKey())
        viewModelScope.launch {
            persist()
            for ((address, key) in pending) c.secrets.setKey(address, key)
            c.companions.select(id)
            load(c.companions.current())
        }
    }

    /** A picture cropped on the profile page; it replaces an emoji the TA picked for itself. */
    fun setAvatar(picture: Bitmap) {
        val id = companionId
        c.appScope.launch { c.companions.setAvatar(id, c.images.save(picture, prefix = "avatar-"), emoji = null) }
    }

    /** Back to the first letter of the name. */
    fun clearAvatar() {
        val id = companionId
        c.appScope.launch { c.companions.setAvatar(id, null, emoji = null) }
    }

    fun setChatTextSize(size: Int) {
        viewModelScope.launch { c.settings.update { it.copy(chatTextSize = size) } }
    }

    fun setChatAvatars(on: Boolean) {
        viewModelScope.launch { c.settings.update { it.copy(chatAvatars = on) } }
    }

    fun setAvatarEachMessage(on: Boolean) {
        viewModelScope.launch { c.settings.update { it.copy(avatarEachMessage = on) } }
    }

    /** 拍一拍: the verb, what follows the TA's name, and the buzz — what the chat's dialog edits too. */
    fun setPat(verb: String, suffix: String, buzz: Boolean) {
        viewModelScope.launch {
            c.settings.update {
                it.copy(patVerb = Pats.cleanVerb(verb), patSuffix = Pats.cleanSuffix(suffix), patBuzz = buzz)
            }
        }
    }

    fun setPatBuzz(on: Boolean) {
        viewModelScope.launch { c.settings.update { it.copy(patBuzz = on) } }
    }

    /** Removes this TA with their conversations and diary; [then] leaves the screen. */
    fun deleteCompanion(then: () -> Unit) {
        deleted = true
        val id = companionId
        viewModelScope.launch {
            c.chat.stopRepliesOf(id)
            c.companions.delete(id)
            then()
        }
    }

    /** The voice fields as they are now, to lay over settings (read before anything suspends). */
    private fun speechSettings(): (AppSettings) -> AppSettings {
        val engine = voiceService?.key.orEmpty()
        val voices = speechVoices.toMap().mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        val global = minimaxGlobal
        val url = speechBaseUrl.trim()
        val m = speechModel.trim()
        val voice = speechVoice.trim()
        val eVoice = elevenVoice.trim()
        val eModel = elevenModel.trim()
        return {
            it.copy(
                speechEngine = engine,
                speechVoices = voices,
                minimaxGlobal = global,
                speechBaseUrl = url,
                speechModel = m,
                speechVoice = voice,
                elevenVoice = eVoice,
                elevenModel = eModel,
            )
        }
    }

    fun pickService(service: VoiceService) {
        voiceService = service
        speechKeyInput = ""
        speechResult = null
        listProblem = null
    }

    /** The voice [service] speaks in: ElevenLabs' and the hand-filled service's are fields of their own. */
    fun voiceOf(service: VoiceService): String = when (service) {
        VoiceService.ElevenLabs -> elevenVoice
        VoiceService.Other -> speechVoice
        else -> speechVoices[service.key] ?: Speech.builtIn(service).first().id
    }

    fun pickVoice(service: VoiceService, id: String) {
        when (service) {
            VoiceService.ElevenLabs -> elevenVoice = id
            VoiceService.Other -> speechVoice = id
            else -> speechVoices[service.key] = id
        }
        speechResult = null
    }

    fun setMinimaxSite(global: Boolean) {
        minimaxGlobal = global
        speechKeyInput = ""
        speechResult = null
    }

    /** Asks the service for every voice it has for this account, and opens the list of them. */
    fun listVoices() {
        val service = voiceService ?: return
        if (listingVoices) return
        listingVoices = true
        listProblem = null
        val s = speechSettings()(settings.value)
        viewModelScope.launch {
            try {
                val found = c.speaker.voices(s, service)
                if (found.isEmpty()) listProblem = "没列出音色来" else listedVoices = found
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeechException) {
                listProblem = e.message
            } finally {
                listingVoices = false
            }
        }
    }

    fun closeVoiceList() {
        listedVoices = null
    }

    fun saveSpeechKey() {
        val url = speechKeyAddress().trim()
        val key = speechKeyInput.trim()
        if (url.isEmpty() || key.isEmpty()) return
        viewModelScope.launch {
            c.secrets.setKey(url, key)
            speechKeyInput = ""
        }
    }

    /** Says a sentence with the voice as set up on screen, saved or not, and plays it. */
    fun previewSpeech() {
        if (speechBusy) return
        speechBusy = true
        speechResult = null
        val s = speechSettings()(settings.value)
        viewModelScope.launch {
            speechResult = try {
                val clip = c.speaker.speak(s, Speech.SAMPLE)
                // Heard the way the TA's messages will be: beside the ear, on headphones.
                val ear = if (settings.value.earVoice && c.ear.headphones()) c.ear.prepared(clip.file) else null
                play(c.images.file(clip.file), ear)
                "能用，正在放（${Voice.duration(clip.ms)}${if (ear != null) "，戴着耳机，在耳边" else ""}）。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "没成：${e.message ?: e.javaClass.simpleName}"
            }
            speechBusy = false
        }
    }

    /** Plays a try-out ([ear], what was made of it for the ear, when there is that), then throws both away. */
    private fun play(file: File, ear: File? = null) {
        speechPlayer?.release()
        val p = MediaPlayer()
        fun gone() {
            file.delete()
            if (ear != null) c.ear.forget(file.name)
        }
        runCatching {
            p.setDataSource((ear ?: file).path)
            p.setOnCompletionListener {
                it.release()
                if (speechPlayer === it) speechPlayer = null
                gone()
            }
            p.prepare()
            p.start()
            speechPlayer = p
        }.onFailure {
            p.release()
            gone()
            throw it
        }
    }

    fun applyVoicePreset(p: VoicePreset) {
        voiceBaseUrl = p.baseUrl
        voiceModel = p.model
        voiceResult = null
    }

    fun saveVoiceKey() {
        val url = voiceBaseUrl.trim()
        val key = voiceKeyInput.trim()
        if (url.isEmpty() || key.isEmpty()) return
        viewModelScope.launch {
            c.secrets.setKey(url, key)
            voiceKeyInput = ""
        }
    }

    /** Sends a second of silence: a service that answers at all is set up right. */
    fun testVoice() {
        if (voiceTesting || voiceBaseUrl.isBlank()) return
        voiceTesting = true
        voiceResult = null
        viewModelScope.launch {
            val probe = c.images.file("voice_probe.wav")
            voiceResult = try {
                withContext(Dispatchers.IO) { probe.writeBytes(Voice.silence()) }
                c.transcriber.transcribe(voiceBaseUrl, voiceModel, probe)
                "能用。（试的是一秒静音，所以没转出字来。）"
            } catch (e: ChatException) {
                "没成：${e.message}"
            } finally {
                withContext(Dispatchers.IO) { probe.delete() }
            }
            voiceTesting = false
        }
    }

    fun setMcpEnabled(id: String, on: Boolean) {
        viewModelScope.launch { c.mcp.servers.setEnabled(id, on) }
    }

    /** Takes effect at once, like the tool switches. */
    fun setReplyWait(seconds: Int) {
        val value = com.cleo.cleos.ai.ReplyWaitRules.seconds(seconds)
        replyWaitSeconds = value
        val id = companionId
        viewModelScope.launch { c.companions.update(id) { it.copy(replyWaitSeconds = value) } }
    }

    fun setFollowUp(on: Boolean) {
        followUpEnabled = on
        val id = companionId
        viewModelScope.launch {
            c.companions.update(id) { it.copy(followUpEnabled = on) }
            if (!on) c.followUps.cancelFor(id)
        }
    }

    fun setFollowUpDelay(seconds: Int) {
        val value = com.cleo.cleos.ai.FollowUpRules.seconds(seconds)
        followUpDelaySeconds = value
        val id = companionId
        viewModelScope.launch { c.companions.update(id) { it.copy(followUpDelaySeconds = value) } }
    }

    fun setFreeTopic(on: Boolean) {
        freeTopicEnabled = on
        changeFreeTopic { it.copy(freeTopicEnabled = on) }
    }

    fun chooseFreeTopicLevel(level: Int) {
        freeTopicLevel = com.cleo.cleos.ai.FreeTopicRules.level(level).id
        val value = freeTopicLevel
        changeFreeTopic { it.copy(freeTopicLevel = value) }
    }

    fun setFreeTopicQuiet(on: Boolean) {
        freeTopicQuietOn = on
        changeFreeTopic { it.copy(freeTopicQuietOn = on) }
    }

    fun setFreeTopicTime(start: Boolean, minutes: Int) {
        val value = com.cleo.cleos.ai.FreeTopicRules.minute(minutes, if (start) 1380 else 480)
        if (start) freeTopicQuietStart = value else freeTopicQuietEnd = value
        changeFreeTopic { if (start) it.copy(freeTopicQuietStart = value) else it.copy(freeTopicQuietEnd = value) }
    }

    private fun changeFreeTopic(transform: (com.cleo.cleos.data.db.CompanionEntity) -> com.cleo.cleos.data.db.CompanionEntity) {
        val id = companionId
        viewModelScope.launch {
            c.companions.update(id, transform)
            c.freeTopics.configure(id)
        }
    }

    fun setThinking(on: Boolean) {
        deepThinking = on
        val id = companionId
        viewModelScope.launch { c.companions.update(id) { it.copy(deepThinking = on) } }
    }

    /** Off, what the TA noted goes unsaid: each note, when it comes due, finds the switch off and is dropped. */
    fun setReachOut(on: Boolean) {
        proactive = on
        val id = companionId
        viewModelScope.launch { c.companions.update(id) { it.copy(proactive = on) } }
    }

    /** Whether notifications can reach the person at all (the app's switch, and on Android 13 the permission). */
    fun notificationsAllowed(): Boolean = c.notifier.allowed()

    /** The permission is being asked for here: the app asks only once (see MainActivity). */
    fun markNotificationsAsked() {
        viewModelScope.launch { c.settings.update { it.copy(notificationsAsked = true) } }
    }

    fun setTool(group: ToolGroup, on: Boolean) {
        viewModelScope.launch {
            c.settings.update { it.copy(tools = if (on) it.tools + group else it.tools - group) }
        }
    }

    fun setLetterEveryDays(days: Int) {
        viewModelScope.launch { c.settings.update { it.copy(letterEveryDays = days) } }
    }

    fun setWallpaper(uri: Uri) {
        viewModelScope.launch {
            wallpaperBusy = true
            wallpaperError = null
            try {
                val old = settings.value.wallpaper
                val stored = c.images.import(uri, maxEdge = 2560, prefix = "wallpaper-")
                val tone = withContext(Dispatchers.Default) {
                    c.images.thumbnail(stored.file)?.let { WallpaperAnalyzer.analyze(it) }
                }
                c.settings.update {
                    it.copy(
                        wallpaper = stored.file,
                        wallpaperDark = tone?.dark,
                        wallpaperHue = tone?.hue,
                        wallpaperChroma = tone?.chroma,
                        wallpaperTrough = tone?.trough,
                        wallpaperPeak = tone?.peak,
                    )
                }
                if (old != null && old != stored.file) c.images.delete(listOf(old))
            } catch (e: Exception) {
                wallpaperError = "这张图读不出来：${e.message ?: e.javaClass.simpleName}"
            } finally {
                wallpaperBusy = false
            }
        }
    }

    fun resetWallpaper() {
        viewModelScope.launch {
            val old = settings.value.wallpaper
            c.settings.update {
                it.copy(
                    wallpaper = null,
                    wallpaperDark = null,
                    wallpaperHue = null,
                    wallpaperChroma = null,
                    wallpaperTrough = null,
                    wallpaperPeak = null,
                )
            }
            if (old != null) c.images.delete(listOf(old))
        }
    }

    fun setGlassMode(mode: GlassMode) {
        viewModelScope.launch { c.settings.update { it.copy(glassMode = mode) } }
    }

    /** The colour of the person's own bubbles (ARGB); null follows the wallpaper. */
    fun setMyBubble(argb: Int?) {
        viewModelScope.launch { c.settings.update { it.copy(myBubble = argb) } }
    }

    fun setBubbleTheme(mine: Boolean, theme: String) {
        val id = companionId.toString()
        val chosen = com.cleo.cleos.ui.chat.BubbleThemes.valid(theme)
        viewModelScope.launch {
            c.settings.update { if (mine) it.copy(myBubbleTheme = chosen)
                else it.copy(taBubbleThemes = it.taBubbleThemes + (id to chosen)) }
        }
    }

    fun setBubblePadding(horizontal: Int, vertical: Int) {
        viewModelScope.launch { c.settings.update { it.copy(bubblePaddingX = horizontal.coerceIn(6, 24), bubblePaddingY = vertical.coerceIn(4, 16)) } }
    }

    fun setBubbleDecoration(value: com.cleo.cleos.data.BubbleDecoration) {
        viewModelScope.launch { c.settings.update { it.copy(bubbleDecoration = value.normalized()) } }
    }

    /** The TA's voice messages beside the ear on headphones (EarVoice). */
    fun setEarVoice(on: Boolean) {
        viewModelScope.launch { c.settings.update { it.copy(earVoice = on) } }
    }

    fun setExpandVoiceText(on: Boolean) {
        viewModelScope.launch { c.settings.update { it.copy(expandVoiceText = on) } }
    }

    var backupBusy by mutableStateOf(false)
        private set
    var backupMessage by mutableStateOf<String?>(null)
        private set
    var canUndoRestore by mutableStateOf(c.backup.hasSnapshot)
        private set

    fun exportBackup(uri: Uri) = runBackup("导出了") { c.backup.export(uri) }

    // A reply still running would write into conversations that are about to be replaced.
    fun restoreBackup(uri: Uri) = runBackup("恢复了") {
        c.chat.stopAll()
        c.backup.restore(uri).also { c.freeTopics.restore() }
    }

    fun undoRestore() = runBackup("撤销了，回到恢复前：") {
        c.chat.stopAll()
        c.backup.undoRestore().also { c.freeTopics.restore() }
    }

    /** A memory file being read in, and the word on how it went. */
    var memoryBusy by mutableStateOf(false)
        private set
    var memoryMessage by mutableStateOf<String?>(null)
        private set

    /**
     * Brings a file from another app in: a 角色卡 (one TA each, and everything else in the file
     * goes to the first of them), a world book, a memory store, a chat log — any of the four, or
     * several at once. Nothing already here is touched: a memory of the same name only gets the
     * details it was missing, a 设定 entry that is already there is left be, and a memory whose
     * kind is full goes to 设定 rather than nowhere.
     */
    fun importMemoryFile(uri: Uri) {
        if (memoryBusy) return
        val id = companionId
        if (id <= 0L) return
        memoryBusy = true
        memoryMessage = null
        viewModelScope.launch {
            try {
                val r = c.imports.import(uri, id)
                memoryMessage = r.said()
                // A 角色卡 makes a TA (a file of cards, one each) and switches to the first:
                // the fields here follow, so what is typed next is about the one just imported.
                r.newTaId?.let { made ->
                    persist()
                    c.companions.select(made)
                    load(c.companions.current())
                }
            } catch (e: ImportException) {
                memoryMessage = e.message
            } catch (e: Exception) {
                memoryMessage = "出错了：${e.message ?: e.javaClass.simpleName}"
            } finally {
                memoryBusy = false
            }
        }
    }

    // App scope: a restore half done because the screen was closed is the worst outcome.
    private fun runBackup(done: String, action: suspend () -> Any) {
        if (backupBusy) return
        backupBusy = true
        backupMessage = null
        c.appScope.launch {
            val message = try {
                "$done ${action()}"
            } catch (e: Exception) {
                (e as? com.cleo.cleos.data.BackupException)?.message ?: "出错了：${e.message ?: e.javaClass.simpleName}"
            }
            withContext(Dispatchers.Main) {
                backupMessage = message
                backupBusy = false
                canUndoRestore = c.backup.hasSnapshot
            }
        }
    }

    override fun onCleared() {
        speechPlayer?.release()
        speechPlayer = null
        val pending = listOfNotNull(chat.pendingKey(), spoken.pendingKey())
        c.appScope.launch {
            persist()
            if (!deleted) for ((address, key) in pending) c.secrets.setKey(address, key)
        }
    }
}
