package com.cleo.cleos.glass

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.unit.dp
import com.cleo.cleos.ui.theme.Oklab

/**
 * Everything a screen needs to draw glass consistently: whether the glass is light or
 * dark, the accent, the colours for text on glass, and the style families.
 *
 * Light vs dark follows the wallpaper, not the system theme: dark glass over a pale
 * wallpaper (or the reverse) is exactly the case where text on glass stops being
 * readable.
 */
@Immutable
data class GlassPalette(
    val dark: Boolean,
    /** Fills that carry white text: the user's bubbles, primary buttons. */
    val accent: Color,
    /** Accent-coloured text and icons sitting directly on glass. */
    val accentContent: Color,
    val content: Color,
    val contentSecondary: Color,
    /** Error text, dark enough on light glass (light enough on dark) to read. */
    val error: Color,
    /**
     * For short text that would otherwise sit straight on the wallpaper (notices, hints,
     * error lines): a capsule tinted heavily enough (0.82) that the text reads over any
     * photo. Bare text on a wallpaper is only readable until the wallpaper changes.
     */
    val notice: GlassStyle,
    /** Bars and buttons: clear, strongly refracting. */
    val chrome: GlassStyle,
    /**
     * Chrome that carries words (a title, the text being typed). Same bend as [chrome],
     * but with enough tint that the words stay readable while content scrolls beneath.
     */
    val bar: GlassStyle,
    /** Surfaces with body text: bubbles, cards. Tinted enough to guarantee contrast. */
    val surface: GlassStyle,
    /** The user's own bubbles: accent glass. */
    val accentSurface: GlassStyle,
    /** Tab bar selection at rest, and held. */
    val lensRest: GlassStyle,
    val lensHeld: GlassStyle,
    /**
     * Each tunable part after the user's tuning and the contrast floor. Empty until
     * [GlassPalettes.build] fills it; the accessors below fall back to the families.
     */
    val parts: Map<GlassPart, GlassStyle> = emptyMap(),
    /** Each part's untuned default: where the glass lab starts and what "reset" returns to. */
    val partDefaults: Map<GlassPart, GlassStyle> = emptyMap(),
    val bubbleMineStyle: GlassStyle? = null,
    /** Text on the user's own bubbles: white, or dark on a light colour they picked. */
    val mineContent: Color = Color.White,
    /** The user's bubbles before any colour and contrast floor: what a colour being picked is tried on. */
    val mineBase: GlassStyle? = null,
    /** The lens held, following the lens as tuned; null untuned. */
    val lensHeldStyle: GlassStyle? = null,
    /** Luminance of the wallpaper's darkest / brightest patch, when known. */
    val troughLum: Float? = null,
    val peakLum: Float? = null,
    val autoTint: Boolean = true,
) {
    val bubble: GlassStyle get() = parts[GlassPart.Bubble] ?: surface
    val card: GlassStyle get() = parts[GlassPart.Card] ?: surface
    val topBar: GlassStyle get() = parts[GlassPart.TopBar] ?: chrome
    val tabBar: GlassStyle get() = parts[GlassPart.TabBar] ?: chrome
    val input: GlassStyle get() = parts[GlassPart.Input] ?: bar
    val chatJump: GlassStyle get() = parts[GlassPart.ChatJump] ?: chatJumpStyle(this)

    /** The tab bar's lens at rest and held, as tuned (see [GlassPart.Lens]). */
    val lens: GlassStyle get() = parts[GlassPart.Lens] ?: lensRest
    val lensPressed: GlassStyle get() = lensHeldStyle ?: lensHeld

    /** The user's own bubbles: the bubble tuning, in the accent colour. */
    val bubbleMine: GlassStyle get() = bubbleMineStyle ?: accentSurface

    val topBarTitle: GlassStyle get() = parts[GlassPart.TopBarTitle] ?: bar

    /** [style] as the app would actually draw it for [part] (with the contrast floor). */
    fun floored(part: GlassPart, style: GlassStyle): GlassStyle = GlassPalettes.floor(this, part, style)

    internal fun previewTabGlass(part: GlassPart, style: GlassStyle): GlassPalette = copy(
        parts = parts + (part to style),
        lensHeldStyle = if (part == GlassPart.Lens) heldLensFor(style) else lensHeldStyle,
    )

    internal fun heldLensFor(rest: GlassStyle): GlassStyle =
        if (rest == lensRest) lensHeld else GlassPalettes.heldFrom(rest, lensRest, lensHeld)
}

object GlassPalettes {
    /** Violet, the accent when the wallpaper has no colour worth following. */
    const val DEFAULT_HUE = 4.93f // radians, ~282°

    /**
     * [trough]/[peak]: luminance of the wallpaper's darkest / brightest patch.
     * [tuning]: what the user saved in the glass lab, per part.
     * [mine]: the colour the user picked for their own bubbles; null follows the wallpaper.
     */
    fun build(
        dark: Boolean,
        hue: Float = DEFAULT_HUE,
        chromaScale: Float = 1f,
        trough: Float? = null,
        peak: Float? = null,
        tuning: Map<GlassPart, GlassTuning> = emptyMap(),
        mine: Color? = null,
        autoTint: Boolean = true,
    ): GlassPalette {
        val base = base(dark, hue, chromaScale)
        val b = base.copy(lensRest = restingLens(base), troughLum = trough, peakLum = peak, autoTint = autoTint)
        val worst = b.worstLum
        val floored = b.copy(chrome = raise(b, b.chrome, worst, withAccent = true), bar = raise(b, b.bar, worst, withAccent = true))

        val topBar = floor(floored, GlassPart.TopBar, tuning[GlassPart.TopBar]?.applyTo(b.chrome, dark) ?: b.chrome)
        // Keep the inherited appearance for users who have only tuned the old TopBar part.
        val titleDefault = topBar.copy(tint = topBar.tint.copy(alpha = maxOf(topBar.tint.alpha, floored.bar.tint.alpha)))
        val defaults = mapOf(
            GlassPart.Bubble to floored.surface,
            GlassPart.Card to floored.surface,
            GlassPart.TopBar to b.chrome,
            GlassPart.TopBarTitle to titleDefault,
            GlassPart.TabBar to b.chrome,
            GlassPart.Input to b.bar,
            GlassPart.ChatJump to chatJumpStyle(b),
            GlassPart.Lens to b.lensRest,
        )
        val parts = defaults.mapValues { (part, style) ->
            floor(floored, part, tuning[part]?.applyTo(style, dark) ?: style)
        }
        // The user's own bubbles take the bubble tuning, in the colour they picked or else the
        // accent (see [mine]).
        val mineBase = tuning[GlassPart.Bubble]?.applyTo(b.accentSurface, dark) ?: b.accentSurface
        val (mineStyle, mineInk) = mineFor(b, mineBase, mine ?: b.accent, picked = mine != null)
        val lensHeld = tuning[GlassPart.Lens]?.let { b.heldLensFor(parts.getValue(GlassPart.Lens)) }
        val notice = tuning[GlassPart.Card]?.applyTo(b.notice, dark)?.let { floor(floored, GlassPart.Card, it) } ?: b.notice
        return floored.copy(
            notice = notice,
            parts = parts,
            partDefaults = defaults,
            bubbleMineStyle = mineStyle,
            mineContent = mineInk,
            mineBase = mineBase,
            lensHeldStyle = lensHeld,
        )
    }

    private fun restingLens(palette: GlassPalette): GlassStyle {
        val base = palette.lensRest
        val tint = lerpColor(base.tint, palette.accentContent, if (palette.dark) 0.45f else 0.7f)
        return base.copy(
            tint = tint.copy(alpha = base.tint.alpha * 1.15f),
            refraction = base.refraction * 0.25f,
            bevel = base.bevel * 0.55f,
            dispersion = base.dispersion * 0.35f,
            lift = base.lift * 0.45f,
            highlight = base.highlight * 0.27f,
            rimWidth = base.rimWidth * 0.6f,
            shadowAlpha = base.shadowAlpha * 0.5f,
        )
    }

    /**
     * The lens held, for a resting lens tuned to [rest]: every knob moved from it as far, in
     * proportion, as the untuned held lens is from the untuned resting one. In proportion so that
     * what was tuned away stays away when pressed: dispersion tuned to 0 must not bring the rainbow
     * back the moment the lens is held. A knob the resting lens has at 0 (no blur) moves by the
     * same amount instead.
     */
    internal fun heldFrom(rest: GlassStyle, defaultRest: GlassStyle, defaultHeld: GlassStyle): GlassStyle {
        fun follow(tuned: Float, r: Float, h: Float) = if (r == 0f) tuned + (h - r) else tuned * (h / r)
        return defaultHeld.copy(
            blur = follow(rest.blur.value, defaultRest.blur.value, defaultHeld.blur.value).dp,
            refraction = follow(rest.refraction.value, defaultRest.refraction.value, defaultHeld.refraction.value).coerceIn(-40f, 40f).dp,
            bevel = follow(rest.bevel.value, defaultRest.bevel.value, defaultHeld.bevel.value).coerceAtMost(48f).dp,
            dispersion = follow(rest.dispersion, defaultRest.dispersion, defaultHeld.dispersion).coerceAtMost(1f),
            zoom = follow(rest.zoom, defaultRest.zoom, defaultHeld.zoom).coerceAtMost(1.6f),
            tint = defaultHeld.tint.copy(alpha = follow(rest.tint.alpha, defaultRest.tint.alpha, defaultHeld.tint.alpha).coerceAtMost(1f)),
            saturation = follow(rest.saturation, defaultRest.saturation, defaultHeld.saturation).coerceAtMost(2.5f),
            highlight = follow(rest.highlight, defaultRest.highlight, defaultHeld.highlight).coerceAtMost(1.5f),
            shadowAlpha = follow(rest.shadowAlpha, defaultRest.shadowAlpha, defaultHeld.shadowAlpha).coerceAtMost(1f),
        )
    }

    /** The user's bubbles in [color], and the text on them, as [p] would draw them: for trying a colour out. */
    fun mine(p: GlassPalette, color: Color): Pair<GlassStyle, Color> =
        mineFor(p, p.mineBase ?: p.accentSurface, color, picked = true)

    /**
     * How much of the glass a colour the user picked covers at least. The bubble tuning is shared
     * with the other side's bubbles and can be tuned almost clear. The contrast floor then raises a
     * deep colour (white text needs it) but not a light one, since dark text reads on clear glass as
     * it is: picking yellow looked the same as picking nothing.
     */
    const val PICKED_TINT = 0.8f

    /**
     * OKLab chroma from which a colour counts as one. Below it [PICKED_TINT] fades out: white, grey
     * and black have no hue to lose and keep the glass as clear as it was tuned, and the chroma
     * slider starting from grey has no step in it.
     */
    private const val GREY_CHROMA = 0.05f

    private fun pickedTint(color: Color): Float =
        PICKED_TINT * (Oklab.fromSrgb(color.red, color.green, color.blue).chroma / GREY_CHROMA).coerceIn(0f, 1f)

    /** Text on a bubble of that colour on light glass: whichever reads better of the two. */
    private val INK = Color(0xFF1C1A22)

    /**
     * The same glass in [color], tinted at least [least], with white text on it or dark: whichever
     * contrasts more. What threatens white text is the wallpaper's brightest patch, dark text its
     * darkest; the tint is raised until the text clears 4.5:1 over it. With no wallpaper
     * information, the worst case: white, or black.
     */
    private fun mineFor(p: GlassPalette, base: GlassStyle, color: Color, picked: Boolean): Pair<GlassStyle, Color> {
        val l = Oklab.luminance(color.red, color.green, color.blue)
        val inkL = Oklab.luminance(INK.red, INK.green, INK.blue)
        val white = 1.05f / (l + 0.05f) >= (l + 0.05f) / (inkL + 0.05f)
        val ink = if (white) Color.White else INK
        val least = if (p.autoTint && picked) pickedTint(color) else 0f
        val tinted = base.copy(tint = color.copy(alpha = maxOf(base.tint.alpha, least)))
        if (!p.autoTint) return tinted to ink
        return raiseFor(tinted, if (white) p.peakLum ?: 1f else p.troughLum ?: 0f, ink, 4.5f) to ink
    }

    private val GlassPalette.worstLum: Float? get() = if (dark) peakLum else troughLum

    /**
     * The contrast floor for one part. Clear glass looks best, but light-or-dark is
     * decided for the wallpaper as a whole: a photo with a bright sky and dark hills gets
     * light glass, and a bar then sits over the hills with dark labels on barely tinted
     * glass. So tints are raised, only as far as needed, until text clears 4.5:1 over the
     * worst patch of the wallpaper (the darkest for light glass, the brightest for dark).
     * The user can tune a part as clear as they like; this is what keeps the words on it
     * readable anyway.
     */
    internal fun floor(p: GlassPalette, part: GlassPart, style: GlassStyle): GlassStyle =
        // The lens carries no words of its own: the label under it belongs to the tab bar, whose floor covers it.
        if (part == GlassPart.Lens || part == GlassPart.ChatJump) style
        else raise(p, style, p.worstLum, withAccent = part == GlassPart.TopBar || part == GlassPart.TabBar)

    private fun raise(p: GlassPalette, style: GlassStyle, worstLum: Float?, withAccent: Boolean): GlassStyle {
        if (!p.autoTint || worstLum == null) return style
        // Text at 4.5:1; accent icons and bold accent labels (the selected tab) at 3:1,
        // the level for graphics and large text.
        val needText = minTintAlpha(style.tint, worstLum, p.content, 4.5f)
        val need = if (withAccent) maxOf(needText, minTintAlpha(style.tint, worstLum, p.accentContent, 3f)) else needText
        return atLeast(style, need)
    }

    private fun raiseFor(style: GlassStyle, worstLum: Float, text: Color, ratio: Float): GlassStyle =
        atLeast(style, minTintAlpha(style.tint, worstLum, text, ratio))

    /**
     * Rounded *up* to the next 1/255: an sRGB Color keeps 8 bits of alpha, and letting the
     * nearest step round down left white-on-accent at 4.48:1 against a 4.5 target.
     */
    private fun atLeast(style: GlassStyle, need: Float): GlassStyle {
        if (need <= style.tint.alpha) return style
        val stored = (kotlin.math.ceil(need * 255f) / 255f).coerceAtMost(1f)
        return style.copy(tint = style.tint.copy(alpha = stored))
    }

    /**
     * Smallest alpha of [tint] over a grey of luminance [bgLum] that gives [content]
     * [ratio]:1. The mix is done on sRGB values, the same way the shader mixes, and the
     * contrast is measured on luminance, so it is found by bisection.
     */
    private fun minTintAlpha(tint: Color, bgLum: Float, content: Color, ratio: Float): Float {
        val bg = Oklab.fromLinear(bgLum.coerceIn(0f, 1f))
        val lc = Oklab.luminance(content.red, content.green, content.blue)
        fun contrast(a: Float): Float {
            val l = Oklab.luminance(
                a * tint.red + (1 - a) * bg,
                a * tint.green + (1 - a) * bg,
                a * tint.blue + (1 - a) * bg,
            )
            return (maxOf(l, lc) + 0.05f) / (minOf(l, lc) + 0.05f)
        }
        if (contrast(0f) >= ratio) return 0f
        if (contrast(1f) < ratio) return 1f
        var lo = 0f
        var hi = 1f
        repeat(18) {
            val mid = (lo + hi) / 2f
            if (contrast(mid) >= ratio) hi = mid else lo = mid
        }
        return hi
    }

    private fun base(dark: Boolean, hue: Float, chromaScale: Float): GlassPalette {
        // Accent fills always sit at OKLab L 0.52: dark enough that white text on them
        // clears 4.5:1 even after the 10% of glass tint lets a white wallpaper through.
        val accent = Oklab.lch(0.52f, 0.15f * chromaScale, hue)
        val accentContent = if (dark) Oklab.lch(0.80f, 0.11f * chromaScale, hue) else Oklab.lch(0.44f, 0.14f * chromaScale, hue)
        return if (dark) {
            GlassPalette(
                dark = true,
                accent = accent,
                accentContent = accentContent,
                content = Color(0xFFF3F1F7),
                contentSecondary = Color(0xFFF3F1F7).copy(alpha = 0.66f),
                error = Color(0xFFFF8F87),
                notice = GlassStyle(
                    blur = 10.dp, refraction = 10.dp, bevel = 10.dp, dispersion = 0.2f,
                    tint = Color(0xFF1B1922).copy(alpha = 0.82f), saturation = 1.2f, lift = 0f,
                    highlight = 0.4f, shadowAlpha = 0f,
                ),
                chrome = GlassStyle(
                    blur = 3.dp, refraction = 22.dp, bevel = 18.dp, dispersion = 0.35f,
                    tint = Color(0xFF16141C).copy(alpha = 0.30f), saturation = 1.5f, lift = -0.02f,
                    highlight = 0.55f, shadowAlpha = 0f,
                ),
                bar = GlassStyle(
                    blur = 8.dp, refraction = 20.dp, bevel = 16.dp, dispersion = 0.3f,
                    tint = Color(0xFF16141C).copy(alpha = 0.58f), saturation = 1.4f, lift = -0.02f,
                    highlight = 0.55f, shadowAlpha = 0f,
                ),
                surface = GlassStyle(
                    blur = 14.dp, refraction = 12.dp, bevel = 12.dp, dispersion = 0.2f,
                    tint = Color(0xFF1B1922).copy(alpha = 0.64f), saturation = 1.3f, lift = 0f,
                    highlight = 0.45f, shadowAlpha = 0f,
                ),
                accentSurface = GlassStyle(
                    blur = 14.dp, refraction = 12.dp, bevel = 12.dp, dispersion = 0.2f,
                    tint = accent.copy(alpha = 0.9f), saturation = 1.2f, lift = 0f,
                    highlight = 0.5f, shadowAlpha = 0f,
                ),
                lensRest = GlassStyle(
                    blur = 0.dp, refraction = 10.dp, bevel = 12.dp, dispersion = 0.12f,
                    tint = Color.White.copy(alpha = 0.10f), saturation = 1.2f, lift = 0.02f,
                    highlight = 0.6f, shadowAlpha = 0f,
                ),
                lensHeld = GlassStyle(
                    blur = 0.dp, refraction = 18.dp, bevel = 16.dp, dispersion = 0.22f,
                    tint = Color.White.copy(alpha = 0.03f), saturation = 1.3f, lift = 0.03f,
                    highlight = 0.95f, shadowAlpha = 0f, zoom = 1.18f,
                ),
            )
        } else {
            GlassPalette(
                dark = false,
                accent = accent,
                accentContent = accentContent,
                content = Color(0xFF1C1A22),
                contentSecondary = Color(0xFF1C1A22).copy(alpha = 0.6f),
                error = Color(0xFFB42318),
                notice = GlassStyle(
                    blur = 10.dp, refraction = 10.dp, bevel = 10.dp, dispersion = 0.2f,
                    tint = Color.White.copy(alpha = 0.82f), saturation = 1.2f, lift = 0f,
                    highlight = 0.5f, shadowAlpha = 0.05f, shadowRadius = 10.dp, shadowOffsetY = 2.dp,
                ),
                chrome = GlassStyle(
                    blur = 2.dp, refraction = 22.dp, bevel = 18.dp, dispersion = 0.35f,
                    tint = Color.White.copy(alpha = 0.16f), saturation = 1.5f, lift = 0.03f,
                    highlight = 0.8f, shadowAlpha = 0.08f,
                ),
                bar = GlassStyle(
                    blur = 8.dp, refraction = 20.dp, bevel = 16.dp, dispersion = 0.3f,
                    tint = Color.White.copy(alpha = 0.55f), saturation = 1.4f, lift = 0.02f,
                    highlight = 0.8f, shadowAlpha = 0.07f,
                ),
                surface = GlassStyle(
                    blur = 14.dp, refraction = 12.dp, bevel = 12.dp, dispersion = 0.2f,
                    tint = Color.White.copy(alpha = 0.62f), saturation = 1.3f, lift = 0f,
                    highlight = 0.6f, shadowAlpha = 0.07f, shadowRadius = 12.dp, shadowOffsetY = 3.dp,
                ),
                accentSurface = GlassStyle(
                    blur = 14.dp, refraction = 12.dp, bevel = 12.dp, dispersion = 0.2f,
                    tint = accent.copy(alpha = 0.9f), saturation = 1.2f, lift = 0f,
                    highlight = 0.55f, shadowAlpha = 0.08f, shadowRadius = 12.dp, shadowOffsetY = 3.dp,
                ),
                lensRest = GlassStyle(
                    blur = 0.dp, refraction = 10.dp, bevel = 12.dp, dispersion = 0.12f,
                    tint = Color.White.copy(alpha = 0.16f), saturation = 1.2f, lift = 0.03f,
                    highlight = 0.9f, shadowAlpha = 0f,
                ),
                lensHeld = GlassStyle(
                    blur = 0.dp, refraction = 18.dp, bevel = 16.dp, dispersion = 0.22f,
                    tint = Color.White.copy(alpha = 0.06f), saturation = 1.3f, lift = 0.03f,
                    highlight = 1f, shadowAlpha = 0.12f, zoom = 1.18f,
                ),
            )
        }
    }
}

private fun chatJumpStyle(palette: GlassPalette): GlassStyle = palette.chrome.copy(
    blur = 0.dp,
    refraction = (-40).dp,
    bevel = 13.dp,
    dispersion = 0.06f,
    zoom = 0.95f,
    tint = palette.chrome.tint.copy(alpha = 0f),
    saturation = 1.43f,
    highlight = 0.32f,
    shadowAlpha = if (palette.dark) 0f else 0.07f,
)

val LocalGlassPalette = staticCompositionLocalOf { GlassPalettes.build(dark = false) }
internal val LocalGlassAutoTint = compositionLocalOf { true }

/** The wallpaper on its own, for glass that sits inside scrolling content. */
val LocalWallpaperBackdrop = staticCompositionLocalOf<Backdrop> {
    error("LocalWallpaperBackdrop is provided at the root of the app")
}
