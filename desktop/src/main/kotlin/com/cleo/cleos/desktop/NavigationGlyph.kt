package com.cleo.cleos.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

internal enum class DesktopDestination(val label: String) { Chats("聊天"), Diary("日记"), Todos("待办"), Letters("信件"), Favorites("收藏"), Settings("设置") }

/** Small original vector glyphs, scaled with the UI; no font symbols or extra icon dependency. */
@Composable internal fun NavigationGlyph(destination: DesktopDestination, color: Color) {
    Canvas(Modifier.size(23.dp)) {
        withTransform({scale(size.width/24f,size.height/24f,pivot=Offset.Zero)}) {
            val outline=Stroke(width=1.7f,cap=StrokeCap.Round,join=StrokeJoin.Round)
            when(destination) {
                DesktopDestination.Chats -> drawPath(Path().apply {
                    moveTo(5f,4f);lineTo(19f,4f);quadraticTo(21f,4f,21f,6f);lineTo(21f,16f);quadraticTo(21f,18f,19f,18f)
                    lineTo(10f,18f);lineTo(5f,21f);lineTo(5f,18f);quadraticTo(3f,18f,3f,16f);lineTo(3f,6f);quadraticTo(3f,4f,5f,4f);close()
                },color,style=outline)
                DesktopDestination.Diary -> {
                    drawPath(Path().apply {moveTo(12f,6f);quadraticTo(8f,3f,3f,5f);lineTo(3f,19f);quadraticTo(8f,17f,12f,20f);quadraticTo(16f,17f,21f,19f);lineTo(21f,5f);quadraticTo(16f,3f,12f,6f);close()},color,style=outline)
                    drawLine(color,Offset(12f,6f),Offset(12f,20f),strokeWidth=1.7f,cap=StrokeCap.Round)
                }
                DesktopDestination.Todos -> {
                    drawRoundRect(color,Offset(4f,3f),Size(16f,18f),CornerRadius(3f,3f),style=outline)
                    drawPath(Path().apply {moveTo(7f,9f);lineTo(9f,11f);lineTo(13f,7f)},color,style=outline)
                    drawLine(color,Offset(8f,16f),Offset(16f,16f),strokeWidth=1.7f,cap=StrokeCap.Round)
                }
                DesktopDestination.Letters -> {
                    drawRoundRect(color,Offset(3f,5f),Size(18f,14f),CornerRadius(2f,2f),style=outline)
                    drawPath(Path().apply {moveTo(3f,6f);lineTo(12f,13f);lineTo(21f,6f)},color,style=outline)
                }
                DesktopDestination.Favorites -> {
                    drawPath(Path().apply {moveTo(6f,3f);lineTo(18f,3f);lineTo(18f,21f);lineTo(12f,17f);lineTo(6f,21f);close()},color,style=outline)
                }
                DesktopDestination.Settings -> {
                    drawCircle(color,6.6f,Offset(12f,12f),style=outline)
                    drawCircle(color,2.6f,Offset(12f,12f),style=outline)
                    repeat(8) {i -> val angle=Math.PI*i/4;drawLine(color,Offset(12f+cos(angle).toFloat()*8f,12f+sin(angle).toFloat()*8f),Offset(12f+cos(angle).toFloat()*10f,12f+sin(angle).toFloat()*10f),strokeWidth=2.2f,cap=StrokeCap.Round)}
                }
            }
        }
    }
}
