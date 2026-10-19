package com.cleo.cleos.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class HttpPermissionsTest {
    @Test fun defaultOffAndRestartKeepsOnlyDeviceLocalOriginConsent() = runBlocking {
        val raw = MutableStateFlow<String?>(null)
        val store = HttpPermissions({ raw }, { raw.value = it })
        assertFalse(store.allowed("http://example.test/v1?key=synthetic-secret"))
        store.set("http://example.test:80", true)
        val restarted = HttpPermissions({ raw }, { raw.value = it })
        assertTrue(restarted.allowed("HTTP://EXAMPLE.TEST/v2"))
        assertFalse(restarted.allowed("http://example.test:8080/v1"))
        assertFalse(restarted.allowed("http://sub.example.test/v1"))
        assertFalse(restarted.allowed("https://example.test/v1"))
        assertFalse(raw.value!!.contains("synthetic-secret"))
    }
    @Test fun modelsAndEachMcpServiceHaveIndependentGrantsAndRevocation() = runBlocking {
        val raw = MutableStateFlow<String?>(null)
        val store = HttpPermissions({ raw }, { raw.value = it })
        val url = "http://127.0.0.1:9300/mcp"
        store.set("http://127.0.0.1:9300", true)
        assertFalse(store.allowed(url, "first"))
        store.set("http://127.0.0.1:9300", true, "first")
        assertTrue(store.allowed(url, "first"))
        assertFalse(store.allowed(url, "second"))
        store.set("http://127.0.0.1:9300", true, "second")
        store.clearMcp("first")
        assertFalse(store.allowed(url, "first"))
        assertTrue(store.allowed(url, "second"))
        assertTrue(store.allowed(url))
        store.set("http://127.0.0.1:9300", false)
        assertFalse(store.allowed(url))
        assertTrue(store.allowed(url, "second"))
    }
    @Test fun corruptStoreFailsClosedAndConcurrentEditsDoNotLoseOtherGrants() = runBlocking {
        val raw = MutableStateFlow<String?>("not json")
        val store = HttpPermissions({ raw }, { raw.value = it })
        assertEquals(emptySet<String>(), store.approvals.first())
        (1..12).map { i -> launch(Dispatchers.Default) { store.set("http://host$i.test:80", true) } }.joinAll()
        assertEquals(12, store.approvals.first().size)
    }
    @Test fun boundedStoreRejectsExtraApprovalWithoutDroppingExistingOnes() = runBlocking {
        val raw = MutableStateFlow<String?>(null)
        val store = HttpPermissions({ raw }, { raw.value = it })
        repeat(128) { store.set("http://host$it.test:80", true) }
        val before = raw.value
        try { store.set("http://extra.test:80", true); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(before, raw.value)
        store.set("http://host0.test:80", false)
        store.set("http://extra.test:80", true)
        assertEquals(128, store.approvals.first().size)
    }
}
