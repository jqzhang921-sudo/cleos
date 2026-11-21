package com.cleo.cleos.glass

import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable

/** The parts of the app whose glass can be tuned in the glass lab. */
enum class GlassPart(val label: String) {
    Bubble("聊天气泡"),
    Card("卡片"),
    TopBar("顶栏"),
    TopBarTitle("标题胶囊"),
    TabBar("Tab 栏"),
    Input("输入框"),
    ChatJump("回底按钮"),

    /** The selected tab's glass behind its icon and label. */
    Lens("Tab 透镜"),
}

/**
 * What the glass lab saves for one part: the physical knobs, not colours.
 *
 * The tint *colour* stays with the palette, so a part tuned while the glass was light
 * still looks right after the wallpaper turns the glass dark. Only how strong the tint
 * is travels with the tuning.
 */
@Serializable
data class GlassTuning(
    val blur: Float,
    val refraction: Float,
    val bevel: Float,
    val dispersion: Float,
    val zoom: Float,
    val tintAlpha: Float,
    val saturation: Float,
    val highlight: Float,
    /**
     * Null: keep the part's own default shadow. Dark glass never casts one, so a part
     * tuned while the glass was dark saves no shadow rather than a 0 that would also
     * wipe the shadow out once the glass turns light again.
     */
    val shadow: Float? = null,
) {
    fun applyTo(style: GlassStyle, dark: Boolean): GlassStyle = style.copy(
        blur = blur.dp,
        refraction = refraction.dp,
        bevel = bevel.dp,
        dispersion = dispersion,
        zoom = zoom,
        tint = style.tint.copy(alpha = tintAlpha),
        saturation = saturation,
        highlight = highlight,
        // Dark glass never casts a shadow: a black shadow on a dark wallpaper is
        // invisible and only muddies the edge; the rim highlight outlines it instead.
        shadowAlpha = if (dark) 0f else shadow ?: style.shadowAlpha,
    )

    /** Same look, ignoring the shadow where it has no effect (dark glass). */
    fun sameAs(other: GlassTuning?, dark: Boolean): Boolean =
        other != null && (if (dark) copy(shadow = null) == other.copy(shadow = null) else this == other)

    companion object {
        /** [keepShadow]: what to save as the shadow when [style] is dark glass (its 0 means nothing). */
        fun of(style: GlassStyle, dark: Boolean = false, keepShadow: Float? = null) = GlassTuning(
            blur = style.blur.value,
            refraction = style.refraction.value,
            bevel = style.bevel.value,
            dispersion = style.dispersion,
            zoom = style.zoom,
            tintAlpha = style.tint.alpha,
            saturation = style.saturation,
            highlight = style.highlight,
            shadow = if (dark) keepShadow else style.shadowAlpha,
        )
    }
}
