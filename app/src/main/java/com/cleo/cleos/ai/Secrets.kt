package com.cleo.cleos.ai

import com.cleo.cleos.data.DiaryBlocks
import com.cleo.cleos.data.db.DiaryEntryEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * The model asking to see one little secret. It lives in the chat as a "request" row,
 * shown to the person as a card with two answers.
 */
@Serializable
data class SecretRequest(
    val diaryId: Long,
    /**
     * The entry's day and title when it was asked for, for the card. The card is only
     * ever drawn for the person; neither goes to the model.
     */
    val day: Long,
    val title: String = "",
    /** What the model gave as its reason, shown on the card. */
    val reason: String = "",
    val status: String = PENDING,
) {
    companion object {
        const val PENDING = "pending"
        const val GRANTED = "granted"
        const val DECLINED = "declined"

        /** The entry was deleted before an answer came. */
        const val GONE = "gone"
    }
}

@Serializable
internal data class SecretShare(val diaryId: Long, val excerpt: String)

internal object SecretShares {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(share: SecretShare): String = json.encodeToString(share)
    fun decode(raw: String): SecretShare? = runCatching { json.decodeFromString<SecretShare>(raw) }.getOrNull()
}

internal object SecretRequests {
    // encodeDefaults: the status starts as its default, and has to be in the stored text.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Past the first few thousand characters a secret is summed up rather than pasted whole. */
    private const val SHARE_MAX = 3000

    fun encode(r: SecretRequest): String = json.encodeToString(r)

    fun decode(raw: String): SecretRequest? = runCatching { json.decodeFromString<SecretRequest>(raw) }.getOrNull()

    /**
     * What the model is given when the person says yes, as the person's own message: it is
     * something they showed, in their turn. Given once; the entry stays locked.
     */
    fun shared(entry: DiaryEntryEntity, today: LocalDate): String {
        val blocks = DiaryBlocks.decode(entry.blocks)
        val body = DiaryBlocks.plainText(blocks)
        val images = DiaryBlocks.images(blocks).size
        return buildString {
            append("（我把 ").append(Describe.date(LocalDate.ofEpochDay(entry.day), today)).append(" 写的小秘密给你看了）")
            if (entry.title.isNotBlank()) append("\n标题：").append(entry.title)
            if (body.isNotEmpty()) {
                append('\n').append(body.take(SHARE_MAX))
                if (body.length > SHARE_MAX) append("……（后面还有 ${body.length - SHARE_MAX} 字）")
            }
            if (images > 0) append("\n（配了 $images 张图）")
        }
    }

    fun declined(day: LocalDate, today: LocalDate): String =
        "（我没给你看 ${Describe.date(day, today)} 的那个小秘密）"
}
