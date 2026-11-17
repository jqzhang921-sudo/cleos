package com.cleo.cleos.qa

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.room.Room
import com.cleo.cleos.ai.*
import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Isolated emulator fixtures; neither a live model nor the app's user database is used. */
class MemoryQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(Build.HARDWARE in setOf("ranchu","goldfish") && applicationInfo.flags and 2 != 0)
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            val checks=mutableListOf<String>()
            val result=JSONObject()
            try { exercise(checks); result.put("ok",true) }
            catch(e:Throwable) { result.put("ok",false).put("error",e.stackTraceToString()) }
            File(filesDir,"memory-qa-result.json").writeText(result.put("checks",JSONArray(checks)).put("externalModelRequests",0).toString(2))
            withContext(Dispatchers.Main) { finish() }
        }
    }
    private suspend fun exercise(checks:MutableList<String>) {
        val zone=ZoneId.of("Asia/Shanghai")
        val at=LocalDate.parse("2026-11-17").atTime(12,0).atZone(zone).toInstant().toEpochMilli()
        val db=Room.inMemoryDatabaseBuilder(applicationContext,AppDatabase::class.java).build()
        try {
            val owner=db.companions().insert(CompanionEntity(name="记忆验收",apiBaseUrl="",apiModel="",createdAt=at))
            val other=db.companions().insert(CompanionEntity(name="另一位",apiBaseUrl="",apiModel="",createdAt=at))
            val c=db.conversations().insert(ConversationEntity(title="本次",createdAt=at,updatedAt=at,companionId=owner))
            val old=db.conversations().insert(ConversationEntity(title="旧聊天",createdAt=at-86400000,updatedAt=at,companionId=owner))
            val foreign=db.conversations().insert(ConversationEntity(title="别人的",createdAt=at,updatedAt=at,companionId=other))
            suspend fun msg(c:Long,text:String,role:String="user",date:Long=at,note:String?=null,error:String?=null) =
                db.messages().insert(MessageEntity(conversationId=c,role=role,content=text,createdAt=date,note=note,error=error))
            val first=msg(old,"周四考英语",date=at-86400000)
            msg(old,"那就周四之后再约",role="assistant",date=at-86400000+1)
            msg(foreign,"英语PRIVATE_SENTINEL")
            msg(c,"英语SECRET_SENTINEL",note="秘密")
            msg(c,"英语FAILURE_SENTINEL",error="失败")
            val current=msg(c,"数学考试改到下周")
            val history=MemoryHistory(db.messages(),db.conversations()) { zone }
            val book=MemoryBook(db.memories(),{at},history)
            suspend fun act(raw:String,conv:Long=c,ta:Long=owner)=book.act(Json.parseToJsonElement(raw).jsonObject,ta,conv)
            val searched=act("""{"action":"search_history","query":"英语","speaker":"user","from":"2026-11-16","to":"2026-11-16"}""").result
            check(searched.contains("周四考英语") && !searched.contains("SENTINEL") && !searched.contains("那就周四"))
            checks += "dated SQL retrieval spans this TA's conversations and excludes other TAs, secrets and failed replies"
            val context=act("""{"action":"open_message","message_id":$first}""").result
            check(context.contains("assistant") && context.contains("user") && context.contains("那就周四之后再约"))
            check(runCatching { act("""{"action":"open_message","message_id":$first}""",foreign,other) }.isFailure)
            check(runCatching { act("""{"action":"search_history","query":"英语"}""",-c) }.isFailure)
            checks += "opening original messages preserves role/date and cannot cross TA or group boundaries"
            act("""{"action":"remember","category":"recent","name":"考试","summary":"考试安排","details":["数学考试改到下周"]}""")
            var memory=db.memories().allFor(owner).single()
            check(MemoryContext.traces(memory).size==2 && MemoryContext.traces(memory).all { it.messageId==current })
            checks += "new summary and detail receive the real current private-chat anchor"
            act("""{"action":"update","id":${memory.id},"add_detail":"周四考英语","source_message_id":$first,"source_quote":"周四考英语"}""")
            memory=db.memories().get(memory.id)!!
            check(MemoryContext.traces(memory).count { it.messageId==first && it.quoted }==1)
            check(MemoryContext.traces(memory).count { it.messageId==current }==2)
            val unchanged=memory
            check(runCatching { act("""{"action":"update","id":${memory.id},"add_detail":"假的","source_message_id":$first,"source_quote":"并不存在"}""") }.isFailure)
            check(db.memories().get(memory.id)==unchanged)
            checks += "appended fact keeps its quoted old source; mismatched quote changes nothing and preserves earlier sources"
            db.messages().setContent(first,"英语考试取消了")
            check(act("""{"action":"open","id":${memory.id}}""").result.contains("原消息已变化"))
            db.conversations().delete(old)
            check(act("""{"action":"open","id":${memory.id}}""").result.contains("原消息不可用"))
            check(!act("""{"action":"search_history","query":"周四"}""").result.contains("周四考英语"))
            checks += "edited and deleted original messages invalidate provenance; deleted chat does not reappear in retrieval"
            act("""{"action":"update","id":${memory.id},"temporal_state":"past","valid_until":"2026-11-16"}""")
            check(act("""{"action":"open","id":${memory.id}}""").result.contains("过去的经历"))
            memory=db.memories().get(memory.id)!!
            check(MemoryDetails.decode(memory.details).size==2)
            db.memories().update(memory.copy(pinned=true))
            check(runCatching { act("""{"action":"update","id":${memory.id},"temporal_state":"current"}""") }.isFailure)
            checks += "past state retains facts and pinned metadata remains protected from model edits"
            val weather=object:WeatherSource { override suspend fun report(city:String,days:Int):WeatherReport=error("network forbidden") }
            val box=ToolBox(db.todos(),db.diary(),weather,memories=db.memories(),memoryHistory=history)
            val call=ToolCall("q","memory","""{"action":"search_history","query":"考试"}""")
            check(box.run(call,AppSettings(tools=emptySet()),c,owner).result.contains("关掉"))
            check(box.run(call,AppSettings(tools=setOf(ToolGroup.Memory)),foreign,owner).result.contains("私聊"))
            check("memory" !in GroupToolRules.names)
            checks += "tool permission is enforced at execution and private retrieval stays unavailable to groups"
            repeat(14) { msg(c,"检索上限 $it") }
            val bounded=act("""{"action":"search_history","query":"检索上限"}""").result
            check(Json.parseToJsonElement(bounded).jsonObject["messages"]!!.jsonArray.size==8)
            val literal=msg(c,"字面 100%_\\ 关键词")
            check(act("""{"action":"search_history","query":"100%_"}""").result.contains("字面"))
            check(literal>0)
            checks += "retrieval is bounded and SQL wildcard characters are treated literally"
        } finally { db.close() }
        val name="memory-phase1-migration-qa.db"
        applicationContext.deleteDatabase(name)
        val file=getDatabasePath(name).apply { parentFile!!.mkdirs() }
        assets.open("memory-v69.db").use { src -> file.outputStream().use(src::copyTo) }
        val migrated=Room.databaseBuilder(applicationContext,AppDatabase::class.java,name).build()
        try {
            val old=migrated.memories().all().single()
            check(old.summary=="旧版考试安排" && old.details=="[\"英语\"]" && old.pinned)
            check(old.provenance=="[]" && old.temporalState=="current" && old.validUntilDay==null && old.validFromDay==null)
            check(migrated.messages().get(1)!!.content=="旧版原消息")
            check(migrated.openHelper.readableDatabase.version==71)
            checks += "real Room 69-to-71 migration preserves old pinned memories, exact detail text and original messages without inventing provenance"
        } finally { migrated.close(); applicationContext.deleteDatabase(name) }
    }
}
