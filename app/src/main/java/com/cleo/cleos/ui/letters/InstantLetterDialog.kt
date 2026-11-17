package com.cleo.cleos.ui.letters

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.InstantLetterData
import com.cleo.cleos.data.db.ConversationEntity
import com.cleo.cleos.data.db.LetterEntity
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.avatarLetter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Uses each letter's frozen colours, independent of wallpaper and dark mode. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun LetterEnvelope(letter: LetterEntity, from: String, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val theme = remember(letter.envelope) { InstantLetterData.theme(letter.envelope) ?: InstantLetterData.defaultEnvelope }
    val ink = Color(theme.envelopeInk)
    val motion=com.cleo.cleos.ui.common.systemMotionEnabled()
    val fresh=remember(letter.id) { !letter.draft && System.currentTimeMillis()-(letter.deliverAt ?: letter.createdAt) in 0L..2500L }
    val entry=remember(letter.id) {Animatable(if(fresh && motion)0f else 1f)}
    LaunchedEffect(letter.id,motion) {if(!motion)entry.snapTo(1f) else entry.animateTo(1f,tween(260,easing=FastOutSlowInEasing))}
    // This runs after the transaction. It never delays delivery or disables the envelope.
    Column(modifier.widthIn(max = 236.dp).graphicsLayer {
        alpha=.78f+.22f*entry.value;translationY=(1f-entry.value)*8.dp.toPx();scaleY=.97f+.03f*entry.value
    }.clip(RoundedCornerShape(22.dp))
        .background(Color(theme.envelope)).envelopeTexture(theme).border(1.dp, ink.copy(alpha = .12f), RoundedCornerShape(22.dp))
        .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EnvelopeArtwork(theme,letter.readAt != null,Modifier.fillMaxWidth().height(42.dp),fold=if(letter.author==LetterEntity.AUTHOR_ME)entry.value else 1f,sealSize=24.dp)
        Text(letter.title.ifBlank { "一封信" }, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(from,modifier=Modifier.weight(1f,fill=false),color=ink.copy(alpha=.7f),fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(if(letter.author==LetterEntity.AUTHOR_ME) "已寄出" else if(letter.readAt==null) "未拆封" else "已拆封",color=ink.copy(alpha=.7f),fontSize=11.sp)
        }
        Text(Dates.chatStamp(letter.deliverAt ?: letter.createdAt), color = ink.copy(alpha = .6f), fontSize = 10.sp)
    }
}

/** An editor/reader over the existing page. Closing leaves its chat scroll state intact. */
@Composable
fun InstantLetterDialog(companionId: Long, conversationId: Long? = null, letterId: Long? = null,
                        replyTo: Long? = null, onDismiss: () -> Unit, onSent: () -> Unit = {}) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    var row by remember { mutableStateOf<LetterEntity?>(null) }
    var text by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var ownerCreatedAt by remember { mutableLongStateOf(0) }
    var ownerName by remember { mutableStateOf("TA") }
    var ownerAvatar by remember { mutableStateOf<String?>(null) }
    var ownerEmoji by remember { mutableStateOf<String?>(null) }
    var targetConversation by remember { mutableStateOf(conversationId) }
    var busy by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var replying by remember { mutableStateOf(false) }
    var choosingTheme by remember { mutableStateOf(false) }
    val lock = remember { Mutex() }
    val scope = rememberCoroutineScope()
    val editable = letterId == null || row?.draft == true

    if (replying) {
        key("reply", row?.id) { InstantLetterDialog(companionId, targetConversation, replyTo = row?.id, onDismiss = onDismiss, onSent = onSent) }
        return
    }
    LaunchedEffect(Unit) {
        try {
            val ta = c.db.companions().get(companionId) ?: error("这位 TA 已不存在")
            ownerCreatedAt = ta.createdAt; ownerName = ta.name.ifBlank { "TA" }; ownerAvatar = ta.avatar; ownerEmoji = ta.avatarEmoji
            val loaded = if (letterId == null) c.instantLetters.draft(companionId, replyTo) else c.db.letters().get(letterId) ?: error("这封信已被删除")
            check(loaded.companionId == companionId) { "信件不属于这位 TA" }
            row = loaded; title = loaded.title; text = loaded.content
            if (loaded.draft && replyTo != null && loaded.replyTo != replyTo) notice = "继续上次未寄出的草稿；没有覆盖原来的内容。"
            if (!loaded.draft) { c.letters.markRead(loaded.id); row = c.db.letters().get(loaded.id) }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { problem = e.message ?: "没能打开信件" }
    }
    suspend fun save(): LetterEntity? = lock.withLock {
        val current = row ?: return@withLock null
        if (!editable || finished) return@withLock current
        c.instantLetters.save(current.id, companionId, title, text).also { row = it }
    }
    LaunchedEffect(title, text, row?.id) {
        if (row != null && editable && !busy && !finished) {
            delay(600)
            try { save() } catch (e: CancellationException) { throw e } catch (e: Exception) { problem = e.message ?: "草稿未保存，请重试" }
        }
    }
    fun close() {
        if (busy) return
        if (!editable || finished || row == null) { onDismiss(); return }
        busy = true
        c.appScope.launch {
            try {
                save()
                if (title.isBlank() && text.isBlank()) row?.let { c.letters.delete(it.id) }
                finished = true; onDismiss()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { problem = e.message ?: "草稿未保存，请重试"
            } finally { busy = false }
        }
    }
    // Covers navigation away, not only the visible close button. Autosave and close share a lock.
    DisposableEffect(editable) { onDispose {
        val current = row
        if (editable && !finished && current != null) {
            val keptTitle = title; val keptBody = text
            c.appScope.launch { lock.withLock {
                runCatching {
                    if (keptTitle.isBlank() && keptBody.isBlank()) c.letters.delete(current.id)
                    else c.instantLetters.save(current.id, companionId, keptTitle, keptBody)
                }
            } }
        }
    } }
    val theme = InstantLetterData.theme(row?.envelope) ?: InstantLetterData.defaultEnvelope
    val ink = Color(theme.ink)
    val bodyStyle = TextStyle(color = ink, fontSize = 17.sp, lineHeight = 29.sp, fontFamily = FontFamily.Serif)
    val motion=com.cleo.cleos.ui.common.systemMotionEnabled()
    val unfolding=remember(letterId){Animatable(if(motion)0f else 1f)}
    LaunchedEffect(row?.id,motion) {
        if(row!=null) {if(motion)unfolding.animateTo(1f,tween(220,easing=FastOutSlowInEasing)) else unfolding.snapTo(1f)}
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        BackHandler { close() }
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(if (fullscreen) 0.dp else 12.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(if (fullscreen) 1f else .92f).graphicsLayer {
                alpha=.72f+.28f*unfolding.value;scaleY=.98f+.02f*unfolding.value;translationY=(1f-unfolding.value)*12.dp.toPx()
            }
                .clip(RoundedCornerShape(if (fullscreen) 16.dp else 28.dp)).background(Color(theme.paper))
                .border(1.dp, ink.copy(alpha = .12f), RoundedCornerShape(if (fullscreen) 16.dp else 28.dp))) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(ownerAvatar, ownerEmoji ?: avatarLetter(ownerName, "TA"), 34.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(if (editable) "写给$ownerName" else if (row?.author == "ai") "${ownerName}写来的" else "你寄出的", color = ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (editable) "草稿自动保存" else row?.let { Dates.chatStamp(it.deliverAt ?: it.createdAt) }.orEmpty(), color = ink.copy(alpha = .6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { fullscreen = !fullscreen }) { Icon(if(fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if(fullscreen) "收起全屏" else "展开全屏", tint = ink) }
                    IconButton(onClick = ::close, enabled = !busy) { Icon(Icons.Rounded.Close, "关闭信件", tint = ink) }
                }
                HorizontalDivider(color = ink.copy(alpha = .08f))
                Column(Modifier.weight(1f).fillMaxWidth().letterPaper(theme).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (editable) {
                        BasicTextField(title, { if (!busy) title = it }, enabled = row != null && !busy,
                            textStyle = bodyStyle.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium), cursorBrush = SolidColor(Color(theme.seal)), modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner -> Box { if(title.isEmpty()) Text("标题（可不写）", color=ink.copy(alpha=.4f)); inner() } })
                        BasicTextField(text, { if (!busy) { text = it; problem = null } }, enabled = row != null && !busy,
                            textStyle = bodyStyle, cursorBrush = SolidColor(Color(theme.seal)), modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                            decorationBox = { inner -> Box { if(text.isEmpty()) Text("想说的话，慢慢写……", style=bodyStyle.copy(color=ink.copy(alpha=.4f))); inner() } })
                    } else SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (title.isNotBlank()) Text(title, style = bodyStyle.copy(fontSize=21.sp,fontWeight=FontWeight.Medium))
                            Text(text, style = bodyStyle)
                        }
                    }
                }
                notice?.let { Text(it, color = ink.copy(alpha=.7f), fontSize = 12.sp, modifier = Modifier.padding(horizontal=20.dp,vertical=4.dp)) }
                problem?.let { Text(it, color = Color(0xFFAA3349), fontSize = 13.sp, modifier = Modifier.padding(horizontal=20.dp,vertical=4.dp)) }
                HorizontalDivider(color=ink.copy(alpha=.08f))
                if (editable) {
                    Row(Modifier.fillMaxWidth().padding(start=20.dp,end=10.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("${text.codePointCount(0,text.length)} 字",color=ink.copy(alpha=.6f),fontSize=12.sp,modifier=Modifier.weight(1f))
                        TextButton(enabled=row!=null && !busy,onClick={choosingTheme=true}) {Text("信封 · ${theme.name}",color=Color(theme.seal),maxLines=1,overflow=TextOverflow.Ellipsis)}
                    }
                    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement=Arrangement.End) {
                        TextButton(onClick=::close,enabled=!busy) { Text("保存草稿",color=Color(theme.seal)) }
                        Button(enabled=row != null && !busy && text.isNotBlank(), colors=ButtonDefaults.buttonColors(containerColor=Color(theme.seal)), onClick={
                            busy=true; problem=null
                            c.appScope.launch {
                                try {
                                    val saved=save() ?: error("草稿还没准备好")
                                    val cv=targetConversation ?: c.db.conversations().latestFor(companionId)?.id ?: run {
                                        val at=System.currentTimeMillis()
                                        c.db.conversations().insert(ConversationEntity(title="聊天",createdAt=at,updatedAt=at,companionId=companionId))
                                    }
                                    targetConversation=cv
                                    val sent=c.instantLetters.send(saved.id,companionId,ownerCreatedAt,cv)
                                    finished=true
                                    if(sent.fresh) c.chat.answerLetter(cv)
                                    onSent(); onDismiss()
                                } catch(e:CancellationException) { throw e
                                } catch(e:Exception) { problem=e.message ?: "没有寄出，草稿仍保留"
                                } finally { busy=false }
                            }
                        }) { Text(if(busy) "正在折叠…" else "折叠寄出") }
                    }
                } else Row(Modifier.fillMaxWidth().padding(10.dp),horizontalArrangement=Arrangement.End) {
                    TextButton(enabled=row!=null && !busy,onClick={ busy=true; scope.launch { try { c.favorites.saveLetter(row!!.id); notice="已收藏" } catch(e:CancellationException){throw e} catch(e:Exception){problem=e.message} finally{busy=false} } }) { Text("收藏",color=Color(theme.seal)) }
                    TextButton(enabled=row!=null && !busy,onClick={ replying=true }) { Text("回信",color=Color(theme.seal)) }
                    TextButton(onClick=::close) { Text("合上",color=Color(theme.seal)) }
                }
            }
        }
    }
    if (choosingTheme) EnvelopeThemePicker(selected=InstantLetterData.theme(row?.envelope),onDismiss={choosingTheme=false},onPick={picked->
        busy=true
        scope.launch { try { row=c.instantLetters.chooseEnvelope(row!!.id,companionId,picked.id) }
            catch(e:CancellationException){throw e} catch(e:Exception){problem=e.message ?: "没有更换信封"} finally {busy=false} }
    })
}
