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
import dev.echo.core.connected.EngineCapture
import dev.echo.core.connected.portableTransferQueue
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
import dev.echo.data.firebase.ConnectedPlaybackParticipation
import dev.echo.data.firebase.ConnectedPlaybackV2State
import dev.echo.data.firebase.ConnectedPlaybackV2Client
import dev.echo.data.firebase.GroupActionV2
import dev.echo.data.firebase.parseRemoteQueueCapture
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val connectedParticipation: ConnectedPlaybackParticipation,
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
    var youtubePlaylistResults by mutableStateOf<List<dev.echo.data.provider.YouTubePlaylistSearchResult>>(emptyList()); private set
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
    private val connectedStopMutex = Mutex()
    internal val stage = AndroidStagePlayback()
    val connectedPlaybackEnabled = MutableStateFlow(!BuildConfig.STAGING && googleAccounts.email.value != null && firebaseConfigured)
    val connectedPlaybackState = MutableStateFlow(ConnectedPlaybackState())
    val connectedPlaybackV2State = MutableStateFlow(ConnectedPlaybackV2State())
    val displacedMoveOperationId = connectedParticipation.displacedMoveOperationId
    internal val connectedPlaybackDeviceIdForUi = connectedParticipation.deviceId
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
        setConnectedPlaybackEnabledAndAwaitStop(false)
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
        if (BuildConfig.STAGING) return
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
        // Kept for older UI/instrumentation callers. Signed-in presence is automatic; false
        // remains a useful explicit stop for fixture cleanup, but is never persisted.
        if (enabled) connectedPlaybackEnabled.value = googleAccounts.email.value != null && firebaseConfigured
        else viewModelScope.launch { setConnectedPlaybackEnabledAndAwaitStop(false) }
    }

    private suspend fun setConnectedPlaybackEnabledAndAwaitStop(enabled: Boolean) = connectedStopMutex.withLock {
        if (!enabled) {
            try { sendConnectedServiceCommand(QueueCommands.CONNECTED_STOP)?.let { awaitServiceCommand(it) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Local service cleanup still runs when remote/auth shutdown fails. */ }
            // Keep the current Firebase session and opt-in valid until the service has attempted the
            // coordinator leave; it always restores the native independent queue paused offline too.
        }
        connectedPlaybackEnabled.value = enabled && googleAccounts.email.value != null && firebaseConfigured
    }

    suspend fun runConnectedPlayback() {
        val email = googleAccounts.email.value?.takeIf { firebaseConfigured } ?: return
        connectedPlaybackEnabled.value = true
        if (connectedPlaybackSelectionAccount != null && connectedPlaybackSelectionAccount != email) clearConnectedPlaybackSelection()
        connectedPlaybackSelectionAccount = email
        // AccountSyncHost can become foreground before MediaController's async connection
        // completes. Return so its bounded retry loop can issue the command once ready.
        if (controller == null) {
            connectedPlaybackState.value = connectedPlaybackState.value.copy(message = "Playback service is starting; connected playback will retry.")
            return
        }
        try {
            val future = sendConnectedServiceCommand(dev.echo.playback.QueueCommands.CONNECTED_START) ?: return
            val result = awaitServiceCommand(future)
            check(result.resultCode == SessionResult.RESULT_SUCCESS) {
                result.extras.getString("error") ?: "Connected playback could not start. Retrying while Echo is open."
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            connectedPlaybackState.value = connectedPlaybackState.value.copy(
                message = error.message ?: "Connected playback could not start. Retrying while Echo is open.")
        }
    }

    fun stopConnectedPlayback() {
        viewModelScope.launch { stopConnectedPlaybackAndAwait() }
    }

    suspend fun stopConnectedPlaybackAndAwait() = withContext(NonCancellable) {
        runCatching { sendConnectedServiceCommand(dev.echo.playback.QueueCommands.CONNECTED_STOP)?.let { awaitServiceCommand(it) } }
    }

    fun moveConnectedPlayback(targetDeviceId: String, confirmedDisplacement: Boolean) =
        sendConnectedV2ServiceCommand(dev.echo.playback.QueueCommands.CONNECTED_MOVE, android.os.Bundle().apply {
            putString(dev.echo.playback.QueueCommands.CONNECTED_TARGET_ID, targetDeviceId)
            putBoolean(dev.echo.playback.QueueCommands.CONNECTED_REPLACE, confirmedDisplacement)
        })

    fun startTogether(targetDeviceIds: List<String>, expectedTargetSessions: Map<String, String>, confirmedDisplacement: Boolean) =
        if (BuildConfig.STAGING) runCatching {
            stage.startTogether(targetDeviceIds, expectedTargetSessions, androidStageTracks)
            connectedPlaybackV2State.value = stage.v2State.value
        }.onFailure { message = it.message ?: "Stage group could not start." }
        else sendConnectedV2ServiceCommand(dev.echo.playback.QueueCommands.CONNECTED_TOGETHER, android.os.Bundle().apply {
            putStringArrayList(dev.echo.playback.QueueCommands.CONNECTED_TARGET_IDS, ArrayList(targetDeviceIds))
            putStringArrayList(dev.echo.playback.QueueCommands.CONNECTED_TARGET_SESSIONS,
                ArrayList(targetDeviceIds.map { expectedTargetSessions[it].orEmpty() }))
            putBoolean(dev.echo.playback.QueueCommands.CONNECTED_REPLACE, confirmedDisplacement)
        })

    fun controlTogether(action: GroupActionV2, positionMs: Long = 0L) =
        if (BuildConfig.STAGING) {
            if (!stage.controlTogether(action, positionMs)) message = "Stage group is no longer available."
            connectedPlaybackV2State.value = stage.v2State.value
        } else sendConnectedV2ServiceCommand(dev.echo.playback.QueueCommands.CONNECTED_GROUP_ACTION, android.os.Bundle().apply {
            putString(dev.echo.playback.QueueCommands.CONNECTED_ACTION, action.name)
            putLong(dev.echo.playback.QueueCommands.CONNECTED_POSITION, positionMs)
        })

    fun seekTogetherAt(positionMs: Long, expectedGroup: dev.echo.core.connected.GroupRef,
        expectedTimelineSequence: Long, expectedOccurrenceId: String) =
        sendConnectedV2ServiceCommand(dev.echo.playback.QueueCommands.CONNECTED_GROUP_ACTION, android.os.Bundle().apply {
            putString(dev.echo.playback.QueueCommands.CONNECTED_ACTION, GroupActionV2.SEEK.name)
            putLong(dev.echo.playback.QueueCommands.CONNECTED_POSITION, positionMs)
            putString(dev.echo.playback.QueueCommands.CONNECTED_GROUP_ID, expectedGroup.groupId)
            putLong(dev.echo.playback.QueueCommands.CONNECTED_GROUP_EPOCH, expectedGroup.epoch)
            putLong(dev.echo.playback.QueueCommands.CONNECTED_GROUP_SEQUENCE, expectedTimelineSequence)
            putString(dev.echo.playback.QueueCommands.CONNECTED_GROUP_OCCURRENCE_ID, expectedOccurrenceId)
        })

    fun restorePreviousConnectedQueue() =
        sendConnectedV2ServiceCommand(dev.echo.playback.QueueCommands.CONNECTED_RESTORE_PREVIOUS)

    private fun sendConnectedV2ServiceCommand(action: String, args: android.os.Bundle = android.os.Bundle()) {
        val future = try { sendConnectedServiceCommand(action, args) }
        catch (error: Exception) {
            message = error.message ?: "Connected playback action failed. Try again."
            return
        }
        if (future == null) {
            message = "Playback service is not connected yet."
            return
        }
        future.addListener({
            try {
                val result = future.get()
                if (result.resultCode != SessionResult.RESULT_SUCCESS)
                    message = result.extras.getString("error") ?: "Connected playback action failed. Try again."
            } catch (error: Exception) {
                message = error.cause?.message ?: error.message ?: "Connected playback action failed. Try again."
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun sendConnectedServiceCommand(action: String, args: android.os.Bundle = android.os.Bundle()):
        com.google.common.util.concurrent.ListenableFuture<SessionResult>? {
        val active = controller ?: run {
            connectedPlaybackState.value = connectedPlaybackState.value.copy(message = "Playback service is not connected yet.")
            return null
        }
        return active.sendCustomCommand(androidx.media3.session.SessionCommand(action, android.os.Bundle.EMPTY), args)
    }

    private suspend fun awaitServiceCommand(future: com.google.common.util.concurrent.ListenableFuture<SessionResult>): SessionResult =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { future.cancel(false) }
            future.addListener({
                if (continuation.isActive) try { continuation.resume(future.get()) }
                catch (error: Exception) { continuation.resumeWithException(error) }
            }, ContextCompat.getMainExecutor(context))
        }

    fun selectConnectedPlaybackTarget(device: ConnectedDevice?) {
        val activeGroup = connectedPlaybackV2State.value.group
        require(device == null || activeGroup?.members?.none {
            it.deviceId == device.id && it.status != dev.echo.data.firebase.GroupMemberV2Status.LEFT
        } != false) {
            "A Play together member is controlled through the shared player."
        }
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
            (if (BuildConfig.STAGING) stage.has(target.id, target.sessionId) else isFreshConnectedPlaybackTarget(target, connectedPlaybackState.value))

    fun sendConnectedPlaybackCommand(target: ConnectedDevice, action: ConnectedPlaybackAction, positionMs: Long = 0L) {
        if (!canControlConnectedDevice(target)) {
            connectedPlaybackState.value = connectedPlaybackState.value.copy(message = "Reconnecting to ${target.label}; controls are paused until its current state is confirmed.")
            return
        }
        if (BuildConfig.STAGING) {
            if (!stage.command(target, action, positionMs))
                connectedPlaybackState.value = connectedPlaybackState.value.copy(message = "The selected Stage player changed. Select it again before controlling playback.")
            return
        }
        viewModelScope.launch {
            try {
                val fresh = connectedPlaybackState.value.devices.firstOrNull { it.id == target.id }
                check(fresh != null && canControlConnectedDevice(target)) {
                    "Reconnecting to ${target.label}; controls are paused until its current state is confirmed."
                }
                val current = requireNotNull(fresh)
                val args = android.os.Bundle().apply {
                    putString(dev.echo.playback.QueueCommands.CONNECTED_TARGET_ID, current.id)
                    putString(dev.echo.playback.QueueCommands.CONNECTED_TARGET_SESSION, current.sessionId)
                    putString(dev.echo.playback.QueueCommands.CONNECTED_TRACK_REVISION, current.player.trackRevision)
                    putString(dev.echo.playback.QueueCommands.CONNECTED_QUEUE_REVISION, current.player.queueRevision)
                    putString(dev.echo.playback.QueueCommands.CONNECTED_REMOTE_ACTION, action.name)
                    putLong(dev.echo.playback.QueueCommands.CONNECTED_POSITION, positionMs)
                }
                val service = controller ?: error("Playback service is not connected")
                val result = service.sendCustomCommand(androidx.media3.session.SessionCommand(
                    dev.echo.playback.QueueCommands.CONNECTED_REMOTE_COMMAND, android.os.Bundle.EMPTY), args)
                val commandResult = suspendCancellableCoroutine<SessionResult> { continuation ->
                    continuation.invokeOnCancellation { result.cancel(false) }
                    result.addListener({
                        if (continuation.isActive) try { continuation.resume(result.get()) }
                        catch (error: Exception) { continuation.resumeWithException(error) }
                    }, ContextCompat.getMainExecutor(context))
                }
                check(commandResult.resultCode == SessionResult.RESULT_SUCCESS) {
                    commandResult.extras.getString("error") ?: "Remote command failed."
                }
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
        if (BuildConfig.STAGING) viewModelScope.launch {
            val existing = library.tracks.first().map { it.id }.toSet()
            library.upsertTracks(androidStageTracks.filter { it.id !in existing })
            if (library.playlists.first().none { it.id == "android-stage-mix" })
                library.importPlaylist("android-stage-mix", "Stage Mix · Silent demos", androidStageTracks)
        } else viewModelScope.launch { updater.check(manual = false) }
        viewModelScope.launch {
            googleAccounts.email.collect { email -> connectedPlaybackEnabled.value = !BuildConfig.STAGING && email != null && firebaseConfigured }
        }
        if (BuildConfig.STAGING) viewModelScope.launch {
            stage.state.collect { state ->
                connectedPlaybackState.value = state
                connectedPlaybackV2State.value = stage.v2State.value
                val selectedId = selectedConnectedPlaybackTargetId.value
                val selectedSession = selectedConnectedPlaybackDevice.value?.sessionId
                if (selectedId != null && selectedSession != null) {
                    state.devices.firstOrNull { it.id == selectedId && it.sessionId == selectedSession }
                        ?.let { selectedConnectedPlaybackDevice.value = it }
                }
            }
        } else viewModelScope.launch {
            connectedParticipation.legacyState.collect { state ->
                connectedPlaybackState.value = state
                val selectedId = selectedConnectedPlaybackTargetId.value
                val selectedSession = selectedConnectedPlaybackDevice.value?.sessionId
                if (selectedId != null && selectedSession != null) {
                    state.devices.firstOrNull { it.id == selectedId && it.sessionId == selectedSession }
                        ?.let { selectedConnectedPlaybackDevice.value = it }
                }
            }
        }
        if (!BuildConfig.STAGING) viewModelScope.launch {
            connectedParticipation.v2State.collect { state ->
                val previousGroup = connectedPlaybackV2State.value.group
                connectedPlaybackV2State.value = state
                val selectedId = selectedConnectedPlaybackTargetId.value
                val selectedJoinedGroup = selectedId != null && state.group?.members?.any {
                    it.deviceId == selectedId && it.status != dev.echo.data.firebase.GroupMemberV2Status.LEFT
                } == true
                if (previousGroup != null && state.group == null || selectedJoinedGroup) clearConnectedPlaybackSelection()
            }
        }
        connection.addListener({
            try { controller = connection.get(); controller?.addListener(listener); sync(); queueItems = queueSnapshot(controller!!) }
            catch (e: Exception) { message = "Playback service could not connect. Reopen Echo to retry." }
        }, ContextCompat.getMainExecutor(context))
        viewModelScope.launch { while (true) { if (BuildConfig.STAGING) stage.tick(); sync(); delay(500) } }
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

    suspend fun previewYouTubePlaylist(link: String) = youtube.playlist(link)

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
    fun checkUpdates() { if (!BuildConfig.STAGING) runAction { updater.check(manual = true)?.let { message = it } } }
    fun downloadUpdate(release: UpdateRelease) { try { dev.echo.app.update.UpdateDownloadService.start(context, release) } catch (error: Exception) { message = error.message ?: "Could not start update download" } }
    fun cancelUpdate() { dev.echo.app.update.UpdateDownloadService.cancel(context) }
    fun playFromSearch(query: String) = runAction {
        val songs = library.tracks.first().filter { it.sourceUri != null && (query.isBlank() || "${it.title} ${it.artist} ${it.album}".contains(query, true)) }
        require(songs.isNotEmpty()) { "No playable music matches this search" }
        play(songs)
    }
    fun play(items: List<Track>, index: Int = if (BuildConfig.STAGING && selectedConnectedPlaybackTargetId.value != null) 0
        else firstPlayableIndex(items, offlineSettings.value.offlineOnly,
            offlineSongs.value.filter { it.status == "saved" }.map { it.trackId }.toSet()), shuffle: Boolean = false) = runAction { startPlayback(items, index, shuffle) }
    private suspend fun startPlayback(items: List<Track>, index: Int, shuffle: Boolean, startPosition: Long = 0) {
        require(items.isNotEmpty()) { "There are no tracks to play" }
        if (routePlaybackQueue(items, index, shuffle)) {
            recommendedIds = emptySet()
            return
        }
        val player = controller ?: error("Playback service is connecting")
        val downloaded = offlineSongs.value.filter { it.status == "saved" }.map { it.trackId }.toSet()
        fun available(track: Track) = track.sourceUri != null &&
            (!offlineSettings.value.offlineOnly || track.provider == "local" || track.id in downloaded)
        val playable = items.filter(::available)
        val selected = if (shuffle) randomPlayableIndex(items, offlineSettings.value.offlineOnly, downloaded) else index
        require(!offlineSettings.value.offlineOnly || items.getOrNull(selected)?.let(::available) == true) { "This song is not available offline. Download it first or turn off Offline mode." }
        require(playable.isNotEmpty()) { "No audio source is available. Match imported tracks to local music first." }
        require(items.getOrNull(selected)?.sourceUri != null) { "This track has no audio source. Choose Match to select a local recording." }
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
    private suspend fun routePlaybackQueue(items: List<Track>, index: Int, shuffle: Boolean): Boolean {
        val selectedId = selectedConnectedPlaybackTargetId.value
        val group = connectedPlaybackV2State.value.group.takeIf { selectedId == null }
        if (group == null && selectedId == null) return false
        val routedItems = if (shuffle) items.shuffled() else items
        val startIndex = if (shuffle) 0 else index
        require(routedItems.isNotEmpty() && startIndex in routedItems.indices) { "Choose a song before playback" }
        if (BuildConfig.STAGING) {
            if (group != null) {
                check(stage.replaceGroupQueue(routedItems, startIndex)) { "Stage group is no longer available." }
            } else {
                requireNotNull(selectedId) { "Choose a Stage player first" }
                val target = selectedConnectedPlaybackDevice.value
                check(target != null && stage.has(target.id, target.sessionId)) { "Reconnect to the selected Stage player." }
                check(stage.replaceQueue(target, routedItems, startIndex)) { "Reconnect to the selected Stage player." }
            }
            connectedPlaybackV2State.value = stage.v2State.value
            return true
        }
        portableTransferQueue(routedItems, startIndex)
        val service = controller ?: error("Playback service is connecting")
        library.upsertTracks(routedItems.drop(startIndex).distinctBy { it.id }.filter { candidate -> tracks.value.none { it.id == candidate.id } })
        val action: String
        val args = android.os.Bundle().apply {
            putStringArrayList(QueueCommands.TRACK_IDS, ArrayList(routedItems.map { it.id }))
            putInt(QueueCommands.START_INDEX, startIndex)
            if (group != null) {
                action = QueueCommands.CONNECTED_REPLACE_GROUP_QUEUE
                putString(QueueCommands.CONNECTED_GROUP_ID, group.ref.groupId)
                putLong(QueueCommands.CONNECTED_GROUP_EPOCH, group.ref.epoch)
                putLong(QueueCommands.CONNECTED_GROUP_SEQUENCE, group.timelineSequence)
            } else {
                action = QueueCommands.CONNECTED_PLAY_QUEUE_ON
                val cached = selectedConnectedPlaybackDevice.value
                val target = connectedPlaybackV2State.value.devices.firstOrNull {
                    it.id == selectedId && it.sessionId == cached?.sessionId && it.enabled
                } ?: error("The selected device restarted or is reconnecting. Select it again.")
                putString(QueueCommands.CONNECTED_TARGET_ID, target.id)
                putString(QueueCommands.CONNECTED_TARGET_SESSION, target.sessionId)
                putString(QueueCommands.CONNECTED_TARGET_FENCE_SESSION, target.snapshot.fence.sessionId)
                putLong(QueueCommands.CONNECTED_TARGET_QUEUE_REVISION, target.snapshot.fence.queueRevision)
                putLong(QueueCommands.CONNECTED_TARGET_OCCURRENCE_REVISION, target.snapshot.fence.occurrenceRevision)
                putLong(QueueCommands.CONNECTED_TARGET_TRANSPORT_REVISION, target.snapshot.fence.transportRevision)
            }
        }
        val future = service.sendCustomCommand(SessionCommand(action, android.os.Bundle.EMPTY), args)
        val result = suspendCancellableCoroutine<SessionResult> { continuation ->
            continuation.invokeOnCancellation { future.cancel(false) }
            future.addListener({
                if (continuation.isActive) try { continuation.resume(future.get()) }
                catch (error: Exception) { continuation.resumeWithException(error) }
            }, ContextCompat.getMainExecutor(context))
        }
        check(result.resultCode == SessionResult.RESULT_SUCCESS) {
            result.extras.getString("error") ?: "The focused player could not start this queue"
        }
        val status = result.extras.getString(QueueCommands.CONNECTED_OPERATION_STATUS).orEmpty()
        val reason = result.extras.getString(QueueCommands.CONNECTED_OPERATION_REASON)
        check(status == "COMPLETE") {
            when (status) {
                "FAILED" -> reason ?: "The queue was not changed."
                "PARTIAL", "UNKNOWN", "COMMITTED" -> "Play together changed its queue but confirmation is incomplete. Check every member before retrying."
                else -> reason ?: "The playback operation did not complete."
            }
        }
        return true
    }
    fun playSelected(track: Track) = runAction { startPlayback(listOf(track), 0, false) }

    suspend fun readRemoteQueue(target: ConnectedDevice): EngineCapture {
        check(canControlConnectedDevice(target)) { "Reconnect to ${target.label} before reading its queue" }
        val selectedId = selectedConnectedPlaybackTargetId.value
        val selectedSession = selectedConnectedPlaybackDevice.value?.sessionId
        require(selectedId == target.id && selectedSession == target.sessionId) { "The focused player changed; open its queue again" }
        if (BuildConfig.STAGING) return stage.readQueue(target)
            ?: error("${target.label} changed while its queue was loading; open it again")
        val v2Target = connectedPlaybackV2State.value.devices.firstOrNull {
            it.id == target.id && it.sessionId == target.sessionId && it.enabled
        } ?: error("${target.label} is reconnecting; select it again")
        val service = controller ?: error("Playback service is connecting")
        val args = android.os.Bundle().apply {
            putString(QueueCommands.CONNECTED_TARGET_ID, target.id)
            putString(QueueCommands.CONNECTED_TARGET_SESSION, target.sessionId)
            putString(QueueCommands.CONNECTED_TARGET_FENCE_SESSION, v2Target.snapshot.fence.sessionId)
            putLong(QueueCommands.CONNECTED_TARGET_QUEUE_REVISION, v2Target.snapshot.fence.queueRevision)
            putLong(QueueCommands.CONNECTED_TARGET_OCCURRENCE_REVISION, v2Target.snapshot.fence.occurrenceRevision)
            putLong(QueueCommands.CONNECTED_TARGET_TRANSPORT_REVISION, v2Target.snapshot.fence.transportRevision)
        }
        val result = awaitServiceCommand(service.sendCustomCommand(
            SessionCommand(QueueCommands.CONNECTED_READ_REMOTE_QUEUE, android.os.Bundle.EMPTY), args))
        check(result.resultCode == SessionResult.RESULT_SUCCESS) {
            result.extras.getString("error") ?: "Could not read ${target.label}'s queue"
        }
        val json = result.extras.getString(QueueCommands.CONNECTED_QUEUE_CAPTURE_JSON)
            ?: error("${target.label} returned an empty queue snapshot")
        return parseRemoteQueueCapture(json)
    }

    fun appendPlaylist(items: List<Track>, mode: QueueAddMode) = runAction {
        if (selectedConnectedPlaybackTargetId.value != null || connectedPlaybackV2State.value.group != null) {
            error("Queue append and Play next are not supported for a focused connected player yet. Use Play to replace its queue.")
        }
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
        if (selectedConnectedPlaybackTargetId.value != null || connectedPlaybackV2State.value.group != null) {
            error("Queue append and Play next are not supported for a focused connected player yet. Use Play to replace its queue.")
        }
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
    private fun sendToFocusedRemote(action: ConnectedPlaybackAction, positionMs: Long = 0L): Boolean {
        val selectedId = selectedConnectedPlaybackTargetId.value ?: return false
        if (connectedPlaybackV2State.value.group?.members?.any {
                it.deviceId == selectedId && it.status != dev.echo.data.firebase.GroupMemberV2Status.LEFT
            } == true) return false
        val target = selectedConnectedPlaybackDevice.value
        if (target == null) {
            connectedPlaybackState.value = connectedPlaybackState.value.copy(message = "Reconnect to the selected player before controlling it.")
            return true
        }
        sendConnectedPlaybackCommand(target, action, positionMs)
        return true
    }

    fun previousFocusedPlayback() {
        if (sendToFocusedRemote(ConnectedPlaybackAction.PREVIOUS)) return
        if (connectedPlaybackV2State.value.group != null)
            controlTogether(GroupActionV2.PREVIOUS)
        else controller?.seekToPrevious()
    }

    fun toggleFocusedPlayback() {
        if (selectedConnectedPlaybackTargetId.value != null && connectedPlaybackV2State.value.group?.members?.none {
                it.deviceId == selectedConnectedPlaybackTargetId.value && it.status != dev.echo.data.firebase.GroupMemberV2Status.LEFT
            } != false) {
            val playing = selectedConnectedPlaybackDevice.value?.player?.isPlaying == true
            if (sendToFocusedRemote(if (playing) ConnectedPlaybackAction.PAUSE else ConnectedPlaybackAction.PLAY)) return
        }
        val group = connectedPlaybackV2State.value.group
        if (group != null) controlTogether(if (group.timeline?.playing == true) GroupActionV2.PAUSE else GroupActionV2.PLAY)
        else controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    }

    fun nextFocusedPlayback() {
        if (sendToFocusedRemote(ConnectedPlaybackAction.NEXT)) return
        if (connectedPlaybackV2State.value.group != null) controlTogether(GroupActionV2.NEXT)
        else controller?.seekToNext()
    }

    fun seekFocusedPlayback(positionMs: Long) {
        if (sendToFocusedRemote(ConnectedPlaybackAction.SEEK, positionMs)) return
        if (connectedPlaybackV2State.value.group != null) controlTogether(GroupActionV2.SEEK, positionMs)
        else controller?.seekTo(positionMs)
    }

    fun toggle() = toggleFocusedPlayback()

    fun resume() {
        if (sendToFocusedRemote(ConnectedPlaybackAction.PLAY)) return
        if (connectedPlaybackV2State.value.group != null) controlTogether(GroupActionV2.PLAY)
        else controller?.play()
    }
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
    fun searchOnline(query: String) = search(query, commons::search, { onlineResults = it })
    fun searchYouTube(query: String) = search(query, youtube::search, { youtubeResults = it })
    fun searchYouTubePlaylists(query: String) = search(query, youtube::searchPlaylists, { youtubePlaylistResults = it }, "No playlists found")
    private fun <T> search(query: String, fetch: suspend (String) -> List<T>, publish: (List<T>) -> Unit, emptyMessage: String = "No music results found") {
        if (offlineSettings.value.offlineOnly) { message = "Offline mode: search your saved library instead"; return }
        searchJob?.cancel()
        val request = ++searchRequest
        val submittedQuery = query.trim()
        onlineResults = emptyList()
        youtubeResults = emptyList()
        youtubePlaylistResults = emptyList()
        searchedQuery = submittedQuery
        isSearching = true
        searchJob = viewModelScope.launch {
            try {
                require(submittedQuery.isNotBlank()) { "Enter a search term" }
                settings.rememberSearch(submittedQuery)
                publishSearchResult({ fetch(submittedQuery) }) {
                    publish(it)
                    if (it.isEmpty()) message = emptyMessage
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (request == searchRequest) message = error.message ?: "Search failed. Please retry." }
            finally { if (request == searchRequest) isSearching = false }
        }
    }
    fun spotifyCallback(uri: Uri) = runAction("Spotify connected") { spotify.handleCallback(uri); spotifyPlaylists = spotify.playlists() }
    fun fetchSpotify() = runAction { spotifyPlaylists = spotify.playlists() }
    override fun onCleared() { controller?.removeListener(listener); MediaController.releaseFuture(connection) }
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
