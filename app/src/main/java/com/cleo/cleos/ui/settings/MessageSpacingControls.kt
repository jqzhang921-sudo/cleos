package com.cleo.cleos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.BubbleSpacing
import com.cleo.cleos.glass.LocalGlassPalette
import kotlin.math.roundToInt

@Composable
internal fun MessageSpacingControls(value: Int, onSave: (Int) -> Unit, onPreview: (Int) -> Unit = {}) {
    val palette = LocalGlassPalette.current
    var gap by remember(value) { mutableFloatStateOf(BubbleSpacing.normalized(value).toFloat()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("紧贴" to 0, "标准" to BubbleSpacing.DEFAULT, "宽松" to 12).forEach { (label, size) ->
            Chip(label, gap.roundToInt() == size) { gap = size.toFloat(); onPreview(size); onSave(size) }
        }
    }
    Text("消息间距：${gap.roundToInt()}", color = palette.contentSecondary, fontSize = 12.sp)
    Slider(gap, { gap = it; onPreview(it.roundToInt()) }, valueRange = 0f..BubbleSpacing.MAX.toFloat(),
        modifier = Modifier.semantics { contentDescription = "消息间距" },
        steps = BubbleSpacing.MAX - 1, onValueChangeFinished = { onSave(gap.roundToInt()) })
    Text("两条消息之间的距离，松手保存，聊天两边共用。头像和伸出气泡的装饰仍会占一点空间。", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
}
