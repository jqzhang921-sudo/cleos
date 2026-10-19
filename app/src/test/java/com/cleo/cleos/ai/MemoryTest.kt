package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.MemoryDetails
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.MemoryDao
import com.cleo.cleos.data.db.MemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MemoryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 9, 24, 20, 0).atZone(zone).toInstant().toEpochMilli()
    private val dao = FakeMemories()
    private val book = MemoryBook(dao, clock = { now })

    private fun act(json: String, ta: Long = 1): ToolOutcome = runBlocking {
        try {
            book.act(ToolArgs.parse(json) ?: JsonObject(emptyMap()), ta)
        } catch (f: ToolFailure) {
            ToolOutcome(f.result, "没成：" + f.note)
        }
    }

    @Test
    fun rememberOpenUpdateForget() {
        val made = act("""{"action":"remember","category":"profile","name":"称呼","summary":"喜欢被叫小名","details":["别叫全名"]}""")
        assertEquals("记下了「称呼」", made.note)
        val id = dao.rows.single().id
        val opened = act("""{"action":"open","id":$id}""")
        assertTrue(opened.result, opened.result.startsWith("【#$id 称呼】喜欢被叫小名\n1. 别叫全名"))
        assertEquals("改了记忆「称呼」", act("""{"action":"update","id":"#$id","add_detail":"朋友叫小雨"}""").note)
        assertEquals(listOf("别叫全名", "朋友叫小雨"), MemoryDetails.decode(dao.rows.single().details))
        act("""{"action":"update","id":$id,"set_details":["只留这一条"],"summary":"喜欢被叫小名，不喜欢全名"}""")
        assertEquals(listOf("只留这一条"), MemoryDetails.decode(dao.rows.single().details))
        assertEquals("喜欢被叫小名，不喜欢全名", dao.rows.single().summary)
        assertEquals("忘掉了「称呼」", act("""{"action":"forget","id":$id}""").note)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun anotherTasMemoryIsNotThere() {
        act("""{"action":"remember","category":"profile","name":"称呼","summary":"喜欢被叫小名"}""", ta = 1)
        val id = dao.rows.single().id
        assertEquals("没成：没找到这一条", act("""{"action":"open","id":$id}""", ta = 2).note)
        assertEquals("没成：没找到这一条", act("""{"action":"forget","id":$id}""", ta = 2).note)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun aFullKindMakesItMergeInsteadOfPilingUp() {
        repeat(MemoryKinds.PER_KIND) { i ->
            act("""{"action":"remember","category":"interest","name":"话题$i","summary":"第 $i 件"}""")
        }
        assertEquals("没成：这一类记满了", act("""{"action":"remember","category":"interest","name":"又一件","summary":"放不下"}""").note)
        // Another kind still has room.
        assertEquals("记下了「称呼」", act("""{"action":"remember","category":"profile","name":"称呼","summary":"小名"}""").note)
        val id = dao.rows.first().id
        act("""{"action":"update","id":$id,"set_details":${(1..MemoryKinds.DETAILS).map { "\"d$it\"" }}}""")
        assertEquals("没成：细节记满了", act("""{"action":"update","id":$id,"add_detail":"再一条"}""").note)
    }

    @Test
    fun whatThePersonPinnedCanBeReadButNotChanged() {
        dao.rows += MemoryEntity(id = 7, companionId = 1, kind = "rapport", name = "别反问", summary = "不喜欢被反问", pinned = true, source = MemoryEntity.SOURCE_ME, createdAt = 1, updatedAt = 1)
        assertTrue(act("""{"action":"open","id":7}""").result.contains("对方钉住的"))
        assertEquals("没成：那条是钉住的", act("""{"action":"update","id":7,"summary":"改掉"}""").note)
        assertEquals("没成：那条是钉住的", act("""{"action":"forget","id":7}""").note)
        assertEquals("不喜欢被反问", dao.rows.single().summary)
    }

    @Test
    fun badCallsSayWhatWasMissing() {
        assertEquals("没成：不知道要做什么", act("""{"action":"write"}""").note)
        assertEquals("没成：没说记在哪一类", act("""{"action":"remember","name":"a","summary":"b"}""").note)
        assertEquals("没成：没写名字或摘要", act("""{"action":"remember","category":"self","name":"a"}""").note)
        assertEquals("没成：不知道是哪一条", act("""{"action":"open"}""").note)
        act("""{"action":"remember","category":"self","name":"那次横幅","summary":"做过一个挡住输入框的提示"}""")
        assertEquals("没成：没说改什么", act("""{"action":"update","id":${dao.rows.single().id}}""").note)
        // Details sent as one string, or as a JSON array inside a string, still count.
        act("""{"action":"remember","category":"profile","name":"x","summary":"y","details":"一条"}""")
        act("""{"action":"remember","category":"profile","name":"z","summary":"w","details":"[\"甲\",\"乙\"]"}""")
        assertEquals(listOf("一条"), MemoryDetails.decode(dao.rows.first { it.name == "x" }.details))
        assertEquals(listOf("甲", "乙"), MemoryDetails.decode(dao.rows.first { it.name == "z" }.details))
    }

    @Test
    fun theDigestSaysWhatEachIsAboutNotWhatItSays() {
        val m = listOf(
            MemoryEntity(id = 3, companionId = 1, kind = "self", name = "横幅", summary = "做过挡住输入框的提示", createdAt = 1, updatedAt = 1),
            MemoryEntity(id = 1, companionId = 1, kind = "profile", name = "称呼", summary = "喜欢被叫小名", details = MemoryDetails.encode(listOf("别叫全名", "朋友叫小雨")), pinned = true, createdAt = 1, updatedAt = 1),
            MemoryEntity(id = 2, companionId = 1, kind = "recent", name = "工作", summary = "这周很忙", createdAt = 1, updatedAt = now),
        )
        val chat = MemoryDigest.forChat(m, zone)!!
        assertTrue(chat.indexOf("对方的基本情况：") < chat.indexOf("对方最近的情况："))
        assertTrue(chat.indexOf("对方最近的情况：") < chat.indexOf("关于你自己："))
        assertTrue(chat.contains("\n- [#1] 称呼：喜欢被叫小名（2 条细节）【对方钉住的：不要改，也不要删】"))
        assertTrue(chat.contains("\n- [#2] 工作：这周很忙（9月24日记下）（还没有细节）"))
        assertFalse("details stay out until opened", chat.contains("别叫全名"))
        val letter = MemoryDigest.forLetter(m, zone)!!
        assertTrue(letter.contains("- 称呼：喜欢被叫小名\n  · 别叫全名\n  · 朋友叫小雨"))
        assertFalse(letter.contains("[#"))
        assertNull(MemoryDigest.forChat(emptyList(), zone))
    }

    @Test
    fun aLetterTakesTheNewestDetailsWhenThereAreTooMany() {
        fun topic(id: Long, name: String, details: List<String>) = MemoryEntity(
            id = id, companionId = 1, kind = "interest", name = name, summary = name,
            details = MemoryDetails.encode(details), createdAt = id, updatedAt = id,
        )
        fun full(tag: String) = (1..MemoryKinds.DETAILS).map { "$tag$it".padEnd(300, '。') }
        // Two full topics of long details, together past the budget, and one with a single short one.
        val m = listOf(topic(1, "书", full("书")), topic(2, "歌", full("歌")), topic(3, "茶", listOf("只喝绿茶")))
        val letter = MemoryDigest.forLetter(m, zone)!!
        val kept = letter.lines().filter { it.startsWith("  · ") }
        assertTrue("within the budget", kept.sumOf { it.length - 4 } <= MemoryDigest.LETTER_DETAILS)
        assertTrue("the one with little keeps all of it", letter.contains("- 茶：茶\n  · 只喝绿茶"))
        // What is left is shared evenly, the newest kept, in the order they were written.
        val books = kept.filter { it.startsWith("  · 书") }
        assertEquals(books.size, kept.count { it.startsWith("  · 歌") })
        assertTrue(books.size in 2 until MemoryKinds.DETAILS)
        assertTrue(books.last().startsWith("  · 书${MemoryKinds.DETAILS}。"))
        assertFalse(letter.contains("  · 书1。"))
        assertEquals(books.sortedBy { it.removePrefix("  · 书").takeWhile(Char::isDigit).toInt() }, books)
    }

    @Test
    fun theSystemPromptEndsWithTheMemories() {
        val ta = CompanionEntity(id = 1, name = "沐", apiBaseUrl = "", apiModel = "", createdAt = 0)
        val m = listOf(MemoryEntity(id = 1, companionId = 1, kind = "profile", name = "称呼", summary = "小名", createdAt = 1, updatedAt = 1))
        val on = Prompt.system(AppSettings(), ta, setOf(ToolGroup.Memory), m, zone)
        assertTrue("the rules come with the tool", on.contains("动手，别宣布"))
        assertTrue("last, so a new memory doesn't break the cached prefix before it", on.endsWith("- [#1] 称呼：小名（还没有细节）"))
        val off = Prompt.system(AppSettings(), ta, emptySet(), m, zone)
        assertFalse(off.contains("称呼") || off.contains("动手，别宣布"))
        val letter = LetterPrompt.system(AppSettings(), ta, own = true, memories = m, zone = zone)
        assertTrue(letter.contains("你长期记着的事：\n对方的基本情况：\n- 称呼：小名"))
    }

    @Test
    fun theBlockAnImportLeftInAPersonaBecomesTopics() {
        val persona = "说话简短。\n\n" + PersonaMemory.HEADER + "\n" +
            "对方的基本情况：\n- 称呼：喜欢被叫小名\n  · 别叫全名\n- 不喜欢被反问\n" +
            "对方最近的情况（记下的时候是这样，可能已经过去了）：\n- 工作：这周很忙（9月20日记下）\n" +
            "对方希望你怎么相处：\n- 吵架后先道歉\n  · 说过一次"
        val (left, topics) = PersonaMemory.split(persona)
        assertEquals("说话简短。", left)
        assertEquals(listOf("profile", "profile", "recent", "rapport"), topics.map { it.kind })
        assertEquals(PersonaMemory.Topic("profile", "称呼", "喜欢被叫小名", listOf("别叫全名")), topics[0])
        assertEquals("the date added when it was written is not part of the summary", "这周很忙", topics[2].summary)
        assertEquals(java.time.MonthDay.of(9, 20), topics[2].recorded)
        assertEquals(listOf("说过一次"), topics[3].details)
        assertEquals("a persona without the block stays as it is", "只有性格" to emptyList<PersonaMemory.Topic>(), PersonaMemory.split("只有性格"))
    }
}

private class FakeMemories : MemoryDao {
    val rows = mutableListOf<MemoryEntity>()
    override fun observeFor(companionId: Long): Flow<List<MemoryEntity>> = flowOf(rows.filter { it.companionId == companionId })
    override suspend fun allFor(companionId: Long) = rows.filter { it.companionId == companionId }
    override suspend fun selectedFor(companionId: Long, ids: List<Long>) = rows.filter { it.companionId == companionId && it.id in ids }
    override fun observe(id: Long): Flow<MemoryEntity?> = flowOf(rows.firstOrNull { it.id == id })
    override suspend fun get(id: Long) = rows.firstOrNull { it.id == id }
    override suspend fun insert(memory: MemoryEntity): Long {
        val id = (rows.maxOfOrNull { it.id } ?: 0) + 1
        rows += memory.copy(id = id)
        return id
    }
    override suspend fun update(memory: MemoryEntity) {
        rows.replaceAll { if (it.id == memory.id) memory else it }
    }
    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }
    override suspend fun deleteFor(companionId: Long) {
        rows.removeAll { it.companionId == companionId }
    }
    override suspend fun all() = rows.toList()
    override suspend fun insertAll(items: List<MemoryEntity>) {
        rows += items
    }
    override suspend fun clear() = rows.clear()
}
