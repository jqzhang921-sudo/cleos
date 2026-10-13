package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.ZonedDateTime

class GroupPhotosTest {
    private val credit = PhotoCredit("Cat", "Photographer", "https://example.com/cat", "CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/")
    private val candidate = PhotoCandidate("145e0b37-0577-416b-8d05-27c0b0bdde46", credit)
    private val outcome = ToolOutcome("photo fetched, receipt required", "share", photo = DownloadedPhoto(candidate, byteArrayOf(1), "image/webp"))
    private val image = MessageImage("mcp-fixture.jpg", 100, 80)
    @Test fun saveAndGroupCommitBothPrecedeSuccessAndCreditSurvives() = runBlocking {
        val order = mutableListOf<String>()
        var stored = emptyList<MessageImage>()
        val result = GroupPhotoDelivery.deliver(outcome, 1, { _, _ -> order += "save"; image },
            { stored = it; order += "commit" }, { fail("committed files must remain") })
        assertEquals(listOf("save", "commit"), order)
        assertTrue(result.result.contains("已在本群展示")); assertNull(result.photo)
        assertEquals(credit, stored.single().credit); assertEquals("Openverse", stored.single().source)
        assertTrue(GroupPhotoRules.valid(MessageImages.encode(stored)))
    }
    @Test fun exhaustedBudgetOrFailedImportCannotProduceSuccessOrCommit() = runBlocking {
        var commits = 0
        for (budget in listOf(0, 1)) {
            val result = GroupPhotoDelivery.deliver(outcome, budget, { _, _ -> error("fixture storage failure") }, { commits++ }, {})
            assertFalse(result.result.contains("已在本群展示")); assertTrue(result.note.contains("没有发出"))
        }
        assertEquals(0, commits)
    }
    @Test fun rejectedGroupCommitDeletesOnlyThisNewFile() = runBlocking {
        var removed = emptyList<String>()
        try {
            GroupPhotoDelivery.deliver(outcome, 1, { _, _ -> image }, { error("slot already stopped") }, { removed = it })
            fail()
        } catch (_: IllegalStateException) { }
        assertEquals(listOf(image.file), removed)
    }
    @Test fun cancellingDuringImportCleansFileAndDoesNotCommit() = runBlocking {
        val began = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var committed = false; var removed = emptyList<String>()
        val worker = launch {
            GroupPhotoDelivery.deliver(outcome, 1, { _, _ -> began.complete(Unit); release.await(); image },
                { committed = true }, { removed = it })
        }
        began.await(); worker.cancel(); release.complete(Unit); worker.join()
        assertFalse(committed); assertEquals(listOf(image.file), removed)
    }
    @Test fun cancellationDuringAtomicCommitKeepsAlreadyStoredCardButNoLaterReceipt() = runBlocking {
        val began = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var committed = false; var returned = false; var removed = emptyList<String>()
        val worker = launch {
            GroupPhotoDelivery.deliver(outcome, 1, { _, _ -> image }, {
                began.complete(Unit); release.await(); committed = true
            }, { removed = it })
            returned = true
        }
        began.await(); worker.cancel(); release.complete(Unit); worker.join()
        assertTrue(committed); assertFalse(returned); assertTrue(removed.isEmpty())
    }
    @Test fun photoOnlyReplyFinishesOnlyAfterRealCommit() = runBlocking {
        val call = ToolCall("p", "share_photo", """{"id":"fixture"}""")
        var round = 0; var saved = false
        val reply = GroupToolReply.reply(listOf(ApiMessage("user", "photo")),
            listOf(ToolSpecs.sharePhoto), false,
            { messages, _, _ -> if (++round == 1) flowOf(ChatEvent.ToolCalls(listOf(call))) else {
                assertTrue(messages.last().content.contains("已在本群展示")); emptyFlow()
            } }, { GroupPhotoDelivery.deliver(outcome, 1, { _, _ -> image }, { saved = true }, {}) },
            hasPhotos = { saved }) {}
        assertEquals("", reply); assertTrue(saved)
    }
    @Test fun uncommittedPhotoPayloadCannotBePassedOffAsSent() = runBlocking {
        try {
            GroupToolReply.reply(listOf(ApiMessage("user", "photo")), listOf(ToolSpecs.sharePhoto), false,
                { _, _, _ -> flowOf(ChatEvent.ToolCalls(listOf(ToolCall("p", "share_photo", "{}")))) }, { outcome }) {}
            fail()
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("尚未保存")) }
    }
    @Test fun textualHistoryRetainsActualAuthorAndCreditButNeverFileOrPixels() {
        val picture = image.copy(source = "Openverse", credit = credit)
        val row = GroupMessageEntity(2, 1, "assistant", "", 0, speakerId = 10, speakerName = "A", images = MessageImages.encode(listOf(picture)))
        val history = GroupChatRules.request(ChatGroupEntity(1, "group", 0, 0),
            listOf(GroupMemberEntity(1, 10, 0, "A"), GroupMemberEntity(1, 20, 1, "B")), CompanionEntity(id = 20, name = "B", apiBaseUrl = "https://example.com/v1", apiModel = "fixture-text", createdAt = 0),
            listOf(row), "user", emptyList(), ZonedDateTime.now(), tools = listOf(ToolSpecs.searchPhotos, ToolSpecs.sharePhoto))
        assertTrue(history.any { it.content.contains("A，成员 10") && it.content.contains("Photographer") })
        assertFalse(history.any { it.content.contains(image.file) }); assertTrue(history.all { it.images.isEmpty() })
        assertFalse(history.first().content.contains("没有发送图片工具"))
    }
    @Test fun alreadyPublishedPhotoSurvivesCancelledTextInSharedContext() {
        val row = GroupMessageEntity(2, 1, "assistant", "unfinished guess", 0, speakerId = 10, speakerName = "A",
            status = "cancelled", images = MessageImages.encode(listOf(image.copy(source = "Openverse", credit = credit))))
        val history = GroupChatRules.request(ChatGroupEntity(1, "group", 0, 0),
            listOf(GroupMemberEntity(1, 10, 0, "A"), GroupMemberEntity(1, 20, 1, "B")),
            CompanionEntity(id = 20, name = "B", apiBaseUrl = "https://example.com", apiModel = "fixture", createdAt = 0),
            listOf(row), "user", emptyList(), ZonedDateTime.now())
        assertTrue(history.any { it.content.contains("Photographer") && it.content.contains("A，成员 10") })
        assertFalse(history.any { it.content.contains("unfinished guess") || it.images.isNotEmpty() })
    }

    @Suppress("UNCHECKED_CAST") private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> error("private DAO must not be used") } as T
    @Test fun groupCandidateCannotBeUsedByPrivateChatOtherGroupOrOtherSpeaker() = runBlocking {
        var downloaded = 0
        val book = PhotoBook(object : PhotoSource {
            override suspend fun search(query: String) = listOf(candidate)
            override suspend fun download(candidate: PhotoCandidate): DownloadedPhoto {
                downloaded++; return requireNotNull(outcome.photo)
            }
        })
        val box = ToolBox(unused<TodoDao>(), unused<DiaryDao>(), object : WeatherSource {
            override suspend fun report(city: String, days: Int) = error("unused")
        }, photos = book)
        var settings = AppSettings(tools = setOf(ToolGroup.Photos))
        val tools = GroupTools(box) { settings }
        tools.run(ToolCall("s", "search_photos", """{"query":"cat"}"""), 1, 10)
        val share = ToolCall("p", "share_photo", """{"id":"145e0b37-0577-416b-8d05-27c0b0bdde46"}""")
        assertNull(tools.run(share, 2, 10).photo)
        assertNull(tools.run(share, 1, 20).photo)
        assertNull(box.run(share, settings, 1, 10).photo)
        assertEquals(0, downloaded)
        assertNotNull(tools.run(share, 1, 10).photo); assertEquals(1, downloaded)
        settings = settings.copy(tools = emptySet())
        assertNull(tools.run(share, 1, 10).photo); assertEquals(1, downloaded)
    }
}
