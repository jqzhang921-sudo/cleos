package com.cleo.cleos.data

import kotlinx.serialization.Serializable

enum class GalleryAccess { None, Selected, All }

@Serializable
data class PhoneAlbum(val volume: String, val bucket: String, val name: String, val count: Int = 0)

data class PhonePhoto(val album: PhoneAlbum, val id: Long, val modified: Long, val size: Long, val taken: Long) {
    val key: String get() = "${album.volume}:$id:$modified:$size"
}

@Serializable
data class AlbumGalleryLink(val owner: Long, val ownerCreatedAt: Long, val revision: String,
    val album: PhoneAlbum, val access: GalleryAccess, val includeFuture: Boolean,
    val initialKeys: Set<String>, val active: Boolean = true)

@Serializable
data class AlbumGalleryImport(val owner: Long, val ownerCreatedAt: Long, val album: PhoneAlbum,
    val sourceKey: String, val file: String, val committed: Boolean = false)

@Serializable
data class AlbumGalleryState(val links: List<AlbumGalleryLink> = emptyList(), val imports: List<AlbumGalleryImport> = emptyList())

internal object AlbumGalleryRules {
    const val MAX_SNAPSHOT = 10_000
    fun accepts(link: AlbumGalleryLink, image: PhonePhoto, access: GalleryAccess): Boolean =
        link.active && access != GalleryAccess.None && access == link.access &&
            image.album.volume == link.album.volume && image.album.bucket == link.album.bucket &&
            (if (link.includeFuture) access == GalleryAccess.All else image.key in link.initialKeys)
}
