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
import java.util.zip.*

internal fun mobileFixture(folder: Path): Path {
    val target = folder.resolve("phone.zip")
    MobileBackupTest::class.java.getResourceAsStream("/mobile/android-v15.zip")!!.use { Files.copy(it, target) }
    return target
}
internal fun fixtureParts(path: Path): MutableMap<String, ByteArray> = ZipFile(path.toFile()).use { z ->
    z.entries().asSequence().filter { !it.isDirectory }.associate { e -> e.name to z.getInputStream(e).use { it.readBytes() } }.toMutableMap()
}
internal fun rewriteBackup(source: Path, target: Path, transform: (MutableMap<String, ByteArray>) -> Unit): Path {
    val entries = fixtureParts(source); transform(entries)
    ZipOutputStream(Files.newOutputStream(target)).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry() } }
    return target
}
internal fun changeDocument(parts: MutableMap<String, ByteArray>, transform: (JsonObject) -> JsonObject) {
    val root = Json.parseToJsonElement(parts.getValue("backup.json").toString(Charsets.UTF_8)).jsonObject
    parts["backup.json"] = transform(root).toString().toByteArray()
}

class MobileBackupTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture() = mobileFixture(temp.newFolder().toPath())
    private fun modified(source: Path, change: (JsonObject) -> JsonObject) = rewriteBackup(source, temp.newFolder().toPath().resolve("modified.zip")) { changeDocument(it, change) }
    @Test fun `current Android serializer fixture preserves participant identity media thinking and memory`() {
        val preview = MobileBackupImporter().inspect(fixture())
        assertEquals(15, preview.version);assertEquals(2, preview.counts["TA"]);assertEquals(2, preview.counts["群聊"])
        val ta = preview.data.companions.first { it.id == "mobile-ta-7" }
        assertEquals("第一位合成角色",ta.persona);assertEquals("media/avatar.png",ta.avatar);assertEquals("media/user.png",ta.userAvatar)
        assertEquals("细节也保留",ta.memories.single().details.single());assertTrue(ta.memories.single().pinned)
        val chat = preview.data.conversations.first { it.id == "mobile-private-11" }
        assertEquals("合成的折叠思考",chat.messages[1].reasoning);assertEquals("media/photo.png",chat.messages[0].attachments.single().file)
        assertEquals("audio",chat.messages.last().attachments.single().kind);assertEquals("error",chat.messages[3].status)
        assertFalse(preview.data.companions.last().endpoint.allowHttp)
    }
    @Test fun `preview does not create a library or extract attachments`() {
        val path=fixture();val before=Files.list(path.parent).use { it.toList() }
        MobileBackupImporter().inspect(path)
        assertEquals(before,Files.list(path.parent).use { it.toList() })
    }
    @Test fun `departed and deleted members remain attributed history without being reactivated`() {
        val p=MobileBackupImporter().inspect(fixture());val chat=p.data.conversations.first { it.id=="mobile-group-21" }
        assertEquals(listOf("mobile-ta-7","mobile-ta-8"),chat.memberIds)
        assertEquals("已删除的历史 TA",chat.messages[1].speakerName);assertEquals("mobile-ta-99",chat.messages[1].speakerId)
        assertTrue(p.data.companions.none { it.id=="mobile-ta-99" })
        val old=p.data.conversations.first { it.id=="mobile-group-22" };assertTrue(old.readOnly);assertTrue(old.memberIds.isEmpty())
        assertEquals("interrupted",chat.messages.last().status)
    }
    @Test fun `private memories tools and diary never leak into group prompt`() {
        val p=MobileBackupImporter().inspect(fixture());val a=p.data.companions.first { it.id=="mobile-ta-7" }
        val group=p.data.conversations.first { it.id=="mobile-group-21" }
        val words=prompt(p.data,group,a).joinToString { it.content }
        assertFalse(words.contains("只属于甲的私人记忆"));assertFalse(words.contains("合成私密日记"));assertTrue(words.contains("只在群里的共同记忆"))
        val private=p.data.conversations.first { it.id=="mobile-private-11" }
        val direct=prompt(p.data,private,a).joinToString { it.content }
        assertTrue(direct.contains("只属于甲的私人记忆"));assertFalse(direct.contains("不应进入桌面模型上下文"))
        assertFalse(direct.contains("合成的折叠思考"));assertTrue(direct.contains("未发送给模型"))
        assertTrue(prompt(p.data,private,a).all {it.images.isEmpty()})
    }
    @Test fun `only matching enabled lore enters private request`() {
        val p=MobileBackupImporter().inspect(fixture());val a=p.data.companions.first();val c=p.data.conversations.first { !it.group }
        assertFalse(prompt(p.data,c,a).first().content.contains("月亮上的合成小屋"))
        val mentioned=c.copy(messages=c.messages+Message(text="想聊月亮"))
        assertTrue(prompt(p.data,mentioned,a).first().content.contains("月亮上的合成小屋"))
        assertFalse(prompt(p.data,mentioned,a.copy(lore=a.lore.map { it.copy(enabled=false) })).first().content.contains("月亮上的合成小屋"))
    }
    @Test fun `missing files retain references and explicit warning`() {
        val source=fixture();val changed=rewriteBackup(source,temp.newFolder().toPath().resolve("missing.zip")) { it.remove("images/photo.png") }
        val p=MobileBackupImporter().inspect(changed);assertTrue("photo.png" in p.missingMedia)
        assertTrue(p.data.conversations.first().messages.first().attachments.isNotEmpty());assertTrue(p.warnings.any { it.contains("缺失") })
    }
    @Test fun `future version and wrong format are refused`() {
        val source=fixture()
        for (root in listOf(modified(source) { JsonObject(it+ ("version" to JsonPrimitive(16))) },modified(source) { JsonObject(it+("format" to JsonPrimitive("other"))) }))
            assertThrows(ImportFailure::class.java) { MobileBackupImporter().inspect(root) }
    }
    @Test fun `legacy single TA backups use explicit settings fallback`() {
        val changed=modified(fixture()) { root -> JsonObject(root.toMutableMap().apply {
            put("version",JsonPrimitive(1));put("companions",JsonArray(emptyList()));put("memories",JsonArray(emptyList()));put("lore",JsonArray(emptyList()));put("groups",JsonArray(emptyList()));put("groupMembers",JsonArray(emptyList()));put("groupMessages",JsonArray(emptyList()));put("groupTurns",JsonArray(emptyList()));put("groupMemories",JsonArray(emptyList()))
            put("conversations",JsonArray(root["conversations"]!!.jsonArray.map { JsonObject(it.jsonObject-"companionId") }))
        }) }
        val p=MobileBackupImporter().inspect(changed);assertEquals("手机甲",p.data.companions.single().name);assertEquals(listOf("mobile-ta-1"),p.data.conversations.first().memberIds)
    }
    @Test fun `duplicate rows dangling messages and cross group speakers are rejected`() {
        val source=fixture()
        val bad=listOf(
            modified(source) { JsonObject(it+("companions" to JsonArray(it["companions"]!!.jsonArray+it["companions"]!!.jsonArray.first()))) },
            modified(source) { JsonObject(it+("conversations" to JsonArray(emptyList()))) },
            modified(source) { root -> JsonObject(root+("groupMessages" to JsonArray(root["groupMessages"]!!.jsonArray.map { row -> if(row.jsonObject["speakerId"]?.jsonPrimitive?.longOrNull==99L) JsonObject(row.jsonObject+("speakerId" to JsonPrimitive(777))) else row }))) })
        bad.forEach { assertThrows(ImportFailure::class.java) { MobileBackupImporter().inspect(it) } }
    }
    @Test fun `zip traversal backslashes duplicate case and reserved names are rejected`() {
        val source=fixture()
        for(name in listOf("../escape.txt","images/../escape.png","images\\escape.png","C:/escape.png","images/CON.png","images/Photo.PNG")) {
            val bad=rewriteBackup(source,temp.newFolder().toPath().resolve("bad.zip")) { it[name]=byteArrayOf(1) }
            assertThrows(ImportFailure::class.java) { MobileBackupImporter().inspect(bad) }
        }
    }
    @Test fun `unpacked size json size and entry count are bounded`() {
        val path=fixture()
        for(importer in listOf(MobileBackupImporter(maxExpandedBytes=50),MobileBackupImporter(maxJsonBytes=50),MobileBackupImporter(maxEntries=2)))
            assertThrows(ImportFailure::class.java) { importer.inspect(path) }
    }
    @Test fun `unsafe attachment reference is rejected even if not present in zip`() {
        val bad=modified(fixture()) { root -> JsonObject(root+("messages" to JsonArray(root["messages"]!!.jsonArray.mapIndexed { index,item ->
            if(index==0) JsonObject(item.jsonObject+("images" to JsonPrimitive("[{\"file\":\"../secret.png\"}]"))) else item
        }))) }
        assertThrows(ImportFailure::class.java) { MobileBackupImporter().inspect(bad) }
    }
    @Test fun `original archive complete JSON and media survive import and restart`() {
        val importer=MobileBackupImporter();val p=importer.inspect(fixture());val target=temp.newFolder().toPath()
        importer.write(p,target)
        assertEquals(p.sha256,fileDigest(target.resolve("source/phone-backup.zip")))
        assertTrue(Files.readString(target.resolve("source/backup.json")).contains("合成私密日记"))
        assertTrue(Files.exists(target.resolve("media/voice-fixture.m4a")))
        LocalStore(target).use { assertEquals(p.data,it.load());assertEquals(4,it.load().version) }
    }
    @Test fun `changed archive after preview cannot be committed`() {
        val path=fixture();val importer=MobileBackupImporter();val p=importer.inspect(path)
        Files.write(path,byteArrayOf(1),java.nio.file.StandardOpenOption.APPEND)
        assertThrows(ImportFailure::class.java) { importer.write(p,temp.newFolder().toPath()) }
    }
    @Test fun `writing into an occupied target is refused`() {
        val importer=MobileBackupImporter();val p=importer.inspect(fixture());val target=temp.newFolder().toPath();Files.writeString(target.resolve("keep.txt"),"keep")
        assertThrows(ImportFailure::class.java) { importer.write(p,target) };assertEquals("keep",Files.readString(target.resolve("keep.txt")))
    }
    @Test fun `workspace import is isolated duplicate safe and survives switching and restart`() = runBlocking(Dispatchers.Swing) {
        val root=temp.newFolder().toPath();val vault=MemoryVault();val workspace=DesktopWorkspace(root,vault)
        val originalId=workspace.controller!!.selected.id
        workspace.controller!!.draft("电脑上原来的草稿")
        val importer=MobileBackupImporter();val p=importer.inspect(fixture())
        assertTrue(workspace.importBackup(p,"手机资料"));val importedId=workspace.selected
        assertEquals(2,workspace.libraries.size);assertNotEquals("local",importedId)
        workspace.controller!!.notes("mobile-ta-7","导入后在电脑上新增的笔记")
        assertTrue(workspace.importBackup(p,"不应重复"));assertEquals(2,workspace.libraries.size);assertEquals("导入后在电脑上新增的笔记",workspace.controller!!.data.companions.first().notes)
        assertTrue(workspace.switch("local"));assertEquals(originalId,workspace.controller!!.selected.id);assertEquals("电脑上原来的草稿",workspace.controller!!.selected.draft)
        assertTrue(workspace.switch(importedId));workspace.close()
        DesktopWorkspace(root,vault).use { assertEquals(importedId,it.selected);assertEquals("导入后在电脑上新增的笔记",it.controller!!.data.companions.first().notes) }
    }
    @Test fun `failed workspace import leaves original library and index unchanged`() = runBlocking(Dispatchers.Swing) {
        val root=temp.newFolder().toPath();DesktopWorkspace(root,MemoryVault()).use { w ->
            w.controller!!.notes(w.controller!!.data.companions.first().id,"保留的电脑笔记")
            val before=Files.readAllBytes(root.resolve("library.json"));val path=fixture();val p=MobileBackupImporter().inspect(path)
            Files.write(path,byteArrayOf(1),java.nio.file.StandardOpenOption.APPEND)
            assertFalse(w.importBackup(p,"坏备份"));assertEquals("local",w.selected);assertEquals(1,w.libraries.size)
            assertArrayEquals(before,Files.readAllBytes(root.resolve("library.json")))
            assertTrue(Files.list(root.resolve("libraries")).use { it.toList().isEmpty() })
        }
    }
    @Test fun `old desktop v1 data migrates in place without discarding its messages`() {
        val root=temp.newFolder().toPath();val original=DesktopData.fresh().let { d -> d.copy(version=1,conversations=d.conversations.map { it.copy(messages=listOf(Message(text="旧版电脑聊天"))) }) }
        Files.writeString(root.resolve("library.json"),diskJson.encodeToString(original))
        LocalStore(root).use { assertEquals(4,it.load().version);assertEquals("旧版电脑聊天",it.load().conversations.first().messages.first().text) }
    }
    @Test fun `corrupt attachment fails CRC and removes only unpublished staging`() = runBlocking(Dispatchers.Swing) {
        val parts=fixtureParts(fixture()); val marker="unique-synthetic-media-payload".toByteArray()
        parts["images/photo.png"]=marker
        val archive=temp.newFolder().toPath().resolve("crc.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip -> parts.forEach { (name,bytes) ->
            val entry=ZipEntry(name).apply { method=ZipEntry.STORED;size=bytes.size.toLong();compressedSize=size;crc=CRC32().apply { update(bytes) }.value }
            zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry()
        } }
        val bytes=Files.readAllBytes(archive)
        val offset=(0..bytes.size-marker.size).first { i -> marker.indices.all { bytes[i+it]==marker[it] } }
        bytes[offset]=(bytes[offset].toInt() xor 1).toByte();Files.write(archive,bytes)
        val preview=MobileBackupImporter().inspect(archive)
        val root=temp.newFolder().toPath();DesktopWorkspace(root,MemoryVault()).use { w ->
            w.controller!!.notes(w.controller!!.data.companions.first().id,"导入失败也保留的笔记")
            val before=Files.readAllBytes(root.resolve("library.json"))
            assertFalse(w.importBackup(preview,"坏附件"));assertTrue(w.problem.contains("校验失败"))
            assertArrayEquals(before,Files.readAllBytes(root.resolve("library.json")))
            assertFalse(Files.exists(root.resolve("workspace.json")))
            assertTrue(Files.list(root.resolve("libraries")).use { it.toList().isEmpty() })
        }
    }
    @Test fun `workspace index cannot escape its root`() {
        val root=temp.newFolder().toPath();Files.writeString(root.resolve("workspace.json"),"""{"selected":"../outside","libraries":[{"id":"../outside","name":"bad"}]}""")
        assertThrows(Exception::class.java) { DesktopWorkspace(root,MemoryVault()) }
    }
}
