package com.cleo.cleos.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.datatransfer.Transferable

@OptIn(ExperimentalComposeUiApi::class)
internal class ImageDropReceiver(
    private val enabled:()->Boolean,
    private val offer:(ImageOffer)->Unit,
    private val problem:(String)->Unit,
    private val highlight:(String?)->Unit,
    private val transferable:(DragAndDropEvent)->Transferable={it.awtTransferable},
):DragAndDropTarget {
    fun accepts(event:DragAndDropEvent)=enabled() && runCatching {supportsImageOffer(transferable(event))}.getOrDefault(false)
    private fun hover(event:DragAndDropEvent) {highlight(if(!enabled())null else if(event.action==DragAndDropTransferAction.Copy) "松开添加图片 · 不会自动发送" else "按住 Ctrl 拖入，添加图片副本")}
    override fun onEntered(event:DragAndDropEvent)=hover(event)
    override fun onChanged(event:DragAndDropEvent)=hover(event)
    override fun onExited(event:DragAndDropEvent)=highlight(null)
    override fun onEnded(event:DragAndDropEvent)=highlight(null)
    override fun onDrop(event:DragAndDropEvent):Boolean {
        highlight(null)
        if(!enabled())return false
        // Compose reports success with the source's current action. Returning true for Move
        // could let Explorer remove the source file after an asynchronous copy, so reject it.
        if(event.action!=DragAndDropTransferAction.Copy) {problem("请按住 Ctrl 拖入以复制图片，原图会保留。");return false}
        return try {
            val value=readImageOffer(transferable(event)) ?:return false
            offer(value);true
        } catch(e:Exception) {problem((e as? TextFailure)?.message?:"这次没有读到图片，请重新拖入或用「＋ 图片」选择。");false}
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable internal fun ImageDropArea(enabled:Boolean,offer:(ImageOffer)->Unit,problem:(String)->Unit,modifier:Modifier=Modifier,content:@Composable ()->Unit) {
    var hint by remember {mutableStateOf<String?>(null)}
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOffer by rememberUpdatedState(offer)
    val currentProblem by rememberUpdatedState(problem)
    val receiver=remember {ImageDropReceiver({currentEnabled},{currentOffer(it)},{currentProblem(it)},{hint=it})}
    LaunchedEffect(enabled) {if(!enabled)hint=null}
    Box(modifier.dragAndDropTarget({receiver.accepts(it)},receiver)) {
        content()
        if(hint!=null && enabled) Box(Modifier.matchParentSize().background(Color(0xffeee8f7).copy(alpha=.92f),RoundedCornerShape(28.dp))
            .border(2.dp,Color(0xff9481b1),RoundedCornerShape(28.dp)),contentAlignment=Alignment.Center) {
            Text(hint!!,color=Color(0xff5a4775),fontSize=15.sp,modifier=Modifier.padding(24.dp))
        }
    }
}
