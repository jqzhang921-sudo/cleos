package com.cleo.cleos.desktop

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class CoreTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `group members see each others attributed messages with their own assistant role`() {
        val a = DesktopTa(name = "A"); val b = DesktopTa(name = "B")
        val chat = Conversation(memberIds = listOf(a.id, b.id), group = true, messages = listOf(Message(text = "大家好"), Message(speakerId = a.id, text = "我是 A")))
        val data = DesktopData(companions = listOf(a, b), conversations = listOf(chat), selectedId = chat.id)
        assertEquals("assistant", prompt(data, chat, a).last().role)
        val other = prompt(data, chat, b).last()
        assertEquals("user", other.role); assertEquals("[群消息 · A] 我是 A", other.content)
        assertTrue(prompt(data, chat, b).first().content.contains("没有工具"))
    }
    @Test fun `only current conversation and selected participant notes enter prompt`() {
        val a = DesktopTa(name = "A", notes = "记得喝水"); val b = DesktopTa(name = "B", notes = "B的私密笔记")
        val chat = Conversation(memberIds = listOf(a.id), messages = listOf(Message(text = "你好")))
        val other = Conversation(memberIds = listOf(b.id), messages = listOf(Message(text = "不应出现")))
        val data = DesktopData(companions = listOf(a,b), conversations = listOf(chat,other))
        val text = prompt(data,chat,a).joinToString { it.content }
        assertTrue(text.contains("记得喝水")); assertFalse(text.contains("不应出现")); assertFalse(text.contains("B的私密笔记"))
    }
    @Test fun `library and drafts survive restart and unfinished response becomes interrupted`() {
        val dir = temp.newFolder().toPath()
        val first = LocalStore(dir)
        val data = first.load(); val c = data.conversations.first()
        first.save(data.copy(conversations = listOf(c.copy(draft="未发送草稿", messages = listOf(Message(text="半条回复",status="streaming")))))); first.close()
        LocalStore(dir).use { reopened ->
            val loaded = reopened.load().conversations.first()
            assertEquals("未发送草稿",loaded.draft); assertEquals("半条回复",loaded.messages.first().text); assertEquals("interrupted",loaded.messages.first().status)
        }
    }
    @Test fun `unreadable library is retained and never reset to an empty account`() {
        val dir=temp.newFolder().toPath(); val path=dir.resolve("library.json"); Files.writeString(path,"damaged")
        LocalStore(dir).use { store -> assertThrows(IllegalStateException::class.java) { store.load() }; assertEquals("damaged",Files.readString(path)) }
    }
    @Test fun `second instance cannot write the same library`() {
        val dir=temp.newFolder().toPath()
        LocalStore(dir).use { assertThrows(Exception::class.java) { LocalStore(dir) } }
    }
    @Test fun `previous complete library remains as recovery snapshot`() {
        LocalStore(temp.newFolder().toPath()).use { store ->
            val first=store.load();store.save(first);store.save(first.copy(userName="新的名字"))
            assertTrue(Files.readString(store.directory.resolve("library.json.bak")).contains("\"userName\": \"我\""))
        }
    }
    @Test fun `windows vault encrypts keys and scopes them by full endpoint`() {
        val dir=temp.newFolder().toPath(); val vault=WindowsKeyVault(dir)
        vault.put("https://one.example/v1/", "synthetic-key-only-123")
        assertEquals("synthetic-key-only-123",WindowsKeyVault(dir).get("https://one.example/v1/chat/completions"))
        assertEquals("",vault.get("https://one.example/other"))
        assertFalse(Files.readAllBytes(dir.resolve("keys.dpapi")).toString(Charsets.UTF_8).contains("synthetic-key-only-123"))
        vault.put("https://one.example/v1", ""); assertEquals("",vault.get("https://one.example/v1"))
    }
    @Test fun `http requires explicit opt in and credential urls are rejected`() {
        assertThrows(TextFailure::class.java) { chatUrl(Endpoint("http://127.0.0.1:8000/v1")) }
        assertEquals("http://127.0.0.1:8000/v1/chat/completions",chatUrl(Endpoint("http://127.0.0.1:8000/v1/",allowHttp=true)))
        assertThrows(TextFailure::class.java) { chatUrl(Endpoint("https://user:secret@example.com/v1")) }
        assertThrows(TextFailure::class.java) { chatUrl(Endpoint("https://example.com/v1?key=secret")) }
    }
    @Test fun `balance errors do not masquerade as invalid key`() {
        assertTrue(failureText(401,"""{"code":"INSUFFICIENT_BALANCE"}""").contains("余额"))
        assertTrue(failureText(401,"{}").contains("Key"))
        assertFalse(failureText(500,"sensitive secret body").contains("secret"))
    }
    @Test fun `thinking tags split at every possible boundary and stay out of visible text`() {
        val text="  <thinking>内部思考</thinking>\n\n你好"
        for (cut in 0..text.length) {
            val split=ThinkingText(); val events=split.split(text.take(cut))+split.split(text.drop(cut))+split.finish()
            assertEquals("你好",events.filterIsInstance<TextEvent.Text>().joinToString(""){it.value})
            assertEquals("内部思考",events.filterIsInstance<TextEvent.Thought>().joinToString(""){it.value})
        }
    }
    @Test fun `unfinished thinking never falls through as reply`() {
        val split=ThinkingText(); val events=split.split("<think>只有思考</thi")+split.finish()
        assertTrue(events.none { it is TextEvent.Text }); assertEquals("只有思考</thi",events.filterIsInstance<TextEvent.Thought>().joinToString(""){it.value})
    }
}
