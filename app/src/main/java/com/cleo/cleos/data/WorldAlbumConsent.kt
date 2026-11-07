package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.Json

internal object WorldAlbumConsent {
    fun fingerprint(ta: CompanionEntity, key: String, revision: String): String = WorldReading.fingerprint(
        Json.encodeToString(listOf(ta.id.toString(), ta.createdAt.toString(), ta.name, ta.persona, ta.apiBaseUrl, ta.apiModel, key, revision)))
    /** Prefer unseen/least recently seen pictures; expose only ten captions for one choice. */
    fun candidates(photos: List<WorldAlbumPhotoEntity>, looks: List<WorldAlbumLookEntity>): List<WorldAlbumPhotoEntity> {
        val latest = looks.groupBy { it.photoId }.mapValues { (_, rows) -> rows.maxOf { it.createdAt } }
        return photos.sortedWith(compareBy<WorldAlbumPhotoEntity> { latest[it.id] ?: 0L }.thenBy { it.createdAt }.thenBy { it.id }).take(10)
    }
}
