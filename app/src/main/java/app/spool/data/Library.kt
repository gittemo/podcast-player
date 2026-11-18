package app.spool.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import androidx.core.content.edit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads the library from MediaStore (Android's own media index), so opening the app
 * is instant: no walking folders or parsing tags on every launch.
 */
class Library(
    private val context: Context,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val _items = MutableStateFlow<List<Media>>(emptyList())
    val items: StateFlow<List<Media>> = _items.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val requests = MutableSharedFlow<Boolean>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // v2: Matroska (.webm/.mkv) tags are read now. The old cache remembered "no artist" for
    // those files, so start over once.
    private val artistCache = context.getSharedPreferences("artist_cache_v2", Context.MODE_PRIVATE)
        .also { context.deleteSharedPreferences("artist_cache") }

    private val audioUri: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val videoUri: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    init {
        scope.launch {
            requests.collectLatest { scanDisk ->
                delay(200) // coalesce bursts of change notifications
                load()
                if (scanDisk) scanUnindexedFiles()
            }
        }
        scope.launch {
            settings.root.collect { refresh(scanDisk = true) }
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        context.contentResolver.registerContentObserver(audioUri, true, observer)
        context.contentResolver.registerContentObserver(videoUri, true, observer)
    }

    fun refresh(scanDisk: Boolean = false) {
        requests.tryEmit(scanDisk)
    }

    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    private suspend fun load() = withContext(Dispatchers.IO) {
        if (!hasAccess()) {
            _items.value = emptyList()
            _loaded.value = true
            return@withContext
        }
        val root = settings.root.value
        val all = (query(audioUri, isVideo = false, root) + query(videoUri, isVideo = true, root))
            .map(::withCachedArtist)
            .sortedByDescending { it.dateAdded }
        _items.value = all
        _loaded.value = true
        resolveMissingArtists(all)
    }

    /**
     * Android's media index doesn't read the artist tag from every format (some video
     * containers). For those files, read the tag ourselves once and remember the answer.
     */
    private suspend fun resolveMissingArtists(items: List<Media>) {
        val pending = items.filter { it.artist == null && !artistCache.contains(artistKey(it)) }
        if (pending.isEmpty()) return
        val found = HashMap<String, String>()
        pending.forEachIndexed { i, m ->
            currentCoroutineContext().ensureActive()
            val artist = TagReader.read(context, m.uri)?.artist
            artistCache.edit { putString(artistKey(m), artist ?: "") }
            if (artist != null) found[m.key] = artist
            if (found.isNotEmpty() && (i % 10 == 9 || i == pending.lastIndex)) {
                val snapshot = HashMap(found)
                _items.update { list -> list.map { item -> snapshot[item.key]?.let { item.copy(artist = it, group = it) } ?: item } }
            }
        }
    }

    private fun withCachedArtist(m: Media): Media {
        if (m.artist != null) return m
        val cached = artistCache.getString(artistKey(m), null)?.takeIf { it.isNotEmpty() } ?: return m
        return m.copy(artist = cached, group = cached)
    }

    private fun artistKey(m: Media) = "${m.key}|${m.sizeBytes}"

    private fun query(collection: Uri, isVideo: Boolean, root: String): List<Media> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.ARTIST,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$root/%")
        val out = ArrayList<Media>()
        try {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.MediaColumns.ARTIST)
                val iDur = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    val rel = c.getString(iRel) ?: continue
                    if (name.startsWith(".")) continue
                    val id = c.getLong(iId)
                    val artist = c.getString(iArtist)
                        ?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                    out += Media(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        key = rel + name,
                        folder = rel,
                        fileName = name,
                        title = Names.displayTitle(name),
                        group = artist ?: UNSORTED,
                        artist = artist,
                        durationMs = c.getLong(iDur),
                        sizeBytes = c.getLong(iSize),
                        dateAdded = c.getLong(iDate),
                        isVideo = isVideo,
                    )
                }
            }
        } catch (_: SecurityException) {
            // Access was revoked; the UI will show the permission screen.
        }
        return out
    }

    /** Ask Android to index files in the library folder that MediaStore hasn't seen yet. */
    private suspend fun scanUnindexedFiles() = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext
        val storage = Environment.getExternalStorageDirectory()
        val base = File(storage, settings.root.value)
        if (!base.isDirectory) return@withContext
        val known = _items.value.mapTo(HashSet()) { it.key.lowercase() }
        val missing = base.walkTopDown()
            .maxDepth(6)
            .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in mediaExtensions }
            .filter { it.relativeTo(storage).path.lowercase() !in known }
            .map { it.absolutePath }
            .toList()
        if (missing.isNotEmpty()) {
            MediaScannerConnection.scanFile(context, missing.toTypedArray(), null, null)
        }
    }

    companion object {
        /** Channel name for files without an artist tag. */
        const val UNSORTED = "Unsorted"
        private val mediaExtensions = setOf(
            "opus", "ogg", "oga", "m4a", "mp3", "aac", "flac", "wav",
            "webm", "mp4", "m4v", "mkv", "mov", "3gp",
        )
    }
}
