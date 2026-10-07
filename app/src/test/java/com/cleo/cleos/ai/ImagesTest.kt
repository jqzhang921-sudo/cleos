package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ImagesTest {
    private val now = ZonedDateTime.of(2026, 9, 23, 21, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
    private var nextId = 0L
    private val ta = CompanionEntity(id = 1, apiBaseUrl = "", apiModel = "", createdAt = 0)

    private fun said(text: String, vararg files: String) = MessageEntity(
        id = ++nextId,
        conversationId = 1,
        role = "user",
        content = text,
        createdAt = nextId,
        images = MessageImages.encode(files.map { MessageImage(it, 100, 100) }),
    )

    private fun reply(text: String) = MessageEntity(id = ++nextId, conversationId = 1, role = "assistant", content = text, createdAt = nextId)

    @Test
    fun picturesGoAsContentPartsAfterTheText() {
        val body = Json.parseToJsonElement(
            requestBody("m", listOf(ApiMessage("user", "看这个", images = listOf("data:image/jpeg;base64,AAA"))), emptyList()).toString(),
        ).jsonObject
        val parts = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("看这个", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AAA", parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun theTextNamesEachPictureSoTheModelCanPointAtOne() {
        val m = said("今天的晚霞", "a.jpg", "b.jpg")
        val out = Prompt.messages(AppSettings(), ta, listOf(m), now, images = true)
        val user = out.last()
        assertEquals(listOf("a.jpg", "b.jpg"), user.images)
        assertTrue(user.content, user.content.contains("（附图 #${m.id}-1 #${m.id}-2）\n今天的晚霞"))
    }

    @Test
    fun onlyTheNewestPicturesGoAlong() {
        val old = said("", "1.jpg", "2.jpg", "3.jpg")
        val mid = reply("好看")
        val new = said("还有这些", "4.jpg", "5.jpg")
        val newer = reply("嗯")
        val newest = said("最后一张", "6.jpg")
        val out = Prompt.messages(AppSettings(), ta, listOf(old, mid, new, newer, newest), now, images = true)
        val users = out.filter { it.role == "user" }
        // 6 + 4,5 fit in MAX_IMAGES (4); the three older ones would not, so they are named only.
        assertEquals(emptyList<String>(), users[0].images)
        assertTrue(users[0].content.startsWith("（消息编号 #1）\n（早先发的 3 张图"))
        assertEquals(listOf("4.jpg", "5.jpg"), users[1].images)
        assertEquals(listOf("6.jpg"), users[2].images)
    }

    @Test
    fun aModelThatCantSeeIsToldSo() {
        val out = Prompt.messages(AppSettings(), ta, listOf(said("", "a.jpg")), now, images = false)
        assertTrue(out.last().images.isEmpty())
        assertTrue(out.last().content.contains("你这边看不到图片"))
    }

    @Test
    fun pictureReferences() {
        assertEquals(PictureRef.Latest, PictureRef.parse("latest"))
        assertEquals(PictureRef.Latest, PictureRef.parse(""))
        assertEquals(PictureRef.Id(45, 2), PictureRef.parse("#45-2"))
        assertEquals(PictureRef.Id(45, 1), PictureRef.parse(" 45 - 1 "))
        assertNull(PictureRef.parse("#45-0"))
        assertNull(PictureRef.parse("the sunset one"))
    }

    @Test
    fun theModelChangesItsOwnAvatar() {
        val port = object : SelfAvatar {
            var used: String? = null
            var emoji: String? = null
            val whose = mutableSetOf<Long>()
            override suspend fun picture(conversationId: Long, ref: String) = if (ref == "latest") "chat-1.jpg" else null
            override suspend fun usePicture(companionId: Long, file: String): Boolean {
                whose += companionId
                used = file
                return true
            }
            override suspend fun useEmoji(companionId: Long, emoji: String) {
                whose += companionId
                this.emoji = emoji
            }
        }
        val box = ToolBox(NoTodos, NoDiary, NoWeather, avatar = port)
        val all = AppSettings(tools = ToolGroup.entries.toSet())
        // TA 2 is the one talking: only their avatar may change.
        fun call(args: String) = runBlocking { box.run(ToolCall("c", "set_my_avatar", args), all, conversationId = 1, companionId = 2) }

        assertEquals("换了新头像", call("""{"image":"latest"}""").note)
        assertEquals("chat-1.jpg", port.used)
        assertEquals("换了新头像：🌙", call("""{"emoji":"🌙"}""").note)
        assertEquals("🌙", port.emoji)
        assertEquals(setOf(2L), port.whose)
        assertEquals("换头像没成：找不到那张图", call("""{"image":"#9-9"}""").note)
        assertEquals("换头像没成：表情太长了", call("""{"emoji":"this is a whole sentence"}""").note)
        assertEquals("换头像没成：没说换成什么", call("{}").note)
    }
}

private object NoTodos : com.cleo.cleos.data.db.TodoDao by unsupported()
private object NoDiary : com.cleo.cleos.data.db.DiaryDao by unsupported()

private object NoWeather : WeatherSource {
    override suspend fun report(city: String, days: Int) = error("not used")
}

/** A stand-in that fails loudly if a test reaches something it did not mean to. */
private inline fun <reified T : Any> unsupported(): T =
    java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("${method.name} not expected here")
    } as T
