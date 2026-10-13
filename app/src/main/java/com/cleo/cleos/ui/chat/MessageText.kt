package com.cleo.cleos.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.data.PhotoCredit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
internal fun PhotoAttribution(credit: PhotoCredit, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val links = buildAnnotatedString {
        listOf("查看来源" to credit.pageUrl, credit.license to credit.licenseUrl).forEachIndexed { index, (label, url) ->
            if (index > 0) append(" · ")
            val valid = url.toHttpUrlOrNull()?.let { it.isHttps && it.username.isEmpty() && it.password.isEmpty() } == true
            if (valid) withLink(LinkAnnotation.Url(url,
                TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))) {
                runCatching { uriHandler.openUri(url) }.onFailure {
                    Toast.makeText(context, "链接暂时打不开", Toast.LENGTH_SHORT).show()
                }
            }) { append(label) } else append(label)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(credit.title, color = palette.content, fontSize = 12.sp, lineHeight = 17.sp)
        Text("作者：${credit.creator}", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        Text(links, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        credit.previewNote?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}

/** A tap opens only the URL span; the rest of the bubble retains its message menu. */
@Composable
internal fun MessageText(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val parts = remember(text) { ChatLinks.parts(text) }
    val linked = remember(parts, color, uriHandler, context) {
        buildAnnotatedString {
            parts.forEach { part ->
                val url = part.url
                if (url == null) append(part.text)
                else withLink(LinkAnnotation.Url(url,
                    TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.Medium))) {
                    runCatching { uriHandler.openUri(url) }.onFailure {
                        Toast.makeText(context, "链接暂时打不开，可以长按消息复制", Toast.LENGTH_SHORT).show()
                    }
                }) { append(part.text) }
            }
        }
    }
    Text(linked, color = color, style = style, modifier = modifier)
}

@Composable
internal fun SelectMessageTextDialog(text: String, style: TextStyle, onDismiss: () -> Unit) {
    val palette = LocalGlassPalette.current
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, shape = GlassShape.Rounded(28.dp),
            contentPadding = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("选择文字", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("长按文字，拖动两端选取，再点复制", color = palette.contentSecondary, fontSize = 12.sp)
                // One eagerly composed text keeps selections intact across scroll boundaries.
                SelectionContainer {
                    Text(text, color = palette.content, style = style,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()).padding(vertical = 8.dp))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("关闭", color = palette.content)
                }
            }
        }
    }
}
