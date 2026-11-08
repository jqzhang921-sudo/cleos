package com.cleo.cleos.ui.call

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.ui.common.avatarLetter

/** One quiet glass surface; the tinted actions share it rather than adding two blur layers. */
@Composable
internal fun IncomingCallBanner(
    ta: CompanionEntity,
    answering: Boolean,
    enabled: Boolean,
    onDecline: () -> Unit,
    onAnswer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    val green = if (palette.dark) Color(0xFFA5E5B8) else Color(0xFF287345)
    val largeType = LocalDensity.current.fontScale > 1.3f
    GlassSurface(
        modifier.widthIn(max = 520.dp).fillMaxWidth().semantics { contentDescription = "TA 主动来电" },
        shape = GlassShape.Rounded(28.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        BoxWithConstraints {
            val stacked = largeType || maxWidth < 280.dp
            @Composable fun Actions(modifier: Modifier = Modifier) {
                Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    @Composable fun Action(label: String, description: String, ink: Color, onClick: () -> Unit) {
                        Button(
                            onClick = onClick,
                            enabled = enabled && !answering,
                            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 52.dp)
                                .semantics { contentDescription = description },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = ink.copy(alpha = 0.16f), contentColor = ink),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            elevation = null,
                        ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    }
                    Action("拒绝", "拒绝 TA 来电", palette.error, onDecline)
                    Action(if (answering) "接通中" else "接听", "接听 TA 来电", green, onAnswer)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(ta.avatar, ta.avatarEmoji ?: avatarLetter(ta.name, "TA"), 44.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(ta.name.ifBlank { "TA" }, color = palette.content, fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Rounded.Call, null, Modifier.size(12.dp), tint = palette.accentContent)
                            Text(if (answering) "正在接通…" else "来电中 · 接听后开麦", color = palette.contentSecondary,
                                fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (!stacked) Actions()
                }
                if (stacked) Actions(Modifier.align(Alignment.End))
            }
        }
    }
}
