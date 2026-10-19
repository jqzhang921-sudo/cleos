package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.CompanionEntity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaVoicesTest {
    private val common = AppSettings(speechEngine = "api", speechBaseUrl = "https://voice.example.invalid/v1",
        speechModel = "fixture-tts", speechVoice = "shared", tools = setOf(ToolGroup.Speak))
    private fun ta(id: Long = 1, raw: String = "{}") = CompanionEntity(id = id, name = "same name", createdAt = 0,
        apiBaseUrl = "https://chat.example.invalid/v1", apiModel = "fixture", voiceChoices = raw)
    private fun custom(s: AppSettings, voice: String, id: Long = 1) = ta(id, TaVoices.saved("{}", requireNotNull(TaVoices.scope(s)), voice))

    @Test fun legacyAndNewCompanionsInheritWithoutChangingAnySharedSetting() {
        for (service in VoiceService.entries) {
            val s = common.copy(speechEngine = service.key)
            assertSame(s, TaVoices.effective(s, ta()))
        }
        val old = Json.decodeFromString<CompanionEntity>("""{"id":1,"apiBaseUrl":"https://chat.example.invalid","apiModel":"fixture","createdAt":0}""")
        assertEquals("{}", old.voiceChoices); assertSame(common, TaVoices.effective(common, old))
    }

    @Test fun sameNamedMembersUseTheirOwnVoiceAndKeepEndpointModelAndToolsShared() {
        val a = custom(common, "alpha", 1); val b = custom(common, "beta", 2)
        val first = TaVoices.effective(common, a); val second = TaVoices.effective(common, b)
        assertEquals("alpha", Speech.voice(first, VoiceService.Other)); assertEquals("beta", Speech.voice(second, VoiceService.Other))
        assertEquals("shared", common.speechVoice)
        for (s in listOf(first, second)) {
            assertEquals(common.speechBaseUrl, s.speechBaseUrl); assertEquals(common.speechModel, s.speechModel)
            assertEquals(common.tools, s.tools); assertEquals(Speech.keyAddress(common, VoiceService.Other), Speech.keyAddress(s, VoiceService.Other))
        }
    }

    @Test fun eachProviderUsesItsActualVoiceField() {
        for (service in VoiceService.entries) {
            val s = common.copy(speechEngine = service.key, elevenVoice = "eleven-shared")
            val effective = TaVoices.effective(s, custom(s, "owned-voice"))
            assertEquals("owned-voice", Speech.voice(effective, service))
            assertEquals(Speech.keyAddress(s, service), Speech.keyAddress(effective, service))
            when (service) {
                VoiceService.ElevenLabs -> assertEquals(s.speechVoice, effective.speechVoice)
                VoiceService.Other -> assertEquals(s.elevenVoice, effective.elevenVoice)
                else -> { assertEquals(s.speechVoice, effective.speechVoice); assertEquals(s.elevenVoice, effective.elevenVoice) }
            }
        }
    }

    @Test fun minimaxRegionsAndOtherEndpointsAndModelsNeverShareAnOverride() {
        val cn = common.copy(speechEngine = "minimax", minimaxGlobal = false)
        val global = cn.copy(minimaxGlobal = true)
        assertNotEquals(TaVoices.scope(cn), TaVoices.scope(global))
        assertNull(TaVoices.choice(custom(cn, "region-voice").voiceChoices, global))
        val owned = custom(common, "relay-voice")
        for (different in listOf(common.copy(speechBaseUrl = "https://different.example.invalid/v1"), common.copy(speechModel = "other-model"))) {
            assertNull(TaVoices.choice(owned.voiceChoices, different))
        }
        assertEquals(TaVoices.scope(common), TaVoices.scope(common.copy(speechBaseUrl = "  https://voice.example.invalid/v1/  ", speechModel = " fixture-tts ")))
    }

    @Test fun inactiveServiceChoicesSurviveSavingAndRemovingTheCurrentChoice() {
        val openai = common.copy(speechEngine = "openai")
        val mini = common.copy(speechEngine = "minimax")
        val a = requireNotNull(TaVoices.scope(openai)); val b = requireNotNull(TaVoices.scope(mini))
        val first = TaVoices.saved("{}", a, "alloy")
        val both = TaVoices.saved(first, b, "voice-b")
        assertEquals("alloy", TaVoices.choice(both, openai)); assertEquals("voice-b", TaVoices.choice(both, mini))
        val removed = TaVoices.saved(both, b, null)
        assertEquals("alloy", TaVoices.choice(removed, openai)); assertNull(TaVoices.choice(removed, mini))
        assertEquals("alloy", TaVoices.choice(first, openai))
    }

    @Test fun sharedDefaultChangesAffectOnlyFollowersAndResettingResumesFollowing() {
        val own = custom(common, "alpha")
        val changed = common.copy(speechVoice = "new-default")
        assertEquals("new-default", Speech.voice(TaVoices.effective(changed, ta()), VoiceService.Other))
        assertEquals("alpha", Speech.voice(TaVoices.effective(changed, own), VoiceService.Other))
        val reset = own.copy(voiceChoices = TaVoices.saved(own.voiceChoices, requireNotNull(TaVoices.scope(common)), null))
        assertEquals("new-default", Speech.voice(TaVoices.effective(changed, reset), VoiceService.Other))
    }

    @Test fun elevenLabsCanUseAPerTaVoiceWithoutACommonDefaultButOthersStayUnready() {
        val s = common.copy(speechEngine = "elevenlabs", elevenVoice = "")
        assertFalse(Speech.ready(s)); assertFalse(Speech.ready(TaVoices.effective(s, ta())))
        val own = TaVoices.effective(s, custom(s, "voice-id"))
        assertTrue(Speech.ready(own)); assertTrue(GroupToolRules.offered(own).any { it.name == "send_voice" })
        assertFalse(GroupToolRules.offered(s).any { it.name == "send_voice" })
        assertFalse(GroupToolRules.offered(own.copy(tools = emptySet())).any { it.name == "send_voice" })
    }

    @Test fun disabledOrIncompleteSharedServiceDoesNotBecomeReadyFromAnOldChoice() {
        val own = custom(common, "alpha")
        for (s in listOf(common.copy(speechEngine = ""), common.copy(speechBaseUrl = ""), common.copy(speechModel = ""))) {
            assertNull(TaVoices.scope(s)); assertSame(s, TaVoices.effective(s, own)); assertFalse(Speech.ready(s))
        }
    }

    @Test fun readAndPreviewCacheKeysSeparateDifferentVoices() {
        val a = TaVoices.effective(common, custom(common, "alpha"))
        val b = TaVoices.effective(common, custom(common, "beta"))
        assertNotEquals(Speech.readKey(a, VoiceService.Other, Speech.SAMPLE), Speech.readKey(b, VoiceService.Other, Speech.SAMPLE))
        assertEquals(Speech.readKey(a, VoiceService.Other, Speech.SAMPLE), Speech.readKey(a, VoiceService.Other, Speech.SAMPLE))
        assertEquals("shared", common.speechVoice)
    }

    @Test fun groupCommitRejectsChangedOwnVoiceButNotAnUnrelatedDefaultChange() {
        val owned = custom(common, "alpha")
        val before = TaVoices.effective(common, owned)
        assertTrue(GroupSpeechRules.sameVoice(before, TaVoices.effective(common.copy(speechVoice = "different-default"), owned)))
        assertFalse(GroupSpeechRules.sameVoice(before, TaVoices.effective(common, custom(common, "beta"))))
        assertFalse(GroupSpeechRules.sameVoice(before, TaVoices.effective(common.copy(tools = emptySet()), owned)))
        assertTrue(GroupSpeechRules.sameVoice(before, TaVoices.effective(common, owned)))
    }

    @Test fun malformedChoicesCannotBeRestoredAndDoNotCrashAnExistingConversation() {
        val key = requireNotNull(TaVoices.scope(common))
        for (raw in listOf("[]", "null", "broken", """{"$key":null}""", """{"wrong":"voice"}""",
            Json.encodeToString(mapOf(key to " ")), Json.encodeToString(mapOf(key to "line\nbreak")),
            Json.encodeToString(mapOf(key to "x".repeat(TaVoices.MAX_VOICE + 1))))) {
            try { TaVoices.checked(raw); fail() } catch (_: IllegalArgumentException) { }
            try { TaVoices.validateBackup(listOf(ta(raw = raw))); fail() } catch (_: IllegalArgumentException) { }
            assertSame(common, TaVoices.effective(common, ta(raw = raw)))
        }
    }

    @Test fun boundedChoicesRejectAnotherScopeUntilOneIsRemoved() {
        var raw = "{}"
        repeat(TaVoices.MAX_CHOICES) { index -> raw = TaVoices.saved(raw, requireNotNull(TaVoices.scope(common.copy(speechModel = "m$index"))), "voice$index") }
        val newScope = requireNotNull(TaVoices.scope(common.copy(speechModel = "new-model")))
        try { TaVoices.saved(raw, newScope, "another"); fail() } catch (_: IllegalArgumentException) { }
        val removed = TaVoices.saved(raw, requireNotNull(TaVoices.scope(common.copy(speechModel = "m0"))), null)
        assertEquals(TaVoices.MAX_CHOICES, TaVoices.checked(TaVoices.saved(removed, newScope, "another")).size)
    }

    @Test fun storedOverridesContainVoiceIdsAndOpaqueScopeButNoEndpointOrModel() {
        val s = common.copy(speechBaseUrl = "https://voice.example.invalid/private-path", speechModel = "private-model")
        val raw = custom(s, "  voice-id  ").voiceChoices
        assertEquals("voice-id", TaVoices.choice(raw, s))
        assertFalse(raw.contains("private-path")); assertFalse(raw.contains("private-model")); assertFalse(raw.contains("https://"))
        for (service in listOf(VoiceService.Other, VoiceService.OpenAI, VoiceService.Mossland)) {
            val effective = TaVoices.withVoice(s.copy(speechEngine = service.key), "voice-id")
            val body = Json.parseToJsonElement(Speech.openAiBody("tts", Speech.voice(effective, service), "fixture",
                voiceField = if (service == VoiceService.Mossland) "voice_id" else "voice")).jsonObject
            assertEquals("voice-id", body[if (service == VoiceService.Mossland) "voice_id" else "voice"]!!.jsonPrimitive.content)
        }
    }
}
