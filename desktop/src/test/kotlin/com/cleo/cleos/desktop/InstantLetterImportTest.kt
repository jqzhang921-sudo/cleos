package com.cleo.cleos.desktop

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

internal const val AI_LETTER=9100001L
internal const val USER_LETTER=9100002L
internal const val DRAFT_LETTER=9100003L
internal const val FUTURE_LETTER=9100004L
internal const val OTHER_LETTER=9100005L
internal const val INSTANT_TITLE="合成即时信：约定的星空"
internal const val INSTANT_BODY="INSTANT_AI_BODY\n\n这封信来自合成测试，不包含真人资料。"
internal fun instantFixture(folder:Path,transform:(JsonObject)->JsonObject={it}):Path {
    val old=mobileFixture(folder)
    return rewriteBackup(old,folder.resolve("instant.zip")) {files->changeDocument(files){root->
        val at=root.number("exportedAt")-100
        val theme=diskJson.encodeToString(MobileEnvelope(id="starry",version=2,name="星夜银河",envelope=0xFF282D48,paper=0xFFF2F1FA,ink=0xFF27232C,seal=0xFF7972AE,envelopeInk=0xFFFFFCFF,decoration="stars",sealMark="星"))
        fun letter(id:Long,owner:Long,author:String,body:String,delivery:Long?,title:String="合成测试信")=buildJsonObject {
            put("id",id);put("companionId",owner);put("author",author);put("content",body);put("title",title)
            put("createdAt",at);put("sourceType","instant");put("envelope",theme);put("deliverAt",delivery?.let{JsonPrimitive(it)}?:JsonNull)
        }
        val ai=letter(AI_LETTER,7,"ai",INSTANT_BODY,at,INSTANT_TITLE)
        val letters=root.rows("letters")+listOf(ai,letter(USER_LETTER,7,"me","USER_INSTANT_BODY",at+1),letter(DRAFT_LETTER,7,"me","PRIVATE_DRAFT_ONLY",null),letter(FUTURE_LETTER,7,"ai","UNDELIVERED_MUST_NOT_LEAK",at+1000),letter(OTHER_LETTER,8,"ai","OTHER_TA_PRIVATE",at))
        fun message(id:Long,role:String,letter:Long)=buildJsonObject {
            put("id",id);put("conversationId",11);put("role",role);put("content","");put("letterId",letter);put("createdAt",at+2)
        }
        val favorite=buildJsonObject {
            put("id",9200001);put("companionId",7);put("createdAt",at+4)
            put("parts",buildJsonArray {add(buildJsonObject {put("role","assistant");put("name","合成 TA");put("at",at);put("text","");put("letter",ai)})}.toString())
        }
        transform(JsonObject(root+mapOf("version" to JsonPrimitive(32),"letters" to JsonArray(letters),
            "messages" to JsonArray(root.rows("messages",true)+listOf(message(9300001,"assistant",AI_LETTER),message(9300002,"user",USER_LETTER))),
            "favorites" to JsonArray(root.rows("favorites")+favorite))))
    }}
}

class InstantLetterImportTest {
    @get:Rule val temp=TemporaryFolder()
    private fun fixture(change:(JsonObject)->JsonObject={it})=instantFixture(temp.newFolder().toPath(),change)
    private fun preview(change:(JsonObject)->JsonObject={it})=MobileBackupImporter().inspect(fixture(change))
    @Test fun `local image material survives import as frozen decoration and ships with matching resource`() {
        val material=MobileEnvelope(id="frost-fiber-v1",version=2,name="雾光织纹",decoration="frost-fiber-v1").checked()
        val p=preview { root -> JsonObject(root+("letters" to JsonArray(root.rows("letters").map {
            JsonObject(it+("envelope" to JsonPrimitive(diskJson.encodeToString(material))))
        }))) }
        assertTrue(p.data.mobileLetters.isNotEmpty())
        assertTrue(p.data.mobileLetters.all{it.envelope==material})
        assertNotNull(javaClass.getResource("/envelopes/frost-fiber-v1.webp"))
    }
    @Test fun `actual Android format 32 exporter archive imports canonical letters themes and tool history together`() {
        val source=temp.newFolder().toPath().resolve("actual.zip")
        javaClass.getResourceAsStream("/mobile/android-v32-instant-letters.zip")!!.use{Files.copy(it,source)}
        val p=MobileBackupImporter().inspect(source)
        assertEquals(32,p.version);assertTrue(p.data.mobileLetters.isNotEmpty())
        assertTrue(p.data.mobileLetters.any{it.envelope.id=="starry"})
        assertTrue(p.data.mobileLetters.any{it.envelope.id.startsWith("custom-")})
        val messages=p.data.conversations.flatMap{cv->cv.messages.filter{it.mobileLetterId!=null}.map{cv to it}}
        assertTrue(messages.isNotEmpty())
        messages.forEach{(cv,m)->
            assertEquals("",m.text);val l=p.data.letterFor(cv,m)!!
            val ta=p.data.companions.first{it.id==l.ownerId}
            assertTrue(prompt(p.data,cv,ta).any{it.content.contains(l.text)})
        }
        val dir=temp.newFolder().toPath();MobileBackupImporter().write(p,dir)
        assertEquals(p.sha256,fileDigest(dir.resolve("source/phone-backup.zip")))
        assertTrue(readPhoneKeeps(dir,p.data)!!.letters.any{it.instant!=null})
        val original=fixtureParts(source).getValue("backup.json").decodeToString()
        assertTrue(original.contains("letterDispatches"));assertTrue(original.contains("customEnvelopeThemes"))
    }
    @Test fun `new archive imports typed references while one canonical body supplies only its owners private context`() {
        val p=preview();assertEquals(32,p.version);assertEquals(5,p.data.version)
        assertEquals(3,p.data.mobileLetters.size)
        val chat=p.data.conversations.first{it.id=="mobile-private-11"};val links=chat.messages.filter{it.mobileLetterId!=null}
        assertEquals(2,links.size);assertTrue(links.all{it.text.isEmpty()})
        val ta=p.data.companions.first{it.id=="mobile-ta-7"}
        val words=prompt(p.data,chat,ta).joinToString {it.content}
        assertTrue(words.contains(INSTANT_BODY));assertTrue(words.contains("USER_INSTANT_BODY"))
        listOf("PRIVATE_DRAFT_ONLY","UNDELIVERED_MUST_NOT_LEAK","OTHER_TA_PRIVATE").forEach{assertFalse(it,words.contains(it))}
        val group=p.data.conversations.first{it.group}
        assertFalse(prompt(p.data,group,ta).joinToString {it.content}.contains(INSTANT_BODY))
        assertEquals(1,searchConversation(chat,"合成即时信",data=p.data).size)
        assertEquals(1,searchConversation(chat,"INSTANT_AI_BODY",data=p.data).size)
    }
    @Test fun `broken role owner draft future and orphan references reject the whole import before touching an existing library`() {
        val root=temp.newFolder().toPath();LocalStore(root).use{it.save(DesktopData.fresh())};val before=fileDigest(root.resolve("library.json"))
        for(ref in listOf(OTHER_LETTER,DRAFT_LETTER,FUTURE_LETTER,123L,USER_LETTER)) {
            assertThrows("ref $ref",ImportFailure::class.java) {preview{r->JsonObject(r+("messages" to JsonArray(r.rows("messages").map{if(it.number("id")==9300001L)JsonObject(it+("letterId" to JsonPrimitive(ref)))else it})))}}
            assertEquals(before,fileDigest(root.resolve("library.json")))
        }
    }
    @Test fun `invalid frozen envelope or conflicting inline body is never silently replaced by a default`() {
        assertThrows(ImportFailure::class.java){preview{r->JsonObject(r+("letters" to JsonArray(r.rows("letters").map{if(it.number("id")==AI_LETTER)JsonObject(it+("envelope" to JsonPrimitive("{}broken")))else it})))}}
        assertThrows(ImportFailure::class.java){preview{r->JsonObject(r+("messages" to JsonArray(r.rows("messages").map{if(it.number("id")==9300001L)JsonObject(it+("content" to JsonPrimitive("conflicting body")))else it})))}}
        val t=MobileEnvelope();assertThrows(IllegalArgumentException::class.java){t.copy(seal=0x00112233).checked()}
        assertThrows(IllegalArgumentException::class.java){t.copy(decoration="download-url").checked()}
    }
    @Test fun `mailbox and independently saved favorite render frozen appearance and keep own draft but hide future mail`() {
        val p=preview();val dir=temp.newFolder().toPath();MobileBackupImporter().write(p,dir)
        val keeps=readPhoneKeeps(dir,p.data)!!
        val l=keeps.letters.first{it.id==AI_LETTER};assertEquals(INSTANT_TITLE,l.title);assertEquals("starry",l.instant!!.envelope.id)
        assertTrue(keeps.letters.any{it.id==DRAFT_LETTER && it.draft});assertFalse(keeps.letters.any{it.id==FUTURE_LETTER})
        val f=keeps.favorites.first{it.id==9200001L};assertEquals(INSTANT_TITLE,f.title);assertEquals(INSTANT_BODY,f.parts.single().instant!!.text)
        assertTrue(f.matches("INSTANT_AI_BODY"))
    }
    @Test fun `export restore and history branching retain one canonical letter and preserve original phone archive bytes`() {
        val p=preview{r->
            val clone=JsonObject(r.rows("messages").first{it.number("id")==9300001L}+mapOf("id" to JsonPrimitive(9300011),"conversationId" to JsonPrimitive(99)))
            val cv=JsonObject(r.rows("conversations").first{it.number("id")==11L}+("id" to JsonPrimitive(99)))
            JsonObject(r+mapOf("messages" to JsonArray(r.rows("messages")+clone),"conversations" to JsonArray(r.rows("conversations")+cv)))
        }
        assertEquals(1,p.data.mobileLetters.count{it.id==AI_LETTER})
        val dir=temp.newFolder().toPath();MobileBackupImporter().write(p,dir);assertEquals(p.sha256,fileDigest(dir.resolve("source/phone-backup.zip")))
        val archive=DesktopBackupArchive();val saved=archive.export(dir,p.data,"合成即时信件",temp.newFolder().toPath().resolve("desktop.zip"))
        val restored=temp.newFolder().toPath();archive.write(saved,restored)
        assertEquals(p.data.mobileLetters,saved.data.mobileLetters);assertEquals(p.sha256,fileDigest(restored.resolve("source/phone-backup.zip")))
        assertEquals(readPhoneKeeps(dir,p.data),readPhoneKeeps(restored,saved.data))
        LocalStore(restored).use{assertEquals(p.data.mobileLetters,it.load().mobileLetters)}
    }
    @Test fun `long Unicode letters remain whole while model context is bounded and rendered chunks join exactly`() {
        val text="a".repeat(11999)+"😀"+"长篇\n".repeat(120000)
        val l=MobileLetter(1,"mobile-ta-7",false,"长信",text,10,envelope=MobileEnvelope()).checked()
        assertFalse(l.context().any{Character.isSurrogate(it)});assertTrue(l.context().length<12500)
        val chunks=letterChunks(text);assertEquals(text,chunks.joinToString(""));assertTrue(chunks.all{it.length<=4000})
        assertTrue(chunks.none{it.isNotEmpty() && (Character.isLowSurrogate(it.first()) || Character.isHighSurrogate(it.last()))})
    }
}
