package app.spool.data

import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Channel pictures, stored in the app's private storage. Either picked from the gallery,
 * or fetched once from YouTube: file tags -> video link -> oEmbed (channel URL) ->
 * channel page's og:image. This is the only part of the app that uses the network,
 * and only when the user asks for it.
 */
class ChannelArt(private val context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "channel_art").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("channel_art", Context.MODE_PRIVATE)
    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** Channel name -> version (changes whenever its picture changes). */
    private val _versions = MutableStateFlow(readVersions())
    val versions: StateFlow<Map<String, Long>> = _versions.asStateFlow()

    /** Progress text for "get missing pictures", or null when idle. */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()
    private var bulkJob: Job? = null

    suspend fun load(name: String, widthPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val version = _versions.value[name] ?: return@withContext null
        val size = if (widthPx <= 256) 256 else 512
        val key = "$name:$version:$size"
        cache.get(key) ?: Images.decode(fileFor(name), size)?.asImageBitmap()?.also { cache.put(key, it) }
    }

    suspend fun setFromUri(name: String, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Couldn't open that image")
            save(name, bytes)
        }
    }

    fun remove(name: String) {
        fileFor(name).delete()
        prefs.edit { remove(name) }
        _versions.update { it - name }
    }

    /** Fetch one channel's picture from YouTube using the links in its files' tags. */
    suspend fun fetchFromYouTube(channel: Channel): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            var videoUrl: String? = null
            for (media in channel.items.take(8)) {
                val tags = TagReader.read(context, media.uri) ?: continue
                videoUrl = TagReader.youTubeUrl(tags)
                if (videoUrl != null) break
            }
            checkNotNull(videoUrl) { "No YouTube link in this channel's files. Is \"Embed metadata\" on in Seal?" }

            val oembed = JSONObject(httpText("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(videoUrl, "UTF-8")))
            val channelUrl = oembed.optString("author_url").ifBlank { error("YouTube didn't say which channel this is") }

            val page = httpText(channelUrl)
            val imageUrl = ogImage.find(page)?.groupValues?.get(1)?.replace("&amp;", "&")
                ?: error("Couldn't find the channel picture on YouTube")
            save(channel.name, httpBytes(imageUrl))
        }
    }

    /** Fetch pictures for every channel that doesn't have one yet. Runs in the background. */
    fun fetchMissing(items: List<Media>) {
        if (bulkJob?.isActive == true) return
        bulkJob = scope.launch {
            val missing = items.groupBy { it.group }
                .filter { (name, _) -> name != "Unsorted" && _versions.value[name] == null }
                .map { (name, list) -> Channel(name, list) }
            if (missing.isEmpty()) {
                _status.value = "Every channel already has a picture"
                return@launch
            }
            var ok = 0
            missing.forEachIndexed { i, channel ->
                _status.value = "Getting pictures… ${i + 1} of ${missing.size}"
                if (fetchFromYouTube(channel).isSuccess) ok++
            }
            _status.value = "Got $ok of ${missing.size} channel pictures" +
                if (ok < missing.size) ". The rest have no YouTube link in their files." else ""
        }
    }

    private fun save(name: String, bytes: ByteArray) {
        val bitmap = Images.decode(bytes, 512) ?: error("That isn't an image")
        Images.saveJpeg(bitmap, fileFor(name))
        bitmap.recycle()
        val version = System.currentTimeMillis()
        prefs.edit { putLong(name, version) }
        _versions.update { it + (name to version) }
    }

    private fun readVersions(): Map<String, Long> =
        prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }
            .filter { (name, _) -> fileFor(name).isFile }
            .toMap()

    private fun fileFor(name: String) = File(dir, sha1(name) + ".jpg")

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "en-US,en;q=0.8")
            // Skip the EU cookie-consent interstitial.
            setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+1")
        }

    private fun httpText(url: String): String {
        val conn = open(url)
        try {
            check(conn.responseCode == 200) { "YouTube answered ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpBytes(url: String): ByteArray {
        val conn = open(url)
        try {
            check(conn.responseCode == 200) { "Image download failed (${conn.responseCode})" }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val ogImage = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""")
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"
    }
}
