package app.spool.ui

import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import app.spool.data.Media
import app.spool.graph
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Home : Screen
    data class Channel(val name: String) : Screen
    data object Settings : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(controller: MediaController?) {
    val context = LocalContext.current
    val g = context.graph

    var hasAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = Environment.isExternalStorageManager()
        if (now != hasAccess) hasAccess = now
        if (now) g.library.refresh(scanDisk = true)
    }
    if (!hasAccess) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { PermissionScreen() }
        return
    }

    val items by g.library.items.collectAsStateWithLifecycle()
    val loaded by g.library.loaded.collectAsStateWithLifecycle()
    val progress by g.progress.all.collectAsStateWithLifecycle()
    val root by g.settings.root.collectAsStateWithLifecycle()
    val ps = rememberPlayerUiState(controller)
    val scope = rememberCoroutineScope()

    var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Home)) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var actionTarget by remember { mutableStateOf<Media?>(null) }
    var renameTarget by remember { mutableStateOf<Media?>(null) }
    var deleteTarget by remember { mutableStateOf<Media?>(null) }

    val byKey = remember(items) { items.associateBy { it.key } }
    val currentKey = ps.mediaId
    val current = currentKey?.let { byKey[it] }
    // When nothing is loaded in the player, offer the last unfinished file in the mini player.
    val lastPlayed = remember(byKey, progress, currentKey) {
        if (currentKey != null) {
            null
        } else {
            progress.entries
                .filter { !it.value.finished && byKey.containsKey(it.key) }
                .maxByOrNull { it.value.lastPlayed }
                ?.let { byKey[it.key] }
        }
    }

    fun play(m: Media) {
        val c = controller
        if (c == null) {
            Toast.makeText(context, "Player is starting, try again", Toast.LENGTH_SHORT).show()
            return
        }
        c.playMedia(m, progress[m.key])
        playerOpen = true
    }

    BackHandler(enabled = playerOpen) { playerOpen = false }
    BackHandler(enabled = !playerOpen && stack.size > 1) { stack = stack.dropLast(1) }
    BackHandler(enabled = !playerOpen && stack.size == 1 && query.isNotEmpty()) { query = "" }

    val miniMedia = current ?: lastPlayed
    val showMini = !playerOpen && miniMedia != null
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding = navBottom + if (showMini) 104.dp else 16.dp

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(
            targetState = stack.last(),
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "screens",
        ) { screen ->
            when (screen) {
                Screen.Home -> HomeScreen(
                    items = items,
                    progress = progress,
                    loaded = loaded,
                    root = root,
                    currentKey = currentKey,
                    query = query,
                    onQuery = { query = it },
                    filter = filter,
                    onFilter = { filter = it },
                    bottomPadding = bottomPadding,
                    onOpenChannel = { stack = stack + Screen.Channel(it) },
                    onPlay = ::play,
                    onLongPress = { actionTarget = it },
                    onSettings = { stack = stack + Screen.Settings },
                )

                is Screen.Channel -> ChannelScreen(
                    name = screen.name,
                    items = items.filter { it.group == screen.name },
                    progress = progress,
                    currentKey = currentKey,
                    bottomPadding = bottomPadding,
                    onBack = { stack = stack.dropLast(1) },
                    onPlay = ::play,
                    onLongPress = { actionTarget = it },
                )

                Screen.Settings -> SettingsScreen(
                    root = root,
                    itemCount = items.size,
                    bottomPadding = bottomPadding,
                    onBack = { stack = stack.dropLast(1) },
                )
            }
        }

        if (showMini && miniMedia != null) {
            val isCurrent = current != null
            MiniPlayer(
                media = miniMedia,
                state = ps,
                isCurrent = isCurrent,
                savedProgress = progress[miniMedia.key],
                onOpen = { if (isCurrent) playerOpen = true else play(miniMedia) },
                onToggle = { if (isCurrent) controller?.togglePlay() else play(miniMedia) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        AnimatedVisibility(
            visible = playerOpen && current != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            val m = current
            if (m != null) {
                PlayerScreen(media = m, player = controller, state = ps, onClose = { playerOpen = false })
            }
        }
    }

    // Long-press actions
    actionTarget?.let { m ->
        val p = progress[m.key]
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
                Text(
                    m.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                val itemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
                ActionRow("Rename", AppIcons.Edit, itemColors) {
                    actionTarget = null
                    renameTarget = m
                }
                if (p?.finished == true || p?.inProgress == true) {
                    ActionRow("Mark as unplayed", AppIcons.Undo, itemColors) {
                        g.progress.setFinished(m.key, false, m.durationMs)
                        actionTarget = null
                    }
                }
                if (p?.finished != true) {
                    ActionRow("Mark as played", AppIcons.Check, itemColors) {
                        g.progress.setFinished(m.key, true, m.durationMs)
                        actionTarget = null
                    }
                }
                ActionRow("Delete file", AppIcons.Delete, itemColors) {
                    actionTarget = null
                    deleteTarget = m
                }
            }
        }
    }

    renameTarget?.let { m ->
        var name by remember(m.key) { mutableStateOf(m.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        renameTarget = null
                        scope.launch {
                            g.fileOps.rename(m, name).onFailure {
                                Toast.makeText(context, it.message ?: "Rename failed", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { m ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete file?") },
            text = { Text("\"${m.title}\" will be removed from your phone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    val c = controller
                    if (c != null && c.currentMediaItem?.mediaId == m.key) {
                        c.stop()
                        c.clearMediaItems()
                        playerOpen = false
                    }
                    scope.launch {
                        g.fileOps.delete(m).onFailure {
                            Toast.makeText(context, it.message ?: "Delete failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: androidx.compose.material3.ListItemColors,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = colors,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clickable(onClick = onClick),
    )
}
