package com.cleo.cleos.ui.letters

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.EnvelopeSnapshot
import com.cleo.cleos.data.EnvelopeThemes
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun EnvelopeThemePicker(selected: EnvelopeSnapshot? = null, onPick: ((EnvelopeSnapshot) -> Unit)? = null, onDismiss: () -> Unit) {
    val c=appContainer();val s by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope=rememberCoroutineScope()
    var selectedId by remember { mutableStateOf(selected?.id ?: EnvelopeThemes.default(s).id) }
    var editing by remember { mutableStateOf<EnvelopeSnapshot?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var colorsValid by remember { mutableStateOf(true) }
    var touched by remember { mutableStateOf(selected != null) }
    var editorRevision by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    val themes=EnvelopeThemes.installed(s)
    val current=themes.firstOrNull { it.id==selectedId } ?: EnvelopeThemes.default(s)
    LaunchedEffect(s.defaultEnvelopeTheme,s.customEnvelopeThemes) { if(!touched) selectedId=EnvelopeThemes.default(s).id }
    fun change(block: (AppSettings)->AppSettings) {
        busy=true
        scope.launch { try { c.settings.update(block);notice="已保存" } catch(e:Exception) {notice="没能保存，请重试"} finally {busy=false} }
    }
    Dialog(onDismissRequest={if(!busy)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        BackHandler { if(!busy) { if(editing!=null)editing=null else onDismiss() } }
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp),shape=RoundedCornerShape(26.dp)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact=maxHeight<570.dp
            Column(Modifier.padding(if(compact)12.dp else 16.dp),verticalArrangement=Arrangement.spacedBy(if(compact)8.dp else 10.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(editing!=null) "自定义信封" else "信封主题",fontSize=20.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f))
                    TextButton(enabled=!busy,onClick={if(editing!=null)editing=null else onDismiss()}) { Text(if(editing!=null) "返回" else "完成") }
                }
                val draft=editing
                if(draft!=null) {
                    key(draft.id,editorRevision) {ThemeEditor(draft,onChange={editing=it},onValidity={colorsValid=it},modifier=Modifier.weight(1f))}
                    notice?.let { Text(it,fontSize=12.sp) }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        TextButton(enabled=!busy,onClick={editing=EnvelopeThemes.builtIn.first().copy(id=draft.id,name=draft.name);editorRevision++;colorsValid=true}) { Text("重置样式") }
                        Button(enabled=!busy && colorsValid && EnvelopeThemes.valid(draft),onClick={
                            change { old -> old.copy(customEnvelopeThemes=EnvelopeThemes.custom(old.customEnvelopeThemes.filterNot{it.id==draft.id}+draft)) }
                            touched=true;selectedId=draft.id;editing=null
                        }) { Text("保存样式") }
                    }
                } else {
                    Text(if(compact) "寄出后样式固定，换默认也不改变旧信。" else "寄出时保存这封信的完整样式；以后修改默认或删除自定义主题，旧信仍保持原样。",fontSize=12.sp,lineHeight=18.sp)
                    LazyVerticalGrid(columns=GridCells.Adaptive(if(compact)104.dp else 138.dp),modifier=Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        items(themes,key={it.id}) { t ->
                            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(t.envelope)).envelopeTexture(t)
                                .border(if(t.id==current.id) 2.dp else .5.dp,if(t.id==current.id) MaterialTheme.colorScheme.primary else Color(t.envelopeInk).copy(alpha=.18f),RoundedCornerShape(16.dp))
                                .clickable(enabled=!busy){touched=true;selectedId=t.id;notice=null}.padding(12.dp)) {
                                EnvelopeArtwork(t,false,Modifier.fillMaxWidth().height(if(compact)36.dp else 58.dp))
                                Text(t.name,color=Color(t.envelopeInk),fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=8.dp))
                                Text(if(t.id==EnvelopeThemes.default(s).id) "默认信封" else if(t.id==current.id) "已选中" else " ",color=Color(t.envelopeInk).copy(alpha=.6f),fontSize=11.sp)
                            }
                        }
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(if(compact) "TA 可自选信封" else "允许 TA 按内容选信封",modifier=Modifier.weight(1f),fontSize=14.sp)
                        Switch(checked=s.aiEnvelopeChoice,enabled=!busy,onCheckedChange={enabled->change{it.copy(aiEnvelopeChoice=enabled)}})
                    }
                    if(!compact) Text(if(s.aiEnvelopeChoice) "平时仍用默认，只在适合时选已安装主题。" else "TA 固定沿用默认信封，不会随机更换。",fontSize=12.sp)
                    notice?.let { Text(it,fontSize=12.sp) }
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled=!busy,contentPadding=PaddingValues(horizontal=if(compact)8.dp else 12.dp),onClick={change{it.copy(defaultEnvelopeTheme=current.id)}}) { Text(if(compact) "默认" else "设为默认") }
                        if(onPick!=null) Button(enabled=!busy,contentPadding=PaddingValues(horizontal=if(compact)8.dp else 24.dp),onClick={onPick(current);onDismiss()}) { Text(if(compact) "使用" else "本次使用") }
                        Box {
                            TextButton(enabled=!busy,contentPadding=PaddingValues(horizontal=if(compact)8.dp else 12.dp),onClick={more=true}) {Text("更多")}
                            DropdownMenu(expanded=more,onDismissRequest={more=false}) {
                                DropdownMenuItem(enabled=s.customEnvelopeThemes.size<EnvelopeThemes.MAX_CUSTOM,text={Text("新建样式")},onClick={more=false;editing=current.copy(id="custom-"+UUID.randomUUID(),name="我的信封");notice=null})
                                if(current.id.startsWith("custom-")) {
                                    DropdownMenuItem(text={Text("编辑样式")},onClick={more=false;editing=current;notice=null})
                                    DropdownMenuItem(text={Text("删除样式")},onClick={more=false;change { old->old.copy(customEnvelopeThemes=old.customEnvelopeThemes.filterNot{it.id==current.id},defaultEnvelopeTheme=if(old.defaultEnvelopeTheme==current.id) "ink-white" else old.defaultEnvelopeTheme) };selectedId="ink-white"})
                                }
                                DropdownMenuItem(text={Text("恢复默认")},onClick={more=false;change{it.copy(defaultEnvelopeTheme="ink-white",aiEnvelopeChoice=false)};selectedId="ink-white"})
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun ThemeEditor(theme: EnvelopeSnapshot,onChange:(EnvelopeSnapshot)->Unit,onValidity:(Boolean)->Unit,modifier:Modifier=Modifier) {
    var valid by remember(theme.id) {mutableStateOf(mapOf("envelope" to true,"paper" to true,"seal" to true))}
    LaunchedEffect(theme.id) {onValidity(true)}
    fun checked(key:String,okay:Boolean) {valid=valid+(key to okay);onValidity(valid.values.all{it})}
    Column(modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(theme.name,{onChange(theme.copy(name=it.take(40)))},label={Text("样式名称")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(theme.envelope)).envelopeTexture(theme).padding(16.dp)) {
            EnvelopeArtwork(theme,false,Modifier.fillMaxWidth().height(70.dp))
            Box(Modifier.fillMaxWidth().padding(top=14.dp).background(Color(theme.paper),RoundedCornerShape(10.dp)).letterPaper(theme).padding(14.dp)) {
                Text("见字如面。\n把此刻想说的话，留在这封信里。",color=Color(theme.ink),fontSize=15.sp,lineHeight=25.sp)
            }
        }
        ColorField("信封底色",theme.envelope,{checked("envelope",it)}) {onChange(theme.copy(envelope=it,envelopeInk=EnvelopeThemes.inkOn(it)))}
        ColorField("信纸颜色",theme.paper,{checked("paper",it)}) {onChange(theme.copy(paper=it,ink=EnvelopeThemes.inkOn(it)))}
        ColorField("封蜡颜色",theme.seal,{checked("seal",it)}) {onChange(theme.copy(seal=it))}
        Choice("信封盖",listOf("v" to "三角","rounded" to "弧形","straight" to "平口"),theme.flap){onChange(theme.copy(flap=it))}
        Choice("封蜡图案",EnvelopeThemes.SEALS.map{it to it},theme.sealMark){onChange(theme.copy(sealMark=it))}
        Choice("装饰",listOf("none" to "无","sakura" to "花瓣","clouds" to "云","stars" to "星星","moon" to "月亮","leaves" to "叶子","sun" to "太阳","shells" to "贝壳","snow" to "雪花","maple" to "秋叶","stamp" to "邮戳","lace" to "蕾丝","aurora" to "极光","cherries" to "樱桃",EnvelopeThemes.FROST_FIBER to "雾光织纹"),theme.decoration){onChange(theme.copy(decoration=it))}
        Choice("信纸纹理",listOf("plain" to "素纸","lined" to "横线","dots" to "点阵"),theme.paperPattern){onChange(theme.copy(paperPattern=it))}
        Text("文字颜色随底色调整；样式只影响外观，不改变 TA 的性格和内容。",fontSize=12.sp)
    }
}

@Composable
private fun ColorField(label:String,color:Long,onValidity:(Boolean)->Unit,onChange:(Long)->Unit) {
    var hex by remember(color) {mutableStateOf("%06X".format(color and 0xFFFFFF))}
    OutlinedTextField(hex,{raw->
        hex=raw.removePrefix("#").take(6)
        onValidity(hex.length==6 && hex.toLongOrNull(16)!=null)
        if(hex.length==6) hex.toLongOrNull(16)?.let {onChange(it or 0xFF000000)}
    },label={Text("$label · HEX")},prefix={Text("#")},singleLine=true,isError=hex.length!=6 || hex.toLongOrNull(16)==null,
        leadingIcon={Box(Modifier.size(22.dp).background(Color(color),RoundedCornerShape(6.dp)).border(.5.dp,MaterialTheme.colorScheme.outline,RoundedCornerShape(6.dp)))},modifier=Modifier.fillMaxWidth())
}

@Composable
private fun Choice(label:String,options:List<Pair<String,String>>,value:String,onChange:(String)->Unit) {
    Text(label,fontSize=13.sp)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        options.forEach { (id,name)->FilterChip(selected=id==value,onClick={onChange(id)},label={Text(name)}) }
    }
}
