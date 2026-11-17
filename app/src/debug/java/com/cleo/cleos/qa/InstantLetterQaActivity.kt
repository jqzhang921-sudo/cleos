package com.cleo.cleos.qa

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.net.Uri
import com.cleo.cleos.CleosApp
import com.cleo.cleos.ai.*
import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/** Emulator-only, debug-only acceptance driver. Its transport intercepts every request locally. */
class InstantLetterQaActivity : Activity() {
    private val job = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val passed = mutableListOf<String>()
    private fun mark(name: String) { passed += name }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(Build.HARDWARE in setOf("ranchu","goldfish") && applicationInfo.flags and 2 != 0) { "QA only runs on a debuggable emulator" }
        job.launch {
            val result = JSONObject()
            try { exercise(); result.put("ok",true) }
            catch (e: Throwable) { result.put("ok",false).put("error",e.stackTraceToString()) }
            result.put("checks",JSONArray(passed)).put("externalModelRequests",0)
            File(filesDir,"instant-letter-qa-result.json").writeText(result.toString(2))
            withContext(Dispatchers.Main) { finish() }
        }
    }

    private suspend fun exercise() {
        val c = (application as CleosApp).container
        val db = c.db
        val base = "https://letter-qa285.invalid/v1"
        check(c.secrets.key(base) == null)
        val oldMcp = c.secrets.secret("mcp_servers").first()
        c.secrets.setSecret("mcp_servers","[]")
        c.secrets.setKey(base,"synthetic-only-not-a-real-key")
        c.settings.update { it.copy(tools=setOf(ToolGroup.Letters,ToolGroup.Messages),historySize=200) }
        suspend fun owner(id: Long, mode: String): CompanionEntity {
            val now=System.currentTimeMillis()
            val ta=CompanionEntity(id=id,name="Letter QA $id",apiBaseUrl=base,apiModel="letter-qa",createdAt=now,
                instantLetterMode=mode,replyWaitSeconds=0,deepThinking=false,proactive=false,freeTopicQuietOn=false,freeTopicEnabled=id in setOf(950003L,950006L))
            db.companions().insert(ta)
            db.conversations().insert(ConversationEntity(id,"Letter QA",now,now,id))
            return ta
        }
        val requested=owner(950001,InstantLetterRules.REQUESTED)
        val automatic=owner(950002,InstantLetterRules.AUTO)
        val wakeOwner=owner(950003,InstantLetterRules.AUTO)
        val rollbackOwner=owner(950004,InstantLetterRules.AUTO)
        val interruptedOwner=owner(950006,InstantLetterRules.AUTO)
        val clock=System.currentTimeMillis()
        val sender=InstantLetters(db) { clock }
        val request=db.messages().insert(MessageEntity(conversationId=requested.id,role="user",content="给我写一封信",createdAt=clock,letterRequested=true))
        val first=sender.sendAi(requested.id,requested.createdAt,requested.id,"${requested.id}:direct:1",request,"第一封","DIRECT_BODY","default",false)
        check(first.fresh)
        check(!sender.sendAi(requested.id,requested.createdAt,requested.id,"${requested.id}:direct:1",request,"不会覆盖","DIFFERENT_BODY","default",false).fresh)
        check(db.letters().get(first.letterId)?.content=="DIRECT_BODY")
        check(runCatching { sender.sendAi(requested.id,requested.createdAt,requested.id,"${requested.id}:direct:2",request,"重复请求","SHOULD_NOT_SEND","default",false) }.isFailure)
        mark("Room transactions deduplicate delivery origins and already fulfilled requests")

        val beforeRollback=db.letters().all().size
        val beforeMessages=db.messages().forFavorite(rollbackOwner.id).size
        check(runCatching { sender.sendAi(rollbackOwner.id,rollbackOwner.createdAt,rollbackOwner.id,"${rollbackOwner.id}:rollback",null,"","ROLLBACK_BODY","default",false) { _,at ->
            db.messages().insert(MessageEntity(conversationId=rollbackOwner.id,role="tool",content="temporary",createdAt=at));error("forced rollback")
        } }.isFailure)
        check(db.letters().all().size==beforeRollback && db.messages().forFavorite(rollbackOwner.id).size==beforeMessages && db.letterDispatches().forOwner(rollbackOwner.id).isEmpty())
        mark("Failed before-delivery callback rolls back letter envelope tool receipts and quota together")
        val race=coroutineScope {
            (1..2).map { n -> async { runCatching { InstantLetters(db) { clock }.sendAi(automatic.id,automatic.createdAt,automatic.id,"${automatic.id}:race:$n",null,"","AUTO_BODY","default",false) } } }.awaitAll()
        }
        check(race.count { it.isSuccess }==1 && db.letterDispatches().forOwner(automatic.id).size==1)
        val autoLetter=db.letterDispatches().forOwner(automatic.id).single().letterId
        c.letters.delete(autoLetter)
        check(db.letterDispatches().forOwner(automatic.id).size==1)
        check(runCatching { sender.sendAi(automatic.id,automatic.createdAt,automatic.id,"${automatic.id}:after-delete",null,"","NO_QUOTA_RESET","default",false) }.isFailure)
        mark("Concurrent automatic requests serialize quotas and deletion cannot reset cooldown")
        val request2=db.messages().insert(MessageEntity(conversationId=automatic.id,role="user",content="再给我写一封信",createdAt=clock+1,letterRequested=true))
        check(sender.sendAi(automatic.id,automatic.createdAt,automatic.id,"${automatic.id}:requested",request2,"","REQUEST_BYPASSES_COOLDOWN","default",false).fresh)
        mark("Actual user request bypasses automatic cooldown and rolling-day cap")

        val scenario=AtomicReference("mixed")
        val onTransport=AtomicReference<(() -> Unit)?>(null)
        val requests=AtomicInteger()
        val observed=java.util.Collections.synchronizedList(mutableListOf<Set<String>>())
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val payload=JSONObject(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
            onTransport.get()?.invoke()
            val tools=payload.optJSONArray("tools") ?: JSONArray()
            observed += (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
            val n=requests.incrementAndGet()
            val messages=payload.getJSONArray("messages")
            val afterTool=messages.getJSONObject(messages.length()-1).getString("role")=="tool"
            fun function(index:Int,name:String,args:String)=JSONObject().put("index",index).put("id","qa-$n-$index").put("type","function")
                .put("function",JSONObject().put("name",name).put("arguments",args))
            val delta=JSONObject()
            var finish="stop"
            if (scenario.get()=="raw") delta.put("content","""{"message_type":"LETTER","content":"RAW_INTERNAL_SHOULD_NOT_SHOW"}""")
            else if (afterTool) delta.put("content","这次没有寄出信件，我们可以正常聊。")
            else {
                delta.put("content","PREFACE_MUST_NOT_DUPLICATE")
                val args=if(scenario.get()=="invalid") """{"content":[1,2]}""" else """{"title":"模型来信","content":"PIPELINE_BODY_UNIQUE\n\n正文第二段。","theme_id":"default"}"""
                val calls=JSONArray().put(function(0,"write_instant_letter",args))
                if(scenario.get()=="mixed") calls.put(function(1,"send_message","""{"text":"DUPLICATE_MUST_NOT_SEND"}""")).put(function(2,"request_call","""{"reason":"MUST_NOT_CALL"}"""))
                delta.put("tool_calls",calls);finish="tool_calls"
            }
            val event=JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",delta).put("finish_reason",JSONObject.NULL)))
            val end=JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",JSONObject()).put("finish_reason",finish)))
            val bytes="data: $event\n\ndata: $end\n\ndata: [DONE]\n\n"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val fakeClient=ChatClient(http,diagnostic={})
        val fakeRecaps=Recaps(db,c.settings,c.secrets,fakeClient,job)
        val repository=ChatRepository(db,c.settings,c.secrets,fakeClient,c.tools,c.images,c.companions,fakeRecaps,c.mcp,c.transcriber,c.speaker,job)
        suspend fun send(owner: Long, text: String, kind: String, explicit: Boolean = false) {
            scenario.set(kind)
            val before=db.messages().forFavorite(owner).lastOrNull()?.id ?: 0
            check(repository.send(owner,text,letterRequested=explicit))
            withTimeout(30_000) {
                while(true) {
                    delay(100)
                    val rows=db.messages().forFavorite(owner)
                    if(rows.any { it.id>before && it.role=="assistant" } && !repository.busy(owner)) break
                }
            }
        }
        try {
            c.settings.update { it.copy(voiceBaseUrl=base,voiceModel="synthetic",speechEngine="api",speechBaseUrl=base,speechModel="synthetic",speechVoice="alloy",persistentIncomingAlerts=false) }
            val callOwner=requested.copy(freeTopicEnabled=true,incomingCallsEnabled=true,incomingCallMode="limited",incomingCallIntervalMinutes=1440,incomingCallDailyMax=1)
            db.companions().update(callOwner)
            val calls=IncomingCalls(this,db,c.settings,c.secrets,repository,c.notifier,job,visible={true},start={error("Never accept a QA invitation")})
            calls.restored()
            val limited=calls.request(requested.id,requested.id,"合成来电验收")
            calls.dismiss(limited.id)
            check(runCatching { calls.request(requested.id,requested.id,"受限重试") }.isFailure)
            db.companions().update(callOwner.copy(incomingCallMode="anytime"))
            val anytime=calls.request(requested.id,requested.id,"随时档立即邀请")
            check(runCatching { calls.request(requested.id,requested.id,"不能并发邀请") }.isFailure)
            calls.dismiss(anytime.id)
            db.companions().update(callOwner.copy(incomingCallMode="anytime",freeTopicQuietOn=true,freeTopicQuietStart=0,freeTopicQuietEnd=0))
            check(runCatching { calls.request(requested.id,requested.id,"免打扰仍然生效") }.isFailure)
            calls.pause()
            db.companions().update(requested)
            mark("Actual incoming-call service switches immediately from capped to anytime while preserving quiet and invitation exclusivity")
            verifyDiscovery(db,requested.id,observed){text->send(requested.id,text,"letter")}
            val initial=db.letterDispatches().forOwner(requested.id).size
            val n=requests.get()
            send(requested.id,"你也给我写一封","mixed")
            check(ToolSpecs.writeInstantLetter.name in observed.last())
            val sent=db.letters().allFor(requested.id).first { it.content.startsWith("PIPELINE_BODY_UNIQUE") }
            val rows=db.messages().forFavorite(requested.id)
            check(db.letterDispatches().forOwner(requested.id).size==initial+1 && requests.get()==n+1)
            check(rows.count{it.letterId==sent.id}==1 && rows.none{it.content.contains("PREFACE_MUST") || it.content.contains("DUPLICATE_MUST")})
            check(rows.none{it.toolCalls?.contains("PIPELINE_BODY_UNIQUE")==true})
            check(db.incomingCalls().open().none { it.companionId==requested.id })
            val prompt=Prompt.messages(c.settings.current(),requested,InstantLetterData.resolve(rows,db.letters().allFor(requested.id),requested.id),java.time.ZonedDateTime.now(),setOf(ToolGroup.Letters,ToolGroup.Messages),instantLetters=true)
            check(prompt.any{it.role=="tool" && it.content.contains("已寄出")})
            check(prompt.any{it.role=="assistant" && it.content.contains("PIPELINE_BODY_UNIQUE")})
            mark("Complete ChatClient to repository tool pipeline delivers one canonical letter and suppresses mixed text and call effects")
            mark("Reported natural request without the noun 信 offers the real tool in requested-only mode without an explicit button flag")
            mark("Persisted history pairs tool results correctly and stores body only in canonical letter record")
            val beforeInvalid=db.letterDispatches().all().size
            send(requested.id,"请写一封信","invalid")
            check(db.letterDispatches().all().size==beforeInvalid)
            check(db.messages().forFavorite(requested.id).any{it.note=="信件没有寄出"})
            mark("Invalid structured output safely returns to ordinary chat without envelope or quota charge")
            send(requested.id,"请写一封信","raw")
            check(db.letterDispatches().all().size==beforeInvalid && db.messages().forFavorite(requested.id).none { it.content.contains("RAW_INTERNAL") })
            mark("Raw LETTER protocol is not displayed as a chat bubble or mistaken for a sent letter")
            send(requested.id,"来一封","letter",explicit=true)
            check(db.letterDispatches().all().size==beforeInvalid+1)
            mark("Explicit client request is recognized without relying on model supplied consent")
            db.companions().update(requested.copy(instantLetterMode="off"))
            val off=db.letterDispatches().all().size
            send(requested.id,"给我写一封信","letter",explicit=true)
            check(db.letterDispatches().all().size==off && ToolSpecs.writeInstantLetter.name !in observed.last())
            mark("Changed per-TA off mode rejects hallucinated writes even for explicit requests")
            scenario.set("letter")
            val w=repository.wake(wakeOwner.id,"沿着重要话题想想是否适合写信。",source=WakeActivityEntity.FREE)
            check(w is ChatRepository.WakeResult.Sent && w.messages.any { it.letterId!=null })
            mark("Opted-in free-topic wake returns a real letter delivery rather than an empty skipped result")
            onTransport.set {repository.typing(interruptedOwner.id,true)}
            val interrupted=try {repository.wake(interruptedOwner.id,"合成测试：不打扰正在输入的人。",source=WakeActivityEntity.FREE)}
                finally {onTransport.set(null);repository.typing(interruptedOwner.id,false)}
            check(interrupted is ChatRepository.WakeResult.Skipped && db.letterDispatches().forOwner(interruptedOwner.id).isEmpty())
            check(db.messages().forFavorite(interruptedOwner.id).none{it.role=="assistant"})
            mark("Typing that starts during model generation stops an automatic letter without fallback chatter or quota charge")

            val custom=EnvelopeThemes.builtIn[3].copy(id="custom-native-test",name="合成夜空")
            c.settings.update { it.copy(defaultEnvelopeTheme=custom.id,customEnvelopeThemes=listOf(custom),aiEnvelopeChoice=false) }
            val draft=c.instantLetters.draft(rollbackOwner.id)
            check(InstantLetterData.theme(draft.envelope)==custom)
            c.instantLetters.save(draft.id,rollbackOwner.id,"冻结自定义主题","FROZEN_CUSTOM_BODY")
            val customSent=c.instantLetters.send(draft.id,rollbackOwner.id,rollbackOwner.createdAt,rollbackOwner.id)
            val frozen=customSent.letter.envelope
            c.settings.update { it.copy(customEnvelopeThemes=listOf(custom.copy(name="改名后",decoration="moon")),defaultEnvelopeTheme="sakura") }
            check(db.letters().get(draft.id)?.envelope==frozen && InstantLetterData.theme(frozen)==custom)
            check(runCatching { c.instantLetters.chooseEnvelope(draft.id,rollbackOwner.id,"winter") }.isFailure)
            mark("Current default creates resolved draft and sent custom envelope stays frozen after theme edits and default changes")
            val another=c.instantLetters.draft(rollbackOwner.id)
            check(InstantLetterData.theme(another.envelope)?.id=="sakura")
            c.instantLetters.chooseEnvelope(another.id,rollbackOwner.id,"starry")
            check(InstantLetterData.theme(db.letters().get(another.id)?.envelope)?.id=="starry")
            c.letters.delete(another.id)
            check(runCatching { c.instantLetters.sendAi(rollbackOwner.id,rollbackOwner.createdAt,rollbackOwner.id,"${rollbackOwner.id}:choice-denied",null,"","DENIED_BODY","starry",false) }.isFailure)
            c.settings.update {it.copy(aiEnvelopeChoice=true)}
            val aiThemed=c.instantLetters.sendAi(rollbackOwner.id,rollbackOwner.createdAt,rollbackOwner.id,"${rollbackOwner.id}:choice-allowed",null,"","THEMED_AI_BODY","starry",false)
            check(InstantLetterData.theme(db.letters().get(aiThemed.letterId)?.envelope)?.id=="starry")
            mark("Per-letter choice does not change default and AI can choose installed themes only after user opt-in")
            val archive=File(cacheDir,"instant-letter-qa.zip")
            c.backup.export(Uri.fromFile(archive))
            val decoded=ZipFile(archive).use { z -> Json{ignoreUnknownKeys=true}.decodeFromString<BackupFile>(z.getInputStream(z.getEntry("backup.json")).reader().readText()) }
            // Captured only on the owned, isolated emulator for Android -> desktop contract tests.
            archive.copyTo(File(filesDir,"instant-letter-current-backup.zip"),overwrite=true)
            check(decoded.version==32 && decoded.letterDispatches.size==db.letterDispatches().all().size)
            check(decoded.settings.customEnvelopeThemes==c.settings.current().customEnvelopeThemes && decoded.settings.defaultEnvelopeTheme=="sakura" && decoded.settings.aiEnvelopeChoice)
            val kept=db.letterDispatches().all().sortedBy{it.origin}
            c.backup.restore(Uri.fromFile(archive))
            check(db.letterDispatches().all().sortedBy{it.origin}==kept)
            check(db.letters().get(sent.id)?.envelope==sent.envelope)
            check(db.letters().get(draft.id)?.envelope==frozen && c.settings.current().defaultEnvelopeTheme=="sakura")
            check(c.settings.current().customEnvelopeThemes.single().name=="改名后")
            mark("Actual backup export and restore retain receipt quotas canonical references and frozen envelope")
            archive.delete()
            c.settings.setCurrentCompanion(requested.id);c.settings.setCurrentConversation(requested.id)
            db.companions().update(requested.copy(instantLetterMode="requested",freeTopicEnabled=true,incomingCallsEnabled=true,incomingCallMode="anytime",freeTopicQuietOn=false))
        } finally {
            repository.stopRepliesOf(requested.id);repository.stopRepliesOf(automatic.id);repository.stopRepliesOf(wakeOwner.id)
            c.secrets.setKey(base,null);c.secrets.setSecret("mcp_servers",oldMcp)
            http.dispatcher.executorService.shutdown();http.connectionPool.evictAll()
        }
    }

    private suspend fun verifyDiscovery(db:AppDatabase,owner:Long,observed:MutableList<Set<String>>,send:suspend (String)->Unit) {
        db.messages().insert(MessageEntity(conversationId=owner,role="assistant",content="我没有能寄信的入口",createdAt=System.currentTimeMillis()))
        val before=db.letterDispatches().forOwner(owner).size
        send("你现在有个letter相关的工具吧")
        check(ToolSpecs.writeInstantLetter.name in observed.last())
        check(db.letterDispatches().forOwner(owner).size==before)
        check(db.messages().forFavorite(owner).any{it.role=="assistant" && it.content=="我没有能寄信的入口"})
        mark("Capability questions in old denial history keep the tool visible but cannot create an unsolicited letter or consume quota")
    }
}
