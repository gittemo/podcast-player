@file:androidx.annotation.OptIn(UnstableApi::class)

package app.spool.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.spool.graph
import app.spool.ui.MainActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the player so playback continues with the screen off or the app closed, and
 * saves the position of every file as it plays.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var ticker: Job? = null

    // Last known position of the current item, so it can be saved even after the
    // player has already switched to the next file.
    private var trackedKey: String? = null
    private var trackedPos = 0L
    private var trackedDur = 0L

    override fun onCreate() {
        super.onCreate()
        val settings = graph.settings

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.setPlaybackSpeed(settings.speed)
        player.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val back = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
            .setDisplayName("Back 15 seconds")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val forward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setDisplayName("Forward 30 seconds")
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()

        // The session (notification, lock screen, Bluetooth, car, watch) talks to SpoolPlayer,
        // which turns next/previous into forward/back.
        session = MediaSession.Builder(this, SpoolPlayer(player))
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(ImmutableList.of(back, forward))
            .setCallback(callback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveCurrent()
        // Swiping the app away keeps playing audio; otherwise there's nothing to keep alive.
        if (!isPlaybackOngoing) stopSelf()
    }

    override fun onDestroy() {
        saveCurrent()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private val callback = object : MediaSession.Callback {
        // Items come from our own UI with a content:// URI in requestMetadata.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                if (uri != null) item.buildUpon().setUri(uri).build() else item
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        /**
         * "Play" from headphones or Bluetooth after the service was stopped (or System UI's
         * resume card): pick up the last unfinished file where it was left.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            lifecycleScope.launch {
                val g = graph
                val items = withTimeoutOrNull(5_000) {
                    g.progress.loaded.first { it }
                    g.library.loaded.first { it }
                    g.library.items.value
                }
                val media = items?.let { list -> g.progress.lastUnfinished(list.associateBy { it.key }) }
                if (media == null) {
                    result.setException(UnsupportedOperationException("Nothing to resume"))
                } else {
                    val start = resumePositionMs(g.progress[media.key])
                    result.set(MediaSession.MediaItemsWithStartPosition(listOf(media.toMediaItem()), 0, start))
                }
            }
            return result
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Persist where we were in the previous file before tracking the new one.
            val oldKey = trackedKey
            if (oldKey != null && oldKey != mediaItem?.mediaId && trackedPos > 0) {
                graph.progress.save(oldKey, trackedPos, trackedDur)
            }
            trackedKey = mediaItem?.mediaId
            trackedPos = 0
            trackedDur = 0
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startTicker() else {
                ticker?.cancel()
                saveCurrent()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && !player.isPlaying) saveCurrent()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                val key = player.currentMediaItem?.mediaId ?: return
                val d = player.duration.takeIf { it != C.TIME_UNSET } ?: trackedDur
                graph.progress.setFinished(key, true, d)
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            graph.settings.speed = playbackParameters.speed
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            var n = 0
            while (isActive) {
                track()
                if (++n % 5 == 0) saveCurrent()
                delay(1_000)
            }
        }
    }

    private fun track() {
        val key = player.currentMediaItem?.mediaId ?: return
        trackedKey = key
        trackedPos = player.currentPosition
        player.duration.takeIf { it != C.TIME_UNSET }?.let { trackedDur = it }
    }

    private fun saveCurrent() {
        if (!::player.isInitialized) return
        if (player.playbackState == Player.STATE_ENDED) return
        track()
        val key = player.currentMediaItem?.mediaId ?: return
        graph.progress.save(key, trackedPos, trackedDur)
    }
}
