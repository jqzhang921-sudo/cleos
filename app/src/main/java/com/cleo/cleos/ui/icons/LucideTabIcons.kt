// Lucide Icons: Copyright (c) 2026 Lucide Icons and Contributors. ISC License.
// Upstream SVGs, full license and pinned provenance: licenses/lucide/.
package com.cleo.cleos.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Four bundled upstream icons; no network lookup or icon-pack dependency at runtime. */
internal object LucideTabIcons {
    val MessageCircle: ImageVector by lazy { icon("MessageCircle",
        "M 2.992 16.342 a 2 2 0 0 1 .094 1.167 l -1.065 3.29 a 1 1 0 0 0 1.236 1.168 l 3.413 -.998 a 2 2 0 0 1 1.099 .092 10 10 0 1 0 -4.777 -4.719",
    ) }
    val Notebook: ImageVector by lazy { icon("Notebook",
        "M2 6h4",
        "M2 10h4",
        "M2 14h4",
        "M2 18h4",
        "M 6 2 H 18 A 2 2 0 0 1 20 4 V 20 A 2 2 0 0 1 18 22 H 6 A 2 2 0 0 1 4 20 V 4 A 2 2 0 0 1 6 2 Z",
        "M16 2v20",
    ) }
    val Globe: ImageVector by lazy { icon("Globe",
        "M 22 12 A 10 10 0 1 0 2 12 A 10 10 0 1 0 22 12 Z",
        "M 12 2 a 14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0 -20",
        "M 2 12 h 20",
    ) }
    val House: ImageVector by lazy { icon("House",
        "M 15 21 v -8 a 1 1 0 0 0 -1 -1 h -4 a 1 1 0 0 0 -1 1 v 8",
        "M 3 10 a 2 2 0 0 1 .709 -1.528 l 7 -6 a 2 2 0 0 1 2.582 0 l 7 6 A 2 2 0 0 1 21 10 v 9 a 2 2 0 0 1 -2 2 H 5 a 2 2 0 0 1 -2 -2 z",
    ) }

    // Cleos filled adaptations of the same silhouettes; details are transparent cutouts.
    val MessageCircleFilled: ImageVector by lazy { filled("MessageCircleFilled",
        "M 2.992 16.342 a 2 2 0 0 1 .094 1.167 l -1.065 3.29 a 1 1 0 0 0 1.236 1.168 l 3.413 -.998 a 2 2 0 0 1 1.099 .092 10 10 0 1 0 -4.777 -4.719 Z",
        "",
    ) }
    val NotebookFilled: ImageVector by lazy { filled("NotebookFilled",
        "M 6 2 H 18 A 2 2 0 0 1 20 4 V 20 A 2 2 0 0 1 18 22 H 6 A 2 2 0 0 1 4 20 V 4 A 2 2 0 0 1 6 2 Z",
        "M 0 0 H 24 V 24 H 0 Z M 15.25 4 V 20 H 16.75 V 4 Z",
        "M2 6h4",
        "M2 10h4",
        "M2 14h4",
        "M2 18h4",
    ) }
    val GlobeFilled: ImageVector by lazy { filled("GlobeFilled",
        "M 22 12 A 10 10 0 1 0 2 12 A 10 10 0 1 0 22 12 Z",
        "M 0 0 H 24 V 24 H 0 Z M 0 11.25 V 12.75 H 24 V 11.25 Z M 9.51684 4 A 15.25 15.25 0 0 0 7.26845 11.25 L 8.77047 11.25 A 13.75 13.75 0 0 1 11.31687 4 Z M 7.26845 12.75 A 15.25 15.25 0 0 0 9.51684 20 L 11.31687 20 A 13.75 13.75 0 0 1 8.77047 12.75 Z M 12.68313 4 A 13.75 13.75 0 0 1 15.22953 11.25 L 16.73155 11.25 A 15.25 15.25 0 0 0 14.48316 4 Z M 15.22953 12.75 A 13.75 13.75 0 0 1 12.68313 20 L 14.48316 20 A 15.25 15.25 0 0 0 16.73155 12.75 Z",
    ) }
    val HouseFilled: ImageVector by lazy { filled("HouseFilled",
        "M 3 10 a 2 2 0 0 1 .709 -1.528 l 7 -6 a 2 2 0 0 1 2.582 0 l 7 6 A 2 2 0 0 1 21 10 v 9 a 2 2 0 0 1 -2 2 H 5 a 2 2 0 0 1 -2 -2 z",
        "M 0 0 H 24 V 24 H 0 Z M 9.5 24 H 14.5 V 13.5 Q 14.5 12.5 13.5 12.5 H 10.5 Q 9.5 12.5 9.5 13.5 Z",
    ) }

    private fun filled(name: String, silhouette: String, clip: String, vararg details: String): ImageVector = ImageVector.Builder(
        name = "Cleos.Lucide.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        if (clip.isNotEmpty()) addGroup(clipPathData = PathParser().parsePathString(clip).toNodes())
        addPath(pathData = PathParser().parsePathString(silhouette).toNodes(),
            fill = SolidColor(Color.Black), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        details.forEach { data ->
            addPath(pathData = PathParser().parsePathString(data).toNodes(), fill = null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        if (clip.isNotEmpty()) clearGroup()
    }.build()

    private fun icon(name: String, vararg paths: String): ImageVector = ImageVector.Builder(
        name = "Lucide.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        paths.forEach { data ->
            addPath(pathData = PathParser().parsePathString(data).toNodes(), fill = null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()
}
