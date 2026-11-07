package com.cleo.cleos.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.MessageUsage
import com.cleo.cleos.data.MessageUsages
import com.cleo.cleos.data.BubbleDecoration
import com.cleo.cleos.glass.LocalGlassPalette
import kotlin.math.roundToInt

@Composable
internal fun TokenUsageLine(
    usage: MessageUsage,
    messageId: Long,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    val palette = LocalGlassPalette.current

    val cachePart = usage.cacheRate?.let { " · 缓存 ${(it * 100f).roundToInt()}%" }.orEmpty()
    val reasoningPart = usage.reasoning?.takeIf { it > 0 }?.let { " · 思考 ${MessageUsages.short(it)}" }.orEmpty()
    val summaryText = "↑${MessageUsages.short(usage.prompt)}$cachePart · ↓${MessageUsages.short(usage.completion)} tokens$reasoningPart"

    Column(
        modifier = modifier.padding(top = 2.dp, start = 4.dp),
    ) {
        Text(
            text = summaryText,
            fontSize = 11.sp,
            color = palette.contentSecondary.copy(alpha = 0.8f),
            modifier = Modifier.clickable(
                interactionSource = null,
                indication = null,
                onClick = { expanded = !expanded },
            ),
        )

        // Reveal the full-size bubble from its top edge. Avoid an alpha transition
        // so the glass does not also need a fading offscreen compositing layer.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top,
            ),
            exit = shrinkVertically(
                animationSpec = tween(180, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top,
            ),
        ) {
            val maxWidth = bubbleMaxWidth()
            ChatBubbleSurface(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .widthIn(max = maxWidth)
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = { expanded = false },
                    ),
                mine = false,
                horizontalPadding = 12,
                verticalPadding = 8,
                decoration = BubbleDecoration(faceEnabled = false, starsEnabled = false),
            ) { ink ->
                val secondaryInk = ink.copy(alpha = ink.alpha * 0.72f)
                Column(
                    modifier = Modifier.widthIn(min = minOf(180.dp, maxWidth), max = maxWidth),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (usage.model.isNotBlank()) {
                        UsageRow("模型", usage.model, ink)
                    }
                    UsageRow("输入", "%,d".format(usage.prompt), ink)
                    if (usage.cached != null) {
                        val pct = ((usage.cacheRate ?: 0f) * 100f).roundToInt()
                        UsageRow("其中缓存命中", "${"%,d".format(usage.cached)} ($pct%)", ink)
                    }
                    UsageRow("输出", "%,d".format(usage.completion), ink)
                    if (usage.reasoning != null) {
                        UsageRow("其中思考", "%,d".format(usage.reasoning), ink)
                    }
                    UsageRow("合计", "%,d".format(usage.total), ink)

                    if (usage.steps.size > 1) {
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(color = ink.copy(alpha = ink.alpha * 0.15f))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "共 ${usage.steps.size} 次请求（含工具调用）",
                            fontSize = 12.sp,
                            color = secondaryInk,
                        )
                        usage.steps.forEachIndexed { i, step ->
                            val stepCache = if (step.cached != null) " 缓存 ${MessageUsages.short(step.cached)}" else ""
                            Text(
                                text = "第 ${i + 1} 次  ↑${MessageUsages.short(step.prompt)} ↓${MessageUsages.short(step.completion)}$stepCache",
                                fontSize = 12.sp,
                                color = secondaryInk,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageRow(
    label: String,
    value: String,
    ink: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = ink.copy(alpha = ink.alpha * 0.72f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            color = ink,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
