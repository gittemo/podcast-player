package app.spool.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.max

object Images {
    /** Decode so the longest side is at most [maxPx]. */
    fun decode(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx) }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return scaleDown(bmp, maxPx)
    }

    fun decode(file: File, maxPx: Int): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx) }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        return scaleDown(bmp, maxPx)
    }

    /** Write as JPEG atomically (temp file, then rename). */
    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 90) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun sampleSize(w: Int, h: Int, maxPx: Int): Int {
        var sample = 1
        while (max(w, h) / (sample * 2) >= maxPx) sample *= 2
        return sample
    }

    private fun scaleDown(bmp: Bitmap, maxPx: Int): Bitmap {
        val longest = max(bmp.width, bmp.height)
        if (longest <= maxPx) return bmp
        val scale = maxPx.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }
}
