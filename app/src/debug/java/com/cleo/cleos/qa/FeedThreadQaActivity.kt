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

/** Entirely synthetic Room/HTTP acceptance, with no external requests or real feed access. */
class FeedThreadQaActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && applicationInfo.flags and 2 != 0)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = JSONObject(); val checks = mutableListOf<String>()
            try { exercise(checks); result.put("ok", true) } catch (e: Throwable) { result.put("ok", false).put("error", e.stackTraceToString()) }
            File(filesDir, "feed-thread-qa-result.json").writeText(result.put("checks", JSONArray(checks)).put("externalModelRequests", 0).toString(2))
            withContext(Dispatchers.Main) { finish() }
        }
    }
    private suspend fun exercise(checks: MutableList<String>) {
        val db = Room.inMemoryDatabaseBuilder(applicationContext, AppDatabase::class.java).build()
        val settings = SettingsRepository(applicationContext); val secrets = SecretStore(applicationContext)
        val base = "https://feed-thread-qa295.invalid/v1"
        var automatic = true; var autoTarget = "friend"; var calls = 0; val payloads = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++; payloads += Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val answer = if (automatic) """{"action":"reply","content":"SYNTHETIC_REPLY_$calls","postId":1,"replyTo":"$autoTarget","like":false}"""
                else """{"content":"MANUAL_REPLY","like":false}"""
            val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", answer)).put("finish_reason", "stop")))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: $event\n\ndata: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val images = ImageStore(applicationContext); val feed = FeedRepository(db, images)
        val ai = FeedAi(db, settings, secrets, ChatClient(http, diagnostic = {}), FeedNews(http), feed, images)
        try {
            check(secrets.key(base) == null); secrets.setKey(base, "SYNTHETIC_ONLY")
            for (id in listOf(7L, 8L, 9L)) db.companions().insert(CompanionEntity(id = id, name = "合成角色$id", apiBaseUrl = base, apiModel = "synthetic", createdAt = 1))
            val comments = listOf(FeedComment("human", 0, "USER_ANCHOR", 1), FeedComment("self", 7, "SELF_REPLY", 2, "human"),
                FeedComment("noise", 9, "UNRELATED_NOISE", 3), FeedComment("friend", 8, "FRIEND_REPLY", 4, "self"))
            var post = FeedPostEntity(id = 1, authorId = 0, content = "POST_ANCHOR", createdAt = System.currentTimeMillis(), comments = FeedComments.encode(comments))
            post = post.copy(interactions = FeedInteractions.record(post, 7, "human", false))
            post = post.copy(interactions = FeedInteractions.record(post, 7, null, false))
            db.feed().insert(post)
            ai.visit(7, false, false, allowed = { true }, onRequest = {}, claimPost = { false })
            val updated = db.feed().get(1)!!
            val added = FeedComments.decode(updated.comments).last()
            check(added.replyTo == "friend" && added.authorId == 7L && added.content == "SYNTHETIC_REPLY_1")
            check(payloads.single().contains("USER_ANCHOR") && payloads.single().contains("POST_ANCHOR") && !payloads.single().contains("UNRELATED_NOISE"))
            checks += "automatic request carries original post and real ancestry; saves correct reply target"
            check(FeedComments.decode(updated.comments).dropLast(1) == comments)
            checks += "existing comments preserved exactly"
            val next = FeedComment("next", 8, "ANOTHER_REPLY", 5, added.id)
            db.feed().update(updated.copy(comments = FeedComments.encode(FeedComments.decode(updated.comments) + next)))
            autoTarget = "next"
            ai.visit(7, false, false, allowed = { true }, onRequest = {}, claimPost = { false })
            val continued = db.feed().get(1)!!
            val latestComment = FeedComments.decode(continued.comments).last()
            check(calls == 2 && latestComment.replyTo == "next" && FeedComments.decode(continued.comments).count { it.authorId == 7L } == 3)
            checks += "automatic comments continue beyond two own comments with correct target"
            val manual = FeedComment("manual", 8, "MANUAL_TARGET", 6, latestComment.id)
            db.feed().update(continued.copy(comments = FeedComments.encode(FeedComments.decode(continued.comments) + manual)))
            automatic = false
            ai.interact(1, 7, "manual")
            check(calls == 3 && FeedComments.decode(db.feed().get(1)!!.comments).last().content == "MANUAL_REPLY")
            checks += "explicit invitation preserves the designated reply target"
            feed.comment(1, "NEW_HUMAN_COMMENT")
            check(FeedVisitRules.targets(listOf(db.feed().get(1)!!), 7, System.currentTimeMillis()).single().replyTo == FeedComments.decode(db.feed().get(1)!!.comments).last().id)
            checks += "new human activity reopens automatic interaction"
        } finally {
            secrets.setKey(base, null); db.close(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
}
