package com.cleo.cleos.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class EnvelopeThemesTest {
    @Test fun sixteenDistinctInstalledThemesHaveReadablePairedPaperAndEnvelope() {
        val themes=EnvelopeThemes.builtIn
        assertEquals(16,themes.size);assertEquals(16,themes.map{it.id}.distinct().size)
        assertEquals(16,themes.map{it.name}.distinct().size)
        themes.forEach { t->
            assertTrue(t.name,EnvelopeThemes.valid(t));assertEquals(t,InstantLetterData.theme(InstantLetterData.encode(t)))
            assertTrue(EnvelopeThemes.contrast(t.ink,t.paper)>=4.5)
            assertTrue(EnvelopeThemes.contrast(t.envelopeInk,t.envelope)>=4.5)
        }
    }
    @Test fun generatedMaterialIsAnExplicitFrozenLocalStyleNotRetroactiveToOldLetters() {
        val old=InstantLetterData.encode(EnvelopeThemes.builtIn.first())
        val material=EnvelopeThemes.builtIn.single{it.decoration==EnvelopeThemes.FROST_FIBER}
        val custom=material.copy(id="custom-fiber",name="自己的织纹")
        val sent=InstantLetterData.encode(custom)
        assertEquals("none",InstantLetterData.theme(old)!!.decoration)
        assertEquals(custom,InstantLetterData.theme(sent))
        assertEquals(listOf(custom),EnvelopeThemes.custom(listOf(custom)))
        assertEquals("ink-white",EnvelopeThemes.default(AppSettings()).id)
    }
    @Test fun oldFrozenSnapshotDecodesWithoutNewDecorationFields() {
        val old="""{"id":"ink-white","version":1,"name":"墨白简约","envelope":4293979109,"paper":4294966519,"ink":4281544501,"seal":4286473613}"""
        val theme=InstantLetterData.theme(old)!!
        assertEquals("v",theme.flap);assertEquals("none",theme.decoration);assertEquals(theme.ink,theme.envelopeInk)
        assertEquals(theme,InstantLetterData.theme(InstantLetterData.encode(theme)))
    }
    @Test fun defaultAndAiChoiceRespectInstalledCatalogueAndExplicitOptIn() {
        val s=AppSettings(defaultEnvelopeTheme="starry")
        assertEquals("starry",EnvelopeThemes.default(s).id)
        assertEquals("starry",EnvelopeThemes.forAi(s,null).id)
        assertEquals("starry",EnvelopeThemes.forAi(s,"default").id)
        assertEquals("starry",EnvelopeThemes.forAi(s,"starry").id)
        assertTrue(runCatching{EnvelopeThemes.forAi(s,"sakura")}.isFailure)
        assertEquals("sakura",EnvelopeThemes.forAi(s.copy(aiEnvelopeChoice=true),"sakura").id)
        assertTrue(runCatching{EnvelopeThemes.forAi(s.copy(aiEnvelopeChoice=true),"not-installed")}.isFailure)
        assertEquals("ink-white",EnvelopeThemes.default(s.copy(defaultEnvelopeTheme="deleted")).id)
    }
    @Test fun customStableIdsAndSavedSnapshotsSurviveEditsDeletionAndDefaultChanges() {
        val custom=EnvelopeThemes.builtIn[3].copy(id="custom-123",name="我的夜空")
        val s=AppSettings(customEnvelopeThemes=listOf(custom),defaultEnvelopeTheme=custom.id)
        val sent=InstantLetterData.encode(EnvelopeThemes.forAi(s,"default"))
        val edited=custom.copy(name="新名字",decoration="moon",seal=0xFF664477)
        assertNotEquals(edited,InstantLetterData.theme(sent))
        assertEquals(custom,InstantLetterData.theme(sent))
        assertEquals(custom.id,EnvelopeThemes.default(s.copy(customEnvelopeThemes=listOf(edited))).id)
        assertEquals("ink-white",EnvelopeThemes.default(s.copy(customEnvelopeThemes=emptyList())).id)
        assertEquals(custom,InstantLetterData.theme(sent))
    }
    @Test fun malformedStylesCannotEnterInstalledCatalogueOrBackupSettings() {
        val t=EnvelopeThemes.builtIn.first().copy(id="custom-test")
        val bad=listOf(t.copy(id="ink-white"),t.copy(id="custom-\n"),t.copy(name=""),t.copy(name="x\nrule"),t.copy(flap="x"),t.copy(decoration="remote-url"),t.copy(paperPattern="x"),t.copy(sealMark="long text"),t.copy(seal=0x00112233),t.copy(ink=t.paper))
        assertTrue(EnvelopeThemes.custom(bad).isEmpty())
        assertEquals(listOf(t),EnvelopeThemes.custom(listOf(t,t)))
        assertEquals(EnvelopeThemes.MAX_CUSTOM,EnvelopeThemes.custom((1..40).map{t.copy(id="custom-$it")}).size)
    }
    @Test fun catalogueAndUserChoiceAreCarriedInBoundedModelInstructions() {
        val t=EnvelopeThemes.builtIn[1].copy(id="custom-test",name="私藏信封")
        val s=AppSettings(defaultEnvelopeTheme="starry",customEnvelopeThemes=listOf(t))
        assertTrue(EnvelopeThemes.prompt(s).contains("未开启"));assertFalse(EnvelopeThemes.prompt(s).contains("custom-test"))
        val p=EnvelopeThemes.prompt(s.copy(aiEnvelopeChoice=true))
        assertTrue(p.contains("starry"));assertTrue(p.contains("custom-test"));assertTrue(p.contains("不随机换"))
    }
}
