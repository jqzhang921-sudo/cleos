package com.cleo.cleos.desktop

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

internal fun keepsFixture(folder:Path):Path {
    val source=notebookFixture(folder)
    return rewriteBackup(source,folder.resolve("keeps.zip")){files->changeDocument(files){root->
        val at=root.number("exportedAt")
        fun letter(id:Long,mine:Boolean,text:String,delivery:Long?,owner:Long=7)=buildJsonObject {
            put("id",id);put("companionId",owner);put("author",if(mine)"me" else "ai");put("content",text)
            put("createdAt",at-100);put("deliverAt",delivery?.let(::JsonPrimitive)?:JsonNull)
        }
        fun part(name:String,text:String,mine:Boolean=false)=buildJsonObject {
            put("messageId",9000);put("at",at-200);put("role",if(mine)"user" else "assistant");put("name",name);put("text",text)
            put("gapBefore",!mine)
            if(!mine) {
                put("audio",buildJsonObject {put("file","voice-fixture.m4a");put("ms",1500)})
                put("images",buildJsonArray {add(buildJsonObject {
                    put("file","photo.png");put("source","合成图片来源")
                    put("credit",buildJsonObject {put("title","合成图片");put("creator","测试作者");put("license","CC BY 4.0");put("pageUrl","https://example.invalid/synthetic");put("licenseUrl","https://example.invalid/license")})
                })})
            }
        }
        val parts=JsonArray(listOf(part("合成用户","合成收藏：书架旁的话",true),part("历史署名","合成收藏：独立保存的回答"))).toString()
        fun favorite(id:Long,owner:Long=7)=buildJsonObject {put("id",id);put("companionId",owner);put("conversationId",9999);put("sourceKey","deleted-source");put("createdAt",at-50);put("parts",parts);put("note","合成备注：想留下这句话")}
        JsonObject(root+mapOf("letters" to JsonArray(listOf(
            letter(1,false,"合成来信：窗外的晚风\n合成来信全文。",at-10),letter(2,true,"合成寄出：路上的树",at-20),
            letter(3,false,"绝不能提前显示的来信",at+100),letter(4,false,"没有送达时间的隐藏来信",null),
            letter(5,true,"合成草稿：尚未寄出",null),letter(6,false,"另一位 TA 的合成来信",at-30,8)
        )),"favorites" to JsonArray(listOf(favorite(1),JsonObject(favorite(2)+mapOf("removedAt" to JsonPrimitive(at-1),"parts" to JsonPrimitive("已取消收藏的隐藏内容"))),favorite(3,8)))))
    }}
}

class PhoneKeepsTest {
    @get:Rule val temp=TemporaryFolder()
    private fun imported(source:Path=keepsFixture(temp.newFolder().toPath())):Pair<Path,DesktopData> {
        val preview=MobileBackupImporter().inspect(source);val root=temp.newFolder().toPath();MobileBackupImporter().write(preview,root)
        return root to preview.data
    }
    @Test fun `letters use export time arrival rules retain drafts and never decode unseen bodies`() {
        val source=keepsFixture(temp.newFolder().toPath())
        val changed=rewriteBackup(source,temp.newFolder().toPath().resolve("hidden.zip")){files->changeDocument(files){root->
            JsonObject(root+("letters" to JsonArray(root.rows("letters").map {if(it.number("id") in setOf(3L,4L))JsonObject(it+("content" to buildJsonObject {put("mustNotDecode",true)}))else it})))
        }}
        val (root,data)=imported(changed);val keeps=readPhoneKeeps(root,data)!!
        assertEquals(setOf(1L,2L,5L,6L),keeps.letters.map {it.id}.toSet());assertEquals(0,keeps.skippedLetters)
        assertTrue(keeps.letters.first {it.id==5L}.draft);assertTrue(keeps.letters.first {it.id==1L}.unread)
        assertEquals("手机甲",keeps.letters.first().owner.name)
        assertFalse(keeps.toString().contains("mustNotDecode"));assertFalse(keeps.toString().contains("隐藏来信"))
        assertFalse(keeps.letters.any {it.matches("绝不能提前")})
    }
    @Test fun `favorites retain copied parts gaps attribution notes and local media after source deletion`() {
        val (root,data)=imported();val keeps=readPhoneKeeps(root,data)!!
        assertEquals(setOf(1L,3L),keeps.favorites.map {it.id}.toSet());assertEquals(0,keeps.skippedFavorites)
        val favorite=keeps.favorites.first {it.id==1L}
        assertEquals("历史署名",favorite.parts.last().name);assertTrue(favorite.parts.last().gapBefore)
        assertTrue(favorite.matches("想留下"));assertTrue(favorite.matches("历史署名"))
        val attachments=favorite.parts.last().attachments
        assertEquals(listOf("audio","image"),attachments.map {it.kind});assertTrue(attachments.last().credit.contains("CC BY 4.0"))
        assertTrue(attachments.all {localMedia(root,it.file)!=null});assertFalse(keeps.toString().contains("已取消收藏的隐藏内容"))
        assertTrue(data.conversations.none {it.id.contains("9999")})
    }
    @Test fun `browsing changes neither source files desktop prompts nor read state and survives backup restore`() {
        val (root,data)=imported();val paths=listOf("source/phone-backup.zip","source/backup.json","library.json")
        val before=paths.associateWith {fileDigest(root.resolve(it))};val prompts=data.conversations.map {prompt(data,it,data.companions.first())}
        val keeps=readPhoneKeeps(root,data)
        assertEquals(before,paths.associateWith {fileDigest(root.resolve(it))});assertEquals(prompts,data.conversations.map {prompt(data,it,data.companions.first())})
        assertFalse(prompts.toString().contains("合成来信"));assertFalse(prompts.toString().contains("合成收藏"))
        val archive=DesktopBackupArchive();val saved=archive.export(root,data,"合成库",temp.newFolder().toPath().resolve("backup.zip"))
        val restored=temp.newFolder().toPath();archive.write(saved,restored)
        assertEquals(keeps,readPhoneKeeps(restored,saved.data));assertEquals(4,saved.data.version)
    }
    @Test fun `malformed visibility roles timestamps paths and duplicate IDs are reported and do not expose records`() {
        val source=keepsFixture(temp.newFolder().toPath())
        val changed=rewriteBackup(source,temp.newFolder().toPath().resolve("bad.zip")){files->changeDocument(files){root->
            val letter=root.rows("letters").first();val fav=root.rows("favorites").first()
            val badParts=buildJsonArray {add(buildJsonObject {put("role","tool");put("at",1);put("text","不应显示的工具内容")})}.toString()
            val badPath=buildJsonArray {add(buildJsonObject {put("role","user");put("at",1);put("text","不安全附件");put("images",buildJsonArray {add(buildJsonObject {put("file","../private.png")})})})}.toString()
            JsonObject(root+mapOf("letters" to JsonArray(listOf(letter,letter,
                JsonObject(letter+mapOf("id" to JsonPrimitive(20),"author" to JsonPrimitive("unknown"))),
                JsonObject(letter+mapOf("id" to JsonPrimitive(21),"deliverAt" to JsonPrimitive("0"))))),
                "favorites" to JsonArray(listOf(fav,fav,JsonObject(fav+mapOf("id" to JsonPrimitive(20),"parts" to JsonPrimitive(badParts))),
                    JsonObject(fav+mapOf("id" to JsonPrimitive(21),"parts" to JsonPrimitive(badPath)))))))
        }}
        val (root,data)=imported(changed);val keeps=readPhoneKeeps(root,data)!!
        assertEquals(1,keeps.letters.size);assertEquals(3,keeps.skippedLetters)
        assertEquals(1,keeps.favorites.size);assertEquals(3,keeps.skippedFavorites)
        assertFalse(keeps.toString().contains("不应显示"));assertFalse(keeps.toString().contains("../private"))
    }
    @Test fun `older snapshots missing collections show empty and bounded fields warn on truncation`() {
        val source=keepsFixture(temp.newFolder().toPath())
        val old=rewriteBackup(source,temp.newFolder().toPath().resolve("old.zip")){files->changeDocument(files){JsonObject(it-setOf("letters","favorites"))}}
        val (root,data)=imported(old);val empty=readPhoneKeeps(root,data)!!;assertTrue(empty.letters.isEmpty());assertTrue(empty.favorites.isEmpty())
        val long=rewriteBackup(source,temp.newFolder().toPath().resolve("long.zip")){files->changeDocument(files){doc->
            val row=doc.rows("letters").first()
            val changed=JsonObject(row+("content" to JsonPrimitive("x".repeat(200001))))
            JsonObject(doc+("letters" to JsonArray(listOf(changed))))
        }}
        val (longRoot,longData)=imported(long);val letter=readPhoneKeeps(longRoot,longData)!!.letters.single()
        assertTrue(letter.limited);assertEquals(200000,letter.text.length)
    }
    @Test fun `snapshot limits missing file and mismatched origin fail read only`() {
        val (root,data)=imported();val before=fileDigest(root.resolve("library.json"))
        assertThrows(ImportFailure::class.java){readPhoneKeeps(root,data,50)}
        assertThrows(ImportFailure::class.java){readPhoneKeeps(root,data.copy(importOrigin=data.importOrigin!!.copy(exportedAt=1)))}
        Files.move(root.resolve("source/backup.json"),root.resolve("source/held.json"))
        assertThrows(ImportFailure::class.java){readPhoneKeeps(root,data)};assertNull(readPhoneKeeps(root,DesktopData.fresh()))
        assertEquals(before,fileDigest(root.resolve("library.json")))
    }
}
