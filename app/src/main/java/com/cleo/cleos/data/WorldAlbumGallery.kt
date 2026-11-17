package com.cleo.cleos.data

import android.content.Context
import android.util.AtomicFile
import com.cleo.cleos.data.db.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Device-only consent, excluded from Android/zip backups. No gallery pixels are stored here. */
class WorldAlbumGallery(context: Context, private val db: AppDatabase, private val album: WorldAlbum,
    val media: GalleryMediaSource = PhoneGallery(context), private val stop: suspend (Long) -> Unit = {},
    file: File = File(context.noBackupFilesDir, "album-gallery.json")) {
    private val disk = AtomicFile(file)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(runCatching { json.decodeFromString<AlbumGalleryState>(disk.readFully().decodeToString()) }.getOrDefault(AlbumGalleryState()))
    val state = mutable.asStateFlow()
    private suspend fun save(change: (AlbumGalleryState) -> AlbumGalleryState) = mutex.withLock {
        val next = change(mutable.value)
        withContext(Dispatchers.IO + NonCancellable) {
            val output = disk.startWrite()
            try { output.write(json.encodeToString(next).toByteArray()); disk.finishWrite(output) }
            catch (e: Throwable) { disk.failWrite(output); throw e }
            mutable.value = next
        }
    }
    fun link(ta: CompanionEntity): AlbumGalleryLink? = state.value.links.firstOrNull { it.owner == ta.id && it.ownerCreatedAt == ta.createdAt }
    suspend fun bind(ta: CompanionEntity, selected: PhoneAlbum, future: Boolean) {
        check(db.companions().get(ta.id)?.createdAt == ta.createdAt) { "角色已经变化，请重新打开" }
        val access = media.access()
        check(access != GalleryAccess.None && (!future || access == GalleryAccess.All)) { "请重新确认照片权限；仅授权部分照片时不能包含以后新增照片" }
        val current = media.photos(selected)
        check(current.isNotEmpty()) { "这个图集没有可访问的照片" }
        val next = AlbumGalleryLink(ta.id, ta.createdAt, UUID.randomUUID().toString(), selected, access, future, current.map { it.key }.toSet())
        save { it.copy(links = it.links.filterNot { old -> old.owner == ta.id } + next) }
        stop(ta.id)
    }
    suspend fun unlink(ta: CompanionEntity) {
        save { it.copy(links = it.links.filterNot { old -> old.owner == ta.id }) }
        stop(ta.id)
    }
    suspend fun clear() {
        val owners = state.value.links.map { it.owner }
        save { AlbumGalleryState() }; owners.forEach { stop(it) }
    }
    suspend fun forget(owner: Long) {
        save { it.copy(links = it.links.filterNot { l -> l.owner == owner }, imports = it.imports.filterNot { p -> p.owner == owner }) }
        stop(owner)
    }
    /** A permission downgrade pauses consent durably; re-granting permission never silently resumes it. */
    suspend fun reconcile(ta: CompanionEntity) {
        val old = link(ta) ?: return
        if (old.active && old.access != media.access()) {
            pauseChangedAccess(ta)
            stop(ta.id)
        }
    }
    // Called from the reading job as well: never cancel/join the job from inside itself.
    private suspend fun pauseChangedAccess(ta: CompanionEntity): AlbumGalleryLink? {
        val old = link(ta) ?: return null
        if (old.active && old.access != media.access()) {
            save { it.copy(links = it.links.map { l -> if (l.revision == old.revision) l.copy(active = false) else l }) }
        }
        return link(ta)
    }
    private suspend fun permitted(ta: CompanionEntity, expected: AlbumGalleryLink, photo: PhonePhoto): Boolean {
        if (db.companions().get(ta.id)?.createdAt != ta.createdAt || pauseChangedAccess(ta) != expected ||
            !AlbumGalleryRules.accepts(expected, photo, media.access())) return false
        return runCatching { media.present(photo) }.getOrDefault(false)
    }
    suspend fun automaticAllowed(photo: WorldAlbumPhotoEntity): Boolean {
        if (!photo.fromGallery) return true
        val ta = db.companions().get(photo.companionId) ?: return false
        val origin = state.value.imports.lastOrNull { it.owner == ta.id && it.ownerCreatedAt == ta.createdAt && it.file == photo.file } ?: return false
        val link = pauseChangedAccess(ta) ?: return false
        val parts = origin.sourceKey.split(':')
        val source = runCatching { check(parts.size == 4 && parts[0] == origin.album.volume)
            PhonePhoto(origin.album, parts[1].toLong(), parts[2].toLong(), parts[3].toLong(), 0) }.getOrNull() ?: return false
        return AlbumGalleryRules.accepts(link, source, media.access()) && runCatching { media.present(source) }.getOrDefault(false)
    }
    suspend fun candidates(ta: CompanionEntity): List<AlbumCandidate> {
        if (db.companions().get(ta.id)?.createdAt != ta.createdAt) return emptyList()
        val link = pauseChangedAccess(ta) ?: return emptyList()
        if (!link.active || link.access != media.access() || media.access() == GalleryAccess.None) return emptyList()
        val saved = db.worldAlbum().photos(ta.id)
        if (saved.size >= WorldAlbumData.MAX_PHOTOS) return emptyList()
        val already = state.value.imports.filter { it.owner == ta.id && it.ownerCreatedAt == ta.createdAt &&
            (it.committed || saved.any { p -> p.file == it.file }) }.map { it.sourceKey }.toSet()
        val candidates = media.photos(link.album).filter { AlbumGalleryRules.accepts(link, it, media.access()) && it.key !in already }.take(10)
        return candidates.mapIndexed { index, source ->
            val label = "来自手机图集「${link.album.name.take(80)}」中已授权的一张照片" +
                if (source.taken > 0) "，日期 ${java.time.Instant.ofEpochMilli(source.taken).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}" else ""
            // Stay exactly representable even through JSON gateways using IEEE-754 numbers.
            AlbumCandidate(WorldAlbumPhotoEntity(9_000_000_000_000L - index, ta.id, "", 0, 0, "", label, 1)) {
                check(permitted(ta, link, source)) { "图集、照片或授权已经变化" }
                val id = album.add(ta.id, ta.createdAt, album.revision(), media.uri(source), fromGallery = true, caption = label,
                    permitted = { permitted(ta, link, source) }, beforeSave = { image ->
                        save { state ->
                            check(state.links.any { it == link }) { "图集关联已经变化" }
                            state.copy(imports = state.imports + AlbumGalleryImport(ta.id, ta.createdAt, link.album, source.key, image.file))
                        }
                    })
                val result = checkNotNull(db.worldAlbum().photo(id))
                save { state -> state.copy(imports = state.imports.map { if (it.file == result.file) it.copy(committed = true) else it }) }
                result
            }
        }
    }
}
