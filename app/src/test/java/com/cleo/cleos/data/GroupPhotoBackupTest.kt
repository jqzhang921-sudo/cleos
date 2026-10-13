package com.cleo.cleos.data

import com.cleo.cleos.ai.GroupPhotoRules
import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GroupPhotoBackupTest {
    private val groups = listOf(ChatGroupEntity(1, "group", 0, 0))
    private val members = listOf(GroupMemberEntity(1, 10, 0, "A"), GroupMemberEntity(1, 20, 1, "B"))
    private val user = GroupMessageEntity(1, 1, "user", "photo", 0)
    private val turn = GroupTurnEntity(1, 1, 1, "[10]", 0, "completed")
    private val picture = MessageImage("mcp-photo.jpg", 100, 80, "Openverse",
        PhotoCredit("Cat", "Author", "https://example.com/cat", "CC BY", "https://example.com/license"))
    private val reply = GroupMessageEntity(2, 1, "assistant", "", 0, speakerId = 10, turnId = 1, images = MessageImages.encode(listOf(picture)))
    private fun validate(row: GroupMessageEntity) = GroupBackupRules.validate(groups, members, listOf(turn), listOf(user, row))
    @Test fun oldFormatsDecodeWithoutMediaAndNewFormatKeepsPhotoOnlyReplyCredit() {
        val old = JsonObject(Json.parseToJsonElement(Json.encodeToString(reply.copy(content = "old"))).jsonObject - "images")
        assertNull(Json.decodeFromString<GroupMessageEntity>(old.toString()).images)
        val restored = Json.decodeFromString<GroupMessageEntity>(Json.encodeToString(reply))
        validate(restored); assertEquals(picture, MessageImages.decode(restored.images).single())
        assertEquals(6, BackupFile.VERSION)
    }
    @Test fun corruptMediaPathsDuplicatesOverBudgetAndMissingCreditAreRejected() {
        val bad = listOf("../outside.jpg", "/absolute.jpg", "C:\\outside.jpg")
        for (file in bad) assertFalse(GroupPhotoRules.valid(MessageImages.encode(listOf(picture.copy(file = file)))))
        for (raw in listOf("broken", "[]", MessageImages.encode(List(4) { picture.copy(file = "p$it.jpg") }),
            MessageImages.encode(listOf(picture, picture)), MessageImages.encode(listOf(picture.copy(credit = null))),
            MessageImages.encode(listOf(picture.copy(width = 0))), MessageImages.encode(listOf(picture.copy(source = "unknown"))),
            MessageImages.encode(listOf(picture.copy(credit = picture.credit!!.copy(pageUrl = "http://example.com")))))) {
            try { validate(reply.copy(images = raw)); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun restoredInterruptedPhotoRemainsStoredButNeverAutomaticallyResends() {
        val row = GroupBackupRules.restored(reply.copy(status = "running"))
        assertEquals("interrupted", row.status); assertEquals(reply.images, row.images)
        validate(row)
    }
    @Test fun missingArchivePictureDoesNotRestoreAFalseSuccessfulEmptyMessage() {
        val missing = GroupPhotoRules.restored(reply) { false }
        assertNull(missing.images); assertEquals("failed", missing.status)
        assertTrue(missing.error!!.contains("文件缺失")); validate(missing)
        assertEquals(reply, GroupPhotoRules.restored(reply) { true })
    }
    @Test fun userCannotSmuggleAssistantGalleryFilesIntoThisFormat() {
        try { GroupBackupRules.validate(groups, members, listOf(turn), listOf(user.copy(images = reply.images), reply)); fail() }
        catch (_: IllegalArgumentException) { }
    }
}
