package com.cleo.cleos.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class StoredImage(val file: String, val width: Int, val height: Int)

/**
 * Pictures copied into the app's own storage.
 *
 * Keeping a content:// URI from the picker is not enough: the grant can lapse, and the
 * photo can be deleted from the gallery, and a diary page should not lose its picture
 * because of either. So every picked image is decoded, downscaled and saved here.
 */
class ImageStore(context: Context) {
    private val resolver = context.contentResolver
    val dir: File = File(context.filesDir, "images").apply { mkdirs() }

    fun file(name: String): File = File(dir, name)

    /**
     * Copies [uri] into the store. ImageDecoder applies the EXIF rotation, so portrait
     * phone photos come out upright. Images with transparency stay PNG; everything else
     * becomes JPEG, whose missing alpha would otherwise turn transparent pixels black.
     */
    suspend fun import(uri: Uri, maxEdge: Int = 2048, prefix: String = ""): StoredImage = withContext(Dispatchers.IO) {
        val bitmap = decode(uri, maxEdge)
        try { StoredImage(write(bitmap, prefix), bitmap.width, bitmap.height) } finally { bitmap.recycle() }
    }

    /** A bounded inline image from an external service; the decoder checks the real file format. */
    suspend fun import(bytes: ByteArray, mimeType: String, maxEdge: Int = 1536): StoredImage = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty() && bytes.size <= 2 * 1024 * 1024)
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            require(info.mimeType == mimeType && mimeType in setOf("image/png", "image/jpeg", "image/webp"))
            val w = info.size.width
            val h = info.size.height
            require(w > 0 && h > 0 && w <= 20_000 && h <= 20_000 && w.toLong() * h <= 40_000_000)
            val scale = min(1f, maxEdge.coerceIn(1, 1536) / max(w, h).toFloat())
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try { StoredImage(write(bitmap, "mcp-"), bitmap.width, bitmap.height) } finally { bitmap.recycle() }
    }

    /**
     * Decodes [uri] upright and at most [maxEdge] on its longer side, into memory the app
     * can crop and redraw (a hardware bitmap can be drawn but not cut).
     */
    suspend fun decode(uri: Uri, maxEdge: Int = 2048): Bitmap = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(resolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = min(1f, maxEdge / max(w, h).toFloat())
            if (scale < 1f) {
                decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    /** Stores a picture the app made itself (a cropped avatar) and returns its name. */
    suspend fun save(bitmap: Bitmap, prefix: String = ""): String = withContext(Dispatchers.IO) { write(bitmap, prefix) }

    private fun write(bitmap: Bitmap, prefix: String): String {
        val png = bitmap.hasAlpha()
        val name = prefix + UUID.randomUUID().toString() + if (png) ".png" else ".jpg"
        val tmp = File(dir, "$name.tmp")
        try {
            tmp.outputStream().use {
                check(bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, it)) { "图片未能保存" }
            }
            check(tmp.renameTo(File(dir, name))) { "图片未能保存" }
        } finally {
            tmp.delete()
        }
        return name
    }

    fun delete(names: Collection<String>) {
        names.forEach { runCatching { File(dir, it).delete() } }
    }

    // A few pictures go out with every turn while they are recent; encoding them again
    // each time would redo the same decode and compress for nothing.
    private val sendable = LruCache<String, String>(8)

    /**
     * [name] as a data: URL for a model to look at: at most [maxEdge] on the longer side
     * and JPEG, which is plenty for a model and keeps each request small.
     */
    suspend fun dataUrl(name: String, maxEdge: Int = 1024): String? = withContext(Dispatchers.IO) {
        sendable.get(name)?.let { return@withContext it }
        val bitmap = thumbnail(name, maxEdge) ?: return@withContext null
        val scale = min(1f, maxEdge / max(bitmap.width, bitmap.height).toFloat())
        val sized = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
        } else {
            bitmap
        }
        val bytes = ByteArrayOutputStream().use { out ->
            sized.compress(Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        }
        ("data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)).also { sendable.put(name, it) }
    }

    /**
     * The middle square of [name], [edge] pixels across: for a picture the model chose as
     * its avatar, where there is nobody to drag a crop circle.
     */
    suspend fun centreSquare(name: String, edge: Int = 512): Bitmap? = withContext(Dispatchers.IO) {
        // Decoded large: the square comes from the shorter side, and a wide panorama
        // decoded to [edge] on its longer side would leave a tiny square.
        val bitmap = thumbnail(name, 2048) ?: return@withContext null
        val side = min(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        if (side > edge) Bitmap.createScaledBitmap(square, edge, edge, true) else square
    }

    /** A small, cheap decode for colour analysis. */
    fun thumbnail(name: String, targetEdge: Int = 48): Bitmap? {
        val f = File(dir, name)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetEdge) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
