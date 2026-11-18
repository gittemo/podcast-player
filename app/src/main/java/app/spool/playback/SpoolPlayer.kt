@file:androidx.annotation.OptIn(UnstableApi::class)

package app.spool.playback

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The player the media session exposes. Spool plays one file at a time, so "next" and
 * "previous" from headphones, car head units, watches and Bluetooth (AVRCP) mean
 * "forward 30 s" and "back 15 s", like in podcast and audiobook players.
 *
 * Without this, those buttons do nothing: with a single item there is no next item, and
 * because the notification puts its own back/forward buttons in those slots, Media3 also
 * stops advertising skip actions to the system.
 */
class SpoolPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {

    override fun getState(): State {
        val state = super.getState()
        if (state.timeline.isEmpty) return state
        val commands = state.availableCommands.buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .build()
        return state.buildUpon().setAvailableCommands(commands).build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> =
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                player.seekForward()
                Futures.immediateVoidFuture()
            }

            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                player.seekBack()
                Futures.immediateVoidFuture()
            }

            else -> super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
}
