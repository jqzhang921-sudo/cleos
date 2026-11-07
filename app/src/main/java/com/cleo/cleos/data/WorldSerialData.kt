package com.cleo.cleos.data

import androidx.room.withTransaction
import com.cleo.cleos.data.db.*

object WorldSerialData {
    const val MAX_TITLE = 80
    fun validTitle(title: String): Boolean = title.isNotBlank() && title == title.trim() && title.length <= MAX_TITLE &&
        title.none { it.isISOControl() } && title.indices.all { i ->
            when { title[i].isHighSurrogate() -> i + 1 < title.length && title[i + 1].isLowSurrogate()
                title[i].isLowSurrogate() -> i > 0 && title[i - 1].isHighSurrogate(); else -> true }
        }

    fun validate(owners: Set<Long>, serials: List<WorldSerialEntity>, chapters: List<WorldSerialChapterEntity>, writings: List<WorldWritingEntity>) {
        require(serials.map { it.id }.distinct().size == serials.size) { "连载编号重复" }
        require(serials.groupingBy { it.companionId }.eachCount().values.all { it <= WorldReading.MAX_NOTES }) { "连载过多" }
        serials.forEach { require(it.id > 0 && it.companionId in owners && validTitle(it.title) && it.createdAt > 0 && it.updatedAt >= it.createdAt) { "连载名称或归属无效" } }
        require(chapters.map { it.writingId }.distinct().size == chapters.size) { "同一篇随笔重复归册" }
        require(chapters.map { it.serialId to it.position }.distinct().size == chapters.size) { "章节顺序重复" }
        val books = serials.associateBy { it.id }; val rows = writings.associateBy { it.id }
        chapters.forEach { c ->
            val book = books[c.serialId]; val row = rows[c.writingId]
            require(c.position >= 0 && c.position < WorldReading.MAX_NOTES && book != null && row != null && row.companionId == book.companionId) { "连载章节缺失或不属于同一位 TA" }
        }
    }

    /** View-only projection; invalid/foreign links never expose another TA's title or content. */
    fun ordered(book: WorldSerialEntity, links: List<WorldSerialChapterEntity>, writings: List<WorldWritingEntity>): List<WorldWritingEntity> {
        val owned = writings.filter { it.companionId == book.companionId }.associateBy { it.id }
        return links.filter { it.serialId == book.id }.sortedBy { it.position }.mapNotNull { owned[it.writingId] }.distinctBy { it.id }
    }
}

/** All writes are metadata-only and verify stale editors inside the same transaction. */
class WorldSerials(private val db: AppDatabase) {
    suspend fun save(owner: CompanionEntity, before: WorldSerialEntity?, title: String, chapters: List<WorldWritingEntity>): Long = db.withTransaction {
        require(db.companions().get(owner.id)?.createdAt == owner.createdAt) { "角色已经变化，请重新打开后再试" }
        require(WorldSerialData.validTitle(title) && chapters.isNotEmpty() && chapters.size <= WorldReading.MAX_NOTES) { "请填写连载名称，并至少选择一篇随笔" }
        require(chapters.map { it.id }.distinct().size == chapters.size) { "同一篇随笔不能重复加入" }
        val dao = db.worldSerials()
        if (before != null) require(before.companionId == owner.id && dao.get(before.id) == before) { "连载已经变化，请重新打开整理" }
        chapters.forEach { row ->
            require(row.companionId == owner.id && db.world().writing(row.id) == row) { "随笔已经变化，请重新选择" }
            require(dao.membership(row.id)?.serialId.let { it == null || it == before?.id }) { "这篇随笔已在另一本连载中，请先拆出" }
        }
        val now = maxOf(System.currentTimeMillis(), (before?.updatedAt ?: 0) + 1)
        val row = before?.copy(title = title, updatedAt = now) ?: WorldSerialEntity(companionId = owner.id, title = title, createdAt = now, updatedAt = now)
        val id = if (before == null) {
            require(dao.all().count { it.companionId == owner.id } < WorldReading.MAX_NOTES) { "连载数量已满，请先整理" }
            dao.add(row)
        } else { dao.update(row); row.id }
        dao.clearChapters(id)
        dao.insertChapters(chapters.mapIndexed { i, writing -> WorldSerialChapterEntity(writing.id, id, i) })
        id
    }

    suspend fun dissolve(owner: CompanionEntity, before: WorldSerialEntity) = db.withTransaction {
        require(db.companions().get(owner.id)?.createdAt == owner.createdAt && before.companionId == owner.id && db.worldSerials().get(before.id) == before) { "角色或连载已经变化，请重新打开后再试" }
        db.worldSerials().delete(before.id, owner.id) // FK cascade removes only links, never writings.
    }
}
