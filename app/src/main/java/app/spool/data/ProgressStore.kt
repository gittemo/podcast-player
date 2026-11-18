package app.spool.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Per-file playback progress. Kept fully in memory for instant UI, written through to
 * SQLite on a single background thread so writes never race.
 */
class ProgressStore(context: Context, private val scope: CoroutineScope) {
    private val helper = Helper(context)
    private val writer = Dispatchers.IO.limitedParallelism(1)

    private val _all = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val all: StateFlow<Map<String, Progress>> = _all.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    /** True once the saved progress has been read from disk. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch(writer) {
            val saved = readAll()
            _all.update { saved + it } // anything saved before loading finished wins
            _loaded.value = true
        }
    }

    /** The most recently played file that isn't finished, among [candidates]. */
    fun lastUnfinished(candidates: Map<String, Media>): Media? =
        _all.value.entries
            .filter { !it.value.finished && candidates.containsKey(it.key) }
            .maxByOrNull { it.value.lastPlayed }
            ?.let { candidates[it.key] }

    operator fun get(key: String): Progress? = _all.value[key]

    /** Save a position. Marks the file finished when it's within 15s of the end. */
    fun save(key: String, positionMs: Long, durationMs: Long) {
        val prev = _all.value[key]
        val duration = if (durationMs > 0) durationMs else prev?.durationMs ?: 0L
        val finished = duration > 0 && positionMs >= duration - FINISH_MARGIN_MS
        put(key, Progress(positionMs.coerceAtLeast(0), duration, finished, System.currentTimeMillis()))
    }

    fun setFinished(key: String, finished: Boolean, durationMs: Long = 0L) {
        val prev = _all.value[key]
        val duration = if (durationMs > 0) durationMs else prev?.durationMs ?: 0L
        val position = if (finished) duration else 0L
        put(key, Progress(position, duration, finished, prev?.lastPlayed ?: System.currentTimeMillis()))
    }

    fun rename(oldKey: String, newKey: String) {
        val p = _all.value[oldKey] ?: return
        _all.update { it - oldKey + (newKey to p) }
        scope.launch(writer) {
            helper.writableDatabase.delete(TABLE, "key = ?", arrayOf(oldKey))
            write(newKey, p)
        }
    }

    fun remove(key: String) {
        _all.update { it - key }
        scope.launch(writer) { helper.writableDatabase.delete(TABLE, "key = ?", arrayOf(key)) }
    }

    private fun put(key: String, p: Progress) {
        _all.update { it + (key to p) }
        scope.launch(writer) { write(key, p) }
    }

    private fun write(key: String, p: Progress) {
        val values = ContentValues().apply {
            put("key", key)
            put("position", p.positionMs)
            put("duration", p.durationMs)
            put("finished", if (p.finished) 1 else 0)
            put("last_played", p.lastPlayed)
        }
        helper.writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun readAll(): Map<String, Progress> {
        val out = HashMap<String, Progress>()
        helper.readableDatabase.query(
            TABLE, arrayOf("key", "position", "duration", "finished", "last_played"),
            null, null, null, null, null,
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = Progress(c.getLong(1), c.getLong(2), c.getInt(3) == 1, c.getLong(4))
            }
        }
        return out
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "spool.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (key TEXT PRIMARY KEY, position INTEGER NOT NULL, " +
                    "duration INTEGER NOT NULL, finished INTEGER NOT NULL, last_played INTEGER NOT NULL)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        private const val TABLE = "progress"
        const val FINISH_MARGIN_MS = 15_000L
    }
}
