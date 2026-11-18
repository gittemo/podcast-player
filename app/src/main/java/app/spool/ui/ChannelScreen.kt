package app.spool.ui

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.spool.data.Media
import app.spool.data.Progress

/** One channel's files, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    name: String,
    items: List<Media>,
    progress: Map<String, Progress>,
    currentKey: String?,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onPlay: (Media) -> Unit,
    onLongPress: (Media) -> Unit,
) {
    val sorted = remember(items) { items.sortedByDescending { it.dateAdded } }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    // One line that scrolls when it's too long, so the whole name is readable.
                    Text(name, maxLines = 1, modifier = Modifier.basicMarquee())
                    Text(
                        if (items.size == 1) "1 item" else "${items.size} items",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Back") }
            },
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(sorted, key = { it.key }) { m ->
                MediaCard(
                    m, progress[m.key], m.key == currentKey,
                    onClick = { onPlay(m) }, onLongClick = { onLongPress(m) },
                    showGroup = false,
                )
            }
        }
    }
}
