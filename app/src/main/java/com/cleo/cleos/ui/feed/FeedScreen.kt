package com.cleo.cleos.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.FeedPostEntity
import com.cleo.cleos.data.db.isTopic
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.text.style.TextOverflow

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun FeedScreen(onBack: () -> Unit, onOpenImage: (String) -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val posts by c.feed.posts.collectAsStateWithLifecycle(emptyList())
    val companions by c.companions.all.collectAsStateWithLifecycle(emptyList())
    val current by c.companions.current.collectAsStateWithLifecycle(null)
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var onlyCurrent by rememberSaveable { mutableStateOf(false) }
    var topics by rememberSaveable { mutableStateOf(false) }
    var composing by rememberSaveable { mutableStateOf(false) }
    var commenting by rememberSaveable { mutableStateOf<Long?>(null) }
    var replyTo by rememberSaveable { mutableStateOf<String?>(null) }
    var inviting by rememberSaveable { mutableStateOf<Long?>(null) }
    val notices = remember { SnackbarHostState() }
    var deleting by remember { mutableStateOf<FeedPostEntity?>(null) }
    var forwarding by remember { mutableStateOf<FeedPostEntity?>(null) }
    var recipient by rememberSaveable { mutableStateOf<Long?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var photoDraft by rememberSaveable { mutableStateOf("") }
    val photos = remember(photoDraft) { MessageImages.decode(photoDraft) }
    fun discardPhotos() {
        val files = MessageImages.decode(photoDraft).map { it.file }
        photoDraft = ""
        c.appScope.launch { c.images.delete(files) }
    }
    var busy by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var browsing by rememberSaveable { mutableStateOf(false) }
    var chosenTa by rememberSaveable { mutableStateOf<Long?>(null) }
    var withNews by rememberSaveable { mutableStateOf(false) }
    var interests by rememberSaveable { mutableStateOf("") }
    var rss by rememberSaveable { mutableStateOf("") }
    var editingBio by rememberSaveable { mutableStateOf(false) }
    var bio by rememberSaveable { mutableStateOf("") }
    var options by rememberSaveable { mutableStateOf(false) }
    // The cover opened up in place (tap it): taller, with 换封面; the feed waits below. Tap again, or back, to close it.
    var coverOpen by rememberSaveable { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val closedCover = top + 168.dp
    val openCover = (screenHeight - 150.dp).coerceAtLeast(closedCover + 120.dp)
    val coverHeight by animateDpAsState(if (coverOpen) openCover else closedCover, tween(320), label = "cover")
    BackHandler(enabled = coverOpen) { coverOpen = false }
    LaunchedEffect(coverOpen) { if (coverOpen) listState.animateScrollToItem(0) }
    val frostTop = if (listState.firstVisibleItemIndex == 0) (coverHeight - with(density) { listState.firstVisibleItemScrollOffset.toDp() }).coerceAtLeast(0.dp) else 0.dp
    fun act(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        running = scope.launch {
            try { action(); problem = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { problem = e.message ?: "操作没完成，请重试" }
            finally { busy = false }
        }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) act {
            val image = c.images.import(picked, prefix = "feed_cover_")
            c.settings.update { it.copy(feedCover = image.file) }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(FeedPostRules.MAX_PHOTOS)) { picked ->
        if (picked.isNotEmpty()) act {
            val existing = MessageImages.decode(photoDraft)
            val made = mutableListOf<MessageImage>()
            try {
                for (photo in picked.take(FeedPostRules.MAX_PHOTOS - existing.size)) {
                    val imported = c.images.import(photo, prefix = "feed_draft_")
                    made += MessageImage(imported.file, imported.width, imported.height)
                }
                photoDraft = MessageImages.encode(existing + made).orEmpty()
            } catch (e: Exception) { c.images.delete(made.map { it.file }); throw e }
        }
    }
    GlassPage(overlay = { page ->
        if (!coverOpen) GlassTopBar(title = if (topics) "话题" else "朋友圈", backdrop = page,
            leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) },
            trailing = {
                Box {
                    GlassIconButton(Icons.Rounded.MoreHoriz, "动态选项", { options = !options }, page)
                    FeedOptionsMenu(options, { options = false }, page, busy, !busy && current != null,
                        onBrowse = { chosenTa = current?.id; withNews = topics; interests = settings.feedInterests; rss = settings.feedRssUrl; browsing = true },
                        onEditBio = { bio = settings.feedBio; editingBio = true },
                        onCover = { coverOpen = true })
                }
                GlassIconButton(Icons.Rounded.Edit, "发动态", { draft = ""; composing = true }, page, enabled = !busy)
            })
    }) {
        Box(Modifier.fillMaxSize()) {
        GlassSurface(Modifier.fillMaxSize().padding(top = frostTop), shape = GlassShape.Rounded(0.dp)) {}
        LazyColumn(Modifier.fillMaxSize(), state = listState,
            contentPadding = PaddingValues(bottom = bottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(coverHeight)
                    .background(Brush.linearGradient(listOf(palette.accent.copy(alpha = .35f), palette.content.copy(alpha = .08f))))
                    .clickable(enabled = !busy) { coverOpen = !coverOpen }) {
                    (settings.feedCover ?: settings.wallpaper)?.let { cover ->
                        AsyncImage(c.images.file(cover), "主页封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    // Only while it is open: a soft dark foot, so the white 换封面 reads on any picture.
                    AnimatedVisibility(coverOpen, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(240)), exit = fadeOut(tween(160))) {
                        Box(Modifier.fillMaxWidth().height(180.dp)
                            .background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black.copy(alpha = .42f)))))
                    }
                    AnimatedVisibility(coverOpen, Modifier.align(Alignment.BottomEnd), enter = fadeIn(tween(240)), exit = fadeOut(tween(160))) {
                        Column(Modifier.padding(end = 22.dp, bottom = 22.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !busy) { coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Image, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(30.dp))
                            Text("换封面", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
            item {
                val ownName = settings.userName.ifBlank { "我" }
                // The avatar hangs over the cover's edge; the block is pulled up by as much, so no gap is left under it.
                val pull = 28.dp
                Column(Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val up = pull.roundToPx()
                    layout(placeable.width, (placeable.height - up).coerceAtLeast(0)) { placeable.place(0, -up) }
                }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Avatar(settings.userAvatar, avatarLetter(ownName, "我"), 60.dp)
                        Text(ownName, color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    if (settings.feedBio.isNotBlank()) Text(settings.feedBio, color = palette.contentSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp).clickable { bio = settings.feedBio; editingBio = true })
                }
            }
            item {
                Column {
                    FeedTabs(topics, enabled = !busy) { topics = it }
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FeedChip("全部", !onlyCurrent) { onlyCurrent = false }
                        FeedChip(current?.name?.ifBlank { "当前 TA" } ?: "当前 TA", onlyCurrent, enabled = current != null) { onlyCurrent = true }
                    }
                }
            }
            problem?.let { item { Text(it, color = palette.content, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) } }
            val visible = posts.filter { it.isTopic == topics && (!onlyCurrent || it.authorId == 0L || it.authorId == current?.id) }
            if (visible.isEmpty()) item {
                Box(Modifier.fillMaxWidth().padding(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.DynamicFeed, null, tint = palette.accentContent, modifier = Modifier.size(30.dp))
                        Text(if (topics) "有什么新发现？" else "今天想说点什么？", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (topics) "分享资讯与观点，留下一起讨论的话题。" else "随手记一句，让彼此的日常在这里碰面。", color = palette.contentSecondary, fontSize = 14.sp)
                        TextButton({ draft = ""; composing = true }) { Text("发第一条动态", color = palette.accentContent) }
                    }
                }
            }
            items(visible, key = { it.id }) { post ->
                val author = companions.firstOrNull { it.id == post.authorId }
                val name = if (post.authorId == 0L) settings.userName.ifBlank { "我" } else author?.name?.ifBlank { "TA" } ?: "TA"
                val comments = remember(post.comments) { FeedComments.decode(post.comments) }
                var expanded by remember(post.id) { mutableStateOf(false) }
                var menu by remember(post.id) { mutableStateOf(false) }
                val shown = if (expanded) comments else comments.takeLast(3)
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // The thread: the line runs from the post's avatar down to the first reply's.
                        Column(Modifier.fillMaxHeight().width(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(if (post.authorId == 0L) settings.userAvatar else author?.avatar,
                                if (post.authorId == 0L) avatarLetter(name, "我") else author?.avatarEmoji ?: avatarLetter(name, "TA"), 36.dp)
                            if (shown.isNotEmpty()) Box(Modifier.padding(top = 6.dp).width(1.5.dp).weight(1f)
                                .clip(RoundedCornerShape(1.dp)).background(palette.content.copy(alpha = .16f)))
                        }
                        Column(Modifier.weight(1f).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(name, color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Text(SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(post.createdAt)), color = palette.contentSecondary, fontSize = 12.sp, maxLines = 1)
                                Spacer(Modifier.weight(1f))
                                Box {
                                    IconButton({ menu = true }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Rounded.MoreHoriz, "更多", tint = palette.contentSecondary, modifier = Modifier.size(18.dp))
                                    }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false },
                                        modifier = Modifier.width(180.dp),
                                        containerColor = Color.Transparent, tonalElevation = 0.dp, shadowElevation = 0.dp,
                                        shape = RoundedCornerShape(20.dp)) {
                                        Box(Modifier.padding(12.dp)) {
                                            GlassSurface(Modifier.fillMaxWidth(), style = palette.card,
                                                shape = GlassShape.Rounded(16.dp), contentPadding = PaddingValues(6.dp)) {
                                                Column {
                                                    FeedOptionRow(Icons.Rounded.MarkChatRead, "邀请 TA 看看", !busy && companions.isNotEmpty()) {
                                                        menu = false; problem = null; inviting = post.id
                                                    }
                                                    FeedOptionRow(Icons.Rounded.DeleteOutline, "删除", !busy) {
                                                        menu = false; deleting = post
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (post.content.isNotBlank()) Text(post.content, color = palette.content, fontSize = 15.sp, lineHeight = 22.sp)
                            val postPhotos = remember(post.images) { MessageImages.decode(post.images) }
                            if (postPhotos.isNotEmpty()) FeedPhotos(postPhotos, onOpenImage, Modifier.padding(vertical = 5.dp))
                            post.sourceUrl?.let { link ->
                                Text(post.sourceTitle ?: "查看来源", color = palette.accentContent, fontSize = 12.sp,
                                    modifier = Modifier.clickable { runCatching { uri.openUri(link) }.onFailure { problem = "无法打开来源" } })
                            }
                            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                FeedAction(if (post.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (post.liked) "取消喜欢" else "喜欢",
                                    if (post.liked) palette.accentContent else palette.contentSecondary, enabled = !busy) { act { c.feed.like(post.id) } }
                                FeedAction(Icons.Rounded.ChatBubbleOutline, "回复", palette.contentSecondary, count = comments.size.takeIf { it > 0 }, enabled = !busy) {
                                    draft = ""; replyTo = null; commenting = post.id
                                }
                                // Everyone shares this feed. Choose the recipient only when forwarding.
                                Row(
                                    Modifier.alpha(if (busy || companions.isEmpty()) .5f else 1f)
                                        .clip(RoundedCornerShape(50))
                                        .border(0.5.dp, palette.content.copy(alpha = .22f), RoundedCornerShape(50))
                                        .clickable(enabled = !busy && companions.isNotEmpty()) { recipient = null; forwarding = post }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(Icons.Rounded.Send, null, tint = palette.contentSecondary, modifier = Modifier.size(14.dp))
                                    Text("转发到聊天", color = palette.content.copy(alpha = .75f), fontSize = 12.sp, maxLines = 1)
                                }
                            }
                            val likes = buildList {
                                if (post.liked) add(settings.userName.ifBlank { "我" })
                                FeedInteractions.decode(post.interactions).filter { it.liked }.forEach { state ->
                                    companions.firstOrNull { it.id == state.taId }?.let { add(it.name.ifBlank { "TA" }) }
                                }
                            }
                            // Reserve one line so toggling the user's like never moves the replies.
                            Row(Modifier.heightIn(min = 20.dp).padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(Icons.Rounded.Favorite, null, tint = palette.accentContent, modifier = Modifier.size(12.dp).alpha(if (likes.isEmpty()) 0f else 1f))
                                Text(if (likes.isEmpty()) " " else likes.joinToString("、") + " 觉得很赞", color = palette.accentContent,
                                    fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    shown.forEach { comment ->
                        val who = if (comment.authorId == 0L) settings.userName.ifBlank { "我" }
                            else companions.firstOrNull { it.id == comment.authorId }?.name ?: "TA"
                        val speaker = companions.firstOrNull { it.id == comment.authorId }
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.width(36.dp), contentAlignment = Alignment.TopCenter) {
                                Avatar(if (comment.authorId == 0L) settings.userAvatar else speaker?.avatar,
                                    if (comment.authorId == 0L) avatarLetter(who, "我") else speaker?.avatarEmoji ?: avatarLetter(who, "TA"), 22.dp)
                            }
                            Text(buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(who) }
                                comment.replyTo?.let { id -> comments.firstOrNull { it.id == id }?.let { target ->
                                    append(" 回复 ")
                                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                        append(if (target.authorId == 0L) settings.userName.ifBlank { "我" }
                                            else companions.firstOrNull { it.id == target.authorId }?.name?.ifBlank { "TA" } ?: "TA")
                                    }
                                } }
                                append("  ")
                                append(comment.content)
                            }, color = palette.content.copy(alpha = .88f), fontSize = 14.sp, lineHeight = 21.sp,
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable(enabled = !busy) {
                                    draft = ""; replyTo = comment.id; commenting = post.id
                                })
                        }
                    }
                    if (comments.size > 3) Text(if (expanded) "收起回复" else "查看全部 ${comments.size} 条回复", color = palette.accentContent, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 46.dp, bottom = 8.dp).clickable { expanded = !expanded })
                    HorizontalDivider(Modifier.padding(top = 4.dp), color = palette.content.copy(alpha = .1f))
                }
            }
        }
        SnackbarHost(notices, Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 8.dp))
        }
    }
    inviting?.let { id -> posts.firstOrNull { it.id == id }?.let { post ->
        FeedInviteDialog(post, companions, current?.id, settings.userName, busy, problem,
            onCancel = { running?.cancel(); inviting = null }, onInvite = { taId, target ->
                act {
                    val notice = c.feedAi.interact(id, taId, target)
                    inviting = null
                    scope.launch { notices.showSnackbar(notice) }
                }
            })
    } }
    if (editingBio) Dialog(onDismissRequest = { if (!busy) editingBio = false }) {
        GlassSurface(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("主页简介", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(bio, { bio = it.take(120) }, modifier = Modifier.fillMaxWidth(), enabled = !busy, placeholder = { Text("写一点关于自己的话…") })
                Row(Modifier.align(Alignment.End)) {
                    TextButton({ editingBio = false }, enabled = !busy) { Text("取消") }
                    TextButton({ val text = bio.trim(); act { c.settings.update { it.copy(feedBio = text) }; editingBio = false } }, enabled = !busy) { Text("保存") }
                }
            }
        }
    }
    forwarding?.let { post -> Dialog(onDismissRequest = { if (!busy) forwarding = null }) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("转发给谁？", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("带到聊天后，可以附上一句话再发送。", color = palette.contentSecondary, fontSize = 13.sp)
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    companions.forEach { ta ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = !busy) { recipient = ta.id }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Avatar(ta.avatar, ta.avatarEmoji ?: avatarLetter(ta.name, "TA"), 36.dp)
                            Text(ta.name.ifBlank { "TA" }, color = palette.content, modifier = Modifier.weight(1f))
                            RadioButton(recipient == ta.id, null, enabled = !busy)
                        }
                    }
                }
                Row(Modifier.align(Alignment.End)) {
                    TextButton({ forwarding = null }, enabled = !busy) { Text("取消", color = palette.content) }
                    TextButton({
                        val id = recipient ?: return@TextButton
                        act {
                            val ta = c.companions.get(id) ?: error("这位 TA 已经不在了")
                            val remembered = if (current?.id == id) c.settings.currentConversation.first() else null
                            val conversation = c.chat.resolveConversation(remembered, id)
                            val authorName = if (post.authorId == 0L) settings.userName.ifBlank { "我" }
                                else companions.firstOrNull { it.id == post.authorId }?.name?.ifBlank { "TA" } ?: "TA"
                            if (current?.id != ta.id) c.companions.select(ta.id)
                            c.settings.setCurrentConversation(conversation)
                            c.chat.stageFeedShare(conversation, FeedShares.of(post, authorName, ta.id))
                            forwarding = null
                            c.opening.value = com.cleo.cleos.Opening.Chat(conversation)
                        }
                    }, enabled = !busy && companions.any { it.id == recipient }) { Text("带到聊天", color = palette.accentContent) }
                }
            }
        }
    } }
    if (browsing) Dialog(onDismissRequest = { if (!busy) browsing = false }) {
        GlassSurface(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
            Column(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (withNews) "让 TA 看看资讯" else "让 TA 说点日常", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("选一位 TA，分享一条自己的想法。每次会使用 TA 的模型。", color = palette.content.copy(alpha = .7f), fontSize = 13.sp)
                Column {
                    companions.forEach { ta ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { chosenTa = ta.id }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(chosenTa == ta.id, { chosenTa = ta.id }, enabled = !busy)
                            Text(ta.name.ifBlank { "TA" }, color = palette.content)
                        }
                    }
                }
                OutlinedTextField(interests, { interests = it.take(300) }, label = { Text("兴趣提示（可留空）") },
                    placeholder = { Text("例如科技、电影、猫；留空跟随角色设定") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                if (withNews) {
                    Text("默认看 NASA 科学与太空资讯；也可以使用自己的 RSS 订阅。", color = palette.content.copy(alpha = .7f), fontSize = 13.sp)
                    OutlinedTextField(rss, { rss = it.take(2000) }, label = { Text("RSS 地址（可留空）") }, placeholder = { Text("https://…") },
                        enabled = !busy, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                }
                problem?.let { Text(it, color = palette.content, fontSize = 12.sp) }
                }
                Row(Modifier.align(Alignment.End)) {
                    TextButton({ running?.cancel(); browsing = false }, enabled = true) { Text(if (busy) "取消生成" else "取消", color = palette.content) }
                    TextButton({
                        val id = chosenTa; val news = withNews; val topic = interests.trim(); val address = rss.trim()
                        if (id != null) act {
                            require(!news || address.isBlank() || address.startsWith("https://")) { "RSS 地址请使用 https://" }
                            c.settings.update { it.copy(feedInterests = topic, feedRssUrl = address) }
                            c.feedAi.browse(id, news)
                            browsing = false
                        }
                    }, enabled = !busy && chosenTa != null) { Text(if (busy) "TA 正在写…" else "去逛逛", color = palette.accentContent) }
                }
            }
        }
    }
    if (composing || commenting != null) Dialog(onDismissRequest = { if (!busy) { discardPhotos(); composing = false; commenting = null } }) {
        GlassSurface(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
            Column(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (composing) { if (topics) "发话题" else "发朋友圈" } else "回复动态", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (!composing && replyTo != null) {
                    val target = posts.firstOrNull { it.id == commenting }?.let { FeedComments.decode(it.comments) }?.firstOrNull { it.id == replyTo }
                    target?.let { comment ->
                        val who = if (comment.authorId == 0L) settings.userName.ifBlank { "我" } else companions.firstOrNull { it.id == comment.authorId }?.name?.ifBlank { "TA" } ?: "TA"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("回复 $who：${comment.content.take(80)}", color = palette.contentSecondary, fontSize = 13.sp, maxLines = 2, modifier = Modifier.weight(1f))
                            IconButton({ replyTo = null }) { Icon(Icons.Rounded.Close, "改为回复动态", tint = palette.contentSecondary) }
                        }
                    }
                }
                OutlinedTextField(draft, { draft = it.take(4000) }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp), enabled = !busy,
                    placeholder = { Text("分享你的想法…") })
                if (composing) {
                    if (photos.isNotEmpty()) FeedPhotos(photos, onOpenImage, compact = true, onRemove = { photo ->
                        photoDraft = MessageImages.encode(photos.filterNot { it.file == photo.file }).orEmpty()
                        c.appScope.launch { c.images.delete(listOf(photo.file)) }
                    })
                    TextButton({ photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !busy && photos.size < FeedPostRules.MAX_PHOTOS) {
                        Icon(Icons.Rounded.AddPhotoAlternate, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("添加照片 · ${photos.size}/9", color = palette.accentContent)
                    }
                }
                problem?.let { Text(it, color = palette.content, fontSize = 12.sp) }
                }
                Row(Modifier.align(Alignment.End)) {
                    TextButton({ discardPhotos(); composing = false; commenting = null }, enabled = !busy) { Text("取消", color = palette.content) }
                    TextButton({
                        val id = commenting; val text = draft
                        act {
                            if (id == null) c.feed.publish(text, topics, photos) else c.feed.comment(id, text, replyTo = replyTo)
                            discardPhotos()
                            composing = false; commenting = null; replyTo = null; draft = ""
                        }
                    }, enabled = !busy && (draft.isNotBlank() || (composing && photos.isNotEmpty()))) { Text(if (busy) "处理中…" else "发布", color = palette.accentContent) }
                }
            }
        }
    }
    deleting?.let { post -> Dialog(onDismissRequest = { if (!busy) deleting = null }) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, shape = GlassShape.Rounded(28.dp), contentPadding = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("删除这条动态？", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("这条动态和下面的回复会一起删除。", color = palette.contentSecondary, fontSize = 14.sp)
                Row(Modifier.align(Alignment.End)) {
                    TextButton({ deleting = null }, enabled = !busy) { Text("取消") }
                    TextButton({ act { c.feed.delete(post.id); deleting = null } }, enabled = !busy) { Text("删除") }
                }
            }
        }
    } }
}
/** 朋友圈 / 话题: two words with a line under the one that is open. */
@Composable
private fun FeedTabs(topics: Boolean, enabled: Boolean, onPick: (Boolean) -> Unit) {
    val palette = LocalGlassPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        listOf(false to "朋友圈", true to "话题").forEach { (isTopics, label) ->
            val on = topics == isTopics
            Column(Modifier.weight(1f).clickable(enabled = enabled) { onPick(isTopics) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, color = if (on) palette.content else palette.contentSecondary, fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(vertical = 12.dp))
                Box(Modifier.fillMaxWidth().height(if (on) 1.5.dp else 0.5.dp)
                    .background(if (on) palette.content else palette.content.copy(alpha = .12f)))
            }
        }
    }
}

/** A filter: soft fill, heavier when it is the one in use. */
@Composable
private fun FeedChip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Text(label, color = if (selected) palette.content else palette.contentSecondary, fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(palette.content.copy(alpha = if (selected) .14f else .06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 5.dp))
}

/** An action under a post: an icon, and a number when there is one. No words. */
@Composable
private fun FeedAction(icon: ImageVector, description: String, tint: Color, count: Int? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(19.dp))
        if (count != null) Text(count.toString(), color = tint, fontSize = 13.sp)
    }
}
