@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.echo.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import dev.echo.data.spotify.PlaylistFile
import dev.echo.data.spotify.SpotifyHistoryEntry
import dev.echo.data.spotify.SpotifyArchiveStore
import dev.echo.data.spotify.StoredSpotifyArchive
import dev.echo.data.spotify.parseSpotifyAccountZip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun SpotifyDataScreen(onSearch: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context) { SpotifyArchiveStore(context) }
    var archives by remember { mutableStateOf<List<StoredSpotifyArchive>>(emptyList()) }
    var selectedHash by rememberSaveable { mutableStateOf<String?>(null) }
    var archive by remember { mutableStateOf<PlaylistFile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var listing by remember { mutableStateOf(true) }
    var reading by remember { mutableStateOf(false) }
    val loading = listing || reading
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val historyRows = remember(archive) {
        archive?.history.orEmpty().mapIndexed { index, entry -> SpotifyHistoryRow(index, entry, spotifyInstant(entry.timestamp)) }
            .sortedWith { first, second ->
                when {
                    first.occurredAt == null && second.occurredAt != null -> 1
                    first.occurredAt != null && second.occurredAt == null -> -1
                    first.occurredAt != null && second.occurredAt != null ->
                        second.occurredAt.compareTo(first.occurredAt).takeIf { it != 0 } ?: first.index.compareTo(second.index)
                    else -> first.index.compareTo(second.index)
                }
            }
    }

    suspend fun refresh() {
        listing = true
        error = null
        try {
            val available = store.list()
            archives = available
            if (available.none { it.sha256 == selectedHash }) {
                selectedHash = available.firstOrNull()?.sha256
                archive = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Saved Spotify archives couldn't be listed"
        } finally {
            listing = false
        }
    }

    LaunchedEffect(store) { refresh() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { refresh() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(selectedHash) {
        archive = null
        val hash = selectedHash ?: run { reading = false; return@LaunchedEffect }
        reading = true
        error = null
        try {
            val parsed = withContext(Dispatchers.IO) { parseSpotifyAccountZip(store.read(hash)) }
            if (selectedHash == hash) archive = parsed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (selectedHash == hash) error = failure.message ?: "This Spotify archive couldn't be verified or read"
        } finally {
            if (selectedHash == hash) reading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Spotify data on this device", style = MaterialTheme.typography.headlineSmall)
                Text("Encrypted exports stay separate from Echo listening history, library sync, and backups.", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { scope.launch { refresh() } }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh saved Spotify exports")
            }
        }
        if (archives.isNotEmpty()) {
            Text("Saved export", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(archives) { index, item ->
                    FilterChip(selectedHash == item.sha256, onClick = { selectedHash = item.sha256 }, label = {
                        Text("Export ${index + 1} · ${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(item.modifiedAt))}")
                    })
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (archives.isEmpty() && !loading) {
            Text("No saved ZIP exports. Import a Spotify account-data ZIP from Settings to browse its listening history, saved artists, and saved albums.")
        }
        archive?.let { parsed ->
            Text("${parsed.history.size} history plays · ${parsed.savedArtists.size} saved artists · ${parsed.savedAlbums.size} saved albums", style = MaterialTheme.typography.bodySmall)
            Text("Latest plays first by timestamp (UTC where available); repeated plays are kept. Browsing does not add them to Echo history or sync them. Search opens Echo Search without sending a request.", style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.TabRow(tab) {
                listOf("History", "Artists", "Albums").forEachIndexed { index, title ->
                    androidx.compose.material3.Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (tab) {
                    0 -> if (parsed.history.isEmpty()) item { Text("No listening history was found in this export.") }
                    else itemsIndexed(historyRows, key = { _, row -> row.index }) { _, row ->
                        val entry = row.entry
                        DataRow(entry.track.title, entry.track.artist, "${entry.msPlayed / 60_000} min · ${formatSpotifyTimestamp(entry.timestamp, row.occurredAt)}") {
                            onSearch("${entry.track.title} ${entry.track.artist}")
                        }
                    }
                    1 -> if (parsed.savedArtists.isEmpty()) item { Text("No saved artists were found in this export.") }
                    else itemsIndexed(parsed.savedArtists) { _, item ->
                        DataRow(item.name, "Saved Spotify artist", "") { onSearch(item.name) }
                    }
                    else -> if (parsed.savedAlbums.isEmpty()) item { Text("No saved albums were found in this export.") }
                    else itemsIndexed(parsed.savedAlbums) { _, item ->
                        DataRow(item.name, item.artist.ifBlank { "Saved Spotify album" }, "") { onSearch(listOf(item.name, item.artist).filter { it.isNotBlank() }.joinToString(" ")) }
                    }
                }
            }
        } ?: if (archives.isNotEmpty() && !loading && error == null) Text("Select an export to browse its history, artists, and albums.") else Unit
    }
}

@Composable
private fun DataRow(title: String, subtitle: String, detail: String, onSearch: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSearch) { Text("Search Echo") }
        }
    }
}

private data class SpotifyHistoryRow(val index: Int, val entry: SpotifyHistoryEntry, val occurredAt: Instant?)

private fun spotifyInstant(value: String): Instant? = runCatching { Instant.parse(value) }
    .getOrElse { runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull() }
    ?: runCatching { java.time.LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }.getOrNull()

private fun formatSpotifyTimestamp(value: String, instant: Instant?): String = instant?.let {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneOffset.UTC).format(it) + " UTC"
} ?: value
