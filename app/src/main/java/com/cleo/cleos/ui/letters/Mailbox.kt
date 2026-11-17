package com.cleo.cleos.ui.letters

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.cleo.cleos.data.db.LetterEntity
import kotlinx.coroutines.delay

/**
 * What the mailbox shows at a given moment. A TA's letter is in it from its arrival time on;
 * until then it doesn't exist for the person, it is on its way.
 */
object Mailbox {
    private fun LetterEntity.arrived(now: Long) = (deliverAt ?: Long.MAX_VALUE) <= now

    /** The person's letters (drafts too) and the TA's that have arrived, newest first. */
    fun shown(letters: List<LetterEntity>, now: Long): List<LetterEntity> =
        letters.filter { it.author == LetterEntity.AUTHOR_ME || it.arrived(now) }
            .sortedByDescending { it.deliverAt ?: it.createdAt }

    fun unread(letters: List<LetterEntity>, now: Long): Int =
        letters.count { it.author == LetterEntity.AUTHOR_AI && it.arrived(now) && it.readAt == null }

    /** A letter the person sent that has no reply in the mailbox yet: not written, or on its way. */
    fun replyOnTheWay(letters: List<LetterEntity>, now: Long): Boolean =
        letters.any { sent -> sent.sourceType == LetterEntity.RETROSPECTIVE && sent.author == LetterEntity.AUTHOR_ME && !sent.draft && letters.none { it.sourceType == LetterEntity.RETROSPECTIVE && it.author == LetterEntity.AUTHOR_AI && it.replyTo == sent.id && it.arrived(now) } }
}

/** The time, again every half minute: a letter due now shows up without leaving the screen. */
@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(30_000)
        value = System.currentTimeMillis()
    }
}
