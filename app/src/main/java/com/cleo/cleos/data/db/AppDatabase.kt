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
        LetterEntity::class,
        MemoryEntity::class,
        LaterEntity::class,
        WakeEntity::class,
        StickerEntity::class,
        FavoriteEntity::class,
        LoreEntity::class,
    ],
    version = 23,
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
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun companions(): CompanionDao
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun diary(): DiaryDao
    abstract fun todos(): TodoDao
    abstract fun letters(): LetterDao
    abstract fun memories(): MemoryDao
    abstract fun later(): LaterDao
    abstract fun wakes(): WakeDao
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
