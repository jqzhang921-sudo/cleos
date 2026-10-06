package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.Companions
import com.cleo.cleos.data.DiaryBlock
import com.cleo.cleos.data.DiaryBlocks
import com.cleo.cleos.data.db.DiaryDao
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.data.db.LetterEntity
import com.cleo.cleos.data.db.TodoDao
import com.cleo.cleos.data.db.TodoEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ToolBoxTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val nowMillis = ZonedDateTime.of(2026, 9, 23, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val today = LocalDate.of(2026, 9, 23)
    private val todos = FakeTodos()
    private val diary = FakeDiary()
    private val weatherCalls = mutableListOf<String>()
    private val weather = object : WeatherSource {
        override suspend fun report(city: String, days: Int): WeatherReport {
            weatherCalls += city
            return WeatherReport(city, "地点：$city\n现在：晴")
        }
    }
    /** Requests to see a secret, with the TA who asked. */
    private val asked = mutableListOf<Pair<Long, SecretRequest>>()
    private val letters = mutableListOf<LetterEntity>()
    private val box = ToolBox(
        todos,
        diary,
        weather,
        requests = { ta -> asked.filter { it.first == ta }.map { it.second } },
        letters = { ta -> letters.filter { it.companionId == ta } },
        clock = { nowMillis },
        zone = { zone },
    )
    private val all = AppSettings(tools = ToolGroup.entries.toSet())

    private fun run(name: String, args: String, settings: AppSettings = all, companionId: Long = Companions.FIRST) =
        runBlocking { box.run(ToolCall("c", name, args), settings, companionId = companionId) }

    @Test
    fun partialShareKeepsTheRestLockedAndRejectsInventedExcerpts() {
        run("write_diary", """{"text":"愿意分享这句。后面的事还想留给自己。","secret":true}""", companionId = 2)
        val id = diary.rows.single().id
        val shared = run("share_my_secret", """{"id":$id,"mode":"partial","excerpt":"愿意分享这句。"}""", companionId = 2)
        assertEquals("愿意分享这句。", shared.sharedExcerpt)
        assertTrue(diary.rows.single().lockedForUser)
        assertEquals("愿意分享这句。", diary.rows.single().sharedExcerpt)
        assertNull(run("share_my_secret", """{"id":$id,"mode":"partial","excerpt":"编造的原文"}""", companionId = 2).sharedDiaryId)
        assertNull(run("share_my_secret", """{"id":$id,"mode":"partial","excerpt":""}""", companionId = 2).sharedDiaryId)
        assertNull(run("share_my_secret", """{"id":$id,"mode":"unknown"}""", companionId = 2).sharedDiaryId)
        assertEquals("愿意分享这句。", diary.rows.single().sharedExcerpt)
        run("share_my_secret", """{"id":$id}""", companionId = 2)
        assertFalse(diary.rows.single().lockedForUser)
    }

    @Test
    fun secretExcerptCardKeepsOnlyTheSharedTextThroughStorage() {
        val share = SecretShare(9, "只分享这句\n第二行")
        assertEquals(share, SecretShares.decode(SecretShares.encode(share)))
        assertNull(SecretShares.decode("broken"))
    }

    @Test
    fun aiSecretStaysLockedUntilItsOwnAuthorSharesIt() {
        val written = run("write_diary", """{"title":"隐藏标题","text":"私密正文","secret":true,"public_hint":"还想留给自己"}""", companionId = 2)
        val entry = diary.rows.single()
        assertTrue(entry.lockedForUser)
        assertEquals("还想留给自己", entry.publicHint)
        assertFalse(written.note.contains("隐藏标题"))
        assertFalse(written.result.contains("隐藏标题"))
        assertNull(run("share_my_secret", """{"id":${entry.id}}""", companionId = 3).sharedDiaryId)
        assertTrue(diary.rows.single().lockedForUser)
        val shared = run("share_my_secret", """{"id":${entry.id}}""", companionId = 2)
        assertEquals(entry.id, shared.sharedDiaryId)
        assertFalse(diary.rows.single().lockedForUser)
        assertTrue(diary.rows.single().secret)
    }

    @Test
    fun ownSecretsCanBeReadBackButNotRequestedAsThePersonsSecrets() {
        run("write_diary", """{"text":"自己的正文","secret":true}""", companionId = 2)
        val id = diary.rows.single().id
        assertTrue(run("read_diary", "{}", companionId = 2).result.contains("自己的正文"))
        assertFalse(run("read_diary", "{}", companionId = 3).result.contains("自己的正文"))
        assertNull(run("request_secret", """{"id":$id}""", companionId = 2).request)
        assertTrue(run("list_secrets", "{}", companionId = 2).result.contains("没有小秘密"))
    }

    @Test
    fun normalDiaryAndThePersonsSecretCannotBeUnlockedByAi() {
        run("write_diary", """{"text":"普通日记"}""")
        assertNull(run("share_my_secret", """{"id":1}""").sharedDiaryId)
        diary.rows += DiaryEntryEntity(id = 9, day = 1, title = "", blocks = "[]", createdAt = 1, updatedAt = 1, secret = true)
        assertNull(run("share_my_secret", """{"id":9}""").sharedDiaryId)
        assertFalse(diary.rows.last().secretShared)
    }

    @Test
    fun addTodoStoresItAndSaysWhere() {
        val out = run("add_todo", """{"title":" 交报告 ","due":"2026-9-24","note":"发到邮箱"}""")
        val t = todos.rows.single()
        assertEquals("交报告", t.title)
        assertEquals(LocalDate.of(2026, 9, 24).toEpochDay(), t.dueDay)
        assertEquals("发到邮箱", t.note)
        assertTrue(out.result, out.result.contains("#${t.id} 交报告"))
        assertTrue("the model can check its date arithmetic", out.result.contains("2026-09-24（周四，明天）"))
        assertEquals("记下了待办「交报告」 · 9月24日", out.note)
    }

    @Test
    fun aDateItCannotReadIsSentBackNotGuessed() {
        val out = run("add_todo", """{"title":"交报告","due":"明天"}""")
        assertTrue(todos.rows.isEmpty())
        assertEquals("记待办没成：日期没写对", out.note)
        assertTrue(out.result.contains("2026-09-24"))
    }

    @Test
    fun listShowsDatedFirstAndFlagsOverdue() {
        todos.rows += TodoEntity(id = 1, title = "买牛奶", createdAt = 1)
        todos.rows += TodoEntity(id = 2, title = "交报告", dueDay = today.minusDays(3).toEpochDay(), createdAt = 2)
        todos.rows += TodoEntity(id = 3, title = "洗衣服", done = true, doneAt = nowMillis, createdAt = 3)
        val out = run("list_todos", "")
        val lines = out.result.lines()
        assertEquals("没做完的 2 条：", lines[0])
        assertTrue(lines[1], lines[1].startsWith("#2 交报告") && lines[1].endsWith("已过期"))
        assertTrue(lines[2].startsWith("#1 买牛奶"))
        assertFalse(out.result.contains("洗衣服"))
        assertEquals("看了一眼待办（2 条没做完）", out.note)
        assertTrue(run("list_todos", """{"include_done":true}""").result.contains("#3 洗衣服"))
    }

    @Test
    fun tickingATodoAndEmptyFieldsTouchNothingElse() {
        todos.rows += TodoEntity(id = 7, title = "交报告", note = "发到邮箱", dueDay = today.toEpochDay(), createdAt = 1)
        // Weaker models fill every optional field with "".
        val out = run("update_todo", """{"id":"#7","done":true,"title":"","note":"","due":""}""")
        val t = todos.rows.single()
        assertTrue(t.done)
        assertEquals(nowMillis, t.doneAt)
        assertEquals("发到邮箱", t.note)
        assertEquals(today.toEpochDay(), t.dueDay)
        assertEquals("把「交报告」打了勾", out.note)
    }

    @Test
    fun noneClearsADate() {
        todos.rows += TodoEntity(id = 7, title = "交报告", dueDay = today.toEpochDay(), createdAt = 1)
        val out = run("update_todo", """{"id":7,"due":"none"}""")
        assertNull(todos.rows.single().dueDay)
        assertEquals("改了待办「交报告」", out.note)
    }

    @Test
    fun anUnknownIdSaysSo() {
        val out = run("update_todo", """{"id":99,"done":true}""")
        assertEquals("改待办没成：没找到这一条", out.note)
        assertTrue(out.result.contains("list_todos"))
    }

    @Test
    fun thePersonsDiaryStaysClosedUnlessAllowed() {
        diary.rows += entry(1, today, "今天", "去了河边")
        diary.rows += entry(2, today, "我的", "今天聊到了河边", author = DiaryEntryEntity.AUTHOR_AI)
        // By default the model reads back only what it wrote itself.
        val own = run("read_diary", "{}", AppSettings())
        assertFalse(own.result.contains("去了河边"))
        assertTrue(own.result.contains("今天聊到了河边"))
        assertTrue(own.result.contains("· 你写的】"))
        val off = run("read_diary", "{}", AppSettings(tools = setOf(ToolGroup.Todos)))
        assertEquals("翻日记没成：设置里关着", off.note)
    }

    @Test
    fun oneTaNeverReadsAnothersDiary() {
        diary.rows += entry(1, today, "今天", "去了河边")
        diary.rows += entry(2, today, "沐的", "今天聊到了河边", author = DiaryEntryEntity.AUTHOR_AI, companionId = 1)
        diary.rows += entry(3, today, "星的", "今天一起听了雨", author = DiaryEntryEntity.AUTHOR_AI, companionId = 2)
        for (args in listOf("{}", """{"date":"2026-09-23"}""", """{"query":"今天"}""")) {
            val second = run("read_diary", args, companionId = 2).result
            assertTrue("the person's diary: the same rules for every TA", second.contains("去了河边"))
            assertTrue(second.contains("听了雨"))
            assertFalse(args, second.contains("聊到了河边") || second.contains("沐的"))
            val first = run("read_diary", args).result
            assertTrue(first.contains("聊到了河边"))
            assertFalse(args, first.contains("听了雨") || first.contains("星的"))
        }
    }

    @Test
    fun aSecretIsNeverReadButItsDayMentionsIt() {
        diary.rows += entry(1, today, "今天", "去了河边")
        diary.rows += entry(2, today, "不能说", "其实我有点想哭", secret = true)
        val out = run("read_diary", """{"date":"2026-09-23"}""")
        assertTrue(out.result.contains("去了河边"))
        assertFalse(out.result.contains("想哭"))
        assertFalse("not even the title", out.result.contains("不能说"))
        assertTrue(out.result.contains("这天对方还写了 1 个小秘密"))
        assertEquals("在日记里找了「想哭」：没找到", run("read_diary", """{"query":"想哭"}""").note)
    }

    @Test
    fun theModelWritesItsOwnEntryForToday() {
        val out = run("write_diary", """{"title":"下雨天","text":"今天记了待办，我也想记点什么。"}""")
        val e = diary.rows.single()
        assertEquals(DiaryEntryEntity.AUTHOR_AI, e.author)
        assertEquals(Companions.FIRST, e.companionId)
        assertEquals(today.toEpochDay(), e.day)
        assertFalse(e.secret)
        assertEquals("写了一篇日记「下雨天」", out.note)
        assertEquals("写日记没成：没有内容", run("write_diary", """{"title":"空的"}""").note)
        run("write_diary", """{"text":"第一次写。"}""", companionId = 2)
        assertEquals(2L, diary.rows.last().companionId)
    }

    @Test
    fun secretsAreListedByDateOnlyWithWhatWasAnswered() {
        diary.rows += entry(4, today.minusDays(1), "昨天的事", "内容", secret = true)
        diary.rows += entry(7, today, "今天的事", "内容", secret = true)
        diary.rows += entry(9, today, "不是秘密", "内容")
        asked += Companions.FIRST to SecretRequest(diaryId = 4, day = today.minusDays(1).toEpochDay(), status = SecretRequest.DECLINED)
        val out = run("list_secrets", "{}")
        assertTrue(out.result.contains("对方有 2 个小秘密"))
        assertTrue(out.result.contains("#4 2026-09-22（周二，昨天） · 你问过，对方没给看"))
        assertTrue(out.result.contains("#7 2026-09-23"))
        assertFalse(out.result.contains("事"))
        assertEquals("数了数你的小秘密：2 个", out.note)
        // Another TA sees the same secrets, but not what the first one asked.
        val second = run("list_secrets", "{}", companionId = 2).result
        assertTrue(second.contains("对方有 2 个小秘密"))
        assertFalse(second.contains("你问过"))
    }

    @Test
    fun askingPutsACardToThePersonAndTellsTheModelNothing() {
        diary.rows += entry(7, today, "今天的事", "其实我有点想哭", secret = true)
        val out = run("request_secret", """{"id":7,"reason":"想知道你今天怎么了"}""")
        val card = out.request!!
        assertEquals(7L, card.diaryId)
        assertEquals("今天的事", card.title)
        assertEquals("想知道你今天怎么了", card.reason)
        assertEquals(SecretRequest.PENDING, card.status)
        assertEquals("", out.note)
        assertFalse(out.result.contains("今天的事") || out.result.contains("想哭"))
        // Asking again while the card waits makes no second card; another TA can still ask.
        asked += Companions.FIRST to card
        assertNull(run("request_secret", """{"id":7}""").request)
        assertEquals(7L, run("request_secret", """{"id":7}""", companionId = 2).request?.diaryId)
    }

    @Test
    fun onlySecretsCanBeAskedFor() {
        diary.rows += entry(9, today, "普通的", "内容")
        val out = run("request_secret", """{"id":9}""")
        assertNull(out.request)
        assertEquals("请求看小秘密没成：没找到这个小秘密", out.note)
    }

    @Test
    fun searchingTheDiaryMatchesWhatWasWrittenNotTheJson() {
        diary.rows += entry(1, today.minusDays(2), "周一", "猫睡在窗台上")
        diary.rows += entry(2, today, "今天", "什么都没发生")
        val cat = run("read_diary", """{"query":"猫"}""")
        assertTrue(cat.result.contains("猫睡在窗台上"))
        assertEquals("在日记里找了「猫」（1 篇）", cat.note)
        // "text" is in every entry's JSON ({"type":"text",...}) but in no one's words.
        assertEquals("在日记里找了「text」：没找到", run("read_diary", """{"query":"text"}""").note)
    }

    @Test
    fun aLongEntryIsCutWithTheRestCounted() {
        diary.rows += entry(1, today, "长", "字".repeat(2000))
        val out = run("read_diary", """{"date":"2026-09-23"}""")
        assertTrue(out.result.contains("……（后面还有 500 字）"))
        assertEquals("读了9月23日的日记", out.note)
    }

    @Test
    fun lettersAreReadAsTheyArrivedNotBefore() {
        val hour = 3_600_000L
        fun letter(id: Long, author: String, text: String, at: Long, deliverAt: Long?, readAt: Long? = null, ta: Long = Companions.FIRST) =
            LetterEntity(id = id, companionId = ta, author = author, content = text, createdAt = at, deliverAt = deliverAt, readAt = readAt)
        assertEquals("翻了翻信：还没有", run("read_letters", "{}").note)
        letters += letter(1, LetterEntity.AUTHOR_ME, "最近睡得不好", nowMillis - 48 * hour, nowMillis - 48 * hour)
        letters += letter(2, LetterEntity.AUTHOR_AI, "那就早点关灯", nowMillis - 46 * hour, nowMillis - 44 * hour, readAt = nowMillis - 30 * hour)
        letters += letter(3, LetterEntity.AUTHOR_ME, "还没写完的草稿", nowMillis - hour, null)
        letters += letter(4, LetterEntity.AUTHOR_AI, "还在路上的回信", nowMillis - hour, nowMillis + 3 * hour)
        letters += letter(5, LetterEntity.AUTHOR_AI, "刚到、还没拆的", nowMillis - 2 * hour, nowMillis - hour)
        letters += letter(6, LetterEntity.AUTHOR_AI, "别的 TA 的信", nowMillis - hour, nowMillis - hour, ta = 2)
        val out = run("read_letters", "{}")
        assertEquals("翻了翻你们的信（3 封）", out.note)
        val r = out.result
        assertFalse(r.contains("草稿") || r.contains("还在路上") || r.contains("别的 TA"))
        assertTrue("newest first", r.indexOf("刚到") < r.indexOf("早点关灯") && r.indexOf("早点关灯") < r.indexOf("睡得不好"))
        assertTrue(r.contains("你写的】（对方还没拆开）\n刚到、还没拆的"))
        assertTrue(r.contains("对方写的】\n最近睡得不好"))
        assertEquals("翻了翻信：还没有", run("read_letters", "{}", companionId = 3).note)
    }

    @Test
    fun weatherFallsBackToTheCityInSettingsAndAsksWhenThereIsNone() {
        assertEquals("查了杭州的天气", run("get_weather", "{}", all.copy(weatherCity = "杭州")).note)
        assertEquals(listOf("杭州"), weatherCalls)
        val none = run("get_weather", "{}")
        assertEquals("查天气没成：不知道在哪个城市", none.note)
        assertEquals(1, weatherCalls.size)
    }

    @Test
    fun brokenArgumentsAndUnknownToolsAreReportedNotThrown() {
        assertEquals("记待办没成：参数写错了", run("add_todo", """{"title":"交""").note)
        assertEquals("想用的工具不存在：delete_everything", run("delete_everything", "{}").note)
    }

    private fun entry(
        id: Long,
        day: LocalDate,
        title: String,
        text: String,
        author: String = DiaryEntryEntity.AUTHOR_ME,
        secret: Boolean = false,
        companionId: Long? = if (author == DiaryEntryEntity.AUTHOR_AI) Companions.FIRST else null,
    ) = DiaryEntryEntity(
        id = id,
        day = day.toEpochDay(),
        title = title,
        blocks = DiaryBlocks.encode(listOf(DiaryBlock.Text(text))),
        createdAt = id,
        updatedAt = id,
        author = author,
        secret = secret,
        companionId = companionId,
    )
}

private class FakeTodos : TodoDao {
    val rows = mutableListOf<TodoEntity>()
    override fun observeAll(): Flow<List<TodoEntity>> = flowOf(rows.toList())
    override fun observeDoneCount(): Flow<Int> = flowOf(rows.count { it.done })
    override suspend fun get(id: Long) = rows.firstOrNull { it.id == id }
    override suspend fun insert(todo: TodoEntity): Long {
        val id = (rows.maxOfOrNull { it.id } ?: 0) + 1
        rows += todo.copy(id = id)
        return id
    }
    override suspend fun upsert(todo: TodoEntity) {
        rows.removeAll { it.id == todo.id }
        rows += todo
    }
    override suspend fun delete(todo: TodoEntity) {
        rows.removeAll { it.id == todo.id }
    }
    override suspend fun all() = rows.toList()
    override suspend fun insertAll(items: List<TodoEntity>) {
        rows += items
    }
    override suspend fun clear() = rows.clear()
}

private class FakeDiary : DiaryDao {
    val rows = mutableListOf<DiaryEntryEntity>()
    private val newest get() = rows.sortedWith(compareByDescending<DiaryEntryEntity> { it.day }.thenByDescending { it.createdAt })
    override fun observeAll(): Flow<List<DiaryEntryEntity>> = flowOf(newest)
    private fun writtenBy(e: DiaryEntryEntity, ta: Long) = e.author == DiaryEntryEntity.AUTHOR_AI && e.companionId == ta
    override fun observeWrittenBy(companionId: Long): Flow<Int> = flowOf(rows.count { writtenBy(it, companionId) })
    override suspend fun deleteWrittenBy(companionId: Long) {
        rows.removeAll { writtenBy(it, companionId) }
    }
    override suspend fun get(id: Long) = rows.firstOrNull { it.id == id }

    // The same visibility as the real queries: never a secret; the person's when [mine];
    // of the TAs' entries, only [own]'s.
    private fun readable(e: DiaryEntryEntity, mine: Boolean, own: Long) =
        (mine && !e.secret && e.author == DiaryEntryEntity.AUTHOR_ME) || writtenBy(e, own)
    override suspend fun onDay(day: Long, mine: Boolean, own: Long) = rows.filter { it.day == day && readable(it, mine, own) }
    override suspend fun recent(mine: Boolean, own: Long, limit: Int) = newest.filter { readable(it, mine, own) }.take(limit)

    /** LIKE '%q%' with ! as the escape character, as the real query. */
    override suspend fun search(pattern: String, mine: Boolean, own: Long, limit: Int): List<DiaryEntryEntity> {
        val q = pattern.removePrefix("%").removeSuffix("%").replace("!%", "%").replace("!_", "_").replace("!!", "!")
        return newest.filter { readable(it, mine, own) && (it.title.contains(q, ignoreCase = true) || it.blocks.contains(q, ignoreCase = true)) }
            .take(limit)
    }
    override suspend fun since(since: Long, mine: Boolean, own: Long, limit: Int) =
        newest.filter { it.createdAt > since && readable(it, mine, own) }.take(limit)
    override suspend fun secrets() = newest.filter { it.secret && it.author == DiaryEntryEntity.AUTHOR_ME }
    override suspend fun secretsOnDay(day: Long) = rows.count { it.secret && it.author == DiaryEntryEntity.AUTHOR_ME && it.day == day }
    override suspend fun insert(entry: DiaryEntryEntity): Long {
        val id = if (entry.id != 0L) entry.id else (rows.maxOfOrNull { it.id } ?: 0) + 1
        rows += entry.copy(id = id)
        return id
    }
    override suspend fun update(entry: DiaryEntryEntity) {
        rows.replaceAll { if (it.id == entry.id) entry else it }
    }
    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }
    override suspend fun all() = rows.toList()
    override suspend fun insertAll(items: List<DiaryEntryEntity>) {
        rows += items
    }
    override suspend fun clear() = rows.clear()
}
