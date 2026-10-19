package com.cleo.cleos.desktop

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ImportPublicationTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `a complete library publishes even while Windows holds its directory against rename`() = runBlocking(Dispatchers.Swing) {
        val root=temp.newFolder().toPath();val w=DesktopWorkspace(root,MemoryVault())
        var handle:WinNT.HANDLE?=null
        val data=DesktopData.fresh()
        try {
            assertTrue(w.publishImport("synthetic-held-directory","合成导入") {target ->
                LocalStore(target).use {it.save(data)}
                handle=Kernel32.INSTANCE.CreateFile(target.toString(),WinNT.GENERIC_READ,WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE,
                    null,WinNT.OPEN_EXISTING,WinNT.FILE_FLAG_BACKUP_SEMANTICS,null)
                assertNotEquals(WinNT.INVALID_HANDLE_VALUE,handle)
                // No FILE_SHARE_DELETE: Windows must reject renaming this open directory.
                assertThrows(java.nio.file.FileSystemException::class.java) {
                    Files.move(target,target.resolveSibling("should-not-move"),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                }
            })
            assertEquals(2,w.libraries.size);assertEquals(data,w.controller!!.data)
            assertTrue(w.switch("local"));assertTrue(w.switch(w.libraries.last().id))
            assertEquals(data,w.controller!!.data)
        }finally {handle?.let {Kernel32.INSTANCE.CloseHandle(it)};w.close()}
        DesktopWorkspace(root,MemoryVault()).use {assertEquals(2,it.libraries.size);assertEquals(data,it.controller!!.data)}
    }
    @Test fun `write failure never registers partial data and identifies the failing stage`() = runBlocking(Dispatchers.Swing) {
        val root=temp.newFolder().toPath();val w=DesktopWorkspace(root,MemoryVault())
        try {
            w.controller!!.draft("原资料保留")
            val before=w.controller!!.data
            assertFalse(w.publishImport("synthetic-failure","不完整导入") {target ->
                Files.writeString(target.resolve("partial.txt"),"synthetic partial")
                throw java.nio.file.AccessDeniedException("synthetic-sensitive-path")
            })
            assertEquals(1,w.libraries.size);assertEquals("local",w.selected);assertEquals(before,w.controller!!.data)
            assertTrue(w.problem.contains("复制并校验备份"));assertFalse(w.problem.contains("synthetic-sensitive-path"))
            assertTrue(Files.list(root.resolve("libraries")).use {it.findAny().isEmpty})
        }finally {w.close()}
        DesktopWorkspace(root,MemoryVault()).use {assertEquals(1,it.libraries.size);assertEquals("原资料保留",it.controller!!.selected.draft)}
    }
}
