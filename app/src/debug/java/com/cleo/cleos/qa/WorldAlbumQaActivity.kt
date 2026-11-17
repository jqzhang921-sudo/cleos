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

/** Emulator-only isolated Room + synthetic pixels/API interceptor. Never reads the user's album. */
class WorldAlbumQaActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checks = mutableListOf<String>()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && applicationInfo.flags and 2 != 0)
        scope.launch {
            val result = JSONObject()
            try { exercise(); result.put("ok", true) }
            catch (e: Throwable) { result.put("ok", false).put("error", e.stackTraceToString()) }
            File(filesDir, "world-album-qa-result.json").writeText(result.put("checks", JSONArray(checks)).put("externalModelRequests", 0).toString(2))
            withContext(Dispatchers.Main) { finish() }
        }
    }
    private suspend fun exercise() {
        val db = Room.inMemoryDatabaseBuilder(applicationContext, AppDatabase::class.java).build()
        val secrets = SecretStore(applicationContext)
        val base = "https://album-qa301.invalid/v1"
        val owner = CompanionEntity(id = 990301, name = "Synthetic album", createdAt = 1, apiBaseUrl = base, apiModel = "synthetic")
        val other = owner.copy(id = 990302)
        val photo = WorldAlbumPhotoEntity(1, owner.id, "album-00000000-0000-0000-0000-000000000001.jpg", 1, 1, "a".repeat(64), "CAPTION_A", 1)
        val requests = mutableListOf<String>()
        var choice: Long? = 1L
        var note = "OLD_VIEW_A"
        var action = "seen"
        var reject = false
        var slow = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.host == "album-qa301.invalid")
            val raw = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            synchronized(requests) { requests.add(raw) }
            val tool = JSONObject(raw).getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name")
            val choosing = tool == "world_choose_album"
            if (!choosing && slow) Thread.sleep(1200)
            if (!choosing && reject) Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("rejected")
                .body("""{"error":{"message":"image input is not supported"}}""".toResponseBody("application/json".toMediaType())).build()
            else {
                val args = if (choosing) JSONObject().put("photo_id", choice ?: JSONObject.NULL)
                    else JSONObject().put("action", action).put("note", if (action == "pause") "" else note).put("question", "")
                val call = JSONObject().put("index", 0).put("id", "qa-${requests.size}").put("type", "function")
                    .put("function", JSONObject().put("name", tool).put("arguments", args.toString()))
                val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(call))).put("finish_reason", "tool_calls")))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("data: $event\n\ndata: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
            }
        }.build()
        val library = WorldLibrary(db, secrets, WorldReader(ChatClient(http, diagnostic = {})), scope, occupied = { false },
            albumPixels = { "data:image/jpeg;base64,/9j/2Q==" })
        val scheduler = WorldAlbumScheduler(applicationContext, db, secrets, library, scope)
        suspend fun attempts() = db.world().state(owner.id)?.attempts ?: 0
        suspend fun manual() {
            library.startAlbum(owner.id, owner.createdAt, photo)
            withTimeout(15000) { while (owner.id in library.busy.value) delay(10) }
        }
        try {
            check(secrets.key(base) == null); secrets.setKey(base, "SYNTHETIC_ONLY")
            db.companions().insert(owner); db.companions().insert(other); db.worldAlbum().add(photo)
            library.browseAlbumInBackground(owner.id, owner.createdAt) { true }
            check(requests.size == 2 && db.worldAlbum().looks(owner.id).single().content == note && attempts() == 1)
            checks += "unseen photo is chosen, actually viewed and confirmed once"
            val state = db.world().state(owner.id)
            repeat(3) { library.browseAlbumInBackground(owner.id, owner.createdAt) { true } }
            check(requests.size == 2 && db.world().state(owner.id) == state && db.worldAlbum().looks(owner.id).size == 1)
            checks += "repeated automatic opportunities with no unseen photos use no network or quota"
            db.worldAlbum().add(photo.copy(id = 2, companionId = other.id, file = "other.jpg"))
            db.worldAlbum().add(WorldAlbumLookEntity(companionId = other.id, photoId = 2, photoSha256 = photo.sha256, runId = "other", content = "OTHER_OWNER_CANARY", question = "", createdAt = 2))
            db.worldAlbum().add(photo.copy(id = 3, sha256 = "b".repeat(64), file = "different.jpg"))
            db.worldAlbum().add(WorldAlbumLookEntity(companionId = owner.id, photoId = 3, photoSha256 = "b".repeat(64), runId = "different", content = "OTHER_PHOTO_CANARY", question = "", createdAt = 2))
            note = "REVISIT_A"; manual()
            check(requests.size == 3 && db.worldAlbum().looks(owner.id).size == 3 && attempts() == 2)
            check(requests.last().contains("OLD_VIEW_A") && !requests.last().contains("OTHER_OWNER_CANARY") && !requests.last().contains("OTHER_PHOTO_CANARY"))
            checks += "manual revisit still works and only carries the same TA's same-pixel confirmed memory"
            db.worldAlbum().add(photo.copy(id = 4, file = "duplicate.jpg", createdAt = 2))
            library.browseAlbumInBackground(owner.id, owner.createdAt) { true }
            check(requests.size == 3 && attempts() == 2)
            checks += "duplicate import does not reset the automatic seen state"
            val fresh = photo.copy(id = 5, sha256 = "c".repeat(64), file = "fresh.jpg", caption = "FRESH_PHOTO")
            db.worldAlbum().add(fresh); choice = null
            library.browseAlbumInBackground(owner.id, owner.createdAt) { true }
            check(requests.size == 4 && db.worldAlbum().looks(owner.id).size == 3)
            check(requests.last().contains("FRESH_PHOTO") && !requests.last().contains("CAPTION_A"))
            checks += "new photos become candidates and the model may decline without creating a view"
            choice = 5; note = ""; library.browseAlbumInBackground(owner.id, owner.createdAt) { true }
            check(requests.size == 6 && db.worldAlbum().looks(owner.id).any { it.photoId == 5L && it.content.isEmpty() })
            library.browseAlbumInBackground(owner.id, owner.createdAt) { true }; check(requests.size == 6)
            checks += "confirmed viewing without a comment is remembered and not automatically repeated"
            db.world().put(WorldReadingStateEntity(owner.id, LocalDate.now().toEpochDay(), 0))
            val failed = photo.copy(id = 6, sha256 = "d".repeat(64), file = "fail.jpg")
            db.worldAlbum().add(failed); choice = 6; reject = true
            library.browseAlbumInBackground(owner.id, owner.createdAt) { true }
            check(requests.size == 8 && attempts() == 0 && db.worldAlbum().looks(owner.id).none { it.photoId == 6L })
            check(WorldAlbumConsent.candidates(db.worldAlbum().photos(owner.id), db.worldAlbum().looks(owner.id)).single().id == 6L)
            checks += "failed actual viewing refunds quota and does not falsely mark pixels seen"
            reject = false
            library.browseAlbumInBackground(owner.id, owner.createdAt) { false }
            check(requests.size == 8 && attempts() == 0)
            checks += "revoked permission prevents model requests"
            db.worldAlbum().deletePhoto(6, owner.id)
            scheduler.setEnabled(owner, true)
            val due = db.worldAlbum().schedule(owner.id)!!.copy(nextAt = System.currentTimeMillis() - 1)
            db.worldAlbum().put(due); check(scheduler.run(owner.id, due.revision, due.nextAt))
            check(requests.size == 8 && attempts() == 0 && db.worldAlbum().schedule(owner.id)!!.nextAt > due.nextAt)
            check(scheduler.run(owner.id, due.revision, due.nextAt)); check(requests.size == 8)
            checks += "real scheduler advances idle tickets without network, quota or stale-worker replay"
            slow = true; note = "CANCELLED_VIEW"; library.startAlbum(owner.id, owner.createdAt, photo)
            withTimeout(10000) { while (requests.size < 9) delay(10) }; library.cancel(owner.id)
            withTimeout(10000) { while (owner.id in library.busy.value) delay(10) }
            check(attempts() == 0 && db.worldAlbum().looks(owner.id).none { it.content == "CANCELLED_VIEW" })
            checks += "cancelled revisit refunds quota and preserves confirmed history"
        } finally {
            scheduler.stopAll(); library.stopAll(); secrets.setKey(base, null); db.close()
            http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
}
