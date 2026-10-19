package com.cleo.cleos.data

import com.cleo.cleos.ai.GroupPhotoRules
import com.cleo.cleos.ai.GroupUploadRules
import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GroupUploadBackupTest {
    private val picture = MessageImage("group-user-1-fixture.jpg",100,80,GroupUploadRules.SOURCE)
    private val raw = MessageImages.encode(listOf(picture))
    private val group = ChatGroupEntity(1,"fixture",0,0)
    private val members = listOf(GroupMemberEntity(1,10,0,"A"),GroupMemberEntity(1,20,1,"B"))
    private val user = GroupMessageEntity(1,1,"user","",0,images=raw)
    private fun validate(g: ChatGroupEntity = group, row: GroupMessageEntity = user) =
        GroupBackupRules.validate(listOf(g),members,emptyList(),listOf(row))
    @Test fun oldBackupsHaveNoDraftPicturesAndNewFormatKeepsPhotoOnlyUserMessages() {
        val old=JsonObject(Json.parseToJsonElement(Json.encodeToString(group)).jsonObject - "draftImages")
        assertNull(Json.decodeFromString<ChatGroupEntity>(old.toString()).draftImages)
        val restored=Json.decodeFromString<GroupMessageEntity>(Json.encodeToString(user))
        assertEquals(user,restored); validate(row=restored); assertEquals(15, BackupFile.VERSION)
    }
    @Test fun draftAndSentFilesCannotShareOwnershipOrCrossGroups() {
        validate(g=group.copy(draftImages=MessageImages.encode(listOf(picture.copy(file="group-user-1-draft.jpg")))))
        for(g in listOf(group.copy(draftImages=raw),group.copy(draftImages=MessageImages.encode(listOf(picture.copy(file="group-user-2-other.jpg")))))) {
            try {validate(g=g);fail()} catch(_:IllegalArgumentException){}
        }
    }
    @Test fun userCannotRelabelGalleryPathsAsOwnUploadAndAssistantCannotPublishUserFiles() {
        for(p in listOf(picture.copy(file="private.jpg"),picture.copy(source="Openverse"),picture.copy(credit=PhotoCredit("title","author","https://example.com","CC BY","https://example.com")))) {
            try{validate(row=user.copy(images=MessageImages.encode(listOf(p))));fail()}catch(_:IllegalArgumentException){}
        }
        assertFalse(GroupPhotoRules.valid(raw))
    }
    @Test fun missingUserImageRestoresAnHonestMessageWithoutClaimingRetryableAssistantStatus() {
        val row=GroupPhotoRules.restored(user){false}
        assertNull(row.images); assertEquals("sent",row.status); assertTrue(row.content.contains("文件缺失")); assertTrue(row.error!!.contains("重新选择"))
        validate(row=row)
    }
    @Test fun partialImageLossKeepsCaptionAndRemainingFile() {
        val two=user.copy(content="caption",images=MessageImages.encode(listOf(picture,picture.copy(file="group-user-1-second.jpg"))))
        val row=GroupPhotoRules.restored(two){it==picture.file}
        assertEquals("caption",row.content);assertEquals(listOf(picture),MessageImages.decode(row.images));assertTrue(row.error!!.contains("1 张"));validate(row=row)
    }
    @Test fun restoreSummaryNamesMissingDraftPictures() {
        assertTrue(BackupSummary(2,0,0,0,0,0,0,missingGroupDraftImages=2).toString().contains("2 张未发送"))
    }
}
