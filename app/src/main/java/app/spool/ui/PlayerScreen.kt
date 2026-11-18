@file:OptIn(UnstableApi::class)

package app.spool.ui

import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import app.spool.data.Media
import kotlinx.coroutines.delay

private val speeds = listOf(0.8f, 1f, 1.2f, 1.5f, 1.75f, 2f)

private fun Float.speedLabel(): String =
    if (this % 1f == 0f) "${this.toInt()}×" else "${"%.2f".format(this).trimEnd('0').trimEnd('.')}×"

@Composable
fun PlayerScreen(
    media: Media,
    player: Player?,
    state: PlayerUiState,
    onClose: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (media.isVideo && landscape && player != null) {
        FullscreenVideo(media, player, state)
    } else {
        PortraitPlayer(media, player, state, onClose)
    }
}

@Composable
private fun PortraitPlayer(media: Media, player: Player?, state: PlayerUiState, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                IconButton(onClick = onClose) { Icon(AppIcons.Down, contentDescription = "Close player") }
                Spacer(Modifier.weight(1f))
                SpeedButton(player, state)
            }

            Spacer(Modifier.weight(1f))

            val artShape = RoundedCornerShape(24.dp)
            if (media.isVideo && player != null) {
                val aspect = if (state.videoAspect > 0f) state.videoAspect else 16f / 9f
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect)
                        .clip(artShape)
                        .background(Color.Black),
                ) {
                    PlayerSurface(player = player, modifier = Modifier.fillMaxSize())
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(artShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Thumb(media, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                text = media.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = media.group,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(20.dp))
            SeekBar(player, state)

            Spacer(Modifier.height(12.dp))
            BigControls(player, state)

            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SpeedButton(player: Player?, state: PlayerUiState) {
    TextButton(onClick = {
        val i = speeds.indexOfFirst { it >= state.speed - 0.01f }.let { if (it < 0) 0 else it }
        player?.setPlaybackSpeed(speeds[(i + 1) % speeds.size])
    }) {
        Text(state.speed.speedLabel(), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SeekBar(player: Player?, state: PlayerUiState, light: Boolean = false) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val shown = dragging ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val textColor = if (light) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { player?.seekTo((it * duration).toLong()) }
                dragging = null
            },
            enabled = state.durationMs > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration((shown * duration).toLong()), style = MaterialTheme.typography.labelLarge, color = textColor)
            Spacer(Modifier.weight(1f))
            val remaining = ((1f - shown) * duration / state.speed.coerceAtLeast(0.1f)).toLong()
            Text("-" + formatDuration(remaining), style = MaterialTheme.typography.labelLarge, color = textColor)
        }
    }
}

@Composable
private fun BigControls(player: Player?, state: PlayerUiState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipButton(label = "15", icon = AppIcons.Rewind, description = "Back 15 seconds") { player?.seekBack() }
        FilledIconButton(
            onClick = { player?.togglePlay() },
            modifier = Modifier.size(96.dp),
            shape = RoundedCornerShape(32.dp),
        ) {
            Icon(
                if (state.isPlaying) AppIcons.Pause else AppIcons.Play,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(48.dp),
            )
        }
        SkipButton(label = "30", icon = AppIcons.Forward, description = "Forward 30 seconds") { player?.seekForward() }
    }
}

@Composable
private fun SkipButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(72.dp), shape = RoundedCornerShape(24.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(28.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Landscape video: edge to edge, tap to show or hide the controls. */
@Composable
private fun FullscreenVideo(media: Media, player: Player, state: PlayerUiState) {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible, state.isPlaying) {
        if (controlsVisible && state.isPlaying) {
            delay(3_000)
            controlsVisible = false
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { controlsVisible = !controlsVisible },
        contentAlignment = Alignment.Center,
    ) {
        val aspect = if (state.videoAspect > 0f) state.videoAspect else 16f / 9f
        Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = true)) {
            PlayerSurface(player = player, modifier = Modifier.fillMaxSize())
        }
        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
                Text(
                    media.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopStart).padding(24.dp),
                )
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                    IconButton(onClick = { player.seekBack() }, colors = colors, modifier = Modifier.size(64.dp)) {
                        Icon(AppIcons.Rewind, contentDescription = "Back 15 seconds", modifier = Modifier.size(40.dp))
                    }
                    IconButton(onClick = { player.togglePlay() }, colors = colors, modifier = Modifier.size(88.dp)) {
                        Icon(
                            if (state.isPlaying) AppIcons.Pause else AppIcons.Play,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(64.dp),
                        )
                    }
                    IconButton(onClick = { player.seekForward() }, colors = colors, modifier = Modifier.size(64.dp)) {
                        Icon(AppIcons.Forward, contentDescription = "Forward 30 seconds", modifier = Modifier.size(40.dp))
                    }
                }
                Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 32.dp, vertical = 16.dp)) {
                    SeekBar(player, state, light = true)
                }
            }
        }
    }
}
