package com.cleo.cleos.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cleo.cleos.data.decodeTuning
import com.cleo.cleos.data.encodeTuning
import com.cleo.cleos.ui.theme.Oklab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTuningTest {
    private val clear = GlassTuning(
        blur = 1f, refraction = 30f, bevel = 20f, dispersion = 0.5f, zoom = 1f,
        tintAlpha = 0.05f, saturation = 1.6f, highlight = 0.9f, shadow = 0.2f,
    )

    private fun lum(c: Color) = Oklab.luminance(c.red, c.green, c.blue)
    private fun contrast(a: Color, b: Color): Float {
        val la = lum(a)
        val lb = lum(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }
    private fun over(tint: Color, bgLum: Float): Color {
        val bg = Oklab.fromLinear(bgLum)
        val a = tint.alpha
        return Color(a * tint.red + (1 - a) * bg, a * tint.green + (1 - a) * bg, a * tint.blue + (1 - a) * bg)
    }

    @Test
    fun tuningKeepsThePaletteColourAndTakesOnlyTheStrength() {
        val light = GlassPalettes.build(dark = false)
        val applied = clear.applyTo(light.surface, dark = false)
        assertEquals(light.surface.tint.copy(alpha = 0.05f), applied.tint)
        assertEquals(30.dp, applied.refraction)
        assertEquals(0.2f, applied.shadowAlpha)
    }

    @Test
    fun darkGlassNeverCastsTheTunedShadow() {
        val dark = GlassPalettes.build(dark = true)
        assertEquals(0f, clear.applyTo(dark.surface, dark = true).shadowAlpha)
    }

    @Test
    fun savingWhileDarkDoesNotWipeTheLightShadow() {
        val dark = GlassPalettes.build(dark = true)
        val t = GlassTuning.of(clear.applyTo(dark.surface, dark = true), dark = true, keepShadow = 0.2f)
        assertEquals(0.2f, t.shadow)
        val never = GlassTuning.of(dark.surface, dark = true, keepShadow = null)
        val light = GlassPalettes.build(dark = false)
        // No light-mode shadow was ever chosen: light glass keeps its own default.
        assertEquals(light.surface.shadowAlpha, never.applyTo(light.surface, dark = false).shadowAlpha)
    }

    @Test
    fun aClearBubbleIsStillReadable() {
        val trough = 0.01f
        val p = GlassPalettes.build(dark = false, trough = trough, peak = 0.9f, tuning = mapOf(GlassPart.Bubble to clear))
        assertTrue("tuning kept", p.bubble.refraction == 30.dp)
        val c = contrast(p.content, over(p.bubble.tint, trough))
        assertTrue("bubble text contrast $c", c >= 4.5f - 0.01f)
    }

    @Test
    fun theUsersOwnBubbleKeepsWhiteTextReadable() {
        val peak = 1f
        val p = GlassPalettes.build(dark = false, trough = 0.3f, peak = peak, tuning = mapOf(GlassPart.Bubble to clear))
        val c = contrast(Color.White, over(p.bubbleMine.tint, peak))
        assertTrue("white on accent $c", c >= 4.5f - 0.01f)
    }

    @Test
    fun onlyTheTunedPartChanges() {
        val base = GlassPalettes.build(dark = false, trough = 0.4f, peak = 0.9f)
        val tuned = GlassPalettes.build(dark = false, trough = 0.4f, peak = 0.9f, tuning = mapOf(GlassPart.TabBar to clear))
        assertEquals(base.card, tuned.card)
        assertEquals(base.bubble, tuned.bubble)
        assertEquals(base.topBar, tuned.topBar)
        assertFalse(base.tabBar == tuned.tabBar)
    }

    @Test
    fun noticesFollowCardTuningAndKeepTheirOwnUntunedAppearance() {
        val base = GlassPalettes.build(dark = false, autoTint = false)
        val tuning = clear.copy(tintAlpha = .35f, shadow = .03f)
        val withCard = GlassPalettes.build(dark = false, autoTint = false, tuning = mapOf(GlassPart.Card to tuning))
        val withButtons = GlassPalettes.build(dark = false, autoTint = false, tuning = mapOf(GlassPart.TopBar to tuning))
        assertEquals(tuning.applyTo(base.notice, dark = false), withCard.notice)
        assertEquals(base.notice, withButtons.notice)
        assertEquals(base.topBar, withCard.topBar)
    }

    @Test
    fun theChatJumpButtonHasItsOwnClearGlass() {
        val base = GlassPalettes.build(dark = false, trough = 0f)
        assertEquals(0.dp, base.chatJump.blur)
        assertEquals((-40).dp, base.chatJump.refraction)
        assertEquals(13.dp, base.chatJump.bevel)
        assertEquals(0.06f, base.chatJump.dispersion, 0f)
        assertEquals(0.95f, base.chatJump.zoom, 0f)
        assertEquals(0f, base.chatJump.tint.alpha, 0f)
        assertEquals(1.43f, base.chatJump.saturation, 0f)
        assertEquals(0.32f, base.chatJump.highlight, 0f)
        assertEquals(0.07f, base.chatJump.shadowAlpha, 0f)
        val topBarChange = GlassPalettes.build(dark = false, trough = 0f, tuning = mapOf(GlassPart.TopBar to clear))
        assertEquals(base.chatJump, topBarChange.chatJump)
        val jumpChange = GlassPalettes.build(dark = false, trough = 0f, tuning = mapOf(GlassPart.ChatJump to clear))
        assertEquals(base.topBar, jumpChange.topBar)
        assertEquals(30.dp, jumpChange.chatJump.refraction)
    }

    @Test
    fun theTopBarTitleNeverGetsClearerThanTheDefaultBar() {
        val p = GlassPalettes.build(dark = false, trough = 0.4f, peak = 0.9f, tuning = mapOf(GlassPart.TopBar to clear))
        assertTrue(p.topBarTitle.tint.alpha >= p.bar.tint.alpha)
        assertEquals(30.dp, p.topBarTitle.refraction)
    }

    @Test
    fun explicitTitleTuningCanLowerTheTintWithoutChangingTheButtons() {
        val base = GlassPalettes.build(dark = false, trough = 0.4f, peak = 0.9f)
        val tuning = clear.copy(tintAlpha = 0.25f, dispersion = 0f)
        val tuned = GlassPalettes.build(dark = false, trough = 0.4f, peak = 0.9f,
            tuning = mapOf(GlassPart.TopBarTitle to tuning))
        assertEquals(base.topBar, tuned.topBar)
        assertTrue(tuned.topBarTitle.tint.alpha < base.topBarTitle.tint.alpha)
        assertEquals(0f, tuned.topBarTitle.dispersion, 0f)
    }

    @Test
    fun disablingAutoTintKeepsTheRequestedTintInTheAppAndPreview() {
        val transparent = clear.copy(tintAlpha = 0f)
        val tuning = GlassPart.entries.associateWith { transparent }
        val chosenColor = Color(0xFFF4C14C)
        for (dark in listOf(false, true)) {
            val automatic = GlassPalettes.build(dark, trough = 0f, peak = 1f, tuning = tuning, mine = chosenColor)
            val manual = GlassPalettes.build(dark, trough = 0f, peak = 1f, tuning = tuning, mine = chosenColor, autoTint = false)
            assertTrue(automatic.topBarTitle.tint.alpha > 0f)
            assertTrue(automatic.bubbleMine.tint.alpha > 0f)
            assertEquals(0f, manual.bubbleMine.tint.alpha, 0f)
            assertEquals(manual.bubbleMine, GlassPalettes.mine(manual, chosenColor).first)
            for (part in GlassPart.entries) {
                val draft = transparent.applyTo(manual.partDefaults.getValue(part), dark)
                assertEquals(0f, manual.parts.getValue(part).tint.alpha, 0f)
                assertEquals(draft, manual.floored(part, draft))
            }
        }
    }

    @Test
    fun draftLensAndSavedLensUseTheSameGlassInBothStates() {
        for (dark in listOf(false, true)) {
            val base = GlassPalettes.build(dark)
            val tuning = clear.copy(refraction = -6f, dispersion = 0f, zoom = 1.3f)
            val style = tuning.applyTo(base.partDefaults.getValue(GlassPart.Lens), dark)
            val preview = base.previewTabGlass(GlassPart.Lens, style)
            val saved = GlassPalettes.build(dark, tuning = mapOf(GlassPart.Lens to tuning))
            assertEquals(style, saved.lens)
            assertEquals(preview.lens, saved.lens)
            assertEquals(preview.lensPressed, saved.lensPressed)
            assertTrue(saved.lensPressed.refraction.value < 0f)
            assertEquals(0f, saved.lensPressed.dispersion, 0f)
            assertEquals(base.tabBar, saved.tabBar)
        }
    }

    @Test
    fun storedTuningSurvivesAndUnknownPartsAreSkipped() {
        val map = mapOf(GlassPart.Card to clear, GlassPart.Input to clear.copy(shadow = null))
        assertEquals(map, decodeTuning(encodeTuning(map)))
        val withRemovedPart = encodeTuning(map).replace("\"Card\"", "\"SomePartThatWasRemoved\"")
        assertEquals(mapOf(GlassPart.Input to clear.copy(shadow = null)), decodeTuning(withRemovedPart))
        assertEquals(emptyMap<GlassPart, GlassTuning>(), decodeTuning("not json"))
    }
}
