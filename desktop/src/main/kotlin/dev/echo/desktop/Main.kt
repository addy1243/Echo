package dev.echo.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.*
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.echo.core.Track
import dev.echo.core.connected.EngineCapture
import dev.echo.core.connected.QueueOccurrence
import dev.echo.core.connected.portableTransferQueue
import dev.echo.desktop.DesktopLibrary.Companion.localTrack
import dev.echo.data.provider.YouTubeProvider
import dev.echo.data.provider.YouTubePlaylistSearchResult
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedPlaybackAction
import dev.echo.data.firebase.GroupQueueOperationV2Status
import dev.echo.data.firebase.QueueOperationV2Status
import dev.echo.core.connected.MAX_CONNECTED_GROUP_MEMBERS
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.awt.Dimension
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

private val DarkCanvas = Color(0xFF0C1016)
private val DarkPanel = Color(0xFF141B24)
private val DefaultAccent = Color(0xFF83E5CB)
private val GroupAccent = Color(0xFFC7A6FF)

private val Canvas: Color
    @Composable get() = MaterialTheme.colorScheme.background
private val Panel: Color
    @Composable get() = MaterialTheme.colorScheme.surface
private val Muted: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Accent: Color
    @Composable get() = MaterialTheme.colorScheme.primary

private data class DesktopGroupSeekTarget(
    val ref: dev.echo.core.connected.GroupRef,
    val timelineSequence: Long,
    val occurrenceId: String,
)

private data class DesktopSidecarPlayer(
    val device: ConnectedDevice?,
    val label: String,
    val presentation: DesktopPlayerPresentationState,
    val selected: Boolean,
    val canSelect: Boolean,
    val hasRecording: Boolean,
)

private fun playerDeviceName(label: String) = label.removePrefix("Echo · ").trim().ifBlank { "Player" }

private fun playerTabName(label: String) = label.removePrefix("Echo · ").replace(" · Demo ", " ").trim().ifBlank { label }

internal fun desktopDataDirectory(stagingMode: Boolean, localAppData: String?, userHome: String,
    stagingOverride: String? = null): java.nio.file.Path {
    val base = java.nio.file.Path.of(localAppData ?: userHome)
    return if (stagingMode) java.nio.file.Path.of(stagingOverride ?: base.resolve("EchoMusicStage").toString())
        else base.resolve("EchoMusic")
}

internal fun launchDesktop(stagingMode: Boolean) = application {
    val dataDirectory = remember(stagingMode) {
        desktopDataDirectory(stagingMode, System.getenv("LOCALAPPDATA"), System.getProperty("user.home"),
            System.getProperty("echo.staging.data"))
    }
    val libraryFile = remember(stagingMode, dataDirectory) {
        if (!stagingMode) System.getProperty("echo.desktop.library")?.let { java.nio.file.Path.of(it) }
            ?: dataDirectory.resolve("library.json")
        else dataDirectory.resolve("library.json")
    }
    var windowVisible by remember { mutableStateOf(true) }
    val windowState = rememberWindowState(placement = WindowPlacement.Maximized, width = 1280.dp, height = 760.dp)
    val showWindow = { windowState.isMinimized = false; windowVisible = true }
    var activation by remember { mutableIntStateOf(0) }
    val singleInstance = remember(libraryFile, dataDirectory, stagingMode) {
        DesktopSingleInstance.acquire(libraryFile, dataDirectory.resolve("instances"),
            if (stagingMode) "stage" else "production") { showWindow(); activation++ }
    }
    DisposableEffect(singleInstance) { onDispose { singleInstance?.close() } }
    if (singleInstance == null) {
        LaunchedEffect(Unit) { exitApplication() }
        return@application
    }
    val preferences = remember(dataDirectory) { DesktopPreferences(dataDirectory.resolve("appearance.json"), dataDirectory.resolve("preferences.json")) }
    var artworkUri by remember { mutableStateOf<String?>(null) }
    val startup = remember(libraryFile) { runCatching {
        DesktopLibrary(libraryFile)
    } }
    val store = startup.getOrNull()
    val provider = remember { YouTubeProvider() }
    val offline = remember(dataDirectory) { DesktopOfflineRepository(provider, dataDirectory.resolve("offline-audio")) }
    val player = remember { store?.let { DesktopPlayback(provider, offline,
        recoveryFile = it.file.resolveSibling("playback-v2-recovery.json").toFile(),
        findLocalTrack = { key -> it.snapshot().tracks.firstOrNull { track -> safeEngineRecordingId(track) == key } }) { track, event, listened ->
        it.recordHistory(track, event, listened, track.durationMs)
    } } }
    val quit = { player?.close(); offline.close(); exitApplication() }
    Tray(icon = painterResource("echo-icon.png"), tooltip = "Echo Music", onAction = showWindow) {
        Item("Open Echo Music", onClick = showWindow)
        Separator()
        Item("Quit Echo Music", onClick = quit)
    }
    Window(visible = windowVisible, onCloseRequest = { if (preferences.closeToTray) windowVisible = false else quit() },
        title = if (stagingMode) "Echo Music · Staging" else "Echo Music", icon = painterResource("echo-icon.png"),
        state = windowState) {
        LaunchedEffect(activation) {
            window.minimumSize = Dimension(1060, 650)
            if (activation > 0) { window.toFront(); window.requestFocus() }
        }
        val artworkAccent by produceState<Color?>(null, artworkUri) { value = desktopArtworkAccent(artworkUri) }
        val accent = preferences.customAccent.toDesktopColor() ?: artworkAccent ?: DefaultAccent
        val light = preferences.themeMode == DesktopThemeMode.LIGHT
        var readableAccent = accent
        repeat(12) {
            if (light && readableAccent.luminance() > .18f) readableAccent = androidx.compose.ui.graphics.lerp(readableAccent, Color.Black, .12f)
            if (!light && readableAccent.luminance() < .36f) readableAccent = androidx.compose.ui.graphics.lerp(readableAccent, Color.White, .12f)
        }
        val colors = if (light) lightColorScheme(primary = readableAccent, onPrimary = Color.White,
            background = androidx.compose.ui.graphics.lerp(Color.White, accent, .04f), surface = androidx.compose.ui.graphics.lerp(Color.White, accent, .07f))
            else darkColorScheme(primary = readableAccent, onPrimary = Color.Black,
                background = androidx.compose.ui.graphics.lerp(DarkCanvas, accent, .04f), surface = androidx.compose.ui.graphics.lerp(DarkPanel, accent, .06f), onSurface = Color(0xFFEAF0F7))
        MaterialTheme(colorScheme = colors) {
            if (store != null && player != null) DesktopApp(store, provider, player, offline, stagingMode, preferences,
                desktopSpotifyArchiveDirectory(dataDirectory), dataDirectory.resolve("google-account.dpapi"), { artworkUri = it })
            else Surface(Modifier.fillMaxSize(), color = Canvas) {
                Column(Modifier.padding(60.dp), verticalArrangement = Arrangement.Center) {
                    Text("Your library needs attention", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Echo could not read its saved library. The original file has been preserved.\n\n" +
                        (startup.exceptionOrNull()?.message ?: "Unknown library error") +
                        "\n\nLibrary: " + (System.getProperty("echo.desktop.library") ?: "${System.getenv("LOCALAPPDATA")}/EchoMusic/library.json"),
                        modifier = Modifier.padding(vertical = 24.dp))
                    Button(onClick = quit) { Text("Quit Echo Music") }
                }
            }
        }
    }
}

fun main() = launchDesktop(stagingMode = false)

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun DesktopApp(store: DesktopLibrary, provider: YouTubeProvider, player: DesktopPlayback,
    offline: DesktopOfflineRepository, stagingMode: Boolean, preferences: DesktopPreferences,
    spotifyArchiveDirectory: java.nio.file.Path, accountFile: java.nio.file.Path, onArtwork: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val updater = remember { DesktopUpdater() }
    val update by updater.state.collectAsState()
    var showUpdates by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!stagingMode) updater.check(manual = false) }
    LaunchedEffect(update.phase) {
        if (update.phase in setOf(DesktopUpdatePhase.AVAILABLE, DesktopUpdatePhase.READY)) showUpdates = true
    }
    DisposableEffect(updater) { onDispose { updater.close() } }
    val google = remember(accountFile) { DesktopGoogleAccount(file = accountFile) }
    val account by google.state.collectAsState()
    val stageSimulator = remember(stagingMode) { if (stagingMode) StageConnectedPlayback() else null }
    val connectedPlayback: DesktopConnectedPlaybackBackend = remember(stagingMode) {
        stageSimulator ?: DesktopConnectedPlayback(store, google, player)
    }
    val connectedState by connectedPlayback.state.collectAsState()
    val connectedV2 by connectedPlayback.v2State.collectAsState()
    var selectedRemoteId by remember { mutableStateOf<String?>(null) }
    var lastSelectedRemote by remember { mutableStateOf<ConnectedDevice?>(null) }
    var lastConnectedAccount by remember { mutableStateOf(account.email) }
    LaunchedEffect(google, connectedPlayback, stagingMode) {
        if (!stagingMode) google.state.map { it.email }.distinctUntilChanged().collectLatest { email ->
            if (email == null || !dev.echo.data.firebase.EchoFirebaseProject.configured) connectedPlayback.stopGracefully()
            else withContext(Dispatchers.IO) { connectedPlayback.run() }
        }
    }
    LaunchedEffect(account.email) {
        if (lastConnectedAccount != account.email) {
            lastConnectedAccount = account.email
            selectedRemoteId = null
            lastSelectedRemote = null
        }
    }
    LaunchedEffect(connectedV2.group?.ref) {
        if (connectedV2.group == null) {
            selectedRemoteId = null
            lastSelectedRemote = null
        }
    }
    LaunchedEffect(connectedV2.group?.members, selectedRemoteId) {
        if (selectedRemoteId != null && connectedV2.group?.members?.any {
                it.deviceId == selectedRemoteId && it.status != dev.echo.data.firebase.GroupMemberV2Status.LEFT
            } == true) {
            selectedRemoteId = null
            lastSelectedRemote = null
        }
    }
    LaunchedEffect(selectedRemoteId, connectedState.devices) {
        val selectedSession = lastSelectedRemote?.sessionId
        selectedRemoteId?.let { id -> connectedState.devices.firstOrNull {
            it.id == id && (selectedSession == null || it.sessionId == selectedSession)
        }?.let { lastSelectedRemote = it } }
    }
    DisposableEffect(connectedPlayback) { onDispose { connectedPlayback.stop() } }
    val firebase = remember { DesktopFirebaseSync(store, google) }
    val cloudSync by firebase.state.collectAsState()
    var accountJob by remember { mutableStateOf<Job?>(null) }
    var automaticSync by remember { mutableStateOf(false) }
    var confirmCloudSync by remember { mutableStateOf(false) }
    val playbackFlow = remember(player, stageSimulator) { stageSimulator?.localPlayback ?: player.state }
    val playback by playbackFlow.collectAsState()
    val downloads by offline.state.collectAsState()
    var library by remember { mutableStateOf(store.snapshot()) }
    var showTutorial by remember { mutableStateOf(!preferences.tutorialCompleted) }
    var page by remember { mutableStateOf("Home") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Track>()) }
    var playlistResults by remember { mutableStateOf(emptyList<YouTubePlaylistSearchResult>()) }
    var playlistSearch by remember { mutableStateOf(false) }
    var importInitialLink by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchGeneration by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var linkedErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val linkedMutex = remember { kotlinx.coroutines.sync.Mutex() }
    suspend fun refreshLinked(id: String) {
        linkedMutex.lock()
        try {
            check(!downloads.offlineOnly) { "Turn off Offline mode to refresh this playlist" }
            refreshDesktopLinkedPlaylist(store, provider, id)
            library = withContext(Dispatchers.IO) { store.snapshot() }
            linkedErrors = linkedErrors - id
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { linkedErrors = linkedErrors + (id to (failure.message ?: "Refresh failed; the saved copy was kept")) }
        finally { linkedMutex.unlock() }
    }
    LaunchedEffect(store, stagingMode, downloads.offlineOnly) {
        if (!stagingMode) while (true) {
            if (!downloads.offlineOnly) {
                val snapshot = withContext(Dispatchers.IO) { store.snapshot() }
                snapshot.linkedPlaylists.filter { (id, source) -> snapshot.playlists.any { it.id == id } &&
                    System.currentTimeMillis() - source.updatedAt >= 6 * 60 * 60 * 1000L }.keys.forEach { refreshLinked(it) }
            }
            delay(30 * 60 * 1000L)
        }
    }
    var newPlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var addTrack by remember { mutableStateOf<Track?>(null) }
    var showDevices by remember { mutableStateOf(false) }
    var showStageAdmin by remember { mutableStateOf(false) }
    var fullPlayerOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    val fullPlayerVisibility = remember { MutableTransitionState(false) }
    val collapsedPlayerHeight = with(LocalDensity.current) { 90.dp.roundToPx() }
    SideEffect { fullPlayerVisibility.targetState = fullPlayerOpen }
    val fullPlayerOnScreen = fullPlayerOpen || fullPlayerVisibility.currentState ||
        fullPlayerVisibility.targetState || !fullPlayerVisibility.isIdle
    var smartJob by remember { mutableStateOf<Job?>(null) }
    var smartBusy by remember { mutableStateOf(false) }
    var recommendedIds by remember { mutableStateOf(emptySet<String>()) }
    var renamePlaylist by remember { mutableStateOf<DesktopPlaylist?>(null) }
    var deletePlaylist by remember { mutableStateOf<DesktopPlaylist?>(null) }
    var clearHistory by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var importInitialFilePicker by remember { mutableStateOf(false) }
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
        if (!stagingMode && account.email != null && automaticSync) while (true) {
            if (!google.state.value.busy) {
                firebase.synchronize()
                library = withContext(Dispatchers.IO) { store.snapshot() }
            }
            delay(60_000)
        }
    }
    fun search(forceSongs: Boolean = false) {
        if (query.isBlank()) return
        val term = query.trim()
        val searchPlaylists = playlistSearch && !forceSongs
        page = "Search"
        searchJob?.cancel()
        val ticket = ++searchGeneration
        results = emptyList()
        playlistResults = emptyList()
        searchJob = scope.launch {
            searching = true
            try {
                if (searchPlaylists) {
                    val found = provider.searchPlaylists(term)
                    if (ticket == searchGeneration) playlistResults = found
                } else {
                    val found = provider.search(term)
                    if (ticket == searchGeneration) results = found
                }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == searchGeneration) { notice = e.message ?: "Search failed"; results = emptyList(); playlistResults = emptyList() } }
            finally { if (ticket == searchGeneration) searching = false }
        }
    }
    LaunchedEffect(playback.occurrence, playback.playing, page) {
        library = withContext(Dispatchers.IO) {
            if (stagingMode && stageSimulator != null)
                seedStageLibraryIfEmpty(store, stageSimulator.tracksForPresentation())
            else store.snapshot()
        }
    }
    val selectedPlaylist = library.playlists.find { "playlist:${it.id}" == page }
    val trackMap = (library.tracks + stageSimulator?.tracksForPresentation().orEmpty()).associateBy { it.id }
    val selectedRemote = selectedRemotePlayer(selectedRemoteId, connectedState.devices, lastSelectedRemote)
    val remotePlayer = selectedRemote?.device
    val selectedIsGroupMember = selectedRemoteId != null && connectedV2.group?.members?.any {
        it.deviceId == selectedRemoteId && it.status != dev.echo.data.firebase.GroupMemberV2Status.LEFT
    } == true
    val remoteFocused = selectedRemoteId != null && !selectedIsGroupMember
    val focusedGroup = connectedV2.group.takeIf { !remoteFocused }
    val focusedGroupSeekTarget = focusedGroup?.timeline?.let { timeline ->
        timeline.queue.entries.getOrNull(timeline.cursor)?.let { occurrence ->
            DesktopGroupSeekTarget(focusedGroup.ref, timeline.sequence, occurrence.id)
        }
    }
    val ownDeviceId = connectedPlayback.deviceId
    val currentFocusedRemoteId = rememberUpdatedState(selectedRemoteId.takeIf { remoteFocused })
    val currentFocusedGroupRef = rememberUpdatedState(focusedGroup?.ref)
    val remoteLive = remoteFocused && selectedRemote != null && !selectedRemote.reconnecting && remotePlayer?.controlEnabled == true &&
        (stagingMode || account.email != null) && !connectedState.pending
    val presentation = desktopPlayerPresentation(playback, selectedRemote.takeIf { remoteFocused }, focusedGroup, trackMap, ownDeviceId)
    LaunchedEffect(presentation.track?.artworkUri) { onArtwork(presentation.track?.artworkUri) }
    val presentedTrack = presentation.track
    val presentedTitle = presentation.title
    val presentedArtist = presentation.artist
    val presentedPlaying = presentation.playing
    val presentedPosition = presentation.positionMs
    val presentedDuration = presentation.durationMs
    val otherPlayers = connectedState.devices.filter { it.id != ownDeviceId }.let { devices ->
        if (lastSelectedRemote != null && devices.none { it.id == lastSelectedRemote!!.id })
            devices + lastSelectedRemote!!.copy(controlEnabled = false) else devices
    }
    val groupMemberIds = connectedV2.group?.members?.filter { it.status != dev.echo.data.firebase.GroupMemberV2Status.LEFT }
        ?.map { it.deviceId }?.toSet().orEmpty()
    val focusDevices = otherPlayers.filter { it.controlEnabled && it.id !in groupMemberIds }.sortedBy { it.label.lowercase() }
    val onlinePlayers = focusDevices.filter { it.id != selectedRemoteId }
    val deckDevices = buildList {
        addAll(focusDevices)
        selectedRemote?.device?.takeIf { remoteFocused && focusDevices.none { online ->
            online.id == it.id && online.sessionId == it.sessionId
        } }?.let(::add)
    }
    val sidecarPlayers = independentPlayerDeckOrder(
        deckDevices, selectedRemoteId.takeIf { remoteFocused }, includeLocal = connectedV2.group == null,
        selectedRemoteSessionId = selectedRemote?.device?.sessionId.takeIf { remoteFocused },
    ).map { device ->
        val selected = when {
            focusedGroup != null -> false
            remoteFocused -> device?.id == remotePlayer?.id && device?.sessionId == remotePlayer?.sessionId
            else -> device == null
        }
        val reconnecting = device != null && selectedRemote?.let {
            remoteFocused && it.device.id == device.id && it.device.sessionId == device.sessionId && it.reconnecting
        } == true
        DesktopSidecarPlayer(
            device = device,
            label = device?.let { playerDeviceName(it.label) } ?: "This laptop",
            presentation = desktopPlayerPresentation(
                playback, device?.let { SelectedRemotePlayer(it, reconnecting || !it.controlEnabled) },
                null, trackMap, ownDeviceId,
            ),
            selected = selected,
            canSelect = device == null || device.controlEnabled,
            hasRecording = device?.player?.trackId != null || (device == null && playback.current != null),
        )
    }
    val offlinePlayersBase = otherPlayers.filterNot { it.controlEnabled || it.id == selectedRemoteId || it.id in groupMemberIds }
    val offlinePlayers = offlinePlayersBase + listOfNotNull(selectedRemote?.takeIf { it.reconnecting }?.device?.takeIf {
        offlinePlayersBase.none { offline -> offline.id == it.id }
    })
    val localStatus = when {
        playback.current == null -> "Ready"
        playback.ended -> "Ready"
        playback.retrying -> "Retrying · ${playback.current?.title.orEmpty()}"
        playback.playing -> "Playing · ${playback.current?.title.orEmpty()}"
        else -> "Paused · ${playback.current?.title.orEmpty()}"
    }
    val connected = stagingMode || (account.email != null && dev.echo.data.firebase.EchoFirebaseProject.configured)
    val canSyncGroup = connectedV2.group?.let { group ->
        group.status == dev.echo.data.firebase.GroupV2Status.ACTIVE && !connectedState.pending &&
            group.members.all { member -> member.status in setOf(
                dev.echo.data.firebase.GroupMemberV2Status.PLAYING,
                dev.echo.data.firebase.GroupMemberV2Status.PAUSED,
            ) && (member.deviceId == ownDeviceId || connectedV2.devices.any { device ->
                device.id == member.deviceId && device.sessionId == member.sessionId && "sync" in device.capabilities
            }) }
    } == true
    val moveTo: (String) -> Unit = { id -> scope.launch {
        runCatching { connectedPlayback.moveTo(id, true) }.onFailure { notice = it.message }
    } }
    val startTogether: (List<String>, Map<String, String>, Boolean) -> Unit = { ids, sessions, replace -> scope.launch {
        runCatching { connectedPlayback.startTogether(ids, replacePlaying = replace, expectedTargetSessions = sessions) }
            .onFailure { notice = it.message }
    } }
    val restorePrevious: () -> Unit = { scope.launch {
        runCatching { connectedPlayback.restoreLatestDisplacedPaused() }.onFailure { notice = it.message }
    } }
    val requestGroupAction: (dev.echo.data.firebase.GroupActionV2) -> Unit = { action -> scope.launch {
        runCatching { connectedPlayback.requestGroup(action) }.onFailure { notice = it.message }
    } }
    fun focusPlayer(device: ConnectedDevice?) {
        if (device == null) {
            selectedRemoteId = null
            lastSelectedRemote = null
            return
        }
        val fresh = connectedState.devices.firstOrNull {
            it.id == device.id && it.sessionId == device.sessionId && it.controlEnabled
        }
        if (fresh == null) {
            notice = "${device.label} changed connection. Choose it again from Devices."
            return
        }
        selectedRemoteId = fresh.id
        lastSelectedRemote = fresh
    }
    val playerPicker: @Composable (Boolean) -> Unit = { showCrossDeviceActions ->
        DesktopPlayerPicker(
            stagingMode = stagingMode,
            showCrossDeviceActions = showCrossDeviceActions,
            accessibleLabel = when {
                focusedGroup != null -> "Choose a device or shared player; current focus is Play together"
                remoteFocused -> "Choose a device; current focus is ${selectedRemote?.device?.label ?: "selected player"}"
                else -> "Choose a device; current focus is This laptop"
            },
            remoteMode = remoteFocused, selectedRemoteId = selectedRemoteId.takeIf { remoteFocused },
            connected = connected, connectedMessage = connectedState.message, devices = otherPlayers,
            connectedV2 = connectedV2, ownDeviceId = ownDeviceId,
            displacedMoveOperationId = playback.displacedMoveOperationId,
            onMoveV2 = moveTo, onTogetherV2 = startTogether, onRestorePrevious = restorePrevious,
            onGroupAction = requestGroupAction, onSyncGroup = { requestGroupAction(dev.echo.data.firebase.GroupActionV2.SYNC) },
            canSyncGroup = canSyncGroup, online = onlinePlayers, offline = offlinePlayers,
            localStatus = localStatus, commandPending = connectedState.pending,
            selectedDevice = selectedRemote?.device,
            onSelectLocal = { selectedRemoteId = null },
            onSelectRemote = ::focusPlayer,
        )
    }
    fun sendRemoteCommand(target: ConnectedDevice, action: ConnectedPlaybackAction, position: Long = 0L) {
        val fresh = connectedState.devices.firstOrNull { it.id == target.id && it.controlEnabled }
        if (!remoteLive || selectedRemoteId != target.id || fresh == null || fresh.sessionId != target.sessionId ||
            fresh.player.trackRevision != target.player.trackRevision || fresh.player.queueRevision != target.player.queueRevision) return
        scope.launch {
            try { connectedPlayback.sendCommand(fresh, action, position) }
            catch (_: Exception) { notice = "The remote command could not be sent. Check the device and account connection." }
        }
    }
    fun focusedPlaybackRoute() = desktopFocusedPlaybackRoute(
        remoteFocused = remoteFocused, remoteAvailable = remoteLive,
        groupFocused = focusedGroup != null, stagingMode = stagingMode,
    )
    fun previousFocusedPlayback() {
        when (focusedPlaybackRoute()) {
            DesktopFocusedPlaybackRoute.GROUP -> scope.launch { runCatching { connectedPlayback.requestGroup(dev.echo.data.firebase.GroupActionV2.PREVIOUS) } }
            DesktopFocusedPlaybackRoute.REMOTE -> remotePlayer?.let { sendRemoteCommand(it, ConnectedPlaybackAction.PREVIOUS) }
            DesktopFocusedPlaybackRoute.STAGE -> stageSimulator?.localCommand(ConnectedPlaybackAction.PREVIOUS)
            DesktopFocusedPlaybackRoute.LOCAL -> player.previous()
            DesktopFocusedPlaybackRoute.UNAVAILABLE -> Unit
        }
    }
    fun toggleFocusedPlayback() {
        when (focusedPlaybackRoute()) {
            DesktopFocusedPlaybackRoute.GROUP -> {
                val group = focusedGroup ?: return
                scope.launch { runCatching {
                    connectedPlayback.requestGroup(if (group.timeline?.playing == true) dev.echo.data.firebase.GroupActionV2.PAUSE else dev.echo.data.firebase.GroupActionV2.PLAY)
                } }
            }
            DesktopFocusedPlaybackRoute.REMOTE -> remotePlayer?.let { sendRemoteCommand(it,
                if (it.player.isPlaying) ConnectedPlaybackAction.PAUSE else ConnectedPlaybackAction.PLAY) }
            DesktopFocusedPlaybackRoute.STAGE -> stageSimulator?.localCommand(
                if (playback.playing) ConnectedPlaybackAction.PAUSE else ConnectedPlaybackAction.PLAY)
            DesktopFocusedPlaybackRoute.LOCAL -> player.toggle()
            DesktopFocusedPlaybackRoute.UNAVAILABLE -> Unit
        }
    }
    fun nextFocusedPlayback() {
        when (focusedPlaybackRoute()) {
            DesktopFocusedPlaybackRoute.GROUP -> scope.launch { runCatching { connectedPlayback.requestGroup(dev.echo.data.firebase.GroupActionV2.NEXT) } }
            DesktopFocusedPlaybackRoute.REMOTE -> remotePlayer?.let { sendRemoteCommand(it, ConnectedPlaybackAction.NEXT) }
            DesktopFocusedPlaybackRoute.STAGE -> stageSimulator?.localCommand(ConnectedPlaybackAction.NEXT)
            DesktopFocusedPlaybackRoute.LOCAL -> player.next()
            DesktopFocusedPlaybackRoute.UNAVAILABLE -> Unit
        }
    }
    fun seekFocusedPlayback(positionMs: Long) {
        when (focusedPlaybackRoute()) {
            DesktopFocusedPlaybackRoute.GROUP -> scope.launch { runCatching { connectedPlayback.requestGroup(dev.echo.data.firebase.GroupActionV2.SEEK, positionMs) } }
            DesktopFocusedPlaybackRoute.REMOTE -> remotePlayer?.let { sendRemoteCommand(it, ConnectedPlaybackAction.SEEK, positionMs) }
            DesktopFocusedPlaybackRoute.STAGE -> stageSimulator?.localCommand(ConnectedPlaybackAction.SEEK, positionMs)
            DesktopFocusedPlaybackRoute.LOCAL -> player.seek(positionMs)
            DesktopFocusedPlaybackRoute.UNAVAILABLE -> Unit
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
    fun dispatchPortableQueue(items: List<Track>, index: Int, selectedOnly: Boolean = false): Boolean {
        val focusedRemoteId = currentFocusedRemoteId.value
        val group = connectedV2.group.takeIf { focusedRemoteId == null }
        if (group == null && focusedRemoteId == null) return false
        val focusedRemoteSession = remotePlayer?.sessionId
        val groupSequence = group?.timelineSequence
        val selected = items.getOrNull(index) ?: run { notice = "Choose a song first"; return true }
        val routedItems = if (selectedOnly) listOf(selected) else items
        val routedIndex = if (selectedOnly) 0 else index
        if (stageSimulator != null) {
            try {
                if (group != null) {
                    require(connectedPlayback.v2State.value.group?.timelineSequence == groupSequence) {
                        "The demo shared queue changed; select the song again."
                    }
                    stageSimulator.replaceDemoGroupQueue(group.ref, requireNotNull(groupSequence), routedItems, routedIndex)
                } else {
                    val session = focusedRemoteSession ?: error("Select a demo device again.")
                    stageSimulator.playTracksOnDemo(focusedRemoteId!!, session, routedItems, routedIndex)
                }
            } catch (error: Exception) { notice = error.message ?: "The demo player could not start this queue." }
            return true
        }
        val queue = try { portableTransferQueue(routedItems, routedIndex) }
        catch (error: Exception) { notice = error.message ?: "This queue cannot be sent to another Echo player"; return true }
        scope.launch {
            try {
                val latest = connectedPlayback.v2State.value
                require(currentFocusedRemoteId.value == focusedRemoteId && currentFocusedGroupRef.value == group?.ref &&
                    latest.group?.takeIf { focusedRemoteId == null }?.ref == group?.ref) {
                    "Player focus changed while preparing this queue. Select the song again."
                }
                if (group != null) {
                    require(latest.group?.timelineSequence == groupSequence) { "The shared queue changed; select the song again." }
                    val result = connectedPlayback.replaceGroupQueue(group.ref, requireNotNull(groupSequence), queue)
                    when (result.status) {
                        GroupQueueOperationV2Status.COMPLETE -> Unit
                        GroupQueueOperationV2Status.PARTIAL, GroupQueueOperationV2Status.UNKNOWN,
                        GroupQueueOperationV2Status.COMMITTED -> notice = "The shared queue change needs reconciliation. Check every Play together device before continuing."
                        else -> notice = result.reason ?: "The shared queue was not changed."
                    }
                } else {
                    val target = latest.devices.firstOrNull { it.id == focusedRemoteId && it.enabled &&
                        it.sessionId == focusedRemoteSession }
                        ?: error("The selected device restarted or is reconnecting. Select it again.")
                    val result = connectedPlayback.playQueueOn(target.id, target.sessionId, target.snapshot.fence, queue)
                    when (result.status) {
                        QueueOperationV2Status.COMPLETE -> Unit
                        QueueOperationV2Status.UNKNOWN -> notice = "The device did not confirm playback. Check its current queue before retrying."
                        else -> notice = result.reason ?: "The selected device could not start this queue."
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notice = error.message ?: "The focused player could not start this queue" }
        }
        return true
    }
    fun play(items: List<Track>, index: Int, selectedOnly: Boolean = false) {
        val track = items.getOrNull(index) ?: return
        if (dispatchPortableQueue(items, index, selectedOnly)) return
        if (stageSimulator != null) {
            stageSimulator.playLocal(items, index)
            return
        }
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
    fun changeFocusedQueue(items: List<Track>, playNext: Boolean = false) {
        if (currentFocusedRemoteId.value == null && focusedGroup == null) {
            if (stageSimulator != null) stageSimulator.appendLocal(items, next = playNext)
            else player.append(items.filter(::desktopPlayable), next = playNext)
            return
        }
        notice = "Queue append and Play next cannot preserve playback on a connected player yet. Use Play to replace its queue."
    }
    fun smartShuffle(items: List<Track>) {
        if (smartJob?.isActive == true) return
        val focusedRemoteId = currentFocusedRemoteId.value
        val focusedRemoteSession = lastSelectedRemote?.sessionId
        val focusedGroup = currentFocusedGroupRef.value
        val routed = focusedRemoteId != null || focusedGroup != null
        val captured = playback
        val original = items.filter(::desktopPlayable)
        val playlist = selectedPlaylist
        val retained = captured.current.takeIf { !routed && captured.canRetainCurrent }
        smartJob = scope.launch {
            smartBusy = true
            try {
                val snapshot = withContext(Dispatchers.IO) { store.snapshot() }
                val mixed = prepareDesktopSmartShuffle(original, retained, snapshot, provider::related, provider::search)
                val latest = withContext(Dispatchers.IO) { store.snapshot() }
                require(playlist == null || latest.playlists.find { it.id == playlist.id }?.trackIds == playlist.trackIds) {
                    "The playlist changed while finding related songs. Try Smart shuffle again."
                }
                if (routed) {
                    require(currentFocusedRemoteId.value == focusedRemoteId && currentFocusedGroupRef.value == focusedGroup &&
                        lastSelectedRemote?.sessionId == focusedRemoteSession) {
                        "Player focus changed while finding related songs. Try Smart shuffle again."
                    }
                    dispatchPortableQueue(mixed.queue, 0, selectedOnly = false)
                } else {
                    val keep = retained?.id?.takeIf { it == mixed.anchor.id }
                    val applied = if (stageSimulator != null) {
                        stageSimulator.playLocal(mixed.queue, 0); true
                    } else player.applySmartQueue(mixed.queue, captured.occurrence, keep, captured.queueRevision)
                    require(applied) {
                        "Playback or the queue changed while finding related songs. Try Smart shuffle again."
                    }
                }
                recommendedIds = mixed.queue.filter { candidate -> original.none { it.id == candidate.id } }.map { it.id }.toSet()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "Related songs could not be found. Your queue is unchanged." }
            finally { smartBusy = false }
        }
    }
    val dockQueueOpen = queueOpen && !fullPlayerOnScreen && !remoteFocused && focusedGroup == null && playback.current != null
    val spaceShortcutRepeatGuard = remember { SpaceShortcutRepeatGuard() }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo.isWindowFocused) {
        if (!windowInfo.isWindowFocused) spaceShortcutRepeatGuard.release()
    }
    fun handleShortcut(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.key == Key.Spacebar && event.type == KeyEventType.KeyUp) {
            spaceShortcutRepeatGuard.release()
            return false
        }
        if (event.type != KeyEventType.KeyDown) return false
        val action = desktopShortcutAction(event.key, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed)
        if (action == DesktopShortcutAction.TOGGLE_PLAYBACK && !spaceShortcutRepeatGuard.press()) return true
        return when (action) {
            DesktopShortcutAction.TOGGLE_PLAYBACK -> { toggleFocusedPlayback(); true }
            DesktopShortcutAction.PREVIOUS -> { previousFocusedPlayback(); true }
            DesktopShortcutAction.NEXT -> { nextFocusedPlayback(); true }
            DesktopShortcutAction.SEEK_BACK -> { seekFocusedPlayback((presentedPosition - 10_000).coerceAtLeast(0)); true }
            DesktopShortcutAction.SEEK_FORWARD -> { seekFocusedPlayback((presentedPosition + 10_000).coerceAtMost(presentedDuration)); true }
            DesktopShortcutAction.VOLUME_UP -> { player.setVolume((playback.volume + .05f).coerceAtMost(1f)); true }
            DesktopShortcutAction.VOLUME_DOWN -> { player.setVolume((playback.volume - .05f).coerceAtLeast(0f)); true }
            DesktopShortcutAction.MUTE -> { player.setVolume(if (playback.volume > 0f) 0f else .7f); true }
            null -> false
        }
    }
    Surface(Modifier.fillMaxSize(), color = Canvas) {
        Box(Modifier.fillMaxSize().onKeyEvent(::handleShortcut).onPreviewKeyEvent {
            if (dockQueueOpen && it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                queueOpen = false
                true
            } else false
        }.semantics { if (fullPlayerOnScreen) hideFromAccessibility() }) {
        Column {
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(220.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState()).padding(22.dp)) {
                    Text("echo", fontSize = 35.sp, fontWeight = FontWeight.Black, color = Accent)
                    Text("MUSIC, IN YOUR SPACE", fontSize = 9.sp, color = Muted, letterSpacing = 2.sp)
                    Spacer(Modifier.height(38.dp))
                    listOf("Home", "Search", "Library", "Liked songs", "History", "Downloads", "Settings", "Spotify archive").forEach { destination ->
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (!stagingMode) TextButton(onClick = { showDevices = true }) { Text("Devices & account", color = Muted) }
                    if (!stagingMode) TextButton(onClick = { showUpdates = true }) { Text("App updates", color = Muted) }
                    TextButton(onClick = { importInitialFilePicker = false; showImport = true }) { Text("Import playlist", color = Muted) }
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
                            placeholder = { Text("Search songs, artists or playlists") }, singleLine = true,
                            shape = RoundedCornerShape(28.dp), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }))
                        Button(onClick = { search() }, enabled = query.isNotBlank()) { Text(if (playlistSearch) "Search playlists" else "Search songs") }
                        OutlinedButton(onClick = { scope.launch {
                            val files = chooseFiles(false)
                            if (files.isNotEmpty()) disk { files.forEach { store.saveTrack(localTrack(it)) } }
                        } }) { Text("Open music") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        FilterChip(selected = !playlistSearch, onClick = {
                            playlistSearch = false
                            if (page == "Search" && query.isNotBlank()) search(forceSongs = true)
                        }, label = { Text("Songs") })
                        FilterChip(selected = playlistSearch, onClick = {
                            playlistSearch = true
                            if (page == "Search" && query.isNotBlank()) search()
                        }, label = { Text("Playlists") })
                    }
                    Spacer(Modifier.height(26.dp))
                    if (page == "Settings") {
                        DesktopSocialPanel(signedIn = account.email != null, accountId = account.email, stagingMode = stagingMode,
                            offlineOnly = downloads.offlineOnly, google = google)
                        DesktopSpotifySettingsPage(preferences,
                            onImport = { importInitialFilePicker = true; showImport = true },
                            onBrowse = { page = "Spotify archive" },
                            onDelete = { scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { deleteDesktopSpotifyArchives(spotifyArchiveDirectory, store.file.resolveSibling("spotify-archives")) }
                                    notice = "Saved Spotify archives deleted from this laptop."
                                } catch (e: Exception) { notice = e.message ?: "Saved Spotify archives could not be deleted" }
                            } },
                        )
                    } else if (page == "Spotify archive") {
                        DesktopSpotifyDataScreen(spotifyArchiveDirectory, store.file.resolveSibling("spotify-archives"),
                            onlineAllowed = !stagingMode && !downloads.offlineOnly,
                            onSearch = { term -> query = term; playlistSearch = false; search(forceSongs = true) })
                    } else if (page == "Downloads") {
                        DesktopDownloadsPanel(offline, onPlay = { track -> play(listOf(track), 0) }, modifier = Modifier.weight(1f))
                    } else if (page == "Home") {
                        DesktopDiscoverScreen(provider, library, { items, index -> play(items, index) },
                        { addTrack = it }, { notice = it }, Modifier.weight(1f), onlineAllowed = !stagingMode && !downloads.offlineOnly,
                            onSaveMix = { items -> disk {
                                store.importParsedPlaylists(DesktopPlaylistImport(listOf(DesktopImportPlaylist("Discovery mix", items))))
                            } })
                    } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (page == "Home") "Your collection" else title, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                            Text(when {
                                searching -> if (playlistSearch) "Searching YouTube playlists…" else "Searching YouTube Music…"
                                page == "History" -> "Listening history · ${tracks.size} events"
                                page == "Search" && playlistSearch -> "${playlistResults.size} playlists · YouTube"
                                else -> "${tracks.size} songs · ${if (page == "Search") "YouTube Music" else "On this laptop"}"
                            }, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 5.dp))
                        }
                        selectedPlaylist?.let { playlist ->
                            library.linkedPlaylists[playlist.id]?.let { source ->
                                Text("Auto-update on · Last updated ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(source.updatedAt))}", style = MaterialTheme.typography.bodySmall)
                                TextButton(enabled = !downloads.offlineOnly, onClick = { scope.launch { refreshLinked(playlist.id) } }) { Text("Refresh now") }
                                TextButton(onClick = { disk { store.unlinkYouTubePlaylist(playlist.id) }; linkedErrors = linkedErrors - playlist.id }) { Text("Turn off auto-update") }
                                TextButton(enabled = tracks.isNotEmpty(), onClick = { disk {
                                    store.importParsedPlaylists(DesktopPlaylistImport(listOf(DesktopImportPlaylist("Copy of ${playlist.name.take(190)}", tracks))))
                                } }) { Text("Make editable copy") }
                            }
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
                        selectedPlaylist?.let { playlist -> linkedErrors[playlist.id]?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
                        if (page == "History" && library.history.isNotEmpty())
                            TextButton(onClick = { clearHistory = true }) { Text("Clear history") }
                    }
                    if (tracks.isNotEmpty()) Row(Modifier.padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { play(tracks, 0) }) { Text("Play all") }
                        OutlinedButton(onClick = { play(tracks.shuffled(), 0) }) { Text("Shuffle") }
                        OutlinedButton(onClick = { smartShuffle(tracks) }, enabled = !smartBusy) { Text("Smart shuffle") }
                        TextButton(onClick = { changeFocusedQueue(tracks) }) { Text("Add to queue") }
                        TextButton(onClick = { changeFocusedQueue(tracks.shuffled()) }) { Text("Queue shuffled") }
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
                    HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    if (page == "Search" && playlistSearch) {
                        if (playlistResults.isEmpty() && !searching) {
                            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No playlists found.", fontSize = 22.sp)
                                Text("Search for a public playlist title, then choose one to preview and import.",
                                    color = Muted, modifier = Modifier.padding(top = 10.dp))
                            }
                        } else LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            items(playlistResults.size, key = { index -> playlistResults[index].id }) { index ->
                                val result = playlistResults[index]
                                ListItem(headlineContent = { Text(result.title) }, supportingContent = {
                                    Text(listOf(result.uploader, result.total?.let { "$it songs" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "))
                                }, trailingContent = { Text("Preview") }, modifier = Modifier.fillMaxWidth().clickable {
                                    importInitialLink = result.url
                                    showImport = true
                                })
                            }
                        }
                    } else if (tracks.isEmpty() && !searching) {
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
                                onAdd = { addTrack = track }, onQueue = { changeFocusedQueue(listOf(track)) },
                                onNext = { changeFocusedQueue(listOf(track), playNext = true) },
                                onDownload = track.takeIf { it.provider == "youtube" }?.let { {
                                    try { offline.download(it) } catch (e: Exception) { notice = e.message ?: "Download could not start" }
                                } },
                                onFind = selectedPlaylist?.let { playlist -> {
                                    recordingTarget = Triple(playlist.id, index, playlist.trackIds.toList())
                                    playlistSearch = false
                                    query = "${track.artist} ${track.title}".take(200); search(forceSongs = true)
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
                if (!fullPlayerOnScreen && !remoteFocused && focusedGroup == null && playback.current != null) AnimatedVisibility(
                    visible = queueOpen,
                    enter = slideInHorizontally(animationSpec = tween(200)) { it } +
                        expandHorizontally(expandFrom = Alignment.End, animationSpec = tween(200)) + fadeIn(tween(200)),
                    exit = slideOutHorizontally(animationSpec = tween(200)) { it } +
                        shrinkHorizontally(shrinkTowards = Alignment.End, animationSpec = tween(200)) + fadeOut(tween(200)),
                ) {
                    LocalQueueSidebar(
                        playback, upcomingIndexes, recommendedIds,
                        onClose = { queueOpen = false },
                        onClear = { selectedRemoteId = null; if (stageSimulator != null) stageSimulator.clearLocalUpcoming() else player.clearUpcoming() },
                        onJump = { index, revision -> selectedRemoteId = null
                            if (stageSimulator != null) stageSimulator.jumpLocal(index, revision) else scope.launch { player.jumpTo(index, revision) } },
                        onMove = { from, to, occurrence, revision -> selectedRemoteId = null
                            if (stageSimulator != null) stageSimulator.moveLocal(from, to, occurrence, revision)
                            else scope.launch { player.moveUpcoming(from, to, occurrence, revision) } },
                        onRemove = { index, occurrence, revision -> selectedRemoteId = null
                            if (stageSimulator != null) stageSimulator.removeLocal(index, occurrence, revision)
                            else scope.launch { player.removeUpcoming(index, occurrence, revision) } },
                    )
                }
            }
            if (selectedRemoteId == null && playback.error != null) Text(playback.error.orEmpty(), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp))
            if (!fullPlayerOnScreen) DesktopPlayerBar(presentedTrack, presentedTitle, presentedArtist, presentedPlaying, presentedPosition, presentedDuration,
                remoteMode = remoteFocused,
                remoteDevice = remotePlayer.takeIf { remoteFocused },
                focusedDeviceLabel = if (focusedGroup != null) "Play together" else remotePlayer?.label?.let(::playerDeviceName) ?: "This laptop",
                onOpenFullPlayer = { fullPlayerOpen = true },
                queueOpen = dockQueueOpen, onToggleQueue = { queueOpen = !queueOpen },
                remoteEnabled = remoteLive,
                groupFocused = focusedGroup != null,
                localAvailable = playback.current != null, localLoading = playback.loading,
                localVolume = playback.volume, localRepeat = playback.repeatMode.name.lowercase(), localSleep = playback.sleepRemainingMs,
                onLocalPrevious = ::previousFocusedPlayback,
                onLocalToggle = ::toggleFocusedPlayback,
                onLocalNext = ::nextFocusedPlayback,
                onLocalSeek = ::seekFocusedPlayback,
                groupSeekTarget = focusedGroupSeekTarget,
                onGroupSeek = { target, position -> scope.launch { runCatching {
                    connectedPlayback.requestGroup(dev.echo.data.firebase.GroupActionV2.SEEK, position,
                        target.ref, target.timelineSequence, target.occurrenceId)
                }.onFailure { notice = it.message } } },
                onLocalVolume = { if (stageSimulator != null) stageSimulator.setLocalVolume(it) else player.setVolume(it) },
                onLocalRepeat = { if (connectedV2.group == null) if (stageSimulator != null) stageSimulator.cycleLocalRepeat()
                    else player.setRepeatMode(DesktopRepeatMode.entries[(playback.repeatMode.ordinal + 1) % DesktopRepeatMode.entries.size]) },
                onLocalShuffle = { if (connectedV2.group == null) { selectedRemoteId = null
                    if (stageSimulator != null) stageSimulator.shuffleLocalUpcoming() else player.shuffleUpcoming() } },
                onLocalSleep = { if (connectedV2.group == null) if (stageSimulator != null) stageSimulator.setLocalSleepTimer(it)
                    else player.setSleepTimer(it) }, onRemoteCommand = ::sendRemoteCommand,
                onlinePlayers = onlinePlayers, selectedRemoteId = selectedRemoteId,
                sidecarPlayers = sidecarPlayers,
                onSidecarFocus = ::focusPlayer,
                onFocusStep = { direction ->
                    if (focusedGroup == null && focusDevices.isNotEmpty()) {
                        val next = nextIndependentPlayer(focusDevices, selectedRemoteId, direction,
                            includeLocal = connectedV2.group == null,
                            selectedRemoteSessionId = selectedRemote?.device?.sessionId.takeIf { remoteFocused })
                        if (next != null || connectedV2.group == null) focusPlayer(next)
                    }
                },
                upcomingIndexes = upcomingIndexes, playback = playback, recommendedIds = recommendedIds,
                onClearQueue = { selectedRemoteId = null; if (stageSimulator != null) stageSimulator.clearLocalUpcoming() else player.clearUpcoming() },
                onJump = { index, revision -> selectedRemoteId = null
                    if (stageSimulator != null) stageSimulator.jumpLocal(index, revision) else scope.launch { player.jumpTo(index, revision) } },
                onMove = { from, to, occurrence, revision -> selectedRemoteId = null
                    if (stageSimulator != null) stageSimulator.moveLocal(from, to, occurrence, revision)
                    else scope.launch { player.moveUpcoming(from, to, occurrence, revision) } },
                onRemove = { index, occurrence, revision -> selectedRemoteId = null
                    if (stageSimulator != null) stageSimulator.removeLocal(index, occurrence, revision)
                    else scope.launch { player.removeUpcoming(index, occurrence, revision) } })
        }
        if (stagingMode) FloatingActionButton(
            onClick = { showStageAdmin = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 34.dp, bottom = 136.dp)
                .semantics { contentDescription = "Stage admin: manage demo devices" },
            containerColor = Accent, contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Devices, null); Spacer(Modifier.width(7.dp)); Text("Admin")
            }
        }
        }
    }
    if (showStageAdmin && stageSimulator != null) AlertDialog(
        onDismissRequest = { showStageAdmin = false },
        title = { Text("Stage Admin · Demo devices") },
        text = {
            Column(Modifier.widthIn(min = 500.dp, max = 680.dp).heightIn(max = 500.dp)
                .verticalScroll(rememberScrollState())) {
                Text("Add simulated phones and laptops to try device focus, queues, Move, and Play together without Gmail or Firebase. Playback is silent and stays inside this stage window.",
                    color = Muted, modifier = Modifier.padding(bottom = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { stageSimulator.addDemoDevice("Android phone") }) { Text("+ Add demo phone") }
                    OutlinedButton(onClick = { stageSimulator.addDemoDevice("Windows laptop") }) { Text("+ Add demo laptop") }
                }
                if (connectedState.devices.isEmpty()) Text("No demo devices yet.", color = Muted,
                    modifier = Modifier.padding(top = 18.dp))
                connectedState.devices.forEach { device ->
                    val isGroupMember = connectedV2.group?.members?.any { it.deviceId == device.id && it.status !=
                        dev.echo.data.firebase.GroupMemberV2Status.LEFT } == true
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(device.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${if (device.controlEnabled) "Online" else "Offline"} · ${device.player.title ?: "Ready"}" +
                                    if (isGroupMember) " · Play together member" else "", color = Muted, fontSize = 12.sp)
                            }
                            TextButton(onClick = { stageSimulator.setDemoDeviceOnline(device.id, !device.controlEnabled) },
                                enabled = !isGroupMember) { Text(if (device.controlEnabled) "Disconnect" else "Reconnect") }
                            TextButton(onClick = {
                                if (selectedRemoteId == device.id) { selectedRemoteId = null; lastSelectedRemote = null }
                                stageSimulator.removeDemoDevice(device.id)
                            }, enabled = !isGroupMember) { Text("Remove") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Simulated network", color = Muted, fontSize = 11.sp)
                            DemoLatencyPreset.entries.forEach { preset ->
                                TextButton(enabled = device.controlEnabled,
                                    onClick = { stageSimulator.setDemoDeviceLatency(device.id, preset) }) {
                                    Text("${if (stageSimulator.demoDeviceLatency(device.id) == preset) "✓ " else ""}${preset.label}", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                Text("Tip: select a demo player from the Devices icon. The Stage host has its own seeded queue for Move and group tests.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
            }
        },
        confirmButton = { TextButton(onClick = { showStageAdmin = false }) { Text("Done") } },
    )
    if (fullPlayerOnScreen) {
        Box(Modifier.fillMaxSize().onKeyEvent(::handleShortcut).onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                if (queueOpen) queueOpen = false else fullPlayerOpen = false
                true
            } else false
        }.focusProperties { onExit = { cancelFocusChange() } }.focusGroup()) {
            Box(Modifier.fillMaxSize().semantics { hideFromAccessibility() }.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            })
            AnimatedVisibility(
                visibleState = fullPlayerVisibility,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = expandVertically(expandFrom = Alignment.Bottom, animationSpec = tween(200),
                    initialHeight = { minOf(it, collapsedPlayerHeight) }) + fadeIn(tween(200), initialAlpha = .65f),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom, animationSpec = tween(200),
                    targetHeight = { minOf(it, collapsedPlayerHeight) }) + fadeOut(tween(200), targetAlpha = .65f),
            ) {
            DesktopFullPlayer(Modifier.fillMaxWidth(),
            playerOpen = fullPlayerOpen,
            track = presentedTrack, title = presentedTitle, artist = presentedArtist,
            tracksById = trackMap,
            playing = presentedPlaying, positionMs = presentedPosition, durationMs = presentedDuration,
            remoteMode = remoteFocused,
            remoteDevice = remotePlayer.takeIf { remoteFocused }, remoteEnabled = remoteLive,
            commandPending = connectedState.pending, localAvailable = playback.current != null,
            localLoading = playback.loading, group = focusedGroup != null,
            groupQueueEntries = connectedV2.group?.timeline?.queue?.entries.orEmpty(),
            groupQueueCursor = connectedV2.group?.timeline?.cursor ?: 0,
            onReadRemoteQueue = { device ->
                val target = connectedV2.devices.firstOrNull {
                    it.id == device.id && it.sessionId == device.sessionId && it.enabled
                } ?: error("${device.label} is reconnecting; select it again")
                connectedPlayback.readRemoteQueue(target.id, target.sessionId, target.snapshot.fence)
            },
            liked = presentedTrack?.id in library.favorites,
            onToggleFavorite = { presentedTrack?.let { track -> disk { store.toggleFavorite(track) } } },
            onAddToPlaylist = { presentedTrack?.let { addTrack = it } },
            onClose = { fullPlayerOpen = false },
            onPrevious = ::previousFocusedPlayback,
            onToggle = ::toggleFocusedPlayback,
            onNext = ::nextFocusedPlayback,
            onSeek = ::seekFocusedPlayback,
            groupSeekTarget = focusedGroupSeekTarget,
            onGroupSeek = { target, position -> scope.launch { runCatching {
                connectedPlayback.requestGroup(dev.echo.data.firebase.GroupActionV2.SEEK, position,
                    target.ref, target.timelineSequence, target.occurrenceId)
            }.onFailure { notice = it.message } } },
            onRemoteCommand = ::sendRemoteCommand,
            queueOpen = queueOpen, onToggleQueue = { queueOpen = !queueOpen },
            localVolume = playback.volume, localRepeat = playback.repeatMode.name.lowercase(), localSleep = playback.sleepRemainingMs,
            onLocalVolume = { if (stageSimulator != null) stageSimulator.setLocalVolume(it) else player.setVolume(it) },
            onLocalRepeat = { if (focusedGroup == null) if (stageSimulator != null) stageSimulator.cycleLocalRepeat()
                else player.setRepeatMode(DesktopRepeatMode.entries[(playback.repeatMode.ordinal + 1) % DesktopRepeatMode.entries.size]) },
            onLocalShuffle = { if (focusedGroup == null) { selectedRemoteId = null
                if (stageSimulator != null) stageSimulator.shuffleLocalUpcoming() else player.shuffleUpcoming() } },
            onLocalSleep = { if (focusedGroup == null) if (stageSimulator != null) stageSimulator.setLocalSleepTimer(it)
                else player.setSleepTimer(it) },
            playerPicker = playerPicker,
            groupMembers = connectedV2.group?.members.orEmpty().map { member ->
                val name = if (member.deviceId == ownDeviceId) "This laptop" else
                    connectedV2.devices.firstOrNull { it.id == member.deviceId }?.label ?: member.deviceId.takeLast(8)
                "$name · ${member.status.name.lowercase().replaceFirstChar(Char::uppercase)}"
            },
            queueControls = {
                if (selectedRemoteId == null && focusedGroup == null && playback.current != null)
                    QueuePicker(queueOpen) { queueOpen = !queueOpen }
            },
            localQueuePane = {
                LocalQueueSidebar(playback, upcomingIndexes, recommendedIds,
                    onClose = { queueOpen = false },
                    onClear = { selectedRemoteId = null; if (stageSimulator != null) stageSimulator.clearLocalUpcoming() else player.clearUpcoming() },
                    onJump = { index, revision -> selectedRemoteId = null
                        if (stageSimulator != null) stageSimulator.jumpLocal(index, revision) else scope.launch { player.jumpTo(index, revision) } },
                    onMove = { from, to, occurrence, revision -> selectedRemoteId = null
                        if (stageSimulator != null) stageSimulator.moveLocal(from, to, occurrence, revision)
                        else scope.launch { player.moveUpcoming(from, to, occurrence, revision) } },
                    onRemove = { index, occurrence, revision -> selectedRemoteId = null
                        if (stageSimulator != null) stageSimulator.removeLocal(index, occurrence, revision)
                        else scope.launch { player.removeUpcoming(index, occurrence, revision) } })
            },
        )
        }
        }
    }
    if (showTutorial) DesktopTutorialDialog {
        preferences.completeTutorial()
        showTutorial = false
    }
    if (showImport) DesktopImportDialog(store, spotifyArchiveDirectory, onChanged = { disk { } }, onError = { notice = it },
        onDismiss = { showImport = false; importInitialLink = null; importInitialFilePicker = false }, initialLink = importInitialLink,
        initialFilePicker = importInitialFilePicker,
        onlineAllowed = !downloads.offlineOnly)
    if (showUpdates && !stagingMode) DesktopUpdatesDialog(updater, scope, onDismiss = { showUpdates = false })
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
    if (showDevices && !stagingMode) AlertDialog(onDismissRequest = { showDevices = false }, title = { Text("Devices & account") },
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
                    TextButton(onClick = { accountAction {
                        connectedPlayback.stopGracefully()
                        google.signOut()
                    } }) { Text("Sign out") }
                }
                if (!google.hasBundledClientConfiguration || !account.clientConfigured ||
                    account.message == DESKTOP_GOOGLE_SETUP_MESSAGE || account.message == DESKTOP_GOOGLE_REDIRECT_SETUP_MESSAGE) {
                    TextButton(onClick = { accountAction {
                        chooseFiles(true, "Choose Echo Desktop OAuth client JSON").firstOrNull()?.let { google.importClientJson(it) }
                    } }) { Text("Import Desktop OAuth configuration") }
                }
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
            Text("While Echo is running, including in the system tray, this laptop shares its player status and accepts remote controls. Play together is available from the player when you want one shared queue.",
                color = Muted, modifier = Modifier.padding(top = 8.dp))
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
private fun DesktopFullPlayer(
    modifier: Modifier,
    playerOpen: Boolean,
    track: Track?, title: String?, artist: String?, playing: Boolean, positionMs: Long, durationMs: Long,
    tracksById: Map<String, Track>,
    remoteMode: Boolean, remoteDevice: ConnectedDevice?, remoteEnabled: Boolean, commandPending: Boolean,
    localAvailable: Boolean, localLoading: Boolean, group: Boolean,
    groupQueueEntries: List<QueueOccurrence>, groupQueueCursor: Int,
    onReadRemoteQueue: suspend (ConnectedDevice) -> EngineCapture,
    liked: Boolean, onToggleFavorite: () -> Unit, onAddToPlaylist: () -> Unit, onClose: () -> Unit,
    onPrevious: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit,
    groupSeekTarget: DesktopGroupSeekTarget?, onGroupSeek: (DesktopGroupSeekTarget, Long) -> Unit,
    onRemoteCommand: (ConnectedDevice, ConnectedPlaybackAction, Long) -> Unit,
    queueOpen: Boolean, onToggleQueue: () -> Unit,
    localVolume: Float, localRepeat: String, localSleep: Long?,
    onLocalVolume: (Float) -> Unit, onLocalRepeat: () -> Unit, onLocalShuffle: () -> Unit, onLocalSleep: (Int?) -> Unit,
    playerPicker: @Composable (Boolean) -> Unit,
    groupMembers: List<String>, queueControls: @Composable () -> Unit,
    localQueuePane: @Composable () -> Unit,
) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { closeFocus.requestFocus() }
    var remoteQueue by remember(remoteDevice?.id, remoteDevice?.sessionId) { mutableStateOf<EngineCapture?>(null) }
    var remoteQueueLoading by remember(remoteDevice?.id, remoteDevice?.sessionId) { mutableStateOf(false) }
    var remoteQueueError by remember(remoteDevice?.id, remoteDevice?.sessionId) { mutableStateOf<String?>(null) }
    val focusAccent = if (group) GroupAccent else Accent
    LaunchedEffect(playerOpen, queueOpen, remoteMode, remoteDevice?.id, remoteDevice?.sessionId) {
        if (playerOpen && queueOpen && remoteMode && remoteDevice != null) {
            remoteQueue = null
            remoteQueueError = null
            remoteQueueLoading = true
            try { remoteQueue = onReadRemoteQueue(remoteDevice) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { remoteQueueError = failure.message ?: "Could not read the device queue" }
            finally { remoteQueueLoading = false }
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val panelMaxHeight = (maxHeight * .94f).coerceAtLeast(400.dp)
    val queueWidth = (maxWidth * .35f).coerceAtMost(310.dp)
    Row(Modifier.fillMaxWidth().heightIn(max = panelMaxHeight), verticalAlignment = Alignment.Bottom) {
    Surface(
        Modifier.weight(1f).heightIn(max = panelMaxHeight).padding(8.dp),
        color = if (group) MaterialTheme.colorScheme.secondaryContainer else Canvas,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, if (group) GroupAccent.copy(alpha = .6f) else Accent.copy(alpha = .25f)),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = panelMaxHeight).verticalScroll(rememberScrollState()).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (group) "PLAYING TOGETHER" else "NOW PLAYING", color = focusAccent,
                        fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    Text(if (group) "${groupMembers.size} devices · shared player" else
                        if (remoteMode) "Playing on ${remoteDevice?.label ?: "selected device"}" else "This laptop",
                        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.focusRequester(closeFocus).semantics {
                    contentDescription = "Close full player"
                }) { Text("×", color = Muted, fontSize = 26.sp) }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(38.dp)) {
                Box(Modifier.size(if (queueOpen) 220.dp else 320.dp)) {
                    Cover(track, Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (playing) Text("▮▮▮", color = focusAccent, fontSize = 13.sp)
                        Text(if (playing) "PLAYING" else "PAUSED", color = focusAccent, fontSize = 11.sp, letterSpacing = 1.8.sp)
                    }
                    Text(title ?: "Choose a song", fontSize = 30.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
                    Text(artist.orEmpty(), color = Muted, fontSize = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 18.dp)) {
                        OutlinedButton(enabled = track != null, onClick = onToggleFavorite) { Text(if (liked) "♥ Liked" else "♡ Like") }
                        OutlinedButton(enabled = track != null, onClick = onAddToPlaylist) { Text("＋ Playlist") }
                    }
                    Spacer(Modifier.height(18.dp))
                    PlayerTransport(
                        Modifier.fillMaxWidth(), remoteMode, remoteDevice, remoteEnabled, playing,
                        positionMs, durationMs, localAvailable, localLoading,
                        onPrevious, onToggle, onNext, onSeek, groupSeekTarget, onGroupSeek, onRemoteCommand,
                    )
                    if (!remoteMode && !group) Row(Modifier.fillMaxWidth().padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VolumeUp, "Volume", tint = Accent)
                        Text("Volume", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                        Slider(localVolume, onLocalVolume, modifier = Modifier.weight(1f).padding(start = 12.dp)
                            .semantics { contentDescription = "Local volume" })
                    }
                    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!remoteMode && !group) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            DesktopLocalPlayerActions(localAvailable, localVolume, localRepeat, localSleep,
                                onLocalVolume, onLocalRepeat, onLocalShuffle, onLocalSleep, showVolume = false)
                            Row(verticalAlignment = Alignment.CenterVertically) { playerPicker(true); queueControls() }
                        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            playerPicker(true)
                            when {
                                remoteMode -> IconButton(enabled = remoteDevice != null && remoteEnabled && !commandPending,
                                    onClick = onToggleQueue, modifier = Modifier.semantics {
                                        contentDescription = if (queueOpen) "Close queue on ${remoteDevice?.label ?: "selected device"}"
                                            else "Open queue on ${remoteDevice?.label ?: "selected device"}"
                                    }) { Icon(Icons.Default.QueueMusic, null) }
                                group -> IconButton(enabled = groupQueueEntries.isNotEmpty(),
                                    onClick = onToggleQueue, modifier = Modifier.semantics {
                                        contentDescription = if (queueOpen) "Close shared queue · ${groupQueueEntries.size} songs"
                                            else "Open shared queue · ${groupQueueEntries.size} songs"
                                    }) { Icon(Icons.Default.QueueMusic, null) }
                            }
                        }
                    }
                    if (commandPending) Text("Remote command pending confirmation…", color = Muted,
                        fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
    AnimatedVisibility(
        visible = playerOpen && queueOpen && (remoteMode || group || localAvailable),
        enter = slideInHorizontally(animationSpec = tween(200)) { it / 3 } +
            expandHorizontally(expandFrom = Alignment.End, animationSpec = tween(200)) + fadeIn(tween(200)),
        exit = slideOutHorizontally(animationSpec = tween(200)) { it / 3 } +
            shrinkHorizontally(shrinkTowards = Alignment.End, animationSpec = tween(200)) + fadeOut(tween(200)),
    ) {
    if (remoteMode || group) {
        Surface(Modifier.width(queueWidth).heightIn(max = panelMaxHeight).padding(start = 10.dp),
            color = Panel, shape = RoundedCornerShape(0.dp),
            border = BorderStroke(1.dp, focusAccent.copy(alpha = .5f))) {
            Column(Modifier.fillMaxSize().padding(22.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (group) "Shared queue" else "Queue on ${remoteDevice?.label ?: "device"}",
                            fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (group) "Play together · ${groupQueueEntries.size} songs"
                            else if (remoteQueueLoading) "Loading device snapshot…"
                            else "Read-only device snapshot", color = focusAccent, fontSize = 12.sp)
                    }
                    IconButton(onClick = onToggleQueue) { Text("×", color = Muted, fontSize = 22.sp) }
                }
                Spacer(Modifier.height(12.dp))
                when {
                    !group && remoteQueueLoading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    !group && remoteQueueError != null -> Text(remoteQueueError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    group && groupQueueEntries.isEmpty() -> Text("The shared queue is empty.", color = Muted)
                    !group && remoteQueue?.queue?.entries.isNullOrEmpty() -> Text("This device's queue is empty.", color = Muted)
                    else -> {
                        val entries = if (group) groupQueueEntries else remoteQueue?.queue?.entries.orEmpty()
                        val cursor = if (group) groupQueueCursor else 0
                        LazyColumn(Modifier.weight(1f)) {
                            itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                                val current = index == cursor
                                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)
                                    .background(if (current) focusAccent.copy(alpha = .12f) else Color.Transparent,
                                        RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (current) "▶" else "${index + 1}", color = focusAccent,
                                        modifier = Modifier.width(36.dp))
                                    QueueArtwork(tracksById[entry.recording.id], entry.recording.title, Modifier.size(42.dp))
                                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text(entry.recording.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(entry.recording.artist, color = Muted, fontSize = 12.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else if (localAvailable) localQueuePane()
    }
    }
    }
}

@Composable
private fun DesktopPlayerBar(
    track: Track?, title: String?, artist: String?, playing: Boolean, positionMs: Long, durationMs: Long,
    remoteMode: Boolean, remoteDevice: ConnectedDevice?, focusedDeviceLabel: String, onOpenFullPlayer: () -> Unit,
    queueOpen: Boolean, onToggleQueue: () -> Unit,
    remoteEnabled: Boolean, groupFocused: Boolean,
    localAvailable: Boolean, localLoading: Boolean, localVolume: Float, localRepeat: String, localSleep: Long?,
    onLocalPrevious: () -> Unit, onLocalToggle: () -> Unit, onLocalNext: () -> Unit, onLocalSeek: (Long) -> Unit,
    groupSeekTarget: DesktopGroupSeekTarget?, onGroupSeek: (DesktopGroupSeekTarget, Long) -> Unit,
    onLocalVolume: (Float) -> Unit, onLocalRepeat: () -> Unit, onLocalShuffle: () -> Unit, onLocalSleep: (Int?) -> Unit,
    onRemoteCommand: (ConnectedDevice, ConnectedPlaybackAction, Long) -> Unit,
    onlinePlayers: List<ConnectedDevice>, selectedRemoteId: String?,
    sidecarPlayers: List<DesktopSidecarPlayer>, onSidecarFocus: (ConnectedDevice?) -> Unit,
    onFocusStep: (Int) -> Unit,
    upcomingIndexes: List<Int>, playback: DesktopPlaybackState, recommendedIds: Set<String>,
    onClearQueue: () -> Unit, onJump: (Int, Long) -> Unit,
    onMove: (Int, Int, Long, Long) -> Unit, onRemove: (Int, Long, Long) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp,
        top = if (queueOpen) 0.dp else 10.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center) {
        val narrow = maxWidth < 1180.dp
        val dockShape = RoundedCornerShape(28.dp)
        val transport: @Composable (Modifier) -> Unit = { modifier ->
            PlayerTransport(modifier, remoteMode, remoteDevice, remoteEnabled, playing,
                positionMs, durationMs, localAvailable, localLoading, onLocalPrevious, onLocalToggle, onLocalNext,
                onLocalSeek, groupSeekTarget, onGroupSeek, onRemoteCommand)
        }
        val swipeFocus = Modifier.pointerInput(selectedRemoteId, onlinePlayers.map { it.id to it.sessionId }, groupFocused) {
            var distance = 0f
            detectHorizontalDragGestures(onHorizontalDrag = { change, amount ->
                distance += amount
                change.consume()
            }, onDragEnd = {
                if (kotlin.math.abs(distance) >= 56f) onFocusStep(if (distance < 0f) 1 else -1)
                distance = 0f
            })
        }
        Row(Modifier.widthIn(max = 1600.dp).fillMaxWidth(if (sidecarPlayers.isEmpty()) .9f else .96f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = dockShape,
                color = if (groupFocused) MaterialTheme.colorScheme.secondaryContainer else Panel,
                shadowElevation = 16.dp,
            ) {
                if (narrow) Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(swipeFocus, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Cover(track, Modifier.size(54.dp).clickable(onClick = onOpenFullPlayer), circular = true)
                        Column(Modifier.weight(1f).clickable(onClick = onOpenFullPlayer)) {
                            Text(focusedDeviceLabel, color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(title ?: if (remoteMode) "Ready to play" else "Ready when you are", fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(artist ?: if (remoteMode) "" else "Choose a song to start", color = Muted, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        transport(Modifier.weight(1f))
                        if (!remoteMode && !groupFocused) DesktopLocalPlayerActions(localAvailable, localVolume, localRepeat, localSleep,
                            onLocalVolume, onLocalRepeat, onLocalShuffle, onLocalSleep)
                        if (!remoteMode && !groupFocused && playback.current != null) QueuePicker(queueOpen, onToggleQueue)
                    }
                } else Row(Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(swipeFocus.widthIn(max = 310.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Cover(track, Modifier.size(62.dp).clickable(onClick = onOpenFullPlayer), circular = true)
                        Column(Modifier.widthIn(min = 130.dp, max = 220.dp).clickable(onClick = onOpenFullPlayer)) {
                            Text(focusedDeviceLabel, color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(title ?: if (remoteMode) "Ready to play" else "Ready when you are", fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(artist ?: if (remoteMode) "" else "Choose a song to start", color = Muted, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    transport(Modifier.weight(1f))
                    if (!remoteMode && !groupFocused) DesktopLocalPlayerActions(localAvailable, localVolume, localRepeat, localSleep,
                        onLocalVolume, onLocalRepeat, onLocalShuffle, onLocalSleep)
                    if (!remoteMode && !groupFocused && playback.current != null) QueuePicker(queueOpen, onToggleQueue)
                }
            }
            if (sidecarPlayers.size > 1 || (groupFocused && sidecarPlayers.isNotEmpty()) ||
                (remoteMode && sidecarPlayers.any { it.selected && it.presentation.reconnecting })) {
                val deckWidth = if (narrow) 270.dp else 352.dp
                val selected = sidecarPlayers.firstOrNull { it.selected } ?: sidecarPlayers.first()
                val backs = sidecarPlayers.filterNot { it == selected }
                val selectedOffset = (backs.size * 80).dp.coerceAtMost(deckWidth - 180.dp)
                val stackStep = if (backs.isEmpty()) 0.dp else selectedOffset / backs.size
                Box(Modifier.width(deckWidth).height(104.dp)
                    .semantics { contentDescription = "Independent player deck, ${sidecarPlayers.size} cards" }) {
                    backs.forEachIndexed { index, card ->
                        DesktopSidecarPlayerCard(card,
                            Modifier.width(deckWidth - stackStep * index).offset(x = stackStep * index),
                            focused = false, peekWidth = stackStep, onFocus = onSidecarFocus)
                    }
                    selected?.let { card ->
                        DesktopSidecarPlayerCard(card, Modifier.width(deckWidth - selectedOffset).offset(x = selectedOffset),
                            focused = card.selected, showDetails = card.selected || groupFocused, onFocus = onSidecarFocus)
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopSidecarPlayerCard(
    card: DesktopSidecarPlayer,
    modifier: Modifier,
    focused: Boolean,
    showDetails: Boolean = focused,
    peekWidth: Dp? = null,
    onFocus: (ConnectedDevice?) -> Unit,
) {
    val player = card.presentation
    val track = player.track
    val status = when {
        player.reconnecting -> "Reconnecting"
        !card.hasRecording -> "Ready"
        player.playing -> "Playing"
        else -> "Paused"
    }
    Surface(
        onClick = { if (card.canSelect) onFocus(card.device) },
        enabled = card.canSelect,
        modifier = modifier.height(104.dp),
        shape = RoundedCornerShape(22.dp),
        color = Panel,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Accent else Accent.copy(alpha = .28f)),
        shadowElevation = if (focused) 16.dp else 6.dp,
    ) {
        if (showDetails) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cover(track, Modifier.size(62.dp), circular = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(card.label, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (card.hasRecording) player.title ?: "Unknown track" else if (player.reconnecting) "Reconnecting…" else "Ready",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (player.artist.isNullOrBlank()) status else "$status · ${player.artist}",
                        color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Column(Modifier.width(peekWidth ?: 64.dp).fillMaxHeight().padding(3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Cover(track, Modifier.size(34.dp))
                    Text(playerTabName(card.label), color = Accent, fontSize = 9.sp, lineHeight = 11.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun DesktopLocalPlayerActions(
    localAvailable: Boolean, volume: Float, repeat: String, sleep: Long?,
    onVolume: (Float) -> Unit, onRepeat: () -> Unit, onShuffle: () -> Unit, onSleep: (Int?) -> Unit,
    showVolume: Boolean = true,
) {
    var volumeOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(enabled = localAvailable, onClick = onShuffle,
            modifier = Modifier.semantics { contentDescription = "Shuffle upcoming" }) {
            Icon(Icons.Default.Shuffle, null, tint = if (localAvailable) Accent else Muted)
        }
        IconButton(enabled = localAvailable, onClick = onRepeat,
            modifier = Modifier.semantics { contentDescription = "Repeat mode: $repeat" }) {
            Icon(if (repeat == "one") Icons.Default.RepeatOne else Icons.Default.Repeat, null,
                tint = if (repeat == "off" || !localAvailable) Muted else Accent)
        }
        if (showVolume) Box {
            IconButton(onClick = { volumeOpen = true },
                modifier = Modifier.semantics { contentDescription = "Volume" }) {
                Icon(Icons.Default.VolumeUp, null, tint = Accent)
            }
            DropdownMenu(volumeOpen, onDismissRequest = { volumeOpen = false }) {
                Column(Modifier.width(190.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("Volume", color = Muted, fontSize = 12.sp)
                    Slider(volume, onVolume)
                }
            }
        }
        Box {
            IconButton(enabled = localAvailable, onClick = { sleepOpen = true },
                modifier = Modifier.semantics {
                    contentDescription = sleep?.let { "Sleep timer, ${clock(it)} remaining" } ?: "Set sleep timer"
                }) {
                Icon(Icons.Default.Bedtime, null, tint = if (sleep == null) Muted else Accent)
            }
            DropdownMenu(sleepOpen, onDismissRequest = { sleepOpen = false }) {
                Text("Sleep timer", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Muted, fontSize = 12.sp)
                listOf(15, 30, 45, 60).forEach { minute -> DropdownMenuItem(text = { Text("$minute minutes") },
                    onClick = { onSleep(minute); sleepOpen = false }) }
                if (sleep != null) DropdownMenuItem(text = { Text("Cancel timer") },
                    onClick = { onSleep(null); sleepOpen = false })
            }
        }
    }
}

@Composable
private fun PlayerTransport(
    modifier: Modifier, remoteMode: Boolean, remoteDevice: ConnectedDevice?, remoteEnabled: Boolean,
    playing: Boolean, positionMs: Long, durationMs: Long, localAvailable: Boolean, localLoading: Boolean,
    onLocalPrevious: () -> Unit, onLocalToggle: () -> Unit, onLocalNext: () -> Unit, onLocalSeek: (Long) -> Unit,
    groupSeekTarget: DesktopGroupSeekTarget?, onGroupSeek: (DesktopGroupSeekTarget, Long) -> Unit,
    onRemoteCommand: (ConnectedDevice, ConnectedPlaybackAction, Long) -> Unit,
) {
    var dragValue by remember(remoteMode, remoteEnabled, remoteDevice?.id, remoteDevice?.sessionId,
        remoteDevice?.player?.trackRevision, remoteDevice?.player?.queueRevision, groupSeekTarget) { mutableStateOf<Float?>(null) }
    var dragGroupTarget by remember(remoteMode, remoteEnabled, remoteDevice?.id, remoteDevice?.sessionId,
        remoteDevice?.player?.trackRevision, remoteDevice?.player?.queueRevision, groupSeekTarget) {
        mutableStateOf<DesktopGroupSeekTarget?>(null)
    }
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
            }, enabled = canTransport && (remoteMode || !localLoading), modifier = Modifier.semantics {
                contentDescription = if (playing) "Pause playback" else "Play playback"
            }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null) }
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
                    }
                    if (dragValue == null) dragGroupTarget = groupSeekTarget
                    dragValue = value
                }, onValueChangeFinished = {
                    val target = dragTarget
                    val groupTarget = dragGroupTarget
                    val value = dragValue
                    if (value != null && remoteMode && target != null)
                        onRemoteCommand(target, ConnectedPlaybackAction.SEEK, value.toLong())
                    else if (value != null && groupTarget != null) onGroupSeek(groupTarget, value.toLong())
                    else if (value != null) onLocalSeek(value.toLong())
                    dragTarget = null; dragGroupTarget = null; dragValue = null
                }, valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(), enabled = durationMs > 0 && canTransport,
                modifier = Modifier.weight(1f).height(26.dp).padding(horizontal = 10.dp))
            Text(clock(durationMs), fontSize = 10.sp, color = Muted)
        }
    }
}

@Composable
private fun DesktopPlayerPicker(
    stagingMode: Boolean,
    showCrossDeviceActions: Boolean,
    accessibleLabel: String,
    remoteMode: Boolean,
    selectedRemoteId: String?,
    connected: Boolean,
    connectedMessage: String?,
    devices: List<ConnectedDevice>,
    connectedV2: dev.echo.data.firebase.ConnectedPlaybackV2State,
    ownDeviceId: String,
    displacedMoveOperationId: String?,
    onMoveV2: (String) -> Unit,
    onTogetherV2: (List<String>, Map<String, String>, Boolean) -> Unit,
    onRestorePrevious: () -> Unit,
    onGroupAction: (dev.echo.data.firebase.GroupActionV2) -> Unit,
    onSyncGroup: () -> Unit,
    canSyncGroup: Boolean,
    online: List<ConnectedDevice>,
    offline: List<ConnectedDevice>,
    localStatus: String,
    commandPending: Boolean,
    selectedDevice: ConnectedDevice?,
    onSelectLocal: () -> Unit,
    onSelectRemote: (ConnectedDevice) -> Unit,
) {
    val group = connectedV2.group
    var openPanel by remember { mutableStateOf<String?>(null) }
    var showOffline by remember { mutableStateOf(false) }
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
    LaunchedEffect(group?.ref, group?.status) {
        if (group?.status == dev.echo.data.firebase.GroupV2Status.ACTIVE) {
            togetherDeviceSessions = emptyMap()
            confirmTogether = false
        }
    }
    val memberIds = group?.members?.filter { it.status != dev.echo.data.firebase.GroupMemberV2Status.LEFT }
        ?.map { it.deviceId }?.toSet().orEmpty()
    val independent = devices.filter { it.id !in memberIds }
    val current = independent.firstOrNull { it.id == selectedRemoteId }
    Box {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { openPanel = "devices" }, modifier = Modifier.semantics {
                    contentDescription = accessibleLabel
                }) { Icon(Icons.Default.Devices, null, tint = Accent) }
                if (online.isNotEmpty()) Surface(
                    modifier = Modifier.align(Alignment.TopEnd), shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface,
                ) { Text("+${online.size}", fontSize = 9.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)) }
            }
            if (showCrossDeviceActions) {
                OutlinedButton(onClick = { openPanel = "move" }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp)); Text("Move")
                }
                OutlinedButton(onClick = { openPanel = "together" }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Icon(Icons.Default.Groups, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp)); Text("Together")
                }
            }
        }
        DropdownMenu(openPanel != null, onDismissRequest = { openPanel = null }) {
            Column(Modifier.width(if (openPanel == "devices") 330.dp else 390.dp)
                .heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(vertical = 5.dp)) {
                when (openPanel) {
                    "devices" -> {
                        Text("Devices", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        DropdownMenuItem(text = {
                            Column {
                                Text(if (group != null) "Play together · ${group.members.size} devices" else "This laptop")
                                Text(if (group != null) "Shared player" else localStatus, color = Muted, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }, onClick = { onSelectLocal(); openPanel = null })
                        group?.members.orEmpty().forEach { member ->
                            val name = if (member.deviceId == ownDeviceId) {
                                if (stagingMode) "Stage host · this player" else "This laptop · coordinator"
                            } else
                                connectedV2.devices.firstOrNull { it.id == member.deviceId }?.label ?: member.deviceId.takeLast(8)
                            val status = when (member.status) {
                                dev.echo.data.firebase.GroupMemberV2Status.BUFFERING -> "Buffering"
                                dev.echo.data.firebase.GroupMemberV2Status.DISCONNECTED -> "Reconnecting"
                                dev.echo.data.firebase.GroupMemberV2Status.LEFT -> "Left"
                                else -> member.status.name.lowercase().replaceFirstChar(Char::uppercase)
                            }
                            DropdownMenuItem(enabled = false, text = { Column {
                                Text("●  $name", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Together · $status", color = Muted, fontSize = 11.sp)
                            } }, onClick = {})
                        }
                        current?.let { device ->
                            DevicePickerRow(device, selected = true, enabled = device.controlEnabled) {
                                onSelectRemote(device); openPanel = null
                            }
                        }
                        independent.filter { it.controlEnabled && it.id != selectedRemoteId }
                            .sortedBy { it.label.lowercase() }.forEach { device ->
                                DevicePickerRow(device, selected = false, enabled = true) { onSelectRemote(device); openPanel = null }
                            }
                        val offlinePeers = independent.filterNot { it.controlEnabled || it.id == selectedRemoteId }
                        if (offlinePeers.isNotEmpty()) {
                            TextButton(onClick = { showOffline = !showOffline }, modifier = Modifier.fillMaxWidth()) {
                                Text("${if (showOffline) "▾" else "▸"} Offline · ${offlinePeers.size}", modifier = Modifier.fillMaxWidth())
                            }
                            if (showOffline) offlinePeers.forEach { device ->
                                DropdownMenuItem(enabled = false, text = { Column {
                                    Text(device.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Offline · last known session", color = Muted, fontSize = 11.sp)
                                } }, onClick = {})
                            }
                        }
                        if (independent.isEmpty() && group == null) Text(
                            if (stagingMode) "No demo devices yet. Use Stage Admin to add some." else "No other players are online.",
                            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        if (remoteMode) {
                            connectedMessage?.let { Text(it, color = Muted, fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                            if (commandPending) Text("Remote command pending…", color = Muted, fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            selectedDevice?.ack?.takeIf { it.status == "rejected" }?.let {
                                Text("Command declined: ${it.reason ?: "device state changed"}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            }
                        } else if (!connected) Text(connectedMessage ?: "Connected players are unavailable.", color = Muted,
                            fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    "move" -> {
                        Text("Move playback", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        if (displacedMoveOperationId != null && !remoteMode && group == null) {
                            Text("The previous demo queue is available for paused recovery.", color = Muted, fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            if (!confirmRestorePrevious) TextButton(enabled = !connectedV2.pending,
                                onClick = { confirmRestorePrevious = true }, modifier = Modifier.fillMaxWidth().testTag("connected-restore-previous")) {
                                Text("Restore previous queue paused")
                            } else {
                                Text("This replaces the current demo queue and restores the earlier queue paused.", color = Muted,
                                    fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                                TextButton(enabled = !connectedV2.pending, onClick = {
                                    confirmRestorePrevious = false; onRestorePrevious(); openPanel = null
                                }, modifier = Modifier.fillMaxWidth().testTag("connected-restore-previous-confirm")) {
                                    Text("Confirm restore previous queue")
                                }
                                TextButton(onClick = { confirmRestorePrevious = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                            }
                        }
                        when {
                            remoteMode -> Text("Choose This laptop before moving its demo queue.", color = Muted, fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
                            group != null -> Text("Leave Play together before moving this queue.", color = Muted, fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
                            localStatus == "Ready" -> Text("Start a demo song before moving the queue.", color = Muted, fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
                            else -> {
                                Text("Move the current song and remaining queue to a demo device.", color = Muted, fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
                                connectedV2.devices.filter { it.enabled && it.id != ownDeviceId && "move" in it.capabilities }.forEach { device ->
                                    TextButton(enabled = !connectedV2.pending, onClick = { onMoveV2(device.id); openPanel = null },
                                        modifier = Modifier.fillMaxWidth()) { Text("Move to ${device.label}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                        }
                    }
                    "together" -> {
                        Text("Play together", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        if (remoteMode && group == null) {
                            Text("Choose This laptop as the coordinator before creating a shared queue.", color = Muted,
                                fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
                        } else if (group != null) {
                            Text("${if (stagingMode) "Shared demo timeline" else "Play together"} · ${group.status.name.lowercase()}", color = Muted, fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            group.members.forEach { member ->
                                val name = if (member.deviceId == ownDeviceId) {
                                    if (stagingMode) "Stage host · virtual" else "This laptop · coordinator"
                                } else
                                    connectedV2.devices.firstOrNull { it.id == member.deviceId }?.label ?: member.deviceId.takeLast(8)
                                val status = if (member.status == dev.echo.data.firebase.GroupMemberV2Status.DISCONNECTED) "Reconnecting"
                                    else member.status.name.lowercase().replaceFirstChar(Char::uppercase)
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                                    Text("$name · $status", color = Muted, fontSize = 11.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    val sample = group.syncTiming.firstOrNull { timing ->
                                        timing.deviceId == member.deviceId && timing.sessionId == member.sessionId &&
                                            System.currentTimeMillis() - timing.sampledAtMs in 0..15_000L
                                    }
                                    Text(sample?.let { "Network estimate · ${it.roundTripP95Ms} ms RTT · ±${it.jitterMs} ms jitter" }
                                        ?: "Network estimate unavailable · using 8s start",
                                        color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Text("Connection timing guides the start; speaker and output delay aren't measured.",
                                color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
                            group.timeline?.let { timeline ->
                                Text("Shared queue · ${timeline.queue.entries.size} songs", color = Accent, fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                                timeline.queue.entries.drop(timeline.cursor).take(8).forEachIndexed { offset, entry ->
                                    Text("${if (offset == 0) "Now" else "${timeline.cursor + offset + 1}."} ${entry.recording.title} · ${entry.recording.artist}",
                                        color = if (offset == 0) Accent else Muted, fontSize = 11.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                                }
                            }
                            if (canSyncGroup) TextButton(enabled = !connectedV2.pending, onClick = onSyncGroup,
                                modifier = Modifier.fillMaxWidth().testTag("connected-group-sync")) {
                                Text(if (connectedV2.pending) "Adjusting timing…" else "Sync now")
                            }
                            TextButton(enabled = !connectedV2.pending, onClick = { onGroupAction(dev.echo.data.firebase.GroupActionV2.PREVIOUS) },
                                modifier = Modifier.fillMaxWidth()) { Text("Previous for everyone") }
                            TextButton(enabled = !connectedV2.pending, onClick = { onGroupAction(
                                if (group.timeline?.playing == true) dev.echo.data.firebase.GroupActionV2.PAUSE else dev.echo.data.firebase.GroupActionV2.PLAY) },
                                modifier = Modifier.fillMaxWidth()) { Text(if (group.timeline?.playing == true) "Pause everyone" else "Play for everyone") }
                            TextButton(enabled = !connectedV2.pending, onClick = { onGroupAction(dev.echo.data.firebase.GroupActionV2.NEXT) },
                                modifier = Modifier.fillMaxWidth()) { Text("Next for everyone") }
                            TextButton(enabled = !connectedV2.pending, onClick = { onGroupAction(dev.echo.data.firebase.GroupActionV2.LEAVE); openPanel = null },
                                modifier = Modifier.fillMaxWidth()) { Text("End group and restore queues paused") }
                        } else {
                            Text(if (stagingMode) "The Stage host and selected demo devices share one preview queue. Native audio is unchanged."
                                else "This laptop and the selected devices will share the current queue after every player is ready.",
                                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            Text(if (stagingMode) "${togetherDeviceIds.size} of ${MAX_CONNECTED_GROUP_MEMBERS - 1} demo devices selected"
                                else "${togetherDeviceIds.size} of ${MAX_CONNECTED_GROUP_MEMBERS - 1} other players selected", color = Muted,
                                fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            togetherCandidates.forEach { device ->
                                val selected = device.id in togetherDeviceIds
                                val expected = togetherDeviceSessions[device.id]
                                val changed = selected && expected != device.sessionId
                                val ready = device.enabled && !changed
                                Row(Modifier.fillMaxWidth().clickable(enabled = ready || selected) {
                                    togetherDeviceSessions = if (selected) togetherDeviceSessions - device.id else
                                        if (togetherDeviceSessions.size < MAX_CONNECTED_GROUP_MEMBERS - 1) togetherDeviceSessions + (device.id to device.sessionId)
                                        else togetherDeviceSessions
                                }.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = selected, enabled = ready || selected, onCheckedChange = { checked ->
                                        togetherDeviceSessions = if (checked && !selected && togetherDeviceSessions.size < MAX_CONNECTED_GROUP_MEMBERS - 1)
                                            togetherDeviceSessions + (device.id to device.sessionId)
                                        else if (!checked) togetherDeviceSessions - device.id else togetherDeviceSessions
                                    })
                                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                        Text(device.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(if (changed) "Session changed · remove and reselect" else
                                            if (!device.enabled) "Offline" else if (device.snapshot.playing) "Playing · ${device.snapshot.title.orEmpty()}"
                                            else "Paused · ${device.snapshot.title ?: "ready"}", color = Muted, fontSize = 11.sp)
                                    }
                                }
                            }
                            if (invalidTogetherSelection) Text(if (stagingMode)
                                "A selected demo player is offline or has restarted. Remove it and select the current session."
                                else "A selected player is offline or has restarted. Remove it and select the current session.",
                                color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            TextButton(enabled = readyToStartTogether && !connectedV2.pending, onClick = {
                                if (selectedTogetherDevices.any { it.snapshot.playing }) confirmTogether = true
                                else { onTogetherV2(togetherDeviceIds.toList(), togetherDeviceSessions, false); openPanel = null }
                            }, modifier = Modifier.fillMaxWidth().testTag("connected-together-start")) {
                                Text(if (stagingMode) "Start Play together preview" else "Start Play together")
                            }
                        }
                        connectedV2.message?.let { Text(it, color = Muted, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                    }
                }
            }
        }
        if (confirmTogether) AlertDialog(onDismissRequest = { confirmTogether = false },
            title = { Text(if (stagingMode) "Replace playing demo players?" else "Replace playing devices?") },
            text = { Text(if (stagingMode) "A selected demo player is playing. Play together will replace its demo queue."
                else "At least one selected player is playing. Play together will replace its current playback and queue.") },
            confirmButton = { TextButton(enabled = readyToStartTogether, onClick = {
                confirmTogether = false; onTogetherV2(togetherDeviceIds.toList(), togetherDeviceSessions, true); openPanel = null
            }, modifier = Modifier.testTag("connected-together-confirm")) { Text("Replace and start") } },
            dismissButton = { TextButton(onClick = { confirmTogether = false }) { Text("Cancel") } })
    }
}

@Composable
private fun DevicePickerRow(device: ConnectedDevice, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(enabled = enabled, text = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Devices, null, tint = if (selected) Accent else Muted, modifier = Modifier.size(18.dp))
            Column {
                Text(device.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (device.player.title == null) "Ready to play" else
                    "${if (device.player.isPlaying) "Playing" else "Paused"} · ${device.player.title}",
                    color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }, onClick = onClick)
}

@Composable
private fun QueuePicker(
    expanded: Boolean, onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle, modifier = Modifier.semantics {
        contentDescription = if (expanded) "Close queue" else "Open queue"
    }) {
        Icon(Icons.Default.QueueMusic, null, tint = if (expanded) Accent else LocalContentColor.current)
    }
}

@Composable
private fun QueueArtwork(track: Track?, title: String, modifier: Modifier) {
    if (track != null) Cover(track, modifier)
    else Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF25594F), Color(0xFF283B5E))),
        RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Text(title.firstOrNull(Char::isLetterOrDigit)?.uppercaseChar()?.toString() ?: "♪",
            color = Color(0xFFEAF0F7), fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
private fun LocalQueueSidebar(
    playback: DesktopPlaybackState,
    upcoming: List<Int>,
    recommended: Set<String>,
    onClose: () -> Unit,
    onClear: () -> Unit,
    onJump: (Int, Long) -> Unit,
    onMove: (Int, Int, Long, Long) -> Unit,
    onRemove: (Int, Long, Long) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxHeight()) {
        val paneWidth = (maxWidth * .35f).coerceAtMost(310.dp)
        Surface(Modifier.width(paneWidth).fillMaxHeight().padding(start = 10.dp), color = Panel,
            shape = RoundedCornerShape(0.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Queue", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear, enabled = upcoming.isNotEmpty()) { Text("Clear") }
                IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Close queue" }) {
                    Text("×", color = Muted, fontSize = 22.sp)
                }
            }
            Text("Up next · ${upcoming.size} songs", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            if (upcoming.isEmpty()) Text("Nothing is queued next.", color = Muted)
            else LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(upcoming, key = { _, absolute -> "${playback.queueRevision}:$absolute" }) { index, absolute ->
                    val track = playback.queue.getOrNull(absolute) ?: return@itemsIndexed
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        QueueArtwork(track, track.title, Modifier.size(42.dp))
                        Column(Modifier.weight(1f).padding(start = 10.dp).clickable { onJump(absolute, playback.queueRevision) }.padding(vertical = 7.dp)) {
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
    Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Muted, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
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
    Row(Modifier.fillMaxWidth().background(if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        RoundedCornerShape(8.dp)).clickable(onClick = onPlay).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", Modifier.width(36.dp), color = if (current) Accent else Muted, fontSize = 12.sp)
        Cover(track, Modifier.size(42.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
        Text(track.title, color = if (current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, maxLines = 1,
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
private fun Cover(track: Track?, modifier: Modifier, circular: Boolean = false) {
    val artwork by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, track?.artworkUri) {
        value = loadDesktopArtwork(track?.artworkUri)
    }
    val shape = if (circular) CircleShape else RoundedCornerShape(8.dp)
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0xFF25594F), Color(0xFF283B5E))), shape),
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
