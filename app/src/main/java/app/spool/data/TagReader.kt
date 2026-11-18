@file:androidx.annotation.OptIn(UnstableApi::class)

package app.spool.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.id3.UrlLinkFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.inspector.MetadataRetriever
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reads a file's own tags without playing it: Media3's extractors for Opus/Ogg Vorbis
 * comments, MP3 ID3 and MP4 tags, plus our own reader for Matroska (.webm/.mkv), which
 * Media3 and Android's media index both skip.
 */
object TagReader {
    class Tags(
        /** Every tag as (name, value), in file order. Names are made readable where known. */
        val entries: List<Pair<String, String>>,
        /** One line per stream, e.g. "Video · VP9 · 1920×1080 · 30 fps". Only when asked for. */
        val streams: List<String>,
        /** Embedded cover image, if any. */
        val artwork: ByteArray?,
        /** Artist tag (yt-dlp writes the channel name here). */
        val artist: String?,
    ) {
        /** All tags as text. Good enough to search for links. */
        val text: String get() = entries.joinToString("\n") { "${it.first}: ${it.second}" }

        companion object {
            val EMPTY = Tags(emptyList(), emptyList(), artwork = null, artist = null)
        }
    }

    /** Blocking. Call from a background thread. */
    fun read(context: Context, uri: Uri, withStreams: Boolean = false): Tags? {
        // Matroska first: it's cheap (header only) and Media3 can't read its tags anyway.
        val mkv = MatroskaTags.read(context, uri)
        if (mkv != null) {
            val entries = buildList {
                mkv.title?.let { add("Title" to it) }
                mkv.tags.forEach { (k, v) -> add(friendlyName(k) to v) }
            }
            val artist = firstOf(mkv.tags, "ARTIST", "ALBUM_ARTIST", "ALBUMARTIST", "PERFORMER", "UPLOADER")
            val streams = if (withStreams) retrieve(context, uri)?.let(::describeStreams).orEmpty() else emptyList()
            return Tags(entries, streams, artwork = null, artist = artist)
        }

        val groups = retrieve(context, uri) ?: return null
        val entries = ArrayList<Pair<String, String>>()
        val meta = MediaMetadata.Builder()
        forEachEntry(groups) { entry ->
            describe(entry)?.let(entries::add)
            entry.populateMediaMetadata(meta)
        }
        val built = meta.build()
        val artist = (built.artist ?: built.albumArtist)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val streams = if (withStreams) describeStreams(groups) else emptyList()
        return Tags(entries, streams, built.artworkData, artist)
    }

    private fun retrieve(context: Context, uri: Uri): TrackGroupArray? = try {
        MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build().use {
            it.retrieveTrackGroups().get(10, TimeUnit.SECONDS)
        }
    } catch (_: Exception) {
        null
    }

    private inline fun forEachEntry(groups: TrackGroupArray, block: (Metadata.Entry) -> Unit) {
        for (i in 0 until groups.length) {
            val group = groups.get(i)
            for (j in 0 until group.length) {
                val md = group.getFormat(j).metadata ?: continue
                for (k in 0 until md.length()) block(md.get(k))
            }
        }
    }

    private fun firstOf(tags: List<Pair<String, String>>, vararg names: String): String? {
        for (name in names) {
            tags.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    /** A tag as (readable name, value), or null for entries with nothing to show. */
    private fun describe(entry: Metadata.Entry): Pair<String, String>? = when (entry) {
        is VorbisComment -> friendlyName(entry.key) to entry.value
        is TextInformationFrame -> {
            val name = if (entry.id == "TXXX" && !entry.description.isNullOrBlank()) {
                friendlyName(entry.description!!)
            } else {
                id3Names[entry.id] ?: entry.id
            }
            name to entry.values.joinToString(", ")
        }
        is CommentFrame -> (entry.description.takeIf { it.isNotBlank() }?.let(::friendlyName) ?: "Comment") to entry.text
        is UrlLinkFrame -> (entry.description?.takeIf { it.isNotBlank() } ?: id3Names[entry.id] ?: entry.id) to entry.url
        is InternalFrame -> friendlyName(entry.description) to entry.text
        is MdtaMetadataEntry -> mdtaValue(entry)?.let { friendlyName(entry.key.substringAfterLast('.')) to it }
        is ApicFrame -> "Picture" to entry.mimeType
        is PictureFrame -> "Picture" to "${entry.mimeType}, ${entry.width}×${entry.height}"
        else -> null
    }?.takeIf { it.second.isNotBlank() }

    private fun mdtaValue(e: MdtaMetadataEntry): String? = when (e.typeIndicator) {
        MdtaMetadataEntry.TYPE_INDICATOR_STRING -> String(e.value, Charsets.UTF_8)
        MdtaMetadataEntry.TYPE_INDICATOR_INT32 -> if (e.value.size >= 4) ByteBuffer.wrap(e.value).int.toString() else null
        MdtaMetadataEntry.TYPE_INDICATOR_FLOAT32 -> if (e.value.size >= 4) ByteBuffer.wrap(e.value).float.toString() else null
        else -> null
    }

    private fun describeStreams(groups: TrackGroupArray): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until groups.length) {
            val group = groups.get(i)
            for (j in 0 until group.length) out += describeFormat(group.type, group.getFormat(j))
        }
        return out
    }

    private fun describeFormat(type: Int, f: Format): String {
        val parts = ArrayList<String>()
        parts += when (type) {
            C.TRACK_TYPE_VIDEO -> "Video"
            C.TRACK_TYPE_AUDIO -> "Audio"
            C.TRACK_TYPE_TEXT -> "Subtitles"
            C.TRACK_TYPE_IMAGE -> "Image"
            else -> "Stream"
        }
        (f.sampleMimeType ?: f.containerMimeType)?.let { parts += codecName(it) }
        if (f.width > 0 && f.height > 0) parts += "${f.width}×${f.height}"
        if (f.frameRate > 0) parts += String.format(Locale.US, "%.3g fps", f.frameRate)
        if (f.sampleRate > 0) parts += "${f.sampleRate / 1000.0} kHz".replace(".0 ", " ")
        if (f.channelCount > 0) parts += if (f.channelCount == 1) "mono" else if (f.channelCount == 2) "stereo" else "${f.channelCount} ch"
        val bitrate = if (f.averageBitrate > 0) f.averageBitrate else f.bitrate
        if (bitrate > 0) parts += "${bitrate / 1000} kbps"
        f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let { parts += it }
        return parts.joinToString(" · ")
    }

    private fun codecName(mime: String): String = when (mime) {
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "H.265"
        MimeTypes.VIDEO_VP8 -> "VP8"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_AC3 -> "AC-3"
        MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
        else -> mime.substringAfter('/')
    }

    private val id3Names = mapOf(
        "TIT2" to "Title", "TPE1" to "Artist", "TPE2" to "Album artist", "TALB" to "Album",
        "TCON" to "Genre", "TDRC" to "Date", "TYER" to "Year", "TDAT" to "Date", "TRCK" to "Track",
        "TPOS" to "Disc", "TCOM" to "Composer", "TSSE" to "Encoder", "TENC" to "Encoded by",
        "TCOP" to "Copyright", "TPUB" to "Publisher", "TLAN" to "Language", "TLEN" to "Length",
        "WOAS" to "Source", "WOAR" to "Artist page", "WXXX" to "Link", "COMM" to "Comment",
        "TDES" to "Description", "TIT3" to "Subtitle",
    )

    private val knownNames = mapOf(
        "ALBUM_ARTIST" to "Album artist", "ALBUMARTIST" to "Album artist", "PURL" to "Source link",
        "DATE" to "Date", "DESCRIPTION" to "Description", "SYNOPSIS" to "Synopsis",
        "ENCODER" to "Encoder", "TRACKNUMBER" to "Track", "DISCNUMBER" to "Disc",
        "COMMENT" to "Comment", "COMPOSER" to "Composer", "GENRE" to "Genre", "ARTIST" to "Artist",
        "TITLE" to "Title", "ALBUM" to "Album", "HANDLER_NAME" to "Handler", "DURATION" to "Duration",
        "VENDOR_ID" to "Vendor", "MAJOR_BRAND" to "Major brand", "COMPATIBLE_BRANDS" to "Compatible brands",
        "MINOR_VERSION" to "Minor version", "METADATA_BLOCK_PICTURE" to "Picture",
    )

    /** "ALBUM_ARTIST" -> "Album artist", "purl" -> "Source link"; unknown names get sentence case. */
    private fun friendlyName(raw: String): String {
        val base = raw.substringBefore(" (track)")
        val suffix = raw.removePrefix(base)
        knownNames[base.uppercase()]?.let { return it + suffix }
        val words = base.replace('_', ' ').trim()
        val pretty = if (words == words.uppercase() || words == words.lowercase()) {
            words.lowercase().replaceFirstChar { it.titlecase() }
        } else {
            words
        }
        return pretty + suffix
    }

    private val youTube = Regex(
        """https?://(?:www\.|m\.|music\.)?(?:youtube\.com/(?:watch\?(?:[^\s]*&)?v=|shorts/|live/)|youtu\.be/)([A-Za-z0-9_-]{11})""",
    )

    /** The YouTube video link yt-dlp writes into the comment/purl tag, normalized. */
    fun youTubeUrl(tags: Tags): String? =
        youTube.find(tags.text)?.groupValues?.get(1)?.let { "https://www.youtube.com/watch?v=$it" }
}
