package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class GroupImagesTest {
    private fun photo(id: String, group: Long = 1) = MessageImage("group-user-$group-$id.jpg", 100, 80, GroupUploadRules.SOURCE)
    private fun user(id: Long, vararg pictures: MessageImage, group: Long = 1) =
        GroupMessageEntity(id, group, "user", "说说这些图", id, images = MessageImages.encode(pictures.toList()))
    private val group = ChatGroupEntity(1, "合成群", 0, 0)
    private val roster = listOf(GroupMemberEntity(1, 10, 0, "A"), GroupMemberEntity(1, 20, 1, "B"))
    private val ta = CompanionEntity(id = 20, name = "B", apiBaseUrl = "https://example.com", apiModel = "fixture", createdAt = 0)
    private fun request(history: List<GroupMessageEntity>, files: Map<Long, List<String>>) =
        GroupChatRules.request(group, roster, ta, history, "user", emptyList(), ZonedDateTime.now(), imageFiles = files)
    private fun gallery(id: Long, vararg files: String) = GroupMessageEntity(id, 1, "assistant", "给你看图", id, speakerId = 20, speakerName = "B",
        images = MessageImages.encode(files.map { MessageImage(it, 100, 80, "Openverse",
            PhotoCredit("Fixture", "Author", "https://example.com/photo", "CC BY", "https://example.com/license")) }))

    @Test fun uploadedPicturesRequireGroupOwnershipAndBoundedValidMetadata() {
        val p = photo("one")
        assertTrue(GroupUploadRules.valid(MessageImages.encode(listOf(p)), 1))
        assertFalse(GroupUploadRules.valid(MessageImages.encode(listOf(p)), 2))
        assertFalse(GroupUploadRules.valid("[]", 1)); assertFalse(GroupUploadRules.valid("broken", 1))
        for (bad in listOf(p.copy(file = "../outside.jpg"), p.copy(source = "Openverse"), p.copy(width = 0),
            p.copy(file = "other-private.jpg"), p.copy(height = McpImages.MAX_EDGE + 1))) {
            assertFalse(GroupUploadRules.valid(MessageImages.encode(listOf(bad)), 1))
        }
        assertFalse(GroupUploadRules.valid(MessageImages.encode(listOf(p,p)), 1))
        assertFalse(GroupUploadRules.valid(MessageImages.encode((1..4).map { photo("$it") }), 1))
    }
    @Test fun photoOnlySendIsAllowedButEmptyTextAndNoPictureStillRejected() {
        assertEquals("", GroupChatRules.text("  ", true))
        try { GroupChatRules.text("  "); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun requestSelectsOnlyThreeLatestPicturesFromTheCurrentGroup() {
        val history = listOf(user(1,photo("old")), user(2,photo("a"),photo("b")), user(3,photo("c")), user(4,photo("private",2),group=2))
        assertEquals(listOf(photo("a").file,photo("b").file,photo("c").file), GroupVisionRules.selected(1,history).values.flatten())
    }
    @Test fun currentUserPicturesCannotBeDisplacedByThePrecedingMembersGallery() {
        val history = listOf(user(1,photo("a"),photo("b"),photo("c")), gallery(2,"g1.jpg","g2.jpg","g3.jpg"))
        assertEquals(mapOf(1L to listOf(photo("a").file,photo("b").file,photo("c").file)), GroupVisionRules.selected(1,history,1))
    }
    @Test fun mergedUserRolesKeepPicturesAndAuthorLabelsWithoutExposingOtherGroups() {
        val history = listOf(user(1,photo("a")), user(2,photo("b")), user(3,photo("private",2),group=2))
        val messages = request(history,GroupVisionRules.selected(1,history))
        assertEquals(listOf(photo("a").file,photo("b").file), messages.flatMap { it.images })
        assertTrue(messages.filter { it.role == "user" }.any { it.content.contains("[user]") && it.content.contains("本群附图") })
        assertFalse(messages.any { it.content.contains("private") })
    }
    @Test fun ownGalleryPixelsUseUserRoleReferenceWithActualHistoricalAuthor() {
        val row = gallery(1,"gallery.jpg")
        val messages = request(listOf(row), GroupVisionRules.selected(1,listOf(row)))
        assertTrue(messages.filter { it.role != "user" }.all { it.images.isEmpty() })
        assertTrue(messages.any { it.images == listOf("gallery.jpg") && it.content.contains("实际作者：B，成员 20") })
    }
    @Test fun noDecodedFileMeansNoPixelClaimAndNoUnfinishedAssistantWords() {
        val row = gallery(1,"gallery.jpg").copy(status="cancelled",content="unfinished guess")
        val messages = request(listOf(row),emptyMap())
        assertTrue(messages.all { it.images.isEmpty() }); assertFalse(messages.any { it.content.contains("unfinished guess") })
        assertTrue(messages.any { it.content.contains("没有提供画面") })
    }
    @Test fun explicitImageRefusalDropsEveryImageAndContinuesOnlyThisMembersReply() = runBlocking {
        val initial = request(listOf(user(1,photo("a"))),mapOf(1L to listOf(photo("a").file)))
            .map { it.copy(images = it.images.map { "data:image/jpeg;base64,AQ==" }) }
        var attempts = 0
        var notice = ""
        val text = GroupToolReply.reply(initial, emptyList(), false,
            stream = { messages, _, _ ->
                if (++attempts == 1) { assertTrue(messages.any { it.images.isNotEmpty() }); throw ChatException("images are not supported",400,rejectedFeature=RequestFeature.Images) }
                assertTrue(messages.all { it.images.isEmpty() }); assertTrue(messages.any { it.content.contains(GroupVisionRules.NO_PIXELS) })
                flowOf(ChatEvent.Delta("只依据文字回答"))
            }, execute = { error("No tool should execute") }, onTrace = { notice = it.notice }) {}
        assertEquals(2,attempts); assertEquals("只依据文字回答",text); assertTrue(notice.contains("其他成员"))
        var sawPixels = false
        GroupToolReply.reply(initial,emptyList(),false,{ messages,_,_-> sawPixels=messages.any{it.images.isNotEmpty()}; flowOf(ChatEvent.Delta("另一位可以看图")) },{ error("No tools") }) {}
        assertTrue(sawPixels)
    }
    @Test fun genericNetworkErrorsAndPartialResponsesNeverTriggerImageResend() = runBlocking {
        val initial=listOf(ApiMessage("system","fixture"),ApiMessage("user","fixture",images=listOf("data:image/jpeg;base64,AQ==")))
        for (partial in listOf(false,true)) {
            var calls=0
            try { GroupToolReply.reply(initial,emptyList(),false,{ _,_,_-> flow {
                calls++; if(partial) emit(ChatEvent.Delta("已有正文")); throw ChatException("fixture network error",if(partial) 400 else 500,rejectedFeature=RequestFeature.Images)
            } },{error("No tools")}) {}; fail() } catch (_:ChatException) { assertEquals(1,calls) }
        }
    }
}
