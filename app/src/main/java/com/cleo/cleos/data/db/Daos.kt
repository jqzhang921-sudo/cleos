package com.cleo.cleos.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CompanionDao {
    @Query("SELECT * FROM companions ORDER BY createdAt, id")
    fun observeAll(): Flow<List<CompanionEntity>>

    @Query("SELECT * FROM companions ORDER BY createdAt, id")
    suspend fun all(): List<CompanionEntity>

    @Query("SELECT * FROM companions WHERE id = :id")
    suspend fun get(id: Long): CompanionEntity?

    @Insert
    suspend fun insert(companion: CompanionEntity): Long

    @Update
    suspend fun update(companion: CompanionEntity)

    @Query("DELETE FROM companions WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertAll(items: List<CompanionEntity>)

    @Query("DELETE FROM companions")
    suspend fun clear()
}

@Dao
interface FreeTopicDao {
    @Query("SELECT * FROM free_topics WHERE companionId = :id")
    suspend fun get(id: Long): FreeTopicStateEntity?

    @Upsert
    suspend fun put(state: FreeTopicStateEntity)

    @Query("UPDATE free_topics SET nextAt = :next WHERE companionId = :id AND nextAt = :expected")
    suspend fun move(id: Long, expected: Long, next: Long): Int

    @Query("UPDATE free_topics SET nextAt = :next, attemptDay = :day, attempts = CASE WHEN attemptDay = :day THEN attempts + 1 ELSE 1 END WHERE companionId = :id AND nextAt = :expected AND (attemptDay IS NOT :day OR attempts < :maximum)")
    suspend fun claim(id: Long, expected: Long, next: Long, day: Long, maximum: Int): Int
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE companionId = :companionId ORDER BY updatedAt DESC")
    fun observeFor(companionId: Long): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE companionId = :companionId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestFor(companionId: Long): ConversationEntity?

    @Query("SELECT id FROM conversations WHERE companionId = :companionId")
    suspend fun idsFor(companionId: Long): List<Long>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE conversations SET followUpMessageId = :anchor, followUpAt = :at WHERE id = :id")
    suspend fun planFollowUp(id: Long, anchor: Long, at: Long)

    @Query("UPDATE conversations SET followUpMessageId = NULL, followUpAt = NULL WHERE id = :id")
    suspend fun cancelFollowUp(id: Long)

    @Query("UPDATE conversations SET followUpMessageId = NULL, followUpAt = NULL WHERE id = :id AND followUpMessageId = :anchor AND followUpAt <= :now")
    suspend fun claimFollowUp(id: Long, anchor: Long, now: Long): Int

    /**
     * A fold's result, kept only if the recap is still the one the fold started from: an edit
     * made meanwhile wins. Returns the rows changed.
     */
    @Query("UPDATE conversations SET recap = :recap, recapUntilAt = :at, recapUntilId = :messageId WHERE id = :id AND recap IS :was")
    suspend fun foldRecap(id: Long, recap: String, at: Long, messageId: Long, was: String?): Int

    /** The person's own version of the recap. */
    @Query("UPDATE conversations SET recap = :recap WHERE id = :id")
    suspend fun editRecap(id: Long, recap: String?)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM conversations")
    suspend fun all(): List<ConversationEntity>

    @Insert
    suspend fun insertAll(items: List<ConversationEntity>)

    @Query("DELETE FROM conversations")
    suspend fun clear()
}

@Dao
interface MessageDao {
    @Query("SELECT MAX(m.createdAt) FROM messages m JOIN conversations c ON c.id = m.conversationId WHERE c.companionId = :companionId AND m.role = 'user' AND m.note IS NULL")
    suspend fun lastUserFor(companionId: Long): Long?

    @Query("SELECT MAX(m.createdAt) FROM messages m JOIN conversations c ON c.id = m.conversationId WHERE c.companionId = :companionId AND m.role IN ('user', 'assistant', 'pat', 'call') AND m.note IS NULL")
    suspend fun lastActivityFor(companionId: Long): Long?

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY createdAt, id")
    suspend fun forFavorite(id: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt, id")
    fun observe(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    /** The pictures of one conversation's messages, to delete the files along with it. */
    @Query("SELECT images FROM messages WHERE conversationId = :conversationId AND images IS NOT NULL")
    suspend fun imagesIn(conversationId: Long): List<String>

    /** The recordings of one conversation's voice messages, likewise. */
    @Query("SELECT audio FROM messages WHERE conversationId = :conversationId AND audio IS NOT NULL")
    suspend fun audioIn(conversationId: Long): List<String>

    @Query("UPDATE messages SET error = :error WHERE id = :id")
    suspend fun setError(id: Long, error: String?)

    /** The person's latest message with pictures in a conversation: what "this photo" means. */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND role = 'user' AND images IS NOT NULL " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1",
    )
    suspend fun latestWithImages(conversationId: Long): MessageEntity?

    // For the home page, with one TA: the first thing the person said to them, and how
    // much was said. Lines shown instead of bubbles (notes, answers) are not things said.

    @Query(
        "SELECT MIN(m.createdAt) FROM messages m JOIN conversations c ON c.id = m.conversationId " +
            "WHERE c.companionId = :companionId AND m.role = 'user' AND m.note IS NULL",
    )
    fun observeFirstSaid(companionId: Long): Flow<Long?>

    @Query(
        "SELECT COUNT(*) FROM messages m JOIN conversations c ON c.id = m.conversationId " +
            "WHERE c.companionId = :companionId AND m.role IN ('user', 'assistant') AND m.note IS NULL " +
            "AND m.error IS NULL AND m.content != ''",
    )
    fun observeSaidCount(companionId: Long): Flow<Int>

    /** What was said with one TA lately, across their conversations, newest first: a letter's material. */
    @Query(
        "SELECT m.* FROM messages m JOIN conversations c ON c.id = m.conversationId " +
            "WHERE c.companionId = :companionId AND m.role IN ('user', 'assistant') AND m.note IS NULL " +
            "AND m.error IS NULL AND m.content != '' ORDER BY m.createdAt DESC, m.id DESC LIMIT :limit",
    )
    suspend fun saidLately(companionId: Long, limit: Int): List<MessageEntity>

    /** How much the person has said to one TA since [since]. */
    @Query(
        "SELECT COUNT(*) FROM messages m JOIN conversations c ON c.id = m.conversationId " +
            "WHERE c.companionId = :companionId AND m.role = 'user' AND m.note IS NULL AND m.createdAt > :since",
    )
    suspend fun saidSince(companionId: Long, since: Long): Int

    /** One TA's requests to see a secret, from any of their conversations, oldest first. */
    @Query(
        "SELECT m.* FROM messages m JOIN conversations c ON c.id = m.conversationId " +
            "WHERE c.companionId = :companionId AND m.role = 'request' ORDER BY m.createdAt, m.id",
    )
    suspend fun requestsBy(companionId: Long): List<MessageEntity>

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun setContent(id: Long, content: String)

    @Query("UPDATE messages SET reactions = :reactions WHERE id = :id")
    suspend fun setReactions(id: Long, reactions: String?)

    /** A run of pats grown by one: its record, and the time it was last patted. */
    @Query("UPDATE messages SET content = :content, createdAt = :at WHERE id = :id")
    suspend fun setPat(id: Long, content: String, at: Long)

    /** The newest [limit] messages, newest first. Callers reverse them for the API. */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun newest(conversationId: Long, limit: Int): List<MessageEntity>

    // Finding things said with one TA, across their conversations. What the chat draws as
    // a line (notes, tool results, cards) and replies that failed are not things said.

    /** Those with [pattern] in them (a LIKE pattern, \ escaping), newest first; [role] null for both sides. */
    @Query(
        "SELECT m.* FROM messages m JOIN conversations c ON c.id = m.conversationId " +
            "WHERE c.companionId = :companionId AND m.role IN ('user', 'assistant') AND m.note IS NULL " +
            "AND m.error IS NULL AND m.content LIKE :pattern ESCAPE '\\' AND (:role IS NULL OR m.role = :role) " +
            "ORDER BY m.createdAt DESC, m.id DESC LIMIT :limit",
    )
    suspend fun search(companionId: Long, pattern: String, role: String?, limit: Int): List<MessageEntity>

    /** When each of them was said: the days a calendar marks. */
    @Query(
        "SELECT m.createdAt FROM messages m JOIN conversations c ON c.id = m.conversationId " +
            "WHERE c.companionId = :companionId AND m.role IN ('user', 'assistant') AND m.note IS NULL " +
            "AND m.error IS NULL AND (m.content != '' OR m.images IS NOT NULL OR m.audio IS NOT NULL)",
    )
    suspend fun saidTimes(companionId: Long): List<Long>

    /** The first of them in [from, to): where a day of the calendar opens. */
    @Query(
        "SELECT m.* FROM messages m JOIN conversations c ON c.id = m.conversationId " +
            "WHERE c.companionId = :companionId AND m.role IN ('user', 'assistant') AND m.note IS NULL " +
            "AND m.error IS NULL AND (m.content != '' OR m.images IS NOT NULL OR m.audio IS NOT NULL) " +
            "AND m.createdAt >= :from AND m.createdAt < :to ORDER BY m.createdAt, m.id LIMIT 1",
    )
    suspend fun firstSaidBetween(companionId: Long, from: Long, to: Long): MessageEntity?

    /** The messages after a point in the conversation (a time, then an id: the order they are read in), oldest first. */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "AND (createdAt > :at OR (createdAt = :at AND id > :id)) ORDER BY createdAt, id",
    )
    suspend fun after(conversationId: Long, at: Long, id: Long): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun count(conversationId: Long): Int

    /** What the TA said on its own here from [since] on, oldest first: what a wake just sent. */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND role = 'assistant' AND proactive = 1 " +
            "AND error IS NULL AND content != '' AND createdAt >= :since ORDER BY createdAt, id",
    )
    suspend fun proactiveSince(conversationId: Long, since: Long): List<MessageEntity>

    /** What the TA said here from [since] on, oldest first: a reply just written, for its notification. */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND role = 'assistant' " +
            "AND error IS NULL AND content != '' AND createdAt >= :since ORDER BY createdAt, id",
    )
    suspend fun repliedSince(conversationId: Long, since: Long): List<MessageEntity>

    /** When the person wrote, to any TA, from [since] on: what their days' usual start and end are read from (RoutineRules). */
    @Query("SELECT createdAt FROM messages WHERE role = 'user' AND note IS NULL AND createdAt >= :since")
    suspend fun userTimesSince(since: Long): List<Long>

    /** Everything a wake put here from [since] on (its calls, their results, its thinking): for when it came to nothing. */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND proactive = 1 AND createdAt >= :since")
    suspend fun deleteProactiveSince(conversationId: Long, since: Long)

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    /** A phone call's row and everything said and done in it. */
    @Query("DELETE FROM messages WHERE id = :id OR call = :id")
    suspend fun deleteCall(id: Long)

    /** Every phone call's row: the ones left open by the app stopping mid-call are closed with these. */
    @Query("SELECT * FROM messages WHERE role = 'call'")
    suspend fun calls(): List<MessageEntity>

    /** These rows, those still there. */
    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun byIds(ids: Collection<Long>): List<MessageEntity>

    /** When the last thing in a call was said; null when nothing was. */
    @Query("SELECT MAX(createdAt) FROM messages WHERE call = :callId")
    suspend fun lastInCall(callId: Long): Long?

    @Query("SELECT * FROM messages")
    suspend fun all(): List<MessageEntity>

    @Insert
    suspend fun insertAll(items: List<MessageEntity>)

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary_entries ORDER BY day DESC, createdAt DESC")
    fun observeAll(): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries WHERE id = :id")
    suspend fun get(id: Long): DiaryEntryEntity?

    /** How many entries one TA has written. */
    @Query("SELECT COUNT(*) FROM diary_entries WHERE author = 'ai' AND companionId = :companionId")
    fun observeWrittenBy(companionId: Long): Flow<Int>

    @Query("DELETE FROM diary_entries WHERE author = 'ai' AND companionId = :companionId")
    suspend fun deleteWrittenBy(companionId: Long)

    // What a TA may read: never a secret; the person's entries when [mine] (reading the
    // diary is switched on); of the TAs' entries only [own]'s, pass -1 for none. Another
    // TA's diary is never among them.

    @Query(
        "SELECT * FROM diary_entries WHERE day = :day AND (secret = 0 OR author = 'ai') " +
            "AND ((:mine AND author = 'me') OR (author = 'ai' AND companionId = :own)) ORDER BY createdAt",
    )
    suspend fun onDay(day: Long, mine: Boolean, own: Long): List<DiaryEntryEntity>

    @Query(
        "SELECT * FROM diary_entries WHERE (secret = 0 OR author = 'ai') " +
            "AND ((:mine AND author = 'me') OR (author = 'ai' AND companionId = :own)) " +
            "ORDER BY day DESC, createdAt DESC LIMIT :limit",
    )
    suspend fun recent(mine: Boolean, own: Long, limit: Int): List<DiaryEntryEntity>

    /**
     * Candidates for a keyword, newest first. [pattern] is a LIKE pattern with `!` as the
     * escape character. It also matches inside the blocks' JSON (keys, image file names),
     * so callers check the plain text again.
     */
    @Query(
        "SELECT * FROM diary_entries WHERE (secret = 0 OR author = 'ai') " +
            "AND ((:mine AND author = 'me') OR (author = 'ai' AND companionId = :own)) " +
            "AND (title LIKE :pattern ESCAPE '!' OR blocks LIKE :pattern ESCAPE '!') " +
            "ORDER BY day DESC, createdAt DESC LIMIT :limit",
    )
    suspend fun search(pattern: String, mine: Boolean, own: Long, limit: Int): List<DiaryEntryEntity>

    /** Written after [since], newest first, with the same reach as the three above. */
    @Query(
        "SELECT * FROM diary_entries WHERE (secret = 0 OR author = 'ai') AND createdAt > :since " +
            "AND ((:mine AND author = 'me') OR (author = 'ai' AND companionId = :own)) " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun since(since: Long, mine: Boolean, own: Long, limit: Int): List<DiaryEntryEntity>

    @Query("SELECT * FROM diary_entries WHERE secret = 1 AND author = 'me' ORDER BY day DESC, createdAt DESC")
    suspend fun secrets(): List<DiaryEntryEntity>

    @Query("SELECT COUNT(*) FROM diary_entries WHERE secret = 1 AND author = 'me' AND day = :day")
    suspend fun secretsOnDay(day: Long): Int

    @Insert
    suspend fun insert(entry: DiaryEntryEntity): Long

    @Update
    suspend fun update(entry: DiaryEntryEntity)

    @Query("DELETE FROM diary_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM diary_entries")
    suspend fun all(): List<DiaryEntryEntity>

    @Insert
    suspend fun insertAll(items: List<DiaryEntryEntity>)

    @Query("DELETE FROM diary_entries")
    suspend fun clear()
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE companionId = :companionId ORDER BY createdAt, id")
    fun observeFor(companionId: Long): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE companionId = :companionId ORDER BY createdAt, id")
    suspend fun allFor(companionId: Long): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    fun observe(id: Long): Flow<MemoryEntity?>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun get(id: Long): MemoryEntity?

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Update
    suspend fun update(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memories WHERE companionId = :companionId")
    suspend fun deleteFor(companionId: Long)

    @Query("SELECT * FROM memories")
    suspend fun all(): List<MemoryEntity>

    @Insert
    suspend fun insertAll(items: List<MemoryEntity>)

    @Query("DELETE FROM memories")
    suspend fun clear()
}

@Dao
interface LoreDao {
    /** In the order the page shows them: by book, then by where they sat in it. */
    @Query("SELECT * FROM lore WHERE companionId = :companionId ORDER BY book, position, id")
    fun observeFor(companionId: Long): Flow<List<LoreEntity>>

    @Query("SELECT * FROM lore WHERE companionId = :companionId ORDER BY book, position, id")
    suspend fun allFor(companionId: Long): List<LoreEntity>

    @Query("SELECT * FROM lore WHERE id = :id")
    suspend fun get(id: Long): LoreEntity?

    @Insert
    suspend fun insert(entry: LoreEntity): Long

    @Update
    suspend fun update(entry: LoreEntity)

    @Query("DELETE FROM lore WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM lore WHERE companionId = :companionId")
    suspend fun deleteFor(companionId: Long)

    @Query("SELECT * FROM lore")
    suspend fun all(): List<LoreEntity>

    @Insert
    suspend fun insertAll(items: List<LoreEntity>)

    @Query("DELETE FROM lore")
    suspend fun clear()
}

@Dao
interface LaterDao {
    @Insert
    suspend fun insert(note: LaterEntity): Long

    @Query("SELECT * FROM later WHERE id = :id")
    suspend fun get(id: Long): LaterEntity?

    @Query("DELETE FROM later WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM later WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT * FROM later WHERE companionId = :companionId ORDER BY dueAt, id")
    suspend fun allFor(companionId: Long): List<LaterEntity>

    @Query("SELECT * FROM later WHERE companionId = :companionId ORDER BY dueAt, id")
    fun observeFor(companionId: Long): Flow<List<LaterEntity>>

    /** Come due and not past their time yet, soonest first. */
    @Query("SELECT * FROM later WHERE companionId = :companionId AND dueAt <= :now AND expiresAt > :now ORDER BY dueAt, id")
    suspend fun dueFor(companionId: Long, now: Long): List<LaterEntity>

    @Query("SELECT * FROM later")
    suspend fun all(): List<LaterEntity>

    @Query("DELETE FROM later")
    suspend fun clear()
}

@Dao
interface WakeDao {
    @Insert
    suspend fun insert(wake: WakeEntity): Long

    @Query("SELECT * FROM wakes WHERE companionId = :companionId ORDER BY at DESC, id DESC LIMIT 1")
    fun observeLatest(companionId: Long): Flow<WakeEntity?>

    /** Times a TA's wakes said something since [since]. */
    @Query("SELECT COUNT(*) FROM wakes WHERE companionId = :companionId AND outcome = 'sent' AND at > :since")
    suspend fun sentSince(companionId: Long, since: Long): Int

    /** Keep recent status plus the last two sent turns, so quiet checks cannot erase the unanswered fuse. */
    @Query(
        "DELETE FROM wakes WHERE companionId = :companionId AND id NOT IN " +
            "(SELECT id FROM wakes WHERE companionId = :companionId ORDER BY at DESC, id DESC LIMIT :keep) " +
            "AND id NOT IN (SELECT id FROM wakes WHERE companionId = :companionId AND outcome = 'sent' ORDER BY at DESC, id DESC LIMIT 2)",
    )
    suspend fun prune(companionId: Long, keep: Int)

    @Query("DELETE FROM wakes")
    suspend fun clear()
}

@Dao
interface LetterDao {
    @Query("SELECT * FROM letters WHERE companionId = :companionId ORDER BY createdAt DESC, id DESC")
    fun observeFor(companionId: Long): Flow<List<LetterEntity>>

    @Query("SELECT * FROM letters WHERE companionId = :companionId ORDER BY createdAt DESC, id DESC")
    suspend fun allFor(companionId: Long): List<LetterEntity>

    @Query("SELECT * FROM letters WHERE id = :id")
    fun observe(id: Long): Flow<LetterEntity?>

    @Query("SELECT * FROM letters WHERE id = :id")
    suspend fun get(id: Long): LetterEntity?

    /** A TA's letters still on their way at [now], unread: each gets a notification when it arrives. */
    @Query("SELECT * FROM letters WHERE author = 'ai' AND readAt IS NULL AND deliverAt > :now")
    suspend fun onTheirWay(now: Long): List<LetterEntity>

    /** The person's sent letters that no letter answers yet: replies still to write. */
    @Query(
        "SELECT * FROM letters l WHERE author = 'me' AND deliverAt IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM letters r WHERE r.replyTo = l.id) ORDER BY deliverAt",
    )
    suspend fun unanswered(): List<LetterEntity>

    @Insert
    suspend fun insert(letter: LetterEntity): Long

    @Update
    suspend fun update(letter: LetterEntity)

    @Query("DELETE FROM letters WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM letters WHERE companionId = :companionId")
    suspend fun deleteFor(companionId: Long)

    @Query("SELECT * FROM letters")
    suspend fun all(): List<LetterEntity>

    @Insert
    suspend fun insertAll(items: List<LetterEntity>)

    @Query("DELETE FROM letters")
    suspend fun clear()
}

@Dao
interface StickerDao {
    /** In the order they were added: the drawer's, and the TA's list's, which should stay put (it is in the cached prompt). */
    @Query("SELECT * FROM stickers ORDER BY createdAt, id")
    fun observeAll(): Flow<List<StickerEntity>>

    @Query("SELECT * FROM stickers ORDER BY createdAt, id")
    suspend fun all(): List<StickerEntity>

    @Query("SELECT * FROM stickers WHERE id = :id")
    suspend fun get(id: Long): StickerEntity?

    @Insert
    suspend fun insert(sticker: StickerEntity): Long

    @Update
    suspend fun update(sticker: StickerEntity)

    @Query("DELETE FROM stickers WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertAll(items: List<StickerEntity>)

    @Query("DELETE FROM stickers")
    suspend fun clear()
}

@Dao
interface TodoDao {
    /** Unordered: the screen sorts (dated first by date, then the rest in the order added). */
    @Query("SELECT * FROM todos")
    fun observeAll(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun get(id: Long): TodoEntity?

    @Query("SELECT COUNT(*) FROM todos WHERE done = 1")
    fun observeDoneCount(): Flow<Int>

    @Insert
    suspend fun insert(todo: TodoEntity): Long

    @Upsert
    suspend fun upsert(todo: TodoEntity)

    @Delete
    suspend fun delete(todo: TodoEntity)

    @Query("SELECT * FROM todos")
    suspend fun all(): List<TodoEntity>

    @Insert
    suspend fun insertAll(items: List<TodoEntity>)

    @Query("DELETE FROM todos")
    suspend fun clear()
}
