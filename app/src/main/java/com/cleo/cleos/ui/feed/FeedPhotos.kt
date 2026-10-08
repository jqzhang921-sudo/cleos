package com.cleo.cleos.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.ui.common.appContainer

/** One photo breathes; albums use compact squares, as in a shared moments timeline. */
@Composable
internal fun FeedPhotos(pictures: List<MessageImage>, onOpen: (String) -> Unit,
    modifier: Modifier = Modifier, compact: Boolean = false, onRemove: ((MessageImage) -> Unit)? = null) {
    val c = appContainer()
    val columns = if (pictures.size == 1) 1 else if (pictures.size in listOf(2, 4)) 2 else 3
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        pictures.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEachIndexed { columnIndex, image ->
                    val ratio = if (pictures.size == 1 && !compact) (image.width.toFloat() / image.height.coerceAtLeast(1)).coerceIn(.7f, 1.6f) else 1f
                    val size = if (compact) Modifier.height(88.dp) else Modifier.aspectRatio(ratio)
                    Box(Modifier.weight(1f).then(size).clip(RoundedCornerShape(10.dp))) {
                        AsyncImage(c.images.file(image.file), "照片 ${rowIndex * columns + columnIndex + 1}",
                            Modifier.fillMaxSize().clickable { onOpen(image.file) }, contentScale = ContentScale.Crop)
                        if (onRemove != null) Box(Modifier.align(Alignment.TopEnd).size(40.dp)
                            .clickable { onRemove(image) }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Close, "移除照片 ${rowIndex * columns + columnIndex + 1}", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
