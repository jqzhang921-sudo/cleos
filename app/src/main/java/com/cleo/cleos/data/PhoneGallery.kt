package com.cleo.cleos.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface GalleryMediaSource {
    fun access(): GalleryAccess
    suspend fun albums(): List<PhoneAlbum>
    suspend fun photos(album: PhoneAlbum): List<PhonePhoto>
    suspend fun present(photo: PhonePhoto): Boolean = photos(photo.album).any { it.key == photo.key }
    fun uri(photo: PhonePhoto): Uri
}

/** Queries only accessible local images. Never queries videos, DATA paths, EXIF or cloud galleries. */
class PhoneGallery(private val context: Context,
    private val permission: () -> GalleryAccess = { currentAccess(context) }) : GalleryMediaSource {
    override fun access() = permission()
    override fun uri(photo: PhonePhoto): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(photo.album.volume), photo.id)
    override suspend fun present(photo: PhonePhoto): Boolean = withContext(Dispatchers.IO) {
        if (access() == GalleryAccess.None) return@withContext false
        context.contentResolver.query(MediaStore.Images.Media.getContentUri(photo.album.volume), arrayOf(MediaStore.Images.Media._ID),
            "_id = ? AND bucket_id = ? AND date_modified = ? AND _size = ? AND is_pending = 0",
            arrayOf(photo.id.toString(), photo.album.bucket, photo.modified.toString(), photo.size.toString()), null)?.use { it.moveToFirst() } == true
    }

    override suspend fun albums(): List<PhoneAlbum> = withContext(Dispatchers.IO) {
        if (access() == GalleryAccess.None) return@withContext emptyList()
        val albums = linkedMapOf<Pair<String, String>, PhoneAlbum>()
        val projection = arrayOf(MediaStore.Images.Media.VOLUME_NAME, MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        context.contentResolver.query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), projection,
            "${MediaStore.Images.Media.IS_PENDING} = 0", null, null)?.use { c ->
            while (c.moveToNext()) {
                val volume = c.getString(0) ?: continue
                val bucket = c.getString(1) ?: continue
                val key = volume to bucket
                val old = albums[key]
                albums[key] = PhoneAlbum(volume, bucket, c.getString(2).orEmpty().ifBlank { "未命名图集" }, (old?.count ?: 0) + 1)
            }
        }
        albums.values.sortedBy { it.name }
    }

    override suspend fun photos(album: PhoneAlbum): List<PhonePhoto> = withContext(Dispatchers.IO) {
        if (access() == GalleryAccess.None) return@withContext emptyList()
        val result = mutableListOf<PhonePhoto>()
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_TAKEN)
        context.contentResolver.query(MediaStore.Images.Media.getContentUri(album.volume), projection,
            "${MediaStore.Images.Media.BUCKET_ID} = ? AND ${MediaStore.Images.Media.IS_PENDING} = 0", arrayOf(album.bucket),
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC")?.use { c ->
            while (c.moveToNext()) {
                check(result.size < AlbumGalleryRules.MAX_SNAPSHOT) { "这个图集超过一万张，请改选较小的图集或仅授权部分照片" }
                if (c.getLong(2) > 0) result += PhonePhoto(album, c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3))
            }
        }
        result
    }

    companion object {
        fun permissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        fun currentAccess(context: Context): GalleryAccess {
            fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            return when {
                Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> GalleryAccess.All
                Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> GalleryAccess.Selected
                Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> GalleryAccess.All
                else -> GalleryAccess.None
            }
        }
    }
}
