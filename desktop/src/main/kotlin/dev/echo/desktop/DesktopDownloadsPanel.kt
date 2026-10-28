package dev.echo.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.echo.core.Track
import dev.echo.data.offline.downloadPercent
import kotlinx.coroutines.launch

@Composable
fun DesktopDownloadsPanel(repository: DesktopOfflineRepository, modifier: Modifier = Modifier, onPlay: (Track) -> Unit) {
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Downloads", style = MaterialTheme.typography.headlineMedium)
        Text("Saved on this laptop. Downloads continue while Echo is open; Retry restarts an interrupted transfer.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(state.offlineOnly, onCheckedChange = { enabled -> scope.launch {
                runCatching { repository.setOfflineOnly(enabled) }.onFailure { error = it.message }
            } })
            Text("Offline playback only", Modifier.padding(start = 12.dp))
        }
        (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.entries.isEmpty()) Text("Use Download beside a playable recording to save it here.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.entries, key = { it.track.id }) { entry ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.track.title, style = MaterialTheme.typography.titleMedium)
                            Text(entry.track.artist)
                            Text(entry.message ?: when (entry.status) {
                                DesktopDownloadStatus.QUEUED -> "Queued"
                                DesktopDownloadStatus.DOWNLOADING -> "${entry.receivedBytes / 1024} KiB" +
                                    (downloadPercent(entry.receivedBytes, entry.totalBytes)?.let { " · $it%" } ?: "")
                                DesktopDownloadStatus.COMPLETE -> "Saved · ${entry.receivedBytes / (1024 * 1024)} MiB"
                                DesktopDownloadStatus.RETRY -> "Retry download"
                            })
                        }
                        when (entry.status) {
                            DesktopDownloadStatus.COMPLETE -> TextButton(onClick = { onPlay(entry.track) }) { Text("Play") }
                            DesktopDownloadStatus.QUEUED, DesktopDownloadStatus.DOWNLOADING ->
                                TextButton(onClick = { repository.cancel(entry.track.id) }) { Text("Cancel") }
                            DesktopDownloadStatus.RETRY -> TextButton(enabled = !state.offlineOnly, onClick = {
                                runCatching { repository.download(entry.track) }.onFailure { error = it.message }
                            }) { Text("Retry") }
                        }
                        TextButton(onClick = { scope.launch {
                            runCatching { repository.remove(entry.track.id) }.onFailure { error = it.message }
                        } }) { Text("Remove") }
                    }
                }
            }
        }
    }
}
