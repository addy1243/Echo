package dev.echo.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.echo.core.Track
import dev.echo.data.provider.YouTubeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun DesktopDiscoverScreen(
    provider: YouTubeProvider,
    library: DesktopLibraryState,
    onPlay: (List<Track>, Int) -> Unit,
    onSave: (Track) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    onlineAllowed: Boolean = true,
    onSaveMix: ((List<Track>) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<DesktopDiscoveryResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var tab by remember { mutableStateOf(0) }
    val artists = remember(library) { desktopDiscoveryArtists(library) }
    fun refresh() {
        if (!onlineAllowed || loading) return
        loading = true
        job = scope.launch {
            try { result = loadDesktopDiscovery(library, provider::search) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onError("Discoveries couldn't load. Try refreshing or searching another artist.") }
            finally { loading = false }
        }
    }
    LaunchedEffect(onlineAllowed, artists) {
        if (!onlineAllowed) job?.cancel()
        else if (result == null && artists.isNotEmpty()) refresh()
    }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Discover", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (loading) TextButton(onClick = { job?.cancel() }) { Text("Cancel") }
            Button(onClick = { refresh() }, enabled = onlineAllowed && !loading) { Text("Refresh") }
        }
        Text(if (artists.isEmpty()) "Like or save songs to find more from your artists. Refresh can search this month's new music."
            else "Explore songs from your artists: ${artists.joinToString()}")
        if (!onlineAllowed) Text("Offline mode: discovery searches are paused.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("For you") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("New music search") })
        }
        if (tab == 1) Text("Current-month YouTube search results. Release dates are not verified.")
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val tracks = if (tab == 0) result?.discovery.orEmpty() else result?.newMusic.orEmpty()
        if (tracks.isEmpty() && !loading) Text(if (result == null) "Refresh to load music." else "No songs found here. Try refreshing or Search.")
        if (tracks.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onPlay(tracks, 0) }) { Text("Play ${if (tab == 0) "discoveries" else "results"}") }
            onSaveMix?.let { save -> OutlinedButton(onClick = { save(tracks) }) { Text("Save as playlist") } }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${index + 1}", modifier = Modifier.width(30.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { onSave(track) }) { Text("Add to playlist") }
                        TextButton(onClick = { onPlay(tracks, index) }) { Text("Play") }
                    }
                }
            }
        }
    }
}
