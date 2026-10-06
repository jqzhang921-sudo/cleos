package com.cleo.cleos.ui.diary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassButton
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.LocalWallpaperBackdrop
import com.cleo.cleos.glass.liquidGlass
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.appViewModel
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

private val ToolbarHeight = 52.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryEditorScreen(id: Long, startSecret: Boolean = false, onBack: () -> Unit, onOpenImage: (String) -> Unit) {
    val vm = appViewModel(key = "diary-$id") { DiaryEditorViewModel(it, id, startSecret) }
    val c = appContainer()
    // A TA's entry is labelled with that TA; a secret is kept from every TA.
    val companions by remember { c.companions.all }.collectAsStateWithLifecycle(emptyList())
    val ai = companions.firstOrNull { it.id == vm.companionId }?.name?.trim()?.ifEmpty { null } ?: "TA"
    val palette = LocalGlassPalette.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    var pickingDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) {
        vm.insertImages(it)
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val bottomBase = if (imeBottom > navBottom) imeBottom else navBottom

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = Dates.full(vm.day),
                subtitle = when {
                    !vm.loaded -> null
                    vm.readOnly -> "${ai}写的"
                    vm.secret -> "小秘密 · ${ai}看不到"
                    else -> null
                },
                backdrop = page,
                leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) },
                trailing = {
                    if (!vm.readOnly) GlassIconButton(Icons.Rounded.CalendarMonth, "改日期", { pickingDate = true }, page)
                    GlassIconButton(Icons.Rounded.DeleteOutline, "删除", { confirmDelete = true }, page)
                },
            )
            EditorToolbar(
                backdrop = page,
                importing = vm.importing,
                readOnly = vm.readOnly,
                secret = vm.secret,
                onToggleSecret = { vm.secret = !vm.secret },
                onPick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onDone = {
                    focusManager.clearFocus()
                    onBack()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomBase + 10.dp),
            )
        },
    ) {
        // The writing sheet stays put and the text scrolls inside it. A sheet that scrolled
        // with the text would be one enormous piece of glass, re-rendered offscreen on
        // every frame of every scroll.
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    top = statusTop + TopBarHeight + 6.dp,
                    start = 10.dp,
                    end = 10.dp,
                    bottom = bottomBase + ToolbarHeight + 20.dp,
                )
                .liquidGlass(LocalWallpaperBackdrop.current, palette.card, GlassShape.Rounded(28.dp)),
        ) {
            if (vm.loaded && vm.lockedForUser) {
                Column(Modifier.align(Alignment.Center).verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Lock, null, tint = palette.accentContent, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("${ai}的小秘密", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Text(vm.publicHint.ifBlank { "有些话，暂时想留给自己" }, color = palette.contentSecondary)
                    if (vm.sharedExcerpt.isNotBlank()) {
                        Spacer(Modifier.height(20.dp))
                        Text("TA 愿意分享的部分", color = palette.accentContent, fontSize = 14.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(vm.sharedExcerpt, color = palette.content, fontSize = 17.sp, lineHeight = 27.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("其余内容仍是小秘密", color = palette.contentSecondary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(24.dp))
                    GlassButton(onClick = vm::askToSee, backdrop = LocalWallpaperBackdrop.current, enabled = !vm.requesting) {
                        Text(if (vm.requesting) "正在询问…" else "问问 TA，能不能看")
                    }
                    vm.requestError?.let { Text(it, color = palette.error, modifier = Modifier.padding(top = 12.dp)) }
                }
            } else if (vm.loaded) EditorContent(vm, onOpenImage) { c.images.file(it) }
        }
    }

    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = vm.day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        vm.day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    pickingDate = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("取消") } },
        ) { DatePicker(state) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这篇日记？") },
            text = { Text("里面的文字和图片会一起删掉，删了找不回来。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(onBack)
                }) { Text("删除", color = LocalGlassPalette.current.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun EditorContent(vm: DiaryEditorViewModel, onOpenImage: (String) -> Unit, file: (String) -> File) {
    val palette = LocalGlassPalette.current
    val scroll = rememberScrollState()
    val bodyStyle = TextStyle(
        color = palette.content,
        fontSize = 17.sp,
        lineHeight = 29.sp,
        fontFamily = FontFamily.Serif,
    )
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .verticalScroll(scroll)
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        BasicTextField(
            value = vm.title,
            onValueChange = { vm.title = it },
            readOnly = vm.readOnly,
            textStyle = TextStyle(color = palette.content, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp),
            cursorBrush = SolidColor(palette.accentContent),
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box {
                    if (vm.title.text.isEmpty() && !vm.readOnly) {
                        Text("标题（可以不写）", color = palette.contentSecondary.copy(alpha = 0.45f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.height(12.dp))

        vm.blocks.forEachIndexed { index, block ->
            key(block.key) {
                when (block) {
                    is EditorBlock.Text -> {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(vm.focusRequest) {
                            if (vm.focusRequest == block.key) {
                                focus.requestFocus()
                                vm.focusRequest = null
                            }
                        }
                        BasicTextField(
                            value = block.value,
                            onValueChange = { block.value = it },
                            readOnly = vm.readOnly,
                            textStyle = bodyStyle,
                            cursorBrush = SolidColor(palette.accentContent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                .onFocusChanged { if (it.isFocused) vm.focusedIndex = index },
                            decorationBox = { inner ->
                                Box {
                                    if (block.value.text.isEmpty() && vm.blocks.size == 1) {
                                        Text("写点什么……", style = bodyStyle.copy(color = palette.contentSecondary.copy(alpha = 0.45f)))
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                    is EditorBlock.Image -> ImageBlock(
                        file = file(block.image.file),
                        ratio = block.image.width.toFloat() / block.image.height.coerceAtLeast(1),
                        onOpen = { onOpenImage(block.image.file) },
                        onRemove = if (vm.readOnly) null else ({ vm.removeImage(block.key) }),
                    )
                }
            }
        }

        // Tapping the empty space below the text continues writing at the end.
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clickable(enabled = !vm.readOnly, interactionSource = null, indication = null) {
                    vm.focusRequest = vm.blocks.lastOrNull { it is EditorBlock.Text }?.key
                },
        )
    }
}

@Composable
private fun ImageBlock(file: File, ratio: Float, onOpen: () -> Unit, onRemove: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        AsyncImage(
            model = file,
            contentDescription = "图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                // Very tall screenshots would fill several screens; they are cropped here
                // and shown whole when opened.
                .aspectRatio(ratio.coerceIn(0.6f, 2.2f))
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onOpen),
        )
        if (onRemove != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(30.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "移除图片", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun EditorToolbar(
    backdrop: Backdrop,
    importing: Boolean,
    readOnly: Boolean,
    secret: Boolean,
    onToggleSecret: () -> Unit,
    onPick: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    Row(modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!readOnly) {
            GlassButton(
                onClick = onPick,
                backdrop = backdrop,
                style = palette.input,
                enabled = !importing,
                modifier = Modifier.height(ToolbarHeight),
                contentPadding = PaddingValues(horizontal = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, tint = palette.content, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (importing) "正在放进来…" else "插图", color = palette.content, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.width(10.dp))
            // Locked or not, said in words as well as by the icon: it decides what the model can read.
            GlassButton(
                onClick = onToggleSecret,
                backdrop = backdrop,
                style = if (secret) palette.accentSurface else palette.input,
                contentColor = if (secret) Color.White else palette.content,
                modifier = Modifier
                    .height(ToolbarHeight)
                    .semantics { stateDescription = if (secret) "锁着，看不到" else "没锁" },
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                val tint = if (secret) Color.White else palette.content
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (secret) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("小秘密", color = tint, fontSize = 15.sp)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        GlassButton(
            onClick = onDone,
            backdrop = backdrop,
            style = palette.accentSurface,
            contentColor = Color.White,
            modifier = Modifier.height(ToolbarHeight),
            contentPadding = PaddingValues(horizontal = 24.dp),
        ) {
            Text("完成", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
