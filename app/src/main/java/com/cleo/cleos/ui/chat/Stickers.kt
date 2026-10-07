package com.cleo.cleos.ui.chat

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.cleo.cleos.ui.common.Avatar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cleo.cleos.data.MessageReaction
import com.cleo.cleos.data.MessageReactions
import com.cleo.cleos.data.StickerBook
import com.cleo.cleos.data.Stickers
import com.cleo.cleos.data.db.StickerEntity
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The collection the chat draws stickers from, by the names messages carry (StickerText). */
val LocalStickers = compositionLocalOf { StickerBook.EMPTY }

/** A sticker in the chat, on its longer side: about what chat apps show. */
private val StickerEdge = 120.dp

/** A cell of the drawer, and the picture in it. */
private val CellPicture = 56.dp

/** The drawer's height: three rows and a bit of the fourth, so it reads as something to scroll. */
private val DrawerHeight = 250.dp

/** Times a moving sticker plays through before it rests; a tap plays it again. */
private const val PLAYS = 3

private fun sizeOf(s: StickerEntity): Pair<Dp, Dp> {
    val ratio = (s.width.toFloat() / s.height.coerceAtLeast(1)).coerceIn(0.5f, 2f)
    return if (ratio >= 1f) StickerEdge to StickerEdge / ratio else StickerEdge * ratio to StickerEdge
}

/**
 * A sticker in a message: the picture on its own, no bubble around it, as chat apps show them.
 * A long press is the message's menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerView(sticker: StickerEntity, onLongClick: () -> Unit) {
    val c = appContainer()
    val (w, h) = sizeOf(sticker)
    val file = c.images.file(sticker.file)
    if (sticker.animated) {
        MovingSticker(file, sticker.name, w, h, onLongClick)
    } else {
        AsyncImage(
            model = file,
            contentDescription = "表情包：${sticker.name}",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(w, h)
                .combinedClickable(interactionSource = null, indication = null, onClick = {}, onLongClick = onLongClick),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MovingSticker(file: File, name: String, w: Dp, h: Dp, onLongClick: () -> Unit) {
    val edge = with(LocalDensity.current) { maxOf(w, h).roundToPx() }
    val drawable by produceState<AnimatedImageDrawable?>(null, file, edge) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val scale = min(1f, edge / max(info.size.width, info.size.height).toFloat())
                    if (scale < 1f) {
                        decoder.setTargetSize(
                            (info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                } as? AnimatedImageDrawable
            }.getOrNull()
        }
    }
    val d = drawable
    if (d == null) {
        Box(Modifier.size(w, h))
        return
    }
    val painter = remember(d) { MovingPainter(d) }
    Image(
        painter = painter,
        contentDescription = "表情包：$name",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(w, h)
            .combinedClickable(interactionSource = null, indication = null, onClick = painter::play, onLongClick = onLongClick),
    )
}

/**
 * A sticker that moves, as a painter. It plays a few times and then rests on its last frame:
 * anything that keeps moving under the glass has the whole screen drawn again every frame, the
 * way the record on the listening bar did until it stopped turning between lines.
 */
private class MovingPainter(private val drawable: AnimatedImageDrawable) : Painter(), RememberObserver {
    private var frame by mutableIntStateOf(0)
    private val main = Handler(Looper.getMainLooper())
    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            frame++
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            main.postAtTime(what, `when`)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            main.removeCallbacks(what)
        }
    }

    override val intrinsicSize: Size get() = Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())

    override fun DrawScope.onDraw() {
        // Read so that a new frame draws again.
        frame
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }

    fun play() {
        drawable.stop()
        drawable.repeatCount = PLAYS - 1
        drawable.start()
    }

    override fun onRemembered() {
        drawable.callback = callback
        play()
    }

    override fun onForgotten() {
        drawable.stop()
        drawable.callback = null
    }

    override fun onAbandoned() = onForgotten()
}

/** The emoji on a TA's message, under it; a tap opens the menu they are changed in. */
@Composable
fun ReactionChips(reactions: List<MessageReaction>, avatar: String?, letter: String, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    reactions.forEach { reaction -> GlassSurface(
        modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onClick),
        style = palette.notice,
        shape = GlassShape.Capsule,
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(reaction.emoji, fontSize = 18.sp)
            Avatar(avatar, letter, 20.dp)
        }
    }
    }
    }
}

/** The row at the top of a TA message's menu: tap one to put it on, tap one that is on to take it off. */
@Composable
fun ReactionPicker(on: Set<String>, onPick: (String) -> Unit) {
    val palette = LocalGlassPalette.current
    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        MessageReactions.OFFERED.forEach { e ->
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (e in on) palette.accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onPick(e) },
                contentAlignment = Alignment.Center,
            ) {
                Text(e, fontSize = 21.sp)
            }
        }
    }
}

/**
 * The person's stickers, inside the input's glass above the text. A tap sends one, a long press
 * renames or deletes it, ＋ adds one from the gallery. The names show under the pictures: they
 * are all the TA gets of them.
 */
@Composable
fun StickerDrawer(
    stickers: List<StickerEntity>,
    onSend: (StickerEntity) -> Unit,
    onAdd: () -> Unit,
    onRename: (StickerEntity) -> Unit,
    onDelete: (StickerEntity) -> Unit,
) {
    val palette = LocalGlassPalette.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CellPicture + 12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(DrawerHeight)
            .padding(horizontal = 10.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "add") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(CellPicture)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.content.copy(alpha = 0.08f))
                        .clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "加表情包", tint = palette.contentSecondary, modifier = Modifier.size(26.dp))
                }
                Text("加一张", color = palette.contentSecondary, fontSize = 11.sp, maxLines = 1)
            }
        }
        items(stickers, key = { it.id }) { s -> StickerCell(s, onSend, onRename, onDelete) }
        if (stickers.isEmpty()) {
            item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "从相册加几张表情包，起个名字。TA 看不到图，是按名字认的，也会从这里挑着发给你（设置里「发表情包」开着的话）。",
                    color = palette.contentSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerCell(
    sticker: StickerEntity,
    onSend: (StickerEntity) -> Unit,
    onRename: (StickerEntity) -> Unit,
    onDelete: (StickerEntity) -> Unit,
) {
    val palette = LocalGlassPalette.current
    val c = appContainer()
    var menu by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            // The first frame only, for one that moves: a drawer of them all moving would keep the screen drawing.
            AsyncImage(
                model = c.images.file(sticker.file),
                contentDescription = sticker.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(CellPicture)
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = { onSend(sticker) }, onLongClick = { menu = true }),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("改名字和说明") }, onClick = {
                    menu = false
                    onRename(sticker)
                })
                DropdownMenuItem(text = { Text("删除") }, onClick = {
                    menu = false
                    onDelete(sticker)
                })
            }
        }
        Text(
            sticker.name,
            color = palette.contentSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(CellPicture + 8.dp),
        )
    }
}

/** Naming a sticker just picked, or renaming one: its picture, its name, a line about it. */
@Composable
fun StickerDialog(draft: StickerDraft, problem: String?, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    val c = appContainer()
    val file: File
    val start: Pair<String, String>
    when (draft) {
        is StickerDraft.New -> {
            file = draft.picked.file
            start = "" to ""
        }
        is StickerDraft.Edit -> {
            file = c.images.file(draft.sticker.file)
            start = draft.sticker.name to draft.sticker.description
        }
    }
    var name by remember(draft) { mutableStateOf(start.first) }
    var about by remember(draft) { mutableStateOf(start.second) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft is StickerDraft.New) "新表情包" else "改表情包") },
        text = {
            // Short enough to stay above the keyboard, whose done key saves: the dialog's own
            // buttons end up under the keyboard on a phone.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(64.dp),
                    )
                    Text(
                        "TA 看不到图，是按名字和说明认的：名字起得像在说这张图，比如「兔子晕倒」。",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("\n", "").take(Stickers.MAX_NAME) },
                    label = { Text("名字") },
                    singleLine = true,
                    isError = problem != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = about,
                    onValueChange = { about = it.replace("\n", " ").take(Stickers.MAX_DESCRIPTION) },
                    label = { Text("说明（可以不写）") },
                    placeholder = { Text("图里是什么，比如：转着圈倒下") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(name, about) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, about) }) { Text("存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Before a sticker goes: what becomes of the messages that sent it. */
@Composable
fun DeleteStickerDialog(sticker: StickerEntity, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删掉「${sticker.name}」？") },
        text = { Text("聊天里发过的这张，会变成一行字：[表情包：${sticker.name}]。", fontSize = 14.sp, lineHeight = 20.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
