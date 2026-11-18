package app.spool.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.spool.data.Media
import app.spool.data.Progress

/**
 * The player item for a file. The URI goes in both places: `localConfiguration` for the
 * service's own use, and `requestMetadata` because a MediaController strips the former
 * when sending items to the session.
 */
fun Media.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(key)
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(group)
                .setMediaType(if (isVideo) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()

/** Where to start a file: where it was left, minus a little overlap for context. */
fun resumePositionMs(progress: Progress?): Long = when {
    progress == null || progress.finished -> 0L
    else -> (progress.positionMs - 2_000).coerceAtLeast(0L)
}
