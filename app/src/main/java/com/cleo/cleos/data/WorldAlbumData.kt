package com.cleo.cleos.data

import com.cleo.cleos.data.db.*

/** Private album data must never become public topic seeds or public reading notes. */
object WorldAlbumData {
    const val MAX_PHOTOS = 50
    private val file = Regex("album-[0-9a-f-]{36}\\.(jpg|png)")
    private val hash = Regex("[0-9a-f]{64}")
    fun caption(text: String): Boolean = text.length <= 500 && text.none { it == '\u0000' || it.isISOControl() && it != '\n' && it != '\t' && it != '\r' }
    fun validate(owners: Set<Long>, photos: List<WorldAlbumPhotoEntity>, looks: List<WorldAlbumLookEntity>,
        notes: List<WorldNoteEntity> = emptyList(), publicLooks: List<WorldPictureLookEntity> = emptyList()) {
        require(photos.map { it.id }.distinct().size == photos.size && photos.map { it.file }.distinct().size == photos.size) { "相册包含重复照片" }
        require(photos.groupingBy { it.companionId }.eachCount().values.all { it <= MAX_PHOTOS }) { "相册照片过多" }
        photos.forEach { p -> require(p.id > 0 && p.companionId in owners && file.matches(p.file) && hash.matches(p.sha256) &&
            p.width in 1..960 && p.height in 1..960 && p.createdAt > 0 && caption(p.caption)) { "相册照片归属或内容无效" } }
        val byId = photos.associateBy { it.id }
        val runIds = looks.map { it.runId } + notes.map { it.runId } + publicLooks.map { it.runId }
        require(looks.map { it.id }.distinct().size == looks.size && runIds.distinct().size == runIds.size) { "相册记录包含重复结果" }
        require((looks.map { it.companionId } + notes.map { it.companionId } + publicLooks.map { it.companionId }).groupingBy { it }.eachCount().values.all { it <= WorldReading.MAX_NOTES }) { "世界记录过多" }
        looks.forEach { l -> val p = byId[l.photoId]
            require(l.id > 0 && p != null && l.companionId == p.companionId && l.photoSha256 == p.sha256 &&
                l.runId.isNotBlank() && l.runId.length <= 100 && l.createdAt >= p.createdAt &&
                l.content.length <= 4000 && l.question.length <= 600 && '\u0000' !in l.content && '\u0000' !in l.question) { "相册看图记录无效" }
        }
    }
}
