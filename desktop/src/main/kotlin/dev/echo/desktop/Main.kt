package dev.echo.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.echo.core.Track
import dev.echo.desktop.DesktopLibrary.Companion.localTrack
import dev.echo.data.provider.YouTubeProvider
import kotlinx.coroutines.*
import java.awt.Dimension
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

private val Canvas = Color(0xFF0C1016)
private val Panel = Color(0xFF141B24)
private val Muted = Color(0xFF98A6B8)
private val Accent = Color(0xFF83E5CB)

fun main() = application {
    val startup = remember { runCatching {
        System.getProperty("echo.desktop.library")?.let { DesktopLibrary(java.nio.file.Path.of(it)) } ?: DesktopLibrary()
    } }
    val store = startup.getOrNull()
    val provider = remember { YouTubeProvider() }
    val offline = remember { DesktopOfflineRepository(provider) }
    val player = remember { store?.let { DesktopPlayback(provider, offline) { track, event, listened ->
        it.recordHistory(track, event, listened, track.durationMs)
    } } }
    Window(onCloseRequest = { player?.close(); offline.close(); exitApplication() }, title = "Echo Music", icon = painterResource("echo-icon.png"),
        state = rememberWindowState(placement = WindowPlacement.Maximized, width = 1280.dp, height = 760.dp)) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(1060, 650) }
        MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Canvas,
            surface = Panel, onSurface = Color(0xFFEAF0F7))) {
            if (store != null && player != null) DesktopApp(store, provider, player, offline)
            else Surface(Modifier.fillMaxSize(), color = Canvas) {
                Column(Modifier.padding(60.dp), verticalArrangement = Arrangement.Center) {
                    Text("Your library needs attention", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Echo could not read its saved library. The original file has been preserved.\n\n" +
                        (startup.exceptionOrNull()?.message ?: "Unknown library error") +
                        "\n\nLibrary: " + (System.getProperty("echo.desktop.library") ?: "${System.getenv("LOCALAPPDATA")}/EchoMusic/library.json"),
                        modifier = Modifier.padding(vertical = 24.dp))
                    Button(onClick = { exitApplication() }) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun DesktopApp(store: DesktopLibrary, provider: YouTubeProvider, player: DesktopPlayback, offline: DesktopOfflineRepository) {
    val scope = rememberCoroutineScope()
    val updater = remember { DesktopUpdater() }
    val update by updater.state.collectAsState()
    var showUpdates by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { updater.check(manual = false) }
    LaunchedEffect(update.phase) {
        if (update.phase in setOf(DesktopUpdatePhase.AVAILABLE, DesktopUpdatePhase.READY)) showUpdates = true
    }
    DisposableEffect(updater) { onDispose { updater.close() } }
    val google = remember { DesktopGoogleAccount() }
    val account by google.state.collectAsState()
    val firebase = remember { DesktopFirebaseSync(store, google) }
    val cloudSync by firebase.state.collectAsState()
    var accountJob by remember { mutableStateOf<Job?>(null) }
    var automaticSync by remember { mutableStateOf(false) }
    var confirmCloudSync by remember { mutableStateOf(false) }
    val playback by player.state.collectAsState()
    val downloads by offline.state.collectAsState()
    var library by remember { mutableStateOf(store.snapshot()) }
    var page by remember { mutableStateOf("Home") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Track>()) }
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchGeneration by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var newPlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var addTrack by remember { mutableStateOf<Track?>(null) }
    var showDevices by remember { mutableStateOf(false) }
    var smartJob by remember { mutableStateOf<Job?>(null) }
    var smartBusy by remember { mutableStateOf(false) }
    var recommendedIds by remember { mutableStateOf(emptySet<String>()) }
    var renamePlaylist by remember { mutableStateOf<DesktopPlaylist?>(null) }
    var deletePlaylist by remember { mutableStateOf<DesktopPlaylist?>(null) }
    var clearHistory by remember { mutableStateOf(false) }
    var timerMenu by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var matchingJob by remember { mutableStateOf<Job?>(null) }
    var matchingProgress by remember { mutableStateOf<String?>(null) }
    var recordingTarget by remember { mutableStateOf<Triple<String, Int, List<String>>?>(null) }
    fun disk(action: () -> Unit) { scope.launch {
        try { library = withContext(Dispatchers.IO) { action(); store.snapshot() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { notice = e.message ?: "The library could not be saved" }
    } }
    fun accountAction(action: suspend () -> Unit) {
        if (accountJob?.isActive == true) return
        accountJob = scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = "The account operation could not finish. Your local library is preserved." }
        }
    }
    LaunchedEffect(account.email) {
        automaticSync = false
        if (account.email != null) try {
            automaticSync = withContext(Dispatchers.IO) { firebase.automaticEnabled() }
        } catch (_: Exception) { notice = "The saved sync preference could not be read. Automatic sync is off." }
    }
    LaunchedEffect(account.email, automaticSync) {
        if (account.email != null && automaticSync) while (true) {
            if (!google.state.value.busy) {
                firebase.synchronize()
                library = withContext(Dispatchers.IO) { store.snapshot() }
            }
            delay(60_000)
        }
    }
    fun search() {
        if (query.isBlank()) return
        val term = query.trim()
        page = "Search"
        searchJob?.cancel()
        val ticket = ++searchGeneration
        searchJob = scope.launch {
            searching = true
            try { val found = provider.search(term); if (ticket == searchGeneration) results = found }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == searchGeneration) { notice = e.message ?: "Search failed"; results = emptyList() } }
            finally { if (ticket == searchGeneration) searching = false }
        }
    }
    LaunchedEffect(playback.occurrence, playback.playing, page) {
        library = withContext(Dispatchers.IO) { store.snapshot() }
    }
    val selectedPlaylist = library.playlists.find { "playlist:${it.id}" == page }
    val trackMap = library.tracks.associateBy { it.id }
    val tracks = when {
        page == "Search" -> results
        page == "Liked songs" -> library.tracks.filter { it.id in library.favorites }
        page == "History" -> library.history.asReversed().mapNotNull { trackMap[it.trackId] }
        selectedPlaylist != null -> selectedPlaylist.trackIds.mapNotNull { trackMap[it] }
        else -> library.tracks
    }
    val title = selectedPlaylist?.name ?: page
    val upcomingIndexes = if (playback.current == null) emptyList() else
        (playback.index + 1 until playback.queue.size).toList() +
            if (playback.repeatMode == DesktopRepeatMode.ALL) (0 until playback.index).toList() else emptyList()
    val currentRow = if (tracks.map { it.id } == playback.queue.map { it.id }) playback.index else -1
    fun play(items: List<Track>, index: Int, selectedOnly: Boolean = false) {
        val track = items.getOrNull(index) ?: return
        if (!desktopPlayable(track)) {
            query = "${track.artist} ${track.title}".take(200)
            notice = "This is imported song metadata. Search for its recording, or open the matching audio file on this laptop."
            page = "Search"
            return
        }
        val playable = items.filter(::desktopPlayable)
        val selected = items.take(index).count(::desktopPlayable)
        if (playable.isNotEmpty()) {
            if (selectedOnly) player.searchSelected(track) else player.play(playable, selected)
        }
    }
    fun smartShuffle(items: List<Track>) {
        if (smartJob?.isActive == true) return
        val captured = player.state.value
        val original = items.filter(::desktopPlayable)
        val playlist = selectedPlaylist
        val retained = captured.current.takeIf { captured.canRetainCurrent }
        smartJob = scope.launch {
            smartBusy = true
            try {
                val snapshot = withContext(Dispatchers.IO) { store.snapshot() }
                val mixed = prepareDesktopSmartShuffle(original, retained, snapshot, provider::related, provider::search)
                val latest = withContext(Dispatchers.IO) { store.snapshot() }
                require(playlist == null || latest.playlists.find { it.id == playlist.id }?.trackIds == playlist.trackIds) {
                    "The playlist changed while finding related songs. Try Smart shuffle again."
                }
                val keep = retained?.id?.takeIf { it == mixed.anchor.id }
                require(player.applySmartQueue(mixed.queue, captured.occurrence, keep, captured.queueRevision)) {
                    "Playback or the queue changed while finding related songs. Try Smart shuffle again."
                }
                recommendedIds = mixed.queue.filter { candidate -> original.none { it.id == candidate.id } }.map { it.id }.toSet()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "Related songs could not be found. Your queue is unchanged." }
            finally { smartBusy = false }
        }
    }
    Surface(Modifier.fillMaxSize(), color = Canvas) {
        Column {
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(220.dp).fillMaxHeight().background(Color(0xFF10151D)).verticalScroll(rememberScrollState()).padding(22.dp)) {
                    Text("echo", fontSize = 35.sp, fontWeight = FontWeight.Black, color = Accent)
                    Text("MUSIC, IN YOUR SPACE", fontSize = 9.sp, color = Muted, letterSpacing = 2.sp)
                    Spacer(Modifier.height(38.dp))
                    listOf("Home", "Search", "Library", "Liked songs", "History", "Downloads").forEach { destination ->
                        NavItem(destination, page == destination) { page = destination }
                    }
                    Spacer(Modifier.height(28.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("YOUR PLAYLISTS", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { newPlaylist = true }) { Text("+") }
                    }
                    Column {
                        library.playlists.forEach { playlist ->
                            NavItem(playlist.name, selectedPlaylist?.id == playlist.id) { page = "playlist:${playlist.id}" }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = Color(0xFF28303C))
                    TextButton(onClick = { showDevices = true }) { Text("Devices & account", color = Muted) }
                    TextButton(onClick = { showUpdates = true }) { Text("App updates", color = Muted) }
                    TextButton(onClick = { showImport = true }) { Text("Import playlist", color = Muted) }
                    TextButton(onClick = { scope.launch {
                        val file = chooseFiles(true).firstOrNull() ?: return@launch
                        disk {
                            require(file.length() <= 8 * 1024 * 1024) { "Backup is too large (maximum 8 MiB)" }
                            store.importBackup(file.readText())
                        }
                    } }) { Text("Import Echo backup", color = Muted) }
                    TextButton(onClick = { scope.launch {
                        val file = chooseBackupDestination() ?: return@launch
                        disk { writeDesktopBackup(file, store.exportBackup()) }
                    } }) { Text("Export library", color = Muted) }
                    Text("DESKTOP PREVIEW", fontSize = 10.sp, color = Muted)
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 30.dp, vertical = 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(query, { query = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text("Search songs and artists") }, singleLine = true,
                            shape = RoundedCornerShape(28.dp), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }))
                        Button(onClick = { search() }, enabled = query.isNotBlank()) { Text("Search") }
                        OutlinedButton(onClick = { scope.launch {
                            val files = chooseFiles(false)
                            if (files.isNotEmpty()) disk { files.forEach { store.saveTrack(localTrack(it)) } }
                        } }) { Text("Open music") }
                    }
                    Spacer(Modifier.height(26.dp))
                    if (page == "Downloads") {
                        DesktopDownloadsPanel(offline, onPlay = { track -> play(listOf(track), 0) }, modifier = Modifier.weight(1f))
                    } else if (page == "Home") {
                        DesktopDiscoverScreen(provider, library, { items, index -> play(items, index) },
                            { addTrack = it }, { notice = it }, Modifier.weight(1f), onlineAllowed = !downloads.offlineOnly,
                            onSaveMix = { items -> disk {
                                store.importParsedPlaylists(DesktopPlaylistImport(listOf(DesktopImportPlaylist("Discovery mix", items))))
                            } })
                    } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (page == "Home") "Your collection" else title, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                            Text(when {
                                searching -> "Searching YouTube Music…"
                                page == "History" -> "Listening history · ${tracks.size} events"
                                else -> "${tracks.size} songs · ${if (page == "Search") "YouTube Music" else "On this laptop"}"
                            }, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 5.dp))
                        }
                        selectedPlaylist?.let { playlist ->
                            TextButton(onClick = { playlistName = playlist.name; renamePlaylist = playlist }) { Text("Rename") }
                            TextButton(onClick = { deletePlaylist = playlist }) { Text("Delete") }
                            TextButton(enabled = matchingProgress == null && !downloads.offlineOnly, onClick = {
                                val expected = playlist.trackIds.toList()
                                val items = tracks.toList()
                                matchingJob = scope.launch {
                                    matchingProgress = "Finding clear recording matches…"
                                    try {
                                        val matches = automaticDesktopMatches(items, library.tracks, provider::search) { done, total ->
                                            matchingProgress = "Matching $done / $total"
                                        }
                                        withContext(Dispatchers.IO) { store.applyRecordingMatches(playlist.id, expected, matches) }
                                        library = withContext(Dispatchers.IO) { store.snapshot() }
                                        notice = "Recording matching finished. Ambiguous songs still need Find recording."
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { notice = e.message ?: "Matching failed. Playlist preserved." }
                                    finally { matchingProgress = null }
                                }
                            }) { Text("Auto match") }
                        }
                        if (page == "History" && library.history.isNotEmpty())
                            TextButton(onClick = { clearHistory = true }) { Text("Clear history") }
                    }
                    if (tracks.isNotEmpty()) Row(Modifier.padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { play(tracks, 0) }) { Text("Play all") }
                        OutlinedButton(onClick = { play(tracks.shuffled(), 0) }) { Text("Shuffle") }
                        OutlinedButton(onClick = { smartShuffle(tracks) }, enabled = !smartBusy) { Text("Smart shuffle") }
                        TextButton(onClick = { player.append(tracks.filter(::desktopPlayable)) }) { Text("Add to queue") }
                        TextButton(onClick = { player.append(tracks.filter(::desktopPlayable).shuffled()) }) { Text("Queue shuffled") }
                    }
                    if (smartBusy) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Finding recording-related songs…", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                        TextButton(onClick = { smartJob?.cancel() }) { Text("Cancel") }
                    }
                    if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                    matchingProgress?.let { progress -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(progress, color = Muted)
                        TextButton(onClick = { matchingJob?.cancel() }) { Text("Cancel") }
                    } }
                    recordingTarget?.let { target -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Choose Replace in a result's menu to match the imported song.", color = Accent)
                        TextButton(onClick = { recordingTarget = null }) { Text("Cancel matching") }
                    } }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text("#", Modifier.width(36.dp), color = Muted, fontSize = 11.sp)
                        Text("TITLE / ARTIST", Modifier.weight(1f), color = Muted, fontSize = 11.sp)
                        Text("TIME", Modifier.width(125.dp), color = Muted, fontSize = 11.sp)
                    }
                    HorizontalDivider(Modifier.padding(top = 12.dp), color = Color(0xFF28303C))
                    if (tracks.isEmpty() && !searching) {
                        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (page == "Search") "Find something you love." else "A little quiet here.", fontSize = 22.sp)
                            Text(if (page == "Search") "Search above for a song or an artist." else
                                "Search for music, open audio files, or import an Echo backup.",
                                color = Muted, modifier = Modifier.padding(top = 10.dp))
                        }
                    } else LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        itemsIndexed(tracks, key = { index, _ -> "$page:$index" }) { index, track ->
                            val event = if (page == "History") library.history.asReversed().getOrNull(index) else null
                            TrackRow(track, index, index == currentRow, track.id in library.favorites,
                                onPlay = { play(tracks, index, page == "Search") }, onLike = { disk { store.toggleFavorite(track) } },
                                onAdd = { addTrack = track }, onQueue = { player.append(listOf(track).filter(::desktopPlayable)) },
                                onNext = { player.append(listOf(track).filter(::desktopPlayable), next = true) },
                                onDownload = track.takeIf { it.provider == "youtube" }?.let { {
                                    try { offline.download(it) } catch (e: Exception) { notice = e.message ?: "Download could not start" }
                                } },
                                onFind = selectedPlaylist?.let { playlist -> {
                                    recordingTarget = Triple(playlist.id, index, playlist.trackIds.toList())
                                    query = "${track.artist} ${track.title}".take(200); search()
                                } },
                                onReplace = recordingTarget?.takeIf { page == "Search" && desktopPlayable(track) }?.let { target -> { scope.launch {
                                    try {
                                        library = withContext(Dispatchers.IO) {
                                            store.replacePlaylistRecording(target.first, target.second, target.third, track); store.snapshot()
                                        }
                                        recordingTarget = null; page = "playlist:${target.first}"
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { notice = e.message ?: "Recording could not be replaced" }
                                } } },
                                onMatchLocal = selectedPlaylist?.let { playlist -> { scope.launch {
                                    val file = chooseFiles(false).firstOrNull() ?: return@launch
                                    disk { store.replacePlaylistRecording(playlist.id, index, playlist.trackIds, localTrack(file)) }
                                } } },
                                onRemove = selectedPlaylist?.let { playlist -> { disk {
                                    store.removeFromPlaylist(playlist.id, index, playlist.trackIds)
                                } } },
                                onMoveUp = selectedPlaylist?.takeIf { index > 0 }?.let { playlist -> { disk {
                                    store.movePlaylistTrack(playlist.id, index, index - 1, playlist.trackIds)
                                } } },
                                onMoveDown = selectedPlaylist?.takeIf { index < tracks.lastIndex }?.let { playlist -> { disk {
                                    store.movePlaylistTrack(playlist.id, index, index + 1, playlist.trackIds)
                                } } }, detail = event?.let {
                                    val whenPlayed = java.time.Instant.ofEpochMilli(it.occurredAt).atZone(java.time.ZoneId.systemDefault())
                                        .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM, HH:mm"))
                                    "${it.event.lowercase().replaceFirstChar(Char::uppercase)} · $whenPlayed · ${clock(it.listenedMs)} listened"
                                })
                        }
                    }
                    }
                }
                if (playback.queue.isNotEmpty()) Column(Modifier.width(245.dp).fillMaxHeight()
                    .background(Color(0xFF10151D)).padding(20.dp)) {
                    Text("Now playing", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(18.dp))
                    Cover(playback.current, Modifier.fillMaxWidth().height(180.dp))
                    Text(playback.current?.title.orEmpty(), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 18.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(playback.current?.artist.orEmpty(), color = Muted, maxLines = 1)
                    Spacer(Modifier.height(26.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("UP NEXT", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { player.clearUpcoming() }) { Text("Clear", fontSize = 11.sp) }
                    }
                    LazyColumn(Modifier.weight(1f).padding(top = 12.dp)) {
                        itemsIndexed(upcomingIndexes, key = { _, absolute -> "${playback.queueRevision}:$absolute" }) { index, absolute ->
                            val track = playback.queue[absolute]
                            val revision = playback.queueRevision
                            val occurrence = playback.occurrence
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { scope.launch {
                                    player.jumpTo(absolute, revision)
                                } }.padding(vertical = 7.dp)) {
                                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                                    Text(if (track.id in recommendedIds) "Smart pick · ${track.artist}" else track.artist,
                                        color = if (track.id in recommendedIds) Accent else Muted, maxLines = 1, fontSize = 11.sp)
                                }
                                Column {
                                    TextButton(onClick = { scope.launch {
                                        player.moveUpcoming(absolute, upcomingIndexes[index - 1], occurrence, revision)
                                    } }, enabled = index > 0, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(28.dp)) { Text("↑") }
                                    TextButton(onClick = { scope.launch {
                                        player.removeUpcoming(absolute, occurrence, revision)
                                    } }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(28.dp)) { Text("×") }
                                }
                            }
                        }
                    }
                }
            }
            if (playback.error != null) Text(playback.error.orEmpty(), color = Color(0xFFFFB7AC), fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF38252A)).padding(12.dp))
            HorizontalDivider(color = Color(0xFF28303C))
            Row(Modifier.fillMaxWidth().height(108.dp).background(Panel).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Cover(playback.current, Modifier.size(58.dp))
                Column(Modifier.width(220.dp)) {
                    Text(playback.current?.title ?: "Ready when you are", fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (playback.loading) "Preparing audio…" else playback.current?.artist ?: "Choose a song to start",
                        color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { player.previous() }, enabled = playback.current != null) { Text("Previous") }
                        Button(onClick = { player.toggle() }, enabled = playback.current != null && !playback.loading) {
                            Text(if (playback.playing) "Pause" else "Play")
                        }
                        TextButton(onClick = { player.next() }, enabled = playback.current != null) { Text("Next") }
                        TextButton(onClick = { player.setRepeatMode(DesktopRepeatMode.entries[
                            (playback.repeatMode.ordinal + 1) % DesktopRepeatMode.entries.size]) }) {
                            Text("Repeat: ${playback.repeatMode.name.lowercase()}", fontSize = 11.sp)
                        }
                        Box {
                            TextButton(onClick = { timerMenu = true }) {
                                Text(playback.sleepRemainingMs?.let { "Sleep ${clock(it)}" } ?: "Sleep", fontSize = 11.sp)
                            }
                            DropdownMenu(expanded = timerMenu, onDismissRequest = { timerMenu = false }) {
                                listOf(15, 30, 45, 60).forEach { minutes -> DropdownMenuItem(text = { Text("$minutes minutes") },
                                    onClick = { player.setSleepTimer(minutes); timerMenu = false }) }
                                DropdownMenuItem(text = { Text("Cancel timer") },
                                    onClick = { player.setSleepTimer(null); timerMenu = false })
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(clock(playback.positionMs), fontSize = 10.sp, color = Muted)
                        Slider(value = playback.positionMs.toFloat().coerceIn(0f, playback.durationMs.coerceAtLeast(1).toFloat()),
                            onValueChange = { player.seek(it.toLong()) },
                            valueRange = 0f..playback.durationMs.coerceAtLeast(1).toFloat(),
                            enabled = playback.durationMs > 0, modifier = Modifier.weight(1f).height(26.dp).padding(horizontal = 12.dp))
                        Text(clock(playback.durationMs), fontSize = 10.sp, color = Muted)
                    }
                }
                Column(Modifier.width(150.dp)) {
                    Text("Volume", fontSize = 11.sp, color = Muted)
                    Slider(playback.volume, { player.setVolume(it) })
                }
            }
        }
    }
    if (showImport) DesktopImportDialog(store, onChanged = { disk { } }, onError = { notice = it }, onDismiss = { showImport = false })
    if (showUpdates) DesktopUpdatesDialog(updater, scope, onDismiss = { showUpdates = false })
    notice?.let { message -> AlertDialog(onDismissRequest = { notice = null }, title = { Text("Echo") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } }) }
    if (newPlaylist) AlertDialog(onDismissRequest = { newPlaylist = false }, title = { Text("New playlist") },
        text = { OutlinedTextField(playlistName, { playlistName = it.take(200) }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(enabled = playlistName.isNotBlank(), onClick = {
            val name = playlistName
            disk { store.createPlaylist(name) }; playlistName = ""; newPlaylist = false
        }) { Text("Create") } }, dismissButton = { TextButton(onClick = { newPlaylist = false }) { Text("Cancel") } })
    renamePlaylist?.let { playlist -> AlertDialog(onDismissRequest = { renamePlaylist = null }, title = { Text("Rename playlist") },
        text = { OutlinedTextField(playlistName, { playlistName = it.take(200) }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(enabled = playlistName.isNotBlank(), onClick = {
            val name = playlistName; disk { store.renamePlaylist(playlist.id, name) }; renamePlaylist = null
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { renamePlaylist = null }) { Text("Cancel") } }) }
    deletePlaylist?.let { playlist -> AlertDialog(onDismissRequest = { deletePlaylist = null }, title = { Text("Delete ${playlist.name}?") },
        text = { Text("This removes the playlist from this laptop. Songs, favorites and listening history stay in your library.") },
        confirmButton = { TextButton(onClick = { disk { store.deletePlaylist(playlist.id) }; page = "Home"; deletePlaylist = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deletePlaylist = null }) { Text("Cancel") } }) }
    if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text("Clear listening history?") },
        text = { Text("This clears listening history and its preference evidence. If you use Firebase sync, clearing is shared with your account at the next sync. Songs, playlists and favorites stay.") },
        confirmButton = { TextButton(onClick = { disk { store.clearHistory() }; clearHistory = false }) { Text("Clear history") } },
        dismissButton = { TextButton(onClick = { clearHistory = false }) { Text("Cancel") } })
    addTrack?.let { track -> AlertDialog(onDismissRequest = { addTrack = null }, title = { Text("Add to playlist") },
        text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
            if (library.playlists.isEmpty()) Text("Create a playlist using + in the sidebar first.")
            library.playlists.forEach { playlist -> TextButton(onClick = {
                disk { store.addToPlaylist(playlist.id, track) }; addTrack = null
            }) { Text(playlist.name) } }
        } }, confirmButton = { TextButton(onClick = { addTrack = null }) { Text("Done") } }) }
    if (showDevices) AlertDialog(onDismissRequest = { showDevices = false }, title = { Text("Devices & account") },
        text = { Column(Modifier.widthIn(min = 400.dp, max = 560.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Text(account.email ?: "Listen locally, or connect your Google account.", fontWeight = FontWeight.SemiBold)
            account.message?.let { Text(it, color = Muted, modifier = Modifier.padding(top = 12.dp)) }
            if (account.busy || cloudSync.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                Text(if (account.busy) "Finish sign-in in your browser. Echo does not handle your password." else "Syncing your Firebase library…", color = Muted)
                TextButton(onClick = {
                    accountJob?.cancel()
                    if (cloudSync.busy) {
                        automaticSync = false
                        scope.launch { try { firebase.setAutomatic(false) } catch (_: Exception) { notice = "Could not save the automatic sync preference." } }
                    }
                }) { Text("Cancel") }
            } else {
                if (account.email == null) Button(onClick = { accountAction { google.signIn() } }, enabled = account.clientConfigured,
                    modifier = Modifier.padding(top = 16.dp)) {
                    Text("Sign in with Google")
                } else {
                    Button(onClick = { confirmCloudSync = true }, enabled = dev.echo.data.firebase.EchoFirebaseProject.configured) { Text("Connect Firebase / Sync now") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Automatic library sync", modifier = Modifier.weight(1f))
                        Switch(checked = automaticSync, onCheckedChange = { enabled ->
                            if (enabled) confirmCloudSync = true else accountAction { firebase.setAutomatic(false); automaticSync = false }
                        })
                    }
                    TextButton(onClick = { accountAction { google.signOut() } }) { Text("Sign out") }
                }
                TextButton(onClick = { accountAction {
                    chooseFiles(true, "Choose Echo Desktop OAuth client JSON").firstOrNull()?.let { google.importClientJson(it) }
                } }) { Text("Import Desktop OAuth configuration") }
                if (!account.clientConfigured) Text("Import your downloaded Desktop OAuth JSON first. Echo encrypts it for this Windows user.",
                    color = Muted, fontSize = 12.sp)
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Firebase library", fontWeight = FontWeight.SemiBold)
            Text("Shares playlists, favorites and listening history after your confirmation. Audio, credentials and local file paths stay on their original device. Existing Drive snapshots are kept.",
                color = Muted, modifier = Modifier.padding(top = 8.dp))
            if (!dev.echo.data.firebase.EchoFirebaseProject.configured) Text("Firebase project setup is pending.", color = Muted)
            if (cloudSync.accountEmail == account.email) {
                cloudSync.message?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                cloudSync.lastSyncedAt?.let { time -> Text("Last synced " + java.time.Instant.ofEpochMilli(time)
                    .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM, HH:mm")), color = Muted) }
            }
            Spacer(Modifier.height(16.dp))
            Text("Connected playback", fontWeight = FontWeight.SemiBold)
            Text("Phone playback status, shared controls, synchronized listening and multiple audio outputs are still being built. Audio files stay on their original device.",
                color = Muted, modifier = Modifier.padding(top = 8.dp))
        } },
        confirmButton = { TextButton(onClick = { showDevices = false }) { Text("Got it") } })
    if (confirmCloudSync) AlertDialog(onDismissRequest = { confirmCloudSync = false }, title = { Text("Sync library with ${account.email}?") },
        text = { Text("This uploads this laptop's playlists, favorites and up to 90 days of listening history to Firebase. Your other devices' changes merge here, including unlikes, playlist deletions and history clearing. This device's library stays after signing out, so check the account before uploading. No audio or file paths are uploaded.") },
        confirmButton = { TextButton(onClick = { confirmCloudSync = false; accountAction {
            firebase.setAutomatic(true); automaticSync = true
        } }) { Text("Enable automatic sync") } },
        dismissButton = { Row {
            TextButton(onClick = { confirmCloudSync = false; accountAction {
                firebase.synchronize(); library = withContext(Dispatchers.IO) { store.snapshot() }
            } }) { Text("Sync once") }
            TextButton(onClick = { confirmCloudSync = false }) { Text("Cancel") }
        } })
}

@Composable
private fun NavItem(label: String, selected: Boolean, action: () -> Unit) {
    Text(label, color = if (selected) Accent else Muted, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().background(if (selected) Color(0xFF1A2D2D) else Color.Transparent,
            RoundedCornerShape(8.dp)).clickable(onClick = action).padding(horizontal = 13.dp, vertical = 13.dp),
        maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
}

@Composable
private fun TrackRow(track: Track, index: Int, current: Boolean, liked: Boolean,
                     onPlay: () -> Unit, onLike: () -> Unit, onAdd: () -> Unit, onQueue: () -> Unit,
                     onRemove: (() -> Unit)? = null, onMoveUp: (() -> Unit)? = null, onMoveDown: (() -> Unit)? = null,
                     onDownload: (() -> Unit)? = null, onFind: (() -> Unit)? = null, onReplace: (() -> Unit)? = null,
                     onMatchLocal: (() -> Unit)? = null,
                     onNext: (() -> Unit)? = null,
                     detail: String? = null) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(if (current) Color(0xFF19312E) else Color.Transparent,
        RoundedCornerShape(8.dp)).clickable(onClick = onPlay).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", Modifier.width(36.dp), color = if (current) Accent else Muted, fontSize = 12.sp)
        Cover(track, Modifier.size(42.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(track.title, color = if (current) Accent else Color(0xFFEAF0F7), maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(detail ?: (track.artist + if (desktopPlayable(track)) "" else " · Find recording"), color = Muted,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(clock(track.durationMs), color = Muted, fontSize = 12.sp, modifier = Modifier.width(45.dp))
        TextButton(onClick = onLike, modifier = Modifier.width(45.dp)) { Text(if (liked) "♥" else "♡", color = if (liked) Accent else Muted) }
        Box {
            TextButton(onClick = { menu = true }, modifier = Modifier.width(40.dp)) { Text("⋯") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { menu = false; onAdd() })
                DropdownMenuItem(text = { Text("Add to queue") }, enabled = desktopPlayable(track), onClick = { menu = false; onQueue() })
                onNext?.let { action -> DropdownMenuItem(text = { Text("Play next") }, enabled = desktopPlayable(track), onClick = { menu = false; action() }) }
                onDownload?.let { action -> DropdownMenuItem(text = { Text("Download for offline") }, onClick = { menu = false; action() }) }
                onFind?.let { action -> DropdownMenuItem(text = { Text("Find recording") }, onClick = { menu = false; action() }) }
                onReplace?.let { action -> DropdownMenuItem(text = { Text("Replace imported recording") }, onClick = { menu = false; action() }) }
                onMatchLocal?.let { action -> DropdownMenuItem(text = { Text("Use local recording") }, onClick = { menu = false; action() }) }
                onMoveUp?.let { action -> DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; action() }) }
                onMoveDown?.let { action -> DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; action() }) }
                onRemove?.let { action -> DropdownMenuItem(text = { Text("Remove from playlist") }, onClick = { menu = false; action() }) }
            }
        }
    }
}

@Composable
private fun Cover(track: Track?, modifier: Modifier) {
    val artwork by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, track?.artworkUri) {
        value = loadDesktopArtwork(track?.artworkUri)
    }
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF25594F), Color(0xFF283B5E))), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) {
        artwork?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            ?: Text(track?.title?.take(1)?.uppercase() ?: "e", color = Accent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

private fun desktopPlayable(track: Track): Boolean =
    track.provider in setOf("youtube", "local") && !track.sourceUri.isNullOrBlank()

private fun clock(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)

private suspend fun chooseFiles(backup: Boolean, title: String? = null): List<File> = suspendCancellableCoroutine { continuation ->
    SwingUtilities.invokeLater {
        val picker = JFileChooser().apply {
            dialogTitle = title ?: if (backup) "Import Echo library backup" else "Open audio files"
            isMultiSelectionEnabled = !backup
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                if (backup) "Echo JSON backup" else "Audio files",
                *(if (backup) arrayOf("json") else arrayOf("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac")))
        }
        val files = if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
            if (backup) listOf(picker.selectedFile) else picker.selectedFiles.toList() else emptyList()
        if (continuation.isActive) continuation.resume(files)
    }
}

private suspend fun chooseBackupDestination(): File? = suspendCancellableCoroutine { continuation ->
    SwingUtilities.invokeLater {
        val picker = JFileChooser().apply { dialogTitle = "Export Echo library"; selectedFile = File("Echo-library.json") }
        var file = if (picker.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) picker.selectedFile else null
        if (file?.exists() == true && javax.swing.JOptionPane.showConfirmDialog(null, "Replace ${file.name}?", "Export library",
                javax.swing.JOptionPane.YES_NO_OPTION) != javax.swing.JOptionPane.YES_OPTION) file = null
        if (continuation.isActive) continuation.resume(file)
    }
}

internal fun writeDesktopBackup(file: File, json: String) {
    val target = file.toPath().toAbsolutePath()
    val temporary = java.nio.file.Files.createTempFile(target.parent, "echo-export-", ".tmp")
    try {
        java.nio.file.Files.writeString(temporary, json)
        try { java.nio.file.Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    } finally { java.nio.file.Files.deleteIfExists(temporary) }
}
