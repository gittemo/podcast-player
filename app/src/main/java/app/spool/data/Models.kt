package app.spool.data

import android.net.Uri

/** One audio or video file in the library. */
data class Media(
    val id: Long,
    val uri: Uri,
    /** Stable key: relative path + file name, e.g. "Download/Seal/Audio/Show/Episode.opus". */
    val key: String,
    /** Relative folder with trailing slash, e.g. "Download/Seal/Audio/Show/". */
    val folder: String,
    val fileName: String,
    val title: String,
    /** Channel / group name shown on the home grid. */
    val group: String,
    val artist: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    /** Seconds since epoch, from MediaStore. */
    val dateAdded: Long,
    val isVideo: Boolean,
)

data class Channel(val name: String, val items: List<Media>) {
    val latest: Media get() = items.first()
}

data class Progress(
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val lastPlayed: Long,
) {
    val fraction: Float
        get() = when {
            finished -> 1f
            durationMs > 0 -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            else -> 0f
        }

    val inProgress: Boolean get() = !finished && positionMs > 5_000

    val status: PlayStatus
        get() = when {
            finished -> PlayStatus.Finished
            inProgress -> PlayStatus.InProgress
            else -> PlayStatus.New
        }
}

/**
 * Coarse play state. Unlike [Progress], it doesn't change every time a position is saved,
 * so lists and counts keyed on it only recompute when a file starts, finishes or is reset.
 */
enum class PlayStatus { New, InProgress, Finished }

/** Status of every file that has saved progress; files missing from the map are [PlayStatus.New]. */
fun Map<String, Progress>.statuses(): Map<String, PlayStatus> = mapValues { it.value.status }

object Names {
    private val idSuffix = Regex("""\s*\[[A-Za-z0-9_-]{5,}\]$""")
    private val illegal = Regex("""[\\/:*?"<>|]""")
    private val spaces = Regex("""\s+""")

    /** "Some Talk [C0LmxiZ2wt8].opus" -> "Some Talk" */
    fun displayTitle(fileName: String): String =
        fileName.substringBeforeLast('.').replace(idSuffix, "").trim().ifEmpty { fileName }

    fun extension(fileName: String): String = fileName.substringAfterLast('.', "")

    fun sanitize(name: String): String = name.replace(illegal, " ").replace(spaces, " ").trim()
}
