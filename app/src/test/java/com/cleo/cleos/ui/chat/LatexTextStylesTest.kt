package com.cleo.cleos.ui.chat

import org.junit.Assert.*
import org.junit.Test

class LatexTextStylesTest {
    @Test fun screenshotRendersChineseAsNativeText() {
        val raw = "\\Large {\\color{blue}\\textbf{Cleo，英语好好考}}"
        val f = LatexTextStyles.at(raw, 0)!!
        assertEquals(raw.length, f.consumed)
        assertEquals("Cleo，英语好好考", f.text)
        assertTrue(f.runs.all { it.style.bold && it.style.color == "blue" && it.style.scale == 1.18f })
    }
    @Test fun scopeRestoresSizeColorAndWeight() {
        val f = LatexTextStyles.at("{\\color{red}甲{\\color{blue}\\bfseries乙}丙}", 0)!!
        assertEquals("甲乙丙", f.text)
        assertEquals(listOf("red", "blue", "red"), f.runs.map { it.style.color })
        assertEquals(listOf(false, true, false), f.runs.map { it.style.bold })
    }
    @Test fun allSizesAreBoundedRatherThanMultiplying() {
        listOf("large", "Large", "LARGE", "huge", "Huge").forEach {
            assertEquals(1.18f, LatexTextStyles.at("\\$it{你好}", 0)!!.runs.single().style.scale)
        }
        assertEquals(.9f, LatexTextStyles.at("\\small{小字}", 0)!!.runs.single().style.scale)
        assertEquals(1f, LatexTextStyles.at("\\normalsize{正常}", 0)!!.runs.single().style.scale)
        assertEquals(1.18f, LatexTextStyles.at("\\Huge{\\Huge{大字}}", 0)!!.runs.single().style.scale)
    }
    @Test fun escapedCharactersAndNewlinesStayText() {
        val f = LatexTextStyles.at("\\textbf{价格 \\\$5，\\{花括号\\}\n下一行}", 0)!!
        assertEquals("价格 \$5，{花括号}\n下一行", f.text)
    }
    @Test fun codeAndLiteralMentionsAreUntouched() {
        val raw = "`\\Large{代码}`\n```tex\n{\\color{red}代码}\n```\n提到 \\Huge 这个命令。"
        assertTrue(MessageMarkup.scan(raw).styles.isEmpty())
        assertNull(LatexTextStyles.at("{正常的括号}", 0))
        assertNull(LatexTextStyles.at("\\Large 没有分组", 0))
    }
    @Test fun malformedUnknownAndOverBudgetFragmentsDoNotExecute() {
        listOf("\\textbf{没结束", "\\textcolor{rgb(1,2,3)}{x}", "\\Huge{\\input{file}}", "\\newcommand{a}{b}",
            "\\textbf{" + "字".repeat(1601) + "}", "\\textbf{".repeat(13) + "x" + "}".repeat(13)).forEach {
            assertNull(it, LatexTextStyles.at(it, 0))
        }
    }
    @Test fun mathRemainsInFormulaPipelineAndStylesDoNotEatAdjacentProse() {
        assertNull(LatexTextStyles.whole("\\textcolor{red}{x^2}"))
        val raw = "\\textbf{你好}，后面还是正文。"
        val f = LatexTextStyles.at(raw, 0)!!
        assertEquals("你好", f.text)
        assertEquals("，后面还是正文。", raw.substring(f.consumed))
        val plan = MessageMarkup.scan("样式 \\textbf{你好}，公式 \$\\frac{x}{y}\$。")
        assertEquals(1, plan.styles.size)
        assertEquals(1, plan.formulas.size)
    }
    @Test fun rgbColorsAndStyleOnlyMathWrappersAreSupported() {
        assertEquals("#3366ff", LatexTextStyles.at("\\textcolor{#3366ff}{你好}", 0)!!.runs.single().style.color)
        assertEquals("你好", LatexTextStyles.whole("{\\color{blue}\\textbf{你好}}")!!.text)
        assertTrue(MessageMarkup.needsRendering("\\textbf{你好}"))
        assertTrue(MessageMarkup.needsRendering("\\Huge{你好}"))
    }
}
