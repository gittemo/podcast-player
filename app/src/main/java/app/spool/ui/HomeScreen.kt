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
import app.spool.data.PlayStatus
import app.spool.data.Progress
import app.spool.data.statuses

enum class Filter(val label: String, val usesStatus: Boolean = false) {
    All("All"),
    Audio("Audio"),
    Video("Video"),
    Unplayed("Unplayed", usesStatus = true),
    InProgress("In progress", usesStatus = true);

    /** [status] is null for files that were never played. */
    fun matches(media: Media, status: PlayStatus?): Boolean = when (this) {
        All -> true
        Audio -> !media.isVideo
        Video -> media.isVideo
        Unplayed -> status == null || status == PlayStatus.New
        InProgress -> status == PlayStatus.InProgress
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
    artVersions: Map<String, Long>,
    onOpenChannel: (String) -> Unit,
    onChannelLongPress: (Channel) -> Unit,
    onPlay: (Media) -> Unit,
    onLongPress: (Media) -> Unit,
    onSettings: () -> Unit,
) {
    // `progress` changes every time a position is saved (every 5 s while playing). The small
    // derived values below are recomputed then, but `remember` compares keys by value, so the
    // expensive lists only rebuild when a file's status or the Continue order actually changes.
    val statuses = remember(progress) { progress.statuses() }
    val filtered = remember(items, filter, if (filter.usesStatus) statuses else null) {
        items.filter { filter.matches(it, statuses[it.key]) }
    }
    val channels = remember(filtered) {
        filtered.groupBy { it.group }
            .map { (name, list) -> Channel(name, list) }
            .sortedByDescending { it.latest.dateAdded }
    }
    val unplayedCounts = remember(channels, statuses) {
        channels.associate { ch ->
            ch.name to ch.items.count { m -> statuses[m.key].let { it == null || it == PlayStatus.New } }
        }
    }
    val continueOrder = remember(progress) {
        progress.entries.filter { it.value.inProgress }.sortedByDescending { it.value.lastPlayed }.map { it.key }
    }
    val continueItems = remember(filtered, continueOrder) {
        val allowed = filtered.associateBy { it.key }
        continueOrder.mapNotNull { allowed[it] }.take(12)
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
                    ChannelTile(
                        channel = ch,
                        unplayed = unplayedCounts[ch.name] ?: 0,
                        artVersion = artVersions[ch.name],
                        onClick = { onOpenChannel(ch.name) },
                        onLongClick = { onChannelLongPress(ch) },
                    )
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
