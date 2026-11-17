package com.cleo.cleos.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.pow

/** Immutable imported envelope parameters, independent of any desktop/default theme. */
@Serializable
data class MobileEnvelope(
    val id:String="ink-white",val version:Int=1,val name:String="墨白简约",
    val envelope:Long=0xFFF0EBE5,val paper:Long=0xFFFFFCF7,val ink:Long=0xFF332F35,val seal:Long=0xFF7E658D,
    val envelopeInk:Long=ink,val flap:String="v",val decoration:String="none",val sealMark:String="信",val paperPattern:String="plain",
) {
    fun checked():MobileEnvelope {
        require(id.length in 1..100 && version in 1..2 && name.length<=100)
        require(listOf(envelope,paper,ink,seal,envelopeInk).all{it in 0..0xFFFFFFFFL && it ushr 24==255L})
        require(flap in setOf("v","rounded","straight") && paperPattern in setOf("plain","lined","dots"))
        require(sealMark in setOf("信","心","星","月","叶","花"))
        require(decoration in setOf("none","sakura","clouds","stars","moon","leaves","sun","shells","snow","maple","stamp","lace","aurora","cherries","frost-fiber-v1"))
        return this
    }
    companion object {
        fun inkOn(c:Long):Long {
            fun luminance(color:Long)=listOf(16,8,0).map {n->val v=((color shr n) and 255)/255.0;if(v<=.04045)v/12.92 else ((v+.055)/1.055).pow(2.4)}.let{.2126*it[0]+.7152*it[1]+.0722*it[2]}
            val l=luminance(c)
            fun contrast(ink:Long):Double {val x=luminance(ink);return(maxOf(x,l)+.05)/(minOf(x,l)+.05)}
            return if(contrast(0xFF27232C)>=contrast(0xFFFFFCFF))0xFF27232C else 0xFFFFFCFF
        }
    }
}

@Serializable
data class MobileLetter(val id:Long,val ownerId:String,val mine:Boolean,val title:String,val text:String,
                        val at:Long,val readAt:Long?=null,val envelope:MobileEnvelope) {
    fun checked():MobileLetter { require(id>0 && ownerId.startsWith("mobile-ta-") && title.length<=200 && text.isNotBlank() && text.length<=1_000_000 && at>=0);envelope.checked();return this }
    fun context(maximum:Int=12_000):String {
        val end=unicodeEnd(text,maximum)
        return "（手机即时信件 #$id · ${if(mine)"对方寄给你的" else "你寄出的"}）\n"+
            (if(title.isBlank())"" else "$title\n")+text.take(end)+(if(end<text.length)"\n（正文较长，其余原文仍保存在信件中。）" else "")
    }
}

internal fun unicodeEnd(text:String,maximum:Int):Int=minOf(text.length,maximum).let {
    if(it>0 && it<text.length && Character.isHighSurrogate(text[it-1]) && Character.isLowSurrogate(text[it]))it-1 else it
}

/** Arrival/draft checks happen before reading title, body or envelope. */
internal fun importedInstantLetter(row:JsonObject,exportedAt:Long,owners:Set<Long>,allowOwnDraft:Boolean=false):MobileLetter? {
    if(row.word("sourceType","retrospective")!="instant")return null
    val author=row.word("author");importCheck(author in setOf("me","ai"),"即时信件的发件人无效。")
    val delivery=(row["deliverAt"] as? JsonPrimitive)?.longOrNull ?: if(allowOwnDraft && author=="me")row.number("createdAt") else return null
    if(delivery>exportedAt)return null
    val owner=row.number("companionId");importCheck(owner in owners,"即时信件的 TA 不存在。")
    try {
        val theme=Json {ignoreUnknownKeys=true}.decodeFromString<MobileEnvelope>(row.word("envelope")).checked()
        return MobileLetter(row.number("id"),"mobile-ta-$owner",author=="me",row.word("title"),row.word("content"),delivery,
            (row["readAt"] as? JsonPrimitive)?.longOrNull,theme).checked()
    } catch(_:Exception) { throw ImportFailure("即时信件内容或信封样式无效，原资料未更改。") }
}

fun DesktopData.letterFor(chat:Conversation,message:Message):MobileLetter? {
    if(chat.group || chat.memberIds.size!=1 || message.mobileLetterId==null)return null
    return mobileLetters.firstOrNull { it.id==message.mobileLetterId && it.ownerId==chat.memberIds.single() &&
        message.speakerId==(if(it.mine)Message.USER else it.ownerId) }
}
internal fun DesktopData.visibleText(chat:Conversation,m:Message):String =
    if(m.mobileLetterId==null)m.text else letterFor(chat,m)?.let {it.title.ifBlank{"一封信"}+"\n"+it.text}.orEmpty()
