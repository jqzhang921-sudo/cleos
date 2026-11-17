package com.cleo.cleos.ui.chat

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.BackupSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MessageMarkupTest {
    @Test fun allFourDelimitersAndOriginalSource() {
        val source = "行内 \$x^2\$、\\(\\alpha+1\\)\n\$\$\\frac{2(x^2+y^2)}{(x-y)^3}\$\$\n\\[\\sqrt{x}\\]"
        val plan = MessageMarkup.scan(source)
        assertEquals(4, plan.formulas.size)
        assertEquals(listOf(false, false, true, true), plan.formulas.map { it.block })
        assertEquals("\$x^2\$", plan.formulas.first().raw)
        assertTrue(plan.formulas.all { MessageMarkup.safe(it.tex) })
        assertTrue(source.contains("\\frac"))
    }
    @Test fun pricesEscapesAndCodeStayLiteral() {
        val source = "价格 \$5 and \$10，转义 \\\$x\\\$。`\$x^2\$`\n```tex\n\\[x^2\\]\n```"
        val plan = MessageMarkup.scan(source)
        assertTrue(plan.formulas.isEmpty())
        assertEquals(source, plan.markdown)
    }
    @Test fun unfinishedMathAndCodeNeverConsumeFollowingMessages() {
        listOf("a \\(x^2", "a \$x+1", "```\n\$x\$").forEach {
            assertEquals(if (it.contains("\\(")) it.replace("\\", "\\\\") else it, MessageMarkup.scan(it).markdown)
            assertTrue(MessageMarkup.scan(it).formulas.isEmpty())
        }
    }
    @Test fun incompleteDollarDoesNotHideIndependentBlockOrCode() {
        val plan = MessageMarkup.scan("不完整：\$\\frac{x}{\n无效：\\[\\unknown{x}\\]\n价格 \$5 and \$10，`\$x\$`。")
        assertEquals(1, plan.formulas.size)
        assertEquals("\\[\\unknown{x}\\]", plan.formulas.single().raw)
        assertTrue(plan.markdown.contains("`\$x\$`"))
    }
    @Test fun unsafeAndInvalidMathRemainRawCandidatesButNeverExecute() {
        listOf("\\includegraphics{https://example.com/x}", "\\newcommand{\\x}{y}", "\\def\\x{y}",
            "\\input{/a}", "\\rule{999999em}{9em}", "\\begin{unknown}x\\end{unknown}", "{x", "x}",
            "{".repeat(25) + "x" + "}".repeat(25), "x".repeat(1601)).forEach {
            assertFalse(it, MessageMarkup.safe(it))
            val raw = "\\[$it\\]"
            assertEquals(raw, MessageMarkup.scan(raw).formulas.single().raw)
        }
    }
    @Test fun structuredMathWorksWithoutArbitraryMacros() {
        listOf("\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}", "\\int_0^1 x^2\\,dx", "y''=\\frac{2(x^2+y^2)}{(x-y)^3}").forEach {
            assertTrue(it, MessageMarkup.safe(it))
        }
    }
    @Test fun workIsBoundedAndPlaceholderCannotBeInjected() {
        assertEquals(24, MessageMarkup.scan(("\$x^2\$ ").repeat(100)).formulas.size)
        assertTrue(MessageMarkup.scan("x".repeat(32_001)).formulas.isEmpty())
        assertTrue(MessageMarkup.scan("CLEOSMATHPLACEHOLDER0END \$x\$").formulas.isEmpty())
    }
    @Test fun colorDirectivesAreDisplayOnlyAndCodeIsProtected() {
        val source = "<span style=\"color:red\">重点</span> `<font color='blue'>代码</font>`"
        val plan = MessageMarkup.scan(source)
        assertEquals(1, plan.tints.size)
        assertEquals("red", plan.tints.single().requested)
        assertTrue(plan.markdown.contains("`<font"))
        assertEquals("{x}+y" to "red", MessageMarkup.withoutMathColors("\\textcolor{red}{x}+y"))
        assertEquals("x" to "blue", MessageMarkup.withoutMathColors("\\color{blue}x"))
    }
    @Test fun oldBackupsUseSafeDefaultsAndNewSettingsRoundTrip() {
        val json = Json { encodeDefaults = true }
        val backup = BackupSettings(apiBaseUrl = "https://example.invalid", apiModel = "fixture", aiName = "TA",
            userName = "User", persona = "", historySize = 20, wallpaper = null, glassMode = "Auto",
            wallpaperDark = null, wallpaperHue = null, wallpaperChroma = null)
        val missing = JsonObject(json.parseToJsonElement(json.encodeToString(BackupSettings.serializer(), backup)).jsonObject - "messageRichText" - "messageColors")
        val old = json.decodeFromString<BackupSettings>(missing.toString())
        assertTrue(old.messageRichText)
        assertFalse(old.messageColors)
        assertTrue(AppSettings().messageRichText)
        val changed = old.copy(messageRichText = false, messageColors = true)
        assertEquals(changed, json.decodeFromString<BackupSettings>(json.encodeToString(BackupSettings.serializer(), changed)))
    }
}
