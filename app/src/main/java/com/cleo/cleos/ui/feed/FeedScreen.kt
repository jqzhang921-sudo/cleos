package com.cleo.cleos.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.text.style.TextOverflow

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun FeedScreen(onBack: () -> Unit) {
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
    var deleting by remember { mutableStateOf<FeedPostEntity?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
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
    var previewCover by rememberSaveable { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
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
    GlassPage(overlay = { page ->
        GlassTopBar(title = if (topics) "话题" else "朋友圈", backdrop = page,
            leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) },
            trailing = { GlassIconButton(Icons.Rounded.MoreHoriz, "动态选项", { options = true }, page); GlassIconButton(Icons.Rounded.Edit, "发动态", { draft = ""; composing = true }, page, enabled = !busy) })
    }) {
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val ownName = settings.userName.ifBlank { "我" }
                Column {
                    Box(Modifier.fillMaxWidth().height(top + 300.dp)
                        .background(Brush.linearGradient(listOf(palette.accent.copy(alpha = .35f), palette.content.copy(alpha = .08f)))).clickable(enabled = !busy) { previewCover = true }) {
                        (settings.feedCover ?: settings.wallpaper)?.let { cover ->
                            AsyncImage(c.images.file(cover), "主页封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).offset(y = (-28).dp), verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.End) {
                        Text(ownName, color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 14.dp, bottom = 8.dp))
                        Avatar(settings.userAvatar, avatarLetter(ownName, "我"), 64.dp)
                    }
                    if (settings.feedBio.isNotBlank()) Text(settings.feedBio, color = palette.content.copy(alpha = .7f), fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 24.dp).clickable { bio = settings.feedBio; editingBio = true })
                    if (busy) Text("TA 正在写…", color = palette.accent, modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
            problem?.let { item { Text(it, color = palette.content, fontSize = 13.sp) } }
            val visible = posts.filter { it.isTopic == topics && (!onlyCurrent || it.authorId == 0L || it.authorId == current?.id) }
            if (visible.isEmpty()) item {
                GlassSurface(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.DynamicFeed, null, tint = palette.accent, modifier = Modifier.size(30.dp))
                        Text(if (topics) "有什么新发现？" else "今天想说点什么？", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (topics) "分享资讯与观点，留下一起讨论的话题。" else "随手记一句，让彼此的日常在这里碰面。", color = palette.content.copy(alpha = .7f), fontSize = 14.sp)
                        TextButton({ draft = ""; composing = true }) { Text("发第一条动态", color = palette.accent) }
                    }
                }
            }
            items(visible, key = { it.id }) { post ->
                val author = companions.firstOrNull { it.id == post.authorId }
                val name = if (post.authorId == 0L) settings.userName.ifBlank { "我" } else author?.name?.ifBlank { "TA" } ?: "TA"
                val comments = remember(post.comments) { FeedComments.decode(post.comments) }
                var expanded by remember(post.id) { mutableStateOf(false) }
                GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentPadding = PaddingValues(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(if (post.authorId == 0L) settings.userAvatar else author?.avatar,
                        if (post.authorId == 0L) avatarLetter(name, "我") else author?.avatarEmoji ?: avatarLetter(name, "TA"), 36.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (topics) 12.dp else 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(name, color = if (topics) palette.content else palette.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(post.createdAt)), color = palette.content.copy(alpha = .55f), fontSize = 11.sp)
                            }
                            IconButton({ deleting = post }, enabled = !busy, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.DeleteOutline, "删除动态", tint = palette.content.copy(alpha = .5f), modifier = Modifier.size(18.dp))
                            }
                        }
                        Text(post.content, color = palette.content, fontSize = 16.sp, lineHeight = 24.sp)
                        post.sourceUrl?.let { link ->
                            Text(post.sourceTitle ?: "查看来源", color = palette.accent, fontSize = 12.sp,
                                modifier = Modifier.clickable { runCatching { uri.openUri(link) }.onFailure { problem = "无法打开来源" } })
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton({ act { c.feed.like(post.id) } }, enabled = !busy) {
                                Icon(if (post.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = palette.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(5.dp)); Text(if (post.liked) "已喜欢" else "喜欢", color = palette.content, fontSize = 13.sp)
                            }
                            TextButton({ draft = ""; commenting = post.id }, enabled = !busy) {
                                Icon(Icons.Rounded.ChatBubbleOutline, null, tint = palette.content, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(5.dp)); Text(if (comments.isEmpty()) "回复" else "回复 ${comments.size}", color = palette.content, fontSize = 13.sp)
                            }
                            TextButton({ current?.let { ta -> act { c.feedAi.reply(post.id, ta.id) } } }, enabled = !busy && current != null) {
                                Text("请${current?.name?.ifBlank { "TA" } ?: "TA"}回复", color = palette.accent, fontSize = 13.sp)
                            }
                        }
                        if (comments.isNotEmpty()) {
                            HorizontalDivider(color = palette.content.copy(alpha = .1f))
                            (if (expanded) comments else comments.takeLast(3)).forEach { comment ->
                                val who = if (comment.authorId == 0L) settings.userName.ifBlank { "我" }
                                    else companions.firstOrNull { it.id == comment.authorId }?.name ?: "TA"
                                Text("$who：${comment.content}", color = palette.content, fontSize = 14.sp, lineHeight = 21.sp)
                            }
                            if (comments.size > 3) TextButton({ expanded = !expanded }) { Text(if (expanded) "收起回复" else "查看全部回复", color = palette.accent) }
                        }
                    }
                    }
                    HorizontalDivider(Modifier.padding(top = 12.dp), color = palette.content.copy(alpha = .1f))
                }
                }
            }
        }
        }
    }
    if (options) Dialog(onDismissRequest = { options = false }) {
        GlassSurface(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("动态选项", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeedChoice("朋友圈", !topics, !busy) { topics = false; options = false }
                    FeedChoice("话题", topics, !busy) { topics = true; options = false }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeedChoice("全部", !onlyCurrent) { onlyCurrent = false; options = false }
                    FeedChoice(current?.name?.ifBlank { "当前 TA" } ?: "当前 TA", onlyCurrent) { onlyCurrent = true; options = false }
                }
                TextButton({ options = false; chosenTa = current?.id; withNews = topics; interests = settings.feedInterests; rss = settings.feedRssUrl; browsing = true }, enabled = !busy && current != null) {
                    Icon(Icons.Rounded.Explore, null, tint = palette.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "TA 正在写…" else "让 TA 逛逛", color = palette.accent)
                }
                TextButton({ options = false; bio = settings.feedBio; editingBio = true }, enabled = !busy) { Text("编辑主页简介") }
                TextButton({ options = false; previewCover = true }, enabled = !busy) { Text("查看与更换封面") }
                TextButton({ options = false }, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
    if (previewCover) Dialog(onDismissRequest = { previewCover = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black)) {
            (settings.feedCover ?: settings.wallpaper)?.let { cover ->
                AsyncImage(c.images.file(cover), "封面预览", Modifier.fillMaxWidth().height(520.dp), contentScale = ContentScale.Fit)
            }
            IconButton({ previewCover = false }, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = androidx.compose.ui.graphics.Color.White)
            }
            TextButton({ previewCover = false; coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                Icon(Icons.Rounded.Image, null, tint = androidx.compose.ui.graphics.Color.White)
                Spacer(Modifier.width(8.dp)); Text("换封面", color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
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
                    }, enabled = !busy && chosenTa != null) { Text(if (busy) "TA 正在写…" else "去逛逛", color = palette.accent) }
                }
            }
        }
    }
    if (composing || commenting != null) Dialog(onDismissRequest = { if (!busy) { composing = false; commenting = null } }) {
        GlassSurface(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (composing) { if (topics) "发话题" else "发朋友圈" } else "回复动态", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(draft, { draft = it.take(4000) }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp), enabled = !busy,
                    placeholder = { Text("分享你的想法…") })
                problem?.let { Text(it, color = palette.content, fontSize = 12.sp) }
                Row(Modifier.align(Alignment.End)) {
                    TextButton({ composing = false; commenting = null }, enabled = !busy) { Text("取消", color = palette.content) }
                    TextButton({
                        val id = commenting; val text = draft
                        act {
                            if (id == null) c.feed.publish(text, topics) else c.feed.comment(id, text)
                            composing = false; commenting = null; draft = ""
                        }
                    }, enabled = !busy && draft.isNotBlank()) { Text(if (busy) "保存中…" else "发布", color = palette.accent) }
                }
            }
        }
    }
    deleting?.let { post -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条动态？") },
        text = { Text("这条动态和下面的回复会一起删除。") },
        confirmButton = { TextButton({ act { c.feed.delete(post.id); deleting = null } }, enabled = !busy) { Text("删除") } },
        dismissButton = { TextButton({ deleting = null }) { Text("取消") } }) }
}

@Composable
private fun FeedChoice(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    GlassButton(onClick, LocalWallpaperBackdrop.current, style = palette.chrome,
        enabled = enabled, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (selected) Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp), tint = palette.accent)
            Text(label, color = if (selected) palette.accent else palette.content, fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
