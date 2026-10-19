package com.cleo.cleos.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.ai.GroupUploadRules
import com.cleo.cleos.glass.*

/** Same stored preview and attribution as private chat, under this group's actual speaker. */
@Composable
internal fun GroupPhotoCard(photo: MessageImage, maxWidth: Dp, onOpen: (String) -> Unit, onLongClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    GlassSurface(Modifier.widthIn(max = maxWidth).padding(top = 8.dp), style = palette.card,
        shape = GlassShape.Rounded(22.dp), contentPadding = PaddingValues(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PictureGroup(listOf(photo), onOpen, onLongClick)
            Text(if (photo.source == GroupUploadRules.SOURCE) "用户上传" else "来自 " + photo.source.orEmpty(),
                color = palette.contentSecondary, fontSize = 12.sp)
            photo.credit?.let { PhotoAttribution(it, Modifier.widthIn(max = minOf(maxWidth - 20.dp, 240.dp))) }
        }
    }
}
