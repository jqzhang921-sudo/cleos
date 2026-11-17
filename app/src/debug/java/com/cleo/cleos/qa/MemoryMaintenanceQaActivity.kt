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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Synthetic in-memory Room and intercepted model responses; emulator/debug only. */
class MemoryMaintenanceQaActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        check(Build.HARDWARE in setOf("ranchu","goldfish") && applicationInfo.flags and 2!=0)
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            val checks=mutableListOf<String>();val result=JSONObject()
            try{exercise(checks);result.put("ok",true)}catch(e:Throwable){result.put("ok",false).put("error",e.stackTraceToString())}
            File(filesDir,"memory-maintenance-qa-result.json").writeText(result.put("checks",JSONArray(checks)).put("externalModelRequests",0).toString(2))
            withContext(Dispatchers.Main){finish()}
        }
    }
    private suspend fun exercise(checks:MutableList<String>) {
        val db=Room.inMemoryDatabaseBuilder(applicationContext,AppDatabase::class.java).build()
        var now=100000L
        val store=MemoryStore(db){now}
        try {
            val owner=db.companions().insert(CompanionEntity(name="记忆验收",apiBaseUrl="",apiModel="fixture",createdAt=1))
            val other=db.companions().insert(CompanionEntity(name="另一位",apiBaseUrl="",apiModel="fixture",createdAt=1))
            val conv=db.conversations().insert(ConversationEntity(title="fixture",createdAt=1,updatedAt=1,companionId=owner))
            db.messages().insert(MessageEntity(conversationId=conv,role="user",content="我喜欢 Coffee",createdAt=now-1))
            val history=MemoryHistory(db.messages(),db.conversations())
            val book=MemoryBook(db.memories(),{now},history,store)
            suspend fun act(raw:String)=book.act(Json.parseToJsonElement(raw).jsonObject,owner,conv)
            act("""{"action":"remember","category":"interest","name":"口味","summary":"饮食偏好","details":["Coffee"]}""")
            var m=db.memories().allFor(owner).single()
            act("""{"action":"remember","category":"interest","name":"口味","summary":"饮食偏好","details":["coffee"]}""")
            check(db.memories().allFor(owner).size==1 && db.memoryChanges().forOwner(owner).size==1)
            act("""{"action":"update","id":${m.id},"add_detail":"Ｃｏｆｆｅｅ"}""")
            check(db.memoryChanges().forOwner(owner).size==1)
            check(runCatching {act("""{"action":"remember","category":"interest","name":"口味","summary":"不喝咖啡"}""")}.isFailure)
            check(runCatching {store.write(owner,emptyList(),listOf(m.copy(id=0)),"remember","ai")}.isFailure)
            checks+="normal chat writes skip normalized duplicate topics/details and reject conflicting same-name recreation"
            now+=10
            check(runCatching {act("""{"action":"update","id":${m.id},"summary":"不喝咖啡"}""")}.isFailure)
            act("""{"action":"update","id":${m.id},"summary":"不喝咖啡","revision":"${MemoryRules.revision(m)}"}""")
            val pending=db.memoryChanges().forOwner(owner).first{it.state=="pending"}
            check(db.memories().get(m.id)==m)
            now+=1
            act("""{"action":"update","id":${m.id},"summary":"不喝咖啡","revision":"${MemoryRules.revision(m)}"}""")
            check(db.memoryChanges().forOwner(owner).count{it.state=="pending"}==1)
            store.apply(owner,pending.id)
            val adopted=db.memories().get(m.id)!!
            check(adopted.summary=="不喝咖啡")
            now+=10;store.undo(owner,pending.id)
            val undone=db.memories().get(m.id)!!
            check(undone.summary==m.summary && undone.details==m.details && MemoryRules.revision(undone)!=MemoryRules.revision(m))
            check(runCatching {act("""{"action":"update","id":${m.id},"summary":"旧生成覆盖","revision":"${MemoryRules.revision(m)}"}""")}.isFailure)
            checks+="destructive writes require current revision; unsupported rewrites stay pending; adoption/undo preserve facts and invalidate old revision tokens"
            m=undone
            act("""{"action":"update","id":${m.id},"summary":"待采用旧建议","revision":"${MemoryRules.revision(m)}"}""")
            val stale=db.memoryChanges().forOwner(owner).first{it.state=="pending"}
            now+=10
            store.write(owner,listOf(m),listOf(m.copy(summary="用户刚刚更正",updatedAt=now)),"manual","user")
            check(runCatching {store.apply(owner,stale.id)}.isFailure)
            check(runCatching {store.write(owner,listOf(m),listOf(m.copy(summary="过期覆盖")),"update","ai")}.isFailure)
            check(db.memories().get(m.id)!!.summary=="用户刚刚更正")
            checks+="user correction wins over stale proposals and concurrent model snapshots inside real Room transactions"
            m=db.memories().get(m.id)!!
            now+=20
            val msg=db.messages().insert(MessageEntity(conversationId=conv,role="user",content="更正一下，我不喝咖啡了",createdAt=now))
            now+=10
            act("""{"action":"update","id":${m.id},"summary":"不喝咖啡了","set_details":["不喝咖啡了"],"source_message_id":$msg,"source_quote":"不喝咖啡了","revision":"${MemoryRules.revision(m)}"}""")
            m=db.memories().get(m.id)!!
            check(m.summary=="不喝咖啡了" && MemoryContext.traces(m).all{it.messageId==msg && it.quoted})
            checks+="fresh verbatim user correction is applied with exact source rather than inferred evidence"
            db.memories().update(m.copy(pinned=true))
            check(runCatching {act("""{"action":"forget","id":${m.id}}""")}.isFailure)
            check(runCatching {act("""{"action":"update","id":${m.id},"add_detail":"pinned mutation"}""")}.isFailure)
            db.memories().update(m)
            val original=m
            act("""{"action":"forget","id":${m.id}}""")
            check(db.memories().get(m.id)==null && db.memoryChanges().forOwner(owner).isEmpty())
            check(db.memoryChanges().blocks(owner).isNotEmpty())
            check(db.memoryChanges().blocks(owner).all{it.factHash.matches(Regex("[0-9a-f]{64}"))})
            check(runCatching {act("""{"action":"remember","category":"interest","name":"新的标题","summary":"不喝咖啡了","details":["不喝咖啡了"]}""")}.isFailure)
            check(runCatching {store.undo(owner,pending.id)}.isFailure)
            checks+="pins remain protected; explicit forgetting purges connected snapshots/undo and blocks exact facts under another title using hashes only"
            now+=10
            val manual=store.write(owner,emptyList(),listOf(original.copy(id=0,source="me",updatedAt=now)),"manual","user")
            val readded=MemoryRules.decode(manual.afterRows).single()
            check(MemoryRules.hashes(readded).none{hash->db.memoryChanges().blocks(owner).any{it.factHash==hash}})
            checks+="manual re-add is explicit user consent and releases only those exact forgetting hashes"
            val second=db.memories().insert(readded.copy(id=0,details=MemoryDetails.encode(listOf("不喝咖啡了","喜欢绿茶"))))
            val pair=listOf(db.memories().get(readded.id)!!,db.memories().get(second)!!)
            val merge=store.write(owner,pair,listOf(MemoryRules.merge(pair,now)),"merge","organizer","测试合并",true)
            store.apply(owner,merge.id)
            check(db.memories().allFor(owner).size==1 && MemoryDetails.decode(db.memories().get(readded.id)!!.details).contains("喜欢绿茶"))
            store.undo(owner,merge.id)
            check(db.memories().allFor(owner).map{it.id}.toSet()==pair.map{it.id}.toSet())
            check(runCatching{store.apply(other,merge.id)}.isFailure)
            checks+="merge preserves every fact and original IDs on undo; revisions never cross TA ownership"
            val foreign=db.memories().insert(readded.copy(id=0,companionId=other,name="OTHER_TA_PRIVATE_SENTINEL"))
            val base="https://memory-qa304.invalid/v1"
            val secrets=SecretStore(applicationContext)
            var requests=0
            val http=OkHttpClient.Builder().addInterceptor{chain->
                requests++
                val buffer=okio.Buffer();chain.request().body!!.writeTo(buffer);val raw=buffer.readUtf8()
                check(!raw.contains("OTHER_TA_PRIVATE_SENTINEL") && !raw.contains("source_message_id"))
                val out="""{"suggestions":[{"action":"merge","ids":[${readded.id},$second],"reason":"同一口味话题"}]}"""
                val event=JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",JSONObject().put("content",out)).put("finish_reason","stop")))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("data: $event\n\ndata: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
            }.build()
            try {
                check(secrets.key(base)==null);secrets.setKey(base,"SYNTHETIC_ONLY")
                val ta=db.companions().get(owner)!!;db.companions().update(ta.copy(apiBaseUrl=base))
                val organizer=MemoryOrganizer(db,store,SettingsRepository(applicationContext),secrets,ChatClient(http,diagnostic={}))
                val before=db.memories().allFor(owner)
                organizer.organize(owner,true)
                check(requests==1 && db.memories().allFor(owner)==before && db.memoryChanges().forOwner(owner).any{it.state=="pending"})
                checks+="intercepted model maintenance reads one TA only and creates review cards without changing facts or making external requests"
            } finally {secrets.setKey(base,null);http.dispatcher.executorService.shutdownNow();http.connectionPool.evictAll()}
            var live=db.memories().get(readded.id)!!
            repeat(105) {n->val next=live.copy(summary="记录$n",updatedAt=++now);store.write(owner,listOf(live),listOf(next),"manual","user");live=db.memories().get(live.id)!!}
            check(db.memoryChanges().forOwner(owner).size==100)
            check(db.memories().get(foreign)!=null)
            MemoryRules.validateBackup(db.memoryChanges().all(),db.memoryChanges().allBlocks(),setOf(owner,other))
            db.companions().delete(owner)
            check(db.memoryChanges().forOwner(owner).isEmpty() && db.memoryChanges().blocks(owner).isEmpty())
            checks+="history retention is bounded, backup metadata validates and deleting a TA cascades only its own maintenance data"
        } finally {db.close()}
        val name="memory-maintenance-migration.db"
        applicationContext.deleteDatabase(name)
        getDatabasePath(name).apply{parentFile!!.mkdirs()}.outputStream().use { dest->assets.open("memory-v70.db").use{it.copyTo(dest)} }
        val migrated=Room.databaseBuilder(applicationContext,AppDatabase::class.java,name).build()
        try {
            check(migrated.memories().all().single().summary=="旧版考试安排")
            check(migrated.memoryChanges().all().isEmpty() && migrated.memoryChanges().allBlocks().isEmpty())
            check(migrated.messages().get(1)!!.content=="旧版原消息" && migrated.openHelper.readableDatabase.version==71)
            checks+="Room 70-to-71 migration keeps existing memories/messages and initializes empty maintenance tables"
        } finally {migrated.close();applicationContext.deleteDatabase(name)}
    }
}
