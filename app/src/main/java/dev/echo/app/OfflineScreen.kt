package dev.echo.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.echo.core.Track

@Composable internal fun OfflineSettingsSection(vm: EchoViewModel) {
    val settings by vm.offlineSettings.collectAsStateWithLifecycle()
    val songs by vm.offlineSongs.collectAsStateWithLifecycle()
    Text("Offline music", style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Offline mode")
            Text("Play downloads and device files", style = MaterialTheme.typography.bodySmall)
        }
        Switch(settings.offlineOnly, vm::setOfflineOnly, modifier = Modifier.testTag("offline-mode"))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Cache frequently played songs")
            Text("After 3 substantial listens · unmetered internet only · 256 MiB", style = MaterialTheme.typography.bodySmall)
        }
        Switch(settings.autoCache, vm::setAutomaticCache, modifier = Modifier.testTag("automatic-cache"))
    }
    Text("Downloads stay until removed (2 GiB limit). Automatic cache can be evicted. Manual downloads may use mobile data.", style = MaterialTheme.typography.bodySmall)
    TextButton(enabled = songs.any { !it.pinned }, onClick = { vm.clearAutomaticCache() }) { Text("Clear automatic audio cache") }
}

@Composable internal fun DownloadsScreen(vm: EchoViewModel) {
    val songs by vm.offlineSongs.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().testTag("downloads-screen"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Downloads", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Keep Echo open while downloading. Interrupted transfers show Retry. Downloads are private to this device and are removed when Echo is uninstalled.", style = MaterialTheme.typography.bodySmall) }
        item { OfflineSettingsSection(vm) }
        if (songs.isEmpty()) item { Text("No saved audio yet. Open a song's options or the player and choose Download.") }
        items(songs, key = { it.trackId }) { song ->
            val track = tracks.find { it.id == song.trackId }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium)
                    Text("${if (song.pinned) "Download" else "Automatic cache"} · ${song.status}" +
                        if (song.status == "saved") " · ${"%.1f".format(song.bytes / (1024.0 * 1024))} MiB" else "", style = MaterialTheme.typography.bodySmall)
                    song.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (song.status == "saved" && track != null) TextButton(onClick = { vm.play(listOf(track)) }) { Text("Play") }
                        if (song.status in setOf("queued", "downloading")) TextButton(onClick = { vm.cancelDownload(song.trackId) }) { Text("Cancel") }
                        if (song.status == "failed" && track != null) TextButton(onClick = { vm.downloadTrack(track) }) { Text("Retry") }
                        if (song.status == "saved" && !song.pinned && track != null) TextButton(onClick = { vm.downloadTrack(track) }) { Text("Keep") }
                        TextButton(onClick = { vm.removeDownload(song.trackId) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable internal fun DownloadTrackAction(vm: EchoViewModel, track: Track) {
    if (track.provider !in setOf("youtube", "commons")) return
    val songs by vm.offlineSongs.collectAsStateWithLifecycle()
    val song = songs.find { it.trackId == track.id }
    val settings by vm.offlineSettings.collectAsStateWithLifecycle()
    val saved = song?.status == "saved" && song.pinned
    val pending = song?.status in setOf("queued", "downloading")
    TextButton(enabled = !saved && !pending && !settings.offlineOnly, onClick = { vm.downloadTrack(track) }) {
        Icon(if (saved) Icons.Default.DownloadDone else Icons.Default.Download, null)
        Spacer(Modifier.width(6.dp))
        Text(when { saved -> "Downloaded"; pending -> "Downloading…"; song?.status == "failed" -> "Retry download"; song?.status == "saved" -> "Keep download"; else -> "Download" })
    }
}
