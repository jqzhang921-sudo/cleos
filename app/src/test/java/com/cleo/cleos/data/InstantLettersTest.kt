package com.cleo.cleos.data

import com.cleo.cleos.ai.LetterRules
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.LetterEntity
import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.ui.chat.ChatRow
import com.cleo.cleos.ui.chat.buildRows
import com.cleo.cleos.ui.letters.Mailbox
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class InstantLettersTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun letter(id: Long = 3, owner: Long = 1, author: String = "me", sent: Boolean = true) =
        LetterEntity(id, owner, author, "今天完成了一个重要目标。\n\n想认真告诉你。", 100,
            deliverAt = if(sent) 100 else null, sourceType = LetterEntity.INSTANT,
            title = "给你的一封信", envelope = InstantLetterData.encode(InstantLetterData.defaultEnvelope))
    private fun message(role: String = "user", id: Long = 3) = MessageEntity(7, 5, role, "", 100, letterId = id)

    @Test fun olderLettersAndMessagesDecodeWithoutReinterpretingThem() {
        val old = json.decodeFromString<LetterEntity>("""{"id":2,"companionId":1,"author":"me","content":"旧草稿","createdAt":5}""")
        assertEquals(LetterEntity.RETROSPECTIVE, old.sourceType)
        assertTrue(old.draft); assertEquals("",old.title); assertNull(old.envelope)
        val m=json.decodeFromString<MessageEntity>("""{"id":1,"conversationId":5,"role":"user","content":"你好","createdAt":8}""")
        assertNull(m.letterId); assertEquals("你好",m.content)
    }
    @Test fun sentEnvelopeIsAResolvedSnapshotNotTheNextDefault() {
        val original=InstantLetterData.defaultEnvelope
        val sent=letter().copy(envelope=InstantLetterData.encode(original))
        val next=original.copy(name="下次的主题",seal=0xFF226644,paper=0xFFE0EFE0)
        assertNotEquals(next,InstantLetterData.theme(sent.envelope))
        assertEquals(original,InstantLetterData.theme(json.decodeFromString<LetterEntity>(json.encodeToString(sent)).envelope))
    }
    @Test fun brokenOrTransparentEnvelopesAreRejected() {
        assertNull(InstantLetterData.theme("{oops"))
        assertNull(InstantLetterData.theme(InstantLetterData.encode(EnvelopeSnapshot(ink=0x00333333))))
        assertNull(InstantLetterData.theme(InstantLetterData.encode(EnvelopeSnapshot(version=0))))
    }
    @Test fun canonicalBodyIsNotDuplicatedInStoredChat() {
        val m=message();val original=letter()
        val resolved=InstantLetterData.resolve(listOf(m),listOf(original),1).single()
        assertTrue(resolved.content.contains(original.content));assertTrue(resolved.content.contains(original.title))
        assertEquals("",m.content);assertEquals(original,letter())
        assertFalse(json.encodeToString(m).contains(original.title))
    }
    @Test fun otherTasDraftsAndSpoofedSendersNeverReachContext() {
        for(l in listOf(letter(owner=2),letter(sent=false),letter(author="ai"))) {
            val resolved=InstantLetterData.resolve(listOf(message()),listOf(l),1).single()
            assertEquals("note",resolved.role);assertFalse(resolved.content.contains(l.content))
        }
        assertEquals("note",InstantLetterData.resolve(listOf(message()),emptyList(),1).single().role)
        val future=letter(author="ai").copy(deliverAt=500)
        assertEquals("note",InstantLetterData.resolve(listOf(message("assistant")),listOf(future),1,now=200).single().role)
    }
    @Test fun longLettersRemainWholeWhileContextIsBounded() {
        val long=letter().copy(content="一段文字😀\n".repeat(30000))
        val shown=InstantLetterData.resolve(listOf(message()),listOf(long),1).single()
        assertTrue(shown.content.length<12500);assertTrue(shown.content.contains("正文较长"))
        assertEquals(210000,long.content.length)
        val emoji=InstantLetterData.content(letter().copy(content="a".repeat(11999)+"😀tail"))
        assertFalse(emoji.any { Character.isSurrogate(it) })
        assertTrue(emoji.contains("11999"))
    }
    @Test fun emptyAssistantEnvelopeRemainsVisibleAndHasTheRightSide() {
        val m=message("assistant")
        val rows=buildRows(listOf(m))
        assertEquals(m,rows.filterIsInstance<ChatRow.Message>().single().message)
        assertTrue(buildRows(listOf(message())).filterIsInstance<ChatRow.Message>().single().showFace)
    }
    @Test fun instantLettersDoNotScheduleOrBlockRetrospectiveLetters() {
        val at=100L*24*60*60*1000
        val ta=CompanionEntity(id=1,name="TA",apiBaseUrl="",apiModel="",createdAt=1)
        val instant=letter(author="ai").copy(createdAt=at-1,deliverAt=at-1)
        assertTrue(LetterRules.ready(at,ta,listOf(instant),50,0))
        assertEquals(ta.createdAt,LetterRules.materialSince(ta,listOf(instant)))
        assertFalse(Mailbox.replyOnTheWay(listOf(letter()),at))
        assertEquals(1,Mailbox.unread(listOf(instant),at))
        val slow=letter(id=10).copy(sourceType=LetterEntity.RETROSPECTIVE,envelope=null)
        assertTrue(Mailbox.replyOnTheWay(listOf(slow,instant.copy(replyTo=10)),at))
    }
    @Test fun chatPromptReadsLetterAndBackupRoundTripsTheSameCanonicalRecord() {
        val ta=CompanionEntity(id=1,name="TA",apiBaseUrl="",apiModel="",createdAt=1)
        val history=InstantLetterData.resolve(listOf(message()),listOf(letter()),1)
        val prompt=com.cleo.cleos.ai.Prompt.messages(AppSettings(),ta,history,java.time.ZonedDateTime.now())
        assertTrue(prompt.last().content.contains(letter().content))
        assertTrue(prompt.last().content.contains(letter().title))
        val settings=BackupSettings(apiBaseUrl="",apiModel="",aiName="TA",userName="我",persona="",historySize=10,
            wallpaper=null,glassMode="Auto",wallpaperDark=null,wallpaperHue=null,wallpaperChroma=null)
        val backup=BackupFile(BackupFile.FORMAT,BackupFile.VERSION,100,settings,
            listOf(com.cleo.cleos.data.db.ConversationEntity(5,"聊天",1,100,1)),listOf(message()),emptyList(),emptyList(),
            companions=listOf(ta),letters=listOf(letter()))
        val restored=json.decodeFromString<BackupFile>(json.encodeToString(backup))
        assertEquals(backup,restored)
        InstantLetterData.validate(restored.letters,restored.messages,restored.conversations.associate{it.id to it.companionId})
    }
    @Test fun favoritesKeepBodyAndEnvelopeAfterTheOriginalIsGone() {
        val l=letter();val p=FavoriteContent.select(listOf(message()),setOf(7L),"我","TA",mapOf(l.id to l)).single()
        val restored=FavoriteContent.decode(FavoriteContent.encode(listOf(p))).single()
        assertEquals(l,restored.letter);assertTrue(restored.text.contains(l.content))
        assertTrue(FavoriteContent.eligible(message()))
        assertFalse(MessageEdits.eligible(message().copy(content="也不能编辑已寄出的正文")))
    }
    @Test fun backupChecksCanonicalLinksOwnersAndDraftVisibility() {
        assertTrue(BackupFile.VERSION >= 32)
        InstantLetterData.validate(listOf(letter()),listOf(message()),mapOf(5L to 1L))
        InstantLetterData.validate(listOf(letter(sent=false)),emptyList(),mapOf(5L to 1L))
        fun bad(letters:List<LetterEntity>,messages:List<MessageEntity>,owners:Map<Long,Long> = mapOf(5L to 1L)) {
            assertThrows(IllegalArgumentException::class.java){InstantLetterData.validate(letters,messages,owners)}
        }
        bad(emptyList(),listOf(message()))
        bad(listOf(letter()),listOf(message()),mapOf(5L to 2L))
        bad(listOf(letter(sent=false)),listOf(message()))
        bad(listOf(letter()),listOf(message("assistant")))
        bad(listOf(letter()),listOf(message().copy(content="重复或伪造的正文")))
        bad(listOf(letter().copy(envelope="{}broken")),emptyList())
        bad(listOf(letter().copy(content="  ")),listOf(message()))
        bad(listOf(letter(id=0)),emptyList())
        bad(listOf(letter().copy(replyTo=3)),emptyList())
        bad(listOf(letter(author="ai").copy(deliverAt=null)),emptyList())
        bad(listOf(letter()),listOf(message().copy(note="隐藏正文")))
    }
}
