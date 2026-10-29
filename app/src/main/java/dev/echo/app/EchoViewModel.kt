package dev.echo.app

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import dev.echo.playback.QueueCommands
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.echo.core.Track
import dev.echo.data.library.LibraryRepository
import dev.echo.data.library.SettingsRepository
import dev.echo.data.provider.CommonsProvider
import dev.echo.data.provider.SourceResolver
import dev.echo.data.spotify.SpotifyRepository
import dev.echo.data.spotify.SpotifyPlaylist
import dev.echo.data.provider.YouTubeProvider
import dev.echo.playback.EchoPlaybackService
import dev.echo.app.update.AppUpdater
import dev.echo.app.update.UpdateRelease
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedPlaybackAction
import dev.echo.data.firebase.ConnectedPlaybackClient
import dev.echo.data.firebase.ConnectedPlaybackCommand
import dev.echo.data.firebase.ConnectedPlaybackState
import dev.echo.data.firebase.ConnectedPlayerSnapshot
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@HiltViewModel
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EchoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val library: LibraryRepository,
    val settings: SettingsRepository,
    val spotify: SpotifyRepository,
    val spotifyLinks: dev.echo.data.spotify.SpotifyPlaylistLinks,
    private val commons: CommonsProvider,
    private val youtube: YouTubeProvider,
    val sources: SourceResolver,
    val updater: AppUpdater,
    val sleepTimer: dev.echo.playback.SleepTimerController,
    val offline: dev.echo.data.offline.OfflineRepository,
    val googleAccounts: dev.echo.app.account.GoogleAccounts,
    private val firebaseSync: dev.echo.app.account.FirebaseAccountSync,
) : ViewModel() {
    internal var requestedDownloads by mutableStateOf(false)
    val offlineSongs = offline.songs
    val sleepTimerRemainingMs = sleepTimer.remainingMs
    fun setSleepTimer(minutes: Int) { sleepTimer.start(minutes) }
    fun cancelSleepTimer() { sleepTimer.cancel() }
    val firebaseLastSynced = firebaseSync.lastSynced.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val firebaseConfigured: Boolean get() = dev.echo.data.firebase.EchoFirebaseProject.configured
    val offlineSettings = offline.settings
    val tracks = library.tracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists = library.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val favorites = library.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val recent = library.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val listeningHistory = library.listeningHistory.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var controller by mutableStateOf<MediaController?>(null); private set
    var current by mutableStateOf<MediaItem?>(null); private set
    var playing by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var position by mutableStateOf(0L); private set
    var duration by mutableStateOf(0L); private set
    var revision by mutableStateOf(0); private set
    internal var queueItems by mutableStateOf<List<QueueEntry>>(emptyList()); private set
    var recommendedIds by mutableStateOf<Set<String>>(emptySet()); private set
    var matchingProgress by mutableStateOf<Pair<Int, Int>?>(null); private set
    private var matchingJob: Job? = null
    private var playlistLoad: Job? = null
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set
    var onlineResults by mutableStateOf<List<Track>>(emptyList()); private set
    var youtubeResults by mutableStateOf<List<Track>>(emptyList()); private set
    var isSearching by mutableStateOf(false); private set
    var searchedQuery by mutableStateOf(""); private set
    private var searchJob: Job? = null
    private var searchRequest = 0
    var spotifyPlaylists by mutableStateOf<List<SpotifyPlaylist>>(emptyList()); private set
    var playlistItems by mutableStateOf<List<Track>>(emptyList()); private set
    var loadedPlaylistId by mutableStateOf<String?>(null); private set
    var discoveryResults by mutableStateOf<List<Track>>(emptyList()); private set
    var newMusicResults by mutableStateOf<List<Track>>(emptyList()); private set
    var discoveryLoading by mutableStateOf(false); private set
    private val connection = MediaController.Builder(context, SessionToken(context, ComponentName(context, EchoPlaybackService::class.java)))
        .setListener(object : MediaController.Listener {
            override fun onError(controller: MediaController, sessionError: SessionError) { message = sessionError.message }
        }).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) connectedPlaybackGeneration++
            sync(); queueItems = queueSnapshot(player)
        }
        override fun onPlayerError(error: PlaybackException) { message = "Playback failed: ${error.errorCodeName}. Check the source or retry." }
    }
    var accountBusy by mutableStateOf(false); private set
    var accountStatus by mutableStateOf<String?>(null); private set
    var googleAccountChoice by mutableStateOf<android.content.Intent?>(null); private set
    var driveResolution by mutableStateOf<android.content.Intent?>(null)
        private set
    private var pendingGoogleAccount: android.accounts.Account? = null
    private var driveAccount: String? = null
    private var accountJob: Job? = null
    private val connectedPlaybackPrefs = context.getSharedPreferences("connected-playback", Context.MODE_PRIVATE)
    val connectedPlaybackEnabled = MutableStateFlow(connectedPlaybackPrefs.getBoolean("enabled", false))
    val connectedPlaybackState = MutableStateFlow(ConnectedPlaybackState())
    internal val connectedPlaybackDeviceIdForUi = connectedPlaybackPrefs.getString("device-id", null)
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
        ?: java.util.UUID.randomUUID().toString().also { connectedPlaybackPrefs.edit().putString("device-id", it).apply() }
    private var connectedPlaybackClient: ConnectedPlaybackClient? = null
    val selectedConnectedPlaybackTargetId = MutableStateFlow<String?>(null)
    val selectedConnectedPlaybackDevice = MutableStateFlow<ConnectedDevice?>(null)
    private var connectedPlaybackSelectionAccount: String? = null

    fun signInGoogle() { if (!accountBusy) googleAccountChoice = googleAccounts.chooseAccountIntent() }
    fun accountChoiceLaunched() { googleAccountChoice = null }
    fun completeGoogleSignIn(data: android.content.Intent?) = accountAction {
        val account = dev.echo.app.account.googleAccountFromSelection(data)
        val authorization = googleAccounts.signIn(account)
        if (authorization.resolution != null) {
            pendingGoogleAccount = account
            driveAccount = null
            driveResolution = authorization.resolution
            accountStatus = "Approve Google account verification to sign in."
        } else accountStatus = "Signed in. Enable Firebase sync to share playlists, favorites and history."
    }
    fun signOutGoogle() = accountAction {
        pendingGoogleAccount = null; driveAccount = null; driveResolution = null; googleAccountChoice = null
        setConnectedPlaybackEnabled(false)
        clearConnectedPlaybackSelection()
        firebaseSync.signOut(googleAccounts.email.value)
        googleAccounts.signOut()
        accountStatus = "Signed out. This device's library and downloads were kept."
    }
    fun setAutomaticSync(enabled: Boolean) {
        googleAccounts.setAutomatic(enabled)
        if (enabled) syncGoogle(manual = true)
    }
    fun syncGoogle(manual: Boolean) {
        if (accountBusy || pendingGoogleAccount != null || driveAccount != null || googleAccountChoice != null || driveResolution != null ||
            (!manual && !googleAccounts.automatic.value)) return
        accountAction {
            check(!offline.settings.value.offlineOnly) { "Turn off Offline mode to sync your library" }
            val email = googleAccounts.email.value ?: error("Sign in first")
            val authorization = googleAccounts.authorizeIdentity()
            if (authorization.resolution != null) {
                if (manual) {
                    driveAccount = email
                    driveResolution = authorization.resolution
                    accountStatus = "Approve Google account access to connect Firebase."
                } else {
                    googleAccounts.setAutomatic(false)
                    accountStatus = "Connect Firebase in Profile to resume automatic sync."
                }
            } else {
                check(googleAccounts.email.value == email) { "Account changed; retry sync" }
                accountStatus = firebaseSync.synchronize(requireNotNull(authorization.accessToken), email)
                loadedPlaylistId?.let(::loadPlaylist)
            }
        }
    }
    fun setConnectedPlaybackEnabled(enabled: Boolean) {
        if (enabled && (googleAccounts.email.value == null || !firebaseConfigured)) return
        connectedPlaybackPrefs.edit().putBoolean("enabled", enabled).apply()
        connectedPlaybackEnabled.value = enabled
        if (!enabled) stopConnectedPlayback()
    }

    suspend fun runConnectedPlayback() {
        val email = googleAccounts.email.value?.takeIf { connectedPlaybackEnabled.value } ?: return
        if (connectedPlaybackSelectionAccount != null && connectedPlaybackSelectionAccount != email) clearConnectedPlaybackSelection()
        connectedPlaybackSelectionAccount = email
        val player = ConnectedPlaybackClient(
            databaseUrl = dev.echo.data.firebase.EchoFirebaseProject.realtimeDatabaseUrl,
            deviceId = connectedPlaybackDeviceIdForUi,
            label = "Android · ${android.os.Build.MODEL.orEmpty().take(32)}",
            sessionProvider = { firebaseSync.existingSession(email) },
            snapshot = { withContext(Dispatchers.Main.immediate) { connectedPlayerSnapshot() } },
            executeCommand = { withContext(Dispatchers.Main.immediate) {
                check(connectedPlaybackEnabled.value && googleAccounts.email.value == email) { "Connected playback was disabled or the account changed" }
                executeConnectedCommand(it)
            } },
        )
        connectedPlaybackClient = player
        val collector = viewModelScope.launch {
            player.state.collect { state ->
                connectedPlaybackState.value = state
                val selectedId = selectedConnectedPlaybackTargetId.value
                if (selectedId != null) state.devices.firstOrNull { it.id == selectedId }?.let { selectedConnectedPlaybackDevice.value = it }
            }
        }
        var failure: String? = null
        try { player.run() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            failure = error.message ?: "Connect Firebase before enabling playback."
            connectedPlaybackState.value = ConnectedPlaybackState(message = failure)
        }
        finally {
            val finalMessage = failure ?: player.state.value.message ?: "Connected playback stopped."
            player.close()
            collector.cancel()
            if (connectedPlaybackClient === player) {
                connectedPlaybackClient = null
                connectedPlaybackState.value = ConnectedPlaybackState(message = finalMessage)
            }
        }
    }

    fun stopConnectedPlayback() {
        connectedPlaybackClient?.close()
        connectedPlaybackClient = null
        connectedPlaybackState.value = ConnectedPlaybackState(message = "Connected playback stopped.")
    }

    fun selectConnectedPlaybackTarget(device: ConnectedDevice?) {
        if (device == null || device.id == connectedPlaybackDeviceIdForUi) {
            clearConnectedPlaybackSelection()
            return
        }
        selectedConnectedPlaybackTargetId.value = device.id
        selectedConnectedPlaybackDevice.value = device
        connectedPlaybackSelectionAccount = googleAccounts.email.value
    }

    private fun clearConnectedPlaybackSelection() {
        selectedConnectedPlaybackTargetId.value = null
        selectedConnectedPlaybackDevice.value = null
        connectedPlaybackSelectionAccount = null
    }

    fun canControlConnectedDevice(target: ConnectedDevice): Boolean =
        target.id == selectedConnectedPlaybackTargetId.value &&
            isFreshConnectedPlaybackTarget(target, connectedPlaybackState.value)

    fun sendConnectedPlaybackCommand(target: ConnectedDevice, action: ConnectedPlaybackAction, positionMs: Long = 0L) {
        val client = connectedPlaybackClient ?: run {
            connectedPlaybackState.value = connectedPlaybackState.value.copy(message = "Reconnecting to ${target.label}.")
            return
        }
        if (!canControlConnectedDevice(target)) {
            connectedPlaybackState.value = connectedPlaybackState.value.copy(message = "Reconnecting to ${target.label}; controls are paused until its current state is confirmed.")
            return
        }
        viewModelScope.launch {
            try {
                val fresh = connectedPlaybackState.value.devices.firstOrNull { it.id == target.id }
                check(fresh != null && canControlConnectedDevice(target)) {
                    "Reconnecting to ${target.label}; controls are paused until its current state is confirmed."
                }
                client.sendCommand(requireNotNull(fresh), action, positionMs)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                connectedPlaybackState.value = connectedPlaybackState.value.copy(message = error.message ?: "Remote control failed.")
            }
        }
    }

    private fun connectedPlayerSnapshot(): ConnectedPlayerSnapshot {
        val player = controller
        val count = player?.mediaItemCount ?: 0
        val ids = if (player != null && count <= 10_000) List(count) { player.getMediaItemAt(it).mediaId } else emptyList()
        val queueRevision = stableRevision(ids.mapIndexed { index, id -> "$index:$id" }.joinToString("\n"))
        val index = player?.currentMediaItemIndex ?: -1
        val item = player?.currentMediaItem
        val mediaId = item?.mediaId.orEmpty()
        val trackRevision = stableRevision("$connectedPlaybackGeneration:$queueRevision:$index:$mediaId")
        val safeTrackId = mediaId.takeIf { it.matches(Regex("[A-Za-z0-9:_-]{1,256}")) && !it.startsWith("local:") }
        return ConnectedPlayerSnapshot(
            trackId = safeTrackId,
            title = item?.mediaMetadata?.title?.toString()?.take(256),
            artist = item?.mediaMetadata?.artist?.toString()?.take(256),
            isPlaying = player?.isPlaying == true,
            positionMs = player?.currentPosition?.coerceAtLeast(0) ?: 0L,
            durationMs = player?.duration?.takeIf { it >= 0 } ?: 0L,
            trackRevision = trackRevision,
            queueRevision = queueRevision,
        )
    }

    private var connectedPlaybackGeneration = 0L

    private fun executeConnectedCommand(command: ConnectedPlaybackCommand) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Playback commands must run on the app thread" }
        val player = controller ?: error("Playback service is offline")
        val fresh = connectedPlayerSnapshot()
        check(fresh.trackRevision == command.expectedTrackRevision) { "The playing song changed" }
        check(fresh.queueRevision == command.expectedQueueRevision) { "The queue changed" }
        when (command.action) {
            ConnectedPlaybackAction.PLAY -> { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
            ConnectedPlaybackAction.PAUSE -> player.pause()
            ConnectedPlaybackAction.NEXT -> player.seekToNextMediaItem()
            ConnectedPlaybackAction.PREVIOUS -> player.seekToPreviousMediaItem()
            ConnectedPlaybackAction.SEEK -> {
                require(command.positionMs in 0..fresh.durationMs) { "Seek position is outside the song" }
                player.seekTo(command.positionMs)
            }
        }
    }

    private fun stableRevision(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
    fun driveResolutionLaunched() { driveResolution = null }
    fun completeDriveAuthorization(approved: Boolean) = accountAction {
        val signIn = pendingGoogleAccount.also { pendingGoogleAccount = null }
        if (signIn != null) {
            check(approved) { "Google sign-in cancelled. Local data was kept." }
            val authorization = googleAccounts.signIn(signIn)
            check(authorization.resolution == null && authorization.accessToken != null) { "Google authorization was not granted. Check the Android OAuth registration and try again." }
            accountStatus = "Signed in. Enable Firebase sync to share playlists, favorites and history."
            return@accountAction
        }
        val expected = driveAccount.also { driveAccount = null } ?: error("Account request expired; try again")
        check(approved) { "Firebase connection cancelled. Local data was kept." }
        check(!offline.settings.value.offlineOnly) { "Turn off Offline mode to sync your library" }
        val result = googleAccounts.authorizeIdentity()
        check(result.resolution == null && result.accessToken != null) { "Google account permission was not granted. Check Android OAuth registration and try again." }
        check(googleAccounts.email.value == expected) { "Account changed; retry sync" }
        accountStatus = firebaseSync.synchronize(requireNotNull(result.accessToken), expected)
        loadedPlaylistId?.let(::loadPlaylist)
    }
    fun cancelAccountOperation() { accountJob?.cancel(); accountStatus = "Account operation cancelled." }
    private fun accountAction(block: suspend () -> Unit) {
        if (accountBusy) return
        accountJob = viewModelScope.launch {
            accountBusy = true
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { accountStatus = error.message ?: "Google account operation failed; retry in Profile." }
            finally { accountBusy = false }
        }
    }
    init {
        viewModelScope.launch { updater.check(manual = false) }
        connection.addListener({
            try { controller = connection.get(); controller?.addListener(listener); sync(); queueItems = queueSnapshot(controller!!) }
            catch (e: Exception) { message = "Playback service could not connect. Reopen Echo to retry." }
        }, ContextCompat.getMainExecutor(context))
        viewModelScope.launch { while (true) { sync(); delay(500) } }
    }
    private fun sync() {
        controller?.let { current = it.currentMediaItem; playing = it.isPlaying; buffering = it.playbackState == Player.STATE_BUFFERING; position = it.currentPosition.coerceAtLeast(0); duration = it.duration.coerceAtLeast(0); revision++ }
    }
    fun runAction(success: String? = null, block: suspend () -> Unit) = viewModelScope.launch {
        busy = true
        try { block(); if (success != null) message = success }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "The operation failed. Please retry." }
        finally { busy = false }
    }
    fun scan() = runAction("Local library updated") { library.scanLocal() }
    fun downloadTrack(track: Track) = runAction {
        if (tracks.value.none { it.id == track.id }) library.upsertTracks(listOf(track))
        require(track.provider in setOf("youtube", "commons")) { "This song cannot be downloaded" }
        check(!offlineSettings.value.offlineOnly) { "Turn off Offline mode to download music" }
        if (offlineSongs.value.any { it.trackId == track.id && it.status == "saved" }) offline.download(track)
        else dev.echo.app.download.SongDownloadService.enqueue(context, track.id)
    }
    fun cancelDownload(trackId: String) = runAction { offline.cancel(trackId) }
    fun removeDownload(trackId: String) = runAction { offline.remove(trackId) }
    fun clearAutomaticCache() = runAction { offline.clearAutomaticCache() }
    fun setOfflineOnly(enabled: Boolean) {
        offline.setOfflineOnly(enabled)
        if (enabled) { searchJob?.cancel(); matchingJob?.cancel() }
    }
    fun setAutomaticCache(enabled: Boolean) = offline.setAutoCache(enabled)
    fun importSpotifyFile(uri: Uri) = runAction {
        val (text, name) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Imported playlist"
            val text = (context.contentResolver.openInputStream(uri) ?: error("Cannot open playlist file")).bufferedReader().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(result.length + count <= 10_000_000) { "Playlist file is larger than 10 MB" }
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            text to name
        }
        message = library.importPlaylistFile(text, name)
    }

    private suspend fun discover(seeds: List<Track>, known: List<Track>): List<Track> {
        // shortcut: artist searches seed discovery; use a richer recommendation provider when available.
        val artists = seeds.map { it.artist }.filter { it.isNotBlank() && it != "Unknown artist" }.distinct().take(3)
        val candidates = artists.flatMap { youtube.search(it.take(200)) }
        return discoveryCandidates(candidates, known).shuffled().take(20)
    }

    fun refreshDiscovery() {
        if (offlineSettings.value.offlineOnly) { message = "Offline mode: play downloaded or local music"; return }
        if (discoveryLoading) return
        discoveryLoading = true
        viewModelScope.launch {
            try {
                val known = library.tracks.first()
                val liked = library.favorites.first()
                val history = library.recent.first()
                val preferences = library.listeningPreferences.first()
                val seeds = preferredCandidates((known.filter { it.id in liked } + history + known).distinctBy { it.id }, preferences, known)
                    .distinctBy { it.artist }
                val fresh = youtube.search("new music releases ${java.time.YearMonth.now()}")
                val suggestions = if (seeds.isEmpty()) fresh else discover(seeds, known + history.take(10))
                newMusicResults = fresh
                discoveryResults = preferredCandidates(suggestions, preferences, known)
                if (suggestions.isEmpty()) message = "No new discoveries found for these artists. Try refreshing or searching another artist."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "Discoveries couldn't load. Try refreshing." }
            finally { discoveryLoading = false }
        }
    }

    fun smartShuffle(items: List<Track>) = runAction {
        val retainedId = current?.mediaId.takeIf { controller?.playbackState == Player.STATE_READY || controller?.playbackState == Player.STATE_BUFFERING }
        val anchor = smartShuffleAnchor(items, retainedId)
        val previousEntry = queueItems.firstOrNull()?.uid
        val mixed = smartQueue(items, anchor)
        if (mixed.isNotEmpty()) {
            require(queueItems.firstOrNull()?.uid == previousEntry) { "Playback changed while finding related songs. Try Smart shuffle again." }
            val keepPosition = retainedId == anchor.id && current?.mediaId == retainedId &&
                (controller?.playbackState == Player.STATE_READY || controller?.playbackState == Player.STATE_BUFFERING)
            startPlayback(mixed, 0, false, if (keepPosition) position else 0)
            recommendedIds = mixed.filter { track -> items.none { it.id == track.id } }.map { it.id }.toSet()
        }
    }

    private suspend fun smartQueue(items: List<Track>, anchor: Track): List<Track> {
        require(!offlineSettings.value.offlineOnly) { "Smart shuffle needs internet. Use normal shuffle for downloaded music." }
        val seed = if (anchor.provider == "youtube") anchor else dev.echo.data.provider.automaticRecordingMatch(anchor, youtube.search("${anchor.title} ${anchor.artist}".take(200)))
        require(seed != null) { "No clear online recording for this song. Use normal shuffle or choose a recording first." }
        val preferences = library.listeningPreferences.first()
        val suggestions = preferredCandidates(
            discoveryCandidates(youtube.related(seed), items + library.recent.first().take(10)),
            preferences, library.tracks.first(),
        ).take(10)
        if (suggestions.isEmpty()) {
            message = "No related songs are available for this recording. Your queue is unchanged."
            return emptyList()
        }
        return smartMix(items, suggestions, anchor.id)
    }

    fun matchPlaylistAutomatically(id: String) {
        if (offlineSettings.value.offlineOnly) { message = "Turn off Offline mode to find recordings"; return }
        if (matchingJob?.isActive == true) { message = "Another playlist is being matched. Cancel it first."; return }
        matchingJob = viewModelScope.launch {
            try {
                val songs = library.playlistTracks(id)
                val missing = songs.filter { it.sourceUri == null && it.provider == "spotify" }.distinctBy { it.id }
                require(missing.isNotEmpty()) { "This playlist has no unmatched imported songs" }
                val local = library.tracks.first().filter { it.provider == "local" && it.sourceUri != null }
                val matches = mutableMapOf<String, Track>()
                missing.forEachIndexed { index, track ->
                    matchingProgress = index to missing.size
                    val localMatch = local.filter { dev.echo.data.provider.TrackMatcher.matches(track, it) }.singleOrNull()
                    val match = localMatch ?: dev.echo.data.provider.automaticRecordingMatch(track, youtube.search("${track.title} ${track.artist}".take(200)))
                    if (match != null) matches[track.id] = match
                    currentCoroutineContext().ensureActive()
                }
                library.applyRecordingMatches(id, songs.map { it.id }, matches)
                if (loadedPlaylistId == id) playlistItems = library.playlistTracks(id)
                message = "Matched ${matches.size} of ${missing.size} songs. ${missing.size - matches.size} need a recording choice."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "Matching failed; the playlist was kept unchanged." }
            finally { matchingProgress = null }
        }
    }
    fun cancelMatching() { matchingJob?.cancel(); message = "Matching cancelled; the playlist was kept unchanged." }

    internal fun jumpQueue(entry: QueueEntry) {
        val player = controller ?: return
        if (!currentQueueEntry(entry)) { message = "Queue changed. Choose the song again."; return }
        player.seekToDefaultPosition(entry.index); player.play()
    }
    private fun currentQueueEntry(entry: QueueEntry): Boolean {
        val timeline = controller?.currentTimeline ?: return false
        return entry.index in 0 until timeline.windowCount &&
            timeline.getWindow(entry.index, androidx.media3.common.Timeline.Window()).uid == entry.uid &&
            controller?.getMediaItemAt(entry.index)?.mediaId == entry.item.mediaId
    }
    internal fun removeQueue(entry: QueueEntry) {
        if (currentQueueEntry(entry) && entry.index != controller?.currentMediaItemIndex) controller?.removeMediaItem(entry.index)
    }
    internal fun moveQueue(from: QueueEntry, to: QueueEntry, captured: List<QueueEntry>) = runAction {
        val player = controller ?: error("Playback service is connecting")
        require(captured == queueItems && captured.all(::currentQueueEntry) && from != captured.firstOrNull() && to != captured.firstOrNull()) { "Queue changed. Try dragging again." }
        require(player.mediaItemCount <= 10_000) { "Queue is too large" }
        val ids = ArrayList(List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId })
        require(ids.sumOf { it.length.toLong() } <= 100_000) { "Queue is too large to reorder" }
        val args = android.os.Bundle().apply {
            putStringArrayList(QueueCommands.TRACK_IDS, ids)
            putInt(QueueCommands.FROM_INDEX, from.index); putInt(QueueCommands.TO_INDEX, to.index)
            putInt(QueueCommands.CURRENT_INDEX, player.currentMediaItemIndex)
        }
        val future = player.sendCustomCommand(SessionCommand(QueueCommands.REORDER, android.os.Bundle.EMPTY), args)
        val result = suspendCancellableCoroutine<SessionResult> { continuation ->
            continuation.invokeOnCancellation { future.cancel(false) }
            future.addListener({ if (continuation.isActive) {
                try { continuation.resume(future.get()) } catch (error: Exception) { continuation.resumeWithException(error) }
            } }, ContextCompat.getMainExecutor(context))
        }
        check(result.resultCode == SessionResult.RESULT_SUCCESS) { result.extras.getString("error") ?: "Could not reorder queue" }
    }
    fun clearUpcoming() {
        val player = controller ?: return
        queueItems.drop(1).map { it.index }.sortedDescending().forEach(player::removeMediaItem)
    }

    fun saveDiscovery() = runAction("Discovery mix saved to Library") {
        require(discoveryResults.isNotEmpty()) { "Refresh discovery first" }
        library.importPlaylist(java.util.UUID.randomUUID().toString(), "Echo discovery · ${java.time.LocalDate.now()}", discoveryResults)
    }
    fun checkUpdates() = runAction { updater.check(manual = true)?.let { message = it } }
    fun downloadUpdate(release: UpdateRelease) { try { dev.echo.app.update.UpdateDownloadService.start(context, release) } catch (error: Exception) { message = error.message ?: "Could not start update download" } }
    fun cancelUpdate() { dev.echo.app.update.UpdateDownloadService.cancel(context) }
    fun playFromSearch(query: String) = runAction {
        val songs = library.tracks.first().filter { it.sourceUri != null && (query.isBlank() || "${it.title} ${it.artist} ${it.album}".contains(query, true)) }
        require(songs.isNotEmpty()) { "No playable music matches this search" }
        play(songs)
    }
    fun play(items: List<Track>, index: Int = firstPlayableIndex(items, offlineSettings.value.offlineOnly,
        offlineSongs.value.filter { it.status == "saved" }.map { it.trackId }.toSet()), shuffle: Boolean = false) = runAction { startPlayback(items, index, shuffle) }
    private suspend fun startPlayback(items: List<Track>, index: Int, shuffle: Boolean, startPosition: Long = 0) {
        require(items.isNotEmpty()) { "There are no tracks to play" }
        val player = controller ?: error("Playback service is connecting")
        val downloaded = offlineSongs.value.filter { it.status == "saved" }.map { it.trackId }.toSet()
        fun available(track: Track) = track.sourceUri != null &&
            (!offlineSettings.value.offlineOnly || track.provider == "local" || track.id in downloaded)
        val playable = items.filter(::available)
        val selected = if (shuffle) randomPlayableIndex(items, offlineSettings.value.offlineOnly, downloaded) else index
        require(!offlineSettings.value.offlineOnly || items.getOrNull(selected)?.let(::available) == true) { "This song is not available offline. Download it first or turn off Offline mode." }
        require(playable.isNotEmpty()) { "No audio source is available. Match imported tracks to local music first." }
        require(items.getOrNull(selected)?.sourceUri != null) { "This track has no audio source. Choose Match to select a local recording." }
        selectConnectedPlaybackTarget(null)
        if (shuffle) {
            sendQueueCommand(QueueCommands.START_SHUFFLED, playable, items.take(selected).count(::available))
            recommendedIds = emptySet()
            return
        }
        library.upsertTracks(playable.filter { candidate -> tracks.value.none { it.id == candidate.id } })
        player.setMediaItems(playable.map { it.mediaItem() }, items.take(selected).count(::available), startPosition.coerceAtLeast(0))
        recommendedIds = emptySet()
        player.shuffleModeEnabled = shuffle
        player.prepare(); player.play()
    }
    fun playSelected(track: Track) = runAction { selectConnectedPlaybackTarget(null); sendQueueCommand(QueueCommands.PLAY_SELECTED, listOf(track)) }

    fun appendPlaylist(items: List<Track>, mode: QueueAddMode) = runAction {
        val playable = queueAvailable(items)
        require(playable.isNotEmpty()) { "This playlist has no songs available in the current listening mode" }
        val additions = when (mode) {
            QueueAddMode.NORMAL -> playable
            QueueAddMode.SHUFFLE -> playable.shuffled()
            QueueAddMode.SMART -> smartQueue(playable, smartShuffleAnchor(playable, null))
        }
        if (additions.isNotEmpty()) {
            sendQueueCommand(QueueCommands.APPEND, additions)
            recommendedIds = recommendedIds + additions.filter { addition -> items.none { it.id == addition.id } }.map { it.id }
            message = "Added ${additions.size} songs to queue"
        }
    }

    private fun queueAvailable(items: List<Track>): List<Track> = items.filter { track ->
        track.sourceUri != null && (!offlineSettings.value.offlineOnly || track.provider == "local" ||
            offlineSongs.value.any { it.trackId == track.id && it.status == "saved" })
    }

    private suspend fun sendQueueCommand(action: String, items: List<Track>, startIndex: Int = -1) {
        require(items.isNotEmpty() && queueAvailable(items).size == items.size) { "Choose a playable song available in the current listening mode" }
        val player = controller ?: error("Playback service is connecting")
        require(items.size <= 5000 && items.sumOf { it.id.length.toLong() } <= 100_000) { "Add up to 5000 songs at a time" }
        library.upsertTracks(items.distinctBy { it.id }.filter { candidate -> tracks.value.none { it.id == candidate.id } })
        val args = android.os.Bundle().apply {
            putStringArrayList(QueueCommands.TRACK_IDS, ArrayList(items.map { it.id }))
            if (startIndex >= 0) putInt(QueueCommands.START_INDEX, startIndex)
        }
        val future = player.sendCustomCommand(SessionCommand(action, android.os.Bundle.EMPTY), args)
        val result = suspendCancellableCoroutine<SessionResult> { continuation ->
            continuation.invokeOnCancellation { future.cancel(false) }
            future.addListener({
                if (continuation.isActive) {
                    try { continuation.resume(future.get()) }
                    catch (error: Exception) { continuation.resumeWithException(error) }
                }
            }, ContextCompat.getMainExecutor(context))
        }
        check(result.resultCode == SessionResult.RESULT_SUCCESS) { result.extras.getString("error") ?: "Queue action failed. Try again." }
    }
    fun enqueue(track: Track, next: Boolean = false) = runAction("Added to queue") {
        if (!next) { sendQueueCommand(QueueCommands.APPEND, listOf(track)); return@runAction }
        require(!offlineSettings.value.offlineOnly || track.provider == "local" || offlineSongs.value.any { it.trackId == track.id && it.status == "saved" }) {
            "Download this song before adding it to the offline queue"
        }
        require(track.sourceUri != null) { "This track has no audio source" }
        if (tracks.value.none { it.id == track.id }) library.upsertTracks(listOf(track))
        val player = controller ?: error("Playback service is connecting")
        player.addMediaItem(if (next) (player.currentMediaItemIndex + 1).coerceAtLeast(0) else player.mediaItemCount, track.mediaItem())
        if (player.playbackState == Player.STATE_IDLE) { selectConnectedPlaybackTarget(null); player.prepare() }
    }
    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun resume() { controller?.play() }
    fun favorite(track: Track) = runAction { if (tracks.value.none { it.id == track.id }) library.upsertTracks(listOf(track)); library.setFavorite(track.id, track.id !in favorites.value) }
    fun loadPlaylist(id: String) {
        playlistLoad?.cancel()
        if (loadedPlaylistId != id) { loadedPlaylistId = id; playlistItems = emptyList() }
        playlistLoad = viewModelScope.launch {
            try { val songs = library.playlistTracks(id); currentCoroutineContext().ensureActive(); if (loadedPlaylistId == id) playlistItems = songs }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "Playlist couldn't load" }
        }
    }
    fun mutatePlaylist(id: String, action: suspend () -> Unit) = runAction { action(); val songs = library.playlistTracks(id); if (loadedPlaylistId == id) playlistItems = songs }
    fun searchOnline(query: String) = search(query, commons::search) { onlineResults = it }
    fun searchYouTube(query: String) = search(query, youtube::search) { youtubeResults = it }
    private fun search(query: String, fetch: suspend (String) -> List<Track>, publish: (List<Track>) -> Unit) {
        if (offlineSettings.value.offlineOnly) { message = "Offline mode: search your saved library instead"; return }
        searchJob?.cancel()
        val request = ++searchRequest
        val submittedQuery = query.trim()
        onlineResults = emptyList()
        youtubeResults = emptyList()
        searchedQuery = submittedQuery
        isSearching = true
        searchJob = viewModelScope.launch {
            try {
                require(submittedQuery.isNotBlank()) { "Enter a search term" }
                settings.rememberSearch(submittedQuery)
                publishSearchResult({ fetch(submittedQuery) }) {
                    publish(it)
                    if (it.isEmpty()) message = "No music results found"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (request == searchRequest) message = error.message ?: "Search failed. Please retry." }
            finally { if (request == searchRequest) isSearching = false }
        }
    }
    fun spotifyCallback(uri: Uri) = runAction("Spotify connected") { spotify.handleCallback(uri); spotifyPlaylists = spotify.playlists() }
    fun fetchSpotify() = runAction { spotifyPlaylists = spotify.playlists() }
    override fun onCleared() { stopConnectedPlayback(); controller?.removeListener(listener); MediaController.releaseFuture(connection) }
}

internal fun firstPlayableIndex(items: List<Track>, offlineOnly: Boolean = false, downloaded: Set<String> = emptySet()): Int =
    items.indexOfFirst { it.sourceUri != null && (!offlineOnly || it.provider == "local" || it.id in downloaded) }

internal suspend fun <T> publishSearchResult(fetch: suspend () -> T, publish: (T) -> Unit) {
    val result = fetch()
    currentCoroutineContext().ensureActive()
    publish(result)
}

internal fun playableStartIndex(items: List<Track>, index: Int): Int {
    require(index in items.indices && items[index].sourceUri != null) { "Select a playable track" }
    return items.take(index).count { it.sourceUri != null }
}

private fun Track.mediaItem(): MediaItem = MediaItem.Builder().setMediaId(id)
    .setUri(sourceUri ?: "")
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
        .setArtworkUri(artworkUri?.let(Uri::parse)).setIsPlayable(true).setIsBrowsable(false).build()).build()

internal fun isFreshConnectedPlaybackTarget(captured: ConnectedDevice, state: ConnectedPlaybackState): Boolean {
    if (!captured.controlEnabled || state.pending) return false
    val fresh = state.devices.firstOrNull { it.id == captured.id } ?: return false
    return fresh.controlEnabled && fresh.sessionId == captured.sessionId &&
        fresh.player.trackRevision == captured.player.trackRevision &&
        fresh.player.queueRevision == captured.player.queueRevision && fresh.updatedAt >= captured.updatedAt
}
