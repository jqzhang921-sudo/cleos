package com.cleo.cleos.ui.settings

import org.junit.Assert.*
import org.junit.Test

class SecretKeyFieldTest {
    @Test fun arbitraryProviderFormatsAndLengthsAreNotRejected() {
        for(value in listOf("x","provider.1234567890", "SYNTHETIC".repeat(80))) {
            val info=inspectKeyDraft(value)
            assertEquals(value.length,info.characters)
            assertNull(info.warning)
        }
    }
    @Test fun copiedEllipsesAreAdvisoryAndInputIsNotRewritten() {
        for(value in listOf("demo…partial","demo...partial")) {
            assertTrue(inspectKeyDraft(value).hasEllipsis)
            assertNotNull(inspectKeyDraft(value).warning)
        }
    }
    @Test fun spacesLineBreaksAndInvisibleCharactersAreDetected() {
        for(character in listOf(' ','\n','\r','\t','\u00A0','\u200B','\uFEFF')) {
            assertTrue(inspectKeyDraft("demo${character}value").hasSpacing)
        }
    }
    @Test fun countUsesCharactersAndResultNeverIncludesSecretFragments() {
        assertEquals(3,inspectKeyDraft("a😀b").characters)
        assertEquals(0,inspectKeyDraft("").characters)
        assertFalse(inspectKeyDraft("synthetic-fragment-to-check").toString().contains("synthetic"))
    }
}
