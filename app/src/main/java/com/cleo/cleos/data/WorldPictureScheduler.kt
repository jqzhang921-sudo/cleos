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

/** Local public-picture consent. Consumed tickets never replay after process death or a stale Worker. */
class WorldPictureScheduler(context: Context, private val db: AppDatabase, private val secrets: SecretStore,
    private val library: WorldLibrary, private val pictures: WorldPictures, private val scope: CoroutineScope,
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
    private suspend fun matches(t: WorldPictureScheduleEntity): Boolean =
        db.companions().get(t.companionId)?.createdAt == t.companionCreatedAt && fingerprint(t.companionId) == t.contextHash

    suspend fun setEnabled(expected: CompanionEntity, enabled: Boolean) = schedules.withLock {
        check(!paused) { "资料正在恢复，请稍后再设置" }
        val id = expected.id
        var changed = false
        val ticket = db.withTransaction {
            check(WorldReading.sameContext(expected, db.companions().get(id))) { "角色或模型设置已变化，请重新打开窗外" }
            if (!enabled) { db.world().deletePictureSchedule(id); null }
            else {
                check(WorldPictureExplore.candidates(id, db.world().articles(id), db.world().pictureLooks(id)).isNotEmpty()) { "先在窗外保存一份百科条目" }
                val hash = checkNotNull(fingerprint(id)) { "请先配置这位 TA 的看图模型和密钥" }
                db.world().pictureSchedule(id)?.takeIf { it.companionCreatedAt == expected.createdAt && it.contextHash == hash }
                    ?: WorldPictureScheduleEntity(id, expected.createdAt, UUID.randomUUID().toString(), hash, WorldScheduleRules.first(now()))
                        .also { db.world().put(it); changed = true }
            }
        }
        if (ticket == null || changed) {
            library.stopPictureBackground(id)
            cancel(id)
        }
        if (ticket != null) try { enqueue(ticket) } catch (e: Exception) {
            withContext(NonCancellable) {
                if (changed && db.world().pictureSchedule(id) == ticket) { db.world().deletePictureSchedule(id); cancel(id) }
            }; throw e
        }
    }
    /** A key change must revoke even when an older key was subsequently put back. */
    suspend fun reconcile(id: Long) = schedules.withLock {
        if (paused) return@withLock
        val ticket = db.world().pictureSchedule(id)
        if (ticket != null && !matches(ticket)) db.world().deletePictureSchedule(id)
        if (db.world().pictureSchedule(id) == null) { library.stopPictureBackground(id); cancel(id) }
    }
    suspend fun forget(id: Long) = schedules.withLock {
        db.world().deletePictureSchedule(id); library.stopPictureBackground(id); cancel(id)
    }
    fun restore() = scope.launch {
        schedules.withLock {
            paused = false; library.resume()
            for (ticket in db.world().pictureSchedules()) {
                if (!matches(ticket)) { db.world().deletePictureSchedule(ticket.companionId); cancel(ticket.companionId) }
                else runCatching { enqueue(ticket) }.onFailure { diagnostic("event=world_picture_schedule_failure route=world stage=restore reason=enqueue") }
            }
        }
    }
    suspend fun stopAll() {
        schedules.withLock { paused = true; cancel(null) }
        library.stopBackground("picture")
    }
    private suspend fun cancel(id: Long?) = withContext(Dispatchers.IO) {
        work.cancelAllWorkByTag(if (id == null) TAG else taTag(id)).result.get(); Unit
    }
    private suspend fun enqueue(ticket: WorldPictureScheduleEntity) {
        if (paused || db.world().pictureSchedule(ticket.companionId) != ticket) return
        val request = OneTimeWorkRequestBuilder<WorldPictureWorker>().addTag(TAG).addTag(taTag(ticket.companionId))
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
            var reconcile: WorldPictureScheduleEntity? = null
            val advanced = db.withTransaction {
                val ticket = db.world().pictureSchedule(id) ?: return@withTransaction null
                if (ticket.revision != revision) return@withTransaction null
                if (!matches(ticket)) { db.world().deletePictureSchedule(id); return@withTransaction null }
                if (ticket.nextAt != due) { reconcile = ticket; return@withTransaction null }
                val time = now()
                if (time.toInstant().toEpochMilli() < due) { early = true; return@withTransaction null }
                val state = db.world().state(id)
                val exhausted = state?.day == time.toLocalDate().toEpochDay() && state.attempts - (if (state.token != null) 1 else 0) >= WorldReading.DAILY
                ticket.copy(nextAt = WorldScheduleRules.next(time, exhausted)).also { db.world().put(it) }
            }
            if (advanced == null) { reconcile?.let { enqueue(it) }; return !early }
            enqueue(advanced); advanced
        }
        suspend fun allowed(): Boolean = !paused && db.world().pictureSchedule(id) == next && matches(next)
        currentCoroutineContext().ensureActive()
        if (!allowed() || !library.canBrowseOutside(id, next.companionCreatedAt) || WorldPictureExplore.candidates(id, db.world().articles(id), db.world().pictureLooks(id)).isEmpty()) return true
        runCatching { diagnostic("event=world_picture_opportunity route=world stage=background") }
        library.browsePicturesInBackground(id, next.companionCreatedAt, pictures::obtain, ::allowed)
        return true
    }
    private fun taTag(id: Long) = "$TAG-ta-$id"
    companion object { private const val TAG = "world-picture" }
}

class WorldPictureWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("companion", -1)
        val revision = inputData.getString("revision") ?: return Result.success()
        val due = inputData.getLong("due", -1)
        if (id <= 0 || due <= 0) return Result.success()
        return try { if ((applicationContext as CleosApp).container.worldPictureScheduler.run(id, revision, due)) Result.success() else Result.retry() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
    }
}
