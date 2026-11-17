package com.cleo.cleos.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun MobileLetterCard(letter:MobileLetter) {
    var open by remember(letter.id){mutableStateOf(false)}
    val t=letter.envelope;val ink=Color(t.envelopeInk)
    Column(Modifier.widthIn(max=320.dp).clip(RoundedCornerShape(22.dp)).background(Color(t.envelope)).envelopeTexture(t)
        .border(1.dp,ink.copy(alpha=.14f),RoundedCornerShape(22.dp)).clickable{open=true}
        .semantics{contentDescription="拆开手机信件 ${letter.id}"}.padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
        EnvelopeArtwork(t,letter.readAt!=null,Modifier.fillMaxWidth().height(72.dp))
        Text(letter.title.ifBlank{"一封信"},color=ink,fontSize=17.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
        Text(if(letter.mine)"你寄出的 · 手机信件" else if(letter.readAt==null)"备份时未拆封 · 手机信件" else "手机信件",color=ink.copy(alpha=.7f),fontSize=11.sp)
    }
    if(open) Dialog(onDismissRequest={open=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=720.dp).fillMaxWidth().heightIn(max=720.dp),shape=RoundedCornerShape(24.dp),color=Color(t.paper)) {
            Column(Modifier.padding(22.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("手机信件 · 只读快照",color=Color(t.ink).copy(alpha=.7f),fontSize=12.sp)
                    TextButton({open=false},modifier=Modifier.semantics{contentDescription="合上手机信件"}) {Text("合上",color=Color(t.ink))}
                }
                MobileLetterReading(letter,Modifier.weight(1f))
            }
        }
    }
}

internal fun letterChunks(text:String):List<String> = buildList {
    var start=0
    while(start<text.length) {
        val count=unicodeEnd(text.substring(start,minOf(text.length,start+4001)),4000)
        val end=(start+count).coerceAtMost(text.length)
        add(text.substring(start,end));start=end
    }
}

@Composable
internal fun MobileLetterReading(letter:MobileLetter,modifier:Modifier=Modifier) {
    val t=letter.envelope;val clipboard=LocalClipboardManager.current
    val chunks=remember(letter.text){letterChunks(letter.text)}
    Surface(modifier.fillMaxWidth(),color=Color(t.paper),shape=RoundedCornerShape(18.dp)) {
        LazyColumn(Modifier.letterPaper(t).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text(letter.title.ifBlank{"一封信"},color=Color(t.ink),fontFamily=FontFamily.Serif,fontSize=21.sp,fontWeight=FontWeight.Medium) }
            item {Text(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(letter.at).atZone(ZoneId.systemDefault())),color=Color(t.ink).copy(alpha=.65f),fontSize=12.sp)}
            items(chunks) { chunk->SelectionContainer {Text(chunk,color=Color(t.ink),fontFamily=FontFamily.Serif,fontSize=16.sp,lineHeight=28.sp)} }
            item {TextButton({clipboard.setText(AnnotatedString(letter.text))}) {Text("复制整封信",color=Color(t.ink))}}
        }
    }
}
