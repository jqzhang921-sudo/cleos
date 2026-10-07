package com.cleo.cleos.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cleo.cleos.ai.Greeting
import com.cleo.cleos.ai.ReplyWhen
import com.cleo.cleos.ai.Speech
import com.cleo.cleos.ai.ToolGroup
import com.cleo.cleos.glass.GlassPart
import com.cleo.cleos.glass.GlassTuning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

enum class GlassMode { Auto, Light, Dark }

/**
 * Settings for the whole app and for the person. Each TA's own (name, persona, model,
 * avatar) lives in their CompanionEntity.
 */
data class AppSettings(
    val feedInterests: String = "",
    val feedRssUrl: String = "",
    val feedCover: String? = null,
    val feedBio: String = "",
    val userName: String = "",
    /** How many recent messages go to the model with each turn. */
    val historySize: Int = 40,
    /** File name inside ImageStore, or null for the built-in wallpaper. */
    val wallpaper: String? = null,
    val glassMode: GlassMode = GlassMode.Auto,
    /** Wallpaper analysis, cached so startup doesn't decode the image to pick colours. */
    val wallpaperDark: Boolean? = null,
    val wallpaperHue: Float? = null,
    val wallpaperChroma: Float? = null,
    val wallpaperTrough: Float? = null,
    val wallpaperPeak: Float? = null,
    /** Glass the user tuned in the glass lab and applied, per part. */
    val glassTuning: Map<GlassPart, GlassTuning> = emptyMap(),
    /**
     * What the model may do. Reading the person's diary starts off: it is the one tool
     * that hands the model something private, so it waits to be asked for. So do location and
     * the calendar, which need a permission asked for when their switch is turned on.
     */
    val tools: Set<ToolGroup> = setOf(
        ToolGroup.Messages,
        ToolGroup.Todos,
        ToolGroup.AiDiary,
        ToolGroup.Secrets,
        ToolGroup.Avatar,
        ToolGroup.Weather,
        ToolGroup.Letters,
        ToolGroup.Memory,
        ToolGroup.Lore,
        ToolGroup.Alarm,
        ToolGroup.Stickers,
        ToolGroup.Pat,
    ),
    /** Where "今天天气怎么样" means, when the model isn't told a city. */
    val weatherCity: String = "",
    /** A file name inside ImageStore, or null for the lettered circle. */
    val userAvatar: String? = null,
    /** Avatars beside the bubbles in the chat. */
    val chatAvatars: Boolean = true,
    /** Beside every bubble, the way WeChat does; otherwise once for several in a row from one side. */
    val avatarEachMessage: Boolean = false,
    /** How big the chat's text is, in sp (ChatType.SIZES). */
    val chatTextSize: Int = 15,
    /** Voice transcripts start collapsed unless the person prefers reading alongside audio. */
    val expandVoiceText: Boolean = false,
    /** 拍一拍 (Pats): the verb's one character, what follows the TA's name, and whether the phone buzzes. */
    val patVerb: String = Pats.VERB,
    val patSuffix: String = "",
    val patBuzz: Boolean = true,
    /** The colour of the person's own bubbles (ARGB), still glass; null follows the wallpaper. */
    val myBubble: Int? = null,
    val myBubbleTheme: String = "glass",
    val bubblePaddingX: Int = 10,
    val bubblePaddingY: Int = 6,
    val bubblePresets: List<BubblePreset> = emptyList(),
    val bubbleDecoration: BubbleDecoration = BubbleDecoration(),
    val bubbleBackgrounds: Map<String, BubbleBackground> = emptyMap(),
    /** Each TA can use a different theme; keys are companion ids, preserved by backups. */
    val taBubbleThemes: Map<String, String> = emptyMap(),
    /** The notification permission was asked for once already; after that it is theirs to change in settings. */
    val notificationsAsked: Boolean = false,
    /** When a letter's reply should come, as picked the last time one was sent. */
    val letterReply: ReplyWhen = ReplyWhen.Hours,
    /** The least time, in days, between two letters a TA writes of their own. */
    val letterEveryDays: Int = 5,
    /** Where voice messages are turned into text (an OpenAI-shaped /audio/transcriptions), and with which model. */
    val voiceBaseUrl: String = "",
    val voiceModel: String = "",
    /** The TA's voice messages: which service makes them (a VoiceService key; empty for none yet). */
    val speechEngine: String = "",
    /** The voice picked on each service that has a list (VoiceService key to voice id); none picked is its first. */
    val speechVoices: Map<String, String> = emptyMap(),
    /** MiniMax's international site rather than the mainland's: each has its own keys. */
    val minimaxGlobal: Boolean = false,
    /** VoiceService.Other, any OpenAI-shaped /audio/speech: its address, model and voice. */
    val speechBaseUrl: String = "",
    val speechModel: String = "",
    val speechVoice: String = "",
    val elevenVoice: String = "",
    val elevenModel: String = "",
    /** The TA's voice messages played beside the ear when headphones are on (EarVoice). */
    val earVoice: Boolean = true,
)

/**
 * The one TA there was before there could be several, as the settings kept them. Read once,
 * to make that TA's CompanionEntity; nothing writes these keys any more.
 */
data class LegacyTa(
    val name: String,
    val persona: String,
    val baseUrl: String,
    val model: String,
    val avatar: String?,
    val avatarEmoji: String?,
    val knownSince: Long?,
)

/** [models]: for a service whose API lists none, the ones it is known to have, to pick from. */
data class ApiPreset(val name: String, val baseUrl: String, val defaultModel: String, val models: List<String> = emptyList())

/** Starting points only; the settings screen can list what an endpoint really serves. */
object ApiPresets {
    val DeepSeek = ApiPreset("DeepSeek", "https://api.deepseek.com", "deepseek-v4-flash")
    val all = listOf(
        DeepSeek,
        // GLM-5.3-Flash: cheap (a tenth of GLM-5.3), and it sees pictures, which the chat sends.
        // Its API has no list of models; these are the chat ones its docs name (2026-10).
        ApiPreset(
            "智谱",
            "https://open.bigmodel.cn/api/paas/v4",
            "glm-5.3-flash",
            models = listOf(
                "glm-5.3", "glm-5.3-flash", "glm-5.3-flashx", "glm-5.2", "glm-5.1", "glm-5", "glm-5-turbo",
                "glm-4.7", "glm-4.7-flashx", "glm-4.7-flash", "glm-4.6", "glm-4.6v", "glm-4.6v-flash", "glm-4.5-air", "glm-4.5-flash",
            ),
        ),
        ApiPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        ApiPreset("硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3"),
        ApiPreset("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        ApiPreset("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
    )

    /** The preset whose address [baseUrl] is, if any. */
    fun at(baseUrl: String): ApiPreset? = all.firstOrNull { it.baseUrl == baseUrl.trim().trimEnd('/') }
}

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        // The one TA of before (see LegacyTa).
        val apiBaseUrl = stringPreferencesKey("api_base_url")
        val apiModel = stringPreferencesKey("api_model")
        val aiName = stringPreferencesKey("ai_name")
        val persona = stringPreferencesKey("persona")
        val aiAvatar = stringPreferencesKey("ai_avatar")
        val aiAvatarEmoji = stringPreferencesKey("ai_avatar_emoji")
        val knownSince = longPreferencesKey("known_since")

        val userName = stringPreferencesKey("user_name")
        val historySize = intPreferencesKey("history_size")
        val wallpaper = stringPreferencesKey("wallpaper")
        val glassMode = stringPreferencesKey("glass_mode")
        val wallpaperDark = stringPreferencesKey("wallpaper_dark")
        val wallpaperHue = floatPreferencesKey("wallpaper_hue")
        val wallpaperChroma = floatPreferencesKey("wallpaper_chroma")
        val wallpaperTrough = floatPreferencesKey("wallpaper_trough")
        val wallpaperPeak = floatPreferencesKey("wallpaper_peak")
        val glassTuning = stringPreferencesKey("glass_tuning")
        val tools = stringPreferencesKey("tools")
        val weatherCity = stringPreferencesKey("weather_city")
        val userAvatar = stringPreferencesKey("user_avatar")
        val chatAvatars = booleanPreferencesKey("chat_avatars")
        val avatarEachMessage = booleanPreferencesKey("avatar_each_message")
        val chatTextSize = intPreferencesKey("chat_text_size")
        val expandVoiceText = booleanPreferencesKey("expand_voice_text")
        val patVerb = stringPreferencesKey("pat_verb")
        val patSuffix = stringPreferencesKey("pat_suffix")
        val patBuzz = booleanPreferencesKey("pat_buzz")
        val feedInterests = stringPreferencesKey("feed_interests")
        val feedRssUrl = stringPreferencesKey("feed_rss_url")
        val feedCover = stringPreferencesKey("feed_cover")
        val feedBio = stringPreferencesKey("feed_bio")
        val myBubble = intPreferencesKey("my_bubble")
        val myBubbleTheme = stringPreferencesKey("my_bubble_theme")
        val bubblePaddingX = intPreferencesKey("bubble_padding_x")
        val bubblePaddingY = intPreferencesKey("bubble_padding_y")
        val bubblePresets = stringPreferencesKey("bubble_presets")
        val bubbleDecoration = stringPreferencesKey("bubble_decoration")
        val bubbleBackgrounds = stringPreferencesKey("bubble_backgrounds")
        val taBubbleThemes = stringPreferencesKey("ta_bubble_themes")
        val notificationsAsked = booleanPreferencesKey("notifications_asked")
        val letterReply = stringPreferencesKey("letter_reply")
        val letterEveryDays = intPreferencesKey("letter_every_days")
        val voiceBaseUrl = stringPreferencesKey("voice_base_url")
        val voiceModel = stringPreferencesKey("voice_model")
        val speechEngine = stringPreferencesKey("speech_engine")
        val speechBaseUrl = stringPreferencesKey("speech_base_url")
        val speechModel = stringPreferencesKey("speech_model")
        val speechVoice = stringPreferencesKey("speech_voice")
        val elevenVoice = stringPreferencesKey("eleven_voice")
        val elevenModel = stringPreferencesKey("eleven_model")
        val speechVoices = stringPreferencesKey("speech_voices")
        val minimaxGlobal = booleanPreferencesKey("minimax_global")
        val earVoice = booleanPreferencesKey("ear_voice")
        val currentConversation = stringPreferencesKey("current_conversation")
        val currentCompanion = longPreferencesKey("current_companion")
        val morningGreeted = longPreferencesKey("morning_greeted")
        val nightGreeted = longPreferencesKey("night_greeted")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        // Before each voice service had its own place, "api" stood for all the OpenAI-shaped ones.
        val (speechEngine, speechVoices) = Speech.migrate(
            this[Keys.speechEngine] ?: d.speechEngine,
            this[Keys.speechBaseUrl].orEmpty(),
            this[Keys.speechVoice].orEmpty(),
            decodeVoices(this[Keys.speechVoices]),
        )
        return AppSettings(
            feedInterests = this[Keys.feedInterests].orEmpty(),
            feedRssUrl = this[Keys.feedRssUrl].orEmpty(),
            feedCover = this[Keys.feedCover],
            feedBio = this[Keys.feedBio].orEmpty(),
            userName = this[Keys.userName] ?: d.userName,
            historySize = this[Keys.historySize] ?: d.historySize,
            wallpaper = this[Keys.wallpaper],
            glassMode = this[Keys.glassMode]?.let { runCatching { GlassMode.valueOf(it) }.getOrNull() } ?: d.glassMode,
            wallpaperDark = this[Keys.wallpaperDark]?.toBooleanStrictOrNull(),
            wallpaperHue = this[Keys.wallpaperHue],
            wallpaperChroma = this[Keys.wallpaperChroma],
            wallpaperTrough = this[Keys.wallpaperTrough],
            wallpaperPeak = this[Keys.wallpaperPeak],
            glassTuning = decodeTuning(this[Keys.glassTuning]),
            tools = this[Keys.tools]?.let(::decodeTools) ?: d.tools,
            weatherCity = this[Keys.weatherCity] ?: d.weatherCity,
            userAvatar = this[Keys.userAvatar],
            chatAvatars = this[Keys.chatAvatars] ?: d.chatAvatars,
            avatarEachMessage = this[Keys.avatarEachMessage] ?: d.avatarEachMessage,
            chatTextSize = this[Keys.chatTextSize] ?: d.chatTextSize,
            expandVoiceText = this[Keys.expandVoiceText] ?: d.expandVoiceText,
            patVerb = this[Keys.patVerb]?.takeIf { it.isNotBlank() } ?: d.patVerb,
            patSuffix = this[Keys.patSuffix] ?: d.patSuffix,
            patBuzz = this[Keys.patBuzz] ?: d.patBuzz,
            myBubble = this[Keys.myBubble],
            myBubbleTheme = this[Keys.myBubbleTheme] ?: "glass",
            bubblePaddingX = (this[Keys.bubblePaddingX] ?: 10).coerceIn(6, 24),
            bubblePaddingY = (this[Keys.bubblePaddingY] ?: 6).coerceIn(4, 16),
            bubblePresets = runCatching { tuningJson.decodeFromString<List<BubblePreset>>(this[Keys.bubblePresets] ?: "[]").distinctBy { it.id }.take(50).map { it.normalized() } }.getOrDefault(emptyList()),
            bubbleDecoration = runCatching { tuningJson.decodeFromString<BubbleDecoration>(this[Keys.bubbleDecoration] ?: "{}").normalized() }.getOrDefault(BubbleDecoration()),
            bubbleBackgrounds = runCatching { tuningJson.decodeFromString<Map<String, BubbleBackground>>(this[Keys.bubbleBackgrounds] ?: "{}").mapValues { it.value.normalized() } }.getOrDefault(emptyMap()),
            taBubbleThemes = decodeVoices(this[Keys.taBubbleThemes]),
            notificationsAsked = this[Keys.notificationsAsked] ?: d.notificationsAsked,
            letterReply = ReplyWhen.of(this[Keys.letterReply]),
            letterEveryDays = this[Keys.letterEveryDays] ?: d.letterEveryDays,
            voiceBaseUrl = this[Keys.voiceBaseUrl] ?: d.voiceBaseUrl,
            voiceModel = this[Keys.voiceModel] ?: d.voiceModel,
            speechEngine = speechEngine,
            speechVoices = speechVoices,
            minimaxGlobal = this[Keys.minimaxGlobal] ?: d.minimaxGlobal,
            speechBaseUrl = this[Keys.speechBaseUrl] ?: d.speechBaseUrl,
            speechModel = this[Keys.speechModel] ?: d.speechModel,
            speechVoice = this[Keys.speechVoice] ?: d.speechVoice,
            elevenVoice = this[Keys.elevenVoice] ?: d.elevenVoice,
            elevenModel = this[Keys.elevenModel] ?: d.elevenModel,
            earVoice = this[Keys.earVoice] ?: d.earVoice,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.userName] = next.userName
            prefs[Keys.historySize] = next.historySize
            prefs[Keys.glassMode] = next.glassMode.name
            if (next.wallpaper != null) prefs[Keys.wallpaper] = next.wallpaper else prefs.remove(Keys.wallpaper)
            if (next.wallpaperDark != null) prefs[Keys.wallpaperDark] = next.wallpaperDark.toString() else prefs.remove(Keys.wallpaperDark)
            if (next.wallpaperHue != null) prefs[Keys.wallpaperHue] = next.wallpaperHue else prefs.remove(Keys.wallpaperHue)
            if (next.wallpaperChroma != null) prefs[Keys.wallpaperChroma] = next.wallpaperChroma else prefs.remove(Keys.wallpaperChroma)
            if (next.wallpaperTrough != null) prefs[Keys.wallpaperTrough] = next.wallpaperTrough else prefs.remove(Keys.wallpaperTrough)
            if (next.wallpaperPeak != null) prefs[Keys.wallpaperPeak] = next.wallpaperPeak else prefs.remove(Keys.wallpaperPeak)
            if (next.glassTuning.isNotEmpty()) prefs[Keys.glassTuning] = encodeTuning(next.glassTuning) else prefs.remove(Keys.glassTuning)
            // Always written, even when empty: "none" must not read back as "never set".
            prefs[Keys.tools] = encodeTools(next.tools)
            prefs[Keys.weatherCity] = next.weatherCity
            if (next.userAvatar != null) prefs[Keys.userAvatar] = next.userAvatar else prefs.remove(Keys.userAvatar)
            prefs[Keys.chatAvatars] = next.chatAvatars
            prefs[Keys.avatarEachMessage] = next.avatarEachMessage
            prefs[Keys.chatTextSize] = next.chatTextSize
            prefs[Keys.myBubbleTheme] = next.myBubbleTheme
            prefs[Keys.bubblePaddingX] = next.bubblePaddingX.coerceIn(6, 24)
            prefs[Keys.bubblePaddingY] = next.bubblePaddingY.coerceIn(4, 16)
            prefs[Keys.bubblePresets] = tuningJson.encodeToString(next.bubblePresets.distinctBy { it.id }.take(50).map { it.normalized() })
            prefs[Keys.bubbleDecoration] = tuningJson.encodeToString(next.bubbleDecoration.normalized())
            prefs[Keys.bubbleBackgrounds] = tuningJson.encodeToString(next.bubbleBackgrounds.mapValues { it.value.normalized() })
            prefs[Keys.taBubbleThemes] = encodeVoices(next.taBubbleThemes)
            prefs[Keys.expandVoiceText] = next.expandVoiceText
            prefs[Keys.patVerb] = next.patVerb
            prefs[Keys.patSuffix] = next.patSuffix
            prefs[Keys.patBuzz] = next.patBuzz
            prefs[Keys.feedInterests] = next.feedInterests.take(300)
            prefs[Keys.feedRssUrl] = next.feedRssUrl.take(2000)
            if (next.feedCover != null) prefs[Keys.feedCover] = next.feedCover else prefs.remove(Keys.feedCover)
            prefs[Keys.feedBio] = next.feedBio.take(120)
            if (next.myBubble != null) prefs[Keys.myBubble] = next.myBubble else prefs.remove(Keys.myBubble)
            prefs[Keys.notificationsAsked] = next.notificationsAsked
            prefs[Keys.letterReply] = next.letterReply.key
            prefs[Keys.letterEveryDays] = next.letterEveryDays
            prefs[Keys.voiceBaseUrl] = next.voiceBaseUrl
            prefs[Keys.voiceModel] = next.voiceModel
            prefs[Keys.speechEngine] = next.speechEngine
            prefs[Keys.speechVoices] = encodeVoices(next.speechVoices)
            prefs[Keys.minimaxGlobal] = next.minimaxGlobal
            prefs[Keys.speechBaseUrl] = next.speechBaseUrl
            prefs[Keys.speechModel] = next.speechModel
            prefs[Keys.speechVoice] = next.speechVoice
            prefs[Keys.elevenVoice] = next.elevenVoice
            prefs[Keys.elevenModel] = next.elevenModel
            prefs[Keys.earVoice] = next.earVoice
            // The MCP tool that could make the voice, gone with it.
            for (name in listOf("speech_mcp_server", "speech_mcp_tool", "speech_mcp_text_param", "speech_mcp_args")) {
                prefs.remove(stringPreferencesKey(name))
            }
        }
    }

    suspend fun legacyTa(): LegacyTa {
        val p = context.settingsStore.data.first()
        return LegacyTa(
            name = p[Keys.aiName].orEmpty(),
            persona = p[Keys.persona].orEmpty(),
            baseUrl = p[Keys.apiBaseUrl] ?: ApiPresets.DeepSeek.baseUrl,
            model = p[Keys.apiModel] ?: ApiPresets.DeepSeek.defaultModel,
            avatar = p[Keys.aiAvatar],
            avatarEmoji = p[Keys.aiAvatarEmoji],
            knownSince = p[Keys.knownSince],
        )
    }

    /** The TA being talked to; null before there has been a choice (the first one then). */
    val currentCompanion: Flow<Long?> = context.settingsStore.data.map { it[Keys.currentCompanion] }

    suspend fun setCurrentCompanion(id: Long) {
        context.settingsStore.edit { it[Keys.currentCompanion] = id }
    }

    suspend fun setGlassTuning(part: GlassPart, tuning: GlassTuning?) = update {
        it.copy(glassTuning = if (tuning == null) it.glassTuning - part else it.glassTuning + (part to tuning))
    }

    val currentConversation: Flow<Long?> =
        context.settingsStore.data.map { it[Keys.currentConversation]?.toLongOrNull() }

    suspend fun setCurrentConversation(id: Long?) {
        context.settingsStore.edit {
            if (id == null) it.remove(Keys.currentConversation) else it[Keys.currentConversation] = id.toString()
        }
    }

    /** The last day (an epoch day, counted as RoutineRules.dayOf counts them) whose [greeting] is over, said or not. */
    suspend fun greetedOn(greeting: Greeting): Long? = context.settingsStore.data.first()[greetedKey(greeting)]

    suspend fun setGreetedOn(greeting: Greeting, day: Long) {
        context.settingsStore.edit { it[greetedKey(greeting)] = day }
    }

    private fun greetedKey(greeting: Greeting) = if (greeting == Greeting.Morning) Keys.morningGreeted else Keys.nightGreeted
}

private val tuningJson = Json { ignoreUnknownKeys = true }

/** The voice picked on each service, as JSON: {"minimax": "female-shaonv", …}. */
internal fun encodeVoices(voices: Map<String, String>): String = tuningJson.encodeToString(voices)

internal fun decodeVoices(raw: String?): Map<String, String> =
    if (raw.isNullOrBlank()) emptyMap() else runCatching { tuningJson.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())

// Stored by enum name, so a part removed in some later version is skipped, not an error.
internal fun encodeTuning(map: Map<GlassPart, GlassTuning>): String =
    tuningJson.encodeToString(map.mapKeys { it.key.name })

/**
 * Every group's choice is written out, on or off. Written as a list of the groups that are
 * on, a group added in a later version would read as switched off; this way it gets its
 * own default until the person decides.
 */
internal fun encodeTools(tools: Set<ToolGroup>): String =
    ToolGroup.entries.joinToString(",") { "${it.name}:${if (it in tools) "on" else "off"}" }

/** 0.3.0 wrote only the names of the groups that were on, and knew only these three. */
private val GROUPS_IN_0_3 = setOf(ToolGroup.Todos, ToolGroup.Diary, ToolGroup.Weather)

/** By name, like the tuning: a group removed in a later version is skipped. */
internal fun decodeTools(raw: String): Set<ToolGroup> {
    fun group(name: String) = ToolGroup.entries.firstOrNull { it.name == name.trim() }
    val defaults = AppSettings().tools
    if (':' !in raw) {
        val on = raw.split(',').mapNotNull(::group).toSet()
        return on + (defaults - GROUPS_IN_0_3)
    }
    val chosen = raw.split(',').mapNotNull { part ->
        val bits = part.split(':')
        if (bits.size != 2) return@mapNotNull null
        group(bits[0])?.let { it to (bits[1].trim() == "on") }
    }.toMap()
    return ToolGroup.entries.filter { chosen[it] ?: (it in defaults) }.toSet()
}

internal fun decodeTuning(raw: String?): Map<GlassPart, GlassTuning> {
    if (raw.isNullOrBlank()) return emptyMap()
    val byName = runCatching { tuningJson.decodeFromString<Map<String, GlassTuning>>(raw) }.getOrDefault(emptyMap())
    return byName.mapNotNull { (name, t) -> GlassPart.entries.firstOrNull { it.name == name }?.let { it to t } }.toMap()
}
