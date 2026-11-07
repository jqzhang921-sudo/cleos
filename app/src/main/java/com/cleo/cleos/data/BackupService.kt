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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
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
    val feedInterests: String = "",
    val feedRssUrl: String = "",
    val feedNewsSources: String = "",
    val feedCover: String? = null,
    val feedBio: String = "",
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
    val photoLibrary: String? = null,
    val feedPhotoTas: List<Long> = emptyList(),
    val selectedGroupId: Long? = null,
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
    val messageSpacing: Int = BubbleSpacing.DEFAULT,
    val bubblePresets: List<BubblePreset> = emptyList(),
    val bubbleDecoration: BubbleDecoration = BubbleDecoration(),
    val bubbleBackgrounds: Map<String, BubbleBackground> = emptyMap(),
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
    val showThought: Boolean = true,
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
    val focusSessions: List<com.cleo.cleos.focus.FocusSession> = emptyList(),
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
    val feedPosts: List<com.cleo.cleos.data.db.FeedPostEntity> = emptyList(),
    val groups: List<com.cleo.cleos.data.db.ChatGroupEntity> = emptyList(),
    val groupMembers: List<com.cleo.cleos.data.db.GroupMemberEntity> = emptyList(),
    val groupTurns: List<com.cleo.cleos.data.db.GroupTurnEntity> = emptyList(),
    val groupMessages: List<com.cleo.cleos.data.db.GroupMessageEntity> = emptyList(),
    val groupMemories: List<com.cleo.cleos.data.db.GroupMemoryEntity> = emptyList(),
    val worldArticles: List<com.cleo.cleos.data.db.WorldArticleEntity> = emptyList(),
    val worldNotes: List<com.cleo.cleos.data.db.WorldNoteEntity> = emptyList(),
    val worldWritings: List<com.cleo.cleos.data.db.WorldWritingEntity> = emptyList(),
    val worldSerials: List<com.cleo.cleos.data.db.WorldSerialEntity> = emptyList(),
    val worldSerialChapters: List<com.cleo.cleos.data.db.WorldSerialChapterEntity> = emptyList(),
    val worldJourneyTopics: List<com.cleo.cleos.data.db.WorldJourneyTopicEntity> = emptyList(),
    val worldJourneyItems: List<com.cleo.cleos.data.db.WorldJourneyItemEntity> = emptyList(),
    val worldWishes: List<com.cleo.cleos.data.db.WorldWishEntity> = emptyList(),
    val worldBookmarks: List<com.cleo.cleos.data.db.WorldBookmarkEntity> = emptyList(),
    val worldShelf: List<com.cleo.cleos.data.db.WorldShelfEntity> = emptyList(),
    val worldPictures: List<com.cleo.cleos.data.db.WorldPictureEntity> = emptyList(),
    val worldPictureLooks: List<com.cleo.cleos.data.db.WorldPictureLookEntity> = emptyList(),
    val worldAlbumPhotos: List<com.cleo.cleos.data.db.WorldAlbumPhotoEntity> = emptyList(),
    val worldAlbumLooks: List<com.cleo.cleos.data.db.WorldAlbumLookEntity> = emptyList(),
) {
    companion object {
        const val FORMAT = "cleos-backup"
        // Older readers must not re-activate former members or drop shared conversations.
        const val VERSION = 29
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
    val feedPosts: Int = 0,
    val groups: Int = 0,
    val groupMessages: Int = 0,
    val groupMemories: Int = 0,
    val missingGroupDraftImages: Int = 0,
    val missingGroupDraftVoices: Int = 0,
    val worldArticles: Int = 0,
    val worldNotes: Int = 0,
    val worldWritings: Int = 0,
    val worldWishes: Int = 0,
    val worldOutside: Int = 0,
    val worldBookmarks: Int = 0,
    val worldShelf: Int = 0,
    val worldPictures: Int = 0,
    val worldPictureLooks: Int = 0,
    val worldAlbumPhotos: Int = 0,
    val worldAlbumLooks: Int = 0,
    val worldJourneyTopics: Int = 0,
    val worldSerials: Int = 0,
) {
    override fun toString() =
        "$tas 个 TA、$conversations 段对话（$messages 条消息）、$diary 篇日记、$letters 封信、$todos 条待办、$images 张图" +
            (if (voices > 0) "、$voices 段语音" else "") +
            (if (stickers > 0) "、$stickers 个表情包" else "") +
            (if (favorites > 0) "、$favorites 条收藏" else "") +
            (if (lore > 0) "、$lore 条设定" else "") +
            (if (feedPosts > 0) "、$feedPosts 条动态" else "") +
            (if (groups > 0) "、$groups 个群聊（$groupMessages 条群消息）" else "") +
            (if (groupMemories > 0) "、$groupMemories 条群内记录" else "") +
            (if (worldArticles > 0) "、$worldArticles 篇书房文章" else "") +
            (if (worldOutside > 0) "、$worldOutside 条窗外摘要" else "") +
            (if (worldNotes > 0) "、$worldNotes 条阅读笔记" else "") +
            (if (worldWritings > 0) "、$worldWritings 篇自由随笔" else "") +
            (if (worldSerials > 0) "、$worldSerials 本连载" else "") +
            (if (worldJourneyTopics > 0) "、$worldJourneyTopics 份图文专题" else "") +
            (if (worldWishes > 0) "、$worldWishes 条想读请求" else "") +
            (if (worldBookmarks > 0) "、$worldBookmarks 枚书签" else "") +
            (if (worldShelf > 0) "、$worldShelf 条读物整理信息" else "") +
            (if (worldPictures > 0) "、$worldPictures 张窗外配图" else "") +
            (if (worldAlbumPhotos > 0) "、$worldAlbumPhotos 张私人相册照片" else "") +
            (if (worldAlbumLooks > 0) "、$worldAlbumLooks 条相册感想" else "") +
            (if (worldPictureLooks > 0) "、$worldPictureLooks 条看图记录" else "") +
            (if (missingGroupDraftImages > 0) "；有 $missingGroupDraftImages 张未发送的群草稿图缺失，请重新选择" else "") +
            (if (missingGroupDraftVoices > 0) "；有 $missingGroupDraftVoices 段群语音草稿文件缺失，已有转写保留为文字草稿，没有转写的请重新录制" else "")
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
        // Replacing a shorter archive must not leave the old ZIP directory at the end.
        val out = resolver.openOutputStream(uri, "wt") ?: throw BackupException("打不开要保存的位置")
        out.use { write(it) }
    }

    /** Same backup format, kept only in private cache for an explicitly approved transfer. */
    suspend fun withTransferBackup(action:(File)->Unit) = withContext(Dispatchers.IO) {
        val directory=File(cacheDir,"desktop-transfer").apply { mkdirs() }
        directory.listFiles()?.filter { it.isFile && it.name.startsWith("outgoing-") && it.name.endsWith(".zip") && System.currentTimeMillis()-it.lastModified()>86400000 }?.forEach { it.delete() }
        val file=File.createTempFile("outgoing-",".zip",directory)
        val task=currentCoroutineContext()
        try {
            file.outputStream().use { raw ->
                val bounded=object:OutputStream() {
                    var size=0L
                    private fun count(n:Int) { task.ensureActive();size+=n;if(size>com.cleo.cleos.transfer.MAX_TRANSFER_BYTES)throw BackupException("备份超过 2 GB，请使用文件导出后分开保管。") }
                    override fun write(value:Int){count(1);raw.write(value)}
                    override fun write(bytes:ByteArray,offset:Int,length:Int){count(length);raw.write(bytes,offset,length)}
                    override fun flush(){raw.flush()}
                }
                write(bounded)
            }
            task.ensureActive();action(file)
        } finally { file.delete() }
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
        MessageStyle.validateBackup(companions)
        val lead = companions.firstOrNull()
        val data = db.withTransaction { BackupFile(
            format = BackupFile.FORMAT,
            version = BackupFile.VERSION,
            exportedAt = System.currentTimeMillis(),
            settings = BackupSettings(
                apiBaseUrl = lead?.apiBaseUrl ?: ApiPresets.DeepSeek.baseUrl,
                selectedGroupId = settings.currentGroup.first(),
                apiModel = lead?.apiModel ?: ApiPresets.DeepSeek.defaultModel,
                aiName = lead?.name.orEmpty(),
                userName = s.userName,
                feedInterests = s.feedInterests,
                feedRssUrl = s.feedRssUrl,
                feedNewsSources = s.feedNewsSources,
                feedCover = s.feedCover,
                feedBio = s.feedBio,
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
                photoLibrary = s.photoLibrary.name,
                feedPhotoTas = s.feedPhotoTas.sorted(),
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
                messageSpacing = s.messageSpacing,
                bubblePresets = s.bubblePresets,
                bubbleDecoration = s.bubbleDecoration,
                bubbleBackgrounds = s.bubbleBackgrounds,
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
                showThought = s.showThought,
                knownSince = lead?.knownSince,
            ),
            conversations = db.conversations().all(),
            messages = db.messages().all(),
            diary = db.diary().all(),
            todos = db.todos().all(),
            focusSessions = db.focus().all().map { com.cleo.cleos.focus.FocusTimer.forBackup(it,
                android.os.SystemClock.elapsedRealtime(),
                android.provider.Settings.Global.getInt(resolver, android.provider.Settings.Global.BOOT_COUNT, -1),
                System.currentTimeMillis()) },
            companions = companions,
            letters = db.letters().all(),
            memories = db.memories().all(),
            stickers = db.stickers().all(),
            favorites = db.favorites().all(),
            lore = db.lore().all(),
            feedPosts = db.feed().all(),
            groups = db.groups().allGroups(),
            groupMembers = db.groups().allMembers(),
            groupTurns = db.groups().allTurns(),
            groupMessages = db.groups().allMessages(),
            groupMemories = db.groups().allMemories(),
            worldArticles = db.world().allArticles(),
            worldNotes = db.world().allNotes(),
            worldWritings = db.world().allWritings(),
            worldSerials = db.worldSerials().all(), worldSerialChapters = db.worldSerials().allChapters(),
            worldJourneyTopics = db.world().allJourneyTopics(), worldJourneyItems = db.world().allJourneyItems(),
            worldWishes = db.world().allWishes(),
            worldBookmarks = db.world().allBookmarks(),
            worldShelf = db.world().allShelf(),
            worldPictures = db.world().allPictures(),
            worldPictureLooks = db.world().allPictureLooks(),
            worldAlbumPhotos = db.worldAlbum().allPhotos(), worldAlbumLooks = db.worldAlbum().allLooks(),
        ) }
        val stickerFiles = data.stickers.map { it.file }.toSet()
        if (data.worldPictures.any { !images.validWorldPicture(images.file(it.file), it.width, it.height, it.sha256) }) throw BackupException("窗外配图文件缺失或损坏，请移除无法显示的配图后再备份")
        val favoriteFiles = data.favorites.flatMap { FavoriteContent.files(FavoriteContent.decode(it.parts)) }
        WorldWritingData.validate(data.companions.map { it.id }.toSet(), data.worldWritings, data.worldNotes, data.worldPictureLooks, data.worldAlbumLooks)
        WorldSerialData.validate(data.companions.map { it.id }.toSet(), data.worldSerials, data.worldSerialChapters, data.worldWritings)
        WorldJourneyTopics.validate(data.companions.map { it.id }.toSet(), data.worldJourneyTopics, data.worldJourneyItems, data.worldArticles)
        WorldAlbumData.validate(data.companions.map { it.id }.toSet(), data.worldAlbumPhotos, data.worldAlbumLooks, data.worldNotes, data.worldPictureLooks)
        if (data.worldAlbumPhotos.any { !images.validWorldPicture(images.file(it.file), it.width, it.height, it.sha256) }) throw BackupException("相册照片文件缺失或损坏，请整理后再备份")
        val pictures = (data.worldAlbumPhotos.map { it.file } + data.worldPictures.map { it.file } + favoriteFiles + data.diary.flatMap { e -> DiaryBlocks.images(DiaryBlocks.decode(e.blocks)).map { it.file } } +
            data.feedPosts.flatMap { MessageImages.decode(it.images).map { picture -> picture.file } } +
            data.messages.flatMap { m -> MessageImages.decode(m.images).map { it.file } } +
            data.groupMessages.flatMap { m -> MessageImages.decode(m.images).map { it.file } } +
            data.groups.flatMap { g -> MessageImages.decode(g.draftImages).map { it.file } } +
            // Recordings live with the pictures and travel the same way.
            data.messages.mapNotNull { m -> MessageAudios.decode(m.audio)?.file } +
            data.groupMessages.mapNotNull { m -> MessageAudios.decode(m.audio)?.file } +
            data.groups.mapNotNull { g -> com.cleo.cleos.ai.GroupVoiceRules.draft(g.draftAudio, g.id)?.file } +
            companions.mapNotNull { it.avatar } + companions.mapNotNull { UserAvatars.filename(it.userAvatar) } + data.groupMembers.mapNotNull { it.avatar } + data.groupMessages.mapNotNull { it.speakerAvatar } +
            stickerFiles +
            s.bubblePresets.flatMap { it.decoration.imageFiles() } + s.bubbleDecoration.components.mapNotNull { it.image } + listOfNotNull(s.wallpaper, s.feedCover, s.userAvatar, s.bubbleDecoration.faceImage, s.bubbleDecoration.starImage)).toSet()

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
            written, voices, stickers, data.favorites.size, data.lore.size, data.feedPosts.size,
            groups = data.groups.size, groupMessages = data.groupMessages.count { it.content.isNotBlank() || it.images != null },
            groupMemories = data.groupMemories.size,
            worldArticles = data.worldArticles.count { it.space == "study" }, worldNotes = data.worldNotes.size, worldWritings = data.worldWritings.size, worldSerials = data.worldSerials.size,
            worldOutside = data.worldArticles.count { it.space == "outside" },
            worldWishes = data.worldWishes.size,
            worldJourneyTopics = data.worldJourneyTopics.size,
            worldBookmarks = data.worldBookmarks.size, worldShelf = data.worldShelf.size, worldPictures = data.worldPictures.size, worldPictureLooks = data.worldPictureLooks.size, worldAlbumPhotos = data.worldAlbumPhotos.size, worldAlbumLooks = data.worldAlbumLooks.size,
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
            try {
                com.cleo.cleos.ai.TaVoices.validateBackup(d.companions)
                MessageStyle.validateBackup(d.companions)
                GroupBackupRules.validate(d.groups, d.groupMembers, d.groupTurns, d.groupMessages, d.groupMemories)
                WorldWritingData.validate(d.companions.map { it.id }.ifEmpty { listOf(Companions.FIRST) }.toSet(), d.worldWritings, d.worldNotes, d.worldPictureLooks, d.worldAlbumLooks)
                WorldSerialData.validate(d.companions.map { it.id }.ifEmpty { listOf(Companions.FIRST) }.toSet(), d.worldSerials, d.worldSerialChapters, d.worldWritings)
                WorldJourneyTopics.validate(d.companions.map { it.id }.ifEmpty { listOf(Companions.FIRST) }.toSet(), d.worldJourneyTopics, d.worldJourneyItems, d.worldArticles)
                WorldReading.validateBackup(d.companions.map { it.id }.ifEmpty { listOf(Companions.FIRST) }.toSet(), d.worldArticles, d.worldNotes)
                WorldWishes.validateBackup(d.worldWishes, d.worldArticles, d.companions.map { it.id }.ifEmpty { listOf(Companions.FIRST) }.toSet())
                WorldShelfData.validateBackup(d.companions.map { it.id }.ifEmpty { listOf(Companions.FIRST) }.toSet(), d.worldArticles, d.worldBookmarks, d.worldShelf)
                WorldPictureData.validate(d.worldArticles, d.worldPictures)
                WorldPictureData.validateLooks(d.worldPictures, d.worldPictureLooks, d.worldNotes)
                WorldAlbumData.validate(d.companions.map { it.id }.toSet(), d.worldAlbumPhotos, d.worldAlbumLooks, d.worldNotes, d.worldPictureLooks)
                require(d.worldAlbumPhotos.all { images.validWorldPicture(File(staging, it.file), it.width, it.height, it.sha256) }) { "相册照片缺失、损坏或尺寸不一致" }
                require(d.worldAlbumPhotos.all { !images.file(it.file).exists() || WorldPictureData.digest(images.file(it.file)) == it.sha256 }) { "同名相册照片内容不一致，原资料保留" }
                require(d.worldPictures.all { images.validWorldPicture(File(staging, it.file), it.width, it.height, it.sha256) }) { "备份缺少窗外配图文件，或图片尺寸及内容不一致" }
                require(d.worldPictures.all { !images.file(it.file).exists() || WorldPictureData.digest(images.file(it.file)) == it.sha256 }) { "同名本地配图内容不一致，原资料保留" }
            }
            catch (e: IllegalArgumentException) { throw BackupException(e.message ?: "群聊备份不完整") }

            try {
                require(d.focusSessions.map { it.id }.distinct().size == d.focusSessions.size)
                d.focusSessions.forEach(com.cleo.cleos.focus.FocusTimer::restored)
            } catch (_: IllegalArgumentException) { throw BackupException("备份中的专注记录无效，原资料保留") }

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
            }.map { it.copy(avatar = picture(it.avatar),userAvatar=UserAvatars.restored(it.userAvatar) { name -> picture(name)!=null }) }
            // TA entries in such a backup carry no owner: they were all TA 1's.
            val diary = d.diary.map {
                if (it.author == DiaryEntryEntity.AUTHOR_AI && it.companionId == null) it.copy(companionId = Companions.FIRST) else it
            }
            // A sticker is its picture: one whose file didn't come along would be a name drawn as nothing.
            val stickers = d.stickers.filter { images.file(it.file).exists() }
            db.withTransaction {
                // What TAs noted to come back to, and what came of it: not in backups, and about
                // conversations that are about to go.
                db.translations().clear()
                db.favorites().clear()
                db.world().clearSchedules()
                db.world().clearOutsideSchedules()
                db.world().clearTopicSchedules()
                db.worldAlbum().clearSchedules()
                db.world().clearPictureSchedules()
                db.world().clearWritingSchedules()
                db.worldSerials().clearAllChapters(); db.worldSerials().clear()
                db.world().clearWritings()
                db.world().clearStates()
                db.world().clearNotes()
                db.world().clearWishes()
                db.world().clearArticles()
                db.world().clearBookmarks()
                db.world().clearShelf()
                db.world().clearPictures()
                db.world().clearPictureLooks()
                db.worldAlbum().clearLooks(); db.worldAlbum().clearPhotos()
                db.incomingCalls().clear()
                db.groups().clear()
                db.later().clear()
                db.wakes().clear()
                db.messages().clear()
                db.conversations().clear()
                db.diary().clear()
                db.focus().clear()
                db.todos().clear()
                db.letters().clear()
                db.memories().clear()
                db.lore().clear()
                db.feed().clear()
                db.stickers().clear()
                db.companions().clear()
                db.companions().insertAll(companions)
                db.conversations().insertAll(d.conversations)
                db.messages().insertAll(d.messages.map { m ->
                    if(m.role=="call") CallRecords.decode(m.content)?.let { r ->
                        m.copy(content=CallRecords.encode(CallRecords.restored(r,d.exportedAt)))
                    } ?: m else m
                })
                db.diary().insertAll(diary)
                db.todos().insertAll(d.todos)
                db.focus().insertAll(d.focusSessions.map(com.cleo.cleos.focus.FocusTimer::restored))
                db.letters().insertAll(d.letters)
                db.memories().insertAll(d.memories)
                db.lore().insertAll(d.lore)
                db.feed().insertAll(d.feedPosts)
                db.stickers().insertAll(stickers)
                db.favorites().insertAll(d.favorites)
                db.groups().restoreGroups(d.groups.map { g -> com.cleo.cleos.ai.GroupVoiceRules.restoredDraft(g) { picture(it) != null }
                    .copy(draftImages = MessageImages.encode(MessageImages.decode(g.draftImages).filter { picture(it.file) != null })) })
                db.groups().insertMembers(d.groupMembers.map { it.copy(avatar = picture(it.avatar)) })
                db.groups().restoreTurns(d.groupTurns.map(GroupBackupRules::restored))
                db.groups().restoreMessages(d.groupMessages.map { com.cleo.cleos.ai.GroupVoiceRules.restored(
                    com.cleo.cleos.ai.GroupPhotoRules.restored(GroupBackupRules.restored(it)) { name -> picture(name) != null }) { name -> picture(name) != null }
                    .copy(speakerAvatar = picture(it.speakerAvatar)) })
                db.groups().restoreMemories(d.groupMemories)
                db.world().restoreArticles(d.worldArticles)
                db.world().restoreJourneyTopics(d.worldJourneyTopics); db.world().restoreJourneyItems(d.worldJourneyItems)
                db.world().restoreNotes(d.worldNotes)
                db.world().restoreWritings(d.worldWritings)
                db.worldSerials().insertAll(d.worldSerials); db.worldSerials().insertChapters(d.worldSerialChapters)
                db.world().restoreWishes(d.worldWishes)
                db.world().restoreBookmarks(d.worldBookmarks)
                db.world().restoreShelf(d.worldShelf)
                db.world().restorePictures(d.worldPictures)
                db.world().restorePictureLooks(d.worldPictureLooks)
                db.worldAlbum().restorePhotos(d.worldAlbumPhotos); db.worldAlbum().restoreLooks(d.worldAlbumLooks)
            }
            // A backup from before each voice service had its own place says "api" for all of them.
            val (speechEngine, speechVoices) = Speech.migrate(bs.speechEngine, bs.speechBaseUrl, bs.speechVoice, bs.speechVoices)
            settings.update {
                it.copy(
                    userName = bs.userName,
                    feedInterests = bs.feedInterests,
                    feedRssUrl = bs.feedRssUrl,
                    feedNewsSources = bs.feedNewsSources,
                    feedCover = bs.feedCover?.takeIf { images.file(it).exists() },
                    feedBio = bs.feedBio.take(120),
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
                    photoLibrary = PhotoLibrary.decode(bs.photoLibrary),
                    feedPhotoTas = bs.feedPhotoTas.filter { id -> companions.any { ta -> ta.id == id } }.toSet(),
                    weatherCity = bs.weatherCity,
                    userAvatar = picture(bs.userAvatar),
                    chatAvatars = bs.chatAvatars,
                    avatarEachMessage = bs.avatarEachMessage,
                    myBubble = bs.myBubble,
                    myBubbleTheme = bs.myBubbleTheme,
                    bubblePaddingX = bs.bubblePaddingX.coerceIn(6, 24),
                    bubblePaddingY = bs.bubblePaddingY.coerceIn(4, 16),
                    messageSpacing = BubbleSpacing.normalized(bs.messageSpacing),
                    bubblePresets = bs.bubblePresets.distinctBy { it.id }.take(50).map { it.normalized().let { p -> p.copy(decoration = p.decoration.mapImages(::picture)) } },
                    bubbleDecoration = bs.bubbleDecoration.normalized().let { it.copy(faceImage = picture(it.faceImage), starImage = picture(it.starImage), components = it.components.map { item -> item.copy(image = picture(item.image)) }).normalized() },
                    bubbleBackgrounds = bs.bubbleBackgrounds.mapValues { it.value.normalized() },
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
                    showThought = bs.showThought,
                )
            }
            settings.setCurrentCompanion(companions.first().id)
            settings.setCurrentConversation(null)
            settings.setCurrentGroup(bs.selectedGroupId?.takeIf { id -> d.groups.any { it.id == id } })
            return BackupSummary(
                companions.size, d.conversations.size, d.messages.size, d.diary.size, d.letters.size, d.todos.size,
                pictures.count { !it.name.startsWith(VOICE_PREFIX) && it.name !in stickers.map { s -> s.file }.toSet() },
                voices = pictures.count { it.name.startsWith(VOICE_PREFIX) },
                stickers = stickers.size, favorites = d.favorites.size, lore = d.lore.size, feedPosts = d.feedPosts.size,
                groups = d.groups.size, groupMessages = d.groupMessages.count { it.content.isNotBlank() || it.images != null },
                groupMemories = d.groupMemories.size,
                worldArticles = d.worldArticles.count { it.space == "study" }, worldNotes = d.worldNotes.size, worldWritings = d.worldWritings.size, worldSerials = d.worldSerials.size,
                worldOutside = d.worldArticles.count { it.space == "outside" },
                worldWishes = d.worldWishes.size,
                worldBookmarks = d.worldBookmarks.size, worldShelf = d.worldShelf.size, worldPictures = d.worldPictures.size, worldPictureLooks = d.worldPictureLooks.size, worldAlbumPhotos = d.worldAlbumPhotos.size, worldAlbumLooks = d.worldAlbumLooks.size,
                missingGroupDraftImages = d.groups.sumOf { g -> MessageImages.decode(g.draftImages).count { picture(it.file) == null } },
                missingGroupDraftVoices = d.groups.count { g -> com.cleo.cleos.ai.GroupVoiceRules.draft(g.draftAudio, g.id)?.let { picture(it.file) == null } == true },
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    private companion object {
        const val JSON_NAME = "backup.json"
    }
}
