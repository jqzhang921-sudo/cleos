package com.cleo.cleos.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

class DesktopBackupTest {
    @get:Rule val temp=TemporaryFolder()
    private fun fresh()=DesktopData.fresh().let { d -> d.copy(
        companions=d.companions.map { it.copy(name="合成角色",persona="我的角色",notes="手写笔记",endpoint=Endpoint("http://127.0.0.1:12345/v1","fake-model",true)) },
        conversations=d.conversations.map { it.copy(draft="还没有保存的草稿",messages=listOf(Message(text="电脑新增正文"),Message(speakerId=d.companions.first().id,text="回复",reasoning="折叠思考",status="interrupted",error="合成中断"))) }) }
    private fun archive(data:DesktopData=fresh()):Path=DesktopBackupArchive().export(temp.newFolder().toPath(),data,"合成电脑资料",temp.newFolder().toPath().resolve("desktop.zip")).source

    @Test fun `snapshot restores drafts reasoning partial status notes identity and disables HTTP`() {
        val data=fresh();val file=archive(data);val preview=DesktopBackupArchive().inspect(file)
        assertEquals(data.copy(companions=data.companions.map {it.copy(endpoint=it.endpoint.copy(allowHttp=false))}),preview.data)
        val destination=temp.newFolder().toPath();DesktopBackupArchive().write(preview,destination)
        LocalStore(destination).use {assertEquals(preview.data,it.load())}
        assertEquals("合成电脑资料",preview.libraryName);assertEquals(2,preview.counts["消息"])
    }
    @Test fun `export allowlist never includes vault lock previous snapshots or unrelated files`() {
        val root=temp.newFolder().toPath()
        for(name in listOf("keys.dpapi","keys.dpapi.bak","library.json.bak","desktop.lock","workspace.json","some-private.txt"))Files.writeString(root.resolve(name),"DO-NOT-EXPORT-THIS")
        Files.createDirectories(root.resolve("libraries/other"));Files.writeString(root.resolve("libraries/other/library.json"),"OTHER-LIBRARY")
        val out=temp.newFolder().toPath().resolve("backup.zip")
        DesktopBackupArchive().export(root,fresh(),"测试",out)
        val parts=fixtureParts(out)
        assertEquals(setOf("manifest.json","library.json"),parts.keys)
        assertTrue(parts.values.none {it.toString(Charsets.UTF_8).contains("DO-NOT-EXPORT-THIS") || it.toString(Charsets.UTF_8).contains("OTHER-LIBRARY")})
    }
    @Test fun `full mobile originals and every media file survive repeated desktop backups without nesting`() {
        val mobile=MobileBackupImporter().inspect(mobileFixture(temp.newFolder().toPath()))
        val root=temp.newFolder().toPath();MobileBackupImporter().write(mobile,root)
        val desktopData=mobile.data.copy(conversations=mobile.data.conversations.mapIndexed {i,c ->if(i==0)c.copy(draft="电脑上新写的草稿") else c})
        val file=temp.newFolder().toPath().resolve("first.zip")
        DesktopBackupArchive().export(root,desktopData,"导入后继续聊",file)
        val preview=DesktopBackupArchive().inspect(file);assertTrue(preview.hasPhoneArchive)
        val restored=temp.newFolder().toPath();DesktopBackupArchive().write(preview,restored)
        assertEquals(mobile.sha256,fileDigest(restored.resolve("source/phone-backup.zip")))
        assertTrue(Files.readString(restored.resolve("source/backup.json")).contains("合成私密日记"))
        assertEquals(fileDigest(root.resolve("media/voice-fixture.m4a")),fileDigest(restored.resolve("media/voice-fixture.m4a")))
        val next=temp.newFolder().toPath().resolve("next.zip")
        DesktopBackupArchive().export(restored,preview.data,"再次备份",next)
        assertEquals(fixtureParts(file).keys,fixtureParts(next).keys)
        assertEquals(desktopData,DesktopBackupArchive().inspect(next).data)
        ZipFile(next.toFile()).use {zip ->assertEquals(1,zip.entries().asSequence().count {it.name.endsWith(".zip")})}
    }
    @Test fun `workspace captures unsaved draft and restore creates separate library duplicate keeps later edits`() = runBlocking {
        withContext(Dispatchers.Swing) {
            val vault=MemoryVault();val original=temp.newFolder().toPath();val file=temp.newFolder().toPath().resolve("out.zip")
            DesktopWorkspace(original,vault).use { source ->
                val controller=source.controller!!;controller.draft("刚输入还没到延迟保存时间")
                controller.notes(controller.data.companions.first().id,"我的小本本")
                vault.put("https://api.deepseek.com","synthetic-secret-not-exported")
                assertNotNull(source.exportBackup(file));assertEquals("local",source.selected)
            }
            val target=temp.newFolder().toPath()
            DesktopWorkspace(target,MemoryVault()).use { receiver ->
                receiver.controller!!.draft("原资料一定保留")
                val preview=DesktopBackupArchive().inspect(file)
                assertTrue(receiver.restoreBackup(preview,"恢复测试"));val id=receiver.selected
                assertEquals(2,receiver.libraries.size);assertNotEquals("local",id)
                assertEquals("刚输入还没到延迟保存时间",receiver.controller!!.selected.draft)
                assertFalse(receiver.controller!!.hasKey(receiver.controller!!.data.companions.first()))
                receiver.controller!!.draft("恢复后又改了")
                assertTrue(receiver.switch("local"));assertEquals("原资料一定保留",receiver.controller!!.selected.draft)
                assertTrue(receiver.restoreBackup(preview,"不会重复导入"));assertEquals(id,receiver.selected)
                assertEquals("恢复后又改了",receiver.controller!!.selected.draft);assertEquals(2,receiver.libraries.size)
            }
            DesktopWorkspace(target,MemoryVault()).use {assertEquals("恢复后又改了",it.controller!!.selected.draft)}
        }
    }
    @Test fun `existing output and protected workspace destination are never overwritten`() = runBlocking {
        val data=fresh();val dir=temp.newFolder().toPath();val target=temp.newFile().toPath();Files.writeString(target,"keep")
        assertThrows(ImportFailure::class.java) {DesktopBackupArchive().export(dir,data,"test",target)}
        assertEquals("keep",Files.readString(target))
        withContext(Dispatchers.Swing) {DesktopWorkspace(dir,MemoryVault()).use { w ->
            assertNull(w.exportBackup(dir.resolve("new.zip")));assertFalse(Files.exists(dir.resolve("new.zip")))
        }}
    }
    @Test fun `changed archive after preview rolls back without publishing or altering original library`() = runBlocking {
        val file=archive();val preview=DesktopBackupArchive().inspect(file)
        Files.write(file,byteArrayOf(1),java.nio.file.StandardOpenOption.APPEND)
        val root=temp.newFolder().toPath()
        withContext(Dispatchers.Swing) {DesktopWorkspace(root,MemoryVault()).use {w ->
            w.controller!!.draft("原草稿");assertFalse(w.restoreBackup(preview,"不应出现"))
            assertEquals("local",w.selected);assertEquals(1,w.libraries.size);assertEquals("原草稿",w.controller!!.selected.draft)
            assertEquals(0,Files.list(root.resolve("libraries")).use {it.count()}.toInt())
        }}
    }
    @Test fun `traversal unknown vault reserved and case duplicate paths are rejected`() {
        val file=archive()
        for(name in listOf("../escape.txt","media/../escape.png","media\\photo.png","C:/escape.png","media/CON.png","keys.dpapi","Library.json","source/unknown.txt")) {
            val bad=rewriteBackup(file,temp.newFolder().toPath().resolve("bad.zip")) {it[name]=byteArrayOf(1)}
            assertThrows(ImportFailure::class.java) {DesktopBackupArchive().inspect(bad)}
        }
    }
    @Test fun `altered file manifest count and future versions are rejected`() {
        val file=archive()
        val mutations:List<(MutableMap<String,ByteArray>)->Unit> = listOf(
            {it["library.json"]=fresh().copy(userName="篡改内容").let(diskJson::encodeToString).toByteArray()},
            {parts -> val root=Json.parseToJsonElement(parts.getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject
                parts["manifest.json"]=JsonObject(root+("version" to JsonPrimitive(2))).toString().toByteArray()},
            {parts -> val root=Json.parseToJsonElement(parts.getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject
                parts["manifest.json"]=JsonObject(root+("files" to JsonArray(emptyList()))).toString().toByteArray()})
        mutations.forEach {mutation ->val bad=rewriteBackup(file,temp.newFolder().toPath().resolve("bad.zip"),mutation)
            assertThrows(ImportFailure::class.java) {DesktopBackupArchive().inspect(bad)}}
    }
    @Test fun `size limits and cancelled export leave no final or partial file`() {
        val file=archive()
        for(reader in listOf(DesktopBackupArchive(maxJsonBytes=5),DesktopBackupArchive(maxExpandedBytes=10),DesktopBackupArchive(maxEntries=1),DesktopBackupArchive(maxArchiveBytes=10)))
            assertThrows(ImportFailure::class.java) {reader.inspect(file)}
        val output=temp.newFolder().toPath();val target=output.resolve("cancel.zip")
        assertThrows(CancellationException::class.java) {DesktopBackupArchive().export(temp.newFolder().toPath(),fresh(),"cancel",target) {throw CancellationException("synthetic")}}
        assertEquals(0,Files.list(output).use {it.count()}.toInt())
    }
    @Test fun `missing references remain explicit but missing original phone archive aborts export`() {
        val data=fresh().let {it.copy(userAvatar="media/missing.png")}
        val preview=DesktopBackupArchive().inspect(archive(data));assertEquals(listOf("media/missing.png"),preview.missingMedia)
        val mobile=MobileBackupImporter().inspect(mobileFixture(temp.newFolder().toPath()))
        val out=temp.newFolder().toPath();assertThrows(ImportFailure::class.java) {DesktopBackupArchive().export(temp.newFolder().toPath(),mobile.data,"missing-original",out.resolve("bad.zip"))}
        assertEquals(0,Files.list(out).use {it.count()}.toInt())
    }
    @Test fun `destination created during export is preserved and partial file is cleaned`() {
        val root=temp.newFolder().toPath();val out=temp.newFolder().toPath();val target=out.resolve("race.zip")
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
            DesktopBackupArchive().export(root,fresh(),"race",target) {if(!Files.exists(target))Files.writeString(target,"created-by-someone-else")}
        }
        assertEquals("created-by-someone-else",Files.readString(target))
        assertEquals(listOf(target),Files.list(out).use {it.toList()})
    }
    @Test fun `validly rehashed HTTP permission is reset and future desktop data still rejected`() {
        val file=archive()
        fun modified(data:DesktopData):Path=rewriteBackup(file,temp.newFolder().toPath().resolve("modified.zip")) {parts ->
            val bytes=diskJson.encodeToString(data).toByteArray(Charsets.UTF_8);parts["library.json"]=bytes
            val manifest=diskJson.decodeFromString<DesktopBackupManifest>(parts.getValue("manifest.json").toString(Charsets.UTF_8))
            val sha=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
            parts["manifest.json"]=diskJson.encodeToString(manifest.copy(files=listOf(BackupFile("library.json",bytes.size.toLong(),sha)))).toByteArray(Charsets.UTF_8)
        }
        val data=DesktopBackupArchive().inspect(file).data
        assertFalse(DesktopBackupArchive().inspect(modified(data.copy(companions=data.companions.map {it.copy(endpoint=it.endpoint.copy(allowHttp=true))}))).data.companions.first().endpoint.allowHttp)
        assertThrows(ImportFailure::class.java) {DesktopBackupArchive().inspect(modified(data.copy(version=5)))}
        assertThrows(ImportFailure::class.java) {DesktopBackupArchive().inspect(modified(data.copy(userAvatar="../keys.dpapi")))}
    }
}
