package com.cleo.cleos.ui.memory

import com.cleo.cleos.ui.common.SlidingCapsuleTabs
import com.cleo.cleos.ui.common.systemMotionEnabled

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MemoryChangesScreen(onBack:()->Unit,onOpen:(Long)->Unit) {
    val c=appContainer();val palette=LocalGlassPalette.current;val scope=rememberCoroutineScope()
    val ta by c.companions.current.collectAsStateWithLifecycle(null)
    val events by remember(ta?.id){ta?.let{c.db.memoryChanges().observe(it.id)} ?: flowOf(emptyList())}.collectAsStateWithLifecycle(emptyList())
    var selected by remember(ta?.id){mutableStateOf<Long?>(null)}
    var problem by remember(ta?.id){mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    var confirmModel by remember{mutableStateOf(false)}
    var pendingOnly by remember{mutableStateOf(true)}
    val top=WindowInsets.statusBars.asPaddingValues().calculateTopPadding()+TopBarHeight
    val bottom=WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    fun work(action:suspend(Long)->Unit) {
        val owner=ta?.id ?: return
        if(busy)return
        busy=true;problem=null
        scope.launch {try{action(owner)}catch(e:CancellationException){throw e}catch(e:Exception){problem=e.message ?: "这次没有完成"}finally{busy=false}}
    }
    GlassPage(overlay={page->GlassTopBar(title="整理与改动",subtitle=ta?.name,backdrop=page,
        leading={GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack,"返回",onBack,page)})}) {
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=14.dp,end=14.dp,top=top+12.dp,bottom=bottom+24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                GlassSurface(modifier=Modifier.fillMaxWidth(),shape=GlassShape.Rounded(24.dp),contentPadding=PaddingValues(16.dp)) {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("检查重复和冲突",fontWeight=FontWeight.Medium,color=palette.content)
                        Text("本地检查查找同名话题；请 TA 协助可检查不同说法。建议采用前不会改动旧记忆。",fontSize=13.sp,color=palette.contentSecondary)
                        Row {
                            TextButton(enabled=!busy,onClick={work{owner->problem=c.memoryOrganizer.organize(owner,false)}}){Text("本地检查")}
                            TextButton(enabled=!busy,onClick={confirmModel=true}){Text(if(busy)"正在处理…" else "请 TA 协助整理")}
                        }
                        problem?.let {Text(it,fontSize=13.sp,color=palette.content)}
                        SlidingCapsuleTabs(listOf("待查看", "改动记录"), if (pendingOnly) 0 else 1, systemMotionEnabled()) {
                            pendingOnly = it == 0
                        }
                        Text("改动最多保留最近 100 条。撤销前会核对当前版本；明确删除记忆时会同时清除相关历史，不能从这里恢复。",fontSize=12.sp,color=palette.contentSecondary)
                    }
                }
            }
            val visible=events.filter {if(pendingOnly)it.state in setOf("pending","review") else it.state !in setOf("pending","review")}
            if(visible.isEmpty())item{Text(if(pendingOnly)"没有待查看的建议" else "还没有改动记录",color=palette.contentSecondary,modifier=Modifier.padding(12.dp))}
            items(visible,key={it.id}){e->
                GlassSurface(modifier=Modifier.fillMaxWidth().clickable{selected=e.id},shape=GlassShape.Rounded(22.dp),contentPadding=PaddingValues(16.dp)) {
                    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(changeTitle(e),fontWeight=FontWeight.Medium,color=palette.content)
                        val names=(MemoryRules.decode(e.beforeRows)+MemoryRules.decode(e.afterRows)).map{it.name}.distinct()
                        Text(names.joinToString("、"),fontSize=14.sp,color=palette.content)
                        Text(Instant.ofEpochMilli(e.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),fontSize=12.sp,color=palette.contentSecondary)
                        if(e.reason.isNotBlank())Text(e.reason,fontSize=13.sp,color=palette.contentSecondary)
                    }
                }
            }
        }
    }
    val event=events.firstOrNull{it.id==selected}
    if(event!=null)AlertDialog(onDismissRequest={if(!busy)selected=null},title={Text(changeTitle(event))},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(event.reason.isNotBlank())Text(event.reason,fontSize=13.sp)
            Text("改动前",fontWeight=FontWeight.SemiBold)
            MemorySnapshot(MemoryRules.decode(event.beforeRows))
            if(event.state!="review") {
                Text(if(event.state=="pending")"采用后的内容" else "改动后",fontWeight=FontWeight.SemiBold)
                MemorySnapshot(MemoryRules.decode(event.afterRows))
            }
            if(event.state=="review")MemoryRules.decode(event.beforeRows).forEach{row->TextButton(enabled=!busy,onClick={work{owner->
                if(c.db.memories().get(row.id)?.companionId!=owner)error("原记忆已移除")
                selected=null;onOpen(row.id)
            }}){Text("打开「${row.name}」核对")}}
            problem?.let{Text(it,color=palette.error,fontSize=13.sp)}
        }
    },confirmButton={
        Row {
            when(event.state) {
                "pending"->TextButton(enabled=!busy,onClick={work{owner->c.memoryStore.apply(owner,event.id);selected=null;problem="已采用，可在改动记录中撤销。"}}){Text("采用")}
                "applied"->TextButton(enabled=!busy,onClick={work{owner->c.memoryStore.undo(owner,event.id);selected=null;problem="已撤销。"}}){Text("撤销这次改动")}
            }
            TextButton(enabled=!busy,onClick={selected=null}){Text("关闭")}
        }
    },dismissButton={if(event.state in setOf("pending","review"))TextButton(enabled=!busy,onClick={work{owner->c.memoryStore.dismiss(owner,event.id);selected=null}}){Text("忽略建议")}})
    if(confirmModel)AlertDialog(onDismissRequest={confirmModel=false},title={Text("请 TA 协助整理？")},
        text={Text("会使用这位 TA 当前的模型，发送其记忆目录及部分细节，产生一次整理请求的模型用量。结果只作为建议，采用前可以查看前后内容。")},
        confirmButton={TextButton(onClick={confirmModel=false;work{owner->problem=c.memoryOrganizer.organize(owner,true)}}){Text("开始整理")}},
        dismissButton={TextButton(onClick={confirmModel=false}){Text("取消")}})
}

private fun changeTitle(e:MemoryChangeEntity)= when(e.state){"pending"->"待查看的整理建议";"review"->"需要核对的说法";"undone"->"已撤销";"rejected"->"已忽略";else->when(e.actor){"user"->"你修改了记忆";"organizer"->"已采用整理";else->"TA 更新了记忆"}}

@Composable private fun MemorySnapshot(rows:List<MemoryEntity>) {
    if(rows.isEmpty())Text("（无）",fontSize=13.sp)
    rows.forEach{m->
        Text("${m.name}：${m.summary}",fontSize=14.sp,fontWeight=FontWeight.Medium)
        Text(MemoryContext.timing(m),fontSize=12.sp)
        MemoryDetails.decode(m.details).forEach{d->Text("· $d",fontSize=13.sp)}
        Text(if(MemoryContext.traces(m).isEmpty())"未关联原消息" else "保留 ${MemoryContext.traces(m).size} 项来源关联",fontSize=12.sp)
    }
}
