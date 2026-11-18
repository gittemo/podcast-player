@file:OptIn(UnstableApi::class)

package app.spool.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import java.util.concurrent.TimeUnit

/**
 * Reads a file's own tags with Media3's extractors (no playback). Works for Opus/Ogg
 * Vorbis comments and embedded pictures, MP3 ID3 and MP4 tags.
 */
object TagReader {
    class Tags(
        /** Every tag rendered as text, one per line. Good enough to search for links. */
        val text: String,
        /** Embedded cover image, if any. */
        val artwork: ByteArray?,
        /** Artist tag (yt-dlp writes the channel name here). */
        val artist: String?,
    )

    /** Blocking. Call from a background thread. */
    fun read(context: Context, uri: Uri): Tags? = try {
        val groups = MetadataRetriever.retrieveMetadata(context, MediaItem.fromUri(uri))
            .get(10, TimeUnit.SECONDS)
        val text = StringBuilder()
        val meta = MediaMetadata.Builder()
        for (i in 0 until groups.length) {
            val group = groups.get(i)
            for (j in 0 until group.length) {
                val md = group.getFormat(j).metadata ?: continue
                for (k in 0 until md.length()) {
                    val entry = md.get(k)
                    text.append(entry.toString()).append('\n')
                    entry.populateMediaMetadata(meta)
                }
            }
        }
        val built = meta.build()
        val artist = (built.artist ?: built.albumArtist)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        Tags(text.toString(), built.artworkData, artist)
    } catch (_: Exception) {
        null
    }

    private val youTube = Regex(
        """https?://(?:www\.|m\.|music\.)?(?:youtube\.com/(?:watch\?(?:[^\s]*&)?v=|shorts/|live/)|youtu\.be/)([A-Za-z0-9_-]{11})""",
    )

    /** The YouTube video link yt-dlp writes into the comment/purl tag, normalized. */
    fun youTubeUrl(tags: Tags): String? =
        youTube.find(tags.text)?.groupValues?.get(1)?.let { "https://www.youtube.com/watch?v=$it" }
}
