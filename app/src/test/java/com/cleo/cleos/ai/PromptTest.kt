package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.MessageQuotes
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PromptTest {
    private val now = ZonedDateTime.of(2026, 9, 22, 21, 5, 0, 0, ZoneId.of("Asia/Shanghai"))
    private val allTools = setOf(ToolGroup.Todos, ToolGroup.Diary, ToolGroup.Weather)
    private var id = 0L
    private fun msg(role: String, content: String, error: String? = null) =
        MessageEntity(id = ++id, conversationId = 1, role = role, content = content, createdAt = id, error = error)

    private fun calling(content: String, vararg calls: ToolCall, reasoning: String? = null) =
        msg("assistant", content).copy(toolCalls = ToolCallCodec.encode(calls.toList()), reasoning = reasoning)

    private fun result(callId: String, content: String) =
        msg("tool", content).copy(toolCallId = callId, note = "记下了待办")

    private val add = ToolCall("c1", "add_todo", """{"title":"交报告"}""")

    private fun ta(name: String = "", persona: String = "") =
        CompanionEntity(id = 1, name = name, persona = persona, apiBaseUrl = "", apiModel = "", createdAt = 0)

    @Test
    fun systemHasNamesButNoTimeAndNoInventedRelationship() {
        val s = Prompt.system(AppSettings(userName = "Cleo"), ta(name = "沐"))
        assertTrue(s.contains("你叫沐。"))
        assertTrue(s.contains("和你说话的人叫Cleo。"))
        assertFalse("time must stay out of the cached prefix", s.contains("现在是"))
        for (label in listOf("朋友", "恋人", "温柔")) assertFalse(s.contains(label))
    }

    @Test
    fun withoutToolsTheOnlyAddedLineIsTheFormatRule() {
        val s = Prompt.system(AppSettings(), ta())
        assertFalse(s.contains("你叫"))
        assertFalse(s.contains("工具"))
        assertTrue(s.contains("Markdown"))
    }

    @Test
    fun toolRulesComeOnlyForToolsOffered() {
        val todosOnly = Prompt.system(AppSettings(), ta(), setOf(ToolGroup.Todos))
        assertTrue(todosOnly.contains("没有调用工具，就不要说已经做好了"))
        assertFalse(todosOnly.contains("日记"))
        assertTrue(Prompt.system(AppSettings(), ta(), setOf(ToolGroup.Diary)).contains("只在对方提起或问到日记里写过的事时"))
    }

    @Test
    fun itsOwnDiaryAndTheSecretsComeWithTheirRules() {
        val s = Prompt.system(AppSettings(), ta(), setOf(ToolGroup.AiDiary, ToolGroup.Secrets))
        assertTrue(s.contains("不是替对方写"))
        assertTrue(s.contains("被拒绝了就别追着要"))
        assertFalse("reading the person's diary is a separate permission", s.contains("只在对方提起或问到日记里写过的事时"))
    }

    @Test
    fun eachTaIsToldOnlyTheirOwnNameAndPersona() {
        val settings = AppSettings(userName = "Cleo")
        val first = Prompt.system(settings, ta("沐", "你喜欢下雨天。"))
        val second = Prompt.system(settings, ta("星", "说话很短。"))
        assertTrue(first.contains("你叫沐。") && first.contains("你喜欢下雨天。"))
        assertTrue(second.contains("你叫星。") && second.contains("说话很短。"))
        assertFalse(second.contains("沐") || second.contains("下雨天"))
        assertTrue("the person is the same for every TA", second.contains("和你说话的人叫Cleo。"))
    }

    @Test
    fun timeRidesOnTheLastUserMessageOnlyAndCarriesTheYear() {
        val out = Prompt.messages(
            AppSettings(),
            ta(),
            listOf(msg("user", "早"), msg("assistant", "早呀"), msg("user", "今天好累")),
            now,
        )
        assertEquals(listOf("system", "user", "assistant", "user"), out.map { it.role })
        assertEquals("（消息编号 #1）\n早", out[1].content)
        assertTrue(out[3].content.startsWith("（现在是2026年9月22日 星期二 21:05）"))
        assertTrue(out[3].content.endsWith("今天好累"))
    }

    @Test
    fun whatCameDueRidesBesideTheTimeNotInTheSystemPrompt() {
        val out = Prompt.messages(
            AppSettings(),
            ta(),
            listOf(msg("user", "我回来了"), msg("assistant", "欢迎回来"), msg("user", "考完了")),
            now,
            setOf(ToolGroup.Later),
            due = listOf("问问考得怎样"),
        )
        val last = out.last().content
        assertTrue(last.startsWith("（现在是2026年9月22日 星期二 21:05）\n（你之前给自己记过、现在到时候了：「问问考得怎样」"))
        assertTrue(last.endsWith("考完了"))
        assertFalse(out.first().content.contains("问问考得怎样"))
        // The rule for noting things comes with the tool only.
        assertTrue(out.first().content.contains("note_for_later"))
        assertFalse(Prompt.system(AppSettings(), ta(), setOf(ToolGroup.Todos)).contains("note_for_later"))
    }

    @Test
    fun theSongPlayingRidesBesideTheTimeToo() {
        val song = "（你们正在一起听《晴天》—周杰伦，放到 1:23 / 4:29）"
        val out = Prompt.messages(
            AppSettings(),
            ta(),
            listOf(msg("user", "这首好好听")),
            now,
            setOf(ToolGroup.Music),
            listening = song,
        )
        assertEquals("（现在是2026年9月22日 星期二 21:05）\n$song\n（消息编号 #1）\n这首好好听", out.last().content)
        // Not in the system prompt, which stays the same from turn to turn; the rule is.
        assertFalse(out.first().content.contains("晴天"))
        assertTrue(out.first().content.contains("music_control"))
        assertFalse(Prompt.system(AppSettings(), ta(), setOf(ToolGroup.Todos)).contains("music_control"))
    }

    @Test
    fun aWakeGoesLastOnThePersonsSideAndStartsATurnOfItsOwn() {
        val base = listOf(ApiMessage("system", "s"), ApiMessage("user", "去做饭了"), ApiMessage("assistant", "去吧", reasoning = "r"))
        val woke = Prompt.withWake(base, "（这条不是对方发的…）")
        assertEquals(listOf("system", "user", "assistant", "user"), woke.map { it.role })
        assertEquals("（这条不是对方发的…）", woke.last().content)
        assertTrue("reasoning belongs to the turn it was in", woke.none { it.reasoning != null })
        // After a turn of the person's (a reply that failed), joined to it: two user turns in a row get refused.
        val afterTheirs = Prompt.withWake(base.dropLast(1), "WAKE")
        assertEquals(listOf("system", "user"), afterTheirs.map { it.role })
        assertEquals("去做饭了\n\nWAKE", afterTheirs.last().content)
    }

    @Test
    fun failedRepliesAreLeftOutAndTheUserTurnsAroundThemMerged() {
        val out = Prompt.messages(
            AppSettings(),
            ta(),
            listOf(msg("user", "第一句"), msg("assistant", "说到一半", error = "网络出错"), msg("user", "第二句")),
            now,
        )
        assertEquals(listOf("system", "user"), out.map { it.role })
        assertTrue(out[1].content.contains("第一句"))
        assertTrue(out[1].content.endsWith("第二句"))
    }

    @Test
    fun aToolRoundGoesBackAsCallThenResult() {
        val out = Prompt.messages(
            AppSettings(),
            ta(),
            listOf(
                msg("user", "明天交报告，帮我记一下"),
                calling("", add, reasoning = "要记待办"),
                result("c1", "已添加：#3 交报告"),
                msg("assistant", "记好啦"),
                msg("user", "谢谢"),
            ),
            now,
            allTools,
        )
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant", "user"), out.map { it.role })
        assertEquals(listOf(add), out[2].toolCalls)
        assertEquals("c1", out[3].toolCallId)
        assertNull("an earlier turn's reasoning is not sent back", out[2].reasoning)
    }

    @Test
    fun theTurnStillUnderWayKeepsItsReasoning() {
        // A retry after the final answer failed: the calls already ran and are not redone.
        val out = Prompt.messages(
            AppSettings(),
            ta(),
            listOf(msg("user", "记一下"), calling("", add, reasoning = "要记待办"), result("c1", "已添加")),
            now,
            allTools,
        )
        assertEquals(listOf("system", "user", "assistant", "tool"), out.map { it.role })
        assertEquals("要记待办", out[2].reasoning)
    }

    @Test
    fun unansweredCallsAndStrayResultsAreDropped() {
        val out = Prompt.messages(
            AppSettings(),
            ta(),
            listOf(
                result("gone", "the window cut its call off"),
                msg("user", "查天气"),
                calling("我看看", ToolCall("w", "get_weather", "{}")),
                // stopped before the tool ran: no result
                msg("user", "算了"),
            ),
            now,
            allTools,
        )
        assertEquals(listOf("system", "user", "assistant", "user"), out.map { it.role })
        assertTrue(out[2].toolCalls.isEmpty())
        assertEquals("我看看", out[2].content)
    }

    @Test
    fun withoutToolsOnlyWhatWasSaidRemains() {
        val out = Prompt.messages(
            AppSettings(),
            ta(),
            listOf(msg("user", "记一下"), calling("好", add), result("c1", "已添加"), msg("assistant", "记好了"), msg("user", "嗯")),
            now,
        )
        assertEquals(listOf("system", "user", "assistant", "user"), out.map { it.role })
        assertEquals("好\n\n记好了", out[2].content)
        assertTrue(out.all { it.toolCalls.isEmpty() && it.toolCallId == null })
    }

    @Test
    fun messagesInARowGoBackAsSentMessages() {
        val history = listOf(
            msg("user", "早"),
            msg("assistant", "早呀"),
            msg("assistant", "今天下雨了"),
            msg("user", "嗯"),
            msg("assistant", "带伞"),
            msg("user", "好"),
        )
        val out = Prompt.messages(AppSettings(), ta(), history, now, setOf(ToolGroup.Messages))
        assertEquals(listOf("system", "user", "assistant", "tool", "tool", "user", "assistant", "user"), out.map { it.role })
        val sent = out[2]
        assertEquals("", sent.content)
        assertEquals(listOf("send_message", "send_message"), sent.toolCalls.map { it.name })
        assertTrue(sent.toolCalls[0].arguments.contains("早呀"))
        assertTrue(sent.toolCalls[1].arguments.contains("今天下雨了"))
        assertEquals(sent.toolCalls.map { it.id }, listOf(out[3].toolCallId, out[4].toolCallId))
        assertEquals("the ids come from the rows", "send_${history[1].id}", sent.toolCalls[0].id)
        assertEquals("one message alone stays plain", "带伞", out[6].content)
        assertTrue(out[6].toolCalls.isEmpty())
        assertTrue(out[0].content.contains("send_message"))
        // Without the tool they are one reply, as before.
        val plain = Prompt.messages(AppSettings(), ta(), history, now)
        assertEquals("早呀\n\n今天下雨了", plain[2].content)
    }

    @Test
    fun aQuotedMessageSaysWhatItAnswers() {
        val said = msg("assistant", "盗走了")
        val mine = msg("user", "早")
        val history = listOf(
            msg("user", "我的小蛋糕呢"),
            said,
            mine,
            msg("user", "这样子").copy(quote = MessageQuotes.encode(MessageQuotes.of(said)!!)),
            msg("user", "补一句").copy(quote = MessageQuotes.encode(MessageQuotes.of(mine)!!)),
        )
        val out = Prompt.messages(AppSettings(), ta(), history, now)
        val last = out.last().content
        assertTrue(last.contains("（回复你说的：「盗走了」）\n这样子"))
        assertTrue(last.contains("（接着自己说的：「早」）\n补一句"))
        // A voice message not turned into words yet says nothing, quote or not.
        val silent = listOf(msg("user", "在吗"), msg("user", "").copy(audio = """{"file":"v.wav","ms":1000}""", quote = MessageQuotes.encode(MessageQuotes.of(said)!!)))
        assertFalse(Prompt.messages(AppSettings(), ta(), silent, now).last().content.contains("回复你说的"))
    }

    @Test
    fun theTasQuoteGoesBackAsTheSendMessageItWas() {
        val asked = msg("user", "今天吃什么")
        val history = listOf(
            asked,
            msg("assistant", "吃面吧").copy(quote = MessageQuotes.encode(MessageQuotes.of(asked)!!)),
            msg("user", "好"),
        )
        val out = Prompt.messages(AppSettings(), ta(), history, now, setOf(ToolGroup.Messages))
        // Even alone, so the model sees it quoted.
        val call = out[2].toolCalls.single()
        assertEquals("send_message", call.name)
        assertTrue(call.arguments.contains("\"quote\":\"今天吃什么\""))
        // Without the tool, it is a plain reply, as before.
        assertEquals("吃面吧", Prompt.messages(AppSettings(), ta(), history, now)[2].content)
    }

    @Test
    fun notesAreNeverSentAndTheWindowStartsAtAUserTurn() {
        val out = Prompt.messages(
            AppSettings(),
            ta(),
            listOf(
                msg("assistant", "the window starts here, mid-exchange"),
                msg("user", "你好"),
                msg("note", "").copy(note = "这个模型不接受工具调用"),
                msg("assistant", "你好呀"),
            ),
            now,
            allTools,
        )
        assertEquals(listOf("system", "user", "assistant"), out.map { it.role })
        assertEquals("你好呀", out[2].content)
    }
}
