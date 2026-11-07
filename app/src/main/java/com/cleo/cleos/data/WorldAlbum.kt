package com.cleo.cleos.data

import android.net.Uri
import androidx.room.withTransaction
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Manual selected-photo import only. Restores drain this lock before replacing the database. */
class WorldAlbum(private val db: AppDatabase, private val images: ImageStore,
    private val beforeChange: suspend (Long, Long) -> Unit, private val discard: suspend (String) -> Unit) {
    private val lock = Mutex()
    @Volatile private var paused = false
    @Volatile private var generation = java.util.UUID.randomUUID().mostSignificantBits
    @Volatile private var removedTas: Set<Long> = emptySet()
    fun revision(): Long = generation
    private suspend fun allowed(ta: Long, createdAt: Long, revision: Long) {
        currentCoroutineContext().ensureActive()
        check(!paused && ta !in removedTas && revision == generation && db.companions().get(ta)?.createdAt == createdAt) { "相册或角色已变化，请重新打开" }
    }
    suspend fun add(ta: Long, createdAt: Long, revision: Long, uri: Uri): Long = lock.withLock {
        allowed(ta, createdAt, revision)
        check(db.worldAlbum().photos(ta).size < WorldAlbumData.MAX_PHOTOS) { "相册已放满 50 张，请先整理一些" }
        var temporary: String? = null
        try {
            val image = withContext(NonCancellable) { images.import(uri, 960, "album-") }
            temporary = image.file
            allowed(ta, createdAt, revision)
            val sha = withContext(Dispatchers.IO) { WorldPictureData.digest(images.file(image.file)) }
            check(images.validWorldPicture(images.file(image.file), image.width, image.height, sha)) { "图片无法保存，请选择其他照片" }
            val id = db.withTransaction {
                allowed(ta, createdAt, revision)
                check(db.worldAlbum().photos(ta).size < WorldAlbumData.MAX_PHOTOS)
                db.worldAlbum().add(WorldAlbumPhotoEntity(companionId = ta, file = image.file, width = image.width,
                    height = image.height, sha256 = sha, createdAt = System.currentTimeMillis()))
            }
            temporary = null
            id
        } finally { temporary?.let { withContext(NonCancellable) { discard(it) } } }
    }
    suspend fun caption(ta: Long, createdAt: Long, revision: Long, expected: WorldAlbumPhotoEntity, text: String) = lock.withLock {
        allowed(ta, createdAt, revision)
        require(WorldAlbumData.caption(text)) { "说明最多 500 字" }
        check(expected.companionId == ta && db.worldAlbum().photo(expected.id) == expected) { "照片已变化，请重新打开" }
        beforeChange(ta, expected.id)
        db.withTransaction { allowed(ta, createdAt, revision); check(db.worldAlbum().photo(expected.id) == expected)
            db.worldAlbum().update(expected.copy(caption = text.trim())) }
    }
    suspend fun look(ta: Long, createdAt: Long, revision: Long, expected: WorldAlbumPhotoEntity, start: () -> Unit) = lock.withLock {
        allowed(ta, createdAt, revision)
        check(expected.companionId == ta && db.worldAlbum().photo(expected.id) == expected) { "照片已变化，请重新打开" }
        start()
    }
    suspend fun permit(ta: Long, createdAt: Long, revision: Long, action: suspend () -> Unit) = lock.withLock {
        allowed(ta, createdAt, revision); action()
    }
    suspend fun remove(ta: Long, createdAt: Long, revision: Long, expected: WorldAlbumPhotoEntity) = lock.withLock {
        allowed(ta, createdAt, revision)
        check(expected.companionId == ta && db.worldAlbum().photo(expected.id) == expected) { "照片已变化，请重新打开" }
        beforeChange(ta, expected.id)
        db.withTransaction { allowed(ta, createdAt, revision); check(db.worldAlbum().photo(expected.id) == expected)
            db.worldAlbum().deletePhoto(expected.id, ta) }
        withContext(NonCancellable) { discard(expected.file) }
    }
    suspend fun stopAll() { paused = true; generation++; lock.withLock { } }
    suspend fun forget(ta: Long) {
        synchronized(this) { removedTas = removedTas + ta }
        lock.withLock { db.worldAlbum().photos(ta).forEach { beforeChange(ta, it.id) } }
    }
    fun resume() { removedTas = emptySet(); paused = false }
}
