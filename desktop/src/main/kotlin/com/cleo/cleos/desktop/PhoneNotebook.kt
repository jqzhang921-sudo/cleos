package com.cleo.cleos.desktop

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.LocalDate

internal sealed interface NotebookBlock {
    data class Text(val text:String):NotebookBlock
    data class Picture(val file:String):NotebookBlock
    data class Notice(val text:String):NotebookBlock
}
internal data class NotebookDiary(val id:Long,val day:Long,val title:String,val author:String,val secret:Boolean,val locked:Boolean,
    val publicHint:String,val sharedExcerpt:String,val blocks:List<NotebookBlock>,val limited:Boolean=false) {
    val excerpt get()=if(locked)publicHint else blocks.filterIsInstance<NotebookBlock.Text>().joinToString(" ") {it.text}.replace(Regex("\\s+")," ").take(180)
    fun matches(query:String)=query.isBlank() || listOf(title,author,excerpt,publicHint,sharedExcerpt).any {it.contains(query,true)} ||
        blocks.filterIsInstance<NotebookBlock.Text>().any {it.text.contains(query,true)}
}
internal data class NotebookTodo(val id:Long,val title:String,val note:String,val done:Boolean,val dueDay:Long?,val limited:Boolean=false)
internal data class PhoneNotebook(val exportedAt:Long,val diary:List<NotebookDiary>,val todos:List<NotebookTodo>,val skippedDiary:Int,val skippedTodos:Int)

/** Read the preserved snapshot only on demand. Nothing here is persisted into model context. */
internal fun readPhoneNotebook(directory:Path,data:DesktopData,maxJsonBytes:Long=32L*1024*1024):PhoneNotebook? {
    val root=readPhoneSnapshot(directory,data,maxJsonBytes) ?:return null
    return parsePhoneNotebook(root,data.importOrigin!!)
}

/** Shared bounded, read-only access to this library's retained phone snapshot. */
internal fun readPhoneSnapshot(directory:Path,data:DesktopData,maxJsonBytes:Long=32L*1024*1024):JsonObject? {
    val origin=data.importOrigin ?:return null
    val source=directory.resolve("source");val document=source.resolve("backup.json")
    importCheck(Files.isDirectory(source,NOFOLLOW_LINKS) && Files.isRegularFile(document,NOFOLLOW_LINKS),"原始手机归档暂时找不到，请保留现有资料并检查备份。")
    importCheck(Files.size(document) in 1..maxJsonBytes,"手机归档过大或为空，暂时无法浏览；原文件保留。")
    val bytes=Files.newInputStream(document,NOFOLLOW_LINKS).use {it.readNBytes((maxJsonBytes+1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())}
    importCheck(bytes.size.toLong()<=maxJsonBytes,"手机归档超过浏览上限，原文件保留。")
    val root=try {Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject} catch(_:Exception){null}
        ?:throw ImportFailure("手机归档格式无法读取，聊天资料未更改。")
    importCheck(root.word("format")=="cleos-backup" && root.number("version") in 1..15,"手机归档版本暂不支持浏览。")
    importCheck(root.number("version").toInt()==origin.mobileVersion && root.number("exportedAt")==origin.exportedAt,"手机归档与这份资料库不对应，请检查原始备份。")
    return root
}

private fun parsePhoneNotebook(root:JsonObject,origin:ImportOrigin):PhoneNotebook {
    val settings=root["settings"] as? JsonObject
    val names=root.rows("companions").associate {it.number("id") to it.word("name").ifBlank {"TA"}}
    val diaryRows=root.rows("diary",true);val todoRows=root.rows("todos",true)
    importCheck(diaryRows.size<=30000 && todoRows.size<=30000,"日记或待办数量超过当前浏览上限，原始归档仍保留。")
    val diaryIds=mutableSetOf<Long>();val todoIds=mutableSetOf<Long>()
    var skippedDiary=0;var skippedTodos=0
    val diary=diaryRows.mapNotNull {row -> try {
        val id=row.number("id");importCheck(id>0 && diaryIds.add(id))
        val day=validDay(row.number("day"));val author=if("author" !in row)"me" else row.word("author")
        importCheck(author in setOf("me","ai"))
        val secret=notebookFlag(row,"secret");val locked=author=="ai" && secret && !notebookFlag(row,"secretShared")
        val by=if(author=="me")settings?.word("userName").orEmpty().ifBlank {"我"} else optionalNumber(row,"companionId")?.let {names[it]?:"原来的 TA"}
            ?:settings?.word("aiName").orEmpty().ifBlank {"TA（旧版记录）"}
        // Do not decode, search or preview a TA's private title, body or images before sharing.
        val decoded=if(locked)emptyList<NotebookBlock>() to false else diaryBlocks(row.word("blocks"))
        NotebookDiary(id,day,if(locked)"TA 的小秘密" else row.word("title").take(5000).ifBlank {"无标题日记"},by,secret,locked,
            row.word("publicHint").take(20000),row.word("sharedExcerpt").take(100000),decoded.first,decoded.second || (!locked && row.word("title").length>5000) || row.word("publicHint").length>20000 || row.word("sharedExcerpt").length>100000)
    } catch(_:Exception){skippedDiary++;null} }.sortedWith(compareByDescending<NotebookDiary>{it.day}.thenByDescending {it.id})
    val todos=todoRows.mapNotNull {row -> try {
        val id=row.number("id");importCheck(id>0 && todoIds.add(id))
        NotebookTodo(id,row.word("title").take(5000).ifBlank {"无标题待办"},row.word("note").take(100000),notebookFlag(row,"done"),optionalNumber(row,"dueDay")?.let(::validDay),row.word("title").length>5000 || row.word("note").length>100000)
    }catch(_:Exception){skippedTodos++;null} }.sortedWith(compareBy<NotebookTodo>{it.done}.thenBy {it.dueDay?:Long.MAX_VALUE}.thenByDescending {it.id})
    return PhoneNotebook(origin.exportedAt,diary,todos,skippedDiary,skippedTodos)
}
private fun validDay(day:Long)=day.also {LocalDate.ofEpochDay(it)}
private fun optionalNumber(row:JsonObject,key:String):Long?=if(row[key]==null || row[key] is JsonNull)null else row.number(key)
private fun notebookFlag(row:JsonObject,key:String):Boolean {
    val value=row[key] ?:return false
    return (value as? JsonPrimitive)?.takeIf {!it.isString}?.booleanOrNull ?:throw ImportFailure("记录中的可见性或状态格式不正确。")
}
private fun diaryBlocks(raw:String):Pair<List<NotebookBlock>,Boolean> {
    if(raw.length>1_000_000)return listOf(NotebookBlock.Notice("这篇内容较长，当前仅保留在原始手机归档中。")) to true
    val parsed=try {Json.parseToJsonElement(raw)}catch(_:Exception){null}
    val array=parsed as? JsonArray ?:return listOf(NotebookBlock.Text(raw.take(200000))) to (raw.length>200000)
    importCheck(array.size<=10000,"这篇日记段落过多。")
    var remaining=200000;var limited=false
    val blocks=array.map {item ->
        val block=item as? JsonObject ?:throw ImportFailure("日记段落格式不正确。")
        when(block.word("type")) {
            "text" -> {val text=block.word("text");val shown=text.take(remaining);remaining-=shown.length;if(shown.length<text.length)limited=true;NotebookBlock.Text(shown)}
            "image" -> {val name=block.word("file");if(safeMediaName(name))NotebookBlock.Picture("media/$name") else NotebookBlock.Notice("图片引用无法安全读取，原记录仍在归档中。")}
            else -> NotebookBlock.Notice("此类日记段落暂不支持显示，原记录仍在归档中。")
        }
    }
    return blocks to limited
}
