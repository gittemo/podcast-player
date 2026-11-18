package app.spool.data

import android.content.Context
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Covers: Android extracts embedded art (audio) or a frame (video) and caches it on disk.
 * We keep decoded bitmaps in a memory cache so scrolling stays smooth.
 */
class Thumbnails(private val context: Context) {
    private val cache = object : LruCache<String, ImageBitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val missing: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val io = Dispatchers.IO.limitedParallelism(4)

    fun cached(media: Media, widthPx: Int): ImageBitmap? = cache.get(key(media, widthPx))

    suspend fun load(media: Media, widthPx: Int): ImageBitmap? {
        val k = key(media, widthPx)
        cache.get(k)?.let { return it }
        if (k in missing) return null
        return withContext(io) {
            try {
                val size = bucket(widthPx)
                val bitmap = context.contentResolver.loadThumbnail(media.uri, Size(size, size), null)
                bitmap.prepareToDraw()
                bitmap.asImageBitmap().also { cache.put(k, it) }
            } catch (_: Exception) {
                missing += k
                null
            }
        }
    }

    private fun key(media: Media, widthPx: Int) = "${media.id}:${media.isVideo}:${bucket(widthPx)}"

    private fun bucket(widthPx: Int): Int = when {
        widthPx <= 200 -> 200
        widthPx <= 480 -> 480
        else -> 960
    }
}
