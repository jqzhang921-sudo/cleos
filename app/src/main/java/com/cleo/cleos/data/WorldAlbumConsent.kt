package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.Json

internal object WorldAlbumConsent {
    fun fingerprint(ta: CompanionEntity, key: String, revision: String): String = WorldReading.fingerprint(
        Json.encodeToString(listOf(ta.id.toString(), ta.createdAt.toString(), ta.name, ta.persona, ta.apiBaseUrl, ta.apiModel, key, revision)))
    /** Confirmed views of these exact pixels, private to one TA (also handles duplicate imports). */
    fun history(photo: WorldAlbumPhotoEntity, looks: List<WorldAlbumLookEntity>): List<WorldAlbumLookEntity> =
        looks.filter { photo.sha256.isNotBlank() && it.companionId == photo.companionId && it.photoSha256 == photo.sha256 }
            .sortedWith(compareByDescending<WorldAlbumLookEntity> { it.createdAt }.thenByDescending { it.id })

    /** Automatic opportunities never recycle confirmed pictures. Manual viewing bypasses this pool. */
    fun candidates(photos: List<WorldAlbumPhotoEntity>, looks: List<WorldAlbumLookEntity>): List<WorldAlbumPhotoEntity> {
        return photos.filter { history(it, looks).isEmpty() }
            .sortedWith(compareBy<WorldAlbumPhotoEntity> { it.createdAt }.thenBy { it.id })
            .distinctBy { it.companionId to it.sha256 }.take(10)
    }
}
