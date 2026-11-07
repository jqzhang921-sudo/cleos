package com.cleo.cleos.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.cleo.cleos.CleosApp
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Local private-album consent. Consumed tickets never replay after process death or a stale Worker. */
class WorldAlbumScheduler(context: Context, private val db: AppDatabase, private val secrets: SecretStore,
    private val library: WorldLibrary, private val scope: CoroutineScope,
    private val diagnostic: (String) -> Unit = {}, private val now: () -> ZonedDateTime = { ZonedDateTime.now() }) {
    private val work = WorkManager.getInstance(context)
    private val schedules = Mutex()
    @Volatile private var paused = false

    private suspend fun fingerprint(id: Long): String? {
        val ta = db.companions().get(id) ?: return null
        if (ta.apiBaseUrl.isBlank() || ta.apiModel.isBlank()) return null
        val key = secrets.key(ta.apiBaseUrl)?.takeIf(String::isNotBlank) ?: return null
        return WorldAlbumConsent.fingerprint(ta, key, secrets.revision(ta.apiBaseUrl))
    }
    private suspend fun matches(t: WorldAlbumScheduleEntity): Boolean =
        db.companions().get(t.companionId)?.createdAt == t.companionCreatedAt && fingerprint(t.companionId) == t.contextHash

    suspend fun setEnabled(expected: CompanionEntity, enabled: Boolean) = schedules.withLock {
        check(!paused) { "资料正在恢复，请稍后再设置" }
        val id = expected.id
        var changed = false
        val ticket = db.withTransaction {
            check(WorldReading.sameContext(expected, db.companions().get(id))) { "角色或模型设置已变化，请重新打开相册" }
            if (!enabled) { db.worldAlbum().deleteSchedule(id); null }
            else {
                check(db.worldAlbum().photos(id).isNotEmpty()) { "先为这位 TA 选择一张照片" }
                val hash = checkNotNull(fingerprint(id)) { "请先配置这位 TA 的看图模型和密钥" }
                db.worldAlbum().schedule(id)?.takeIf { it.companionCreatedAt == expected.createdAt && it.contextHash == hash }
                    ?: WorldAlbumScheduleEntity(id, expected.createdAt, UUID.randomUUID().toString(), hash, WorldScheduleRules.first(now()))
                        .also { db.worldAlbum().put(it); changed = true }
            }
        }
        if (ticket == null || changed) {
            library.stopAlbumBackground(id)
            cancel(id)
        }
        if (ticket != null) try { enqueue(ticket) } catch (e: Exception) {
            withContext(NonCancellable) {
                if (changed && db.worldAlbum().schedule(id) == ticket) { db.worldAlbum().deleteSchedule(id); cancel(id) }
            }; throw e
        }
    }
    /** A key change must revoke even when an older key was subsequently put back. */
    suspend fun reconcile(id: Long) = schedules.withLock {
        if (paused) return@withLock
        val ticket = db.worldAlbum().schedule(id)
        if (ticket != null && !matches(ticket)) db.worldAlbum().deleteSchedule(id)
        if (db.worldAlbum().schedule(id) == null) { library.stopAlbumBackground(id); cancel(id) }
    }
    suspend fun forget(id: Long) = schedules.withLock {
        db.worldAlbum().deleteSchedule(id); library.stopAlbumBackground(id); cancel(id)
    }
    fun restore() = scope.launch {
        schedules.withLock {
            paused = false; library.resume()
            for (ticket in db.worldAlbum().schedules()) {
                if (!matches(ticket)) { db.worldAlbum().deleteSchedule(ticket.companionId); cancel(ticket.companionId) }
                else runCatching { enqueue(ticket) }.onFailure { diagnostic("event=world_album_schedule_failure route=world stage=restore reason=enqueue") }
            }
        }
    }
    suspend fun stopAll() {
        schedules.withLock { paused = true; cancel(null) }
        library.stopBackground("album")
    }
    private suspend fun cancel(id: Long?) = withContext(Dispatchers.IO) {
        work.cancelAllWorkByTag(if (id == null) TAG else taTag(id)).result.get(); Unit
    }
    private suspend fun enqueue(ticket: WorldAlbumScheduleEntity) {
        if (paused || db.worldAlbum().schedule(ticket.companionId) != ticket) return
        val request = OneTimeWorkRequestBuilder<WorldAlbumWorker>().addTag(TAG).addTag(taTag(ticket.companionId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay((ticket.nextAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setInputData(workDataOf("companion" to ticket.companionId, "revision" to ticket.revision, "due" to ticket.nextAt)).build()
        withContext(Dispatchers.IO) { work.enqueueUniqueWork("$TAG-${ticket.companionId}-${ticket.revision}-${ticket.nextAt}", ExistingWorkPolicy.KEEP, request).result.get() }
    }
    suspend fun run(id: Long, revision: String, due: Long): Boolean {
        var early = false
        val next = schedules.withLock {
            if (paused) return true
            var reconcile: WorldAlbumScheduleEntity? = null
            val advanced = db.withTransaction {
                val ticket = db.worldAlbum().schedule(id) ?: return@withTransaction null
                if (ticket.revision != revision) return@withTransaction null
                if (!matches(ticket)) { db.worldAlbum().deleteSchedule(id); return@withTransaction null }
                if (ticket.nextAt != due) { reconcile = ticket; return@withTransaction null }
                val time = now()
                if (time.toInstant().toEpochMilli() < due) { early = true; return@withTransaction null }
                val state = db.world().state(id)
                val exhausted = state?.day == time.toLocalDate().toEpochDay() && state.attempts - (if (state.token != null) 1 else 0) >= WorldReading.DAILY
                ticket.copy(nextAt = WorldScheduleRules.next(time, exhausted)).also { db.worldAlbum().put(it) }
            }
            if (advanced == null) { reconcile?.let { enqueue(it) }; return !early }
            enqueue(advanced); advanced
        }
        suspend fun allowed(): Boolean = !paused && db.worldAlbum().schedule(id) == next && matches(next)
        currentCoroutineContext().ensureActive()
        if (!allowed() || !library.canBrowseOutside(id, next.companionCreatedAt) || db.worldAlbum().photos(id).isEmpty()) return true
        runCatching { diagnostic("event=world_album_opportunity route=world stage=background") }
        library.browseAlbumInBackground(id, next.companionCreatedAt, ::allowed)
        return true
    }
    private fun taTag(id: Long) = "$TAG-ta-$id"
    companion object { private const val TAG = "world-album" }
}

class WorldAlbumWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("companion", -1)
        val revision = inputData.getString("revision") ?: return Result.success()
        val due = inputData.getLong("due", -1)
        if (id <= 0 || due <= 0) return Result.success()
        return try { if ((applicationContext as CleosApp).container.worldAlbumScheduler.run(id, revision, due)) Result.success() else Result.retry() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
    }
}
