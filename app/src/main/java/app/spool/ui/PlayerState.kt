@file:androidx.annotation.OptIn(UnstableApi::class)

package app.spool.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import app.spool.data.Media
import app.spool.data.Progress
import app.spool.playback.resumePositionMs
import app.spool.playback.toMediaItem
import kotlinx.coroutines.delay

/** Snapshot of the player for the UI. Only the composables that read a field recompose. */
class PlayerUiState {
    var mediaId by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    /** Whether the play/pause button should show Play (Media3's rule: covers buffering and focus loss). */
    var showPlay by mutableStateOf(true)
    var isBuffering by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
    var videoAspect by mutableFloatStateOf(0f)
}

@Composable
fun rememberPlayerUiState(player: Player?): PlayerUiState {
    val state = remember { PlayerUiState() }
    DisposableEffect(player) {
        if (player == null) {
            state.isPlaying = false
            state.showPlay = true
            return@DisposableEffect onDispose { }
        }
        fun sync() {
            state.mediaId = player.currentMediaItem?.mediaId
            state.isPlaying = player.isPlaying
            state.showPlay = Util.shouldShowPlayButton(player)
            state.isBuffering = player.playbackState == Player.STATE_BUFFERING
            state.ended = player.playbackState == Player.STATE_ENDED
            state.positionMs = player.currentPosition.coerceAtLeast(0L)
            val d = player.duration
            state.durationMs = if (d == C.TIME_UNSET) 0L else d
            state.speed = player.playbackParameters.speed
            val vs = player.videoSize
            state.videoAspect =
                if (vs.width > 0 && vs.height > 0) vs.width * vs.pixelWidthHeightRatio / vs.height else 0f
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync()
        }
        player.addListener(listener)
        sync()
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, state.isPlaying) {
        while (player != null && state.isPlaying) {
            state.positionMs = player.currentPosition.coerceAtLeast(0L)
            delay(250)
        }
    }
    return state
}

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

/** Play/pause like Media3's own buttons: also prepares an idle player and restarts an ended one. */
fun Player.togglePlay() {
    Util.handlePlayPauseButtonAction(this)
}
