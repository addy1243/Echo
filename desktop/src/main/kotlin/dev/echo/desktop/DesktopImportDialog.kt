package dev.echo.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.echo.data.provider.YouTubeProvider
import dev.echo.data.spotify.SpotifyPlaylistLinks
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun DesktopImportDialog(store: DesktopLibrary, spotifyArchiveDirectory: java.nio.file.Path, onChanged: () -> Unit, onError: (String) -> Unit, onDismiss: () -> Unit, initialLink: String? = null, initialFilePicker: Boolean = false, onlineAllowed: Boolean = true) {
    val scope = rememberCoroutineScope()
    val links = remember { SpotifyPlaylistLinks() }
    val youtube = remember { YouTubeProvider() }
    var link by remember(initialLink) { mutableStateOf(initialLink.orEmpty()) }
    var pending by remember { mutableStateOf<DesktopPlaylistImport?>(null) }
    var description by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var autoUpdate by remember { mutableStateOf(false) }
    var eligibleLink by remember { mutableStateOf<String?>(null) }
    var matching by remember { mutableStateOf(false) }
    var matchProgress by remember { mutableStateOf("") }
    val partialFlow = remember { MutableStateFlow<DesktopPlaylistImport?>(null) }
    val partialPreview by partialFlow.collectAsState()
    fun cancel() { job?.cancel(); onDismiss() }
    fun preview(action: suspend () -> Pair<DesktopPlaylistImport, String>) {
        pending = null; partialFlow.value = null; description = ""; error = null; busy = true; eligibleLink = null; autoUpdate = false
        job = scope.launch {
            try {
                val result = action(); pending = result.first; description = result.second
                val originals = result.first.playlists.flatMap { it.tracks }
                val unmatched = originals.filter { it.provider == "spotify" && it.sourceUri == null }.distinctBy { it.id }
                if (result.first.archive == null && onlineAllowed && unmatched.isNotEmpty()) {
                    matching = true
                    val limit = unmatched.take(25)
                    matchProgress = "Matching imported songs…"
                    try {
                        val matches = automaticDesktopMatches(limit, store.snapshot().tracks, youtube::search) { done, total ->
                            matchProgress = "Matching $done / $total"
                        }
                        pending = result.first.copy(playlists = result.first.playlists.map { playlist ->
                            playlist.copy(tracks = playlist.tracks.map { matches[it.id] ?: it })
                        })
                        val left = (unmatched.size - limit.size).coerceAtLeast(0)
                        description = result.second + "\n${matches.size} matched automatically; ambiguous songs remain unchanged." +
                            if (left > 0) " $left additional songs were left for manual matching." else ""
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        error = "Automatic matching stopped; original songs are still ready to import. ${failure.message.orEmpty()}"
                    } finally { matching = false }
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not read this playlist. Try a file export." }
            finally { matching = false; busy = false; partialFlow.value = null }
        }
    }
    fun loadLink(requested: String) = preview {
        if (runCatching { java.net.URI(requested.trim()).host.equals("open.spotify.com", true) }.getOrDefault(false)) {
            val exposed = links.preview(requested)
            val count = when {
                exposed.total == null -> "${exposed.tracks.size} exposed songs; total unknown. This may be a partial playlist."
                exposed.complete -> "${exposed.tracks.size} of ${exposed.total} songs exposed."
                else -> "${exposed.tracks.size} of ${exposed.total} songs exposed. Only these songs will be imported."
            }
            desktopSpotifyImport(exposed, requested) to "${exposed.name}\n$count"
        } else {
            val exposed = youtube.playlist(requested, onFirstPage = { first ->
                partialFlow.value = desktopYouTubeImport(first, requested)
            })
            if (exposed.complete && !dev.echo.data.provider.youtubePlaylistId(requested).startsWith("RD")) eligibleLink = requested.trim()
            val count = when {
                exposed.hasMore -> "At least ${exposed.tracks.size} songs found; more entries remain. Only these will be imported."
                exposed.total == null -> "${exposed.tracks.size} songs; total unknown. This may be partial."
                exposed.complete -> "${exposed.tracks.size} of ${exposed.total} songs available."
                else -> "${exposed.tracks.size} of ${exposed.total} songs available. Only these will be imported."
            }
            desktopYouTubeImport(exposed, requested) to "${exposed.name}\n$count"
        }
    }
    fun loadFile() = preview {
        val file = chooseSpotifyFile() ?: return@preview DesktopPlaylistImport(emptyList()) to "No file selected"
        val parsed = readDesktopPlaylistImport(file)
        parsed to "${parsed.playlists.size} playlists, ${parsed.playlists.sumOf { it.tracks.size }} songs" +
            if (parsed.archive != null) " · ${parsed.historyCount} history events · ${parsed.savedArtistCount} saved artists · ${parsed.savedAlbumCount} saved albums" else "" +
            if (parsed.skipped > 0) "; ${parsed.skipped} podcast/local entries skipped" else ""
    }
    LaunchedEffect(initialLink, initialFilePicker) {
        if (!initialLink.isNullOrBlank()) {
            if (onlineAllowed) loadLink(initialLink) else error = "Turn off Offline mode to load this playlist."
        } else if (initialFilePicker) loadFile()
    }
    AlertDialog(onDismissRequest = ::cancel, title = { Text("Import public playlist") }, text = {
        Column(Modifier.width(540.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Import a public YouTube or Spotify playlist link, Exportify CSV, Spotify playlist JSON, or Spotify account-data ZIP.")
            OutlinedTextField(link, onValueChange = { link = it; pending = null; error = null; eligibleLink = null; autoUpdate = false },
                label = { Text("YouTube or Spotify playlist link") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && onlineAllowed && link.isNotBlank(), onClick = { loadLink(link) }) { Text("Preview link") }
                OutlinedButton(enabled = !busy, onClick = ::loadFile) { Text("Choose playlist file") }
            }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (matching) matchProgress else "Reading playlist…") }
            val shown = pending ?: partialPreview
            shown?.let { draft ->
                Text(description.ifBlank { draft.playlists.firstOrNull()?.name.orEmpty() })
                val songs = draft.playlists.flatMap { it.tracks }
                LazyColumn(Modifier.heightIn(max = 230.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(songs.size) { index ->
                        val track = songs[index]
                        Text("${index + 1}. ${track.title} · ${track.artist}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (pending != null && eligibleLink != null) Row {
                Checkbox(checked = autoUpdate, onCheckedChange = { autoUpdate = it }, enabled = !busy)
                Text("Automatically update this playlist while Echo is running")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Imports create new playlists and preserve song order and duplicates. Spotify imports are metadata snapshots with no audio or automatic sync.")
        }
    }, confirmButton = {
        Button(enabled = !busy && (pending?.playlists?.isNotEmpty() == true || pending?.archive != null), onClick = {
            val captured = pending ?: return@Button
            val capturedAutoUpdate = autoUpdate && eligibleLink != null
            val capturedEligibleLink = eligibleLink
            busy = true
            job = scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        captured.archive?.let { saveDesktopSpotifyArchive(it, spotifyArchiveDirectory) }
                        if (capturedAutoUpdate && capturedEligibleLink != null) {
                            val playlist = captured.playlists.single()
                            store.importLinkedYouTubePlaylist(capturedEligibleLink, playlist.name, playlist.tracks)
                        } else if (captured.playlists.isNotEmpty()) store.importParsedPlaylists(captured)
                    }
                    onChanged(); onDismiss()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { val message = failure.message ?: "Import failed; your library was kept."; error = if (captured.archive != null) "ZIP saved locally, but playlist import failed: $message" else message; onError(error!!) }
                finally { busy = false }
            }
        }) { Text(if (pending?.archive != null) "Save ZIP and import" else "Import playlist${if ((pending?.playlists?.size ?: 0) == 1) "" else "s"}") }
    }, dismissButton = {
        Row {
            if (matching) TextButton(onClick = { job?.cancel() }) { Text("Skip matching") }
            TextButton(onClick = ::cancel) { Text(if (busy) "Cancel" else "Close") }
        }
    })
}

private suspend fun chooseSpotifyFile(): File? = suspendCancellableCoroutine { continuation ->
    SwingUtilities.invokeLater {
        val picker = JFileChooser().apply {
            dialogTitle = "Import Spotify playlist file"
            fileFilter = FileNameExtensionFilter("Exportify CSV, Spotify playlist JSON or account ZIP", "csv", "json", "zip")
        }
        val file = if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) picker.selectedFile else null
        if (continuation.isActive) continuation.resume(file)
    }
}
