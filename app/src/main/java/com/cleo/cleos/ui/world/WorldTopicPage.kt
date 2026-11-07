package com.cleo.cleos.ui.world

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.WorldReading
import com.cleo.cleos.data.WorldTopicSearch
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import java.time.LocalDate

@Composable
internal fun WorldTopicPage(ta: CompanionEntity?, seed: String, query: String, onQuery: (String) -> Unit, articles: List<WorldArticleEntity>,
    reading: WorldReadingStateEntity?, busy: Boolean, top: Dp, bottom: Dp, backdrop: Backdrop,
    onOpen: (Long, Int) -> Unit, onPaste: () -> Unit,
) {
    val c = appContainer(); val p = LocalGlassPalette.current; val keyboard = LocalSoftwareKeyboardController.current
    val states by c.worldOutside.states.collectAsStateWithLifecycle()
    val fetch = states[ta?.id]
    val result = fetch?.takeIf { it.query.isNotBlank() && it.query == query.trim() }
    val matches = topicResults(articles, query, fetch?.query, fetch?.articleIds)
    val used = reading?.takeIf { it.day == LocalDate.now().toEpochDay() }?.let { (it.attempts - if (it.token != null) 1 else 0).coerceAtLeast(0) } ?: 0
    val valid = runCatching { WorldTopicSearch.query(query) }.isSuccess
    LazyColumn(Modifier.fillMaxSize().fadeUnderTopBar(top, bottom), contentPadding = worldPadding(top, bottom), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { WorldPanel {
            Text("沿着一个问题看看", color = p.content, fontSize = 18.sp)
            if (seed.isNotBlank()) Text("还想知道 · $seed", color = p.contentSecondary, fontSize = 14.sp, lineHeight = 22.sp)
            Text("可以把问题改成几个关键词，也可以找一个地方、人物或自然现象。", color = p.contentSecondary, fontSize = 14.sp, lineHeight = 22.sp)
            OutlinedTextField(query, { if (it.length <= WorldTopicSearch.MAX_QUERY) onQuery(it) }, Modifier.fillMaxWidth(),
                enabled = fetch?.busy != true && !busy, label = { Text("检索词") }, minLines = 1, maxLines = 3)
            WorldBrowserSearch(query)
            TextButton(onClick = onPaste, enabled = ta != null) { Text("粘贴正文给 TA") }
            HorizontalDivider(color = p.contentSecondary.copy(alpha = .12f))
            Text("维基百科摘要", color = p.contentSecondary, fontSize = 13.sp)
            Text("点击查找，会把上面的检索词发送到中文维基百科，最多获取 3 条导言摘要。不会发送聊天、用户记忆或整本笔记；检索本身不调用模型。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(onClick = {
                    keyboard?.hide()
                    ta?.let { if (fetch?.busy == true) c.worldOutside.cancel(it.id) else c.worldOutside.search(it.id, it.createdAt, query) }
                }, backdrop = backdrop, enabled = ta != null && !busy && (if (fetch?.busy == true) result != null else valid)) {
                    Text(if (fetch?.busy == true && result != null) "停止查找" else "查找摘要")
                }
                TextButton(onClick = { ta?.let { if (busy) c.world.cancel(it.id) else c.world.startOutsideResults(it.id, matches.map { a -> a.id }.toSet(), query) } },
                    enabled = ta != null && (busy || fetch?.busy != true && matches.any { it.readOffset < it.content.length } && used < WorldReading.DAILY)) {
                    Text(if (busy) "停止阅读" else "让 TA 看看")
                }
            }
            Text("让 TA 看时，角色设定、少量近期随手本笔记、这些结果的标题、来源、检索词与所选摘要会发给 TA 配置的模型。与书房共用每天 ${WorldReading.DAILY} 次机会，今天已用 $used 次；失败或停止会退回。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
            (result ?: fetch?.takeIf { it.busy })?.message?.takeIf(String::isNotBlank)?.let { Text(it, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp) }
            if (reading?.space == "outside" && reading.status.isNotBlank()) Text("最近一次窗外阅读：${reading.status}", color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp)
        } }
        item { WorldPanel { WorldTopicConsent(ta) } }
        if (matches.isNotEmpty()) item { Text("找到的摘要", color = p.contentSecondary, fontSize = 13.sp) }
        items(matches, key = { it.id }) { article -> WorldPanel {
            Text(article.title, color = p.content, fontSize = 17.sp)
            Text("${article.source} · 百科导言摘要", color = p.contentSecondary, fontSize = 12.sp)
            Text(article.content.substringAfter("\n\n").take(180), color = p.contentSecondary, fontSize = 14.sp, lineHeight = 23.sp)
            Text(if (article.readOffset == article.content.length) "TA 已看过这份摘要" else "尚未看完摘要", color = p.contentSecondary, fontSize = 12.sp)
            TextButton(onClick = { onOpen(article.id, article.readOffset) }) { Text("查看摘要与来源") }
        } }
        item { Text("这里只查阅百科导言，未读取完整条目或图片，也不代表问题已经解决。中文维基百科文字按 CC BY-SA 4.0 等条款提供，原作者与修订记录可从每条来源查看。订阅源自主浏览仍只获取所选订阅源；自主续查需要在上方单独开启。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp) }
    }
}
