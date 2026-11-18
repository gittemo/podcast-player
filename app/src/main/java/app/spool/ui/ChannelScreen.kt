package app.spool.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.spool.data.Media
import app.spool.data.Progress

private enum class Sort(val label: String) { Newest("Newest first"), Oldest("Oldest first"), Title("A–Z") }

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
    var sort by rememberSaveable { mutableStateOf(Sort.Newest) }
    val sorted = remember(items, sort) {
        when (sort) {
            Sort.Newest -> items.sortedByDescending { it.dateAdded }
            Sort.Oldest -> items.sortedBy { it.dateAdded }
            Sort.Title -> items.sortedBy { it.title.lowercase() }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${items.size} items · ${sort.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = { sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size] }) {
                    Icon(AppIcons.Sort, contentDescription = "Change sort order")
                }
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
