package com.cleo.cleos.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.*

/**
 * The actions that are not the message itself, in the card material and one tap away: 点名 and 图片. They keep the
 * labels the buttons had, for screen readers. Voice is not here: recording starts only from its own button, never
 * from opening this.
 */
@Composable
internal fun GroupComposerTray(page: Backdrop, mentions: Int, photos: Int, maxPhotos: Int, photoEnabled: Boolean,
    onMention: () -> Unit, onPhoto: () -> Unit) {
    val palette = LocalGlassPalette.current
    GlassSurface(Modifier.widthIn(max = 300.dp), backdrop = page, style = palette.card, shape = GlassShape.Rounded(22.dp),
        contentPadding = PaddingValues(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TrayAction(Icons.Rounded.AlternateEmail, if (mentions > 0) "点名 $mentions" else "点名", "点名成员", true, onMention)
            TrayAction(Icons.Rounded.AddPhotoAlternate, if (photos > 0) "图片 $photos/$maxPhotos" else "图片", "发群图片", photoEnabled, onPhoto)
        }
    }
}

@Composable
private fun TrayAction(icon: ImageVector, label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    val ink = palette.content.copy(alpha = if (enabled) 1f else .35f)
    Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
        .padding(horizontal = 12.dp).semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
        Text(label, color = ink, fontSize = 14.sp, maxLines = 1)
    }
}
