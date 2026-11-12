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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.compose.*
import coil.compose.AsyncImage
import dev.echo.core.Playlist
import dev.echo.core.Track
import dev.echo.data.provider.TrackMatcher
import dev.echo.app.update.UpdatePrompt
import dev.echo.app.update.UpdateStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private val Violet = Color(0xFFBFA4FF)
private val Ink = Color(0xFF101014)

@Composable
private fun rememberArtworkAccent(uri: String?): Color {
    val context = LocalContext.current
    var accent by remember { mutableStateOf(Violet) }
    LaunchedEffect(uri, context) {
        accent = Violet
        if (uri.isNullOrBlank()) return@LaunchedEffect
        val result = try {
            coil.Coil.imageLoader(context).execute(coil.request.ImageRequest.Builder(context).data(uri).allowHardware(false).build())
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null } as? coil.request.SuccessResult ?: return@LaunchedEffect
        val bitmap = result.drawable.toBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888)
        var red = 0L; var green = 0L; var blue = 0L; var count = 0
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
            val pixel = bitmap.getPixel(x, y)
            red += android.graphics.Color.red(pixel); green += android.graphics.Color.green(pixel); blue += android.graphics.Color.blue(pixel); count++
        }
        if (count > 0) {
            val sampled = Color(android.graphics.Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt()))
            accent = androidx.compose.ui.graphics.lerp(sampled, if (brightnessForPalette(sampled) < .45f) Color.White else Color.Black, .22f)
        }
    }
    return accent
}

private fun brightnessForPalette(color: Color): Float = .299f * color.red + .587f * color.green + .114f * color.blue

@Composable
fun EchoApp(vm: EchoViewModel, showTutorial: Boolean = true) {
    if (!BuildConfig.STAGING) dev.echo.app.account.AccountSyncHost(vm)
    val connected by vm.connectedPlaybackState.collectAsStateWithLifecycle()
    val connectedV2 by vm.connectedPlaybackV2State.collectAsStateWithLifecycle()
    val selectedPlaybackTargetId by vm.selectedConnectedPlaybackTargetId.collectAsStateWithLifecycle()
    val connectedPlaybackEnabled by vm.connectedPlaybackEnabled.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    var spotifySearchPrefill by rememberSaveable { mutableStateOf("") }
    val entry by nav.currentBackStackEntryAsState()
    LaunchedEffect(vm.requestedDownloads, entry) {
        if (vm.requestedDownloads && entry != null) { nav.navigate("downloads") { launchSingleTop = true }; vm.requestedDownloads = false }
    }
    val route = entry?.destination?.route ?: "home"
    val activeTab = when { route.startsWith("playlist/") -> "library"; route.startsWith("discover") -> "home"; else -> route }
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val credentialError by vm.settings.credentialError.collectAsStateWithLifecycle()
    val historyError by vm.settings.historyError.collectAsStateWithLifecycle()
    val appearance by vm.settings.appearance.collectAsStateWithLifecycle("artwork")
    val accentName by vm.settings.guiAccent.collectAsStateWithLifecycle("violet")
    val brightness by vm.settings.brightness.collectAsStateWithLifecycle("dark")
    val focusedArtwork = rememberPlayerPresentation(vm, tracks).artworkUri
    val artworkAccent = rememberArtworkAccent(focusedArtwork)
    val accent = if (appearance == "artwork") artworkAccent else when (accentName) {
        "teal" -> Color(0xFF55C9B1); "rose" -> Color(0xFFE78EA8); "amber" -> Color(0xFFFFC15A)
        else -> if (accentName.startsWith("#")) runCatching { Color(android.graphics.Color.parseColor(accentName)) }.getOrDefault(Violet) else Violet
    }
    val light = brightness == "light"
    var readableAccent = accent
    repeat(12) {
        if (light && readableAccent.luminance() > .18f) readableAccent = androidx.compose.ui.graphics.lerp(readableAccent, Color.Black, .12f)
        if (!light && readableAccent.luminance() < .36f) readableAccent = androidx.compose.ui.graphics.lerp(readableAccent, Color.White, .12f)
    }
    val colors = if (light) lightColorScheme(primary = readableAccent, onPrimary = Color.White,
        background = androidx.compose.ui.graphics.lerp(Color.White, accent, .04f),
        surface = androidx.compose.ui.graphics.lerp(Color.White, accent, .07f))
        else darkColorScheme(primary = readableAccent, onPrimary = Color.Black,
            background = androidx.compose.ui.graphics.lerp(Ink, accent, .04f),
            surface = androidx.compose.ui.graphics.lerp(Color(0xFF19191F), accent, .06f))
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
    val tutorialPrefs = remember(context) { context.getSharedPreferences("echo-onboarding", android.content.Context.MODE_PRIVATE) }
    var tutorialPage by rememberSaveable { mutableIntStateOf(tutorialPrefs.getInt("page", 0).coerceIn(0, 4)) }
    var tutorialVisible by remember { mutableStateOf(!tutorialPrefs.getBoolean("complete", false)) }
    var tutorialSaveFailed by rememberSaveable { mutableStateOf(false) }
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
    MaterialTheme(colorScheme = colors) {
        if (showTutorial && tutorialVisible) EchoTutorial(
            page = tutorialPage,
            saveFailed = tutorialSaveFailed,
            onNext = {
                if (tutorialPage == 4) {
                    if (tutorialPrefs.edit().putBoolean("complete", true).remove("page").commit()) tutorialVisible = false
                    else tutorialSaveFailed = true
                } else {
                    tutorialPage++
                    tutorialPrefs.edit().putInt("page", tutorialPage).apply()
                }
            },
            onSkip = {
                if (tutorialPrefs.edit().putBoolean("complete", true).remove("page").commit()) tutorialVisible = false
                else tutorialSaveFailed = true
            }
        )
        UpdatePrompt(vm)
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { if (route != "player") TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text("echo", fontWeight = FontWeight.Bold, letterSpacing = 2.sp) } },
                navigationIcon = { if (route !in listOf("home", "search", "library", "profile", "settings")) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (connectedPlaybackEnabled || connected.devices.any { it.id != vm.connectedPlaybackDeviceIdForUi }) {
                        IconButton(onClick = { nav.navigate("player") { launchSingleTop = true } }) { Icon(Icons.Default.Devices, "Connected player controls") }
                    }
                    IconButton(onClick = { nav.navigate("settings") { launchSingleTop = true } }) { Icon(Icons.Default.Settings, "Settings") }
                }) },
            bottomBar = { if (route != "player") Column {
                if (vm.current != null || selectedPlaybackTargetId != null || connectedV2.group != null ||
                    connectedPlaybackEnabled && connected.devices.isNotEmpty() || BuildConfig.STAGING && connected.devices.isNotEmpty()) PlayerDock(vm, tracks) { nav.navigate("player") { launchSingleTop = true } }
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(Triple("home", "Home", Icons.Default.Home), Triple("search", "Search", Icons.Default.Search), Triple("library", "Library", Icons.Default.LibraryMusic), Triple("profile", "Profile", Icons.Default.Person)).forEach { (key, label, icon) ->
                        Column(Modifier.weight(1f).fillMaxHeight().testTag("tab-$key").selectable(selected = activeTab == key, role = Role.Tab, onClick = {
                            nav.navigate(key) { popUpTo("home"); launchSingleTop = true }
                        }), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(icon, null, tint = if (activeTab == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
                            Text(label, fontSize = 11.sp, fontWeight = if (activeTab == key) FontWeight.Bold else FontWeight.Normal, color = if (activeTab == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } },
            floatingActionButton = { if (BuildConfig.STAGING && route != "player") StageAdminControls(vm.stage) }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                (credentialError ?: historyError)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.matchingProgress?.let { (done, total) -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Matching recordings · $done/$total", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::cancelMatching) { Text("Cancel") }
                } }
                NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
                    composable("home") {
                        HomeScreen(vm, tracks, playlists, favorites, recent, scan,
                            { tab -> nav.navigate("discover?tab=$tab") },
                            { nav.navigate("playlist/$it") }, { nav.navigate("library") }, { nav.navigate("search") })
                    }
                    composable("search") { SearchScreen(vm, tracks, playlists, favorites, spotifySearchPrefill) { spotifySearchPrefill = "" } }
                    composable("spotify-data") { SpotifyDataScreen { query -> spotifySearchPrefill = query; nav.navigate("search") { launchSingleTop = true } } }
                    composable("discover?tab={tab}", arguments = listOf(androidx.navigation.navArgument("tab") { type = androidx.navigation.NavType.IntType; defaultValue = 0 })) { back ->
                        DiscoveryScreen(vm, playlists, favorites, back.arguments?.getInt("tab") ?: 0)
                    }
                    composable("library") { LibraryScreen(vm, tracks, playlists, favorites, scan, { filePicker.launch(arrayOf("audio/*")) }, { nav.navigate("playlist/$it") }) }
                    composable("playlist/{id}") { back ->
                        val id = back.arguments?.getString("id").orEmpty()
                        PlaylistScreen(vm, id, playlists.find { it.id == id }, tracks, playlists, favorites)
                    }
                    composable("player") { FullPlayer(vm, tracks, playlists, favorites) { nav.popBackStack() } }
                    composable("profile") { ProfileScreen(vm) }
                    composable("settings") { SettingsScreen(vm, { nav.navigate("history") }, { nav.navigate("downloads") }, { nav.navigate("licenses") }, { nav.navigate("profile") }, { nav.navigate("spotify-data") }) }
                    composable("licenses") { LicensesScreen() }
                    composable("history") { HistoryScreen(vm) }
                    composable("downloads") { DownloadsScreen(vm) }
                }
            }
        }
    }
}

@Composable private fun HomeScreen(
    vm: EchoViewModel, tracks: List<Track>, playlists: List<Playlist>, favorites: Set<String>, recent: List<Track>,
    scan: () -> Unit, discover: (Int) -> Unit, openPlaylist: (String) -> Unit, library: () -> Unit, search: () -> Unit,
) {
    LaunchedEffect(Unit) { if (!BuildConfig.STAGING && vm.newMusicResults.isEmpty()) vm.refreshDiscovery() }
    val suggestions = vm.discoveryResults.ifEmpty { vm.newMusicResults }
    val preview = suggestions.ifEmpty { tracks.filter { it.sourceUri != null } }.take(5)
    val liked = tracks.filter { it.id in favorites && it.sourceUri != null }
    LazyColumn(Modifier.testTag("home-feed"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "welcome") { Column {
            Text("Made for today", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("A few discoveries. All your favorites.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item(key = "discover-heading") { HomeSectionHeader("Discover", "See all", { discover(0) }) }
        if (preview.isNotEmpty()) item(key = "discover-songs") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (suggestions.isEmpty()) Text(if (vm.discoveryLoading) "From your collection · refreshing picks" else "From your collection", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(preview, key = { "suggestion:${it.id}" }) { track ->
                        Surface(onClick = { vm.play(preview, preview.indexOf(track)) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.width(136.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Artwork(track.artworkUri, 120)
                                Text(track.title, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        } else item(key = "discovery-empty") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (BuildConfig.STAGING) {
                    Text("Stage preview · add demo players with Admin, then open Stage Mix to try queue routing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (vm.discoveryLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Finding songs for you…") }
                    else Text("Discoveries couldn't load. Try again, or search for an artist.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(enabled = !vm.discoveryLoading, onClick = vm::refreshDiscovery) { Text("Refresh discoveries") }
                }
            }
        }
        item(key = "discover-mixes") { LazyRow(Modifier.testTag("home-mixes"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "discovery-mix") { HomeMixCard("Echo discovery", "Picks from your artists", vm.discoveryResults.firstOrNull()?.artworkUri, Color(0xFF40315B)) { discover(0) } }
            item(key = "new-music-mix") { HomeMixCard("New music", "Explore this month's search", vm.newMusicResults.firstOrNull()?.artworkUri, Color(0xFF234743)) { discover(1) } }
        } }
        item(key = "playlists-heading") { HomeSectionHeader("Your playlists", "Open library", library) }
        if (playlists.isEmpty()) item(key = "playlists-empty") {
            OutlinedButton(onClick = library, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text(" Create or import a playlist") }
        } else item(key = "playlist-cards") { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(playlists.take(6), key = { "playlist:${it.id}" }) { playlist ->
                Surface(onClick = { openPlaylist(playlist.id) }, shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
                    Column(Modifier.width(168.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Violet, modifier = Modifier.size(32.dp))
                        Text(playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Open playlist", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } }
        item(key = "quick-actions") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(enabled = liked.isNotEmpty(), onClick = { vm.play(liked, shuffle = true) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Favorite, null); Text(" Play liked · ${liked.size}") }
                OutlinedButton(onClick = search, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Search, null); Text(" Search") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = scan) { Icon(Icons.Default.Sync, null); Text(" Scan music") }
                if (vm.current == null) TextButton(onClick = vm::resume, enabled = vm.controller != null) { Icon(Icons.Default.Restore, null); Text(" Resume queue") }
            }
        } }
        if (recent.isNotEmpty()) {
            item(key = "recent-heading") { SectionTitle("On repeat") }
            items(recent.take(4), key = { "recent:${it.id}" }) { track -> TrackRow(track, vm, playlists, favorites) { vm.play(recent, recent.indexOf(track)) } }
        }
        if (tracks.isNotEmpty()) {
            item(key = "library-heading") { HomeSectionHeader("From your library", "See all", library) }
            items(tracks.takeLast(4).reversed(), key = { "library:${it.id}" }) { track -> TrackRow(track, vm, playlists, favorites) { vm.play(listOf(track)) } }
        }
    }
}

@Composable private fun HomeSectionHeader(title: String, action: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        TextButton(onClick = click) { Text(action) }
    }
}

@Composable private fun HomeMixCard(title: String, subtitle: String, artwork: String?, color: Color, click: () -> Unit) {
    Surface(onClick = click, shape = RoundedCornerShape(16.dp), color = color) {
        Row(Modifier.width(240.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Artwork(artwork, 48)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun Hero(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF40315B), Color(0xFF20232E)))).padding(24.dp)) {
        Text("ECHO MUSIC", color = Violet, style = MaterialTheme.typography.labelMedium, letterSpacing = 3.sp)
        Spacer(Modifier.height(16.dp)); Text(title, color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp)); Text(subtitle, color = Color(0xFFCEC7D9))
    }
}
@Composable private fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
@Composable private fun EmptyState(title: String, body: String) { Column(Modifier.padding(vertical = 20.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(8.dp)); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable internal fun Artwork(uri: String?, size: Int = 56) {
    val requested = if (size >= 140) playerArtwork(uri) else uri
    var fallback by remember(requested) { mutableStateOf(false) }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 6).dp)).background(Brush.linearGradient(listOf(Color(0xFF493962), Color(0xFF263B43)))), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.GraphicEq, null, tint = Violet, modifier = Modifier.size((size / 2).dp))
        if (!uri.isNullOrBlank()) AsyncImage(if (fallback) uri else requested, "Album artwork", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop, onError = { fallback = true })
    }
}
@Composable private fun PlaylistCard(playlist: Playlist, click: () -> Unit) {
    Surface(onClick = click, shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)); Spacer(Modifier.width(16.dp))
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
            val detail = if (BuildConfig.STAGING && track.provider == "stage") "Silent Stage demo · routes to the focused player"
                else if (track.sourceUri == null) "Choose a recording in its playlist" else "${track.provider} · ${time(track.durationMs)}"
            Text(detail, color = if (track.sourceUri == null && track.provider != "stage") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Options for ${track.title}") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(if (track.id in favorites) "Unlike" else "Like") }, onClick = { menu = false; vm.favorite(track) })
                DropdownMenuItem(text = { Text("Play next") }, onClick = { menu = false; vm.enqueue(track, true) })
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menu = false; vm.enqueue(track) })
                DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { menu = false; add = true })
                DownloadTrackAction(vm, track)
                if ((track.id.startsWith("spotify:") || track.id.startsWith("cloud-local:")) && track.provider == "local") DropdownMenuItem(text = { Text("Clear local match") }, onClick = { menu = false; vm.runAction("Local match cleared") { vm.library.clearLocalMatch(track.id) } })
                if (track.attribution != null) DropdownMenuItem(text = { Text("Credits & license") }, onClick = { menu = false; credits = true })
            }
        }
    }
    if (add) AddToPlaylistDialog(track, vm, playlists) { add = false }
    if (credits) Credits(track) { credits = false }
}

@Composable internal fun Credits(track: Track, close: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = close, title = { Text("Audio credits") }, text = { Column {
        Text(track.attribution.orEmpty())
        track.licenseUrl?.let { TextButton(onClick = { openLink(context, it) }) { Text("View license") } }
        track.sourcePage?.let { TextButton(onClick = { openLink(context, it) }) { Text("Original source") } }
    } }, confirmButton = { TextButton(onClick = close) { Text("Done") } })
}

@Composable private fun SearchScreen(vm: EchoViewModel, tracks: List<Track>, playlists: List<Playlist>, favorites: Set<String>, initialQuery: String = "", onInitialQueryConsumed: () -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) { query = initialQuery; onInitialQueryConsumed() }
    }
    var source by rememberSaveable { mutableIntStateOf(2) }
    var youtubeMode by rememberSaveable { mutableIntStateOf(0) }
    var importLink by remember { mutableStateOf<String?>(null) }
    val history by vm.settings.searchHistory.collectAsStateWithLifecycle(emptyList())
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Find your next favorite") }
        item { OutlinedTextField(query, { query = it }, label = { Text("Songs, artists, albums or playlist titles") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Library", "Licensed audio", "YouTube").forEachIndexed { index, label -> FilterChip(source == index, { source = index }, label = { Text(label) }, modifier = Modifier.testTag("search-source-$index")) } } }
        if (vm.isSearching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (source == 0) {
            val filtered = if (query.isBlank()) emptyList() else tracks.filter { "${it.title} ${it.artist} ${it.album}".contains(query, true) }
            if (query.isBlank()) items(history) { TextButton(onClick = { query = it }) { Icon(Icons.Default.History, null); Text("  $it") } }
            else if (filtered.isEmpty()) item { EmptyState("No results", "Try another title, artist or album.") }
            items(filtered, key = { it.id }) { TrackRow(it, vm, playlists, favorites) { vm.runAction { vm.settings.rememberSearch(query) }; vm.playSelected(it) } }
        } else if (source == 1) {
            item { Text("Search openly licensed audio on Wikimedia Commons. Credits and licenses are available on each recording.", style = MaterialTheme.typography.bodySmall) }
            item { Button(enabled = query.isNotBlank(), onClick = { vm.searchOnline(query) }) { Text("Search licensed audio") } }
            if (vm.searchedQuery.isNotBlank()) item { Text("Results for “${vm.searchedQuery}”", style = MaterialTheme.typography.bodySmall) }
            items(vm.onlineResults, key = { it.id }) { TrackRow(it, vm, playlists, favorites) { vm.playSelected(it) } }
        } else {
            item { Text("Find music on YouTube and play it in Echo. No API key needed. Some recordings may be unavailable.", style = MaterialTheme.typography.bodySmall) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(youtubeMode == 0, { youtubeMode = 0; if (query.isNotBlank()) vm.searchYouTube(query) }, label = { Text("Songs") })
                FilterChip(youtubeMode == 1, { youtubeMode = 1; if (query.isNotBlank()) vm.searchYouTubePlaylists(query) }, label = { Text("Playlists") })
            } }
            item { Button(enabled = query.isNotBlank(), onClick = { if (youtubeMode == 0) vm.searchYouTube(query) else vm.searchYouTubePlaylists(query) }) {
                Text(if (youtubeMode == 0) "Search YouTube songs" else "Search YouTube playlists")
            } }
            if (vm.searchedQuery.isNotBlank()) item { Text("Results for “${vm.searchedQuery}”", style = MaterialTheme.typography.bodySmall) }
            if (youtubeMode == 0) items(vm.youtubeResults, key = { it.id }) { TrackRow(it, vm, playlists, favorites) { vm.playSelected(it) } }
            else {
                if (vm.youtubePlaylistResults.isEmpty() && vm.searchedQuery.isNotBlank() && !vm.isSearching)
                    item { EmptyState("No playlists found", "Try a different title or search for a song.") }
                items(vm.youtubePlaylistResults, key = { it.id }) { result ->
                    ListItem(headlineContent = { Text(result.title) }, supportingContent = {
                        Text(listOf(result.uploader, result.total?.let { "$it songs" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "))
                    }, trailingContent = { Text("Preview") }, modifier = Modifier.clickable { importLink = result.url })
                }
            }
        }
    }
    importLink?.let { link -> PublicPlaylistDialog(vm, initialLink = link, close = { importLink = null }, imported = { importLink = null }) }
}

@Composable private fun DiscoveryScreen(vm: EchoViewModel, playlists: List<Playlist>, favorites: Set<String>, initialTab: Int = 0) {
    LaunchedEffect(Unit) { if (!BuildConfig.STAGING && vm.newMusicResults.isEmpty()) vm.refreshDiscovery() }
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
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
    var linkImport by remember { mutableStateOf(false) }
    var fileImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val importPlaylist = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) fileImport = uri
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Your collection") }
        item { Button(enabled = !vm.busy, onClick = { linkImport = true }) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(8.dp)); Text("Import public playlist link") } }
        item { OutlinedButton(enabled = !vm.busy, onClick = { importPlaylist.launch(arrayOf("application/zip", "application/x-zip-compressed", "text/csv", "application/json", "text/plain", "application/vnd.ms-excel", "text/comma-separated-values")) }) { Text("Import Spotify data file") } }
        item { Text("Paste a public YouTube or Spotify playlist link, or choose an Exportify CSV / Spotify playlist JSON. YouTube songs can play when available; Spotify imports need recording selection.", style = MaterialTheme.typography.bodySmall) }
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
    if (linkImport) PublicPlaylistDialog(vm, initialLink = "", close = { linkImport = false }) { linkImport = false; tab = 2 }
    fileImport?.let { uri -> PublicPlaylistDialog(vm, initialLink = "", initialFile = uri, close = { fileImport = null }) { fileImport = null; tab = 2 } }
    group?.let { (title, songs) -> AlertDialog(onDismissRequest = { group = null }, title = { Text(title) }, text = { LazyColumn { items(songs) { TrackRow(it, vm, playlists, favorites) { vm.play(songs, songs.indexOf(it)); group = null } } } }, confirmButton = { TextButton(onClick = { group = null }) { Text("Close") } }) }
}

private data class PublicPlaylistPreview(val playlists: List<dev.echo.data.spotify.FilePlaylist>, val total: Long?, val spotify: Boolean, val hasMore: Boolean, val skipped: Int = 0, val archive: ByteArray? = null, val historyCount: Int = 0, val savedArtistCount: Int = 0, val savedAlbumCount: Int = 0) {
    val tracks by lazy(LazyThreadSafetyMode.NONE) { playlists.flatMap { it.tracks } }
    val complete get() = !hasMore && total != null && total == tracks.size.toLong()
}

@Composable private fun PublicPlaylistDialog(vm: EchoViewModel, initialLink: String, initialFile: android.net.Uri? = null, close: () -> Unit, imported: () -> Unit) {
    val context = LocalContext.current
    var link by rememberSaveable(initialLink) { mutableStateOf(initialLink) }
    var preview by remember { mutableStateOf<PublicPlaylistPreview?>(null) }
    var autoUpdate by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var matching by remember { mutableStateOf(false) }
    var matchProgress by remember { mutableStateOf(0 to 0) }
    var matchingSummary by remember { mutableStateOf<String?>(null) }
    val firstPageFlow = remember { kotlinx.coroutines.flow.MutableStateFlow<PublicPlaylistPreview?>(null) }
    val firstPage by firstPageFlow.collectAsStateWithLifecycle()
    val offlineSettings by vm.offlineSettings.collectAsStateWithLifecycle()
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current

    fun loadPreview() {
        preview = null; firstPageFlow.value = null; matchingSummary = null; error = null; loading = true; autoUpdate = false
        job = scope.launch {
            try {
                if (initialFile == null && offlineSettings.offlineOnly) error("Turn off Offline mode to load this playlist.")
                val draft = if (initialFile != null) {
                    val (file, archive) = vm.previewSpotifyFile(initialFile)
                    PublicPlaylistPreview(file.playlists, file.playlists.sumOf { it.tracks.size.toLong() }, true, false,
                        file.skipped, archive, file.history.size, file.savedArtists.size, file.savedAlbums.size)
                } else {
                    val spotify = runCatching { java.net.URI(link.trim()).host.equals("open.spotify.com", true) }.getOrDefault(false)
                    if (spotify) {
                        val exposed = vm.spotifyLinks.preview(link)
                        PublicPlaylistPreview(listOf(dev.echo.data.spotify.FilePlaylist(exposed.name, exposed.tracks)), exposed.total?.toLong(), true, false)
                    } else {
                        val exposed = vm.previewYouTubePlaylist(link) { first ->
                            firstPageFlow.value = PublicPlaylistPreview(listOf(dev.echo.data.spotify.FilePlaylist(first.name, first.tracks)), first.total, false, true)
                        }
                        PublicPlaylistPreview(listOf(dev.echo.data.spotify.FilePlaylist(exposed.name, exposed.tracks)), exposed.total, false, exposed.hasMore)
                    }
                }
                preview = draft
                if (draft.spotify && draft.archive == null && !offlineSettings.offlineOnly && draft.tracks.any { it.sourceUri == null }) {
                    matching = true
                    val matches = vm.matchImportedTracks(draft.tracks) { done, total -> matchProgress = done to total }
                    preview = draft.copy(playlists = draft.playlists.map { playlist ->
                        playlist.copy(tracks = playlist.tracks.map { matches[it.id] ?: it })
                    })
                    val unique = draft.tracks.filter { it.sourceUri == null }.distinctBy { it.id }.size
                    matchingSummary = "${matches.size} matched automatically; ${unique - matches.size} need manual matching." +
                        if (unique > 25) " Only the first 25 songs were searched." else ""
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { firstPageFlow.value = null; error = failure.message ?: "Playlist preview failed. Try a file export." }
            finally { firstPageFlow.value = null; matching = false; loading = false }
        }
    }
    LaunchedEffect(initialLink, initialFile) {
        if (initialFile != null) loadPreview()
        else if (initialLink.isNotBlank()) {
            if (offlineSettings.offlineOnly) error = "Turn off Offline mode to load this playlist."
            else loadPreview()
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text(if (initialFile == null) "Import public playlist link" else "Preview playlist file") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (initialFile == null) OutlinedTextField(link, { link = it; preview = null; firstPageFlow.value = null; error = null; autoUpdate = false }, enabled = !loading, label = { Text("YouTube or Spotify playlist link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (matching) Text("Matching ${matchProgress.first} / ${matchProgress.second}")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            (preview ?: firstPage)?.let { result ->
                Text(result.playlists.firstOrNull()?.name?.takeIf { result.playlists.size == 1 } ?: "${result.playlists.size} playlists", style = MaterialTheme.typography.titleMedium)
                Text(when {
                    preview == null && firstPage != null -> "${result.tracks.size} songs loaded; more songs are loading."
                    result.hasMore -> "At least ${result.tracks.size} songs found; more entries remain. Only these songs will be imported."
                    result.complete -> "${result.tracks.size} of ${result.total} songs available."
                    result.total != null -> "Only ${result.tracks.size} of ${result.total} songs are available. This imports only those ${result.tracks.size}."
                    else -> "${result.tracks.size} songs available. The full playlist count couldn't be verified; entries may be missing."
                })
                Text(if (result.spotify) "Spotify imports contain metadata only and won't sync later changes." else "YouTube playlist entries are saved as playable recording links when available.", style = MaterialTheme.typography.bodySmall)
                if (result.archive != null) {
                    Text("This ZIP contains ${result.historyCount} listening-history plays, ${result.savedArtistCount} saved artists, and ${result.savedAlbumCount} saved albums. The full archive is encrypted on this device, separate from Echo history, sync, and backups.", style = MaterialTheme.typography.bodySmall)
                    Text("Browsing is available from Settings. Imported playlists remain separate from these archive records.", style = MaterialTheme.typography.bodySmall)
                }
                if (preview != null && initialFile == null && !result.spotify && result.complete &&
                    android.net.Uri.parse(link).getQueryParameter("list")?.startsWith("RD") == false) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = autoUpdate, onCheckedChange = { autoUpdate = it })
                        Text("Automatically update while Echo is running")
                    }
                }
                if (result.skipped > 0) Text("${result.skipped} podcast/local entries skipped.", style = MaterialTheme.typography.bodySmall)
                matchingSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.heightIn(max = 220.dp)) { itemsIndexed(result.tracks) { index, track -> Text("${index + 1}. ${track.title} · ${track.artist}", modifier = Modifier.padding(vertical = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis) } }
            }
        }
    }, confirmButton = {
        TextButton(enabled = !loading && (preview != null || (initialFile == null && link.isNotBlank() && !offlineSettings.offlineOnly)), onClick = {
            keyboard?.hide(); focus.clearFocus()
            val result = preview
            if (result == null) loadPreview() else {
                loading = true
                scope.launch {
                    var archiveSaved = false
                    try {
                        result.archive?.let { dev.echo.data.spotify.SpotifyArchiveStore(context).save(it); archiveSaved = true }
                        if (autoUpdate && !result.spotify && result.complete && initialFile == null) {
                            val playlist = result.playlists.single()
                            vm.library.importLinkedYouTubePlaylist(link.trim(), playlist.name, playlist.tracks)
                            vm.reloadLinkedPlaylists()
                        } else if (result.playlists.isNotEmpty()) vm.importSpotifyFile(dev.echo.data.spotify.PlaylistFile(result.playlists, result.skipped))
                        imported()
                    }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = (if (archiveSaved) "ZIP saved locally; song import failed. " else "") + (failure.message ?: "Playlist import failed. Try a file export.") }
                    finally { loading = false }
                }
            }
        }) { Text(preview?.let { if (it.archive != null) "Save ZIP and import" else if (it.playlists.size == 1) "Import ${it.tracks.size} songs" else "Import ${it.playlists.size} playlists" } ?: "Preview playlist") }
    }, dismissButton = { Row {
        if (matching) TextButton(onClick = { job?.cancel() }) { Text("Skip matching") }
        TextButton(onClick = { job?.cancel(); close() }) { Text(if (loading) "Cancel" else "Close") }
    } })
}

@Composable private fun PlaylistScreen(vm: EchoViewModel, id: String, playlist: Playlist?, all: List<Track>, playlists: List<Playlist>, favorites: Set<String>) {
    LaunchedEffect(id, all) { vm.loadPlaylist(id) }
    var rename by remember { mutableStateOf(false) }
    var match by remember { mutableStateOf<Track?>(null) }
    var onlineMatch by remember { mutableStateOf<Pair<Int, Track>?>(null) }
    var add by remember { mutableStateOf(false) }
    val songs = if (vm.loadedPlaylistId == id) vm.playlistItems else emptyList()
    var queueMenu by remember { mutableStateOf(false) }
    val offlineSettings by vm.offlineSettings.collectAsStateWithLifecycle()
    val downloads by vm.offlineSongs.collectAsStateWithLifecycle()
    val selectedPlaybackTargetId by vm.selectedConnectedPlaybackTargetId.collectAsStateWithLifecycle()
    val firstPlayable = if (BuildConfig.STAGING && selectedPlaybackTargetId != null) songs.indices.firstOrNull() ?: -1
        else firstPlayableIndex(songs, offlineSettings.offlineOnly, downloads.filter { it.status == "saved" }.map { it.trackId }.toSet())
    LazyColumn(Modifier.testTag("playlist-feed"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Hero(playlist?.name ?: "Playlist", "${songs.size} tracks · made for your moments") }
        vm.linkedPlaylists.find { it.id == id }?.let { source -> item {
            Text("Auto-update on · Last updated ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(source.updatedAt))}", style = MaterialTheme.typography.bodySmall)
            vm.linkedPlaylistErrors[id]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row {
                TextButton(enabled = !offlineSettings.offlineOnly, onClick = { vm.refreshLinkedPlaylist(id) }) { Text("Refresh now") }
                TextButton(onClick = { vm.unlinkPlaylist(id) }) { Text("Turn off auto-update") }
            }
            TextButton(enabled = songs.isNotEmpty(), onClick = { vm.runAction("Editable copy created") {
                vm.library.importPlaylist(UUID.randomUUID().toString(), "Copy of ${(playlist?.name ?: "Playlist").take(190)}", songs)
            } }) { Text("Make editable copy") }
            Text("If you edit this playlist, Echo will keep your edits and stop replacing its songs.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.play(songs, firstPlayable) }, enabled = firstPlayable >= 0) { Icon(Icons.Default.PlayArrow, null); Text("Play") }
            FilledTonalButton(onClick = { vm.play(songs, firstPlayable, shuffle = true) }, enabled = firstPlayable >= 0) { Icon(Icons.Default.Shuffle, "Shuffle playlist") }
            IconButton(onClick = { add = true }) { Icon(Icons.Default.Add, "Add tracks") }
            IconButton(onClick = { rename = true }) { Icon(Icons.Default.Edit, "Rename") }
            playlist?.spotifyId?.let { spotifyId -> IconButton(onClick = { vm.mutatePlaylist(id) { vm.spotify.importPlaylist(spotifyId) } }) { Icon(Icons.Default.Refresh, "Refresh from Spotify; replaces playlist contents") } }
        } }
        item { OutlinedButton(enabled = firstPlayable >= 0 && !vm.busy && !offlineSettings.offlineOnly, onClick = { vm.smartShuffle(songs) }) { Text("Smart shuffle · Echo") } }
        item {
            OutlinedButton(enabled = firstPlayable >= 0 && !vm.busy, onClick = { queueMenu = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, null); Spacer(Modifier.width(8.dp)); Text("Add playlist to queue") }
        }
        item { Text("Smart shuffle uses songs related to the current recording, with one recommendation after three playlist songs. Your saved playlist stays as it is.", style = MaterialTheme.typography.bodySmall) }
        if (songs.any { it.sourceUri == null && it.provider == "spotify" }) item {
            OutlinedButton(enabled = vm.matchingProgress == null, onClick = { vm.matchPlaylistAutomatically(id) }) { Text("Auto-match recordings") }
            Text("Title, artist and duration must agree. Only unclear matches need Find recording. Cancelling keeps this playlist unchanged.", style = MaterialTheme.typography.bodySmall)
        }
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
    if (queueMenu) AlertDialog(onDismissRequest = { queueMenu = false }, title = { Text("Add playlist to queue") }, text = {
        Column {
            listOf("Playlist order" to QueueAddMode.NORMAL, "Shuffle" to QueueAddMode.SHUFFLE, "Smart shuffle" to QueueAddMode.SMART).forEach { (label, mode) ->
                TextButton(enabled = mode != QueueAddMode.SMART || !offlineSettings.offlineOnly, onClick = { queueMenu = false; vm.appendPlaylist(songs, mode) }) { Text(label) }
            }
        }
    }, confirmButton = { TextButton(onClick = { queueMenu = false }) { Text("Cancel") } })
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

@Composable private fun SettingsScreen(vm: EchoViewModel, openHistory: () -> Unit, openDownloads: () -> Unit, openLicenses: () -> Unit, openProfile: () -> Unit, openSpotifyData: () -> Unit) {
    val updateStatus by vm.updater.status.collectAsStateWithLifecycle()
    val connected by vm.spotify.connected.collectAsStateWithLifecycle()
    var sourcePriority by remember { mutableStateOf(vm.sources.priority) }
    val appearance by vm.settings.appearance.collectAsStateWithLifecycle("artwork")
    val accentName by vm.settings.guiAccent.collectAsStateWithLifecycle("violet")
    val brightness by vm.settings.brightness.collectAsStateWithLifecycle("dark")
    val keepBackground by vm.settings.keepBackground.collectAsStateWithLifecycle(true)
    val savedAccent by vm.settings.guiAccent.collectAsStateWithLifecycle("violet")
    var customAccent by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(savedAccent) { if (savedAccent.startsWith("#")) customAccent = savedAccent }
    var spotifyFileImport by remember { mutableStateOf<android.net.Uri?>(null) }
    var deleteSpotifyArchives by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val spotifyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> spotifyFileImport = uri }
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
        item { Text("Appearance", style = MaterialTheme.typography.titleMedium) }
        item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Album, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("Your Echo", style = MaterialTheme.typography.titleMedium)
                    Text("A live preview of your colors", style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
            }
        } }
        item { Text("Artwork theme follows the focused player's cover. Fixed GUI uses your chosen accent.", style = MaterialTheme.typography.bodySmall) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("artwork" to "Artwork", "gui" to "Fixed GUI").forEach { (value, label) ->
                FilterChip(appearance == value, onClick = { vm.runAction { vm.settings.setAppearance(value) } }, label = { Text(label) }, modifier = Modifier.testTag("appearance-$value"))
            }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("dark" to "Dark", "light" to "Light").forEach { (value, label) ->
                FilterChip(brightness == value, onClick = { vm.runAction { vm.settings.setBrightness(value) } }, label = { Text(label) }, modifier = Modifier.testTag("brightness-$value"))
            }
        } }
        if (appearance == "gui") item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("violet" to "Violet", "teal" to "Teal", "rose" to "Rose", "amber" to "Amber").forEach { (value, label) ->
                FilterChip(accentName == value, onClick = { vm.runAction { vm.settings.setGuiAccent(value) } }, label = { Text(label) }, modifier = Modifier.testTag("accent-$value"))
            }
        } }
        if (appearance == "gui") item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(customAccent, { customAccent = it.take(7) }, label = { Text("Custom hex color") }, singleLine = true, modifier = Modifier.weight(1f).testTag("custom-accent"))
            Button(enabled = customAccent.matches(Regex("#[0-9a-fA-F]{6}")), onClick = { vm.runAction { vm.settings.setGuiAccent(customAccent) } }) { Text("Save") }
        } }
        item { OutlinedButton(enabled = vm.current != null && vm.controller?.isCommandAvailable(Player.COMMAND_STOP) == true,
            onClick = { vm.controller?.stop(); vm.message = "Playback stopped. The foreground playback session stays available for background and connected controls." }, modifier = Modifier.testTag("stop-playback")) { Text("Stop playback") } }
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep Echo available after closing the app")
                Text("When off, Echo stops after playback pauses or ends and the app is closed. Locking the screen does not stop playback.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = keepBackground, onCheckedChange = { value -> vm.runAction { vm.settings.setKeepBackground(value) } })
        } }
        item { OutlinedButton(onClick = openProfile) { Text("Profile & Google account") } }
        item { OutlinedButton(onClick = openHistory) { Text("Listening history") } }
        item { Text("Echo Music ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedButton(onClick = openLicenses) { Text("Licenses & source") } }
        item { OutlinedButton(enabled = updateStatus !is UpdateStatus.Checking && updateStatus !is UpdateStatus.Downloading, onClick = vm::checkUpdates) { Text(if (updateStatus is UpdateStatus.Checking) "Checking for updates…" else "Check for updates") } }
        if (updateStatus is UpdateStatus.Downloading) item {
            val downloading = updateStatus as UpdateStatus.Downloading
            Text("Update download: ${downloading.percent}%")
            TextButton(onClick = vm::cancelUpdate) { Text("Cancel update download") }
        }
        item { Text("Playlist imports", style = MaterialTheme.typography.titleMedium) }
        item { Text("Import from Library using a public YouTube or Spotify playlist link, or Spotify CSV/JSON. Link imports preview available counts first. Spotify entries have no audio and may need a recording choice; links never sync automatically.", style = MaterialTheme.typography.bodySmall) }
        item { HorizontalDivider(); Text("Spotify data", style = MaterialTheme.typography.titleMedium) }
        item { Text("Import a Spotify account-data ZIP to keep an encrypted local copy and browse its listening history, saved artists, and saved albums.", style = MaterialTheme.typography.bodySmall) }
        item { OutlinedButton(onClick = openSpotifyData) { Text("Browse saved Spotify exports") } }
        item { OutlinedButton(onClick = { spotifyPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/json", "text/plain", "text/csv")) }) { Text("Import Spotify data") } }
        item { TextButton(onClick = { deleteSpotifyArchives = true }) { Text("Delete saved Spotify ZIPs") } }
        if (connected) item { OutlinedButton(onClick = { vm.runAction("Disconnected") { vm.spotify.disconnect() } }) { Text("Disconnect previous Spotify session") } }
        item { HorizontalDivider(); SectionTitle("Playback & storage") }
        item { OfflineSettingsSection(vm) }
        item { OutlinedButton(onClick = openDownloads) { Text("Downloads & cache") } }
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
        item { Text(if (BuildConfig.STAGING) "Echo Stage uses an isolated demo library and simulated players.\nDark theme · Android Auto browsing through Media3." else "Original source quality · normal speed\nDownloads and listening history stay on this device.\nPlaylists can sync privately with your Google account.\nDark theme · Android Auto browsing through Media3.") }
        item { OutlinedButton(onClick = { vm.runAction("Artwork cache cleared") { coil.Coil.imageLoader(context).memoryCache?.clear(); coil.Coil.imageLoader(context).diskCache?.clear() } }) { Text("Clear artwork cache") } }
        item { Text("Android Auto needs a compatible host and Desktop Head Unit for validation. See docs/ARCHITECTURE.md in the project.", style = MaterialTheme.typography.bodySmall) }
    }
    spotifyFileImport?.let { uri -> PublicPlaylistDialog(vm, initialLink = "", initialFile = uri,
        close = { spotifyFileImport = null }, imported = { spotifyFileImport = null }) }
    if (deleteSpotifyArchives) AlertDialog(onDismissRequest = { deleteSpotifyArchives = false }, title = { Text("Delete saved Spotify ZIPs?") },
        text = { Text("This deletes the encrypted Spotify export copies kept by Echo on this device. It does not delete imported playlists or Echo listening history.") },
        confirmButton = { TextButton(onClick = { deleteSpotifyArchives = false; vm.runAction("Saved Spotify ZIPs deleted") { dev.echo.data.spotify.SpotifyArchiveStore(context).deleteAll() } }) { Text("Delete ZIPs") } },
        dismissButton = { TextButton(onClick = { deleteSpotifyArchives = false }) { Text("Cancel") } })
}

@Composable private fun NameDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { save(name.trim()) }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
private fun time(milliseconds: Long): String = "%d:%02d".format(milliseconds / 60_000, milliseconds / 1000 % 60)
private fun openLink(context: android.content.Context, url: String) { if (Uri.parse(url).scheme == "https") context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
