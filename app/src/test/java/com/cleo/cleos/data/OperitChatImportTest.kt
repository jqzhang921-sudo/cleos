package com.cleo.cleos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OperitChatImportTest {
    @Test
    fun v2ArchiveKeepsEveryConversationAndUsesBaseMessagesOnly() {
        val file = ForeignFile.read(
            """{"archiveType":"operit_chat_archive","formatVersion":2,"exportedAt":300,
              "chats":[
                {"id":"a","title":"第一段","characterCardName":"小颂","messages":[
                  {"baseMessage":{"sender":"user","content":"在吗","timestamp":100}},
                  {"baseMessage":{"sender":"ai","content":"在呀","timestamp":200},
                   "variants":[{"variantIndex":1,"content":"备用答案"}]},
                  {"baseMessage":{"sender":"tool","content":"工具结果"}},
                  {"baseMessage":{"sender":"system","content":"系统提示"}}]},
                {"id":"b","title":"第二段","messages":[
                  {"baseMessage":{"sender":"user","content":"晚安","timestamp":250}},
                  {"baseMessage":{"sender":"ai","content":"好梦","timestamp":280}}]}
              ]} """.trimIndent(),
        )
        assertEquals(listOf("第一段", "第二段"), file.chats.map { it.title })
        assertEquals(listOf("在吗", "在呀"), file.chats[0].messages.map { it.content })
        assertEquals(listOf("晚安", "好梦"), file.chats[1].messages.map { it.content })
        assertTrue(file.chats[0].messages[0].fromMe)
        assertFalse(file.chats[0].messages[1].fromMe)
        assertEquals(100L, file.chats[0].messages[0].at)
        assertEquals(280L, file.chats[1].messages[1].at)
        assertTrue(file.cards.isEmpty()) // A binding name is not a complete character card.
        assertTrue(file.memories.isEmpty())
    }

    @Test
    fun legacyArrayReadsUnwrappedSenderMessages() {
        val file = ForeignFile.read(
            """[
              {"title":"旧版","messages":[
                {"sender":"user","content":"你好","timestamp":10},
                {"sender":"ai","content":"嗨","timestamp":20}]}]""",
        )
        assertEquals(1, file.chats.size)
        assertEquals(listOf(true, false), file.chats.single().messages.map { it.fromMe })
        assertEquals(listOf("你好", "嗨"), file.chats.single().messages.map { it.content })
    }

    @Test
    fun selectedVariantReplacesOriginalWithoutDuplicatingAlternatives() {
        val file = ForeignFile.read(
            """{"archiveType":"operit_chat_archive","formatVersion":2,"chats":[
              {"title":"重新生成","messages":[
                {"baseMessage":{"sender":"ai","content":"原来的回答","timestamp":123,"selectedVariantIndex":2},
                 "variants":[{"variantIndex":1,"content":"未选的回答"},{"variantIndex":2,"content":"选中的回答"}]}]}]}""",
        )
        val message = file.chats.single().messages.single()
        assertEquals("选中的回答", message.content)
        assertEquals(123L, message.at)
        assertFalse(message.fromMe)
    }

    @Test
    fun missingSelectedVariantDoesNotSilentlyImportRejectedAnswer() {
        val error = assertThrows(ImportException::class.java) {
            ForeignFile.read(
                """{"archiveType":"operit_chat_archive","formatVersion":2,"chats":[
                  {"messages":[{"baseMessage":{"sender":"ai","content":"已否定的回答","selectedVariantIndex":1}}]}]}""",
            )
        }
        assertTrue(error.message.orEmpty().contains("已选中的回复版本"))
    }

    @Test
    fun unsupportedVersionDoesNotFallBackToMemory() {
        val error = assertThrows(ImportException::class.java) {
            ForeignFile.read("""{"archiveType":"operit_chat_archive","formatVersion":99,"chats":[]}""")
        }
        assertTrue(error.message.orEmpty().contains("版本暂不支持"))
    }

    @Test
    fun malformedLaterConversationFailsBeforeAnyDataCanBeImported() {
        val error = assertThrows(ImportException::class.java) {
            ForeignFile.read(
                """{"archiveType":"operit_chat_archive","formatVersion":2,"chats":[
                  {"title":"有效","messages":[{"baseMessage":{"sender":"user","content":"你好"}}]},
                  {"title":"损坏","messages":[{"content":"没有 baseMessage"}]}]}""",
            )
        }
        assertTrue(error.message.orEmpty().contains("baseMessage"))
    }

    @Test
    fun emptyArchiveExplainsThatThereIsNoTextToImport() {
        val error = assertThrows(ImportException::class.java) {
            ForeignFile.read("""{"archiveType":"operit_chat_archive","formatVersion":2,"chats":[]}""")
        }
        assertTrue(error.message.orEmpty().contains("没有可导入的文字聊天"))
    }

    @Test
    fun archiveCanCoexistWithOtherModulesInOneFile() {
        val file = ForeignFile.read(
            """记忆库
              {"memories":[{"title":"称呼","content":"叫我小七"}]}
              历史聊天
              {"archiveType":"operit_chat_archive","formatVersion":2,"chats":[
                {"title":"归档","messages":[{"baseMessage":{"sender":"user","content":"你好"}}]}]}
            """.trimIndent(),
        )
        assertEquals(1, file.memories.size)
        assertEquals("归档", file.chats.single().title)
    }

    @Test
    fun resultReportsAllConversationsInsteadOfOneMergedChat() {
        val result = ForeignImportResult(chatMessages = 4, chatConversations = 2)
        assertTrue(result.said().contains("2 段对话，共 4 句聊天"))
    }
}
