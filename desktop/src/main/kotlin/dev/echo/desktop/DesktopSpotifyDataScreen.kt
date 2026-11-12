package dev.echo.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.echo.data.spotify.PlaylistFile
import dev.echo.data.spotify.parseSpotifyAccountZip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DesktopSpotifyDataScreen(directory: Path, legacyDirectory: Path?, onlineAllowed: Boolean, onSearch: (String) -> Unit) {
    var archives by remember(directory) { mutableStateOf<List<DesktopSpotifyArchive>>(emptyList()) }
    var selected by remember(directory) { mutableStateOf<String?>(null) }
    var data by remember(directory) { mutableStateOf<PlaylistFile?>(null) }
    var loading by remember(directory) { mutableStateOf(false) }
    var error by remember(directory) { mutableStateOf<String?>(null) }
    var listing by remember(directory) { mutableStateOf(false) }
    var listError by remember(directory) { mutableStateOf<String?>(null) }
    var legacyCopiesRemain by remember(directory) { mutableStateOf(false) }
    var category by remember(directory) { mutableStateOf("History") }
    LaunchedEffect(directory) {
        listing = true
        listError = null
        try {
            val (saved, hasLegacyCopies) = withContext(Dispatchers.IO) {
                legacyDirectory?.let { migrateDesktopSpotifyArchives(it, directory) }
                val legacy = legacyDirectory?.takeUnless {
                    it.toAbsolutePath().normalize() == directory.toAbsolutePath().normalize()
                }
                listDesktopSpotifyArchives(directory) to (legacy?.let { listDesktopSpotifyArchives(it).isNotEmpty() } == true)
            }
            archives = saved
            selected = saved.firstOrNull()?.id
            legacyCopiesRemain = hasLegacyCopies
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            archives = emptyList()
            listError = failure.message ?: "Could not list saved Spotify archives"
        } finally { listing = false }
    }
    fun select(id: String) {
        if (selected == id) return
        selected = id; data = null; error = null; loading = true
    }
    LaunchedEffect(directory, selected) {
        val id = selected ?: run { loading = false; return@LaunchedEffect }
        data = null
        error = null
        loading = true
        try {
            val parsed = withContext(Dispatchers.IO) { parseSpotifyAccountZip(readDesktopSpotifyArchive(directory, id)) }
            if (selected == id) data = parsed
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (selected == id) error = failure.message ?: "Could not read this saved archive" }
        finally { if (selected == id) loading = false }
    }
    val dateFormat = remember { DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()) }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Spotify archive", style = MaterialTheme.typography.headlineMedium)
        Text("Browse this device's encrypted Spotify exports. This data stays separate from Echo listening history and sync.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (legacyCopiesRemain) Text("Older encrypted copies remain beside the previous library location. Delete saved archives in Settings to remove copies from both locations.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        if (listing) LinearProgressIndicator(Modifier.fillMaxWidth())
        listError?.let { Text("Saved Spotify archives could not be listed: $it", color = MaterialTheme.colorScheme.error) }
        if (archives.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(archives.size) { index ->
                val archive = archives[index]
                FilterChip(selected == archive.id, { select(archive.id) }, label = {
                    Text("Export ${archives.size - index} · ${dateFormat.format(Instant.ofEpochMilli(archive.modifiedAt))}")
                })
            }
        } else if (!listing && listError == null) Text("No saved Spotify ZIPs. Import an account-data ZIP from Settings to keep a local copy.")
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        data?.let { parsed ->
            Text("${parsed.history.size} listening events · ${parsed.savedArtists.size} saved artists · ${parsed.savedAlbums.size} saved albums",
                style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(category == "History", { category = "History" }, label = { Text("History (${parsed.history.size})") })
                FilterChip(category == "Artists", { category = "Artists" }, label = { Text("Artists (${parsed.savedArtists.size})") })
                FilterChip(category == "Albums", { category = "Albums" }, label = { Text("Albums (${parsed.savedAlbums.size})") })
            }
            if (category == "History") Text("Latest plays first; timestamps without a time zone are UTC.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!onlineAllowed) Text("Echo search is unavailable in Offline mode.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val sortedHistory = remember(parsed.history) { parsed.history.sortedByDescending { entry ->
                runCatching { java.time.OffsetDateTime.parse(entry.timestamp).toInstant() }
                    .getOrElse { java.time.LocalDateTime.parse(entry.timestamp.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC) }
            } }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (category == "History" && parsed.history.isNotEmpty()) {
                    items(sortedHistory) { entry ->
                        ListItem(headlineContent = { Text(entry.track.title) }, supportingContent = {
                            Text("${entry.track.artist} · ${entry.timestamp} · ${entry.msPlayed / 60_000} min played")
                        }, trailingContent = {
                            TextButton(enabled = onlineAllowed, onClick = { onSearch("${entry.track.artist} ${entry.track.title}".take(200)) }) {
                                Text("Search Echo")
                            }
                        })
                    }
                }
                if (category == "Artists" && parsed.savedArtists.isNotEmpty()) {
                    items(parsed.savedArtists) { artist ->
                        ListItem(headlineContent = { Text(artist.name) }, trailingContent = {
                            TextButton(enabled = onlineAllowed, onClick = { onSearch(artist.name.take(200)) }) { Text("Search Echo") }
                        })
                    }
                }
                if (category == "Albums" && parsed.savedAlbums.isNotEmpty()) {
                    items(parsed.savedAlbums) { album ->
                        ListItem(headlineContent = { Text(album.name) }, supportingContent = { Text(album.artist) }, trailingContent = {
                            TextButton(enabled = onlineAllowed, onClick = { onSearch("${album.name} ${album.artist}".take(200)) }) { Text("Search Echo") }
                        })
                    }
                }
                val empty = when (category) {
                    "History" -> parsed.history.isEmpty()
                    "Artists" -> parsed.savedArtists.isEmpty()
                    else -> parsed.savedAlbums.isEmpty()
                }
                if (empty) item { Text("No saved ${category.lowercase()} in this export.", modifier = Modifier.padding(top = 16.dp)) }
            }
        }
    }
}

@Composable
internal fun DesktopSpotifySettingsPage(preferences: DesktopPreferences, onImport: () -> Unit, onBrowse: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    var customAccent by remember { mutableStateOf(preferences.customAccent) }
    LaunchedEffect(preferences.customAccent) { customAccent = preferences.customAccent }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        preferences.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Appearance", style = MaterialTheme.typography.titleLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("Your Echo", style = MaterialTheme.typography.titleLarge); Text("A live preview of your colors", style = MaterialTheme.typography.bodySmall) }
                Text("▶", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopThemeMode.entries.forEach { mode ->
                FilterChip(preferences.themeMode == mode, { preferences.setTheme(mode) }, label = { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(preferences.customAccent.isBlank(), { preferences.setAccent("") }, label = { Text("Follow artwork") })
            listOf("Mint" to "#83E5CB", "Ocean" to "#62B5F5", "Violet" to "#B69CFF", "Rose" to "#F28BA8", "Amber" to "#F2B866").forEach { (name, value) ->
                val color = value.toDesktopColor() ?: MaterialTheme.colorScheme.primary
                FilterChip(preferences.customAccent.equals(value, true), { preferences.setAccent(value) }, label = { Text(name, color = color) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(customAccent, { customAccent = it.take(7) }, label = { Text("Custom accent color (hex)") },
            placeholder = { Text("#83E5CB") }, singleLine = true)
            Button(enabled = customAccent.matches(Regex("#[0-9a-fA-F]{6}")), onClick = { preferences.setAccent(customAccent) }) { Text("Save") }
        }
        Text("Behavior", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(preferences.closeToTray, preferences::updateCloseToTray)
            Column {
                Text("Keep Echo running when I close the window")
                Text("When off, closing the window quits Echo.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Keyboard shortcuts", style = MaterialTheme.typography.titleMedium)
        Text("Space · Play / pause    Ctrl + Right / Left · Next / previous")
        Text("Ctrl + Shift + Right / Left · Seek 10 seconds")
        Text("Ctrl + Up / Down · Laptop volume    Ctrl + M · Mute")
        Text("Shortcuts work while the Echo window is focused.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Spotify account data", style = MaterialTheme.typography.titleLarge)
        Text("Import a Spotify account-data ZIP to keep an encrypted copy on this laptop. It stays outside your Echo library and sync.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImport) { Text("Import Spotify ZIP") }
            OutlinedButton(onClick = onBrowse) { Text("Browse archive") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete saved archives") }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete saved Spotify archives?") },
        text = { Text("This removes encrypted Spotify ZIP copies from this laptop. Your Echo library is not changed.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}
