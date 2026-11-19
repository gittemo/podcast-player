package app.spool.ui

import androidx.media3.common.Player
import app.spool.data.Media
import app.spool.data.Progress
import app.spool.playback.resumePositionMs
import app.spool.playback.toMediaItem

/*
 * Player state for the UI comes from media3-ui-compose's state holders
 * (rememberPlayPauseButtonState, rememberProgressStateWithTickInterval, ...), which listen
 * to the player and update only what changed.
 */

/** Start (or resume) a file from where it was left. */
fun Player.playMedia(media: Media, progress: Progress?) {
    if (currentMediaItem?.mediaId == media.key) {
        if (playbackState == Player.STATE_ENDED) seekTo(0)
        if (playbackState == Player.STATE_IDLE) prepare()
        play()
        return
    }
    setMediaItem(media.toMediaItem(), resumePositionMs(progress))
    prepare()
    play()
}
