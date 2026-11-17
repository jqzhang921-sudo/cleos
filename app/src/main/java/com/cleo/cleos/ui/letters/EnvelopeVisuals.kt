package com.cleo.cleos.ui.letters

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.cleo.cleos.R
import com.cleo.cleos.data.EnvelopeSnapshot
import com.cleo.cleos.data.EnvelopeThemes
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/** Stable local asset ID is frozen in decoration; old snapshots have no texture. */
@Composable
internal fun Modifier.envelopeTexture(theme: EnvelopeSnapshot): Modifier {
    if(theme.decoration != EnvelopeThemes.FROST_FIBER) return this
    val bitmap=ImageBitmap.imageResource(R.drawable.envelope_frost_fiber_v1)
    // Drawing never supplies intrinsic constraints: adding a bitmap must not enlarge a letter.
    return drawWithCache {
        val scale=maxOf(size.width/bitmap.width,size.height/bitmap.height).coerceAtLeast(.001f)
        val w=(size.width/scale).roundToInt().coerceIn(1,bitmap.width)
        val h=(size.height/scale).roundToInt().coerceIn(1,bitmap.height)
        val filter=ColorFilter.tint(Color(theme.envelope),BlendMode.Modulate)
        onDrawBehind { if(size.width>0 && size.height>0) drawImage(bitmap,
            srcOffset=IntOffset((bitmap.width-w)/2,(bitmap.height-h)/2),srcSize=IntSize(w,h),
            dstSize=IntSize(size.width.roundToInt(),size.height.roundToInt()),alpha=.6f,colorFilter=filter) }
    }
}

@Composable
internal fun EnvelopeArtwork(theme: EnvelopeSnapshot, opened: Boolean, modifier: Modifier = Modifier,fold:Float=1f,sealSize:Dp=30.dp) {
    val ink=Color(theme.envelopeInk)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            if(fold<1f) {
                val paperHeight=size.height*(1f-fold)*.7f
                drawRoundRect(Color(theme.paper),Offset(size.width*.14f,0f),Size(size.width*.72f,paperHeight),cornerRadius=androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            }
            envelopeDecoration(theme,ink)
            val flap=Path().apply {
                moveTo(0f,0f)
                when(theme.flap) {
                    "rounded" -> quadraticTo(size.width*.5f,size.height*1.1f,size.width,0f)
                    "straight" -> { lineTo(size.width*.22f,size.height*.48f);lineTo(size.width*.78f,size.height*.48f);lineTo(size.width,0f) }
                    else -> { lineTo(size.width/2,size.height*(.18f+.48f*fold));lineTo(size.width,0f) }
                }
            }
            drawPath(flap,ink.copy(alpha=.2f),style=Stroke(1.dp.toPx()))
            drawLine(ink.copy(alpha=.12f),Offset(0f,size.height),Offset(size.width*.32f,size.height*.46f),1.dp.toPx())
            drawLine(ink.copy(alpha=.12f),Offset(size.width,size.height),Offset(size.width*.68f,size.height*.46f),1.dp.toPx())
        }
        Box(Modifier.align(Alignment.Center).size(sealSize).background(Color(theme.seal),CircleShape),contentAlignment=Alignment.Center) {
            Text(if(opened) "✓" else theme.sealMark,color=Color(EnvelopeThemes.inkOn(theme.seal)),fontSize=12.sp,lineHeight=14.sp,maxLines=1)
        }
    }
}

private fun DrawScope.envelopeDecoration(theme: EnvelopeSnapshot,ink: Color) {
    val color=ink.copy(alpha=.18f);val unit=minOf(size.width,size.height)*.14f
    val x=size.width*.88f;val y=size.height*.24f
    fun line(a:Offset,b:Offset)=drawLine(color,a,b,1.dp.toPx())
    fun star(cx:Float,cy:Float,r:Float,points:Int=5) {
        val p=Path()
        repeat(points*2) { i ->
            val a=i*Math.PI/points-Math.PI/2;val radius=if(i%2==0) r else r*.42f
            val px=cx+cos(a).toFloat()*radius;val py=cy+sin(a).toFloat()*radius
            if(i==0)p.moveTo(px,py) else p.lineTo(px,py)
        };p.close();drawPath(p,color)
    }
    when(theme.decoration) {
        "sakura" -> repeat(5) { i -> val a=i*Math.PI*2/5;drawCircle(color,unit*.5f,Offset(x+cos(a).toFloat()*unit*.65f,y+sin(a).toFloat()*unit*.65f)) }
        "clouds" -> { drawOval(color,Offset(x-unit*1.6f,y),Size(unit*3,unit));drawCircle(color,unit*.7f,Offset(x,y)) }
        "stars" -> { star(x,y,unit);star(size.width*.12f,size.height*.7f,unit*.5f);star(size.width*.76f,size.height*.8f,unit*.4f) }
        "moon" -> { drawArc(color,65f,280f,false,Offset(x-unit,y-unit),Size(unit*2,unit*2),style=Stroke(unit*.3f));star(x+unit*1.4f,y,unit*.4f) }
        "leaves" -> { rotate(-35f,Offset(x,y)) { drawOval(color,Offset(x-unit*.5f,y-unit),Size(unit,unit*2));line(Offset(x,y-unit),Offset(x,y+unit*1.6f)) } }
        "sun" -> { drawCircle(color,unit*.65f,Offset(x,y));repeat(10) { i ->val a=i*Math.PI/5;line(Offset(x+cos(a).toFloat()*unit*.9f,y+sin(a).toFloat()*unit*.9f),Offset(x+cos(a).toFloat()*unit*1.3f,y+sin(a).toFloat()*unit*1.3f)) } }
        "shells" -> { repeat(4) { i -> drawArc(color,180f,180f,false,Offset(x-unit+i*unit*.2f,y-unit+i*unit*.2f),Size(unit*2-i*unit*.4f,unit*2-i*unit*.4f),style=Stroke(1.dp.toPx())) } }
        "snow" -> repeat(3) { i ->rotate(i*60f,Offset(x,y)) { line(Offset(x-unit,y),Offset(x+unit,y));line(Offset(x+unit*.4f,y-unit*.3f),Offset(x+unit*.7f,y));line(Offset(x+unit*.4f,y+unit*.3f),Offset(x+unit*.7f,y)) } }
        "maple" -> { star(x,y,unit,7);line(Offset(x,y),Offset(x+unit*.5f,y+unit*1.5f)) }
        "stamp" -> { drawRect(color,Offset(x-unit,y-unit),Size(unit*1.8f,unit*2),style=Stroke(1.dp.toPx()));repeat(4) { i ->line(Offset(x-unit*2.9f,y+unit*(i*.4f-.8f)),Offset(x-unit*1.4f,y+unit*(i*.4f-.8f))) } }
        "lace" -> repeat(12) { i ->drawCircle(color,unit*.27f,Offset(size.width*i/11,size.height*.13f),style=Stroke(1.dp.toPx())) }
        "aurora" -> repeat(3) { i ->drawPath(Path().apply { moveTo(size.width*.58f,size.height*(.1f+i*.14f));cubicTo(size.width*.7f,-unit,size.width*.76f,size.height,size.width,size.height*.6f) },color.copy(alpha=.10f+i*.04f),style=Stroke(unit*.25f)) }
        "cherries" -> { drawCircle(color,unit*.5f,Offset(x-unit*.6f,y+unit*.6f));drawCircle(color,unit*.5f,Offset(x+unit*.6f,y+unit*.6f));line(Offset(x-unit*.6f,y+unit*.6f),Offset(x,y-unit));line(Offset(x+unit*.6f,y+unit*.6f),Offset(x,y-unit)) }
    }
}

internal fun Modifier.letterPaper(theme: EnvelopeSnapshot) = drawBehind {
    val color=Color(theme.ink).copy(alpha=.07f)
    val gap=29.dp.toPx()
    when(theme.paperPattern) {
        "lined" -> { var y=gap;while(y<size.height) { drawLine(color,Offset(0f,y),Offset(size.width,y),.5.dp.toPx());y+=gap } }
        "dots" -> { var y=gap*.5f;while(y<size.height) {var x=gap*.5f;while(x<size.width){drawCircle(color,1.dp.toPx(),Offset(x,y));x+=gap};y+=gap} }
    }
}
