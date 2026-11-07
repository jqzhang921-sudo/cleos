package com.cleo.cleos.ui.world

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cleo.cleos.data.db.*
import com.cleo.cleos.data.WorldJourneyTopics
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Continuous local chapters. Images, source text and TA impressions stay distinct. */
@Composable
internal fun WorldJourney(ta: CompanionEntity?, articles: List<WorldArticleEntity>, notes: List<WorldNoteEntity>,
    looks: List<WorldPictureLookEntity>, pictures: List<WorldPictureEntity>, top: Dp, bottom: Dp, depth: Boolean,
    onJournal: (Long) -> Unit, onSource: (Long, Int) -> Unit, onLookup: (String) -> Unit,
    navigation: WorldNavigationModel, topic: WorldJourneyTopicEntity? = null, topicItems: List<WorldJourneyItemEntity> = emptyList(),
    onEdit: () -> Unit = {},
) {
    val c = appContainer(); val p = LocalGlassPalette.current; val list = rememberLazyListState(); val scope = rememberCoroutineScope()
    val anchorInset = with(LocalDensity.current) { (top + 12.dp).roundToPx() }
    val chapters = remember(ta?.id, articles, pictures, notes, looks, topic, topicItems) { ta?.let {
        val rows = if (topic == null) articles else WorldJourneyTopics.articles(it.id, topic, topicItems, articles)
        WorldJourneyData.chapters(it.id, rows, pictures, notes, looks, ordered = topic != null)
    } ?: emptyList() }
    LaunchedEffect(ta?.id, navigation.selectedId.value, topic?.id) {
        val owner = ta?.id ?: return@LaunchedEffect
        if (owner != navigation.selectedId.value) return@LaunchedEffect
        list.scrollToItem(navigation.journeyIndex.value, navigation.journeyOffset.value)
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.layoutInfo.totalItemsCount) }.collect { (index, offset, count) ->
            if (count > 0 && navigation.selectedId.value == owner) { navigation.journeyIndex.value = index; navigation.journeyOffset.value = offset }
        }
    }
    val current by remember { derivedStateOf { (list.firstVisibleItemIndex - 1).coerceAtLeast(0) } }
    val name = ta?.name?.ifBlank { "TA" } ?: "TA"
    LazyColumn(Modifier.fillMaxSize().fadeUnderTopBar(top, bottom), state = list,
        contentPadding = PaddingValues(top = top + 18.dp, bottom = bottom + 28.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item(key = "intro") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(topic?.title ?: "随意漫游", color = p.content, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (topic == null) "串起${name}已保存的公开见闻。图文来自资料，TA 的感想单独留下。" else
                        "已保存的${WorldJourneyTopics.kinds[topic.kind] ?: "主题"}专题 · ${chapters.size} 则见闻；TA 已读完 ${chapters.count { WorldOutsideCardData.from(ta?.id, it.article, emptyList())?.reading == WorldOutsideCardData.Reading.Complete }} 份摘要。", color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp)
                    Text("只浏览本地图文，不会自动搜索或请求模型。", color = p.contentSecondary, fontSize = 11.sp, lineHeight = 18.sp)
                    if (chapters.any { chapter -> chapter.thoughts.any { it.question.isNotBlank() } }) Text("笔记中的问题按原记录保留，未自动判断是否已找到答案；可以沿问题继续查阅。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
                    if (topic == null && articles.count { it.companionId == ta?.id && it.space == "outside" } > WorldJourneyData.LIMIT)
                        Text("这里展示最近 ${WorldJourneyData.LIMIT} 则，全部见闻仍在窗外。", color = p.contentSecondary, fontSize = 11.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (topic != null) TextButton(onClick = onEdit) { Text("整理这个专题") }
                        TextButton(onClick = { navigation.backJourney() }) { Text("专题列表") }
                    }
                }
                if (chapters.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(chapters, key = { _, chapter -> chapter.article.id }) { index, chapter ->
                        FilterChip(selected = index == current, onClick = { scope.launch {
                            if (depth) list.animateScrollToItem(index + 1, -anchorInset) else list.scrollToItem(index + 1, -anchorInset)
                        } }, label = { Text("${index + 1} · ${chapter.article.title}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp), fontSize = 12.sp) })
                    }
                }
            }
        }
        if (chapters.isEmpty()) item(key = "empty") {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (topic == null) "还没有可漫游的见闻" else "专题里的见闻已移除", color = p.content, fontSize = 18.sp)
                Text(if (topic == null) "先查一个感兴趣的地方，或回窗外刷新摘要。保存的资料会在这里连起来，没有配图的见闻也能阅读。" else
                    "专题名仍然保留。可以整理专题，再加入现有见闻；原先移除的内容不会重新出现。", color = p.contentSecondary, fontSize = 14.sp, lineHeight = 24.sp)
                if (topic != null) TextButton(onClick = onEdit) { Text("加入现有见闻") }
                TextButton(onClick = { onLookup("") }, enabled = ta != null) { Text("查一个地方") }
            }
        }
        itemsIndexed(chapters, key = { _, chapter -> "chapter:${chapter.article.id}" }) { index, chapter ->
            val article = chapter.article
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (index > 0) HorizontalDivider(Modifier.padding(bottom = 14.dp), color = p.contentSecondary.copy(alpha = .16f))
                    Text("${(index + 1).toString().padStart(2, '0')} / ${chapters.size.toString().padStart(2, '0')}  ·  ${if (article.searchQuery.isBlank()) "订阅见闻" else "沿着「${article.searchQuery}」"}",
                        color = p.contentSecondary, fontSize = 11.sp, lineHeight = 18.sp)
                    Text(article.title, color = p.content, fontSize = 18.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
                    val state = WorldOutsideCardData.from(ta?.id, article, emptyList())?.reading
                    val status = when (state) { WorldOutsideCardData.Reading.Complete -> "TA 已读完摘要"; WorldOutsideCardData.Reading.Partial -> "TA 已读过部分摘要"
                        WorldOutsideCardData.Reading.Unread -> "TA 尚未读这份摘要"; WorldOutsideCardData.Reading.Empty -> "暂无可读摘要"; else -> "阅读进度暂不可确认" }
                    Text("${article.source} · $status",
                        color = p.contentSecondary, fontSize = 12.sp, lineHeight = 19.sp)
                }
                if (index % 2 == 0) JourneyPicture(chapter, list, depth, index)
                Column(Modifier.padding(horizontal = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("来源摘要片段", color = p.contentSecondary, fontSize = 11.sp)
                    SelectionContainer { Text(chapter.paragraphs.firstOrNull() ?: "这则摘要暂时没有正文，可在单篇见闻中核对来源。",
                        color = p.content, fontSize = 15.sp, lineHeight = 26.sp) }
                }
                if (index % 2 != 0) JourneyPicture(chapter, list, depth, index)
                chapter.paragraphs.drop(1).forEach { paragraph -> SelectionContainer(Modifier.padding(horizontal = 26.dp)) {
                    Text(paragraph, color = p.content, fontSize = 15.sp, lineHeight = 26.sp)
                } }
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (chapter.thoughts.isNotEmpty()) chapter.thoughts.forEach { thought ->
                        GlassSurface(style = p.notice, contentPadding = PaddingValues(14.dp), shape = GlassShape.Rounded(18.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("${name}的随手记 · ${thought.kind}", color = p.contentSecondary, fontSize = 11.sp)
                                if (thought.content.isNotBlank()) Text(thought.content, color = p.content, fontSize = 14.sp, lineHeight = 23.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                if (thought.question.isNotBlank()) {
                                    Text("笔记中的问题 · ${thought.question}", color = p.accentContent, fontSize = 13.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    TextButton(onClick = { onLookup(thought.question) }) { Text("沿着这个问题查阅") }
                                }
                            }
                        }
                    } else Text("这里还没有留下感想。", color = p.contentSecondary, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onJournal(article.id) }) { Text("打开这则见闻") }
                        TextButton(onClick = { onSource(article.id, 0) }) { Text("摘要与来源") }
                    }
                }
            }
        }
        if (chapters.isNotEmpty()) item(key = "end") {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider(color = p.contentSecondary.copy(alpha = .16f))
                Text("好奇可以继续", color = p.content, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("下一处，想看看哪里？", color = p.contentSecondary, fontSize = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onLookup("") }) { Text("再查一个地方") }
                    TextButton(onClick = { scope.launch { if (depth) list.animateScrollToItem(0) else list.scrollToItem(0) } }) { Text("回到开头") }
                }
            }
        }
    }
}

@Composable
private fun JourneyPicture(chapter: WorldJourneyChapter, list: androidx.compose.foundation.lazy.LazyListState, depth: Boolean, index: Int) {
    val pic = chapter.image
    if (pic == null) {
        Text("这则见闻暂未配图，可以先读来源摘要。", color = LocalGlassPalette.current.contentSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 26.dp))
        return
    }
    val c = appContainer(); val p = LocalGlassPalette.current
    var failed by remember(pic.file, pic.sha256) { mutableStateOf(false) }
    Column(Modifier.padding(start = if (index % 2 == 0) 14.dp else 28.dp, end = if (index % 2 == 0) 28.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))) {
            if (!failed) AsyncImage(model = c.images.file(pic.file), contentDescription = "漫游配图：${pic.title}；作者 ${pic.author}",
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio((pic.width.toFloat() / pic.height).coerceIn(.75f, 2f)).graphicsLayer {
                    val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "chapter:${chapter.article.id}" }
                    val offset = item?.let { -it.offset.toFloat() / it.size.coerceAtLeast(1) } ?: 0f
                    scaleX = if (depth) 1.04f else 1f; scaleY = scaleX
                    translationY = size.height * WorldJournalMotion.at(offset, depth)
                }, onError = { failed = true })
            else Text("这张配图暂时无法显示，摘要与来源仍可查看。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(14.dp))
        }
        Text("${pic.author} · ${pic.license} · 来源配图", color = p.contentSecondary, fontSize = 11.sp, lineHeight = 18.sp)
    }
}
