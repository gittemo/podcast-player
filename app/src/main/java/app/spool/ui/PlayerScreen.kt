@file:androidx.annotation.OptIn(UnstableApi::class)

package app.spool.ui

import android.content.res.Configuration
import android.text.format.Formatter
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import app.spool.data.Media
import app.spool.data.TagReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
    var showInfo by rememberSaveable(media.key) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                IconButton(onClick = onClose) { Icon(AppIcons.Down, contentDescription = "Close player") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showInfo = true }) { Icon(AppIcons.Info, contentDescription = "File details") }
                SpeedButton(player, state)
            }

            // Everything below is centred as a group; the picture takes whatever height is
            // left after the text and controls, at its own aspect ratio.
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val shape = RoundedCornerShape(20.dp)
                Box(
                    Modifier.weight(1f, fill = false).fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (media.isVideo && player != null) {
                        val aspect = if (state.videoAspect > 0f) state.videoAspect else 16f / 9f
                        Box(Modifier.aspectRatio(aspect).clip(shape).background(Color.Black)) {
                            // TextureView: a SurfaceView ignores the rounded clip.
                            ContentFrame(
                                player = player,
                                modifier = Modifier.fillMaxSize(),
                                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                            )
                        }
                    } else {
                        CoverArt(media, shape = shape, sizePx = 960)
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = media.title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = media.group,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(16.dp))
                SeekBar(player, state)

                Spacer(Modifier.height(8.dp))
                Controls(player, state)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (showInfo) MediaInfoSheet(media, onDismiss = { showInfo = false })
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
            Text(formatDuration((shown * duration).toLong()), style = MaterialTheme.typography.labelMedium, color = textColor)
            Spacer(Modifier.weight(1f))
            val remaining = ((1f - shown) * duration / state.speed.coerceAtLeast(0.1f)).toLong()
            Text("-" + formatDuration(remaining), style = MaterialTheme.typography.labelMedium, color = textColor)
        }
    }
}

/** Back 15 s · play/pause · forward 30 s. [overlay] is for white controls on top of video. */
@Composable
private fun Controls(player: Player?, state: PlayerUiState, overlay: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipButton("15", AppIcons.Rewind, "Back 15 seconds", overlay) { player?.seekBack() }
        val icon = if (state.showPlay) AppIcons.Play else AppIcons.Pause
        val description = if (state.showPlay) "Play" else "Pause"
        if (overlay) {
            IconButton(
                onClick = { player?.togglePlay() },
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                modifier = Modifier.size(64.dp),
            ) { Icon(icon, contentDescription = description, modifier = Modifier.size(40.dp)) }
        } else {
            FilledIconButton(onClick = { player?.togglePlay() }, modifier = Modifier.size(64.dp)) {
                Icon(icon, contentDescription = description, modifier = Modifier.size(32.dp))
            }
        }
        SkipButton("30", AppIcons.Forward, "Forward 30 seconds", overlay) { player?.seekForward() }
    }
}

@Composable
private fun SkipButton(label: String, icon: ImageVector, description: String, overlay: Boolean, onClick: () -> Unit) {
    val colors = if (overlay) {
        IconButtonDefaults.iconButtonColors(contentColor = Color.White)
    } else {
        IconButtonDefaults.iconButtonColors()
    }
    IconButton(onClick = onClick, colors = colors, modifier = Modifier.size(56.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = description)
            Text(label, style = MaterialTheme.typography.labelSmall)
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
        // ContentFrame keeps the video's aspect ratio and shows black until the first frame.
        ContentFrame(player = player, modifier = Modifier.fillMaxSize())
        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
                Text(
                    media.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(24.dp),
                )
                Box(Modifier.align(Alignment.Center)) {
                    Controls(player, state, overlay = true)
                }
                Box(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(horizontal = 32.dp, vertical = 16.dp)) {
                    SeekBar(player, state, light = true)
                }
            }
        }
    }
}

/** Everything known about the file: what Android indexed, its streams and every tag in it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaInfoSheet(media: Media, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // null while reading.
    val tags by produceState<TagReader.Tags?>(initialValue = null, media.key) {
        value = withContext(Dispatchers.IO) { TagReader.read(context, media.uri, withStreams = true) } ?: TagReader.Tags.EMPTY
    }
    val file = remember(media) {
        buildList {
            add("Title" to media.title)
            add("Channel" to media.group)
            add("Type" to if (media.isVideo) "Video" else "Audio")
            if (media.durationMs > 0) add("Duration" to formatDuration(media.durationMs))
            add("Size" to Formatter.formatFileSize(context, media.sizeBytes))
            add(
                "Added" to Instant.ofEpochSecond(media.dateAdded).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)),
            )
            add("File name" to media.fileName)
            add("Folder" to media.folder)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            item { Text("Details", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp)) }
            items(file) { (label, value) -> InfoRow(label, value) }

            val read = tags
            if (read != null && read.streams.isNotEmpty()) {
                item { InfoSection("Streams") }
                items(read.streams) { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                }
            }

            item { InfoSection("Tags in the file") }
            when {
                read == null -> item { InfoNote("Reading tags…") }
                read.entries.isEmpty() -> item { InfoNote("No tags found in this file.") }
                else -> items(read.entries) { (label, value) -> InfoRow(label, value) }
            }
        }
    }
}

@Composable
private fun InfoSection(title: String) {
    Column(Modifier.padding(top = 16.dp)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Selectable, so links and descriptions can be copied.
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun InfoNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
