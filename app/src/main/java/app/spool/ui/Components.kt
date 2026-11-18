package app.spool.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.spool.data.Channel
import app.spool.data.Media
import app.spool.data.Progress
import app.spool.graph
import kotlin.math.abs

/** Cover art for a file, with a colored placeholder while loading or when there's none. */
@Composable
fun Thumb(
    media: Media?,
    modifier: Modifier = Modifier,
    placeholderLabel: String = media?.group ?: "",
    contentScale: ContentScale = ContentScale.Crop,
) {
    val thumbs = LocalContext.current.graph.thumbnails
    BoxWithConstraints(modifier) {
        val px = with(LocalDensity.current) { maxWidth.roundToPx() }
        val bitmap by produceState(initialValue = media?.let { thumbs.cached(it, px) }, media?.id, px) {
            if (media != null && value == null) value = thumbs.load(media, px)
        }
        val bmp = bitmap
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        } else {
            Placeholder(placeholderLabel, media?.isVideo == true, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Placeholder(label: String, isVideo: Boolean, modifier: Modifier) {
    val hue = (abs(label.hashCode()) % 360).toFloat()
    val top = Color.hsl(hue, 0.35f, 0.42f)
    val bottom = Color.hsl((hue + 40f) % 360f, 0.40f, 0.26f)
    Box(modifier.background(Brush.linearGradient(listOf(top, bottom))), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = if (isVideo) AppIcons.Video else AppIcons.Audio,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(32.dp),
        )
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).background(Color.Black.copy(alpha = 0.35f))) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A file: 16:9 cover with duration, progress and title. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    media: Media,
    progress: Progress?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    showGroup: Boolean = true,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(bottom = 6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape),
        ) {
            Thumb(media, Modifier.fillMaxSize())
            if (media.isVideo) {
                Icon(
                    AppIcons.Video, contentDescription = "Video", tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(3.dp)
                        .size(14.dp),
                )
            }
            when {
                progress?.finished == true -> Badge("Played", Modifier.align(Alignment.BottomEnd).padding(6.dp))
                media.durationMs > 0 -> {
                    val text = if (progress?.inProgress == true) {
                        formatDuration(media.durationMs - progress.positionMs) + " left"
                    } else {
                        formatDuration(media.durationMs)
                    }
                    Badge(text, Modifier.align(Alignment.BottomEnd).padding(6.dp))
                }
            }
            if (progress != null && (progress.inProgress || progress.finished)) {
                ProgressLine(progress.fraction, Modifier.fillMaxWidth().align(Alignment.BottomCenter))
            }
            if (isCurrent) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = media.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (progress?.finished == true) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (showGroup) {
            Text(
                text = media.group,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** The channel's picture if it has one, else the cover of its newest file. */
@Composable
fun ChannelCover(channel: Channel, artVersion: Long?, modifier: Modifier = Modifier) {
    if (artVersion == null) {
        Thumb(channel.latest, modifier, placeholderLabel = channel.name)
        return
    }
    val art = LocalContext.current.graph.channelArt
    BoxWithConstraints(modifier) {
        val px = with(LocalDensity.current) { maxWidth.roundToPx() }
        val bitmap by produceState<ImageBitmap?>(initialValue = null, channel.name, artVersion, px) {
            value = art.load(channel.name, px)
        }
        val bmp = bitmap
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Thumb(channel.latest, Modifier.fillMaxSize(), placeholderLabel = channel.name)
        }
    }
}

/** A channel on the home grid: its picture (or newest cover), name and counts. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelTile(
    channel: Channel,
    unplayed: Int,
    artVersion: Long?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(bottom = 6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(shape)) {
            ChannelCover(channel, artVersion, Modifier.fillMaxSize())
            if (unplayed > 0) {
                Text(
                    text = unplayed.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = channel.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            text = if (channel.items.size == 1) "1 item" else "${channel.items.size} items",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** Pill-shaped search box. */
@Composable
fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Icon(AppIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(AppIcons.Close, contentDescription = "Clear search")
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
