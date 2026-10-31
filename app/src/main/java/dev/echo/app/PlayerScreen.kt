@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.echo.app

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import dev.echo.core.Track
import dev.echo.core.Playlist

@Composable internal fun PlayerScreen(vm: EchoViewModel, tracks: List<Track>, playlists: List<Playlist>, favorites: Set<String>, close: () -> Unit) {
    var showQueue by remember { mutableStateOf(false) }
    var credits by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf<Track?>(null) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var seekTarget by remember { mutableStateOf<dev.echo.data.firebase.ConnectedDevice?>(null) }
    val targetId by vm.selectedConnectedPlaybackTargetId.collectAsStateWithLifecycle()
    val connectedState by vm.connectedPlaybackState.collectAsStateWithLifecycle()
    val connectedV2 by vm.connectedPlaybackV2State.collectAsStateWithLifecycle()
    val localGroup = connectedV2.group
    val cachedTarget by vm.selectedConnectedPlaybackDevice.collectAsStateWithLifecycle()
    val remote = if (targetId == null) null else connectedState.devices.firstOrNull { it.id == targetId } ?: cachedTarget
    val remoteSelected = targetId != null
    val canControlRemote = remote != null && remote.player.title != null && vm.canControlConnectedDevice(remote)
    LaunchedEffect(targetId, remote?.sessionId, remote?.player?.trackRevision, remote?.player?.queueRevision, vm.current?.mediaId) {
        seeking = null
        seekTarget = null
    }
    val current = if (remoteSelected) tracks.firstOrNull { it.id == remote?.player?.trackId } else tracks.find { it.id == vm.current?.mediaId }
    val player = vm.controller
    val revision = vm.revision
    val accent = MaterialTheme.colorScheme.primary
    val remotePosition = seekTarget?.player?.positionMs ?: remote?.player?.positionMs ?: 0L
    val remoteDuration = seekTarget?.player?.durationMs ?: remote?.player?.durationMs ?: 0L
    val duration = if (remoteSelected || seekTarget != null) remoteDuration else vm.duration
    val basePosition = if (remoteSelected || seekTarget != null) remotePosition else vm.position
    val progress = seeking ?: basePosition.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f))
    val liveRemote = remote?.let { candidate -> connectedState.devices.firstOrNull { it.id == candidate.id && it.sessionId == candidate.sessionId && it.controlEnabled } }
    val displayedTitle = if (remoteSelected) remote?.player?.title ?: if (liveRemote != null) "Ready for playback" else "Reconnecting to player" else vm.current?.mediaMetadata?.title?.toString() ?: "Choose a song"
    val displayedArtist = if (remoteSelected) remote?.player?.artist.orEmpty() else vm.current?.mediaMetadata?.artist?.toString().orEmpty()
    val artworkUri = if (remoteSelected) connectedArtworkUri(remote?.player?.trackId, tracks) else vm.current?.mediaMetadata?.artworkUri?.toString()
    val displayedPlaying = if (remoteSelected) remote?.player?.isPlaying == true else localGroup?.timeline?.playing ?: vm.playing
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF342742), Color(0xFF141318), Color(0xFF101014)))).testTag("player-screen")) {
        val artSize = minOf(maxWidth - 56.dp, maxHeight * .38f).coerceIn(140.dp, 340.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) { Icon(Icons.Default.KeyboardArrowDown, "Close player", modifier = Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp, color = accent)
                    Text(if (!remoteSelected && vm.recommendedIds.contains(vm.current?.mediaId)) "Recommended for this session" else "Your music, in the moment", style = MaterialTheme.typography.bodySmall)
                }
                if (!remoteSelected && localGroup == null) IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Open queue") }
                else Spacer(Modifier.size(48.dp))
            }
            Spacer(Modifier.height(14.dp))
            Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 20.dp) { Artwork(artworkUri, artSize.value.toInt()) }
            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(displayedTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("player-track-title"))
                    Spacer(Modifier.height(6.dp))
                    Text(displayedArtist, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFC8C1CE), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(enabled = current != null, onClick = { current?.let(vm::favorite) }) { Icon(if (current?.id in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Like song", tint = accent) }
                IconButton(enabled = current != null, onClick = { add = current }) { Icon(Icons.Default.PlaylistAdd, "Add current song to playlist", tint = accent) }
            }
            Spacer(Modifier.height(18.dp))
            if (!remoteSelected) {
                current?.let { DownloadTrackAction(vm, it) }
                if (localGroup == null) SleepTimerControls(vm)
            }
            Slider(progress, { value ->
                if (seekTarget == null && remoteSelected) seekTarget = remote
                seeking = value
            }, valueRange = 0f..duration.toFloat().coerceAtLeast(1f), enabled = if (remoteSelected || seekTarget != null) {
                val captured = seekTarget ?: remote
                duration > 0 && captured != null && vm.canControlConnectedDevice(captured)
            } else duration > 0,
                onValueChangeFinished = {
                    val value = seeking
                    val captured = seekTarget
                    if (value != null) {
                        if (captured != null) vm.sendConnectedPlaybackCommand(captured, dev.echo.data.firebase.ConnectedPlaybackAction.SEEK, value.toLong())
                        else if (localGroup != null) vm.controlTogether(dev.echo.data.firebase.GroupActionV2.SEEK, value.toLong())
                        else player?.seekTo(value.toLong())
                    }
                    seeking = null
                    seekTarget = null
                }, modifier = Modifier.fillMaxWidth().testTag("player-seek"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(playerTime(progress.toLong()), style = MaterialTheme.typography.labelSmall); Text(playerTime(duration), style = MaterialTheme.typography.labelSmall) }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                if (remoteSelected) {
                    IconButton(enabled = canControlRemote, onClick = { remote?.let { vm.sendConnectedPlaybackCommand(it, dev.echo.data.firebase.ConnectedPlaybackAction.PREVIOUS) } }) { Icon(Icons.Default.SkipPrevious, "Previous on ${remote?.label ?: "selected player"}", modifier = Modifier.size(34.dp)) }
                    FilledIconButton(enabled = canControlRemote && !connectedState.pending, onClick = { remote?.let { vm.sendConnectedPlaybackCommand(it, if (displayedPlaying) dev.echo.data.firebase.ConnectedPlaybackAction.PAUSE else dev.echo.data.firebase.ConnectedPlaybackAction.PLAY) } }, modifier = Modifier.size(80.dp)) {
                        Icon(if (displayedPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "${if (displayedPlaying) "Pause" else "Play"} on ${remote?.label ?: "selected player"}", modifier = Modifier.size(42.dp))
                    }
                    IconButton(enabled = canControlRemote && !connectedState.pending, onClick = { remote?.let { vm.sendConnectedPlaybackCommand(it, dev.echo.data.firebase.ConnectedPlaybackAction.NEXT) } }) { Icon(Icons.Default.SkipNext, "Next on ${remote?.label ?: "selected player"}", modifier = Modifier.size(34.dp)) }
                } else if (localGroup != null) {
                    IconButton(enabled = !connectedV2.pending, onClick = { vm.controlTogether(dev.echo.data.firebase.GroupActionV2.PREVIOUS) }) { Icon(Icons.Default.SkipPrevious, "Previous for everyone", modifier = Modifier.size(34.dp)) }
                    FilledIconButton(enabled = !connectedV2.pending, onClick = {
                        vm.controlTogether(if (localGroup.timeline?.playing == true) dev.echo.data.firebase.GroupActionV2.PAUSE else dev.echo.data.firebase.GroupActionV2.PLAY)
                    }, modifier = Modifier.size(80.dp)) {
                        Icon(if (localGroup.timeline?.playing == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (localGroup.timeline?.playing == true) "Pause for everyone" else "Play for everyone", modifier = Modifier.size(42.dp))
                    }
                    IconButton(enabled = !connectedV2.pending, onClick = { vm.controlTogether(dev.echo.data.firebase.GroupActionV2.NEXT) }) { Icon(Icons.Default.SkipNext, "Next for everyone", modifier = Modifier.size(34.dp)) }
                } else {
                    IconButton(onClick = { player?.shuffleModeEnabled = player?.shuffleModeEnabled != true }) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (player?.shuffleModeEnabled == true && revision >= 0) accent else Color(0xFFA29BAA)) }
                    IconButton(enabled = player != null, onClick = { player?.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, "Previous", modifier = Modifier.size(34.dp)) }
                    FilledIconButton(enabled = vm.current != null, onClick = vm::toggle, modifier = Modifier.size(80.dp)) {
                        if (vm.buffering) CircularProgressIndicator(Modifier.size(30.dp), color = MaterialTheme.colorScheme.onPrimary)
                        else Icon(if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (vm.playing) "Pause" else "Play", modifier = Modifier.size(42.dp))
                    }
                    IconButton(enabled = player?.hasNextMediaItem() == true && revision >= 0, onClick = { player?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(34.dp)) }
                    IconButton(onClick = { player?.repeatMode = when (player?.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }) {
                        Icon(if (player?.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat mode ${player?.repeatMode}", tint = if (player != null && player.repeatMode != Player.REPEAT_MODE_OFF && revision >= 0) accent else Color(0xFFA29BAA))
                    }
                }
            }
            ConnectedPlayerControls(vm)
            Spacer(Modifier.height(24.dp))
            if (!remoteSelected && localGroup == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, null); Spacer(Modifier.width(8.dp)); Text("Up next · ${vm.queueItems.drop(1).size}") }
                    if (current?.attribution != null) TextButton(onClick = { credits = true }) { Text("Credits") }
                    else Text(current?.provider?.replaceFirstChar { it.uppercase() }.orEmpty(), style = MaterialTheme.typography.labelSmall, color = Color(0xFFA29BAA))
                }
            } else if (connectedState.pending) {
                Text("Remote command pending confirmation…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (showQueue && !remoteSelected && localGroup == null) QueueSheet(vm, tracks, onAdd = { add = it }) { showQueue = false }
    add?.let { track -> AddToPlaylistDialog(track, vm, playlists) { add = null } }
    if (credits && current != null) Credits(current) { credits = false }
}

@Composable private fun QueueSheet(vm: EchoViewModel, tracks: List<Track>, onAdd: (Track) -> Unit, close: () -> Unit) {
    val entries = vm.queueItems
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    var dragTarget by remember { mutableStateOf<Int?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(entries.firstOrNull()?.uid) { listState.scrollToItem(0) }
    ModalBottomSheet(onDismissRequest = close, sheetState = sheetState, containerColor = Color(0xFF19171F)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.78f).padding(horizontal = 20.dp).testTag("queue-sheet")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Your queue", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(enabled = entries.size > 1, onClick = vm::clearUpcoming) { Text("Clear up next") }
            }
            if (entries.isEmpty()) Text("Choose a song to start listening.")
            else {
                Text("Now playing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                QueueRow(entries.first(), vm, true, tracks, onAdd)
                Text("Up next · ${entries.size - 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 16.dp))
                Text("Hold a drag handle to reorder upcoming songs.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f), state = listState) {
                    items(entries.drop(1), key = { it.index }) { entry ->
                        QueueRow(entry, vm, false, tracks, onAdd, dragTarget == entry.index) {
                            Icon(Icons.Default.DragHandle, "Reorder ${entry.item.mediaMetadata.title}", Modifier.size(48.dp)
                                .testTag("queue-drag-${entry.index}")
                                .semantics {
                                    customActions = listOfNotNull(
                                        entries.indexOf(entry).takeIf { it > 1 }?.let { position -> CustomAccessibilityAction("Move up") { vm.moveQueue(entry, entries[position - 1], entries); true } },
                                        entries.indexOf(entry).takeIf { it < entries.lastIndex }?.let { position -> CustomAccessibilityAction("Move down") { vm.moveQueue(entry, entries[position + 1], entries); true } },
                                    )
                                }
                                .pointerInput(entry.uid) {
                                    var captured = emptyList<QueueEntry>()
                                    var y = 0f
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            captured = vm.queueItems
                                            val row = listState.layoutInfo.visibleItemsInfo.find { it.key == entry.index }
                                            y = (row?.offset ?: 0) + (row?.size ?: 0) / 2f
                                            dragTarget = entry.index
                                        },
                                        onDrag = { change, amount ->
                                            change.consume(); y += amount.y
                                            val layout = listState.layoutInfo
                                            val target = layout.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2f - y) }
                                            dragTarget = target?.key as? Int
                                            if (y > layout.viewportEndOffset - 40 || y < layout.viewportStartOffset + 40) {
                                                scope.launch { listState.scrollBy(if (amount.y > 0) 32f else -32f) }
                                                y = y.coerceIn(layout.viewportStartOffset + 1f, (layout.viewportEndOffset - 1).toFloat())
                                            }
                                        },
                                        onDragEnd = {
                                            val target = captured.find { it.index == dragTarget }
                                            if (target != null && target.index != entry.index) vm.moveQueue(entry, target, captured)
                                            dragTarget = null
                                        },
                                        onDragCancel = { dragTarget = null },
                                    )
                                })
                        }
                    }
                    if (entries.size == 1) item { Text("You're at the end of the queue.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable private fun QueueRow(entry: QueueEntry, vm: EchoViewModel, current: Boolean, tracks: List<Track>, onAdd: (Track) -> Unit, dropTarget: Boolean = false, dragHandle: (@Composable () -> Unit)? = null) {
    Surface(onClick = { vm.jumpQueue(entry) }, color = if (current || dropTarget) Color(0xFF332B40) else Color.Transparent, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag(if (current) "queue-current" else "queue-next-${entry.index}")) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            dragHandle?.invoke()
            Artwork(entry.item.mediaMetadata.artworkUri?.toString(), 44)
            Column(Modifier.weight(1f)) {
                Text(entry.item.mediaMetadata.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(entry.item.mediaMetadata.artist?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.item.mediaId in vm.recommendedIds) Text("Recommended", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            val track = tracks.find { it.id == entry.item.mediaId }
            IconButton(enabled = track != null, onClick = { track?.let(onAdd) }) { Icon(Icons.Default.PlaylistAdd, "Add ${entry.item.mediaMetadata.title} to playlist") }
            if (current) Icon(Icons.Default.GraphicEq, "Playing now", tint = MaterialTheme.colorScheme.primary)
            else IconButton(onClick = { vm.removeQueue(entry) }) { Icon(Icons.Default.Close, "Remove ${entry.item.mediaMetadata.title} from queue") }
        }
    }
}

private fun playerTime(ms: Long) = "%d:%02d".format(ms.coerceAtLeast(0) / 60_000, ms.coerceAtLeast(0) / 1000 % 60)
