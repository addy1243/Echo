@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, coil.annotation.ExperimentalCoilApi::class)

package dev.echo.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.navigation.compose.*
import coil.compose.AsyncImage
import dev.echo.core.Playlist
import dev.echo.core.Track
import dev.echo.data.provider.TrackMatcher
import dev.echo.app.update.UpdatePrompt
import dev.echo.app.update.UpdateStatus
import kotlinx.coroutines.delay
import java.util.UUID

private val Violet = Color(0xFFBFA4FF)
private val Ink = Color(0xFF101014)

@Composable
fun EchoApp(vm: EchoViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val credentialError by vm.settings.credentialError.collectAsStateWithLifecycle()
    val historyError by vm.settings.historyError.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val audio = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (result[audio] == true) vm.scan() else vm.message = "Allow audio access to scan your music, or add a file from Library."
    }
    val scan = {
        permission.launch(buildList {
            add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())
    }
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.runAction("File added to library") {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val track = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val metadata = android.media.MediaMetadataRetriever()
                try {
                    metadata.setDataSource(context, uri)
                    Track("file:${UUID.nameUUIDFromBytes(uri.toString().toByteArray())}", metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: uri.lastPathSegment.orEmpty(),
                        metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown artist",
                        metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                        metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0, sourceUri = uri.toString())
                } finally { metadata.release() }
            }
            vm.library.upsertTracks(listOf(track))
        }
    }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    MaterialTheme(colorScheme = darkColorScheme(primary = Violet, secondary = Color(0xFF8CDBCD), background = Ink, surface = Color(0xFF19191F))) {
        UpdatePrompt(vm)
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.GraphicEq, null, tint = Violet); Spacer(Modifier.width(10.dp)); Text("echo", fontWeight = FontWeight.Bold, letterSpacing = 2.sp) } },
                navigationIcon = { if (route !in listOf("home", "search", "library", "settings")) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { nav.navigate("settings") { launchSingleTop = true } }) { Icon(Icons.Default.Settings, "Settings") } }) },
            bottomBar = { Column {
                if (vm.current != null && route != "player") MiniPlayer(vm) { nav.navigate("player") { launchSingleTop = true } }
                NavigationBar {
                    listOf(Triple("home", "Home", Icons.Default.Home), Triple("search", "Search", Icons.Default.Search), Triple("library", "Library", Icons.Default.LibraryMusic)).forEach { (key, label, icon) ->
                        NavigationBarItem(selected = route == key, onClick = { nav.navigate(key) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } }, icon = { Icon(icon, label) }, label = { Text(label) })
                    }
                }
            } },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                (credentialError ?: historyError)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
                    composable("home") {
                        LazyColumn(modifier = Modifier.testTag("home-feed"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item { Hero("Your music.\nYour space.", "A little room for everything you love.") }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = scan) { Icon(Icons.Default.Sync, null); Text(" Scan music") }; OutlinedButton(onClick = { nav.navigate("discover") }) { Text("Discover") } } }
                            if (vm.current == null) item { TextButton(onClick = vm::resume, enabled = vm.controller != null) { Icon(Icons.Default.Restore, null); Text(" Resume saved queue") } }
                            if (tracks.isEmpty()) item { EmptyState("Make yourself at home", "Discover music online, scan your device, or choose an audio file in Library.") }
                            if (recent.isNotEmpty()) {
                                item { SectionTitle("On repeat") }
                                items(recent.take(8), key = { "recent:${it.id}" }) { TrackRow(it, vm, playlists, favorites) { vm.play(recent, recent.indexOf(it)) } }
                            }
                            if (playlists.isNotEmpty()) {
                                item { SectionTitle("Your playlists") }
                                items(playlists, key = { "playlist:${it.id}" }) { PlaylistCard(it) { nav.navigate("playlist/${it.id}") } }
                            }
                            if (tracks.isNotEmpty()) {
                                item { SectionTitle("From your library") }
                                items(tracks.takeLast(12).reversed(), key = { "library:${it.id}" }) { TrackRow(it, vm, playlists, favorites) { vm.play(listOf(it)) } }
                            }
                        }
                    }
                    composable("search") { SearchScreen(vm, tracks, playlists, favorites) }
                    composable("discover") { DiscoveryScreen(vm, playlists, favorites) }
                    composable("library") { LibraryScreen(vm, tracks, playlists, favorites, scan, { filePicker.launch(arrayOf("audio/*")) }, { nav.navigate("playlist/$it") }) }
                    composable("playlist/{id}") { back ->
                        val id = back.arguments?.getString("id").orEmpty()
                        PlaylistScreen(vm, id, playlists.find { it.id == id }, tracks, playlists, favorites)
                    }
                    composable("player") { PlayerScreen(vm, tracks, favorites) }
                    composable("settings") { SettingsScreen(vm) }
                }
            }
        }
    }
}

@Composable private fun Hero(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF40315B), Color(0xFF20232E)))).padding(24.dp)) {
        Text("ECHO MUSIC", color = Violet, style = MaterialTheme.typography.labelMedium, letterSpacing = 3.sp)
        Spacer(Modifier.height(16.dp)); Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp)); Text(subtitle, color = Color(0xFFCEC7D9))
    }
}
@Composable private fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
@Composable private fun EmptyState(title: String, body: String) { Column(Modifier.padding(vertical = 20.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(8.dp)); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun Artwork(uri: String?, size: Int = 56) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 6).dp)).background(Brush.linearGradient(listOf(Color(0xFF493962), Color(0xFF263B43)))), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.GraphicEq, null, tint = Violet, modifier = Modifier.size((size / 2).dp))
        if (!uri.isNullOrBlank()) AsyncImage(uri, "Album artwork", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}
@Composable private fun PlaylistCard(playlist: Playlist, click: () -> Unit) {
    Surface(onClick = click, shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Violet, modifier = Modifier.size(36.dp)); Spacer(Modifier.width(16.dp))
            Column { Text(playlist.name, fontWeight = FontWeight.SemiBold); Text(if (playlist.spotifyId == null) "Personal playlist" else "Imported from Spotify", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable private fun TrackRow(track: Track, vm: EchoViewModel, playlists: List<Playlist>, favorites: Set<String>, onPlay: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf(false) }
    var credits by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track.artworkUri); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(if (track.sourceUri == null) "Choose a recording in its playlist" else "${track.provider} · ${time(track.durationMs)}", color = if (track.sourceUri == null) MaterialTheme.colorScheme.error else Violet, style = MaterialTheme.typography.labelSmall)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Options for ${track.title}") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(if (track.id in favorites) "Unlike" else "Like") }, onClick = { menu = false; vm.favorite(track) })
                DropdownMenuItem(text = { Text("Play next") }, onClick = { menu = false; vm.enqueue(track, true) })
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menu = false; vm.enqueue(track) })
                DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { menu = false; add = true })
                if (track.id.startsWith("spotify:") && track.provider == "local") DropdownMenuItem(text = { Text("Clear local match") }, onClick = { menu = false; vm.runAction("Local match cleared") { vm.library.clearLocalMatch(track.id) } })
                if (track.attribution != null) DropdownMenuItem(text = { Text("Credits & license") }, onClick = { menu = false; credits = true })
            }
        }
    }
    if (add) AlertDialog(onDismissRequest = { add = false }, title = { Text("Add to playlist") }, text = {
        LazyColumn { if (playlists.isEmpty()) item { Text("Create a playlist in Library first.") }; items(playlists) { playlist -> TextButton(onClick = { add = false; vm.runAction("Added to ${playlist.name}") { if (vm.tracks.value.none { it.id == track.id }) vm.library.upsertTracks(listOf(track)); vm.library.addToPlaylist(playlist.id, track.id) } }) { Text(playlist.name) } } }
    }, confirmButton = { TextButton(onClick = { add = false }) { Text("Close") } })
    if (credits) Credits(track) { credits = false }
}

@Composable private fun Credits(track: Track, close: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = close, title = { Text("Audio credits") }, text = { Column {
        Text(track.attribution.orEmpty())
        track.licenseUrl?.let { TextButton(onClick = { openLink(context, it) }) { Text("View license") } }
        track.sourcePage?.let { TextButton(onClick = { openLink(context, it) }) { Text("Original source") } }
    } }, confirmButton = { TextButton(onClick = close) { Text("Done") } })
}

@Composable private fun SearchScreen(vm: EchoViewModel, tracks: List<Track>, playlists: List<Playlist>, favorites: Set<String>) {
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableIntStateOf(2) }
    val history by vm.settings.searchHistory.collectAsStateWithLifecycle(emptyList())
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Find your next favorite") }
        item { OutlinedTextField(query, { query = it }, label = { Text("Songs, artists or albums") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Library", "Licensed audio", "YouTube").forEachIndexed { index, label -> FilterChip(source == index, { source = index }, label = { Text(label) }) } } }
        if (vm.isSearching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (source == 0) {
            val filtered = if (query.isBlank()) emptyList() else tracks.filter { "${it.title} ${it.artist} ${it.album}".contains(query, true) }
            if (query.isBlank()) items(history) { TextButton(onClick = { query = it }) { Icon(Icons.Default.History, null); Text("  $it") } }
            else if (filtered.isEmpty()) item { EmptyState("No results", "Try another title, artist or album.") }
            items(filtered, key = { it.id }) { TrackRow(it, vm, playlists, favorites) { vm.runAction { vm.settings.rememberSearch(query) }; vm.play(filtered, filtered.indexOf(it)) } }
        } else if (source == 1) {
            item { Text("Search openly licensed audio on Wikimedia Commons. Credits and licenses are available on each recording.", style = MaterialTheme.typography.bodySmall) }
            item { Button(enabled = query.isNotBlank(), onClick = { vm.searchOnline(query) }) { Text("Search licensed audio") } }
            if (vm.searchedQuery.isNotBlank()) item { Text("Results for “${vm.searchedQuery}”", style = MaterialTheme.typography.bodySmall) }
            items(vm.onlineResults, key = { it.id }) { TrackRow(it, vm, playlists, favorites) { vm.play(vm.onlineResults, vm.onlineResults.indexOf(it)) } }
        } else {
            item { Text("Find music on YouTube and play it in Echo. No API key needed. Some recordings may be unavailable.", style = MaterialTheme.typography.bodySmall) }
            item { Button(enabled = query.isNotBlank(), onClick = { vm.searchYouTube(query) }) { Text("Search YouTube") } }
            if (vm.searchedQuery.isNotBlank()) item { Text("Results for “${vm.searchedQuery}”", style = MaterialTheme.typography.bodySmall) }
            items(vm.youtubeResults, key = { it.id }) { TrackRow(it, vm, playlists, favorites) { vm.play(vm.youtubeResults, vm.youtubeResults.indexOf(it)) } }
        }
    }
}

@Composable private fun DiscoveryScreen(vm: EchoViewModel, playlists: List<Playlist>, favorites: Set<String>) {
    LaunchedEffect(Unit) { if (vm.newMusicResults.isEmpty()) vm.refreshDiscovery() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val songs = if (tab == 0) vm.discoveryResults else vm.newMusicResults
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Discover with Echo") }
        item { Text("Artist-based discoveries from your likes and library. New music searches YouTube for this month's releases; results may include older songs. Refresh to check again.", style = MaterialTheme.typography.bodySmall) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !vm.discoveryLoading, onClick = vm::refreshDiscovery) { Text("Refresh discoveries") }
            if (songs.isNotEmpty()) Button(onClick = { vm.play(songs) }) { Text("Play") }
        } }
        item { TabRow(tab) { listOf("Discovery mix", "New music").forEachIndexed { index, label -> Tab(tab == index, { tab = index }, text = { Text(label) }) } } }
        if (vm.discoveryLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (tab == 0 && songs.isNotEmpty()) item { TextButton(enabled = !vm.busy, onClick = vm::saveDiscovery) { Text("Save mix to Library") } }
        if (songs.isEmpty() && !vm.discoveryLoading) item { EmptyState("No discoveries yet", "Add songs to your library, then refresh. You can also find music from Search.") }
        items(songs, key = { it.id }) { TrackRow(it, vm, playlists, favorites) { vm.play(songs, songs.indexOf(it)) } }
    }
}

@Composable private fun LibraryScreen(vm: EchoViewModel, tracks: List<Track>, playlists: List<Playlist>, favorites: Set<String>, scan: () -> Unit, addFile: () -> Unit, openPlaylist: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var create by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf<Pair<String, List<Track>>?>(null) }
    val importPlaylist = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSpotifyFile(uri)
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Your collection") }
        item { OutlinedButton(enabled = !vm.busy, onClick = { importPlaylist.launch(arrayOf("text/csv", "application/json", "text/plain", "application/vnd.ms-excel", "text/comma-separated-values")) }) { Text("Import Spotify playlist file") } }
        item { Text("Choose an Exportify CSV or Spotify's downloaded Playlist JSON. No Spotify login needed. Songs are imported in order; choose recordings in the playlist to play them.", style = MaterialTheme.typography.bodySmall) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilledTonalButton(onClick = { create = true }) { Text("New playlist") }; OutlinedButton(onClick = addFile) { Text("Add file") }; IconButton(onClick = scan) { Icon(Icons.Default.Sync, "Scan device") } } }
        item { ScrollableTabRow(tab, edgePadding = 0.dp) { listOf("Tracks", "Liked", "Playlists", "Albums", "Artists").forEachIndexed { i, title -> Tab(tab == i, { tab = i }, text = { Text(title) }) } } }
        when (tab) {
            0, 1 -> {
                val shown = if (tab == 1) tracks.filter { it.id in favorites } else tracks
                if (shown.isEmpty()) item { EmptyState("Room for your music", if (tab == 1) "Like a track from its options menu." else "Scan your device or add an audio file to get started.") }
                items(shown, key = { it.id }) { TrackRow(it, vm, playlists, favorites) { vm.play(shown, shown.indexOf(it)) } }
            }
            2 -> items(playlists, key = { it.id }) { PlaylistCard(it) { openPlaylist(it.id) } }
            3, 4 -> {
                val groups = tracks.groupBy { if (tab == 3) "${it.album.ifBlank { "Unknown album" }} · ${it.artist}" else it.artist }.toSortedMap()
                items(groups.entries.toList(), key = { it.key }) { (name, songs) -> ListItem(headlineContent = { Text(name) }, supportingContent = { Text("${songs.size} tracks") }, leadingContent = { Artwork(songs.first().artworkUri) }, modifier = Modifier.clickable { group = name to songs }) }
            }
        }
    }
    if (create) NameDialog("New playlist", "", { create = false }) { name -> create = false; vm.runAction { openPlaylist(vm.library.createPlaylist(name)) } }
    group?.let { (title, songs) -> AlertDialog(onDismissRequest = { group = null }, title = { Text(title) }, text = { LazyColumn { items(songs) { TrackRow(it, vm, playlists, favorites) { vm.play(songs, songs.indexOf(it)); group = null } } } }, confirmButton = { TextButton(onClick = { group = null }) { Text("Close") } }) }
}

@Composable private fun PlaylistScreen(vm: EchoViewModel, id: String, playlist: Playlist?, all: List<Track>, playlists: List<Playlist>, favorites: Set<String>) {
    LaunchedEffect(id, all) { vm.loadPlaylist(id) }
    var rename by remember { mutableStateOf(false) }
    var match by remember { mutableStateOf<Track?>(null) }
    var onlineMatch by remember { mutableStateOf<Pair<Int, Track>?>(null) }
    var add by remember { mutableStateOf(false) }
    val songs = if (vm.loadedPlaylistId == id) vm.playlistItems else emptyList()
    val firstPlayable = firstPlayableIndex(songs)
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Hero(playlist?.name ?: "Playlist", "${songs.size} tracks · made for your moments") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.play(songs, firstPlayable) }, enabled = firstPlayable >= 0) { Icon(Icons.Default.PlayArrow, null); Text("Play") }
            FilledTonalButton(onClick = { vm.play(songs, firstPlayable, shuffle = true) }, enabled = firstPlayable >= 0) { Icon(Icons.Default.Shuffle, "Shuffle playlist") }
            IconButton(onClick = { add = true }) { Icon(Icons.Default.Add, "Add tracks") }
            IconButton(onClick = { rename = true }) { Icon(Icons.Default.Edit, "Rename") }
            playlist?.spotifyId?.let { spotifyId -> IconButton(onClick = { vm.mutatePlaylist(id) { vm.spotify.importPlaylist(spotifyId) } }) { Icon(Icons.Default.Refresh, "Refresh from Spotify; replaces playlist contents") } }
        } }
        item { OutlinedButton(enabled = firstPlayable >= 0 && !vm.busy, onClick = { vm.smartShuffle(songs) }) { Text("Smart shuffle · Echo") } }
        item { Text("Smart shuffle adds discoveries between your songs for this listening session. Your saved playlist stays as it is.", style = MaterialTheme.typography.bodySmall) }
        if (songs.isEmpty()) item { EmptyState("Start the soundtrack", "Add tracks from your library using the plus button.") }
        itemsIndexed(songs, key = { index, _ -> index }) { index, track ->
            Column {
                TrackRow(track, vm, playlists, favorites) { vm.play(songs, index) }
                if (track.sourceUri == null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onlineMatch = index to track; vm.searchYouTube("${track.title} ${track.artist}".take(200)) }) { Text("Find recording") }
                        TextButton(onClick = { match = track }) { Text("Match local file") }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(enabled = index > 0, onClick = { vm.mutatePlaylist(id) { vm.library.movePlaylistTrack(id, index, index - 1) } }) { Icon(Icons.Default.ArrowUpward, "Move ${track.title} up") }
                    IconButton(enabled = index < songs.lastIndex, onClick = { vm.mutatePlaylist(id) { vm.library.movePlaylistTrack(id, index, index + 1) } }) { Icon(Icons.Default.ArrowDownward, "Move ${track.title} down") }
                    IconButton(onClick = { vm.mutatePlaylist(id) { vm.library.removeFromPlaylist(id, index) } }) { Icon(Icons.Default.RemoveCircleOutline, "Remove this occurrence") }
                }
            }
        }
    }
    if (rename) NameDialog("Rename playlist", playlist?.name.orEmpty(), { rename = false }) { name -> rename = false; vm.runAction { vm.library.renamePlaylist(id, name) } }
    onlineMatch?.let { (position, imported) ->
        AlertDialog(onDismissRequest = { onlineMatch = null }, title = { Text("Choose a recording") }, text = {
            Column {
                Text("${imported.title} · ${imported.artist}\nChoosing a result replaces this occurrence in your Echo playlist.")
                if (vm.isSearching) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (vm.youtubeResults.isEmpty()) Text("No results. Try Search for another title or use a local file.")
                LazyColumn { items(vm.youtubeResults, key = { it.id }) { recording ->
                    ListItem(headlineContent = { Text(recording.title) }, supportingContent = { Text("${recording.artist} · ${time(recording.durationMs)}") }, modifier = Modifier.clickable {
                        onlineMatch = null
                        vm.mutatePlaylist(id) { vm.library.replacePlaylistTrack(id, position, imported.id, recording) }
                    })
                } }
            }
        }, confirmButton = { TextButton(onClick = { onlineMatch = null }) { Text("Cancel") } })
    }
    if (add) AlertDialog(onDismissRequest = { add = false }, title = { Text("Add a track") }, text = { LazyColumn { items(all, key = { it.id }) { track -> ListItem(headlineContent = { Text(track.title) }, supportingContent = { Text(track.artist) }, modifier = Modifier.clickable { vm.mutatePlaylist(id) { vm.library.addToPlaylist(id, track.id) }; add = false }) } } }, confirmButton = { TextButton(onClick = { add = false }) { Text("Done") } })
    match?.let { imported -> AlertDialog(onDismissRequest = { match = null }, title = { Text("Choose a local recording") }, text = { LazyColumn { items(all.filter { it.provider == "local" && !it.id.startsWith("spotify:") && it.sourceUri != null }.sortedByDescending { TrackMatcher.matches(imported, it) }, key = { it.id }) { local -> ListItem(headlineContent = { Text(local.title) }, supportingContent = { Text(local.artist + if (TrackMatcher.matches(imported, local)) " · Suggested match" else "") }, modifier = Modifier.clickable {
        match = null; vm.mutatePlaylist(id) { vm.library.matchLocalTrack(imported.id, local.id) }
    }) } } }, confirmButton = { TextButton(onClick = { match = null }) { Text("Cancel") } }) }
}

@Composable private fun MiniPlayer(vm: EchoViewModel, open: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clickable(onClick = open), shape = RoundedCornerShape(12.dp), color = Color(0xFF302B3D)) {
        Column {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(vm.current?.mediaMetadata?.artworkUri?.toString(), 42); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(vm.current?.mediaMetadata?.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis); Text(vm.current?.mediaMetadata?.artist?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                IconButton(onClick = vm::toggle) { if (vm.buffering) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) else Icon(if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (vm.playing) "Pause" else "Play") }
                IconButton(onClick = { vm.controller?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "Next") }
            }
            LinearProgressIndicator(progress = { if (vm.duration > 0) (vm.position.toFloat() / vm.duration).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

@Composable private fun PlayerScreen(vm: EchoViewModel, tracks: List<Track>, favorites: Set<String>) {
    var queue by remember { mutableStateOf(false) }
    var credits by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    val current = tracks.find { it.id == vm.current?.mediaId }
    val controller = vm.controller
    val revision = vm.revision
    LazyColumn(contentPadding = PaddingValues(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("NOW PLAYING", color = Violet, letterSpacing = 3.sp, style = MaterialTheme.typography.labelMedium) }
        item { Artwork(vm.current?.mediaMetadata?.artworkUri?.toString(), 280) }
        item { Column(Modifier.fillMaxWidth()) { Text(vm.current?.mediaMetadata?.title?.toString() ?: "Choose a song", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(vm.current?.mediaMetadata?.artist?.toString().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item { Column {
            Slider(value = seeking ?: vm.position.toFloat().coerceIn(0f, vm.duration.toFloat().coerceAtLeast(1f)), onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { controller?.seekTo(it.toLong()) }; seeking = null }, valueRange = 0f..vm.duration.toFloat().coerceAtLeast(1f), enabled = vm.duration > 0)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(time(vm.position)); Text(time(vm.duration)) }
        } }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { controller?.shuffleModeEnabled = controller?.shuffleModeEnabled != true }) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (controller?.shuffleModeEnabled == true && revision >= 0) Violet else Color.Gray) }
            IconButton(onClick = { controller?.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, "Previous", modifier = Modifier.size(32.dp)) }
            FilledIconButton(onClick = vm::toggle, modifier = Modifier.size(72.dp)) { if (vm.buffering) CircularProgressIndicator(Modifier.size(28.dp)) else Icon(if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (vm.playing) "Pause" else "Play", modifier = Modifier.size(36.dp)) }
            IconButton(onClick = { controller?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(32.dp)) }
            IconButton(onClick = { controller?.repeatMode = when (controller?.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }) { Icon(if (controller?.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat mode ${controller?.repeatMode}", tint = if (controller?.repeatMode != Player.REPEAT_MODE_OFF && revision >= 0) Violet else Color.Gray) }
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(enabled = current != null, onClick = { current?.let(vm::favorite) }) { Icon(if (current?.id in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Like song", tint = Violet) }
            TextButton(onClick = { queue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, null); Text(" Queue") }
            if (current?.attribution != null) TextButton(onClick = { credits = true }) { Text("Credits") }
        } }
        item { Text("Source: ${current?.provider ?: "none"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (queue) AlertDialog(onDismissRequest = { queue = false }, title = { Text("Playback queue") }, text = {
        LazyColumn { items(controller?.mediaItemCount ?: 0) { index -> val item = controller?.getMediaItemAt(index); Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { controller?.seekToDefaultPosition(index); controller?.play() }, modifier = Modifier.weight(1f)) { Text(item?.mediaMetadata?.title?.toString() ?: "Track ${index + 1}") }
            IconButton(onClick = { controller?.removeMediaItem(index); vm.message = "Removed from queue" }) { Icon(Icons.Default.Close, "Remove from queue") }
        } } }
    }, confirmButton = { TextButton(onClick = { queue = false }) { Text("Done") } })
    if (credits && current != null) Credits(current) { credits = false }
}

@Composable private fun SettingsScreen(vm: EchoViewModel) {
    val updateStatus by vm.updater.status.collectAsStateWithLifecycle()
    val connected by vm.spotify.connected.collectAsStateWithLifecycle()
    var sourcePriority by remember { mutableStateOf(vm.sources.priority) }
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.runAction("Library exported; keep the backup private") {
            val json = vm.library.exportBackup()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                (context.contentResolver.openOutputStream(uri) ?: error("Cannot open backup destination")).bufferedWriter().use { it.write(json) }
            }
        }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.runAction("Library backup merged") {
            val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                (context.contentResolver.openInputStream(uri) ?: error("Cannot open backup")).bufferedReader().use { reader ->
                    val text = StringBuilder(); val buffer = CharArray(8192)
                    while (true) { val count = reader.read(buffer); if (count == -1) break; require(text.length + count <= 10_000_000) { "Backup is larger than 10 MB" }; text.append(buffer, 0, count) }
                    text.toString()
                }
            }
            vm.library.importBackup(json)
        }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionTitle("Make Echo yours") }
        item { Text("Echo Music ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedButton(enabled = updateStatus !is UpdateStatus.Checking && updateStatus !is UpdateStatus.Downloading, onClick = vm::checkUpdates) { Text(if (updateStatus is UpdateStatus.Checking) "Checking for updates…" else "Check for updates") } }
        item { Text("Spotify playlist files", style = MaterialTheme.typography.titleMedium) }
        item { Text("Import an existing playlist from Library using a CSV or Spotify playlist JSON export. This copies song metadata; it does not sync changes from Spotify or include audio. Echo's discovery and smart shuffle use YouTube searches.", style = MaterialTheme.typography.bodySmall) }
        if (connected) item { OutlinedButton(onClick = { vm.runAction("Disconnected") { vm.spotify.disconnect() } }) { Text("Disconnect previous Spotify session") } }
        item { HorizontalDivider(); SectionTitle("Playback & storage") }
        item { Text("Playback sources", style = MaterialTheme.typography.titleMedium) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("local", "youtube", "commons").forEach { id -> FilterChip(id in sourcePriority, onClick = { vm.runAction { sourcePriority = if (id in sourcePriority) sourcePriority - id else sourcePriority + id; vm.sources.priority = sourcePriority } }, label = { Text(when (id) { "local" -> "Local files"; "youtube" -> "YouTube"; else -> "Commons" }) }) }
        } }
        item { Text("Priority: ${sourcePriority.joinToString(" → ").ifBlank { "All providers disabled" }}", style = MaterialTheme.typography.bodySmall) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { export.launch("echo-library.json") }) { Text("Export library") }
            OutlinedButton(onClick = { importBackup.launch(arrayOf("application/json")) }) { Text("Import backup") }
        } }
        item { Text("Backups contain library metadata, playlists, likes and local matches. Import merges tracks and replaces playlists with matching IDs. Audio files and API credentials are excluded.", style = MaterialTheme.typography.bodySmall) }
        item { Text("Original source quality · normal speed\nLocal files stay on your device. Echo does not download streams.\nThe queue, playlists, likes and history are saved locally.\nDark theme · Android Auto browsing through Media3.") }
        item { OutlinedButton(onClick = { vm.runAction("Artwork cache cleared") { coil.Coil.imageLoader(context).memoryCache?.clear(); coil.Coil.imageLoader(context).diskCache?.clear() } }) { Text("Clear artwork cache") } }
        item { Text("Android Auto requires testing on a phone and Desktop Head Unit. See playback/VALIDATION.md in the project.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun NameDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { save(name.trim()) }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
private fun time(milliseconds: Long): String = "%d:%02d".format(milliseconds / 60_000, milliseconds / 1000 % 60)
private fun openLink(context: android.content.Context, url: String) { if (Uri.parse(url).scheme == "https") context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
