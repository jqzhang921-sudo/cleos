package com.cleo.cleos.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * One TA: who they are and which model speaks for them. Each has their own conversations
 * and their own diary entries; they don't see each other's.
 */
@Serializable
@Entity(tableName = "companions")
data class CompanionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    /** Written by the person, sent as-is. Empty means no persona at all. */
    val persona: String = "",
    val apiBaseUrl: String,
    val apiModel: String,
    /** A picture in ImageStore; or an emoji TA picked for itself; neither means the initial. */
    val avatar: String? = null,
    val avatarEmoji: String? = null,
    /** LocalDate.toEpochDay() the home page counts from; null counts from the first message. */
    val knownSince: Long? = null,
    val createdAt: Long,
    /** When this TA last tried to write a letter of their own, written or not: the wait between tries counts from it. */
    val lastLetterTry: Long? = null,
    /** Asks the model to think before it answers (the switch DeepSeek and GLM take). */
    @ColumnInfo(defaultValue = "0")
    val deepThinking: Boolean = false,
    /** May note things down to come back to, and say them on its own when they come due (ai/Later.kt). */
    @ColumnInfo(defaultValue = "1")
    val proactive: Boolean = true,
    /**
     * Another model for words that are heard: what the TA says on the phone, and its answer to a
     * voice message. Off, the model above does those too. The address and model stay while it is
     * off, to switch back to; its key is filed by address, like the chat model's.
     */
    @ColumnInfo(defaultValue = "0")
    val spokenModelOn: Boolean = false,
    @ColumnInfo(defaultValue = "")
    val spokenApiBaseUrl: String = "",
    @ColumnInfo(defaultValue = "")
    val spokenApiModel: String = "",
) {
    /**
     * The model for this TA's words: its own, or, for words that will be heard, the other one when
     * that is switched on and filled in. Half filled in counts as off: a blank address or model
     * can't answer, and the chat model can.
     */
    fun modelFor(heard: Boolean): TaModel =
        if (heard && spokenModelOn && spokenApiBaseUrl.isNotBlank() && spokenApiModel.isNotBlank()) {
            TaModel(spokenApiBaseUrl.trim(), spokenApiModel.trim(), forHeard = true)
        } else {
            TaModel(apiBaseUrl, apiModel, forHeard = false)
        }
}

/** Where a TA's words come from (CompanionEntity.modelFor); [forHeard]: the model for words heard, not the chat one. */
data class TaModel(val baseUrl: String, val model: String, val forHeard: Boolean)

/**
 * Something a TA noted in a conversation to come back to later (ai/Later.kt): what, the
 * situation it came up in, and when. Kept only until it is dealt with, and not backed up: a
 * note restored days later would only be past its time.
 */
@Entity(
    tableName = "later",
    indices = [Index("companionId"), Index("conversationId")],
    foreignKeys = [
        ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class LaterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companionId: Long,
    /** Where it came up; null once that conversation is gone, and the TA's latest is used. */
    val conversationId: Long?,
    val what: String,
    /** The situation it came up in, for the TA to read when it comes due. */
    val why: String = "",
    val createdAt: Long,
    val dueAt: Long,
    /** Past this it is dropped unsaid: the moment for it has gone. */
    val expiresAt: Long,
    /** Noted again when it came due, putting it off: it can't be put off a second time. */
    val putOff: Boolean = false,
)

/** What came of one note coming due, for the line in settings. The last few per TA are kept. */
@Entity(
    tableName = "wakes",
    indices = [Index("companionId")],
    foreignKeys = [
        ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class WakeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companionId: Long,
    val at: Long,
    /** One of [SENT], [SKIPPED], [EXPIRED], [HELD], [FAILED]. */
    val outcome: String,
    /** What was said, why not, or what went wrong. */
    val detail: String = "",
) {
    companion object {
        const val SENT = "sent"
        const val SKIPPED = "skipped"
        const val EXPIRED = "expired"

        /** Not pushed: what it said on its own before is still unanswered. */
        const val HELD = "held"
        const val FAILED = "failed"
    }
}

/**
 * Something one TA keeps in mind, about the person or about themselves: a topic, not a
 * single fact. Its one-line [summary] goes with every message; the [details] only when the
 * TA opens it. Only that TA has it.
 */
@Serializable
@Entity(tableName = "memories", indices = [Index("companionId")])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companionId: Long,
    /** One of MemoryKinds: profile, interest, recent, rapport, self. */
    val kind: String,
    /** A short title: 怎么称呼, 读书口味. */
    val name: String,
    /** What the topic is about, in one line. */
    val summary: String,
    /** A JSON array of strings (MemoryDetails). */
    val details: String = "[]",
    /** Pinned by the person: the TA can't change or delete it. */
    val pinned: Boolean = false,
    /** Who wrote it: [SOURCE_AI], [SOURCE_ME], or [SOURCE_IMPORT]. */
    val source: String = SOURCE_AI,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val SOURCE_AI = "ai"
        const val SOURCE_ME = "me"
        const val SOURCE_IMPORT = "import"
    }
}

/**
 * One entry of a TA's 设定: what a world book brought over from another app says about a
 * person, a place or a word. Kept apart from memory on purpose: this is the world the TA
 * lives in, not something they came to know, so it has no per-kind bound and it is not in
 * front of the model every message — the TA looks a word up in it when the word comes up.
 */
@Serializable
@Entity(tableName = "lore", indices = [Index("companionId")])
data class LoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companionId: Long,
    /** The book it came in, for grouping on the page; empty for one written by hand. */
    val book: String = "",
    /** What the entry is called: the file's remark, or its first word. */
    val title: String,
    /** A JSON array of strings (LoreKeys): the words that bring this entry up. */
    val keys: String = "[]",
    val content: String,
    /** Off: kept and readable on the page, but never given to the TA. */
    val enabled: Boolean = true,
    /** Where it sat among its book's entries (the file's insertion order). */
    val position: Int = 0,
    /** Who wrote it: [MemoryEntity.SOURCE_IMPORT] or [SOURCE_ME]. */
    val source: String = MemoryEntity.SOURCE_IMPORT,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val SOURCE_ME = "me"
    }
}

/**
 * A letter between the person and one TA. Theirs alone: other TAs don't see it.
 *
 * The person's start as drafts and are sent once, then stay as sent. A TA's is written
 * ahead of time and appears at [deliverAt], a while later, the way letters arrive.
 */
@Serializable
@Entity(tableName = "letters", indices = [Index("companionId")])
data class LetterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companionId: Long,
    /** [AUTHOR_ME] or [AUTHOR_AI]. */
    val author: String,
    val content: String,
    /** Written (a TA's) or last edited (a draft); for a sent letter, when it was sent. */
    val createdAt: Long,
    /** A TA's letter shows from then on. The person's: when it was sent, null while a draft. */
    val deliverAt: Long? = null,
    /** When the person opened a TA's letter; null while it is unread. */
    val readAt: Long? = null,
    /** For a TA's reply: the person's letter it answers. */
    val replyTo: Long? = null,
    /** For the person's sent letter: when its reply arrives, as they picked on sending. */
    val replyDueAt: Long? = null,
) {
    val draft: Boolean get() = author == AUTHOR_ME && deliverAt == null

    companion object {
        const val AUTHOR_ME = "me"
        const val AUTHOR_AI = "ai"
    }
}

@Serializable
@Entity(tableName = "conversations", indices = [Index("companionId")])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Whose conversation it is. Everything from before there were several TAs is TA 1's. */
    @ColumnInfo(defaultValue = "1")
    val companionId: Long = 1,
    /** What the TA keeps of the messages no longer sent verbatim: a running summary (ai/Recap.kt). */
    val recap: String? = null,
    /** The last message folded into [recap], by the order messages are read in: its time, then its id. */
    val recapUntilAt: Long? = null,
    val recapUntilId: Long? = null,
)

@Serializable
@Entity(
    tableName = "messages",
    indices = [Index("conversationId")],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /**
     * "user", "assistant", "tool" (what a tool call returned, answering [toolCallId]),
     * "note" (a line shown in the chat that is never sent to the model), "request" (the
     * model asking to see a little secret; [content] is a SecretRequest as JSON, and the
     * card is for the person only) or "call" (a phone call with the TA, where it began;
     * [content] is a CallRecord as JSON, and what was said in it are the rows whose [call] is
     * this row's id) or "pat" (a 拍一拍 line; [content] is a PatRecord as JSON, whose `who` says
     * which side patted — the person, the TA's own tool, or the person on themself — and the TA
     * hears of the person's pats with their next message, a run of ten or more as a turn of its own).
     */
    val role: String,
    /** For "tool": the result exactly as the model saw it. */
    val content: String,
    val createdAt: Long,
    /**
     * Set when this assistant turn did not finish (network error, stopped by hand).
     * The partial text is kept rather than thrown away: it may be the part worth reading.
     */
    val error: String? = null,
    /** Assistant turns that called tools: JSON array of ToolCall. */
    val toolCalls: String? = null,
    /**
     * Reasoning streamed before those calls. Providers that stream reasoning want it
     * back while the same turn is still calling tools (DeepSeek rejects the request
     * without it), so it is kept for turns with calls and only for those.
     */
    val reasoning: String? = null,
    /** "tool": the call this answers. */
    val toolCallId: String? = null,
    /**
     * The one line the chat shows instead of a bubble: for "tool" and "note" rows
     * (记下了待办「交报告」), and for a "user" row that is an answer to a request.
     */
    val note: String? = null,
    /** "user": the pictures sent with it, a JSON array of MessageImage, in order. */
    val images: String? = null,
    /** "user": a voice message's recording (MessageAudio as JSON); [content] is what it said, once transcribed. */
    val audio: String? = null,
    /**
     * "assistant": what the TA thought before this (MessageThought as JSON), shown folded above
     * it. Unlike [reasoning] it is never sent back: it is kept to be read.
     */
    val thought: String? = null,
    /** "user" or "assistant": the message this one answers (MessageQuote as JSON), shown under it. */
    val quote: String? = null,
    /** "assistant": said on the TA's own when something it noted came due, not in answer to anything. */
    @ColumnInfo(defaultValue = "0")
    val proactive: Boolean = false,
    /** "assistant": the emoji the person stuck on it from the long-press menu (MessageReaction list as JSON). */
    val reactions: String? = null,
    /**
     * Said in a phone call (or done in one: a tool's call and result): the id of the call's "call"
     * row. The chat shows the call as that one row; what was said is read from it.
     */
    val call: Long? = null,
    /** User request tied to a diary; hidden from the visible message text. */
    val diaryRequestId: Long? = null,
)

/**
 * A sticker in the person's collection, which both sides send from. A message carries it as a
 * few words, [[sticker:name]] (StickerText), never as the picture: the model knows it by [name]
 * and [description], and a sticker costs what those words cost. Shared by every TA.
 */
@Serializable
@Entity(tableName = "stickers")
data class StickerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** What it is called in a message; no other sticker has it as its name or among its [aliases]. */
    val name: String,
    /** One line saying what is in the picture, for the TA, who never sees it. */
    val description: String = "",
    /** A file in ImageStore, as it was picked when it moves (GIF, WebP), else downscaled. */
    val file: String,
    val width: Int,
    val height: Int,
    val animated: Boolean = false,
    /** Names it had before (a JSON array): messages sent under one still show it. */
    val aliases: String = "[]",
    val createdAt: Long,
)

@Serializable
@Entity(tableName = "diary_entries", indices = [Index("day")])
data class DiaryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** LocalDate.toEpochDay(): the day the entry is about, which the writer can change. */
    val day: Long,
    val title: String,
    /** JSON array of DiaryBlock: text and images in the order they were written. */
    val blocks: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** [AUTHOR_ME] or [AUTHOR_AI]: the book is shared, each entry says whose it is. */
    @ColumnInfo(defaultValue = DiaryEntryEntity.AUTHOR_ME)
    val author: String = DiaryEntryEntity.AUTHOR_ME,
    /**
     * A little secret: the model never reads it. It can only ask, in the chat, and sees it
     * when the person says yes, that one time.
     */
    @ColumnInfo(defaultValue = "0")
    val secret: Boolean = false,
    /** For [AUTHOR_AI]: which TA wrote it. Null for the person's own entries. */
    val companionId: Long? = null,
    @ColumnInfo(defaultValue = "''") val publicHint: String = "",
    @ColumnInfo(defaultValue = "0") val secretShared: Boolean = false,
    @ColumnInfo(defaultValue = "''") val sharedExcerpt: String = "",
) {
    val lockedForUser: Boolean get() = author == AUTHOR_AI && secret && !secretShared
    companion object {
        const val AUTHOR_ME = "me"
        const val AUTHOR_AI = "ai"
    }
}

@Serializable
@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String = "",
    val done: Boolean = false,
    /** LocalDate.toEpochDay(), or null for no date. */
    val dueDay: Long? = null,
    val createdAt: Long,
    val doneAt: Long? = null,
)
