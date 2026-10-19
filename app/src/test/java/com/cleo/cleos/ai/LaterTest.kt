package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.LaterEntity
import com.cleo.cleos.data.decodeTools
import com.cleo.cleos.data.encodeTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.ZoneId
import java.time.ZonedDateTime

class LaterTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val minute = 60_000L

    @Test
    fun aNoteKeepsAsLongAsItWaitedBetweenHalfAnHourAndThreeHours() {
        assertEquals(30 * minute, LaterRules.grace(5 * minute))
        assertEquals(90 * minute, LaterRules.grace(90 * minute))
        assertEquals(180 * minute, LaterRules.grace(20 * 60 * minute))
    }

    @Test
    fun waitsAndTimesReadTheWayPeopleSayThem() {
        assertEquals("40 分钟", LaterRules.span(40))
        assertEquals("2 小时", LaterRules.span(120))
        assertEquals("2 小时 15 分钟", LaterRules.span(135))
        // Under an hour past the days is not worth saying.
        assertEquals("1 天", LaterRules.span(24 * 60 + 20))
        assertEquals("1 天 3 小时", LaterRules.span(27 * 60))
        val now = ZonedDateTime.of(2026, 9, 29, 22, 10, 0, 0, zone)
        assertEquals("今天 22:50", LaterRules.at(now.plusMinutes(40), now))
        assertEquals("明天 08:00", LaterRules.at(now.plusDays(1).withHour(8).withMinute(0), now))
        assertEquals("10月3日 09:00", LaterRules.at(ZonedDateTime.of(2026, 10, 3, 9, 0, 0, 0, zone), now))
    }

    @Test
    fun theWakeSaysWhatWasNotedAndLeavesTheChoice() {
        val now = ZonedDateTime.of(2026, 9, 29, 19, 0, 0, 0, zone)
        val written = now.minusMinutes(40).toInstant().toEpochMilli()
        val note = LaterEntity(
            companionId = 1,
            conversationId = 3,
            what = "问问糖醋排骨做成没有",
            why = "对方说去做饭了。",
            createdAt = written,
            dueAt = now.toInstant().toEpochMilli(),
            expiresAt = now.plusMinutes(40).toInstant().toEpochMilli(),
        )
        val text = LaterRules.wakeText(note, now, canPutOff = true)
        assertTrue(text.startsWith("（这条不是对方发的"))
        assertTrue("「问问糖醋排骨做成没有」" in text)
        assertTrue("记下的时候是今天 18:20，当时对方说去做饭了。" in text)
        assertTrue(Prompt.timeLine(now) in text)
        assertFalse("SKIP" in text)
        assertTrue("直接结束，不输出文字" in text)
        assertTrue("note_for_later" in text)
        // Put off once already: no second time.
        assertFalse("note_for_later" in LaterRules.wakeText(note.copy(putOff = true), now, canPutOff = false))
        // The line it all stands on: bring something, ask for nothing.
        assertTrue("别问「在干嘛」「怎么不回我」" in text)
        // The day ahead only when there is something in it, and as background, not the point.
        assertFalse("日程" in text)
        val withDay = LaterRules.wakeText(note, now, canPutOff = true, glance = "日程：今天 20:30 取快递")
        assertTrue("日程：今天 20:30 取快递" in withDay)
        assertTrue("别念清单" in withDay)
    }

    @Test
    fun skipIsReadWhereverTheModelPutsIt() {
        assertTrue(LaterRules.isSkip("SKIP"))
        assertTrue(LaterRules.isSkip("skip：已经聊过了"))
        assertTrue(LaterRules.isSkip("（SKIP）"))
        assertTrue(LaterRules.isSkip("  SKIP\n"))
        assertFalse(LaterRules.isSkip("饭做好了吗？"))
        assertEquals("已经聊过了", LaterRules.skipReason("SKIP：已经聊过了"))
        assertEquals("", LaterRules.skipReason("（SKIP）"))
        assertEquals("", LaterRules.skipReason("SKIP"))
    }

    @Test
    fun whatCameDueIsNamedEachInItsQuotes() {
        assertNull(LaterRules.dueLine(emptyList()))
        val line = LaterRules.dueLine(listOf("问问考得怎样", "提醒带伞"))!!
        assertTrue("「问问考得怎样」；「提醒带伞」" in line)
    }

    @Test
    fun notingLeavesNoLineInTheChatEvenWhenItFails() = runBlocking {
        var got: List<Any>? = null
        val book = object : LaterBook {
            override suspend fun note(companionId: Long, conversationId: Long, what: String, why: String, minutes: Int): String {
                got = listOf(companionId, conversationId, what, why, minutes)
                return "记下了。"
            }
        }
        val box = ToolBox(unused(), unused(), unused(), later = { book })
        val on = AppSettings().let { it.copy(tools = it.tools + ToolGroup.Later) }
        fun call(args: String) = ToolCall("c", ToolSpecs.noteForLater.name, args)

        val ok = box.run(call("""{"what":"问问做成没有","minutes":"40","why":"去做饭了"}"""), on, conversationId = 7, companionId = 2)
        assertEquals("记下了。", ok.result)
        assertEquals("", ok.note)
        assertEquals(listOf<Any>(2L, 7L, "问问做成没有", "去做饭了", 40), got)
        // Nothing to note, no time, switched off, garbled: the model hears why, the chat shows nothing.
        val empty = box.run(call("""{"minutes":40}"""), on)
        assertTrue(empty.result.startsWith("缺少 what"))
        assertEquals("", empty.note)
        assertEquals("", box.run(call("""{"what":"x"}"""), on).note)
        assertEquals("", box.run(call("""{"what":"x","minutes":5}"""), AppSettings()).note)
        assertEquals("", box.run(call("not json"), on).note)
        // Nor "在…" while it runs.
        assertNull(box.activity(ToolSpecs.noteForLater.name))
        // Offered with its group only, which no switch in settings stores.
        assertTrue(ToolSpecs.offered(on.tools).any { it.name == ToolSpecs.noteForLater.name })
        assertFalse(ToolSpecs.offered(AppSettings().tools).any { it.name == ToolSpecs.noteForLater.name })
        assertFalse(ToolGroup.Later in decodeTools(encodeTools(AppSettings().tools)))
    }

    /** A DAO or source noting never touches. */
    private inline fun <reified T> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> error("not used") } as T
}
