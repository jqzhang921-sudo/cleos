package com.cleo.cleos.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.ConversationEntity
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.ai.ReplyWhen
import com.cleo.cleos.ai.Speech
import com.cleo.cleos.data.db.FavoriteEntity
import com.cleo.cleos.data.db.LetterEntity
import com.cleo.cleos.data.db.MemoryEntity
import com.cleo.cleos.data.db.LoreEntity
import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.data.db.StickerEntity
import com.cleo.cleos.data.db.TodoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The settings in a backup. apiBaseUrl through knownSince described the one TA of before
 * there could be several; a newer backup fills them from the first TA and restores all of
 * them from [BackupFile.companions].
 */
@Serializable
data class BackupSettings(
    val apiBaseUrl: String,
    val apiModel: String,
    val aiName: String,
    val userName: String,
    val persona: String,
    val historySize: Int,
    val wallpaper: String?,
    val glassMode: String,
    val wallpaperDark: Boolean?,
    val wallpaperHue: Float?,
    val wallpaperChroma: Float?,
    val wallpaperTrough: Float? = null,
    val wallpaperPeak: Float? = null,
    /** JSON as SettingsRepository stores it; absent in backups from before the glass lab could apply. */
    val glassTuning: String? = null,
    /** Tool groups by name, comma-separated; absent in backups from before tools. */
    val tools: String? = null,
    val weatherCity: String = "",
    /** File names under images/; absent in backups from before the home page. */
    val userAvatar: String? = null,
    val aiAvatar: String? = null,
    val aiAvatarEmoji: String? = null,
    val chatAvatars: Boolean = true,
    val avatarEachMessage: Boolean = false,
    val myBubble: Int? = null,
    val myBubbleTheme: String = "glass",
    val bubblePaddingX: Int = 10,
    val bubblePaddingY: Int = 6,
    val taBubbleThemes: Map<String, String> = emptyMap(),
    val knownSince: Long? = null,
    /** Absent from backups made before these could be set: the defaults then. */
    val letterReply: String? = null,
    val letterEveryDays: Int = 5,
    val voiceBaseUrl: String = "",
    val voiceModel: String = "",
    /** "api" for any OpenAI-shaped service in backups from before each had its own place (Speech.migrate). */
    val speechEngine: String = "",
    val speechVoices: Map<String, String> = emptyMap(),
    val minimaxGlobal: Boolean = false,
    val speechBaseUrl: String = "",
    val speechModel: String = "",
    val speechVoice: String = "",
    val elevenVoice: String = "",
    val elevenModel: String = "",
    val earVoice: Boolean = true,
    val expandVoiceText: Boolean = false,
)

/** The backup format: one zip, `backup.json` plus the pictures under `images/`. */
@Serializable
data class BackupFile(
    // No defaults on decode: a file without these is not one of ours.
    val format: String,
    val version: Int,
    val exportedAt: Long,
    val settings: BackupSettings,
    val conversations: List<ConversationEntity>,
    val messages: List<MessageEntity>,
    val diary: List<DiaryEntryEntity>,
    val todos: List<TodoEntity>,
    /** Absent in backups from before there could be several TAs. */
    val companions: List<CompanionEntity> = emptyList(),
    /** Absent in backups from before there were letters. */
    val letters: List<LetterEntity> = emptyList(),
    /** Absent in backups from before TAs kept memories. */
    val memories: List<MemoryEntity> = emptyList(),
    /** Absent in backups from before there were stickers; their pictures are under `images/` with the rest. */
    val stickers: List<StickerEntity> = emptyList(),
    val favorites: List<FavoriteEntity> = emptyList(),
    /** Absent in backups from before a TA had 设定 (a world book brought over from elsewhere). */
    val lore: List<LoreEntity> = emptyList(),
) {
    companion object {
        const val FORMAT = "cleos-backup"
        const val VERSION = 1
    }
}

data class BackupSummary(
    val tas: Int,
    val conversations: Int,
    val messages: Int,
    val diary: Int,
    val letters: Int,
    val todos: Int,
    val images: Int,
    val voices: Int = 0,
    val stickers: Int = 0,
    val favorites: Int = 0,
    val lore: Int = 0,
) {
    override fun toString() =
        "$tas 个 TA、$conversations 段对话（$messages 条消息）、$diary 篇日记、$letters 封信、$todos 条待办、$images 张图" +
            (if (voices > 0) "、$voices 段语音" else "") +
            (if (stickers > 0) "、$stickers 个表情包" else "") +
            (if (favorites > 0) "、$favorites 条收藏" else "") +
            (if (lore > 0) "、$lore 条设定" else "")
}

/** Recordings are named voice_…, among the pictures (VoiceRecorder). */
private const val VOICE_PREFIX = "voice_"

class BackupException(message: String) : Exception(message)

/**
 * Export and restore. The API key is never written out: it only exists encrypted with a
 * key that cannot leave this phone, and a backup file travels (chat apps, cloud drives).
 *
 * Restore replaces everything, so it is careful about order:
 *  1. read and check the whole file before touching anything;
 *  2. snapshot what is there now (`before-restore.zip`), so a wrong file can be undone;
 *  3. copy pictures in, then swap the database contents in one transaction. If any row
 *     is bad the transaction rolls back and the old data is still there.
 */
class BackupService(
    context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val images: ImageStore,
) {
    private val resolver = context.contentResolver
    private val cacheDir = context.cacheDir
    private val snapshot = File(context.filesDir, "backups/before-restore.zip")
    // encodeDefaults: format and version have default values, and without this they are
    // silently left out of the file, which makes the "is this our backup, which version"
    // check on restore pass for anything.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val hasSnapshot: Boolean get() = snapshot.exists()

    suspend fun export(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val out = resolver.openOutputStream(uri) ?: throw BackupException("打不开要保存的位置")
        out.use { write(it) }
    }

    suspend fun restore(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri) ?: throw BackupException("打不开这个文件")
        input.use { restoreFrom(it, takeSnapshot = true) }
    }

    /** Puts back what was there before the last restore. */
    suspend fun undoRestore(): BackupSummary = withContext(Dispatchers.IO) {
        if (!snapshot.exists()) throw BackupException("没有可以撤销的恢复")
        val summary = snapshot.inputStream().use { restoreFrom(it, takeSnapshot = false) }
        snapshot.delete()
        summary
    }

    private suspend fun write(raw: OutputStream): BackupSummary {
        val s = settings.current()
        val companions = db.companions().all()
        val lead = companions.firstOrNull()
        val data = BackupFile(
            format = BackupFile.FORMAT,
            version = BackupFile.VERSION,
            exportedAt = System.currentTimeMillis(),
            settings = BackupSettings(
                apiBaseUrl = lead?.apiBaseUrl ?: ApiPresets.DeepSeek.baseUrl,
                apiModel = lead?.apiModel ?: ApiPresets.DeepSeek.defaultModel,
                aiName = lead?.name.orEmpty(),
                userName = s.userName,
                persona = lead?.persona.orEmpty(),
                historySize = s.historySize,
                wallpaper = s.wallpaper,
                glassMode = s.glassMode.name,
                wallpaperDark = s.wallpaperDark,
                wallpaperHue = s.wallpaperHue,
                wallpaperChroma = s.wallpaperChroma,
                wallpaperTrough = s.wallpaperTrough,
                wallpaperPeak = s.wallpaperPeak,
                glassTuning = s.glassTuning.takeIf { it.isNotEmpty() }?.let { encodeTuning(it) },
                tools = encodeTools(s.tools),
                weatherCity = s.weatherCity,
                userAvatar = s.userAvatar,
                aiAvatar = lead?.avatar,
                aiAvatarEmoji = lead?.avatarEmoji,
                chatAvatars = s.chatAvatars,
                avatarEachMessage = s.avatarEachMessage,
                myBubble = s.myBubble,
                myBubbleTheme = s.myBubbleTheme,
                bubblePaddingX = s.bubblePaddingX,
                bubblePaddingY = s.bubblePaddingY,
                taBubbleThemes = s.taBubbleThemes,
                letterReply = s.letterReply.key,
                letterEveryDays = s.letterEveryDays,
                voiceBaseUrl = s.voiceBaseUrl,
                voiceModel = s.voiceModel,
                speechEngine = s.speechEngine,
                speechVoices = s.speechVoices,
                minimaxGlobal = s.minimaxGlobal,
                speechBaseUrl = s.speechBaseUrl,
                speechModel = s.speechModel,
                speechVoice = s.speechVoice,
                elevenVoice = s.elevenVoice,
                elevenModel = s.elevenModel,
                earVoice = s.earVoice,
                expandVoiceText = s.expandVoiceText,
                knownSince = lead?.knownSince,
            ),
            conversations = db.conversations().all(),
            messages = db.messages().all(),
            diary = db.diary().all(),
            todos = db.todos().all(),
            companions = companions,
            letters = db.letters().all(),
            memories = db.memories().all(),
            stickers = db.stickers().all(),
            favorites = db.favorites().all(),
            lore = db.lore().all(),
        )
        val stickerFiles = data.stickers.map { it.file }.toSet()
        val favoriteFiles = data.favorites.flatMap { FavoriteContent.files(FavoriteContent.decode(it.parts)) }
        val pictures = (favoriteFiles + data.diary.flatMap { e -> DiaryBlocks.images(DiaryBlocks.decode(e.blocks)).map { it.file } } +
            data.messages.flatMap { m -> MessageImages.decode(m.images).map { it.file } } +
            // Recordings live with the pictures and travel the same way.
            data.messages.mapNotNull { m -> MessageAudios.decode(m.audio)?.file } +
            companions.mapNotNull { it.avatar } +
            stickerFiles +
            listOfNotNull(s.wallpaper, s.userAvatar)).toSet()

        var written = 0
        var voices = 0
        var stickers = 0
        ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_NAME))
            zip.write(json.encodeToString(data).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (name in pictures) {
                val f = images.file(name)
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry("images/$name"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                when {
                    name.startsWith(VOICE_PREFIX) -> voices++
                    name in stickerFiles -> stickers++
                    else -> written++
                }
            }
        }
        return BackupSummary(
            companions.size, data.conversations.size, data.messages.size, data.diary.size, data.letters.size, data.todos.size,
            written, voices, stickers, data.favorites.size, data.lore.size,
        )
    }

    private suspend fun restoreFrom(input: InputStream, takeSnapshot: Boolean): BackupSummary {
        val staging = File(cacheDir, "restore-${System.nanoTime()}").apply { mkdirs() }
        try {
            var data: BackupFile? = null
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.name == JSON_NAME -> {
                            data = runCatching { json.decodeFromString<BackupFile>(zip.readBytes().decodeToString()) }
                                .getOrElse { throw BackupException("备份文件坏了，读不出来") }
                        }
                        entry.name.startsWith("images/") && !entry.isDirectory -> {
                            // Only the bare file name is used: a crafted archive with
                            // "images/../../somewhere" must not write outside the staging dir.
                            val name = File(entry.name).name
                            if (name.isNotBlank() && !name.startsWith(".")) {
                                File(staging, name).outputStream().use { zip.copyTo(it) }
                            }
                        }
                    }
                }
            }
            val d = data ?: throw BackupException("这不是 Cleos 的备份文件")
            if (d.format != BackupFile.FORMAT) throw BackupException("这不是 Cleos 的备份文件")
            if (d.version > BackupFile.VERSION) throw BackupException("这份备份来自更新版本的 Cleos，先更新 App 再恢复")

            if (takeSnapshot) {
                snapshot.parentFile?.mkdirs()
                val tmp = File(snapshot.path + ".tmp")
                tmp.outputStream().use { write(it) }
                tmp.renameTo(snapshot)
            }

            val pictures = staging.listFiles().orEmpty()
            for (f in pictures) {
                val dest = images.file(f.name)
                if (!dest.exists()) f.copyTo(dest)
            }
            val bs = d.settings
            fun picture(name: String?) = name?.takeIf { images.file(it).exists() }
            // A backup from before there could be several TAs has one, described in its settings.
            val companions = d.companions.ifEmpty {
                listOf(
                    CompanionEntity(
                        id = Companions.FIRST,
                        name = bs.aiName,
                        persona = bs.persona,
                        apiBaseUrl = bs.apiBaseUrl,
                        apiModel = bs.apiModel,
                        avatar = bs.aiAvatar,
                        avatarEmoji = bs.aiAvatarEmoji,
                        knownSince = bs.knownSince,
                        createdAt = d.exportedAt,
                    ),
                )
            }.map { it.copy(avatar = picture(it.avatar)) }
            // TA entries in such a backup carry no owner: they were all TA 1's.
            val diary = d.diary.map {
                if (it.author == DiaryEntryEntity.AUTHOR_AI && it.companionId == null) it.copy(companionId = Companions.FIRST) else it
            }
            // A sticker is its picture: one whose file didn't come along would be a name drawn as nothing.
            val stickers = d.stickers.filter { images.file(it.file).exists() }
            db.withTransaction {
                // What TAs noted to come back to, and what came of it: not in backups, and about
                // conversations that are about to go.
                db.favorites().clear()
                db.later().clear()
                db.wakes().clear()
                db.messages().clear()
                db.conversations().clear()
                db.diary().clear()
                db.todos().clear()
                db.letters().clear()
                db.memories().clear()
                db.lore().clear()
                db.stickers().clear()
                db.companions().clear()
                db.companions().insertAll(companions)
                db.conversations().insertAll(d.conversations)
                db.messages().insertAll(d.messages)
                db.diary().insertAll(diary)
                db.todos().insertAll(d.todos)
                db.letters().insertAll(d.letters)
                db.memories().insertAll(d.memories)
                db.lore().insertAll(d.lore)
                db.stickers().insertAll(stickers)
                db.favorites().insertAll(d.favorites)
            }
            // A backup from before each voice service had its own place says "api" for all of them.
            val (speechEngine, speechVoices) = Speech.migrate(bs.speechEngine, bs.speechBaseUrl, bs.speechVoice, bs.speechVoices)
            settings.update {
                it.copy(
                    userName = bs.userName,
                    historySize = bs.historySize,
                    wallpaper = bs.wallpaper?.takeIf { name -> images.file(name).exists() },
                    glassMode = runCatching { GlassMode.valueOf(bs.glassMode) }.getOrDefault(GlassMode.Auto),
                    wallpaperDark = bs.wallpaperDark,
                    wallpaperHue = bs.wallpaperHue,
                    wallpaperChroma = bs.wallpaperChroma,
                    wallpaperTrough = bs.wallpaperTrough,
                    wallpaperPeak = bs.wallpaperPeak,
                    glassTuning = decodeTuning(bs.glassTuning),
                    tools = bs.tools?.let(::decodeTools) ?: AppSettings().tools,
                    weatherCity = bs.weatherCity,
                    userAvatar = picture(bs.userAvatar),
                    chatAvatars = bs.chatAvatars,
                    avatarEachMessage = bs.avatarEachMessage,
                    myBubble = bs.myBubble,
                    myBubbleTheme = bs.myBubbleTheme,
                    bubblePaddingX = bs.bubblePaddingX.coerceIn(6, 24),
                    bubblePaddingY = bs.bubblePaddingY.coerceIn(4, 16),
                    taBubbleThemes = bs.taBubbleThemes.filterKeys { key -> companions.any { ta -> ta.id.toString() == key } },
                    letterReply = ReplyWhen.of(bs.letterReply),
                    letterEveryDays = bs.letterEveryDays,
                    voiceBaseUrl = bs.voiceBaseUrl,
                    voiceModel = bs.voiceModel,
                    speechEngine = speechEngine,
                    speechVoices = speechVoices,
                    minimaxGlobal = bs.minimaxGlobal,
                    speechBaseUrl = bs.speechBaseUrl,
                    speechModel = bs.speechModel,
                    speechVoice = bs.speechVoice,
                    elevenVoice = bs.elevenVoice,
                    elevenModel = bs.elevenModel,
                    earVoice = bs.earVoice,
                    expandVoiceText = bs.expandVoiceText,
                )
            }
            settings.setCurrentCompanion(companions.first().id)
            settings.setCurrentConversation(null)
            return BackupSummary(
                companions.size, d.conversations.size, d.messages.size, d.diary.size, d.letters.size, d.todos.size,
                pictures.count { !it.name.startsWith(VOICE_PREFIX) && it.name !in stickers.map { s -> s.file }.toSet() },
                voices = pictures.count { it.name.startsWith(VOICE_PREFIX) },
                stickers = stickers.size, favorites = d.favorites.size, lore = d.lore.size,
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    private companion object {
        const val JSON_NAME = "backup.json"
    }
}
