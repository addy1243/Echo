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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedPlaybackAction
import dev.echo.core.connected.MAX_CONNECTED_GROUP_MEMBERS
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
    val player = remember { store?.let { DesktopPlayback(provider, offline,
        recoveryFile = it.file.resolveSibling("playback-v2-recovery.json").toFile(),
        findLocalTrack = { key -> it.snapshot().tracks.firstOrNull { track -> safeEngineRecordingId(track) == key } }) { track, event, listened ->
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
    val connectedPlayback = remember { DesktopConnectedPlayback(store, google, player) }
    val connectedState by connectedPlayback.state.collectAsState()
    val connectedV2 by connectedPlayback.v2State.collectAsState()
    var connectedPlaybackEnabled by remember { mutableStateOf(connectedPlayback.enabled()) }
    var selectedRemoteId by remember { mutableStateOf<String?>(null) }
    var lastSelectedRemote by remember { mutableStateOf<ConnectedDevice?>(null) }
    var lastConnectedAccount by remember { mutableStateOf(account.email) }
    LaunchedEffect(account.email) {
        if (lastConnectedAccount != account.email) {
            lastConnectedAccount = account.email
            connectedPlaybackEnabled = false
            connectedPlayback.stopGracefully()
            connectedPlayback.setEnabled(false)
        }
    }
    LaunchedEffect(account.email, connectedPlaybackEnabled, lastConnectedAccount) {
        if (account.email == null || account.email != lastConnectedAccount || !connectedPlaybackEnabled ||
            !dev.echo.data.firebase.EchoFirebaseProject.configured) {
            connectedPlayback.stopGracefully()
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) { connectedPlayback.run() }
    }
    LaunchedEffect(account.email, connectedPlaybackEnabled) {
        if (account.email == null || !connectedPlaybackEnabled) {
            selectedRemoteId = null
            lastSelectedRemote = null
        }
    }
    LaunchedEffect(selectedRemoteId, connectedState.devices) {
        selectedRemoteId?.let { id -> connectedState.devices.firstOrNull { it.id == id }?.let { lastSelectedRemote = it } }
    }
    DisposableEffect(connectedPlayback) { onDispose { connectedPlayback.stop() } }
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
    val selectedRemote = selectedRemotePlayer(selectedRemoteId, connectedState.devices, lastSelectedRemote)
    val remotePlayer = selectedRemote?.device
    val remoteLive = selectedRemote != null && !selectedRemote.reconnecting && remotePlayer?.controlEnabled == true &&
        remotePlayer?.player?.title?.isNullOrBlank() == false &&
        connectedPlaybackEnabled && account.email != null && !connectedState.pending
    val presentedTrack = if (selectedRemoteId != null) remotePlayer?.let { connectedPresentationTrack(it.player, trackMap) } else playback.current
    val presentedTitle = if (selectedRemoteId != null) remotePlayer?.player?.title else playback.current?.title
    val presentedArtist = if (selectedRemoteId != null) remotePlayer?.player?.artist else playback.current?.artist
    val presentedPlaying = if (selectedRemoteId != null) remotePlayer?.player?.isPlaying == true else playback.playing
    val presentedPosition = if (selectedRemoteId != null) remotePlayer?.player?.positionMs ?: 0L else playback.positionMs
    val presentedDuration = if (selectedRemoteId != null) remotePlayer?.player?.durationMs ?: 0L else playback.durationMs
    val ownDeviceId = connectedPlayback.deviceId
    val otherPlayers = connectedState.devices.filter { it.id != ownDeviceId }.let { devices ->
        if (lastSelectedRemote != null && devices.none { it.id == lastSelectedRemote!!.id })
            devices + lastSelectedRemote!!.copy(controlEnabled = false) else devices
    }
    val onlinePlayers = otherPlayers.filter { it.controlEnabled && it.id != selectedRemoteId }
    val offlinePlayersBase = otherPlayers.filterNot { it.controlEnabled || it.id == selectedRemoteId }
    val offlinePlayers = offlinePlayersBase + listOfNotNull(selectedRemote?.takeIf { it.reconnecting }?.device?.takeIf {
        offlinePlayersBase.none { offline -> offline.id == it.id }
    })
    fun sendRemoteCommand(target: ConnectedDevice, action: ConnectedPlaybackAction, position: Long = 0L) {
        val fresh = connectedState.devices.firstOrNull { it.id == target.id && it.controlEnabled }
        if (!remoteLive || selectedRemoteId != target.id || fresh == null || fresh.sessionId != target.sessionId ||
            fresh.player.trackRevision != target.player.trackRevision || fresh.player.queueRevision != target.player.queueRevision) return
        scope.launch {
            try { connectedPlayback.sendCommand(fresh, action, position) }
            catch (_: Exception) { notice = "The remote command could not be sent. Check the device and account connection." }
        }
    }
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
    val currentRow = if (selectedRemoteId == null && tracks.map { it.id } == playback.queue.map { it.id }) playback.index else -1
    fun play(items: List<Track>, index: Int, selectedOnly: Boolean = false) {
        selectedRemoteId = null
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
        selectedRemoteId = null
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
                        TextButton(onClick = { selectedRemoteId = null; player.append(tracks.filter(::desktopPlayable)) }) { Text("Add to queue") }
                        TextButton(onClick = { selectedRemoteId = null; player.append(tracks.filter(::desktopPlayable).shuffled()) }) { Text("Queue shuffled") }
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
                                onAdd = { addTrack = track }, onQueue = { selectedRemoteId = null; player.append(listOf(track).filter(::desktopPlayable)) },
                                onNext = { selectedRemoteId = null; player.append(listOf(track).filter(::desktopPlayable), next = true) },
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
            }
            if (selectedRemoteId == null && playback.error != null) Text(playback.error.orEmpty(), color = Color(0xFFFFB7AC), fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF38252A)).padding(12.dp))
            HorizontalDivider(color = Color(0xFF28303C))
            DesktopPlayerBar(presentedTrack, presentedTitle, presentedArtist, presentedPlaying, presentedPosition, presentedDuration,
                remoteMode = selectedRemoteId != null, remoteDevice = remotePlayer,
                remoteEnabled = remoteLive, commandPending = connectedState.pending,
                localAvailable = playback.current != null, localLoading = playback.loading,
                localVolume = playback.volume, localRepeat = playback.repeatMode.name.lowercase(), localSleep = playback.sleepRemainingMs,
                onLocalPrevious = { selectedRemoteId = null; if (connectedV2.group != null) scope.launch { runCatching { connectedPlayback.requestGroup(dev.echo.data.firebase.GroupActionV2.PREVIOUS) } } else player.previous() },
                onLocalToggle = { selectedRemoteId = null; if (connectedV2.group != null) scope.launch { runCatching { connectedPlayback.requestGroup(if (connectedV2.group?.timeline?.playing == true) dev.echo.data.firebase.GroupActionV2.PAUSE else dev.echo.data.firebase.GroupActionV2.PLAY) } } else player.toggle() },
                onLocalNext = { selectedRemoteId = null; if (connectedV2.group != null) scope.launch { runCatching { connectedPlayback.requestGroup(dev.echo.data.firebase.GroupActionV2.NEXT) } } else player.next() },
                onLocalSeek = { if (connectedV2.group != null) scope.launch { runCatching { connectedPlayback.requestGroup(dev.echo.data.firebase.GroupActionV2.SEEK, it) } } else player.seek(it) }, onLocalVolume = { player.setVolume(it) },
                onLocalRepeat = { if (connectedV2.group == null) player.setRepeatMode(DesktopRepeatMode.entries[(playback.repeatMode.ordinal + 1) % DesktopRepeatMode.entries.size]) },
                onLocalShuffle = { if (connectedV2.group == null) { selectedRemoteId = null; player.shuffleUpcoming() } },
                onLocalSleep = { if (connectedV2.group == null) player.setSleepTimer(it) }, onRemoteCommand = ::sendRemoteCommand,
                connected = connectedPlaybackEnabled && account.email != null,
                connectedMessage = connectedState.message, otherPlayers = otherPlayers,
                connectedV2 = connectedV2,
                ownDeviceId = ownDeviceId,
                onMoveV2 = { id -> scope.launch { runCatching { connectedPlayback.moveTo(id, true) }.onFailure { notice = it.message } } },
                onTogetherV2 = { ids, sessions, replace -> scope.launch { runCatching {
                    connectedPlayback.startTogether(ids, replacePlaying = replace, expectedTargetSessions = sessions)
                }.onFailure { notice = it.message } } },
                onRestorePrevious = { scope.launch { runCatching { connectedPlayback.restoreLatestDisplacedPaused() }.onFailure { notice = it.message } } },
                onGroupAction = { action -> scope.launch { runCatching { connectedPlayback.requestGroup(action) }.onFailure { notice = it.message } } },
                onlinePlayers = onlinePlayers, offlinePlayers = offlinePlayers,
                localStatus = when {
                    playback.current == null -> "Ready"
                    playback.ended -> "Ready"
                    playback.playing -> "Playing · ${playback.current?.title.orEmpty()}"
                    else -> "Paused · ${playback.current?.title.orEmpty()}"
                },
                selectedRemoteId = selectedRemoteId, reconnecting = selectedRemote?.reconnecting == true,
                onSelectLocal = { selectedRemoteId = null }, onSelectRemote = { device ->
                    selectedRemoteId = device.id; lastSelectedRemote = device
                },
                upcomingIndexes = upcomingIndexes, playback = playback, recommendedIds = recommendedIds,
                onClearQueue = { selectedRemoteId = null; player.clearUpcoming() },
                onJump = { index, revision -> selectedRemoteId = null; scope.launch { player.jumpTo(index, revision) } },
                onMove = { from, to, occurrence, revision -> selectedRemoteId = null; scope.launch { player.moveUpcoming(from, to, occurrence, revision) } },
                onRemove = { index, occurrence, revision -> selectedRemoteId = null; scope.launch { player.removeUpcoming(index, occurrence, revision) } })
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
            Text("When enabled, this open Echo desktop app shares the current song and accepts remote controls. Audio stays on its original device; this is not synchronized playback.",
                color = Muted, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Allow remote controls on this laptop", modifier = Modifier.weight(1f))
                Switch(checked = connectedPlaybackEnabled, enabled = account.email != null &&
                    dev.echo.data.firebase.EchoFirebaseProject.configured, onCheckedChange = { enabled ->
                    if (enabled) {
                        connectedPlayback.setEnabled(true)
                        connectedPlaybackEnabled = true
                    } else scope.launch {
                        connectedPlayback.stopGracefully()
                        connectedPlayback.setEnabled(false)
                        connectedPlaybackEnabled = false
                    }
                })
            }
            if (account.email == null) Text("Sign in first. Connected playback uses the signed-in Firebase account.", color = Muted, fontSize = 12.sp)
            Text("Choose connected players from the bottom player. Remote controls only work while a player is reachable.",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
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
private fun DesktopPlayerBar(
    track: Track?, title: String?, artist: String?, playing: Boolean, positionMs: Long, durationMs: Long,
    remoteMode: Boolean, remoteDevice: ConnectedDevice?, remoteEnabled: Boolean, commandPending: Boolean,
    localAvailable: Boolean, localLoading: Boolean, localVolume: Float, localRepeat: String, localSleep: Long?,
    onLocalPrevious: () -> Unit, onLocalToggle: () -> Unit, onLocalNext: () -> Unit, onLocalSeek: (Long) -> Unit,
    onLocalVolume: (Float) -> Unit, onLocalRepeat: () -> Unit, onLocalShuffle: () -> Unit, onLocalSleep: (Int?) -> Unit,
    onRemoteCommand: (ConnectedDevice, ConnectedPlaybackAction, Long) -> Unit,
    connected: Boolean, connectedMessage: String?, otherPlayers: List<ConnectedDevice>,
    connectedV2: dev.echo.data.firebase.ConnectedPlaybackV2State,
    ownDeviceId: String,
    onMoveV2: (String) -> Unit, onTogetherV2: (List<String>, Map<String, String>, Boolean) -> Unit, onRestorePrevious: () -> Unit,
    onGroupAction: (dev.echo.data.firebase.GroupActionV2) -> Unit,
    onlinePlayers: List<ConnectedDevice>,
    offlinePlayers: List<ConnectedDevice>, localStatus: String, selectedRemoteId: String?, reconnecting: Boolean,
    onSelectLocal: () -> Unit, onSelectRemote: (ConnectedDevice) -> Unit,
    upcomingIndexes: List<Int>, playback: DesktopPlaybackState, recommendedIds: Set<String>,
    onClearQueue: () -> Unit, onJump: (Int, Long) -> Unit,
    onMove: (Int, Int, Long, Long) -> Unit, onRemove: (Int, Long, Long) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(Panel)) {
        val narrow = maxWidth < 1180.dp
        val shortName = remoteDevice?.let { shortDeviceName(it.label) } ?: if (remoteMode) "Device" else "Laptop"
        val stateName = when {
            remoteMode && reconnecting -> "Reconnecting to ${remoteDevice?.label ?: lastKnownDeviceName(otherPlayers, selectedRemoteId)}"
            remoteMode && commandPending -> "Command pending on ${remoteDevice?.label ?: "device"}"
            remoteMode && remoteDevice?.player?.title != null -> "${if (playing) "Playing on" else "Paused on"} ${remoteDevice.label}"
            remoteMode -> "Controlling ${remoteDevice?.label ?: "device"}"
            playing -> "Playing on this laptop"
            localAvailable -> "Paused on this laptop"
            else -> "This laptop"
        }
        val deviceLabel = when {
            remoteMode && reconnecting -> "Reconnecting · $shortName"
            remoteMode && commandPending -> "Pending · $shortName"
            remoteMode && remoteDevice?.player?.title != null -> "${if (playing) "Playing" else "Paused"} · $shortName"
            remoteMode -> "Controlling · $shortName"
            else -> "⌘ Laptop"
        }
        val pick: @Composable () -> Unit = {
            DesktopPlayerPicker(deviceLabel, stateName, remoteMode, selectedRemoteId, connected,
                connectedMessage, otherPlayers, connectedV2, ownDeviceId, playback.displacedMoveOperationId,
                onMoveV2, onTogetherV2, onRestorePrevious, onGroupAction,
                onlinePlayers, offlinePlayers, localVolume, localRepeat, localSleep,
                localStatus, commandPending, remoteDevice, onSelectLocal, onSelectRemote,
                onLocalVolume, onLocalRepeat, onLocalShuffle, onLocalSleep)
        }
        val transport: @Composable (Modifier) -> Unit = { modifier ->
            PlayerTransport(modifier, remoteMode, remoteDevice, remoteEnabled, playing,
                positionMs, durationMs, localAvailable, localLoading, onLocalPrevious, onLocalToggle, onLocalNext,
                onLocalSeek, onRemoteCommand)
        }
        if (narrow) Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Cover(track, Modifier.size(54.dp))
                Column(Modifier.weight(1f)) {
                    Text(title ?: if (remoteMode) "Ready to play" else "Ready when you are", fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(artist ?: if (remoteMode) "" else "Choose a song to start", color = Muted, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                transport(Modifier.weight(1f))
                pick()
                if (!remoteMode && connectedV2.group == null && playback.current != null) QueuePicker(playback, upcomingIndexes, recommendedIds,
                    onClearQueue, onJump, onMove, onRemove)
            }
        } else Row(Modifier.fillMaxWidth().height(108.dp).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Cover(track, Modifier.size(58.dp))
            Column(Modifier.widthIn(min = 130.dp, max = 230.dp)) {
                Text(title ?: if (remoteMode) "Ready to play" else "Ready when you are", fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(artist ?: if (remoteMode) "" else "Choose a song to start", color = Muted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            transport(Modifier.weight(1f))
            if (!remoteMode) {
                Column(Modifier.width(118.dp)) {
                    Text("Volume", fontSize = 11.sp, color = Muted)
                    Slider(localVolume, onLocalVolume)
                }
                if (connectedV2.group == null && playback.current != null) QueuePicker(playback, upcomingIndexes, recommendedIds,
                    onClearQueue, onJump, onMove, onRemove)
            }
            pick()
        }
    }
}

@Composable
private fun PlayerTransport(
    modifier: Modifier, remoteMode: Boolean, remoteDevice: ConnectedDevice?, remoteEnabled: Boolean,
    playing: Boolean, positionMs: Long, durationMs: Long, localAvailable: Boolean, localLoading: Boolean,
    onLocalPrevious: () -> Unit, onLocalToggle: () -> Unit, onLocalNext: () -> Unit, onLocalSeek: (Long) -> Unit,
    onRemoteCommand: (ConnectedDevice, ConnectedPlaybackAction, Long) -> Unit,
) {
    var dragValue by remember(remoteMode, remoteEnabled, remoteDevice?.id, remoteDevice?.sessionId,
        remoteDevice?.player?.trackRevision, remoteDevice?.player?.queueRevision) { mutableStateOf<Float?>(null) }
    var dragTarget by remember(remoteMode, remoteEnabled, remoteDevice?.id, remoteDevice?.sessionId,
        remoteDevice?.player?.trackRevision, remoteDevice?.player?.queueRevision) { mutableStateOf<ConnectedDevice?>(null) }
    val canTransport = if (remoteMode) remoteEnabled else localAvailable
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = {
                if (remoteMode) remoteDevice?.let { onRemoteCommand(it, ConnectedPlaybackAction.PREVIOUS, 0) }
                else onLocalPrevious()
            }, enabled = canTransport) { Text("Previous") }
            Button(onClick = {
                if (remoteMode) remoteDevice?.let { onRemoteCommand(it,
                    if (playing) ConnectedPlaybackAction.PAUSE else ConnectedPlaybackAction.PLAY, 0) }
                else onLocalToggle()
            }, enabled = canTransport && (remoteMode || !localLoading)) { Text(if (playing) "Pause" else "Play") }
            TextButton(onClick = {
                if (remoteMode) remoteDevice?.let { onRemoteCommand(it, ConnectedPlaybackAction.NEXT, 0) }
                else onLocalNext()
            }, enabled = canTransport) { Text("Next") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock(positionMs), fontSize = 10.sp, color = Muted)
            Slider(value = (dragValue ?: positionMs.toFloat()).coerceIn(0f, durationMs.coerceAtLeast(1).toFloat()),
                onValueChange = { value ->
                    if (remoteMode) {
                        if (dragTarget == null) dragTarget = remoteDevice
                        dragValue = value
                    } else onLocalSeek(value.toLong())
                }, onValueChangeFinished = {
                    if (remoteMode) {
                        val target = dragTarget
                        val value = dragValue
                        if (target != null && value != null) onRemoteCommand(target, ConnectedPlaybackAction.SEEK, value.toLong())
                        dragTarget = null; dragValue = null
                    }
                }, valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(), enabled = durationMs > 0 && canTransport,
                modifier = Modifier.weight(1f).height(26.dp).padding(horizontal = 10.dp))
            Text(clock(durationMs), fontSize = 10.sp, color = Muted)
        }
    }
}

@Composable
private fun DesktopPlayerPicker(
    buttonLabel: String, accessibleLabel: String, remoteMode: Boolean, selectedRemoteId: String?,
    connected: Boolean, connectedMessage: String?, devices: List<ConnectedDevice>,
    connectedV2: dev.echo.data.firebase.ConnectedPlaybackV2State,
    ownDeviceId: String,
    displacedMoveOperationId: String?,
    onMoveV2: (String) -> Unit, onTogetherV2: (List<String>, Map<String, String>, Boolean) -> Unit, onRestorePrevious: () -> Unit,
    onGroupAction: (dev.echo.data.firebase.GroupActionV2) -> Unit,
    online: List<ConnectedDevice>,
    offline: List<ConnectedDevice>, volume: Float, repeat: String, sleep: Long?, localStatus: String,
    commandPending: Boolean, selectedDevice: ConnectedDevice?, onSelectLocal: () -> Unit,
    onSelectRemote: (ConnectedDevice) -> Unit, onVolume: (Float) -> Unit, onRepeat: () -> Unit,
    onShuffle: () -> Unit, onSleep: (Int?) -> Unit,
) {
    val group = connectedV2.group
    var expanded by remember { mutableStateOf(false) }
    var otherOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    var togetherOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var confirmRestorePrevious by remember { mutableStateOf(false) }
    var togetherDeviceSessions by remember { mutableStateOf(emptyMap<String, String>()) }
    var confirmTogether by remember { mutableStateOf(false) }
    val togetherCandidates = connectedV2.devices.filter { it.id != ownDeviceId && "group" in it.capabilities }
    val togetherDeviceIds = togetherDeviceSessions.keys
    val selectedTogetherDevices = togetherDeviceSessions.mapNotNull { (id, sessionId) ->
        togetherCandidates.firstOrNull { it.id == id && it.sessionId == sessionId }
    }
    val invalidTogetherSelection = togetherDeviceSessions.any { (id, sessionId) ->
        togetherCandidates.none { it.id == id && it.sessionId == sessionId && it.enabled }
    }
    val readyToStartTogether = togetherDeviceIds.size in 1 until MAX_CONNECTED_GROUP_MEMBERS &&
        !invalidTogetherSelection && selectedTogetherDevices.size == togetherDeviceIds.size
    LaunchedEffect(connectedV2.group?.ref, connectedV2.group?.status) {
        if (connectedV2.group?.status == dev.echo.data.firebase.GroupV2Status.ACTIVE) {
            togetherDeviceSessions = emptyMap()
            confirmTogether = false
        }
    }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = accessibleLabel }) {
            Text(buttonLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            Column(Modifier.width(350.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Text("Your players", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                devices.firstOrNull { it.id == selectedRemoteId && it.controlEnabled }?.let { device ->
                        DropdownMenuItem(text = {
                            Column {
                                Text("${if (device.label.contains("android", true)) "▣" else "▱"}  ${device.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (!device.controlEnabled) "Offline" else if (device.player.title == null) "Ready to play" else
                                    "${if (device.player.isPlaying) "Playing" else "Paused"} · ${device.player.title}",
                                    color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }, onClick = { onSelectRemote(device); expanded = false })
                    }
                DropdownMenuItem(text = {
                    Column {
                        Text("▱  This laptop")
                        Text(localStatus, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }, onClick = { onSelectLocal(); expanded = false })
                online.filterNot { it.id == selectedRemoteId }.forEach { device ->
                    DropdownMenuItem(text = {
                        Column {
                            Text("${if (device.label.contains("android", true)) "▣" else "▱"}  ${device.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (device.player.title == null) "Ready to play" else
                                "${if (device.player.isPlaying) "Playing" else "Paused"} · ${device.player.title}",
                                color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }, onClick = { onSelectRemote(device); expanded = false })
                }
                if (offline.isNotEmpty()) {
                    TextButton(onClick = { otherOpen = !otherOpen }, modifier = Modifier.fillMaxWidth()) {
                        Text("${if (otherOpen) "▾" else "▸"} Other devices (${offline.size})", modifier = Modifier.fillMaxWidth())
                    }
                    if (otherOpen) offline.forEach { device -> DropdownMenuItem(text = {
                        Column { Text(device.label, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("Offline · last known session", color = Muted, fontSize = 11.sp) }
                    }, onClick = { onSelectRemote(device); expanded = false }) }
                }
                if (remoteMode) {
                    connectedMessage?.let { Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                    if (commandPending) Text("Remote command pending…", color = Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    selectedDevice?.ack?.takeIf { it.status == "rejected" }?.let {
                        Text("Command declined: ${it.reason ?: "device state changed"}", color = Color(0xFFFFB7AC), fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                } else if (!connected) Text(connectedMessage ?: "Sign in and enable connected playback to see other live players.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                if (!remoteMode) {
                    Text("Volume", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    Slider(volume, onVolume, modifier = Modifier.padding(horizontal = 12.dp))
                    if (group == null) {
                        TextButton(onClick = onRepeat, modifier = Modifier.fillMaxWidth()) { Text("Repeat: $repeat", modifier = Modifier.fillMaxWidth()) }
                        TextButton(onClick = onShuffle, modifier = Modifier.fillMaxWidth()) { Text("Shuffle upcoming", modifier = Modifier.fillMaxWidth()) }
                        Box {
                            TextButton(onClick = { sleepOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(sleep?.let { "Sleep timer · ${clock(it)}" } ?: "Sleep timer", modifier = Modifier.fillMaxWidth())
                            }
                            DropdownMenu(sleepOpen, onDismissRequest = { sleepOpen = false }) {
                                listOf(15, 30, 45, 60).forEach { minute -> DropdownMenuItem(text = { Text("$minute minutes") },
                                    onClick = { onSleep(minute); sleepOpen = false }) }
                                DropdownMenuItem(text = { Text("Cancel timer") }, onClick = { onSleep(null); sleepOpen = false })
                            }
                        }
                    }
                }
                TextButton(onClick = { moveOpen = !moveOpen }, modifier = Modifier.fillMaxWidth()) { Text("Move this song…", modifier = Modifier.fillMaxWidth()) }
                if (moveOpen) {
                    if (displacedMoveOperationId != null && !remoteMode && group == null) {
                        if (connectedV2.reconcilingMove) {
                            Text("A Move is still being reconciled. Wait for its outcome before restoring the previous queue.",
                                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        } else if (!confirmRestorePrevious) {
                            TextButton(enabled = !connectedV2.pending, onClick = { confirmRestorePrevious = true },
                                modifier = Modifier.fillMaxWidth().testTag("connected-restore-previous")) {
                                Text("Restore previous queue paused")
                            }
                        } else {
                            Text("This replaces the current queue and restores the previous one paused.", color = Muted,
                                fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            TextButton(enabled = !connectedV2.pending, onClick = {
                                confirmRestorePrevious = false
                                onRestorePrevious()
                            }, modifier = Modifier.fillMaxWidth().testTag("connected-restore-previous-confirm")) {
                                Text("Confirm restore previous queue")
                            }
                            TextButton(onClick = { confirmRestorePrevious = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                        }
                    }
                    when {
                        remoteMode -> Text("Choose This laptop as the source before moving. A selected remote player is not moved by this action.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        group != null -> Text("Leave Play together before moving this queue.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        localStatus == "Ready" -> Text("Start a song on this laptop before moving the queue.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        else -> {
                            Text("The current song and remaining queue move together. The destination queue is saved for paused recovery.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            connectedV2.devices.filter { it.enabled && it.id != ownDeviceId && "move" in it.capabilities }.forEach { device ->
                                TextButton(enabled = !connectedV2.pending, onClick = { onMoveV2(device.id) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Replace ${device.label}'s queue and Move", maxLines = 2)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { togetherOpen = !togetherOpen }, modifier = Modifier.fillMaxWidth()) { Text("Play together…", modifier = Modifier.fillMaxWidth()) }
                if (togetherOpen) {
                    if (remoteMode && group == null) {
                        Text("Choose This laptop as the source before creating a shared queue.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    } else if (group != null) {
                        Text("Together · ${group.status.name.lowercase()}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        group.members.forEach { member ->
                            val memberName = if (member.deviceId == ownDeviceId) "This laptop" else
                                connectedV2.devices.firstOrNull { it.id == member.deviceId }?.label ?: member.deviceId.takeLast(8)
                            val memberStatus = when (member.status) {
                                dev.echo.data.firebase.GroupMemberV2Status.BUFFERING -> "Buffering"
                                dev.echo.data.firebase.GroupMemberV2Status.DISCONNECTED -> "Reconnecting"
                                dev.echo.data.firebase.GroupMemberV2Status.LEFT -> "Left"
                                else -> member.status.name.lowercase().replaceFirstChar(Char::uppercase)
                            }
                            Text("$memberName · $memberStatus", color = Muted, fontSize = 11.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                        }
                        group.timeline?.let { timeline ->
                            Text("Shared queue · ${timeline.queue.entries.size} songs", color = Accent, fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            val preview = timeline.queue.entries.drop(timeline.cursor).take(12)
                            preview.forEachIndexed { offset, occurrence ->
                                val index = timeline.cursor + offset
                                Text("${if (offset == 0) "Now · " else "${index + 1}. "}${occurrence.recording.title} · ${occurrence.recording.artist}",
                                    color = if (index == timeline.cursor) Accent else Muted, fontSize = 11.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                            }
                            val remaining = timeline.queue.entries.size - timeline.cursor - preview.size
                            if (remaining > 0) Text("+$remaining more songs", color = Muted, fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                        }
                        TextButton(enabled = !connectedV2.pending, onClick = { onGroupAction(dev.echo.data.firebase.GroupActionV2.PREVIOUS) }, modifier = Modifier.fillMaxWidth()) { Text("Previous for everyone") }
                        TextButton(enabled = !connectedV2.pending, onClick = { onGroupAction(if (group.timeline?.playing == true) dev.echo.data.firebase.GroupActionV2.PAUSE else dev.echo.data.firebase.GroupActionV2.PLAY) }, modifier = Modifier.fillMaxWidth()) { Text(if (group.timeline?.playing == true) "Pause everyone" else "Play for everyone") }
                        TextButton(enabled = !connectedV2.pending, onClick = { onGroupAction(dev.echo.data.firebase.GroupActionV2.NEXT) }, modifier = Modifier.fillMaxWidth()) { Text("Next for everyone") }
                        TextButton(enabled = !connectedV2.pending, onClick = { onGroupAction(dev.echo.data.firebase.GroupActionV2.LEAVE) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (group.coordinatorDeviceId == ownDeviceId) "End for everyone and restore queue paused"
                                else "Leave group and restore queue paused")
                        }
                    } else {
                        Text("Share this queue with up to ${MAX_CONNECTED_GROUP_MEMBERS - 1} other Echo players. Each device's independent queue remains available for paused recovery.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        Text("${togetherDeviceIds.size} of ${MAX_CONNECTED_GROUP_MEMBERS - 1} other players selected", color = Muted,
                            fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
                        togetherCandidates.forEach { device ->
                            val selected = device.id in togetherDeviceIds
                            val expectedSession = togetherDeviceSessions[device.id]
                            val changedSession = selected && expectedSession != device.sessionId
                            val ready = device.enabled && !changedSession
                            Row(Modifier.fillMaxWidth().clickable(enabled = ready || selected) {
                                togetherDeviceSessions = if (selected) togetherDeviceSessions - device.id else
                                    if (togetherDeviceSessions.size < MAX_CONNECTED_GROUP_MEMBERS - 1) togetherDeviceSessions + (device.id to device.sessionId) else togetherDeviceSessions
                            }.padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = selected, enabled = ready || selected, onCheckedChange = { checked ->
                                    togetherDeviceSessions = if (checked && !selected && togetherDeviceSessions.size < MAX_CONNECTED_GROUP_MEMBERS - 1)
                                        togetherDeviceSessions + (device.id to device.sessionId)
                                        else if (!checked) togetherDeviceSessions - device.id else togetherDeviceSessions
                                })
                                Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
                                    Text(device.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (changedSession) "Session changed · remove and select again" else if (!device.enabled) "Offline or unavailable" else if (device.snapshot.playing)
                                        "Playing · ${device.snapshot.title.orEmpty()}" else "Paused · ${device.snapshot.title ?: "ready"}",
                                        color = Muted, fontSize = 11.sp)
                                }
                            }
                        }
                        togetherDeviceIds.filter { id -> togetherCandidates.none { it.id == id } }.forEach { id ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Selected player ${id.takeLast(8)} is no longer available. Remove it to continue.",
                                    color = Color(0xFFFFB7AC), fontSize = 11.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { togetherDeviceSessions = togetherDeviceSessions - id }) { Text("Remove") }
                            }
                        }
                        if (invalidTogetherSelection) Text("A selected player is offline, unavailable, or its session changed. Remove it or wait until it is ready; no selected players will be skipped.",
                            color = Color(0xFFFFB7AC), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        TextButton(enabled = readyToStartTogether && !connectedV2.pending, onClick = {
                            if (selectedTogetherDevices.any { it.snapshot.playing }) confirmTogether = true
                            else onTogetherV2(togetherDeviceIds.toList(), togetherDeviceSessions, false)
                        }, modifier = Modifier.fillMaxWidth().testTag("connected-together-start")) {
                            Text("Start Play together")
                        }
                    }
                    connectedV2.message?.let { Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                }
                Text("Audio outputs", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                Text("Output selection is not available yet.", color = Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
        if (confirmTogether) AlertDialog(onDismissRequest = { confirmTogether = false },
            title = { Text("Replace playing devices?") },
            text = { Text("At least one selected player is currently playing. Play together will replace its current playback and queue.") },
            confirmButton = { TextButton(enabled = !connectedV2.pending && readyToStartTogether, onClick = {
                confirmTogether = false
                onTogetherV2(togetherDeviceIds.toList(), togetherDeviceSessions, true)
            }, modifier = Modifier.testTag("connected-together-confirm")) { Text("Replace and start") } },
            dismissButton = { TextButton(onClick = { confirmTogether = false }) { Text("Cancel") } })
    }
}

@Composable
private fun QueuePicker(
    playback: DesktopPlaybackState, upcoming: List<Int>, recommended: Set<String>, onClear: () -> Unit,
    onJump: (Int, Long) -> Unit, onMove: (Int, Int, Long, Long) -> Unit, onRemove: (Int, Long, Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Queue") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            Column(Modifier.width(340.dp).heightIn(max = 400.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Up next", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onClear(); expanded = false }) { Text("Clear") }
                }
                LazyColumn(Modifier.heightIn(max = 350.dp)) {
                    itemsIndexed(upcoming, key = { _, absolute -> "${playback.queueRevision}:$absolute" }) { index, absolute ->
                        val track = playback.queue[absolute]
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { onJump(absolute, playback.queueRevision); expanded = false }.padding(vertical = 7.dp)) {
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                                Text(if (track.id in recommended) "Smart pick · ${track.artist}" else track.artist,
                                    color = if (track.id in recommended) Accent else Muted, maxLines = 1, fontSize = 11.sp)
                            }
                            Column {
                                TextButton(onClick = { onMove(absolute, upcoming[index - 1], playback.occurrence, playback.queueRevision) },
                                    enabled = index > 0, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(28.dp)) { Text("↑") }
                                TextButton(onClick = { onRemove(absolute, playback.occurrence, playback.queueRevision) },
                                    contentPadding = PaddingValues(0.dp), modifier = Modifier.size(28.dp)) { Text("×") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shortDeviceName(label: String): String = when {
    label.contains("windows", true) -> "Laptop"
    label.contains("android", true) -> "Phone"
    else -> label.substringBefore(' ').take(14)
}

private fun lastKnownDeviceName(devices: List<ConnectedDevice>, id: String?): String =
    devices.firstOrNull { it.id == id }?.label ?: "device"

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
