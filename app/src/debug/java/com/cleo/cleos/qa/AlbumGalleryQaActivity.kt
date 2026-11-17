package com.cleo.cleos.qa

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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

/** Real MediaStore, isolated Room, generated pixels and intercepted API; emulator/debug only. */
class AlbumGalleryQaActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checks = mutableListOf<String>()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && applicationInfo.flags and 2 != 0)
        scope.launch {
            val result = JSONObject()
            try { exercise(); result.put("ok", true) }
            catch (e: Throwable) { result.put("ok", false).put("error", e.stackTraceToString()) }
            File(filesDir,"album-gallery-qa-result.json").writeText(result.put("checks",JSONArray(checks)).put("externalModelRequests",0).toString(2))
            withContext(Dispatchers.Main) { finish() }
        }
    }
    private suspend fun exercise() {
        val db = Room.inMemoryDatabaseBuilder(applicationContext, AppDatabase::class.java).build()
        val images = ImageStore(applicationContext)
        val existing = images.dir.listFiles().orEmpty().map { it.name }.toSet()
        val registry = File(noBackupFilesDir,"gallery-qa-consent.json")
        registry.delete()
        val uris = mutableListOf<Uri>()
        val folder = "CleosGalleryQA-${System.currentTimeMillis()}"
        fun addPhoto(sub: String, color: Int): Uri {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,"sample-${uris.size}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/$folder/$sub/")
                put(MediaStore.Images.Media.IS_PENDING,1)
                put(MediaStore.Images.Media.DATE_TAKEN,System.currentTimeMillis())
            }
            val uri = checkNotNull(contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values))
            uris += uri
            val bmp = Bitmap.createBitmap(48,32,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            try { contentResolver.openOutputStream(uri)!!.use { check(bmp.compress(Bitmap.CompressFormat.JPEG,90,it)) } }
            finally { bmp.recycle() }
            contentResolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
            return uri
        }
        var access = GalleryAccess.All
        val real = PhoneGallery(applicationContext) { access }
        var selectedKeys: Set<String>? = null
        val media = object: GalleryMediaSource {
            override fun access() = access
            override suspend fun albums() = real.albums()
            override suspend fun photos(album: PhoneAlbum) = real.photos(album).filter { selectedKeys?.contains(it.key) ?: true }
            override suspend fun present(photo: PhonePhoto) = (selectedKeys?.contains(photo.key) ?: true) && real.present(photo)
            override fun uri(photo: PhonePhoto) = real.uri(photo)
        }
        val album = WorldAlbum(db,images,{_,_->},{images.file(it).delete()})
        var stops = 0
        val gallery = WorldAlbumGallery(applicationContext,db,album,media,{stops++},registry)
        val secrets = SecretStore(applicationContext)
        val base = "https://gallery-qa302.invalid/v1"
        val owner = CompanionEntity(id=990401,name="Gallery QA",createdAt=1,apiBaseUrl=base,apiModel="synthetic")
        val other = owner.copy(id=990402)
        val requests = mutableListOf<String>()
        var decline = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.host == "gallery-qa302.invalid")
            val raw = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            requests += raw
            val request = JSONObject(raw)
            val tool = request.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name")
            val choosing = tool == "world_choose_album"
            val args = if (choosing) {
                check(!raw.contains("data:image"))
                val text = request.getJSONArray("messages").getJSONObject(1).getString("content")
                val offered = JSONArray(text.substring(text.indexOf('[')))
                JSONObject().put("photo_id",if(decline) JSONObject.NULL else offered.getJSONObject(0).getLong("photo_id"))
            } else {
                check(tool == "world_finish_album" && raw.contains("data:image/jpeg;base64,"))
                JSONObject().put("action","seen").put("note","Synthetic confirmed view").put("question","")
            }
            val call = JSONObject().put("index",0).put("id","qa-${requests.size}").put("type","function")
                .put("function",JSONObject().put("name",tool).put("arguments",args.toString()))
            val event = JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",JSONObject().put("tool_calls",JSONArray().put(call))).put("finish_reason","tool_calls")))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: $event\n\ndata: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val library = WorldLibrary(db,secrets,WorldReader(ChatClient(http,diagnostic={})),scope,{false},
            albumPixels={images.dataUrl(it.file,960)},galleryCandidates={gallery.candidates(it)},albumAutomaticAccess={gallery.automaticAllowed(it)})
        suspend fun resetQuota() { db.world().put(WorldReadingStateEntity(owner.id,LocalDate.now().toEpochDay(),0)) }
        try {
            check(secrets.key(base)==null); secrets.setKey(base,"SYNTHETIC_ONLY")
            db.companions().insert(owner); db.companions().insert(other)
            addPhoto("Selected",Color.RED); addPhoto("Other",Color.BLUE)
            val target = real.albums().single { it.name=="Selected" }
            val source = real.photos(target).single()
            check(real.present(source) && real.photos(target).size==1)
            gallery.bind(owner,target,false)
            check(gallery.candidates(owner).size==1 && gallery.candidates(other).isEmpty())
            check(db.worldAlbum().photos(owner.id).isEmpty() && requests.isEmpty())
            checks += "real MediaStore bucket filtering; linking copies/uploads nothing and is isolated per TA"
            addPhoto("Selected",Color.GREEN)
            check(gallery.candidates(owner).size==1)
            checks += "snapshot association excludes later photos"
            library.browseAlbumInBackground(owner.id,owner.createdAt) { true }
            val saved = db.worldAlbum().photos(owner.id).single()
            check(saved.fromGallery && gallery.automaticAllowed(saved))
            check(requests.size==2 && db.worldAlbum().looks(owner.id).single().photoId==saved.id)
            check(images.validWorldPicture(images.file(saved.file),saved.width,saved.height,saved.sha256))
            library.browseAlbumInBackground(owner.id,owner.createdAt) { true }; check(requests.size==2)
            checks += "chooser gets metadata only; chosen actual pixels are copied, confirmed and not repeatedly viewed"
            val persisted = WorldAlbumGallery(applicationContext,db,album,media,{},registry)
            check(persisted.automaticAllowed(saved) && persisted.link(owner)==gallery.link(owner))
            checks += "association and source provenance survive process-level repository recreation"
            gallery.bind(owner,target,true)
            check(gallery.candidates(owner).size==1)
            decline = true; resetQuota(); library.browseAlbumInBackground(owner.id,owner.createdAt) { true }
            check(requests.size==3 && db.worldAlbum().photos(owner.id).size==1)
            decline = false
            checks += "explicit future consent offers new photos; model decline sends no image and creates no copy"
            val stale = gallery.candidates(owner).single()
            gallery.unlink(owner)
            check(!gallery.automaticAllowed(saved) && gallery.candidates(owner).isEmpty())
            check(runCatching { stale.materialize!!.invoke() }.isFailure)
            check(db.worldAlbum().looks(owner.id).size==1 && real.present(source))
            checks += "unlink blocks cached automatic use and stale selections while preserving notes and phone originals"
            resetQuota(); library.startAlbum(owner.id,owner.createdAt,saved)
            withTimeout(15000) { while(owner.id in library.busy.value) delay(10) }
            check(requests.size==4 && db.worldAlbum().looks(owner.id).size==2)
            checks += "explicit manual revisit of a retained private copy works after unlink"
            gallery.bind(owner,target,true); access=GalleryAccess.None
            check(gallery.candidates(owner).isEmpty() && gallery.link(owner)?.active==false)
            access=GalleryAccess.All
            check(gallery.candidates(owner).isEmpty() && !gallery.automaticAllowed(saved))
            checks += "permission loss pauses consent durably; a later grant cannot silently resume automatic access"
            access=GalleryAccess.Selected; selectedKeys=setOf(source.key)
            check(runCatching { gallery.bind(owner,target,true) }.isFailure)
            gallery.bind(owner,target,false)
            check(gallery.link(owner)?.initialKeys==setOf(source.key))
            access=GalleryAccess.All; check(!gallery.automaticAllowed(saved) && gallery.link(owner)?.active==false)
            checks += "partial grants use an explicit snapshot and cannot expand implicitly to full access"
            selectedKeys=null; gallery.bind(owner,target,true)
            val fresh = gallery.candidates(owner).single()
            val freshPhoto = real.photos(target).first { it.key!=source.key }
            contentResolver.delete(real.uri(freshPhoto),null,null)
            check(runCatching { fresh.materialize!!.invoke() }.isFailure)
            checks += "deleted or moved source invalidates a pending selection before pixels are copied"
            registry.writeText("corrupt")
            val lost = WorldAlbumGallery(applicationContext,db,album,media,{},registry)
            check(!lost.automaticAllowed(saved))
            checks += "missing or corrupt device consent fails closed for gallery-derived saved copies"
            gallery.clear(); check(!gallery.automaticAllowed(saved) && gallery.state.value==AlbumGalleryState())
            val manual = saved.copy(id=100,file="manually-selected.jpg",fromGallery=false)
            check(gallery.automaticAllowed(manual))
            check(stops>=6)
            checks += "restore clearing removes device consent; existing manual photos keep their previous behavior"
            gallery.bind(owner,target,true)
            repeat(49) { db.worldAlbum().add(saved.copy(id=200L+it,file="capacity-$it.jpg")) }
            check(gallery.candidates(owner).isEmpty())
            checks += "50-copy capacity remains enforced without overwriting or deleting phone photos"
        } finally {
            library.stopAll(); album.stopAll(); secrets.setKey(base,null); db.close()
            uris.forEach { runCatching { contentResolver.delete(it,null,null) } }
            images.dir.listFiles().orEmpty().filter { it.name !in existing }.forEach { it.delete() }
            registry.delete(); File(registry.path+".bak").delete()
            http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
}
