@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.echo.app

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import dev.echo.core.Track
import dev.echo.core.Playlist

@Composable internal fun PlayerScreen(vm: EchoViewModel, tracks: List<Track>, playlists: List<Playlist>, favorites: Set<String>, close: () -> Unit) {
    var showQueue by remember { mutableStateOf(false) }
    var credits by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf<Track?>(null) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    val current = tracks.find { it.id == vm.current?.mediaId }
    val player = vm.controller
    val revision = vm.revision
    val accent = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF342742), Color(0xFF141318), Color(0xFF101014)))).testTag("player-screen")) {
        val artSize = minOf(maxWidth - 56.dp, maxHeight * .38f).coerceIn(140.dp, 340.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) { Icon(Icons.Default.KeyboardArrowDown, "Close player", modifier = Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp, color = accent)
                    Text(if (vm.recommendedIds.contains(vm.current?.mediaId)) "Recommended for this session" else "Your music, in the moment", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Open queue") }
            }
            Spacer(Modifier.height(14.dp))
            Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 20.dp) { Artwork(vm.current?.mediaMetadata?.artworkUri?.toString(), artSize.value.toInt()) }
            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(vm.current?.mediaMetadata?.title?.toString() ?: "Choose a song", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(vm.current?.mediaMetadata?.artist?.toString().orEmpty(), style = MaterialTheme.typography.bodyLarge, color = Color(0xFFC8C1CE), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(enabled = current != null, onClick = { current?.let(vm::favorite) }) { Icon(if (current?.id in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Like song", tint = accent) }
                IconButton(enabled = current != null, onClick = { add = current }) { Icon(Icons.Default.PlaylistAdd, "Add current song to playlist", tint = accent) }
            }
            Spacer(Modifier.height(18.dp))
            current?.let { DownloadTrackAction(vm, it) }
            SleepTimerControls(vm)
            val progress = seeking ?: vm.position.toFloat().coerceIn(0f, vm.duration.toFloat().coerceAtLeast(1f))
            Slider(progress, { seeking = it }, valueRange = 0f..vm.duration.toFloat().coerceAtLeast(1f), enabled = vm.duration > 0,
                onValueChangeFinished = { seeking?.let { player?.seekTo(it.toLong()) }; seeking = null }, modifier = Modifier.fillMaxWidth().testTag("player-seek"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(playerTime(progress.toLong()), style = MaterialTheme.typography.labelSmall); Text(playerTime(vm.duration), style = MaterialTheme.typography.labelSmall) }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
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
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, null); Spacer(Modifier.width(8.dp)); Text("Up next · ${vm.queueItems.drop(1).size}") }
                if (current?.attribution != null) TextButton(onClick = { credits = true }) { Text("Credits") }
                else Text(current?.provider?.replaceFirstChar { it.uppercase() }.orEmpty(), style = MaterialTheme.typography.labelSmall, color = Color(0xFFA29BAA))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (showQueue) QueueSheet(vm, tracks, onAdd = { add = it }) { showQueue = false }
    add?.let { track -> AddToPlaylistDialog(track, vm, playlists) { add = null } }
    if (credits && current != null) Credits(current) { credits = false }
}

@Composable private fun QueueSheet(vm: EchoViewModel, tracks: List<Track>, onAdd: (Track) -> Unit, close: () -> Unit) {
    val entries = vm.queueItems
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
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
                LazyColumn(Modifier.weight(1f), state = listState) {
                    items(entries.drop(1), key = { it.index }) { entry -> QueueRow(entry, vm, false, tracks, onAdd) }
                    if (entries.size == 1) item { Text("You're at the end of the queue.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable private fun QueueRow(entry: QueueEntry, vm: EchoViewModel, current: Boolean, tracks: List<Track>, onAdd: (Track) -> Unit) {
    Surface(onClick = { vm.jumpQueue(entry) }, color = if (current) Color(0xFF332B40) else Color.Transparent, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag(if (current) "queue-current" else "queue-next-${entry.index}")) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
