package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.DiaryBlock
import com.cleo.cleos.data.DiaryBlocks
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SecretsTest {
    private val today = LocalDate.of(2026, 9, 23)

    @Test
    fun editedSecretRequestKeepsItsDiaryAssociationOnlyInModelContext() {
        val message = MessageEntity(id = 1, conversationId = 1, role = "user", content = "不方便也没关系，可以看看吗？", createdAt = 1, diaryRequestId = 42)
        val ta = CompanionEntity(id = 1, apiBaseUrl = "", apiModel = "", createdAt = 0)
        val now = ZonedDateTime.of(2026, 9, 23, 21, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        val out = Prompt.messages(AppSettings(), ta, listOf(message), now, setOf(ToolGroup.AiDiary))
        assertTrue(out.last().content.contains("日记 #42"))
        assertTrue(out.last().content.contains(message.content))
        assertFalse(message.content.contains("#42"))
        assertTrue(out.last().content.contains("share_my_secret"))
    }

    @Test
    fun aRequestKeepsItsStatusThroughStorage() {
        val r = SecretRequest(diaryId = 7, day = today.toEpochDay(), title = "今天的事", reason = "想知道")
        val raw = SecretRequests.encode(r)
        assertTrue("the default status must be written out", raw.contains("\"pending\""))
        assertEquals(r.copy(status = SecretRequest.GRANTED), SecretRequests.decode(SecretRequests.encode(r.copy(status = SecretRequest.GRANTED))))
        assertEquals(null, SecretRequests.decode("not json"))
    }

    @Test
    fun sayingYesSharesTheEntryAsWritten() {
        val entry = DiaryEntryEntity(
            id = 7,
            day = today.minusDays(1).toEpochDay(),
            title = "昨天的事",
            blocks = DiaryBlocks.encode(listOf(DiaryBlock.Text("其实我有点想哭"), DiaryBlock.Image("a.jpg", 10, 10))),
            createdAt = 1,
            updatedAt = 1,
            secret = true,
        )
        val shared = SecretRequests.shared(entry, today)
        assertTrue(shared.startsWith("（我把 2026-09-22（周二，昨天） 写的小秘密给你看了）"))
        assertTrue(shared.contains("标题：昨天的事"))
        assertTrue(shared.contains("其实我有点想哭"))
        assertTrue(shared.contains("（配了 1 张图）"))
    }

    @Test
    fun theAnswerGoesToTheModelAndTheCardDoesNot() {
        val now = ZonedDateTime.of(2026, 9, 23, 21, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        val history = listOf(
            MessageEntity(id = 1, conversationId = 1, role = "user", content = "你猜我写了什么", createdAt = 1),
            MessageEntity(id = 2, conversationId = 1, role = "assistant", content = "能给我看看吗", createdAt = 2),
            MessageEntity(
                id = 3, conversationId = 1, role = "request", createdAt = 3,
                content = SecretRequests.encode(SecretRequest(7, today.toEpochDay(), title = "标题只给人看")),
            ),
            MessageEntity(
                id = 4, conversationId = 1, role = "user", createdAt = 4,
                content = "（我没给你看 2026-09-23 的那个小秘密）", note = "没给Song看9月23日的小秘密",
            ),
        )
        val ta = CompanionEntity(id = 1, apiBaseUrl = "", apiModel = "", createdAt = 0)
        val out = Prompt.messages(AppSettings(), ta, history, now, setOf(ToolGroup.Secrets))
        assertEquals(listOf("system", "user", "assistant", "user"), out.map { it.role })
        assertTrue(out.last().content.endsWith("（我没给你看 2026-09-23 的那个小秘密）"))
        assertFalse(out.any { it.content.contains("标题只给人看") || it.content.contains("没给Song看") })
    }
}
