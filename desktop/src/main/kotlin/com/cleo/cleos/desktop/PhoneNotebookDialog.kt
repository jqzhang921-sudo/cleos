package com.cleo.cleos.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class PhoneNotebookCache {
    var notebook by mutableStateOf<PhoneNotebook?>(null)
    var loading by mutableStateOf(true)
    var failure by mutableStateOf("")
    var loaded=false
}

@Composable internal fun PhoneNotebookPage(root:Path,data:DesktopData,libraryName:String,todos:Boolean,cache:PhoneNotebookCache,modifier:Modifier=Modifier,back:()->Unit) {
    var diaryTab by rememberSaveable{mutableStateOf(0)}
    val tab=if(todos)2 else diaryTab
    var query by rememberSaveable(tab){mutableStateOf("")}
    var diaryId by rememberSaveable(tab){mutableStateOf<Long?>(null)}
    var todoId by rememberSaveable(tab){mutableStateOf<Long?>(null)}
    var doneFilter by rememberSaveable{mutableStateOf(0)}
    val listState=rememberLazyListState()
    LaunchedEffect(root,cache) {
        if(cache.loaded)return@LaunchedEffect
        cache.loading=true
        try {cache.notebook=withContext(Dispatchers.IO){readPhoneNotebook(root,data)};cache.loaded=true}
        catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){cache.failure=(e as? ImportFailure)?.message?:"手机归档暂时无法浏览，聊天资料未更改。";cache.loaded=true}
        finally {cache.loading=false}
    }
        Surface(modifier.fillMaxSize(),shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.97f)) {
            Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {Text(if(todos)"待办" else "日记",fontSize=22.sp,fontWeight=FontWeight.SemiBold);Text(libraryName,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)}
                    TextButton(back){Text("返回聊天")}
                }
                Text("手机备份快照 · 只读查看",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    cache.loading -> Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(28.dp))}
                    cache.failure.isNotBlank() -> Text(cache.failure,color=MaterialTheme.colorScheme.error)
                    cache.notebook==null -> Text("这份资料库还没有手机日记与待办。可以先从个人菜单的「设置 → 数据与备份」导入手机备份，或扫码接收手机资料。",fontSize=14.sp,lineHeight=23.sp)
                    else -> {
                        val book=cache.notebook!!
                        val date=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(book.exportedAt).atZone(ZoneId.systemDefault()))
                        Text("备份于 $date；电脑上的查看不会更新手机，也不会设提醒。",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(!todos)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf("日记","小秘密").forEachIndexed {index,title->FilterChip(tab==index,{diaryTab=index},label={Text(title)},modifier=Modifier.semantics {contentDescription="${title}分类"})}
                        }
                        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().semantics {contentDescription="搜索日记与待办"},placeholder={Text("搜索当前分类",fontSize=13.sp)},singleLine=true,shape=RoundedCornerShape(16.dp))
                        if(book.skippedDiary+book.skippedTodos>0)Text("${book.skippedDiary} 篇日记、${book.skippedTodos} 条待办格式异常，未显示；完整记录仍在原始归档。",fontSize=11.sp,color=MaterialTheme.colorScheme.error)
                        if(tab==2) {
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("全部","未完成","已完成").forEachIndexed {i,title->FilterChip(doneFilter==i,{doneFilter=i;todoId=null},label={Text(title)})}}
                            val rows=remember(book,query,doneFilter){book.todos.filter { (doneFilter==0 || it.done==(doneFilter==2)) && (query.isBlank() || it.title.contains(query,true) || it.note.contains(query,true)) }}
                            val selected=rows.firstOrNull {it.id==todoId}
                            if(selected!=null) {
                                TextButton({todoId=null}){Text("‹ 返回待办列表")}
                                SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                    Text(selected.title,fontSize=20.sp,fontWeight=FontWeight.Medium)
                                    Text(if(selected.done)"已完成" else "未完成",fontSize=12.sp)
                                    Text(selected.dueDay?.let {"日期：${LocalDate.ofEpochDay(it)}"}?:"没有指定日期",fontSize=12.sp)
                                    Text(selected.note.ifBlank {"没有备注"},fontSize=14.sp,lineHeight=24.sp)
                                    if(selected.limited)Text("内容较长，当前只显示部分；完整内容仍在原始手机归档中。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }}
                            } else LazyColumn(Modifier.weight(1f),state=listState,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                if(rows.isEmpty())item {Text("当前分类没有匹配的待办。",fontSize=13.sp)}
                                items(rows,key={it.id}) {todo->NotebookCard(todo.title,(if(todo.done)"已完成" else "未完成")+" · "+(todo.dueDay?.let {LocalDate.ofEpochDay(it).toString()}?:"无日期"),todo.note.take(180)){todoId=todo.id}}
                            }
                        } else {
                            val rows=remember(book,query,tab){book.diary.filter {it.secret==(tab==1) && it.matches(query)}}
                            val selected=rows.firstOrNull {it.id==diaryId}
                            if(selected!=null) {
                                TextButton({diaryId=null}){Text("‹ 返回日记列表")}
                                NotebookDiaryBody(root,selected,Modifier.weight(1f))
                            } else LazyColumn(Modifier.weight(1f),state=listState,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                if(rows.isEmpty())item {Text("当前分类没有匹配的日记。",fontSize=13.sp)}
                                items(rows,key={it.id}) {entry->NotebookCard(entry.title,"${LocalDate.ofEpochDay(entry.day)} · ${entry.author}"+(if(entry.locked)" · 未分享" else ""),entry.excerpt){diaryId=entry.id}}
                            }
                        }
                    }
                }
            }
        }
}
@Composable private fun NotebookCard(title:String,meta:String,excerpt:String,open:()->Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick=open),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.primary.copy(alpha=.055f)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(title,fontSize=15.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(meta,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(excerpt.isNotBlank())Text(excerpt,fontSize=12.sp,lineHeight=19.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
}
@Composable private fun NotebookDiaryBody(root:Path,entry:NotebookDiary,modifier:Modifier) {
    key(entry.id) {
        LazyColumn(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {SelectionContainer {Text(entry.title,fontSize=21.sp,fontWeight=FontWeight.Medium)}}
            item {Text("${LocalDate.ofEpochDay(entry.day)} · ${entry.author}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(entry.locked) {
                item {Text(entry.publicHint.ifBlank {"有些话，暂时想留给自己"},fontSize=14.sp,lineHeight=24.sp)}
                if(entry.sharedExcerpt.isNotBlank())item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("TA 愿意分享的部分",fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
                    SelectionContainer {Text(entry.sharedExcerpt,fontSize=14.sp,lineHeight=24.sp)}
                }}
                item {Text("其余内容仍是小秘密。请回手机版向 TA 询问；电脑版暂不支持发起请求。",fontSize=12.sp,lineHeight=21.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                if(entry.limited)item {Text("公开摘录较长，当前只显示部分；完整记录仍在原始手机归档中。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            } else {
                items(entry.blocks) {block->when(block) {
                    is NotebookBlock.Text -> if(block.text.isNotEmpty())SelectionContainer {Text(block.text,fontSize=14.sp,lineHeight=24.sp)}
                    is NotebookBlock.Picture -> AttachmentView(root,Attachment(block.file,label="日记图片"))
                    is NotebookBlock.Notice -> Text(block.text,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }}
                if(entry.limited)item {Text("内容较长，当前只显示部分；完整内容仍在原始手机归档中。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        }
    }
}
