package com.cleo.cleos.ui.theme

import android.app.Activity
import android.graphics.BitmapFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.cleo.cleos.ui.chat.LocalMessageRendering
import com.cleo.cleos.ui.chat.MessageRendering
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.GlassMode
import com.cleo.cleos.data.ImageStore
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassPalette
import com.cleo.cleos.glass.GlassPalettes
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.LocalWallpaperBackdrop
import com.cleo.cleos.glass.WallpaperOverscan
import com.cleo.cleos.glass.backdropSource
import com.cleo.cleos.glass.overscan
import com.cleo.cleos.glass.rememberBackdrop
import com.cleo.cleos.ui.wallpaper.DefaultWallpaper
import com.cleo.cleos.ui.wallpaper.WallpaperAnalyzer
import com.cleo.cleos.ui.wallpaper.defaultWallpaperTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The root of every screen: decides light or dark glass and the accent from the
 * wallpaper, draws the wallpaper once as the backdrop everything else can look
 * through, and gives Material components colours that match the glass.
 */
@Composable
fun CleosTheme(settings: AppSettings, images: ImageStore, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val custom = settings.wallpaper
    val dark = when (settings.glassMode) {
        GlassMode.Light -> false
        GlassMode.Dark -> true
        GlassMode.Auto -> if (custom != null) settings.wallpaperDark ?: systemDark else systemDark
    }
    // The built-in wallpaper is measured once per light/dark, the same way a photo is.
    val defaultTone = remember(dark) { defaultWallpaperTone(dark) }
    val palette = remember(
        dark, custom, settings.wallpaperHue, settings.wallpaperChroma,
        settings.wallpaperTrough, settings.wallpaperPeak, settings.glassTuning, defaultTone, settings.myBubble,
    ) {
        val mine = settings.myBubble?.let { Color(it) }
        if (custom != null && settings.wallpaperHue != null) {
            GlassPalettes.build(
                dark = dark,
                hue = settings.wallpaperHue,
                chromaScale = WallpaperAnalyzer.chromaScale(settings.wallpaperChroma),
                trough = settings.wallpaperTrough,
                peak = settings.wallpaperPeak,
                tuning = settings.glassTuning,
                mine = mine,
            )
        } else {
            GlassPalettes.build(
                dark = dark,
                trough = defaultTone.trough,
                peak = defaultTone.peak,
                tuning = settings.glassTuning,
                mine = mine,
            )
        }
    }
    val wallpaperBackdrop = rememberBackdrop(flat = true)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !palette.dark
                isAppearanceLightNavigationBars = !palette.dark
            }
        }
    }

    MaterialTheme(colorScheme = colorSchemeFor(palette)) {
        CompositionLocalProvider(
            LocalGlassPalette provides palette,
            LocalMessageRendering provides MessageRendering(settings.messageRichText, settings.messageColors),
            LocalWallpaperBackdrop provides wallpaperBackdrop,
        ) {
            Box(Modifier.fillMaxSize()) {
                Wallpaper(custom, dark, images, wallpaperBackdrop)
                content()
            }
        }
    }
}

@Composable
private fun Wallpaper(file: String?, dark: Boolean, images: ImageStore, backdrop: Backdrop) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val longEdge = with(density) { max(config.screenWidthDp, config.screenHeightDp).dp.roundToPx() }
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        bitmap = if (file == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val f = images.file(file)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, bounds)
                    var sample = 1
                    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longEdge) sample *= 2
                    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val modifier = Modifier.fillMaxSize().overscan(WallpaperOverscan).backdropSource(backdrop)
    val image = bitmap
    if (file != null && image != null) {
        // The picture fills the screen, not the larger area the wallpaper is laid out in: filling that
        // blew every picture up (1.3 times on a 360dp-wide phone), and one shaped like the screen lost
        // an eighth on every side. Past the screen edge, where only blur looks, the picture carries on
        // mirrored, so there is no seam for the blur to pick up.
        val brush = remember(image) { ShaderBrush(ImageShader(image, TileMode.Mirror, TileMode.Mirror)) }
        Box(
            modifier.drawBehind {
                val edge = WallpaperOverscan.toPx()
                val w = size.width - 2 * edge
                val h = size.height - 2 * edge
                val scale = max(w / image.width, h / image.height)
                val left = edge + (w - image.width * scale) / 2
                val top = edge + (h - image.height * scale) / 2
                withTransform({
                    translate(left, top)
                    scale(scale, scale, pivot = Offset.Zero)
                }) {
                    // In the picture's own pixels, the whole area: past the picture on every side.
                    drawRect(brush, topLeft = Offset(-left / scale, -top / scale), size = Size(size.width / scale, size.height / scale))
                }
            },
        )
    } else {
        DefaultWallpaper(dark, modifier)
    }
}

private fun colorSchemeFor(p: GlassPalette) = if (p.dark) {
    darkColorScheme(
        primary = p.accentContent,
        onPrimary = Color(0xFF15131B),
        primaryContainer = p.accent,
        onPrimaryContainer = Color.White,
        secondary = p.accentContent,
        background = Color(0xFF15131C),
        onBackground = p.content,
        surface = Color(0xFF1F1D26),
        onSurface = p.content,
        onSurfaceVariant = p.contentSecondary,
        surfaceContainerLowest = Color(0xFF15131C),
        surfaceContainerLow = Color(0xFF1C1A23),
        surfaceContainer = Color(0xFF221F29),
        surfaceContainerHigh = Color(0xFF28252F),
        surfaceContainerHighest = Color(0xFF2E2B36),
        outline = p.content.copy(alpha = 0.35f),
        outlineVariant = p.content.copy(alpha = 0.14f),
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = Color.White,
        primaryContainer = p.accent.copy(alpha = 0.14f),
        onPrimaryContainer = p.accentContent,
        secondary = p.accentContent,
        background = Color(0xFFF7F5FA),
        onBackground = p.content,
        surface = Color(0xFFFCFBFE),
        onSurface = p.content,
        onSurfaceVariant = p.contentSecondary,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF8F6FB),
        surfaceContainer = Color(0xFFF3F1F7),
        surfaceContainerHigh = Color(0xFFEDEAF2),
        surfaceContainerHighest = Color(0xFFE7E4ED),
        outline = p.content.copy(alpha = 0.35f),
        outlineVariant = p.content.copy(alpha = 0.12f),
    )
}
