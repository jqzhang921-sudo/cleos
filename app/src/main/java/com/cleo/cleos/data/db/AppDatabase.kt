package com.cleo.cleos.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema changes must come with a Migration. Never fall back to destructive migration:
 * this database holds a diary, and "the app updated and my diary is empty" is the one
 * failure this app cannot have.
 */
@Database(
    entities = [
        CompanionEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        DiaryEntryEntity::class,
        TodoEntity::class,
        com.cleo.cleos.focus.FocusSession::class,
        LetterEntity::class,
        MemoryEntity::class,
        LaterEntity::class,
        WakeEntity::class,
        WakeActivityEntity::class,
        StickerEntity::class,
        FavoriteEntity::class,
        LoreEntity::class,
        FreeTopicStateEntity::class,
        FeedVisitStateEntity::class,
        FeedPostEntity::class,
        ChatGroupEntity::class,
        GroupMemberEntity::class,
        GroupTurnEntity::class,
        GroupMessageEntity::class,
        GroupMemoryEntity::class,
        IncomingCallEntity::class,
        WorldArticleEntity::class,
        WorldNoteEntity::class,
        WorldReadingStateEntity::class,
        WorldScheduleEntity::class,
        WorldOutsideScheduleEntity::class,
        WorldTopicScheduleEntity::class,
        WorldBookmarkEntity::class,
        WorldShelfEntity::class,
        WorldPictureEntity::class,
        WorldPictureLookEntity::class,
        WorldAlbumPhotoEntity::class,
        WorldAlbumLookEntity::class,
        WorldAlbumScheduleEntity::class,
        WorldPictureScheduleEntity::class,
        WorldWishEntity::class,
        MessageTranslationEntity::class,
        WorldWritingEntity::class,
        WorldSerialEntity::class,
        WorldSerialChapterEntity::class,
        WorldWritingScheduleEntity::class,
        WorldJourneyTopicEntity::class,
        WorldJourneyItemEntity::class,
    ],
    version = 66,
    exportSchema = true,
    autoMigrations = [
        // 1 -> 2: tool calls on messages (four nullable columns, nothing rewritten).
        AutoMigration(from = 1, to = 2),
        // 2 -> 3: who wrote a diary entry, and whether it is a secret (both with defaults).
        AutoMigration(from = 2, to = 3),
        // 3 -> 4: pictures sent in the chat (one nullable column).
        AutoMigration(from = 3, to = 4),
        // 4 -> 5: several TAs. Their table; whose each conversation and TA diary entry is.
        // The first TA's row is made at startup from the old settings (Companions.ensure).
        AutoMigration(from = 4, to = 5, spec = AppDatabase.OneTaBefore::class),
        // 5 -> 6: letters (a table), and when each TA last tried to write one (a nullable column).
        AutoMigration(from = 5, to = 6),
        // 6 -> 7: what each TA keeps in mind (a table).
        AutoMigration(from = 6, to = 7),
        // 7 -> 8: when the reply to a letter comes, picked as it is sent (a nullable column).
        AutoMigration(from = 7, to = 8),
        // 8 -> 9: each conversation's recap and where it ends (nullable columns), and the
        // thinking switch on each TA (off to begin with).
        AutoMigration(from = 8, to = 9),
        // 9 -> 10: voice messages (a nullable column).
        AutoMigration(from = 9, to = 10),
        // 10 -> 11: what the TA thought before a reply, kept to be read (a nullable column).
        AutoMigration(from = 10, to = 11),
        // 11 -> 12: the message a message answers, for quoting (a nullable column).
        AutoMigration(from = 11, to = 12),
        // 12 -> 13: what TAs note to come back to, and what came of it (two tables); whether each
        // TA may reach out on its own (on to begin with); which messages it sent that way (none yet).
        AutoMigration(from = 12, to = 13),
        // 13 -> 14: the sticker collection (a table), and the emoji stuck on messages (a nullable column).
        AutoMigration(from = 13, to = 14),
        // 14 -> 15: which phone call a message was said in (a nullable column).
        AutoMigration(from = 14, to = 15),
        // 15 -> 16: a second model for each TA's words that are heard, on the phone and answering
        // voice messages (off to begin with, its address and model empty).
        AutoMigration(from = 15, to = 16),
        AutoMigration(from = 16, to = 17),
        // 17 -> 18: a TA's 设定, brought over from another app's world book (a table).
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
        AutoMigration(from = 25, to = 26),
        AutoMigration(from = 26, to = 27),
        // Shared feed cards: one nullable column, preserving every existing message.
        AutoMigration(from = 27, to = 28),
        AutoMigration(from = 28, to = 29),
        AutoMigration(from = 29, to = 30),
        AutoMigration(from = 30, to = 31),
        AutoMigration(from = 31, to = 32),
        // Keep former members as historical authors while excluding them from new replies.
        AutoMigration(from = 32, to = 33),
        AutoMigration(from = 33, to = 34),
        AutoMigration(from = 34, to = 35),
        AutoMigration(from = 35, to = 36),
        AutoMigration(from = 36, to = 37),
        AutoMigration(from = 37, to = 38),
        AutoMigration(from = 38, to = 39),
        AutoMigration(from = 39, to = 40),
        AutoMigration(from = 40, to = 41),
        AutoMigration(from = 41, to = 42),
        AutoMigration(from = 42, to = 43),
        // Per-member local permission to use explicitly selected private memory revisions.
        AutoMigration(from = 43, to = 44),
        AutoMigration(from = 44, to = 45),
        AutoMigration(from = 45, to = 46, spec = AppDatabase.WorldRetryBudget::class),
        AutoMigration(from = 46, to = 47),
        AutoMigration(from = 47, to = 48),
        AutoMigration(from = 48, to = 49),
        AutoMigration(from = 49, to = 50),
        AutoMigration(from = 50, to = 51),
        AutoMigration(from = 51, to = 52),
        AutoMigration(from = 52, to = 53),
        AutoMigration(from = 53, to = 54),
        AutoMigration(from = 54, to = 55),
        AutoMigration(from = 55, to = 56),
        AutoMigration(from = 56, to = 57),
        AutoMigration(from = 57, to = 58),
        AutoMigration(from = 58, to = 59),
        // Text-message preference defaults to natural; no historical message is rewritten.
        AutoMigration(from = 59, to = 60),
        AutoMigration(from = 60, to = 61),
        AutoMigration(from = 61, to = 62),
        // Token usage of each reply, shown under its last bubble (a nullable column).
        AutoMigration(from = 62, to = 63),
        AutoMigration(from = 63, to = 64),
        AutoMigration(from = 64, to = 65),
        // User-arranged serial metadata; creative prose stays untouched.
        AutoMigration(from = 65, to = 66),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun translations(): MessageTranslationDao
    /** Old failed attempts were charged without retaining enough detail to split them from valid ones.
     * Give affected shelves a fresh budget once on upgrade; books, positions and notes are untouched. */
    class WorldRetryBudget : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("""UPDATE world_reading_state SET attempts = 0, token = NULL, failureCode = 'legacy',
                status = '旧版异常占用的阅读次数已重新计算，可以重试'
                WHERE token IS NOT NULL OR status IN (
                '这次阅读没有完成，进度未更新。可检查网络或模型后再试',
                '阅读已停止，未完成的片段没有计入进度', '上次阅读中断，进度没有向前移动')""")
        }
    }
    abstract fun worldSerials(): WorldSerialDao
    abstract fun world(): WorldDao
    abstract fun worldAlbum(): WorldAlbumDao
    abstract fun incomingCalls(): IncomingCallDao
    abstract fun groups(): GroupDao
    abstract fun feed(): FeedDao
    abstract fun freeTopics(): FreeTopicDao
    abstract fun feedVisits(): FeedVisitDao
    abstract fun companions(): CompanionDao
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun diary(): DiaryDao
    abstract fun focus(): com.cleo.cleos.focus.FocusDao
    abstract fun todos(): TodoDao
    abstract fun letters(): LetterDao
    abstract fun memories(): MemoryDao
    abstract fun later(): LaterDao
    abstract fun wakes(): WakeDao
    abstract fun wakeActivities(): WakeActivityDao
    abstract fun stickers(): StickerDao
    abstract fun favorites(): FavoriteDao
    abstract fun lore(): LoreDao

    /**
     * Before version 5 there was one TA, so every entry a TA wrote was TA 1's. (Conversations
     * get that from the column's default; a diary entry's owner depends on its author.)
     */
    class OneTaBefore : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE diary_entries SET companionId = 1 WHERE author = 'ai'")
        }
    }
}
