package com.cleo.cleos.qa

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.room.Room
import com.cleo.cleos.ai.*
import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/** Separate in-memory Room database, synthetic credential and interceptor; no real model or user data. */
class WorldReadingQaActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        File(filesDir, "world-reading-qa-unhandled.txt").writeText(error.stackTraceToString())
    })
    private val checks = mutableListOf<String>()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && applicationInfo.flags and 2 != 0)
        scope.launch {
            val result = JSONObject()
            try { exercise(); exerciseRepair(); result.put("ok", true) } catch (e: Throwable) { result.put("ok", false).put("error", e.stackTraceToString()) }
            File(filesDir, "world-reading-qa-result.json").writeText(result.put("checks", JSONArray(checks)).put("externalModelRequests", 0).toString(2))
            withContext(Dispatchers.Main) { finish() }
        }
    }
    private suspend fun exercise() {
        val db = Room.inMemoryDatabaseBuilder(applicationContext, AppDatabase::class.java).build()
        val secrets = SecretStore(applicationContext)
        val base = "https://reading-qa293.invalid/v1"
        val calls = AtomicInteger()
        var span = 12000
        var pauseChoice = false
        var pauseRead = false
        var slowRead = false
        var busy = false
        var occupation = 0
        var race = false
        val diagnostics = mutableListOf<String>()
        val requests = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val raw = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            synchronized(requests) { requests.add(raw) }
            val choosing = JSONObject(raw).optJSONArray("tools")?.optJSONObject(0)?.getJSONObject("function")?.getString("name") == "world_choose_article" || raw.contains("JSON Schema")
            val args = if (choosing) {
                if (pauseChoice) """{"article_id":null}""" else """{"article_id":7,"read_characters":$span}"""
            } else {
                if (slowRead) Thread.sleep(1500)
                if (pauseRead) """{"action":"pause","note":"","question":"","quote":""}"""
                else """{"action":"read","note":"SYNTHETIC_NOTE","question":"","quote":""}"""
            }
            val function = JSONObject().put("name", if (choosing) "world_choose_article" else "world_finish_reading").put("arguments", args)
            val tool = JSONObject().put("index", 0).put("id", "qa-${calls.get()}").put("type", "function").put("function", function)
            val jsonOnly = JSONObject(raw).optJSONArray("tools")?.length().let { it == null || it == 0 }
            val delta = if (jsonOnly) JSONObject().put("content", args) else JSONObject().put("tool_calls", JSONArray().put(tool))
            val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta).put("finish_reason", if (jsonOnly) "stop" else "tool_calls")))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: $event\n\ndata: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val library = WorldLibrary(db, secrets, WorldReader(ChatClient(http, diagnostic = {})), scope,
            occupied = { occupation++; busy || (race && occupation == 2) }, diagnostic = {
                diagnostics.add(it); File(filesDir, "world-reading-qa-trace.txt").appendText(it + "\n")
            })
        val scheduler = WorldReadingScheduler(applicationContext, db, library, scope, diagnostic = { diagnostics.add(it) })
        val owner = CompanionEntity(id = 990293, name = "Synthetic reader", createdAt = 1, apiBaseUrl = base, apiModel = "synthetic-model")
        try {
            check(secrets.key(base) == null); secrets.setKey(base, "SYNTHETIC_ONLY")
            db.companions().insert(owner)
            val body = "x".repeat(80000)
            db.world().add(WorldArticleEntity(7, owner.id, "SYNTHETIC_BOOK", "fixture", body, WorldReading.fingerprint(body), 1, 11340, 2))
            check(library.readInBackground(owner.id) { true })
            check(db.world().article(7)!!.readOffset == 23340 && db.world().state(owner.id)!!.attempts == 1)
            check(requests.size == 2 && !requests[0].contains("x".repeat(100)) && requests[1].contains("x".repeat(12000)))
            checks += "model chooses 12000; one chooser and one reader; confirmed progress advances beyond 11340"
            span = 18000
            library.readInBackground(owner.id) { true }
            check(db.world().article(7)!!.readOffset == 41340 && requests.last().contains("SYNTHETIC_NOTE"))
            checks += "18000 span continues beyond 24000 with prior confirmed note"
            pauseChoice = true
            library.readInBackground(owner.id) { true }
            check(db.world().article(7)!!.readOffset == 41340 && db.world().state(owner.id)!!.attempts == 3)
            checks += "voluntary pause does not fake progress or loop"
            pauseChoice = false; span = 18001
            library.readInBackground(owner.id) { true }
            check(db.world().article(7)!!.readOffset == 41340 && db.world().state(owner.id)!!.attempts == 3)
            checks += "invalid span repair fails safely and refunds opportunity"
            span = 6000; pauseRead = true
            library.readInBackground(owner.id) { true }
            check(db.world().article(7)!!.readOffset == 41340 && db.world().state(owner.id)!!.attempts == 4)
            checks += "reader may pause after choosing; supplied text alone is not acknowledgement"
            pauseRead = false
            val ticket = WorldScheduleEntity(owner.id, 1, "synthetic-revision", System.currentTimeMillis() - 1000)
            db.world().put(ticket); busy = true
            val before = calls.get()
            check(!scheduler.run(owner.id, ticket.revision, ticket.nextAt))
            check(db.world().schedule(owner.id) == ticket && before == calls.get())
            checks += "busy preflight retains due ticket without network or quota"
            busy = false; race = true; occupation = 0
            check(scheduler.run(owner.id, ticket.revision, ticket.nextAt))
            val deferred = db.world().schedule(owner.id)!!
            check(deferred.nextAt in (System.currentTimeMillis() + 14 * 60000)..(System.currentTimeMillis() + 16 * 60000))
            check(calls.get() == before && db.world().state(owner.id)!!.attempts == 4)
            checks += "occupation race reschedules 15 minutes later without spending opportunity"
            race = false
            check(scheduler.run(owner.id, ticket.revision, ticket.nextAt))
            check(calls.get() == before)
            checks += "stale worker cannot replay deferred ticket"
            db.world().put(deferred.copy(nextAt = System.currentTimeMillis() - 1))
            val ready = db.world().schedule(owner.id)!!
            check(scheduler.run(owner.id, ready.revision, ready.nextAt))
            check(db.world().article(7)!!.readOffset == 47340 && db.world().state(owner.id)!!.attempts == 5)
            checks += "deferred reading resumes and commits exactly once"
            db.world().put(WorldReadingStateEntity(owner.id, LocalDate.now().toEpochDay(), 0))
            slowRead = true
            val launched = scope.async { library.readInBackground(owner.id) { true } }
            withTimeout(10000) { while (calls.get() < before + 4) delay(10) }
            library.cancel(owner.id); launched.await()
            check(db.world().article(7)!!.readOffset == 47340 && db.world().state(owner.id)!!.attempts == 0)
            checks += "cancelling a long read refunds quota and preserves prior confirmed offset"
        } finally {
            scheduler.stopAll(); library.stopAll(); secrets.setKey(base, null); db.close()
            http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
    private suspend fun exerciseRepair() {
        val db = Room.inMemoryDatabaseBuilder(applicationContext, AppDatabase::class.java).build()
        val secrets = SecretStore(applicationContext)
        val base = "https://reading-repair-qa298.invalid/v1"
        val owner = CompanionEntity(id = 990298, name = "Synthetic repair reader", createdAt = 1, apiBaseUrl = base, apiModel = "glm-5.3-flash")
        val previous = "Already confirmed. "
        val passage = "Англичане попросили, чтобы им серебром отпустили."
        val body = previous + passage
        val bad = """{"action":"read","note":"FAILED_NOTE_CANARY","question":"","quote":"The English asked"}"""
        val good = """{"action":"read","note":"公开笔记","question":"","quote":"Англичане попросили"}"""
        val pause = """{"action":"pause","note":"","question":"","quote":""}"""
        var repair = good
        var initial = bad
        var mutate = false
        val requests = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val raw = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            requests.add(raw)
            val json = JSONObject(raw)
            val toolName = json.optJSONArray("tools")?.optJSONObject(0)?.getJSONObject("function")?.getString("name")
            val choosing = toolName == "world_choose_article"
            val jsonOnly = toolName == null
            if (jsonOnly && mutate) runBlocking { db.world().update(db.world().article(7)!!.copy(readOffset = body.length)) }
            val args = if (choosing) """{"article_id":7}""" else if (jsonOnly) repair else initial
            val delta = if (jsonOnly) JSONObject().put("content", args) else JSONObject().put("tool_calls", JSONArray().put(
                JSONObject().put("index", 0).put("id", "fixture").put("type", "function").put("function",
                    JSONObject().put("name", toolName).put("arguments", args))))
            delta.put("reasoning_content", "PRIVATE_CANARY")
            val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta).put("finish_reason", if (jsonOnly) "stop" else "tool_calls")))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: $event\n\ndata: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val library = WorldLibrary(db, secrets, WorldReader(ChatClient(http, diagnostic = {}), { diagnostics.add(it) }), scope, occupied = { false })
        suspend fun reset() {
            requests.clear(); diagnostics.clear()
            db.world().put(WorldReadingStateEntity(owner.id, LocalDate.now().toEpochDay(), 0))
            db.world().update(WorldArticleEntity(7, owner.id, "SYNTHETIC_RUSSIAN_BOOK", "fixture", body, WorldReading.fingerprint(body), 1, previous.length, 2))
        }
        try {
            check(secrets.key(base) == null); secrets.setKey(base, "SYNTHETIC_ONLY"); db.companions().insert(owner)
            db.world().add(WorldArticleEntity(7, owner.id, "SYNTHETIC_RUSSIAN_BOOK", "fixture", body, WorldReading.fingerprint(body), 1, previous.length, 2))
            library.readInBackground(owner.id) { true }
            check(requests.size == 3 && db.world().article(7)!!.readOffset == body.length && db.world().state(owner.id)!!.attempts == 1)
            val notes = db.world().studyNotes(owner.id, listOf(7))
            check(notes.size == 1 && notes.single().quoteStart == previous.length && notes.single().quote == "Англичане попросили")
            check(!requests.last().contains("FAILED_NOTE_CANARY") && !requests.last().contains("PRIVATE_CANARY"))
            checks += "translated quote repairs once against current Cyrillic source; one quota and one exact persisted note"
            reset(); repair = bad
            library.readInBackground(owner.id) { true }
            check(requests.size == 3 && db.world().article(7)!!.readOffset == previous.length && db.world().state(owner.id)!!.attempts == 0)
            check(db.world().state(owner.id)!!.failureCode == "reading_quote" && db.world().studyNotes(owner.id, listOf(7)).size == 1)
            checks += "bad repair stops after two reader requests; quota refunded and prior progress and notes preserved"
            reset(); repair = pause
            library.readInBackground(owner.id) { true }
            check(requests.size == 3 && db.world().article(7)!!.readOffset == previous.length && db.world().state(owner.id)!!.attempts == 1)
            checks += "repair may choose pause; no new progress or note"
            reset(); repair = good; mutate = true
            library.readInBackground(owner.id) { true }
            check(requests.size == 3 && db.world().article(7)!!.readOffset == body.length && db.world().state(owner.id)!!.attempts == 0)
            check(db.world().state(owner.id)!!.failureCode == "article_changed" && db.world().studyNotes(owner.id, listOf(7)).size == 1)
            checks += "concurrent source position change during repair cannot commit stale note; quota refunded"
            reset(); mutate = false; initial = """{"action":"read","note":"","question":"","quote":""}"""
            library.readInBackground(owner.id) { true }
            check(requests.size == 2 && db.world().article(7)!!.readOffset == body.length && db.world().state(owner.id)!!.attempts == 1)
            check(db.world().studyNotes(owner.id, listOf(7)).size == 1)
            checks += "explicit empty-note read still commits without repair or fabricated quote"
        } finally {
            library.stopAll(); secrets.setKey(base, null); db.close()
            http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
}
