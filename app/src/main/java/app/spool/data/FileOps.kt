package app.spool.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileOps(
    private val context: Context,
    private val library: Library,
    private val progress: ProgressStore,
) {
    /** Renames the file on disk. Progress follows the file. */
    suspend fun rename(media: Media, newTitle: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = Names.sanitize(newTitle)
            require(clean.isNotEmpty()) { "Name can't be empty" }
            val ext = Names.extension(media.fileName)
            val newName = if (ext.isEmpty()) clean else "$clean.$ext"
            if (newName == media.fileName) return@runCatching

            val storage = Environment.getExternalStorageDirectory()
            val src = File(storage, media.folder + media.fileName)
            val dst = File(storage, media.folder + newName)
            check(!dst.exists()) { "A file with that name already exists" }

            val viaMediaStore = try {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
                context.contentResolver.update(media.uri, values, null, null) > 0
            } catch (_: Exception) {
                false
            }
            if (!viaMediaStore) {
                check(src.renameTo(dst)) { "Couldn't rename the file" }
                scan(src, dst)
            }
            progress.rename(media.key, media.folder + newName)
            library.refresh()
        }
    }

    suspend fun delete(media: Media): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val viaMediaStore = try {
                context.contentResolver.delete(media.uri, null, null) > 0
            } catch (_: Exception) {
                false
            }
            if (!viaMediaStore) {
                val file = File(Environment.getExternalStorageDirectory(), media.key)
                check(file.delete()) { "Couldn't delete the file" }
                scan(file)
            }
            progress.remove(media.key)
            library.refresh()
        }
    }

    private fun scan(vararg files: File) {
        MediaScannerConnection.scanFile(context, files.map { it.absolutePath }.toTypedArray(), null, null)
    }
}
