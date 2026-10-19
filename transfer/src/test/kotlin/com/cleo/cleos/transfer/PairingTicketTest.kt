package com.cleo.cleos.transfer

import org.junit.Assert.*
import org.junit.Test

class PairingTicketTest {
    private fun ticket()=PairingTicket.create("192.168.5.15",42345,"ab".repeat(32),"我的电脑")
    @Test fun `ticket round trip keeps identity and verification code without logging credentials`() {
        val t=ticket();val copy=PairingTicket.parse(t.encode())
        assertEquals(t.host,copy.host);assertEquals(t.token,copy.token);assertEquals(t.fingerprint,copy.fingerprint)
        assertEquals(t.name,copy.name);assertEquals(t.verificationCode,copy.verificationCode);assertTrue(Regex("[0-9]{6}").matches(t.verificationCode))
        assertFalse(t.toString().contains(t.token));assertFalse(t.toString().contains(t.host))
        assertNotEquals(t.token,ticket().token)
    }
    @Test fun `public hosts DNS userinfo duplicate unknown and malformed fields are rejected`() {
        val text=ticket().encode()
        for(bad in listOf(text.replace("192.168.5.15","8.8.8.8"),text.replace("192.168.5.15","localhost"),text.replace("192.168.5.15","0172.19.0.1"),text+"&host=10.1.1.2",text+"&extra=1",text+"#fragment",text.replace("//pair/","//user@pair/"),text.replace("cleos-transfer:","https:"),text.replace("/v1?","/v2?"),text.replace("42345","80"),text.replace("token=","token=%00"))) {
            assertThrows(TransferException::class.java){PairingTicket.parse(bad)}
        }
    }
    @Test fun `only explicit numeric local IPv4 addresses are accepted`() {
        for(good in listOf("10.1.2.3","172.16.1.4","172.31.7.8","192.168.5.4"))assertTrue(PairingTicket.privateIpv4(good))
        for(bad in listOf("169.254.1.1","0.0.0.0","172.32.1.1","10.0.0.255","10.0.0.0","::1","256.1.1.1","10.1.1.2.example.com"))assertFalse(PairingTicket.privateIpv4(bad))
    }
    @Test fun `abandoned receipts cleanup never deletes unrelated files`() {
        val cache=java.nio.file.Files.createTempDirectory("cleos-receipt-test").toFile()
        try {
            val old=java.io.File(cache,"incoming-00000000-0000-0000-0000-000000000001").apply{mkdir()};java.io.File(old,"phone.zip.part").writeText("synthetic")
            val unknown=java.io.File(cache,"incoming-00000000-0000-0000-0000-000000000002").apply{mkdir()};java.io.File(unknown,"keep.txt").writeText("keep")
            removeAbandonedReceipts(cache)
            assertFalse(old.exists());assertEquals("keep",java.io.File(unknown,"keep.txt").readText())
        } finally {cache.deleteRecursively()}
    }
}
