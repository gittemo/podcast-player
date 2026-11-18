package app.spool.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Reads the tags of a Matroska file (.webm, .mkv, .mka). Neither Android's media index nor
 * Media3 reads these, but yt-dlp ("Embed metadata" in Seal) writes the channel name there
 * as ARTIST, plus TITLE, PURL, COMMENT, DESCRIPTION and so on.
 *
 * Only the file's header area is read: the Tags element is found through the SeekHead
 * (or by stepping over top-level elements), never by reading the media data.
 */
object MatroskaTags {
    class Result(
        /** Tags in file order. Names are as written (ffmpeg writes them upper-case). */
        val tags: List<Pair<String, String>>,
        /** Segment title from the Info element (ffmpeg stores the title there, not in Tags). */
        val title: String?,
    )

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TITLE = 0x7BA9L
    private const val ID_TAGS = 0x1254C367L
    private const val ID_TAG = 0x7373L
    private const val ID_TARGETS = 0x63C0L
    private const val ID_TAG_TRACK_UID = 0x63C5L
    private const val ID_TAG_CHAPTER_UID = 0x63C4L
    private const val ID_TAG_ATTACHMENT_UID = 0x63C6L
    private const val ID_SIMPLE_TAG = 0x67C8L
    private const val ID_TAG_NAME = 0x45A3L
    private const val ID_TAG_STRING = 0x4487L

    private const val UNKNOWN_SIZE = -1L
    private const val MAX_MASTER_BYTES = 4 * 1024 * 1024
    private const val MAX_TOP_LEVEL_STEPS = 50_000

    /** Blocking. Returns null if the file isn't Matroska or can't be read. */
    fun read(context: Context, uri: Uri): Result? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { parse(it.channel) }
        }
    } catch (_: Exception) {
        null
    }

    private class Header(val id: Long, val size: Long, val dataStart: Long)

    /** Visible for tests. */
    internal fun parse(ch: FileChannel): Result? {
        val fileSize = ch.size()
        val ebml = readHeader(ch, 0) ?: return null
        if (ebml.id != ID_EBML || ebml.size == UNKNOWN_SIZE) return null
        val segment = readHeader(ch, ebml.dataStart + ebml.size) ?: return null
        if (segment.id != ID_SEGMENT) return null
        val segmentStart = segment.dataStart
        val segmentEnd = if (segment.size == UNKNOWN_SIZE) fileSize else minOf(fileSize, segmentStart + segment.size)

        var title: String? = null
        var tagsAt: Long? = null
        var pos = segmentStart
        var steps = 0
        while (pos < segmentEnd && steps++ < MAX_TOP_LEVEL_STEPS) {
            val h = readHeader(ch, pos) ?: break
            when (h.id) {
                ID_SEEK_HEAD -> readMaster(ch, h)?.let { data ->
                    for (seek in children(data, 0, data.size)) {
                        if (seek.id != ID_SEEK) continue
                        var target = 0L
                        var offset = -1L
                        for (f in children(data, seek.start, seek.end)) {
                            when (f.id) {
                                ID_SEEK_ID -> target = uint(data, f.start, f.end)
                                ID_SEEK_POSITION -> offset = uint(data, f.start, f.end)
                            }
                        }
                        if (target == ID_TAGS && offset >= 0) tagsAt = segmentStart + offset
                    }
                }

                ID_INFO -> readMaster(ch, h)?.let { data ->
                    children(data, 0, data.size).firstOrNull { it.id == ID_TITLE }?.let {
                        title = string(data, it.start, it.end).ifBlank { null }
                    }
                }

                ID_TAGS -> {
                    val data = readMaster(ch, h) ?: return Result(emptyList(), title)
                    return Result(parseTags(data), title)
                }
            }
            // Reached the media data: jump straight to the tags if the index says where they are.
            val jump = tagsAt
            if (h.id == ID_CLUSTER && jump != null && jump > pos) {
                tagsAt = null
                pos = jump
                continue
            }
            if (h.size == UNKNOWN_SIZE) break // live-style element; nothing to step over
            pos = h.dataStart + h.size
        }
        return Result(emptyList(), title)
    }

    private fun parseTags(data: ByteArray): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (tag in children(data, 0, data.size)) {
            if (tag.id != ID_TAG) continue
            val scoped = children(data, tag.start, tag.end)
                .filter { it.id == ID_TARGETS }
                .any { t ->
                    children(data, t.start, t.end).any {
                        it.id == ID_TAG_TRACK_UID || it.id == ID_TAG_CHAPTER_UID || it.id == ID_TAG_ATTACHMENT_UID
                    }
                }
            for (st in children(data, tag.start, tag.end)) {
                if (st.id == ID_SIMPLE_TAG) simpleTag(data, st, prefix = "", scoped = scoped, out = out)
            }
        }
        return out
    }

    private fun simpleTag(data: ByteArray, el: El, prefix: String, scoped: Boolean, out: MutableList<Pair<String, String>>) {
        var name: String? = null
        var value: String? = null
        val nested = ArrayList<El>()
        for (c in children(data, el.start, el.end)) {
            when (c.id) {
                ID_TAG_NAME -> name = string(data, c.start, c.end)
                ID_TAG_STRING -> value = string(data, c.start, c.end)
                ID_SIMPLE_TAG -> nested += c
            }
        }
        val fullName = prefix + (name ?: return)
        if (!value.isNullOrBlank()) out += (if (scoped) "$fullName (track)" else fullName) to value
        nested.forEach { simpleTag(data, it, "$fullName.", scoped, out) }
    }

    // --- EBML primitives ---

    private class El(val id: Long, val start: Int, val end: Int)

    /** Child elements of a master element held in memory. Stops at the first malformed one. */
    private fun children(data: ByteArray, from: Int, to: Int): Sequence<El> = sequence {
        var p = from
        while (p < to) {
            val (id, idLen) = vint(data, p, keepMarker = true) ?: return@sequence
            val (size, sizeLen) = vint(data, p + idLen, keepMarker = false) ?: return@sequence
            val start = p + idLen + sizeLen
            val end = if (size == UNKNOWN_SIZE) to else (start + size).coerceAtMost(to.toLong()).toInt()
            if (start > to) return@sequence
            yield(El(id, start, end))
            p = end
        }
    }

    private fun readHeader(ch: FileChannel, pos: Long): Header? {
        val buf = ByteBuffer.allocate(12)
        val n = ch.read(buf, pos)
        if (n <= 0) return null
        val bytes = buf.array().copyOf(n)
        val (id, idLen) = vint(bytes, 0, keepMarker = true) ?: return null
        val (size, sizeLen) = vint(bytes, idLen, keepMarker = false) ?: return null
        return Header(id, size, pos + idLen + sizeLen)
    }

    private fun readMaster(ch: FileChannel, h: Header): ByteArray? {
        if (h.size == UNKNOWN_SIZE || h.size > MAX_MASTER_BYTES) return null
        val buf = ByteBuffer.allocate(h.size.toInt())
        var p = h.dataStart
        while (buf.hasRemaining()) {
            val n = ch.read(buf, p)
            if (n <= 0) break
            p += n
        }
        return buf.array().copyOf(buf.position())
    }

    /**
     * Variable-length integer. IDs keep their length marker bit; sizes drop it.
     * Returns the value and its length in bytes, or null if malformed.
     */
    private fun vint(data: ByteArray, at: Int, keepMarker: Boolean): Pair<Long, Int>? {
        if (at >= data.size) return null
        val first = data[at].toInt() and 0xFF
        if (first == 0) return null
        val len = Integer.numberOfLeadingZeros(first) - 23 // 24 leading zeros in an Int for a byte, +1
        if (len > (if (keepMarker) 4 else 8) || at + len > data.size) return null
        var value = if (keepMarker) first.toLong() else (first and (0xFF shr len)).toLong()
        var allOnes = (first and (0xFF shr len)) == (0xFF shr len)
        for (i in 1 until len) {
            val b = data[at + i].toInt() and 0xFF
            if (b != 0xFF) allOnes = false
            value = (value shl 8) or b.toLong()
        }
        if (!keepMarker && allOnes) return UNKNOWN_SIZE to len
        return value to len
    }

    private fun uint(data: ByteArray, from: Int, to: Int): Long {
        var v = 0L
        for (i in from until minOf(to, from + 8)) v = (v shl 8) or (data[i].toLong() and 0xFF)
        return v
    }

    private fun string(data: ByteArray, from: Int, to: Int): String =
        String(data, from, (to - from).coerceAtLeast(0), Charsets.UTF_8).trimEnd('\u0000').trim()
}
