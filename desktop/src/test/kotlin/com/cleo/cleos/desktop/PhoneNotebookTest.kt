package com.cleo.cleos.desktop

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

internal fun notebookFixture(folder:Path):Path {
    val original=mobileFixture(folder)
    val day=LocalDate.of(2026,10,18).toEpochDay()
    fun entry(id:Long,title:String,body:String,secret:Boolean=false,ai:Boolean=false,shared:Boolean=false)=buildJsonObject {
        put("id",id);put("day",day);put("title",title);put("blocks",body);put("author",if(ai)"ai" else "me")
        put("secret",secret);put("secretShared",shared);put("companionId",7);put("publicHint",if(secret && ai)"公开提示：今天想留一点空间" else "")
        put("sharedExcerpt",if(secret && ai)"仅愿意分享的摘录" else "")
    }
    val blocks=buildJsonArray {
        add(buildJsonObject {put("type","text");put("text","合成正文：图片前的一段")})
        add(buildJsonObject {put("type","image");put("file","photo.png");put("width",32);put("height",24)})
        add(buildJsonObject {put("type","text");put("text","合成正文：图片后的一段")})
    }.toString()
    return rewriteBackup(original,folder.resolve("notebook.zip")) {parts ->
        changeDocument(parts) {root ->JsonObject(root+mapOf("diary" to JsonArray(listOf(
            entry(1,"合成日记：散步之后",blocks),entry(2,"自己的合成秘密","私人合成正文",secret=true),
            entry(3,"不可泄露的标题","不可泄露的正文",secret=true,ai=true),entry(4,"TA 分享的合成日记","已分享的合成正文",secret=true,ai=true,shared=true)
        )),"todos" to buildJsonArray {
            add(buildJsonObject {put("id",1);put("title","合成待办：整理书架");put("note","合成备注：把书按颜色排列");put("done",false);put("dueDay",day)})
            add(buildJsonObject {put("id",2);put("title","合成待办：买铅笔");put("note","");put("done",true);put("dueDay",JsonNull)})
        }))}
        val image=syntheticImage(folder.resolve("photo.png"));parts["images/photo.png"]=Files.readAllBytes(image)
    }
}

class PhoneNotebookTest {
    @get:Rule val temp=TemporaryFolder()
    private fun imported(source:Path=notebookFixture(temp.newFolder().toPath())):Pair<Path,DesktopData> {
        val preview=MobileBackupImporter().inspect(source);val target=temp.newFolder().toPath();MobileBackupImporter().write(preview,target)
        return target to preview.data
    }
    @Test fun `current phone diary and todo preserve order authors local images dates and state`() {
        val (root,data)=imported();val book=readPhoneNotebook(root,data)!!
        assertEquals(4,book.diary.size);assertEquals(2,book.todos.size);assertEquals(0,book.skippedDiary)
        val entry=book.diary.first {it.id==1L}
        assertEquals(listOf("合成正文：图片前的一段","media/photo.png","合成正文：图片后的一段"),entry.blocks.map {when(it){is NotebookBlock.Text->it.text;is NotebookBlock.Picture->it.file;is NotebookBlock.Notice->it.text}})
        assertNotNull(localMedia(root,(entry.blocks[1] as NotebookBlock.Picture).file))
        assertEquals(LocalDate.of(2026,10,18).toEpochDay(),book.todos.first().dueDay);assertFalse(book.todos.first().done)
        assertTrue(book.todos.last().done);assertNull(book.todos.last().dueDay)
    }
    @Test fun `unshared TA secret is redacted before preview search and body decoding`() {
        val (root,data)=imported();val book=readPhoneNotebook(root,data)!!
        val locked=book.diary.first {it.id==3L}
        assertTrue(locked.locked);assertEquals("TA 的小秘密",locked.title);assertTrue(locked.blocks.isEmpty())
        assertFalse(locked.matches("不可泄露"));assertTrue(locked.matches("仅愿意分享的摘录"));assertTrue(locked.matches("公开提示"))
        assertEquals("手机甲",locked.author)
        assertFalse(book.diary.first {it.id==2L}.locked);assertTrue(book.diary.first {it.id==2L}.matches("私人合成正文"))
        val shared=book.diary.first {it.id==4L};assertFalse(shared.locked);assertTrue(shared.matches("已分享的合成正文"))
        assertFalse(book.toString().contains("不可泄露"))
    }
    @Test fun `reading leaves original archive desktop library and model prompts unchanged`() {
        val (root,data)=imported();val paths=listOf("source/phone-backup.zip","source/backup.json","library.json")
        val before=paths.associateWith {fileDigest(root.resolve(it))}
        val promptBefore=data.conversations.associate {c->c.id to prompt(data,c,data.companions.first())}
        readPhoneNotebook(root,data)
        assertEquals(before,paths.associateWith {fileDigest(root.resolve(it))})
        assertEquals(promptBefore,data.conversations.associate {c->c.id to prompt(data,c,data.companions.first())})
        assertFalse(promptBefore.toString().contains("合成正文：图片"));assertFalse(promptBefore.toString().contains("私人合成正文"))
    }
    @Test fun `desktop backup restore retains notebook and secret visibility at the current desktop schema`() {
        val (root,data)=imported();val expected=readPhoneNotebook(root,data)
        val archive=DesktopBackupArchive();val file=temp.newFolder().toPath().resolve("notebook-desktop.zip")
        val preview=archive.export(root,data,"test",file);val target=temp.newFolder().toPath();archive.write(preview,target)
        assertEquals(4,preview.data.version);assertEquals(expected,readPhoneNotebook(target,preview.data))
    }
    @Test fun `invalid visibility unknown authors duplicate ids and impossible dates are skipped with counts`() {
        val source=notebookFixture(temp.newFolder().toPath())
        val changed=rewriteBackup(source,temp.newFolder().toPath().resolve("invalid.zip")){parts->changeDocument(parts){root->
            val rows=root["diary"]!!.jsonArray
            JsonObject(root+("diary" to JsonArray(listOf(rows[0],rows[0],
                JsonObject(rows[1].jsonObject+("secret" to JsonNull)),JsonObject(rows[2].jsonObject+("author" to JsonNull)),
                JsonObject(rows[3].jsonObject+("day" to JsonPrimitive(Long.MAX_VALUE))),
                JsonObject(rows[3].jsonObject+mapOf("id" to JsonPrimitive(5),"author" to JsonPrimitive("unknown"))),
                JsonObject(rows[3].jsonObject+mapOf("id" to JsonPrimitive(6),"secretShared" to JsonPrimitive("true")))
            ))))
        }}
        val (root,data)=imported(changed);val book=readPhoneNotebook(root,data)!!
        assertEquals(1,book.diary.size);assertEquals(6,book.skippedDiary);assertEquals(2,book.todos.size)
        assertFalse(book.toString().contains("不可泄露"))
    }
    @Test fun `legacy plain diary and missing optional fields use known old defaults`() {
        val source=notebookFixture(temp.newFolder().toPath())
        val changed=rewriteBackup(source,temp.newFolder().toPath().resolve("old.zip")){parts->changeDocument(parts){root->
            val first=root["diary"]!!.jsonArray.first().jsonObject
            val old=JsonObject(first-setOf("author","secret","secretShared")+("blocks" to JsonPrimitive("旧版合成纯文字")))
            JsonObject(root+("diary" to JsonArray(listOf(old))))
        }}
        val (root,data)=imported(changed);val entry=readPhoneNotebook(root,data)!!.diary.single()
        assertFalse(entry.secret);assertFalse(entry.locked);assertEquals("旧版合成纯文字",(entry.blocks.single() as NotebookBlock.Text).text)
    }
    @Test fun `unsafe image references and unknown block types never open external content`() {
        val source=notebookFixture(temp.newFolder().toPath())
        val changed=rewriteBackup(source,temp.newFolder().toPath().resolve("paths.zip")){parts->changeDocument(parts){root->
            val first=root["diary"]!!.jsonArray.first().jsonObject
            val blocks=buildJsonArray {
                for(file in listOf("../secret.png","https://example.invalid/photo.png","C:\\private.png"))add(buildJsonObject {put("type","image");put("file",file)})
                add(buildJsonObject {put("type","unknown");put("url","https://example.invalid/")})
            }
            val changed=JsonObject(first+("blocks" to JsonPrimitive(blocks.toString())))
            JsonObject(root+("diary" to JsonArray(listOf(changed))))
        }}
        val (root,data)=imported(changed);val blocks=readPhoneNotebook(root,data)!!.diary.single().blocks
        assertEquals(4,blocks.size);assertTrue(blocks.all {it is NotebookBlock.Notice})
    }
    @Test fun `missing oversized and mismatched archive fail without changing chat data`() {
        val (root,data)=imported();val before=fileDigest(root.resolve("library.json"))
        assertThrows(ImportFailure::class.java){readPhoneNotebook(root,data,maxJsonBytes=50)}
        assertThrows(ImportFailure::class.java){readPhoneNotebook(root,data.copy(importOrigin=data.importOrigin!!.copy(exportedAt=1)))}
        Files.move(root.resolve("source/backup.json"),root.resolve("source/held.json"))
        assertThrows(ImportFailure::class.java){readPhoneNotebook(root,data)}
        assertEquals(before,fileDigest(root.resolve("library.json")))
        assertNull(readPhoneNotebook(root,DesktopData.fresh()))
    }
}
