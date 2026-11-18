package app.spool.data

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * Covers. First ask Android (embedded art for audio, a frame for video; cached by the
 * system). If that fails for an audio file (Android may not read covers in Opus), read
 * the embedded picture from the file's tags ourselves and cache the result on disk.
 */
class Thumbnails(private val context: Context) {
    private val cache = object : LruCache<String, ImageBitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val missing: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val io = Dispatchers.IO.limitedParallelism(4)
    private val diskDir = File(context.cacheDir, "covers")

    fun cached(media: Media, widthPx: Int): ImageBitmap? = cache.get(key(media, widthPx))

    suspend fun load(media: Media, widthPx: Int): ImageBitmap? {
        val k = key(media, widthPx)
        cache.get(k)?.let { return it }
        if (k in missing) return null
        return withContext(io) {
            val size = bucket(widthPx)
            val bitmap = fromSystem(media, size) ?: if (!media.isVideo) fromTags(media, size) else null
            if (bitmap == null) {
                missing += k
                null
            } else {
                bitmap.prepareToDraw()
                bitmap.asImageBitmap().also { cache.put(k, it) }
            }
        }
    }

    private fun fromSystem(media: Media, size: Int): Bitmap? = try {
        context.contentResolver.loadThumbnail(media.uri, Size(size, size), null)
    } catch (_: Exception) {
        null
    }

    private fun fromTags(media: Media, size: Int): Bitmap? {
        // Disk cache keyed by file id + size + modification marker (file size).
        val file = File(diskDir, "${media.id}_${media.sizeBytes}_$size.jpg")
        Images.decode(file, size)?.let { return it }
        val art = TagReader.read(context, media.uri)?.artwork ?: return null
        val bmp = Images.decode(art, size) ?: return null
        runCatching { Images.saveJpeg(bmp, file, quality = 85) }
        return bmp
    }

    private fun key(media: Media, widthPx: Int) = "${media.id}:${media.isVideo}:${bucket(widthPx)}"

    private fun bucket(widthPx: Int): Int = when {
        widthPx <= 200 -> 200
        widthPx <= 480 -> 480
        else -> 960
    }
}
