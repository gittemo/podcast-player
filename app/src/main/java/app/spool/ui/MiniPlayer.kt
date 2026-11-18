package app.spool.ui

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.spool.data.Media
import app.spool.data.Progress

@Composable
fun MiniPlayer(
    media: Media,
    state: PlayerUiState,
    isCurrent: Boolean,
    savedProgress: Progress?,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.clip(shape).clickable(onClick = onOpen)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                // Cover at its own shape (fixed height), clamped so very wide/tall art stays tidy.
                CoverArt(
                    media,
                    Modifier.height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    sizePx = 200,
                    matchHeightFirst = true,
                    minAspect = 9f / 16f,
                    maxAspect = 16f / 9f,
                    loadingAspect = 1f,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    // One line that scrolls when it's too long, so the whole title is readable.
                    Text(
                        media.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    Text(
                        media.group,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                val playing = isCurrent && !state.showPlay
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (playing) AppIcons.Pause else AppIcons.Play,
                        contentDescription = if (playing) "Pause" else "Play",
                    )
                }
            }
            val fraction = if (isCurrent) {
                if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
            } else {
                savedProgress?.fraction ?: 0f
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                ProgressLine(fraction, Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)))
            }
        }
    }
}
