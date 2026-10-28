package dev.echo.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.echo.data.spotify.SpotifyPlaylistLinks
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.coroutines.resume
import kotlinx.coroutines.*

@Composable
fun DesktopImportDialog(store: DesktopLibrary, onChanged: () -> Unit, onError: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val links = remember { SpotifyPlaylistLinks() }
    var link by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<DesktopPlaylistImport?>(null) }
    var description by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun cancel() { job?.cancel(); onDismiss() }
    fun preview(action: suspend () -> Pair<DesktopPlaylistImport, String>) {
        pending = null; error = null; busy = true
        job = scope.launch {
            try { val result = action(); pending = result.first; description = result.second }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not read this playlist. Try a file export." }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = ::cancel, title = { Text("Import Spotify playlist") }, text = {
        Column(Modifier.width(540.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Import a public Spotify link, Exportify CSV or Spotify account-data playlist JSON. Songs need a playable recording after import.")
            OutlinedTextField(link, onValueChange = { link = it; pending = null; error = null },
                label = { Text("Public Spotify playlist link") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && link.isNotBlank(), onClick = { preview {
                    val requested = link
                    val exposed = links.preview(requested)
                    val count = when {
                        exposed.total == null -> "${exposed.tracks.size} exposed songs; total unknown. This may be a partial playlist."
                        exposed.complete -> "${exposed.tracks.size} of ${exposed.total} songs exposed."
                        else -> "${exposed.tracks.size} of ${exposed.total} songs exposed. Only these songs will be imported."
                    }
                    desktopSpotifyImport(exposed, requested) to "${exposed.name}\n$count"
                } }) { Text("Preview link") }
                OutlinedButton(enabled = !busy, onClick = { preview {
                    val file = chooseSpotifyFile() ?: return@preview DesktopPlaylistImport(emptyList()) to "No file selected"
                    val parsed = readDesktopPlaylistImport(file)
                    parsed to "${parsed.playlists.size} playlists, ${parsed.playlists.sumOf { it.tracks.size }} songs" +
                        if (parsed.skipped > 0) "; ${parsed.skipped} podcast/local entries skipped" else ""
                } }) { Text("Choose playlist file") }
            }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Reading playlist…") }
            if (pending != null) Text(description)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Imports create new playlists and preserve song order and duplicates. This imports metadata, with no Spotify audio or automatic Spotify sync.")
        }
    }, confirmButton = {
        Button(enabled = !busy && pending?.playlists?.isNotEmpty() == true, onClick = {
            val captured = pending ?: return@Button
            busy = true
            job = scope.launch {
                try {
                    withContext(Dispatchers.IO) { store.importParsedPlaylists(captured) }
                    onChanged(); onDismiss()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { val message = failure.message ?: "Import failed; your library was kept."; error = message; onError(message) }
                finally { busy = false }
            }
        }) { Text("Import previewed songs") }
    }, dismissButton = { TextButton(onClick = ::cancel) { Text(if (busy) "Cancel" else "Close") } })
}

private suspend fun chooseSpotifyFile(): File? = suspendCancellableCoroutine { continuation ->
    SwingUtilities.invokeLater {
        val picker = JFileChooser().apply {
            dialogTitle = "Import Spotify playlist file"
            fileFilter = FileNameExtensionFilter("Exportify CSV or Spotify playlist JSON", "csv", "json")
        }
        val file = if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) picker.selectedFile else null
        if (continuation.isActive) continuation.resume(file)
    }
}
