package com.cleo.cleos.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DynamicFeed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cleo.cleos.data.FeedShare
import com.cleo.cleos.glass.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FeedShareCard(share: FeedShare, modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null) {
    val palette = LocalGlassPalette.current
    var open by remember { mutableStateOf(false) }
    val date = remember(share.createdAt) { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(share.createdAt)) }
    GlassSurface(modifier.combinedClickable(onClick = { open = true }, onLongClick = onLongClick),
        style = palette.card, shape = GlassShape.Rounded(18.dp), contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Rounded.DynamicFeed, null, tint = palette.accentContent, modifier = Modifier.size(17.dp))
                Text(if (share.topic) "话题" else "朋友圈", color = palette.accentContent, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (onRemove != null) IconButton(onRemove, Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Close, "取消转发", tint = palette.contentSecondary, modifier = Modifier.size(16.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(share.authorName, color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(date, color = palette.contentSecondary, fontSize = 11.sp)
            }
            Text(share.content, color = palette.content, fontSize = 14.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            HorizontalDivider(color = palette.content.copy(alpha = .1f))
            Text(share.sourceTitle?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "点开看看这条动态",
                color = palette.contentSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (open) Dialog(onDismissRequest = { open = false }) {
        val uri = LocalUriHandler.current
        var linkProblem by remember { mutableStateOf(false) }
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(share.authorName, color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(date, color = palette.contentSecondary, fontSize = 12.sp)
                    Text(share.content, color = palette.content, fontSize = 15.sp, lineHeight = 23.sp)
                    share.sourceTitle?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp) }
                    if (linkProblem) Text("无法打开来源", color = palette.error, fontSize = 12.sp)
                }
                Row(Modifier.align(Alignment.End)) {
                    share.sourceUrl?.let { link -> TextButton({ runCatching { uri.openUri(link) }.onFailure { linkProblem = true } }) { Text("查看来源", color = palette.accentContent) } }
                    TextButton({ open = false }) { Text("关闭", color = palette.content) }
                }
            }
        }
    }
}
