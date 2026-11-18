package app.spool.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/** Builds small Matroska files laid out the way ffmpeg (yt-dlp/Seal) writes them. */
class MatroskaTagsTest {

    private fun idBytes(id: Long): ByteArray {
        val n = when {
            id > 0xFFFFFF -> 4
            id > 0xFFFF -> 3
            id > 0xFF -> 2
            else -> 1
        }
        return ByteArray(n) { i -> (id shr (8 * (n - 1 - i))).toByte() }
    }

    private fun size(n: Long): ByteArray =
        if (n < 0x7F) {
            byteArrayOf((0x80 or n.toInt()).toByte())
        } else {
            ByteArray(8) { i -> if (i == 0) 0x01 else (n shr (8 * (7 - i))).toByte() }
        }

    private fun el(id: Long, vararg payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { payload.forEach { write(it) } }.toByteArray()
        return idBytes(id) + size(body.size.toLong()) + body
    }

    private fun str(id: Long, s: String) = el(id, s.toByteArray())
    private fun uint8(id: Long, v: Long) = el(id, ByteArray(8) { i -> (v shr (8 * (7 - i))).toByte() })

    private val ebmlHeader = el(0x1A45DFA3, str(0x4282, "webm"))
    private val unknownSize = byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1)

    private val info = el(0x1549A966, str(0x7BA9, "My Video Title"))
    private val tracks = el(0x1654AE6B, el(0xAE, uint8(0xD7, 1)))
    private val cluster = el(0x1F43B675, ByteArray(200_000) { 7 })
    private val tags = el(
        0x1254C367,
        el(
            0x7373,
            el(0x63C0),
            el(0x67C8, str(0x45A3, "ARTIST"), str(0x4487, "Some Channel")),
            el(0x67C8, str(0x45A3, "PURL"), str(0x4487, "https://www.youtube.com/watch?v=abcdefghijk")),
        ),
        el(
            0x7373,
            el(0x63C0, uint8(0x63C5, 1)),
            el(0x67C8, str(0x45A3, "DURATION"), str(0x4487, "00:10:00.000000000")),
        ),
    )

    private fun parse(bytes: ByteArray): MatroskaTags.Result? {
        val f = File.createTempFile("spool", ".webm")
        try {
            f.writeBytes(bytes)
            return RandomAccessFile(f, "r").use { MatroskaTags.parse(it.channel) }
        } finally {
            f.delete()
        }
    }

    private fun assertSeal(r: MatroskaTags.Result?) {
        requireNotNull(r)
        assertEquals("My Video Title", r.title)
        assertTrue(r.tags.contains("ARTIST" to "Some Channel"))
        assertTrue(r.tags.contains("PURL" to "https://www.youtube.com/watch?v=abcdefghijk"))
        assertTrue(r.tags.contains("DURATION (track)" to "00:10:00.000000000"))
    }

    @Test
    fun tagsBeforeMediaData() {
        // Current ffmpeg: Tags in the header area, before the first Cluster.
        val segment = byteArrayOf(0x18, 0x53, -128, 0x67) + unknownSize + info + tracks + tags + cluster
        assertSeal(parse(ebmlHeader + segment))
    }

    @Test
    fun tagsAfterMediaDataFoundThroughSeekHead() {
        // Older ffmpeg: Tags after the Clusters, located through the SeekHead.
        fun seekHead(pos: Long) = el(0x114D9B74, el(0x4DBB, el(0x53AB, idBytes(0x1254C367)), uint8(0x53AC, pos)))
        val headLen = seekHead(0).size
        val tagsOffset = (headLen + info.size + tracks.size + cluster.size).toLong()
        val children = seekHead(tagsOffset) + info + tracks + cluster + tags
        val segment = byteArrayOf(0x18, 0x53, -128, 0x67) + size(children.size.toLong()) + children
        assertSeal(parse(ebmlHeader + segment))
    }

    @Test
    fun tagsAfterMediaDataWithoutIndex() {
        // No SeekHead: step over the clusters by their sizes.
        val segment = byteArrayOf(0x18, 0x53, -128, 0x67) + unknownSize + info + tracks + cluster + cluster + tags
        assertSeal(parse(ebmlHeader + segment))
    }

    @Test
    fun notMatroska() {
        assertNull(parse("OggS".toByteArray() + ByteArray(100)))
        assertNull(parse(ByteArray(0)))
    }
}
