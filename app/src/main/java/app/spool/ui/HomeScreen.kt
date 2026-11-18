package app.spool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.spool.data.Channel
import app.spool.data.Media
import app.spool.data.Progress

enum class Filter(val label: String) {
    All("All"),
    Audio("Audio"),
    Video("Video"),
    Unplayed("Unplayed"),
    InProgress("In progress");

    fun matches(media: Media, progress: Progress?): Boolean = when (this) {
        All -> true
        Audio -> !media.isVideo
        Video -> media.isVideo
        Unplayed -> progress == null || (!progress.finished && !progress.inProgress)
        InProgress -> progress?.inProgress == true
    }
}

@Composable
fun HomeScreen(
    items: List<Media>,
    progress: Map<String, Progress>,
    loaded: Boolean,
    root: String,
    currentKey: String?,
    query: String,
    onQuery: (String) -> Unit,
    filter: Filter,
    onFilter: (Filter) -> Unit,
    bottomPadding: Dp,
    onOpenChannel: (String) -> Unit,
    onPlay: (Media) -> Unit,
    onLongPress: (Media) -> Unit,
    onSettings: () -> Unit,
) {
    val filtered = remember(items, progress, filter) { items.filter { filter.matches(it, progress[it.key]) } }
    val channels = remember(filtered) {
        filtered.groupBy { it.group }
            .map { (name, list) -> Channel(name, list) }
            .sortedByDescending { it.latest.dateAdded }
    }
    val continueItems = remember(filtered, progress) {
        filtered.filter { progress[it.key]?.inProgress == true }
            .sortedByDescending { progress[it.key]?.lastPlayed ?: 0L }
            .take(12)
    }
    val results = remember(filtered, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            emptyList()
        } else {
            filtered.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.group.contains(q, ignoreCase = true) ||
                    (it.artist?.contains(q, ignoreCase = true) == true)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
        ) {
            SearchField(query, onQuery, Modifier.weight(1f), placeholder = "Search ${items.size} files")
            IconButton(onClick = onSettings) { Icon(AppIcons.Tune, contentDescription = "Settings") }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Filter.entries) { f ->
                FilterChip(selected = filter == f, onClick = { onFilter(f) }, label = { Text(f.label) })
            }
        }

        val gridPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottomPadding)
        when {
            loaded && items.isEmpty() -> EmptyState(
                title = "Nothing here yet",
                body = "No audio or video found in $root. Change the folder in settings, or download something with Seal.",
            )

            query.isNotBlank() -> {
                if (results.isEmpty()) {
                    EmptyState("No matches", "Nothing matches \"${query.trim()}\".")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        contentPadding = gridPadding,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(results, key = { it.key }) { m ->
                            MediaCard(m, progress[m.key], m.key == currentKey, { onPlay(m) }, { onLongPress(m) })
                        }
                    }
                }
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = gridPadding,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (continueItems.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "continue-header") {
                        SectionHeader("Continue")
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "continue-row") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(continueItems, key = { it.key }) { m ->
                                MediaCard(
                                    m, progress[m.key], m.key == currentKey,
                                    onClick = { onPlay(m) }, onLongClick = { onLongPress(m) },
                                    modifier = Modifier.width(240.dp),
                                )
                            }
                        }
                    }
                }
                if (channels.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "channels-header") {
                        SectionHeader("Channels")
                    }
                }
                items(channels, key = { "ch:" + it.name }) { ch ->
                    val unplayed = ch.items.count { m -> progress[m.key].let { it == null || (!it.finished && !it.inProgress) } }
                    ChannelTile(ch, unplayed, onClick = { onOpenChannel(ch.name) })
                }
                if (loaded && channels.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState("Nothing in this filter", "Try another filter above.")
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.fillMaxWidth()) }
            }
        }
    }
}
