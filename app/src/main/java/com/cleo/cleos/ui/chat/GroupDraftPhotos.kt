package com.cleo.cleos.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.appContainer

@Composable
internal fun GroupDraftPhotos(pictures: List<MessageImage>, enabled: Boolean,
                             onRemove: (String) -> Unit, onOpen: (String) -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    GlassSurface(Modifier.fillMaxWidth(), style = palette.card,
        shape = GlassShape.Rounded(20.dp), contentPadding = PaddingValues(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pictures.forEachIndexed { i, p ->
                    Box(Modifier.size(72.dp)) {
                        AsyncImage(c.images.file(p.file), "已选群图片 ${i + 1}",
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).clickable { onOpen(p.file) },
                            contentScale = ContentScale.Crop)
                        GlassIconButton(Icons.Rounded.Close, "移除群图片 ${i + 1}", { onRemove(p.file) },
                            LocalWallpaperBackdrop.current, modifier = Modifier.align(Alignment.TopEnd).size(28.dp), enabled = enabled)
                    }
                }
            }
            Text("已选 ${pictures.size} / 3 张，发送前可以移除", color = palette.contentSecondary, fontSize = 11.sp)
        }
    }
}
