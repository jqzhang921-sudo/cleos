package com.cleo.cleos.ui.settings

import com.cleo.cleos.transfer.PairingTicket
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class PhoneMigrationTest {
    @Test fun `scanning alone never exports private backup`() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val calls=AtomicInteger()
        try {
            val migration=PhoneMigration(scope){calls.incrementAndGet()}
            val ticket=PairingTicket.create("192.168.5.15",43000,"aa".repeat(32))
            migration.scanned(ticket.encode());assertNotNull(migration.ticket);assertFalse(migration.busy);assertEquals(0,calls.get())
            migration.dismissTicket();assertNull(migration.ticket);assertEquals(0,calls.get())
        } finally {scope.cancel()}
    }
    @Test fun `invalid external QR never connects or includes credentials in error`() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val migration=PhoneMigration(scope){error("must not export")}
            migration.scanned("https://example.invalid/?token=synthetic-private-credential")
            migration.start();assertNull(migration.ticket);assertFalse(migration.busy);assertFalse(migration.message.contains("synthetic-private-credential"))
        } finally {scope.cancel()}
    }
    @Test fun `connection failure leaves the backup unexported`() = runBlocking {
        val port=ServerSocket(0).use {it.localPort};val calls=AtomicInteger();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val migration=PhoneMigration(scope){calls.incrementAndGet()}
            migration.scanned(PairingTicket.create("127.0.0.1",port,"aa".repeat(32)).encode());migration.start()
            withTimeout(5000){while(migration.busy)delay(10)}
            assertEquals(0,calls.get());assertNull(migration.ticket);assertTrue(migration.message.contains("没有完成传输"))
        } finally {scope.cancel()}
    }
}
