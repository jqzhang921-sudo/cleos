package com.cleo.cleos.ui.world

import com.cleo.cleos.ui.common.systemMotionEnabled
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.WorldShelfSummary
import com.cleo.cleos.data.db.WorldArticleEntity
import com.cleo.cleos.data.db.WorldNoteEntity
import com.cleo.cleos.data.db.WorldPictureLookEntity
import com.cleo.cleos.data.db.WorldPictureEntity
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.LocalWallpaperBackdrop
import com.cleo.cleos.ui.LocalPageShown
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.avatarLetter
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf

internal enum class WorldSpace(
    val title: String, val short: String, val icon: ImageVector,
    val description: String, val empty: String, val detail: String,
) {
    Study("书房", "书房", Icons.Rounded.AutoStories,
        "书、杂志与文章，留一点时间慢慢读。", "书架还空着",
        "书房正在准备中。以后，你放进来的书、杂志和文章会在这里留下，TA 可以挑一本读，也可以接着上次的地方。"),
    Outside("窗外", "窗外", Icons.Rounded.Public,
        "看看新闻、地方与自然，跟着兴趣走一走。", "还没有新的见闻",
        "窗外正在准备中。以后，TA 可以看看新闻，了解一个地方，把还想知道的问题留到下次继续。"),
    Notebook("随手本", "随手本", Icons.Rounded.EditNote,
        "感想、疑问和几句没写完的话。", "本子里还没有笔记",
        "随手本正在准备中。以后，TA 可以在这里摘录、吐槽、写草稿，也可以只留一句没想完的话。"),
    Album("共享相册", "相册", Icons.Rounded.PhotoLibrary,
        "放一些你愿意给 TA 看的照片。", "相册还空着",
        "为这位 TA 单独选择照片，添加后留在本机；手动确认后再让 TA 看这一张。"),
}

/** Per-TA books, public feed summaries, notebook and separately selected private album. */
@Composable
fun WorldTab(bottomInset: Dp) {
    val c = appContainer()
    val navigation: WorldNavigationModel = viewModel()
    val palette = LocalGlassPalette.current
    val companions by remember { c.companions.all }.collectAsStateWithLifecycle(emptyList())
    val current by remember { c.companions.current }.collectAsStateWithLifecycle(null)
    var selectedId by navigation.selectedId
    val ta = companions.firstOrNull { it.id == selectedId } ?: current ?: companions.firstOrNull()
    LaunchedEffect(ta?.id) { if (selectedId == 0L && ta != null) selectedId = ta.id }
    val articleRows by key(ta?.id) { remember { ta?.let { c.db.world().observeArticles(it.id) } ?: flowOf(emptyList()) }.collectAsStateWithLifecycle<List<WorldArticleEntity>?>(null) }
    val noteRows by key(ta?.id) { remember { ta?.let { c.db.world().observeNotes(it.id) } ?: flowOf(emptyList()) }.collectAsStateWithLifecycle<List<WorldNoteEntity>?>(null) }
    val lookRows by key(ta?.id) { remember { ta?.let { c.db.world().observePictureLooks(it.id) } ?: flowOf(emptyList()) }.collectAsStateWithLifecycle<List<WorldPictureLookEntity>?>(null) }
    val pictureRows by key(ta?.id) { remember { ta?.let { c.db.world().observePictures(it.id) } ?: flowOf(emptyList()) }.collectAsStateWithLifecycle<List<WorldPictureEntity>?>(null) }
    val articles = articleRows.orEmpty(); val notes = noteRows.orEmpty(); val looks = lookRows.orEmpty(); val pictures = pictureRows.orEmpty()
    val writingRows by key(ta?.id) { remember { ta?.let { c.db.world().observeWritings(it.id) } ?: flowOf(emptyList()) }.collectAsStateWithLifecycle<List<com.cleo.cleos.data.db.WorldWritingEntity>?>(null) }
    val writings = writingRows.orEmpty()
    val reading by key(ta?.id) { remember { ta?.let { c.db.world().observeState(it.id) } ?: flowOf(null) }.collectAsStateWithLifecycle(null) }
    val busy by c.world.busy.collectAsStateWithLifecycle()
    val name = ta?.name?.trim()?.ifEmpty { "TA" } ?: "TA"
    var space by navigation.space
    var adding by navigation.adding
    var onlineOpen by navigation.onlineOpen
    var onlineQuery by navigation.onlineQuery
    var onlineFullText by navigation.onlineFullText
    var studyTab by navigation.studyTab
    var studyCategory by navigation.studyCategory
    var studyOptions by navigation.studyOptions
    var notebookQuery by navigation.notebookQuery
    var notebookFilter by navigation.notebookFilter
    var albumOptions by navigation.albumOptions
    var albumExpanded by navigation.albumExpanded
    var articleId by navigation.articleId
    var journalId by navigation.journalId
    var journeyOpen by navigation.journeyOpen
    var sourceOffset by navigation.sourceOffset
    var lookupOpen by navigation.lookupOpen
    var lookupSeed by navigation.lookupSeed
    var lookupQuery by navigation.lookupQuery
    var pasteOpen by navigation.pasteOpen
    var depthRequested by navigation.depthRequested
    val systemMotion = systemMotionEnabled()
    val depth = depthRequested && systemMotion
    val showSubtitle = LocalDensity.current.fontScale <= 1.2f
    val shown = LocalPageShown.current
    val holder = rememberSaveableStateHolder()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // MainScreen retains hidden tabs: a hidden world's back handler must be inert.
    val goBack = { if (pasteOpen) { if (!navigation.pasteSaving.value) { if (navigation.pastePreview.value) navigation.pastePreview.value = false else pasteOpen = false } } else if (articleId != 0L) articleId = 0L else if (lookupOpen) lookupOpen = false else if (journalId != 0L) journalId = 0L else if (journeyOpen) { if (!navigation.backJourney()) journeyOpen = false } else if (onlineOpen) onlineOpen = false else if (adding) adding = false else if (space == WorldSpace.Notebook && navigation.notebookEntry.value.isNotBlank()) navigation.notebookEntry.value = "" else if (space == WorldSpace.Notebook && navigation.notebookBook.value != 0L) { navigation.notebookBook.value = 0L; notebookQuery = ""; notebookFilter = "all" } else space = null }
    BackHandler(enabled = shown && (space != null || adding || onlineOpen || articleId != 0L || journalId != 0L || journeyOpen || lookupOpen), onBack = goBack)

    GlassPage(overlay = { page ->
        GlassTopBar(
            title = if (pasteOpen) "粘贴正文" else if (articleId != 0L) (if (articles.firstOrNull { it.id == articleId }?.space == "outside") "摘要" else "原文") else if (lookupOpen) "继续查阅" else if (journalId != 0L) "见闻" else if (journeyOpen) "图文漫游" else if (onlineOpen) "在线文库" else if (adding) "添加读物" else space?.title ?: "${name}的世界",
            subtitle = if (!showSubtitle) null else if (space == null && !lookupOpen && articleId == 0L && journalId == 0L && !journeyOpen) "给兴趣留一点空间" else "${name}的世界",
            backdrop = page,
            leading = {
                if (space != null || adding || onlineOpen || articleId != 0L || journalId != 0L || journeyOpen || lookupOpen) GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回世界", goBack, page)
            },
            trailing = {
                if ((space == null || journalId != 0L || journeyOpen) && articleId == 0L && !lookupOpen) GlassIconButton(
                    Icons.Rounded.ViewCarousel,
                    if (!systemMotion) "系统已关闭动效" else if (depthRequested) "关闭立体滚动" else "开启立体滚动",
                    { depthRequested = !depthRequested }, page,
                    modifier = Modifier.semantics {
                        stateDescription = when {
                            !systemMotion -> "系统已关闭动效"
                            depthRequested -> "立体滚动已开启"
                            else -> "立体滚动已关闭"
                        }
                    },
                    enabled = systemMotion,
                    tint = if (depth) palette.accentContent else palette.contentSecondary,
                )
            },
        )
    }) {
        val page = LocalWallpaperBackdrop.current
        val active = space
        val contentTop = statusTop + TopBarHeight
        val openArticle: (Long, Int) -> Unit = navigation::openSource
        val openJournal: (Long) -> Unit = { id -> articleId = 0L; lookupOpen = false; journalId = id }
        val lookup: (String) -> Unit = navigation::openLookup
        if (pasteOpen && ta != null) {
            WorldPastedPage(ta, navigation, contentTop, bottomInset, page) { id -> openArticle(id, 0) }
        } else if (articleId != 0L) {
            if (articleRows == null || ta?.id != navigation.selectedId.value) {
                Box(Modifier.fillMaxSize().padding(worldPadding(contentTop, bottomInset))) {
                    Text("正在打开本地原文…", color = palette.contentSecondary, fontSize = 14.sp)
                }
            } else {
            val book = remember(articles, articleId) { com.cleo.cleos.data.WorldBookVolumes.books(articles)
                .firstOrNull { it.grouped && it.parts.any { part -> part.id == articleId } } }
            holder.SaveableStateProvider("article:${ta?.id}:$articleId") { WorldArticlePage(articles.firstOrNull { it.id == articleId }, ta, sourceOffset, contentTop, bottomInset, book, openArticle, openJournal, navigation) { articleId = 0L; journalId = 0L } }
            }
        } else if (lookupOpen) {
            holder.SaveableStateProvider("lookup:${ta?.id}:$lookupSeed") { WorldTopicPage(ta, lookupSeed, lookupQuery, { lookupQuery = it }, articles, reading, ta?.id in busy, contentTop, bottomInset, page, openArticle, { navigation.openPaste(c.world.revision()) }) }
        } else if (journalId != 0L) {
            holder.SaveableStateProvider("journal:${ta?.id}:$journalId") { WorldJournal(ta, articles.firstOrNull { it.id == journalId }, notes, looks, contentTop, bottomInset, page, depth && shown, openArticle, lookup) }
        } else if (journeyOpen) {
            holder.SaveableStateProvider("journey:${ta?.id}") { WorldJourneys(ta, articles, notes, looks, pictures,
                articleRows != null && noteRows != null && lookRows != null && pictureRows != null,
                contentTop, bottomInset, page, depth && shown, navigation, openJournal, openArticle, lookup) }
        } else if (onlineOpen && ta != null) {
            holder.SaveableStateProvider("online:${ta.id}") { WorldOnlineBooks(ta, contentTop, bottomInset, page, onlineQuery, { onlineQuery = it }, onlineFullText, { onlineFullText = it }, { onlineOpen = false; adding = true }) { id -> onlineOpen = false; adding = false; openArticle(id, 0) } }
        } else if (adding && ta != null) {
            holder.SaveableStateProvider("add:${ta.id}") { WorldArticleForm(ta, contentTop, bottomInset, page, { onlineOpen = true }) { id -> adding = false; openArticle(id, 0) } }
        } else if (active == WorldSpace.Study) {
            holder.SaveableStateProvider("study:${ta?.id}") { WorldStudy(ta, articles.filter { it.space == "study" }, reading, ta?.id in busy, contentTop, bottomInset, page,
                studyTab, { studyTab = it }, studyCategory, { studyCategory = it }, studyOptions, { studyOptions = it }, { adding = true }, openArticle,
                navigation, articleRows != null) }
        } else if (active == WorldSpace.Outside) {
            holder.SaveableStateProvider("outside:${ta?.id}") { WorldOutsidePage(ta, articles, notes, looks, pictures,
                articleRows != null && noteRows != null && lookRows != null && pictureRows != null,
                reading, ta?.id in busy, contentTop, bottomInset, page, openArticle, lookup, openJournal, { journeyOpen = true }, navigation) }
        } else if (active == WorldSpace.Notebook) {
            holder.SaveableStateProvider("notebook:${ta?.id}") { WorldNotebook(writings, navigation.writingOptions.value, { navigation.writingOptions.value = it }, notes, looks, ta, articles, contentTop, bottomInset,
                notebookQuery, { notebookQuery = it }, notebookFilter, { notebookFilter = it }, openArticle, lookup, openJournal,
                navigation, articleRows != null && noteRows != null && lookRows != null && writingRows != null) }
        } else if (active == WorldSpace.Album) {
            holder.SaveableStateProvider("album:${ta?.id}") { WorldAlbumPage(ta, contentTop, bottomInset,
                albumOptions, { albumOptions = it }, albumExpanded, { albumExpanded = it }, navigation) }
        } else if (active == null) {
            holder.SaveableStateProvider("overview") {
                WorldOverview(
                    companions, ta?.id, navigation::select, name, depth && shown, articles, notes, looks, writings,
                    statusTop + TopBarHeight, bottomInset, { space = it }, lookup,
                )
            }
        } else {
            holder.SaveableStateProvider(active) {
                LazyColumn(
                    Modifier.fillMaxSize().fadeUnderTopBar(statusTop + TopBarHeight, bottom = bottomInset - 10.dp),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = statusTop + TopBarHeight + 22.dp, bottom = bottomInset + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    item { SpaceArtwork(active, Modifier.fillMaxWidth().height(130.dp)) }
                    item {
                        GlassSurface(Modifier.fillMaxWidth(), shape = GlassShape.Rounded(28.dp), contentPadding = PaddingValues(24.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(active.empty, color = palette.content, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                                Text(active.detail, color = palette.contentSecondary, fontSize = 15.sp, lineHeight = 24.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorldOverview(
    companions: List<CompanionEntity>, selectedId: Long?, onSelect: (Long) -> Unit,
    name: String, depth: Boolean, articles: List<WorldArticleEntity>, notes: List<WorldNoteEntity>, looks: List<WorldPictureLookEntity>, writings: List<com.cleo.cleos.data.db.WorldWritingEntity>, top: Dp, bottom: Dp, onOpen: (WorldSpace) -> Unit,
    onLookup: (String) -> Unit,
) {
    val palette = LocalGlassPalette.current
    val shelf = remember(selectedId, articles) { WorldShelfSummary.from(selectedId, articles) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().fadeUnderTopBar(top, bottom = bottom - 10.dp),
        contentPadding = PaddingValues(top = top + 12.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (companions.size > 1) item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                companions.forEach { ta ->
                    val selected = ta.id == selectedId
                    val label = ta.name.trim().ifEmpty { "TA" }
                    Row(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(palette.content.copy(alpha = if (selected) .13f else .05f))
                            .selectable(selected, role = Role.RadioButton, onClick = { onSelect(ta.id) })
                            .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Avatar(ta.avatar, ta.avatarEmoji ?: avatarLetter(label, "TA"), 28.dp)
                        Text(label, color = if (selected) palette.content else palette.contentSecondary,
                            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(70.dp))
                    }
                }
            }
        }
        item {
            GlassSurface(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = "打开书房") { onOpen(WorldSpace.Study) },
                shape = GlassShape.Rounded(28.dp), contentPadding = PaddingValues(20.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    SpaceArtwork(WorldSpace.Study, Modifier.size(width = 70.dp, height = 98.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("最近在读", color = palette.contentSecondary, fontSize = 12.sp)
                        val recent = shelf.recent
                        Text(recent?.book?.title ?: "还没开始阅读", color = palette.content, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(recent?.let { if (it.book.grouped) "整本已读 ${it.book.readCharacters} / ${it.book.characters} 字符"
                            else "读到 ${it.part.readOffset} / ${it.part.content.length} 字符" }
                            ?: if (shelf.books.isEmpty()) "选一本读物放进${name}的书架" else "书架上有 ${shelf.books.size} 份读物，等 TA 慢慢挑", color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
        item { SpaceDeck(depth, shelf.books.size, notes.size + looks.size + writings.size, articles.count { it.space == "outside" }, onOpen) }
        item {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("还想知道", color = palette.contentSecondary, fontSize = 12.sp)
                val question = (notes.map { it.createdAt to it.question } + looks.map { it.createdAt to it.question }).filter { it.second.isNotBlank() }.maxByOrNull { it.first }?.second.orEmpty()
                Text(question.ifBlank { "给还没想完的问题留个位置" }, color = palette.content, fontSize = 16.sp)
                TextButton(onClick = { onLookup(question) }, enabled = selectedId != null) { Text("继续查阅") }
                Text(if (notes.isEmpty() && looks.isEmpty() && writings.isEmpty()) "随手写写、阅读或看图后，想法都可以留在随手本里。" else "随手本里留下了 ${writings.size} 篇自由随笔、${notes.size} 条阅读笔记、${looks.size} 条看图记录。", color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp)
            }
        }
    }
}

@Composable
private fun SpaceDeck(depth: Boolean, articles: Int, notes: Int, outside: Int, onOpen: (WorldSpace) -> Unit) {
    val palette = LocalGlassPalette.current
    val cardStyle = palette.card.let {
        it.copy(shadowAlpha = it.shadowAlpha * .8f, shadowRadius = it.shadowRadius + 6.dp)
    }
    val pager = rememberPagerState { WorldSpace.entries.size }
    val density = LocalDensity.current
    val cardHeight = 272.dp * density.fontScale.coerceAtLeast(1f)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("四个小空间", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("${pager.currentPage + 1} / ${WorldSpace.entries.size}", color = palette.contentSecondary, fontSize = 12.sp)
        }
        SpaceTabs(pager, depth)
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().height(cardHeight + 24.dp),
            contentPadding = PaddingValues(horizontal = 42.dp),
            pageSpacing = 12.dp,
            beyondViewportPageCount = 1,
        ) { page ->
            val room = WorldSpace.entries[page]
            // Read scroll in the layer only: no recomposition or relayout per drag frame.
            Box(
                Modifier.fillMaxSize().padding(vertical = 12.dp).graphicsLayer {
                    val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                    val pose = WorldDepth.at(offset, depth)
                    scaleX = pose.scale
                    scaleY = pose.scale
                    rotationY = pose.rotationY
                    cameraDistance = 14f * this.density
                    // Auto adds a bounds-clipped buffer as soon as alpha falls below 1.
                    // Keep the glass's outside shadow visible throughout the drag/settle.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    alpha = pose.alpha
                    translationX = pose.inward * 12.dp.toPx()
                },
            ) {
                GlassSurface(
                    modifier = Modifier.fillMaxSize().clickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "进入${room.title}",
                    ) { onOpen(room) },
                    style = cardStyle,
                    shape = GlassShape.Rounded(30.dp), contentPadding = PaddingValues(24.dp),
                ) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SpaceArtwork(room, Modifier.fillMaxWidth().weight(1f))
                        Text(room.title, color = palette.content, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                        Text(room.description, color = palette.contentSecondary, fontSize = 14.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(when (room) { WorldSpace.Study -> "$articles 份读物"; WorldSpace.Notebook -> "$notes 条笔记"; WorldSpace.Outside -> "$outside 条摘要"; else -> "为这位 TA 单独选照片" }, color = palette.contentSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = palette.accentContent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The highlight follows the pager itself; it never waits for settledPage or starts a second animation. */
@Composable
private fun SpaceTabs(pager: PagerState, motion: Boolean) {
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    var navigation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        val itemWidth = maxWidth / WorldSpace.entries.size
        Box(Modifier.align(Alignment.CenterStart).width(itemWidth - 4.dp).height(36.dp)
            .graphicsLayer {
                translationX = 2.dp.toPx() +
                    (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (WorldSpace.entries.size - 1).toFloat()) * itemWidth.toPx()
            }
            .background(palette.content.copy(alpha = .08f), RoundedCornerShape(50)))
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            WorldSpace.entries.forEachIndexed { index, space ->
                val selected = pager.currentPage == index
                val ink by animateColorAsState(if (selected) palette.accentContent else palette.contentSecondary,
                    animationSpec = tween(if (motion) 140 else 0), label = "space-tab-ink")
                Box(Modifier.weight(1f).heightIn(min = 44.dp)
                    .selectable(selected, interactionSource = null, indication = null, role = Role.Tab, onClick = {
                        navigation?.cancel()
                        navigation = scope.launch {
                            if (motion) pager.animateScrollToPage(index, animationSpec = tween(320, easing = FastOutSlowInEasing))
                            else pager.scrollToPage(index)
                        }
                    }).padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(space.short, color = ink, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

}

/** Palette silhouettes, not actual photos or covers. */
@Composable
private fun SpaceArtwork(space: WorldSpace, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    val ink = palette.accentContent
    val fill = palette.accent.copy(alpha = .12f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            val line = 1.5.dp.toPx()
            when (space) {
                WorldSpace.Study -> {
                    val bookW = (w * .30f).coerceAtMost(h * .54f)
                    val bookH = h * .78f
                    val left = (w - bookW * 2f - 8.dp.toPx()) / 2f
                    repeat(2) { i ->
                        val x = left + i * (bookW + 8.dp.toPx())
                        drawRoundRect(fill, Offset(x, h - bookH), Size(bookW, bookH), CornerRadius(5.dp.toPx()))
                        drawLine(ink.copy(alpha = .5f), Offset(x + bookW * .23f, h - bookH + 8.dp.toPx()), Offset(x + bookW * .23f, h - 8.dp.toPx()), line)
                    }
                }
                WorldSpace.Outside -> {
                    val windowW = (w * .65f).coerceAtMost(h * 1.45f)
                    val left = (w - windowW) / 2f
                    drawRoundRect(fill, Offset(left, h * .05f), Size(windowW, h * .9f), CornerRadius(h * .19f))
                    drawLine(ink.copy(alpha = .35f), Offset(w / 2f, h * .12f), Offset(w / 2f, h * .88f), line)
                    drawLine(ink.copy(alpha = .35f), Offset(left + 8.dp.toPx(), h * .57f), Offset(left + windowW - 8.dp.toPx(), h * .57f), line)
                }
                WorldSpace.Notebook -> {
                    val noteW = (w * .6f).coerceAtMost(h * 1.3f)
                    val left = (w - noteW) / 2f
                    drawRoundRect(fill, Offset(left, h * .08f), Size(noteW, h * .84f), CornerRadius(8.dp.toPx()))
                    repeat(3) { i ->
                        val y = h * (.33f + i * .17f)
                        drawLine(ink.copy(alpha = .35f), Offset(left + noteW * .18f, y), Offset(left + noteW * .8f, y), line)
                    }
                }
                WorldSpace.Album -> {
                    val photoW = (w * .6f).coerceAtMost(h * 1.4f)
                    val left = (w - photoW) / 2f
                    drawRoundRect(fill, Offset(left + 7.dp.toPx(), h * .06f), Size(photoW, h * .78f), CornerRadius(8.dp.toPx()))
                    drawRoundRect(fill, Offset(left - 4.dp.toPx(), h * .17f), Size(photoW, h * .78f), CornerRadius(8.dp.toPx()))
                    drawCircle(ink.copy(alpha = .4f), h * .06f, Offset(left + photoW * .3f, h * .44f))
                }
            }
        }
    }
}

