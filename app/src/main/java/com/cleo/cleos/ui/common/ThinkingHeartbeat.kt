package com.cleo.cleos.ui.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.LocalPageShown
import kotlin.math.PI
import kotlin.math.sin

/** Original Cleos heart and ECG paths. Animation only invalidates this small drawing. */
@Composable
internal fun ThinkingHeartbeat(running: Boolean, modifier: Modifier = Modifier) {
    val ink = LocalGlassPalette.current.contentSecondary
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = running && LocalPageShown.current && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && systemMotionEnabled()
    val progress = if (animate) {
        rememberInfiniteTransition(label = "thinking-heartbeat").animateFloat(0f, 1f,
            infiniteRepeatable(tween(1350, easing = LinearEasing)), label = "thinking-heartbeat-wave")
    } else rememberUpdatedState(0f)
    val heart = remember { Path().apply {
        moveTo(3f, 9.5f)
        cubicTo(2.4f, 5.5f, 5f, 3f, 8.1f, 3f)
        cubicTo(9.8f, 3f, 11.2f, 3.8f, 12f, 5.1f)
        cubicTo(13.1f, 3.6f, 14.6f, 3f, 16.2f, 3f)
        cubicTo(20.6f, 3f, 22.6f, 6.5f, 21.3f, 10.7f)
        cubicTo(20f, 15f, 15.6f, 19f, 12f, 21f)
        cubicTo(9.1f, 19.3f, 6.2f, 17.1f, 4.4f, 14.7f)
    } }
    val wave = remember { Path().apply {
        moveTo(2f, 12f); lineTo(6.5f, 12f); lineTo(9f, 8.5f)
        lineTo(12f, 15f); lineTo(14.3f, 11.5f); lineTo(17.2f, 11.5f)
    } }
    val measure = remember { PathMeasure().apply { setPath(wave, false) } }
    val trace = remember { Path() }
    Canvas(modifier.semantics { contentDescription = if (running) "正在思考" else "思考过程" }) {
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            val stroke = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(heart, ink, style = stroke)
            val p = progress.value
            val gain = if (animate) 1f + .14f * sin(p * 2 * PI).toFloat() else 1f
            withTransform({ scale(1f, gain, Offset(0f, 12f)) }) {
                drawPath(wave, ink.copy(alpha = if (animate) .55f else 1f), style = stroke)
                if (animate) {
                    trace.reset()
                    measure.getSegment(((p - .25f) * measure.length).coerceAtLeast(0f), p * measure.length, trace, true)
                    drawPath(trace, ink.copy(alpha = sin(p * PI).toFloat().coerceIn(0f, 1f)), style = stroke)
                }
            }
        }
    }
}
