package dev.echo.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.echo.data.library.ListeningEventType
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HistoryScreen(vm: EchoViewModel) {
    val events by vm.listeningHistory.collectAsStateWithLifecycle()
    val libraryTracks by vm.tracks.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Listening history", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }, enabled = events.isNotEmpty() && !vm.busy) { Text("Clear history") }
            }
            Text("On this device · Up to 90 days / 5,000 events. Showing the latest 500. Listening and skips help Smart shuffle learn your preferences.", style = MaterialTheme.typography.bodySmall)
        }
        if (events.isEmpty()) item { Text("Play a song to begin your listening history.") }
        items(events, key = { it.id }) { event ->
            val playableTrack = libraryTracks.firstOrNull { it.id == event.track.id } ?: event.track
            val outcome = when (event.event) {
                ListeningEventType.PLAYED -> "Started playing"
                ListeningEventType.SKIPPED -> "Skipped"
                ListeningEventType.COMPLETED -> "Finished"
                ListeningEventType.INTERRUPTED -> "Queue changed"
                ListeningEventType.ERROR -> "Playback error"
            }
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(event.track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(event.track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$outcome${if (event.listenedMs > 0) " · ${event.listenedMs / 1000}s listened" else ""}", style = MaterialTheme.typography.bodySmall)
                        Text(formatter.format(Date(event.occurredAt)), style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { vm.play(listOf(playableTrack)) }, enabled = !vm.busy && playableTrack.sourceUri != null) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play ${event.track.title}")
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear listening history?") },
        text = { Text("This removes listening events and resets learned preferences on this device. Your playlists and likes are kept.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; vm.runAction("Listening history cleared") { vm.library.clearListeningHistory() } }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}
