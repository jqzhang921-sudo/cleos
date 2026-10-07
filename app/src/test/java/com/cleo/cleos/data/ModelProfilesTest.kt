package com.cleo.cleos.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelProfilesTest {
    @Test fun differentModelsAndProvidersSurviveRestartWithoutCredentialsInProfiles() = runBlocking {
        val raw = MutableStateFlow<String?>(null)
        val store = ModelProfiles({ raw }, { raw.value = it })
        store.save("https://one.example/v1", "model-a", "A")
        store.save("https://one.example/v1", "model-b", "B")
        store.save("https://two.example/v1", "model-a", "C")
        val restored = ModelProfiles({ raw }, { raw.value = it }).profiles.first()
        assertEquals(3, restored.size)
        assertEquals(setOf("A", "B", "C"), restored.map { it.name }.toSet())
        assertFalse(raw.value!!.contains("apiKey"))
    }
    @Test fun rememberingSameEndpointKeepsCustomNameAndIdentity() {
        val first = ModelProfileRules.saved(emptyList(), "https://one.example/v1", "a", "我选的模型")
        val again = ModelProfileRules.saved(first, "https://one.example/v1/", " a ", null)
        assertEquals(1, again.size)
        assertEquals(first.single().id, again.single().id)
        assertEquals("我选的模型", again.single().name)
    }
    @Test fun renameDeleteAndConcurrentSavesPreserveOtherProfiles() = runBlocking {
        val raw = MutableStateFlow<String?>(null)
        val store = ModelProfiles({ raw }, { raw.value = it })
        val jobs = (1..8).map { i -> launch { store.save("https://one.example/v1", "model-$i", "配置 $i") } }
        jobs.forEach { it.join() }
        val original = store.profiles.first()
        assertEquals(8, original.size)
        store.rename(original[0].id, "新版")
        store.delete(original[1].id)
        val changed = store.profiles.first()
        assertEquals(7, changed.size)
        assertEquals("新版", changed.first { it.id == original[0].id }.name)
        assertFalse(changed.any { it.id == original[1].id })
    }
    @Test fun incompleteOrInvalidConnectionsAreNotRemembered() {
        assertFalse(ModelProfileRules.valid("", "a"))
        assertFalse(ModelProfileRules.valid("file:///tmp", "a"))
        assertFalse(ModelProfileRules.valid("https://one.example", ""))
        assertTrue(ModelProfileRules.valid("https://one.example/v1", "a"))
    }
    @Test fun deletedHistoryStaysRemovedAfterRestartUntilExplicitlySaved() = runBlocking {
        val raw = MutableStateFlow<String?>(null)
        val store = ModelProfiles({ raw }, { raw.value = it })
        store.save("https://one.example/v1", "a", "A")
        store.delete(store.profiles.first().single().id)
        val restarted = ModelProfiles({ raw }, { raw.value = it })
        restarted.save("https://one.example/v1/", "a")
        assertTrue(restarted.profiles.first().isEmpty())
        restarted.save("https://one.example/v1", "a", "重新保存")
        assertEquals("重新保存", restarted.profiles.first().single().name)
    }
}
