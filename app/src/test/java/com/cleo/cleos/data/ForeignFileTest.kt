package com.cleo.cleos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Files from other apps: a 角色卡, a 世界书, a 记忆库, a 聊天记录 — one of them, or several in
 * one file. Everything that is none of the four is still read as memory, the way it always was.
 */
class ForeignFileTest {
    @Test
    fun aCharacterCardIsReadAsACard() {
        val out = ForeignFile.read(
            """
            {"id":"c1","name":"林夏","description":"安静的人","personality":"话少","scenario":"大学里",
             "system_prompt":"别叫她姐姐","greeting_message":"嗨，你来啦","created_at":1}
            """.trimIndent(),
        )
        val card = out.cards.single()
        assertEquals("林夏", card.name)
        assertEquals("嗨，你来啦", card.greeting)
        assertTrue(card.persona.contains("安静的人"))
        assertTrue(card.persona.contains("性格：话少"))
        assertTrue(card.persona.contains("场景：大学里"))
        assertTrue(card.persona.contains("别叫她姐姐"))
        // A card is not a book and not a memory: it makes a TA, and nothing else comes with it.
        assertTrue(out.lore.isEmpty())
        assertTrue(out.memories.isEmpty())
    }

    @Test
    fun anOperitCardIsReadAsACard() {
        // Operit's own card, as its database keeps it and its card backup writes it out.
        val out = ForeignFile.read(
            """
            {"id":"c1","name":"洺洺","description":"一起用小红书的人","characterSetting":"你是洺洺，说话短",
             "openingStatement":"在吗","otherContentChat":"嗯。",
             "attachedTags":[{"name":"口癖","promptContent":"不要说「好的」"}],
             "chatModelBindingMode":"FOLLOW_GLOBAL","createdAt":1}
            """.trimIndent(),
        )
        val card = out.cards.single()
        assertEquals("洺洺", card.name)
        assertEquals("在吗", card.greeting)
        assertTrue(card.persona.contains("一起用小红书的人"))
        assertTrue(card.persona.contains("你是洺洺，说话短"))
        assertTrue(card.persona.contains("不要说「好的」"))
        // Which model it talks through, and what it may call, is Operit's business, not this app's.
        assertTrue(!card.persona.contains("FOLLOW_GLOBAL"))
        assertTrue(out.memories.isEmpty())
    }

    @Test
    fun aBackupOfEveryCardIsOneCardAfterAnother() {
        val out = ForeignFile.read(
            """
            {"characterCards":[
               {"id":"c1","name":"洺洺","characterSetting":"说话短","openingStatement":"在吗"},
               {"id":"c2","name":"夏以昼","characterSetting":"话多","openingStatement":"早"}],
             "promptTags":[{"id":"t1","name":"口癖"}]}
            """.trimIndent(),
        )
        assertEquals(listOf("洺洺", "夏以昼"), out.cards.map { it.name })
        assertEquals("早", out.cards[1].greeting)
        assertTrue(out.lore.isEmpty())
        assertTrue(out.memories.isEmpty())
    }

    @Test
    fun aTavernCardKeepsItsFieldsUnderData() {
        // What Operit writes when one of its cards is exported as a 酒馆 card, and what other apps
        // write; the world book, when there is one, sits under `data` with the rest of it.
        val out = ForeignFile.read(
            """
            {"spec":"chara_card_v2","spec_version":"2.0","data":{
              "name":"林夏","description":"安静的人","personality":"","first_mes":"嗨","mes_example":"",
              "scenario":"","system_prompt":"别叫她姐姐","creator_notes":"","tags":[],
              "character_book":{"name":"世界观","entries":[{"keys":["学校"],"content":"学校在海边","comment":"学校"}]}}}
            """.trimIndent(),
        )
        val card = out.cards.single()
        assertEquals("林夏", card.name)
        assertEquals("嗨", card.greeting)
        assertTrue(card.persona.contains("别叫她姐姐"))
        assertEquals("世界观", out.lore.single().book)
    }

    @Test
    fun aFileThatIsNoneOfTheFourSaysWhatItHasInIt() {
        // The one thing a person can act on, or send on, when a file reads as nothing at all.
        val e = assertThrows(ImportException::class.java) { ForeignFile.read("""{"whatever":[1],"else":2}""") }
        val said = e.message.orEmpty()
        assertTrue(said.contains("whatever"))
        assertTrue(said.contains("else"))
    }

    @Test
    fun aCardCarryingAWorldBookBringsBoth() {
        val out = ForeignFile.read(
            """
            {"name":"林夏","personality":"话少",
             "character_book":{"name":"世界观","entries":[
               {"keys":["学校"],"content":"学校在海边","comment":"学校","insertion_order":2}]}}
            """.trimIndent(),
        )
        assertEquals("林夏", out.card?.name)
        assertEquals(1, out.lore.size)
        assertEquals("世界观", out.lore[0].book)
    }

    @Test
    fun aWorldBookKeepsItsEntriesAndTheirKeywords() {
        val out = ForeignFile.read(
            """
            {"id":"b1","name":"世界观","description":"这本书讲什么",
             "entries":[
               {"id":"e1","keys":["学校","大学"],"secondary_keys":["宿舍"],"content":"学校在海边",
                "comment":"学校","enabled":true,"constant":false,"selective":false,"insertion_order":3},
               {"id":"e2","keys":["旧历"],"content":"一年三百天","comment":"历法","enabled":false,"insertion_order":4},
               {"id":"e3","keys":["空的"],"content":"","comment":"没有内容"}]}
            """.trimIndent(),
        )
        assertNull(out.card)
        assertEquals(2, out.lore.size)
        val school = out.lore[0]
        assertEquals("世界观", school.book)
        assertEquals("学校", school.title)
        assertEquals(listOf("学校", "大学", "宿舍"), school.keys)
        assertEquals("学校在海边", school.content)
        assertEquals(3, school.position)
        assertTrue(school.enabled)
        // Switched off in the app it came from stays switched off here.
        assertTrue(!out.lore[1].enabled)
    }

    @Test
    fun aMemoryStoreBecomesTopics() {
        val out = ForeignFile.read(
            """
            {"memories":[{"id":"m1","title":"称呼","content":"喜欢被叫小名","category":"profile","tags":["亲昵"]}],
             "user_profile":{"name":"小七","persona":"在上海读大学"}}
            """.trimIndent(),
        )
        assertNull(out.card)
        assertTrue(out.lore.isEmpty())
        assertEquals(2, out.memories.size)
        assertEquals("称呼", out.memories[0].name)
        assertEquals("喜欢被叫小名", out.memories[0].summary)
        assertEquals(listOf("亲昵"), out.memories[0].details)
        assertEquals("人设", out.memories[1].name)
        assertEquals("在上海读大学", out.memories[1].summary)
    }

    @Test
    fun aChatLogBecomesOneConversation() {
        val out = ForeignFile.read(
            """
            {"session_id":"s1","title":"那个夏天","messages":[
              {"id":"1","role":"user","content":"在吗","timestamp":100},
              {"id":"2","role":"assistant","content":"在呀","timestamp":200},
              {"id":"3","role":"system","content":"You are a helpful assistant"}]}
            """.trimIndent(),
        )
        assertNull(out.card)
        assertEquals(1, out.chats.size)
        assertEquals("那个夏天", out.chats[0].title)
        val said = out.chats[0].messages
        // What the app wrote into the log itself is not a line anyone said.
        assertEquals(2, said.size)
        assertEquals("在吗", said[0].content)
        assertTrue(said[0].fromMe)
        assertEquals(100L, said[0].at)
        assertEquals("在呀", said[1].content)
        assertTrue(!said[1].fromMe)
    }

    @Test
    fun aFileWithFourModulesReadsAllOfThem() {
        val out = ForeignFile.read(
            """
            角色卡
            {"name":"林夏","personality":"话少","greeting_message":"嗨"}
            世界书
            {"id":"b1","name":"世界观","entries":[{"keys":["学校"],"content":"学校在海边","comment":"学校"}]}
            记忆库
            {"memories":[{"title":"称呼","content":"喜欢被叫小名"}]}
            历史聊天
            {"title":"那个夏天","messages":[{"role":"user","content":"在吗","timestamp":5}]}
            """.trimIndent(),
        )
        assertEquals("林夏", out.card?.name)
        assertEquals(1, out.lore.size)
        assertEquals(1, out.memories.size)
        assertEquals(1, out.chats.size)
        assertEquals("世界观", out.books.single())
    }

    @Test
    fun aMemoryFileUsingEntriesIsNotAWorldBook() {
        // No keywords, no comment: the same wrapper a memory file uses. It stays memory.
        val out = ForeignFile.read("""{"entries":[{"title":"称呼","content":"喜欢被叫小名"}]}""")
        assertTrue(out.lore.isEmpty())
        assertEquals(1, out.memories.size)
        assertEquals("称呼", out.memories[0].name)
    }

    @Test
    fun plainNotesAreStillReadAsMemory() {
        val out = ForeignFile.read("喜欢喝咖啡\n\n- 住在上海\n- 养了一只猫")
        assertNull(out.card)
        assertTrue(out.lore.isEmpty())
        assertTrue(out.chats.isEmpty())
        assertEquals(listOf("喜欢喝咖啡", "住在上海", "养了一只猫"), out.memories.map { it.name })
    }

    @Test
    fun anEmptyFileSaysSo() {
        assertThrows(ImportException::class.java) { ForeignFile.read("   \n ") }
    }

    @Test
    fun keysAreWrittenOneLineAndSplitBackIntoWords() {
        val words = LoreKeys.parse("星历、旧历, 历法;星历")
        assertEquals(listOf("星历", "旧历", "历法"), words)
        assertEquals("星历、旧历、历法", LoreKeys.line(words))
        assertEquals(words, LoreKeys.decode(LoreKeys.encode(words)))
        assertEquals(emptyList<String>(), LoreKeys.decode(null))
        assertTrue(LoreKeys.parse("").isEmpty())
    }
}
