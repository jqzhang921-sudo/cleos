package com.cleo.cleos.data

import com.cleo.cleos.ai.GroupCallRules
import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GroupCallBackupTest {
    private val settings=BackupSettings(apiBaseUrl="https://example.invalid",apiModel="fixture",aiName="TA",userName="user",
        persona="",historySize=40,wallpaper=null,glassMode="Auto",wallpaperDark=null,wallpaperHue=null,wallpaperChroma=null)
    private val group=ChatGroupEntity(1,"合成群",0,0,draft="原来的草稿",mentions="[22]")
    private val members=listOf(GroupMemberEntity(1,11,0,"同名"),GroupMemberEntity(1,22,1,"同名"))
    private fun payload():BackupFile {
        val turns=mutableListOf<GroupTurnEntity>();val rows=mutableListOf<GroupMessageEntity>()
        repeat(3) { n ->
            val tid=(n+1).toLong();val anchor=n*3L+1;val ids=GroupCallRules.order(listOf(11,22),n)
            rows+=GroupMessageEntity(anchor,1,"user",GroupCallRules.userWords("第${n}轮转写"),tid,mentions="[${ids.joinToString()}]")
            turns+=GroupTurnEntity(tid,1,anchor,"[${ids.joinToString()}]",tid,status=if(n==2)"running" else "completed")
            ids.forEachIndexed { i,id -> rows+=GroupMessageEntity(anchor+i+1,1,"assistant",
                GroupCallRules.transcript("已经播完的第${n}轮${id}发言。",if(n==2 && i==0)"未完整播完的下一句话。" else ""),
                tid,speakerId=id,speakerName="同名",turnId=tid,status=if(n==2)"running" else "sent") }
        }
        return BackupFile(BackupFile.FORMAT,BackupFile.VERSION,0,settings,emptyList(),emptyList(),emptyList(),emptyList(),
            groups=listOf(group),groupMembers=members,groupTurns=turns,groupMessages=rows)
    }
    @Test fun fullBackupPayloadKeepsThreeCallRoundsAuthorsAndUntouchedDraft() {
        val original=payload();val read=Json.decodeFromString<BackupFile>(Json.encodeToString(original))
        GroupBackupRules.validate(read.groups,read.groupMembers,read.groupTurns,read.groupMessages)
        assertEquals(original,read);assertEquals(9,read.groupMessages.size)
        assertEquals(listOf(11L,22,22,11,11,22),read.groupMessages.filter { it.role=="assistant" }.map { it.speakerId })
        assertEquals("原来的草稿",read.groups.single().draft);assertTrue(read.groupMessages.all { it.audio==null })
        assertEquals(15,read.version)
    }
    @Test fun restoredUnfinishedCallKeepsPlayedWordsAndDoesNotRemainRunning() {
        val original=payload();val read=original.copy(groupTurns=original.groupTurns.map(GroupBackupRules::restored),
            groupMessages=original.groupMessages.map(GroupBackupRules::restored))
        GroupBackupRules.validate(read.groups,read.groupMembers,read.groupTurns,read.groupMessages)
        assertTrue(read.groupTurns.none { it.status=="running" })
        assertTrue(read.groupMessages.none { it.status in setOf("running","pending") })
        assertEquals(original.groupMessages.map { it.content },read.groupMessages.map { it.content })
        assertTrue(read.groupMessages.any { it.content.contains("未完整播完") })
    }
    @Test fun completedCallCannotBeRestoredUnderADifferentSpeakerOrTurn() {
        val original=payload()
        for (bad in listOf(original.groupMessages[1].copy(speakerId=99),original.groupMessages[1].copy(turnId=3))) {
            assertThrows(IllegalArgumentException::class.java) {
                GroupBackupRules.validate(original.groups,original.groupMembers,original.groupTurns,original.groupMessages.map { if(it.id==bad.id)bad else it })
            }
        }
    }
}
