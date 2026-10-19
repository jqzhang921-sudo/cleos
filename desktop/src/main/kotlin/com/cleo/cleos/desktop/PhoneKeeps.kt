package com.cleo.cleos.desktop

import kotlinx.serialization.json.*
import java.nio.file.Path

internal data class KeptOwner(val key:String,val name:String)
internal data class KeptLetter(val id:Long,val owner:KeptOwner,val mine:Boolean,val draft:Boolean,val unread:Boolean,
    val at:Long,val text:String,val limited:Boolean) {
    val title get()=text.lineSequence().firstOrNull {it.isNotBlank()}?.trim()?.take(80)?:"一封信"
    fun matches(query:String)=query.isBlank() || text.contains(query,true) || owner.name.contains(query,true)
}
internal data class KeptPart(val name:String,val mine:Boolean,val at:Long,val text:String,val gapBefore:Boolean,
    val attachments:List<Attachment>)
internal data class KeptFavorite(val id:Long,val owner:KeptOwner,val at:Long,val note:String,val parts:List<KeptPart>,val limited:Boolean) {
    val title get()=parts.firstOrNull()?.text?.lineSequence()?.firstOrNull {it.isNotBlank()}?.trim()?.take(80)?:"留下的对话"
    fun matches(query:String)=query.isBlank() || note.contains(query,true) || owner.name.contains(query,true) || parts.any {it.text.contains(query,true) || it.name.contains(query,true)}
}
internal data class PhoneKeeps(val exportedAt:Long,val letters:List<KeptLetter>,val favorites:List<KeptFavorite>,val skippedLetters:Int,val skippedFavorites:Int)

/** Snapshot browsers never append these records to DesktopData or model prompts. */
internal fun readPhoneKeeps(directory:Path,data:DesktopData,maxJsonBytes:Long=32L*1024*1024):PhoneKeeps? {
    val root=readPhoneSnapshot(directory,data,maxJsonBytes) ?:return null
    val exportedAt=data.importOrigin!!.exportedAt
    val names=root.rows("companions").associate {it.number("id") to it.word("name").ifBlank {"TA"}.take(200)}
    val legacyName=(root["settings"] as? JsonObject)?.word("aiName").orEmpty().ifBlank {"TA（旧版记录）"}.take(200)
    fun owner(row:JsonObject):KeptOwner {
        val id=keptOptionalNumber(row,"companionId")
        return if(id==null)KeptOwner("legacy",legacyName) else {
            importCheck(id>0);KeptOwner(id.toString(),names[id]?:"原来的 TA")
        }
    }
    val letterRows=root.rows("letters");val favoriteRows=root.rows("favorites")
    importCheck(letterRows.size<=30000 && favoriteRows.size<=30000,"信件或收藏数量超过当前浏览上限，完整记录仍在原始归档。")
    val letterIds=hashSetOf<Long>();val favoriteIds=hashSetOf<Long>()
    var skippedLetters=0;var skippedFavorites=0
    val letters=letterRows.mapNotNull {row->try {
        val id=keptNumber(row,"id");importCheck(id>0 && letterIds.add(id))
        val author=row.word("author");importCheck(author in setOf("me","ai"))
        val delivery=keptOptionalNumber(row,"deliverAt")
        // Evaluate visibility at export time, before decoding any unseen letter body.
        if(author=="ai" && (delivery==null || delivery>exportedAt))return@mapNotNull null
        val created=keptNumber(row,"createdAt");importCheck(created>=0 && (delivery==null || delivery>=0))
        val text=row.word("content");val read=keptOptionalNumber(row,"readAt")
        KeptLetter(id,owner(row),author=="me",author=="me" && delivery==null,author=="ai" && read==null,
            delivery?:created,text.take(200000),text.length>200000)
    }catch(_:Exception){skippedLetters++;null}}.sortedWith(compareByDescending<KeptLetter>{it.at}.thenByDescending{it.id})
    val favorites=favoriteRows.mapNotNull {row->try {
        val id=keptNumber(row,"id");importCheck(id>0 && favoriteIds.add(id))
        if(keptOptionalNumber(row,"removedAt")!=null)return@mapNotNull null
        val at=keptNumber(row,"createdAt");importCheck(at>=0)
        val raw=row.word("parts");importCheck(raw.length<=1_000_000,"收藏片段过长。")
        val array=Json.parseToJsonElement(raw) as? JsonArray ?:throw ImportFailure("收藏片段格式不正确。")
        importCheck(array.size in 1..2000)
        var remaining=200000;var limited=false
        val parts=array.map {value->
            val part=value as? JsonObject ?:throw ImportFailure("收藏片段格式不正确。")
            val role=part.word("role");importCheck(role in setOf("user","assistant"))
            val time=keptNumber(part,"at");importCheck(time>=0)
            val rawText=part.word("text");val text=rawText.take(remaining);remaining-=text.length
            if(text.length<rawText.length)limited=true
            val rawName=part.word("name");if(rawName.length>200)limited=true
            KeptPart(rawName.take(200).ifBlank {if(role=="user")"我" else "TA"},role=="user",time,text,
                keptFlag(part,"gapBefore"),keptAttachments(part))
        }
        val note=row.word("note");if(note.length>20000)limited=true
        KeptFavorite(id,owner(row),at,note.take(20000),parts,limited)
    }catch(_:Exception){skippedFavorites++;null}}.sortedWith(compareByDescending<KeptFavorite>{it.at}.thenByDescending{it.id})
    return PhoneKeeps(exportedAt,letters,favorites,skippedLetters,skippedFavorites)
}

private fun keptNumber(row:JsonObject,key:String):Long = (row[key] as? JsonPrimitive)?.takeIf {!it.isString}?.longOrNull
    ?:throw ImportFailure("记录中的时间或编号格式不正确。")
private fun keptOptionalNumber(row:JsonObject,key:String):Long?=if(row[key]==null || row[key] is JsonNull)null else keptNumber(row,key)
private fun keptFlag(row:JsonObject,key:String):Boolean=if(row[key]==null)false else (row[key] as? JsonPrimitive)?.takeIf {!it.isString}?.booleanOrNull
    ?:throw ImportFailure("记录中的状态格式不正确。")
private fun keptAttachments(part:JsonObject):List<Attachment> = buildList {
    nested(part["audio"])?.let {value->
        val audio=value as? JsonObject ?:throw ImportFailure("收藏录音格式不正确。")
        val duration=keptNumber(audio,"ms");importCheck(duration>=0)
        add(Attachment(mediaPath(audio.word("file")),"audio","收藏录音 · ${(duration/1000).coerceAtLeast(1)} 秒"))
    }
    nested(part["images"])?.let {value->
        val pictures=value as? JsonArray ?:throw ImportFailure("收藏图片格式不正确。")
        importCheck(pictures.size<=100)
        pictures.forEach {image->
            val picture=image as? JsonObject ?:throw ImportFailure("收藏图片格式不正确。")
            val credit=picture["credit"]?.takeUnless {it is JsonNull}?.let {it as? JsonObject ?:throw ImportFailure("图片来源格式不正确。")}
            add(Attachment(mediaPath(picture.word("file")),label=picture.word("source").take(500),credit=credit?.let {
                listOf("title","creator","license","pageUrl","licenseUrl","previewNote").map {key->it.word(key).take(2000)}.filter(String::isNotBlank).joinToString(" · ")
            }.orEmpty()))
        }
    }
}
