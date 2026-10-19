package com.cleo.cleos.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class PhoneKeepsCache {
    var value by mutableStateOf<PhoneKeeps?>(null)
    var loading by mutableStateOf(true)
    var failure by mutableStateOf("")
    var loaded=false
}

@Composable internal fun PhoneKeepsPage(root:Path,data:DesktopData,libraryName:String,favorites:Boolean,cache:PhoneKeepsCache,
    modifier:Modifier=Modifier,back:()->Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    var ownerKey by rememberSaveable {mutableStateOf("")}
    var letterFilter by rememberSaveable {mutableStateOf(0)}
    var selectedId by rememberSaveable {mutableStateOf<Long?>(null)}
    var ownerMenu by remember {mutableStateOf(false)}
    val listState=rememberLazyListState()
    val player=LocalAudioPlayback.current
    DisposableEffect(selectedId,query,ownerKey) {onDispose {player?.stop()}}
    LaunchedEffect(root,cache) {
        if(cache.loaded)return@LaunchedEffect
        cache.loading=true
        try {cache.value=withContext(Dispatchers.IO){readPhoneKeeps(root,data)};cache.loaded=true}
        catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){cache.failure=(e as? ImportFailure)?.message?:"手机归档暂时无法浏览，聊天资料未更改。";cache.loaded=true}
        finally {cache.loading=false}
    }
    Surface(modifier.fillMaxSize(),shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.97f)) {
        Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if(favorites)"收藏" else "信件",fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                    Text(libraryName,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                TextButton(back){Text("返回聊天")}
            }
            when {
                cache.loading -> Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(28.dp))}
                cache.failure.isNotBlank() -> Text(cache.failure,color=MaterialTheme.colorScheme.error)
                cache.value==null -> Text("这份资料库还没有手机信件与收藏。可以从「设置 → 数据与备份」导入手机备份，或扫码接收手机资料。",fontSize=14.sp,lineHeight=23.sp)
                else -> {
                    val keeps=cache.value!!
                    Text("备份于 ${keptDate(keeps.exportedAt)} · 只读快照",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(favorites)"保留收藏当时的内容；原对话删除后仍可查看。" else "仅显示备份时已送达的来信及你的信件；查看不会标记手机已读。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    val owners=remember(keeps,favorites) {(if(favorites)keeps.favorites.map {it.owner} else keeps.letters.map {it.owner}).distinctBy {it.key}.sortedBy {it.name}}
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(query,{query=it.take(500)},Modifier.weight(1f).semantics {contentDescription=if(favorites)"搜索收藏" else "搜索信件"},
                            placeholder={Text("搜索当前分类",fontSize=13.sp)},singleLine=true,shape=RoundedCornerShape(16.dp))
                        Box {
                            OutlinedButton({ownerMenu=true},modifier=Modifier.widthIn(max=180.dp).semantics {contentDescription=if(favorites)"收藏 TA 筛选" else "信件 TA 筛选"}) {
                                Text(owners.find {it.key==ownerKey}?.name?:"全部 TA",maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                            DropdownMenu(ownerMenu,{ownerMenu=false},modifier=Modifier.heightIn(max=280.dp)) {
                                DropdownMenuItem(text={Text("全部 TA")},onClick={ownerKey="";selectedId=null;ownerMenu=false},modifier=Modifier.semantics {contentDescription="筛选全部 TA"})
                                owners.forEach {owner->DropdownMenuItem(text={Text(owner.name,maxLines=1,overflow=TextOverflow.Ellipsis)},onClick={ownerKey=owner.key;selectedId=null;ownerMenu=false},modifier=Modifier.semantics {contentDescription="筛选 TA ${owner.key}"})}
                            }
                        }
                    }
                    if(!favorites)Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("全部","收到的","寄出的","草稿").forEachIndexed {index,label->FilterChip(letterFilter==index,{letterFilter=index;selectedId=null},label={Text(label)})}
                    }
                    val skipped=if(favorites)keeps.skippedFavorites else keeps.skippedLetters
                    if(skipped>0)Text("$skipped 条记录格式异常，未显示；完整记录仍在原始归档。",fontSize=11.sp,color=MaterialTheme.colorScheme.error)
                    if(favorites) {
                        val rows=remember(keeps,query,ownerKey){keeps.favorites.filter {(ownerKey.isBlank() || it.owner.key==ownerKey) && it.matches(query)}}
                        val selected=rows.find {it.id==selectedId}
                        if(selected!=null) {
                            TextButton({selectedId=null}){Text("‹ 返回收藏列表")}
                            KeptFavoriteBody(root,selected,Modifier.weight(1f))
                        }else LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=listState,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(rows.isEmpty())item {Text("当前分类没有匹配的收藏。",fontSize=13.sp)}
                            items(rows,key={it.id}) {entry->KeepCard(entry.title,"${entry.owner.name} · ${keptDate(entry.at)} · ${entry.parts.size} 条消息",entry.note.ifBlank {entry.parts.joinToString(" ") {it.text}.take(180)}){selectedId=entry.id}}
                        }
                    }else {
                        val rows=remember(keeps,query,ownerKey,letterFilter){keeps.letters.filter {
                            (ownerKey.isBlank() || it.owner.key==ownerKey) && it.matches(query) && when(letterFilter){1->!it.mine;2->it.mine && !it.draft;3->it.draft;else->true}
                        }}
                        val selected=rows.find {it.id==selectedId}
                        if(selected!=null) {
                            TextButton({selectedId=null}){Text("‹ 返回信件列表")}
                            key(selected.id) {LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                                item {Text(letterMeta(selected),fontSize=13.sp,color=MaterialTheme.colorScheme.primary)}
                                item {Text(keptDate(selected.at),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                                item {SelectionContainer {Text(selected.text.ifBlank {"这封信没有正文。"},fontSize=15.sp,lineHeight=27.sp)}}
                                if(selected.limited)item {KeepLimited()}
                            }}
                        }else LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=listState,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(rows.isEmpty())item {Text("当前分类没有匹配的信件。",fontSize=13.sp)}
                            items(rows,key={it.id}) {entry->KeepCard(entry.title,"${letterMeta(entry)} · ${keptDate(entry.at)}",entry.text.take(180)){selectedId=entry.id}}
                        }
                    }
                }
            }
        }
    }
}

private fun letterMeta(entry:KeptLetter)=when {entry.draft->"写给 ${entry.owner.name} · 草稿";entry.mine->"寄给 ${entry.owner.name}";else->"来自 ${entry.owner.name}"+(if(entry.unread)" · 备份时未读" else "")}
private fun keptDate(at:Long)=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
@Composable private fun KeepLimited(){Text("内容较长，当前只显示部分；完整原文仍在手机归档中。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
@Composable private fun KeepCard(title:String,meta:String,excerpt:String,open:()->Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick=open),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.primary.copy(alpha=.055f)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(title,fontSize=15.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(meta,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(excerpt.isNotBlank())Text(excerpt,fontSize=12.sp,lineHeight=19.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
}
@Composable private fun KeptFavoriteBody(root:Path,entry:KeptFavorite,modifier:Modifier) {
    key(entry.id) {LazyColumn(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {Text("${entry.owner.name} · 收藏于 ${keptDate(entry.at)}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(entry.note.isNotBlank())item {SelectionContainer {Text("备注：${entry.note}",fontSize=14.sp,lineHeight=23.sp)}}
        items(entry.parts) {part->Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(part.gapBefore)Text("··· 中间有未收藏的消息 ···",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${part.name} · ${keptDate(part.at)}",fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
            Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.primary.copy(alpha=if(part.mine).1f else .04f)) {
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(part.text.isNotBlank())SelectionContainer {Text(part.text,fontSize=14.sp,lineHeight=25.sp)}
                    part.attachments.forEach {AttachmentView(root,it)}
                }
            }
        }}
        if(entry.limited)item {KeepLimited()}
    }}
}
