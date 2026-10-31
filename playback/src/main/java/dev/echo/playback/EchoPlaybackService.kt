package dev.echo.playback

import android.app.PendingIntent
import android.net.Uri
import android.os.SystemClock
import android.os.Bundle
import android.util.Log
import android.util.AtomicFile
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.echo.core.PlayableSource
import dev.echo.core.Track
import dev.echo.core.connected.EngineCapture
import dev.echo.core.connected.EngineFence
import dev.echo.core.connected.EngineObservation
import dev.echo.core.connected.CONNECTED_V2_CAPABILITY_GROUP
import dev.echo.core.connected.CONNECTED_V2_CAPABILITY_MOVE
import dev.echo.core.connected.GroupRef
import dev.echo.core.connected.NativePlaybackBridgeV2
import dev.echo.core.connected.PortableRecording
import dev.echo.core.connected.QueueOccurrence
import dev.echo.core.connected.RepeatModeV2
import dev.echo.core.connected.TransferQueue
import dev.echo.core.connected.validateEngineCapture
import dev.echo.core.connected.validateEngineFence
import dev.echo.core.connected.validateEngineObservation
import dev.echo.core.connected.validateTransferQueue
import dev.echo.data.library.LibraryRepository
import dev.echo.data.library.ListeningEventType
import dev.echo.data.firebase.ConnectedPlaybackAction
import dev.echo.data.firebase.ConnectedPlaybackClient
import dev.echo.data.firebase.ConnectedPlaybackCommand
import dev.echo.data.firebase.ConnectedPlaybackParticipation
import dev.echo.data.firebase.ConnectedPlaybackV2Client
import dev.echo.data.firebase.ConnectedPlaybackV2State
import dev.echo.data.firebase.ConnectedPlaybackState
import dev.echo.data.firebase.ConnectedPlayerSnapshot
import dev.echo.data.firebase.GroupActionV2
import dev.echo.core.connected.MAX_CONNECTED_GROUP_MEMBERS
import dev.echo.data.provider.SourceResolver
import dev.echo.data.offline.OfflineRepository
import dev.echo.data.provider.YouTubeRequestException
import javax.inject.Inject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

@AndroidEntryPoint
@UnstableApi
class EchoPlaybackService : MediaLibraryService(), NativePlaybackBridgeV2 {
    @Inject lateinit var repository: LibraryRepository
    @Inject lateinit var resolver: SourceResolver
    @Inject lateinit var sleepTimer: SleepTimerController
    @Inject lateinit var offline: OfflineRepository
    @Inject lateinit var connectedParticipation: ConnectedPlaybackParticipation

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var playbackListener: Player.Listener
    private lateinit var session: MediaLibrarySession
    private lateinit var mediaSourceFactory: MediaSource.Factory
    private val audioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
    private val preferences by lazy { getSharedPreferences("echo_queue", MODE_PRIVATE) }
    private val recoveryFile by lazy { AtomicFile(java.io.File(noBackupFilesDir, "connected-playback-v2-recovery.json")) }
    override val sessionId: String = UUID.randomUUID().toString()
    private var v2QueueRevision = 0L
    private var v2OccurrenceRevision = 0L
    private var v2TransportRevision = 0L
    private var v2CaptureKey: Pair<Long, Long>? = null
    private var v2CaptureQueue: TransferQueue? = null
    private var v2GroupEnded = false
    private data class V2Stage(val operationId: String, val queue: TransferQueue, val items: List<MediaItem>,
        val stagingPlayer: ExoPlayer, val expectedTarget: EngineFence, val group: GroupRef?)
    private data class RecoveryItem(val key: String, val provider: String, val title: String, val artist: String,
        val album: String, val durationMs: Long)
    private data class PlaybackBackup(val items: List<RecoveryItem>, val index: Int, val positionMs: Long,
        val repeatMode: Int, val shuffleEnabled: Boolean, val shuffleOrder: List<Int>)
    private data class ActiveTransfer(val queue: TransferQueue, val group: GroupRef?)
    @Volatile private var v2Stage: V2Stage? = null
    @Volatile private var v2PreparingOperationId: String? = null
    @Volatile private var v2ActivatingOperationId: String? = null
    private var v2Queue: TransferQueue? = null
    @Volatile private var v2Group: GroupRef? = null
    private val displacedV2 = mutableMapOf<String, PlaybackBackup>()
    private val groupV2Backups = mutableMapOf<String, PlaybackBackup>()
    private var connectedV1: ConnectedPlaybackClient? = null
    private var connectedV2: ConnectedPlaybackV2Client? = null
    private var connectedV1Job: Job? = null
    private var connectedV2Job: Job? = null
    private var queueWasSet = false
    private val subscriptions = mutableMapOf<String, MutableSet<MediaSession.ControllerInfo>>()
    private var recoveryJob: Job? = null
    private var recoveryAttempts = 0
    private var lastPlaybackId: String? = null
    private val listeningTracker = ListeningTracker { id, event, listened, duration ->
        scope.launch {
            try { repository.recordListeningEvent(id, event, listened, duration) } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("EchoPlayback", "Could not record listening event")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val defaultFactory = DefaultMediaSourceFactory(this)
        val sources = object : MediaSource.Factory by defaultFactory {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                val deferredTrack = mediaItem.localConfiguration?.tag as? Track
                if (deferredTrack != null) {
                    val cache = PlaybackSourceCache {
                        runBlocking { resolver.resolve(deferredTrack) }
                    }
                    val data = ResolvingDataSource.Factory(DefaultDataSource.Factory(this@EchoPlaybackService)) { spec ->
                        try {
                            val source = cache.get(System.currentTimeMillis())
                            val scheme = Uri.parse(source.uri).scheme
                            if (scheme !in setOf("https", "file")) throw IOException("Invalid playback source")
                            if (offline.settings.value.offlineOnly && scheme == "https") throw IOException("Offline mode: download this song first")
                            spec.withUri(Uri.parse(source.uri)).withAdditionalHeaders(source.headers)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            val message = (error as? YouTubeRequestException)?.message
                                ?: "Online audio could not be loaded. Check your connection, retry, or update Echo if this keeps happening."
                            scope.launch { session.sendError(SessionError(SessionError.ERROR_IO, message)) }
                            // Provider exceptions may contain signed URLs; expose only an actionable message.
                            throw SourceResolutionException(message)
                        }
                    }
                    return DefaultMediaSourceFactory(this@EchoPlaybackService)
                        .setDataSourceFactory(data).setLoadErrorHandlingPolicy(EchoLoadErrorPolicy())
                        .createMediaSource(mediaItem)
                }
                val source = mediaItem.localConfiguration?.tag as? PlayableSource
                    ?: error("Media must be resolved by the library service")
                val http = DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(source.headers)
                return DefaultMediaSourceFactory(this@EchoPlaybackService)
                    .setDataSourceFactory(ResolvingDataSource.Factory(DefaultDataSource.Factory(this@EchoPlaybackService, http)) { spec ->
                        if (offline.settings.value.offlineOnly && spec.uri.scheme == "https")
                            throw IOException("Offline mode: download this song first")
                        spec
                    })
                    .setLoadErrorHandlingPolicy(EchoLoadErrorPolicy())
                    .createMediaSource(mediaItem)
            }
        }
        mediaSourceFactory = sources
        player = buildPlaybackEngine(requestAudioFocus = true)
        val builder = MediaLibrarySession.Builder(this, player, LibraryCallback())
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        session = builder.build()
        connectedParticipation.attachNativeBridge(this)
        connectedParticipation.updateDisplacedMoveOperationId(runCatching { latestDisplacedMoveOperationId() }.getOrNull())
        if (connectedParticipation.enabled) scope.launch { startConnectedParticipation() }
        scope.launch {
            while (true) {
                // A timer belongs to this device's independent queue, not a shared group timeline.
                if (sleepTimer.tick() && v2Group == null) player.pause()
                delay(1000)
            }
        }
        scope.launch {
            offline.settings.map { it.offlineOnly }.distinctUntilChanged().collect { offlineOnly ->
                if (offlineOnly) {
                    val tag = player.currentMediaItem?.localConfiguration?.tag
                    if (tag is Track || tag is PlayableSource && Uri.parse(tag.uri).scheme == "https") player.stop()
                }
            }
        }
        scope.launch {
            var observedQualifiedPlays: Map<String, Int>? = null
            combine(repository.listeningPreferences, repository.tracks, offline.settings) { preferences, tracks, _ ->
                val counts = preferences.mapValues { it.value.qualifiedPlays }
                val previous = observedQualifiedPlays
                observedQualifiedPlays = counts
                val increased = qualifiedPlayIncreases(previous, counts)
                tracks.filter { it.id in increased }.forEach { track ->
                    offline.considerAutomaticCache(track, counts[track.id] ?: 0)
                }
            }.collect()
        }
        playbackListener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                v2OccurrenceRevision++
                val outcome = when (reason) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> ListeningEventType.COMPLETED
                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> ListeningEventType.SKIPPED
                    else -> ListeningEventType.INTERRUPTED
                }
                listeningTracker.transition(mediaItem?.mediaId, player.duration, SystemClock.elapsedRealtime(), outcome,
                    replacement = reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                if (mediaItem?.mediaId != lastPlaybackId) {
                    recoveryAttempts = 0
                    recoveryJob?.cancel()
                    lastPlaybackId = mediaItem?.mediaId
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                v2TransportRevision++
                listeningTracker.playing(player.currentMediaItem?.mediaId, player.duration, isPlaying, SystemClock.elapsedRealtime())
                if (isPlaying) recoveryAttempts = 0
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) v2TransportRevision++
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) listeningTracker.finish(ListeningEventType.COMPLETED, SystemClock.elapsedRealtime())
            }

            override fun onPlayerError(error: PlaybackException) {
                listeningTracker.error(SystemClock.elapsedRealtime())
                if (error.isSourceResolutionFailure()) {
                    recoveryJob?.cancel()
                    return
                }
                val http = error.httpResponseError()
                val refreshExpiredSource = shouldRefreshOnlineSource(
                    (player.currentMediaItem?.localConfiguration?.tag as? Track)?.provider,
                    http?.responseCode, recoveryAttempts,
                )
                if (http != null && !isTransientHttpStatus(http.responseCode) && !refreshExpiredSource) {
                    recoveryJob?.cancel()
                    val retryAfter = http.headerFields.entries.firstOrNull { it.key.equals("Retry-After", true) }
                        ?.value?.firstOrNull()?.take(100)
                    val message = when (http.responseCode) {
                        429 -> "The audio source rate limited playback (HTTP 429). " +
                            (retryAfter?.let { "Wait before retrying. Retry-After: $it." } ?: "Wait before retrying.")
                        401 -> "The audio source requires authentication (HTTP 401). Reconnect the source before retrying."
                        403 -> "The audio source denied access (HTTP 403). Check source permissions before retrying."
                        else -> "The audio source rejected playback (HTTP ${http.responseCode}). No automatic retry."
                    }
                    session.sendError(SessionError(SessionError.ERROR_IO, message))
                    return
                }
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && http == null) {
                    recoveryJob?.cancel()
                    return
                }
                if (error.errorCode !in setOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED) || recoveryAttempts >= 2) return
                val failedItem = player.currentMediaItem ?: return
                val index = player.currentMediaItemIndex
                val position = player.currentPosition
                val attempt = ++recoveryAttempts
                recoveryJob?.cancel()
                recoveryJob = scope.launch {
                    delay(attempt * 1_000L)
                    val track = repository.tracks.first().find { it.id == failedItem.mediaId } ?: return@launch
                    val replacement = resolve(track) ?: return@launch
                    if (player.currentMediaItem != failedItem || player.currentMediaItemIndex != index) return@launch
                    // A fresh MediaSource also drops its cached signed URL after the one allowed 403 refresh.
                    player.replaceMediaItem(index, replacement)
                    player.seekTo(index, position)
                    player.prepare()
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) { queueWasSet = true; v2QueueRevision++ }
                if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) || events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) v2QueueRevision++
                if (queueWasSet) persistQueue()
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    listeningTracker.playing(player.currentMediaItem?.mediaId, player.duration, player.isPlaying, SystemClock.elapsedRealtime())
                }
            }
        }
        player.addListener(playbackListener)
        scope.launch {
            while (true) {
                delay(5_000)
                if (queueWasSet && player.isPlaying) persistQueue()
            }
        }
        scope.launch {
            combine(repository.tracks, repository.playlists, repository.favorites, repository.recent) { _, _, _, _ -> Unit }
                .collect {
                    for (parent in subscriptions.keys.toList()) {
                        session.notifyChildrenChanged(parent, children(parent).orEmpty().size, null)
                    }
                }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    private suspend fun startConnectedParticipation() {
        if (!connectedParticipation.enabled) return
        val email = connectedParticipation.accountEmail ?: run {
            connectedParticipation.updateLegacyState(ConnectedPlaybackState(message = "Sign in and connect Firebase to enable connected playback."))
            connectedParticipation.updateV2State(ConnectedPlaybackV2State(message = "Sign in and connect Firebase to enable connected playback."))
            return
        }
        if (connectedV1Job?.isActive == true && connectedV2Job?.isActive == true) return
        val sessionProvider: suspend () -> dev.echo.data.firebase.FirebaseSession = {
            check(connectedParticipation.enabled && connectedParticipation.accountEmail.equals(email, true)) {
                "Connected playback was disabled or the account changed"
            }
            connectedParticipation.existingSession(email)
        }
        val v1 = ConnectedPlaybackClient(
            databaseUrl = dev.echo.data.firebase.EchoFirebaseProject.realtimeDatabaseUrl,
            deviceId = connectedParticipation.deviceId,
            label = connectedParticipation.label(),
            sessionProvider = sessionProvider,
            snapshot = {
                val state = withContext(Dispatchers.Main.immediate) { observeNow() }
                ConnectedPlayerSnapshot(state.recordingId, state.title, state.artist, state.playing,
                    state.positionMs, state.durationMs, state.fence.occurrenceRevision.toString(),
                    state.fence.queueRevision.toString())
            },
            executeCommand = { command -> withContext(Dispatchers.Main.immediate) { executeConnectedV1(command, email) } },
            v2Participation = { v2PreparingOperationId != null || v2Stage != null || v2ActivatingOperationId != null || v2Group != null },
            controlEnabled = { v2PreparingOperationId == null && v2Stage == null && v2ActivatingOperationId == null && v2Group == null },
        )
        val v2 = ConnectedPlaybackV2Client(
            databaseUrl = dev.echo.data.firebase.EchoFirebaseProject.realtimeDatabaseUrl,
            deviceId = connectedParticipation.deviceId,
            label = connectedParticipation.label(),
            sessionProvider = sessionProvider,
            bridge = this,
        )
        if (connectedV1Job?.isActive != true) {
            connectedV1?.close()
            connectedV1 = v1
            connectedV1Job = scope.launch {
                val collector = launch { v1.state.collect(connectedParticipation::updateLegacyState) }
                try { v1.run() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    connectedParticipation.updateLegacyState(ConnectedPlaybackState(message = error.message ?: "Connected playback is unavailable."))
                }
                finally { collector.cancel(); if (connectedV1 === v1) connectedV1 = null }
            }
        }
        if (connectedV2Job?.isActive != true) {
            connectedV2?.close()
            connectedV2 = v2
            connectedV2Job = scope.launch {
                val collector = launch { v2.state.collect(connectedParticipation::updateV2State) }
                try { v2.run() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    connectedParticipation.updateV2State(ConnectedPlaybackV2State(message = error.message ?: "Connected playback v2 is unavailable."))
                }
                finally { collector.cancel(); if (connectedV2 === v2) connectedV2 = null }
            }
        }
    }

    private suspend fun stopConnectedParticipationGracefully() {
        val currentGroup = v2Group
        if (currentGroup != null) {
            val client = connectedV2
            val left = client != null && runCatching { client.leaveTogether(); true }.getOrDefault(false)
            if (!left && v2Group == currentGroup) {
                runCatching { leaveGroupPaused(currentGroup.groupId, currentGroup.epoch) }
            }
        }
        v2Stage?.let { stage -> runCatching { abort(stage.operationId) } }
        stopConnectedParticipation()
    }

    private fun stopConnectedParticipation() {
        connectedV1?.close(); connectedV2?.close()
        connectedV1 = null; connectedV2 = null
        connectedV1Job?.cancel(); connectedV2Job?.cancel()
        connectedV1Job = null; connectedV2Job = null
        connectedParticipation.updateLegacyState(ConnectedPlaybackState(message = "Connected playback stopped."))
        connectedParticipation.updateV2State(ConnectedPlaybackV2State(message = "Connected playback stopped."))
    }

    private fun executeConnectedV1(command: ConnectedPlaybackCommand, email: String) {
        check(connectedParticipation.enabled && connectedParticipation.accountEmail.equals(email, true)) {
            "Connected playback was disabled or the account changed"
        }
        check(v2PreparingOperationId == null && v2Stage == null && v2ActivatingOperationId == null && v2Group == null) {
            "This device is preparing or following Play together; legacy controls are paused."
        }
        when (command.action) {
            ConnectedPlaybackAction.PLAY -> { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
            ConnectedPlaybackAction.PAUSE -> player.pause()
            ConnectedPlaybackAction.NEXT -> player.seekToNextMediaItem()
            ConnectedPlaybackAction.PREVIOUS -> player.seekToPreviousMediaItem()
            ConnectedPlaybackAction.SEEK -> player.seekTo(command.positionMs.coerceIn(0, player.duration.coerceAtLeast(0)))
        }
    }

    private fun buildPlaybackEngine(requestAudioFocus: Boolean) = ExoPlayer.Builder(this)
        .setMediaSourceFactory(mediaSourceFactory)
        .setAudioAttributes(audioAttributes, requestAudioFocus)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()

    private fun engineFence() = EngineFence(sessionId, v2QueueRevision, v2OccurrenceRevision, v2TransportRevision)

    /** A MediaSession player swap has no transition callback on the newly attached listener. */
    private fun onPrimaryPlayerChanged(primary: Player) {
        val mediaId = primary.currentMediaItem?.mediaId
        listeningTracker.transition(mediaId, primary.duration, SystemClock.elapsedRealtime(),
            ListeningEventType.INTERRUPTED, replacement = true)
        if (mediaId != lastPlaybackId) {
            recoveryAttempts = 0
            recoveryJob?.cancel()
            lastPlaybackId = mediaId
        }
    }

    override suspend fun fence(): EngineFence = withContext(Dispatchers.Main.immediate) {
        check(::player.isInitialized) { "The Android playback service is unavailable" }
        engineFence()
    }

    override suspend fun capabilities(): Set<String> = setOf(CONNECTED_V2_CAPABILITY_MOVE, CONNECTED_V2_CAPABILITY_GROUP)

    override suspend fun observe(): EngineObservation = withContext(Dispatchers.Main.immediate) { observeNow() }

    private fun observeNow(): EngineObservation {
        val item = player.currentMediaItem
        val metadata = item?.mediaMetadata
        val duration = player.duration.takeIf { it >= 0 } ?: 0L
        val queue = v2Queue
        val occurrence = when {
            item == null || queue == null -> null
            v2Group != null -> queue.entries.firstOrNull()?.id
            else -> queue.entries.getOrNull(player.currentMediaItemIndex)?.id
        } ?: v2CaptureQueue?.takeIf { v2CaptureKey == (v2QueueRevision to v2OccurrenceRevision) }
            ?.entries?.firstOrNull()?.id
        return EngineObservation(
            fence = engineFence(), currentOccurrenceId = occurrence,
            positionMs = player.currentPosition.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it },
            playing = player.isPlaying,
            ready = item != null && player.playbackState in setOf(Player.STATE_READY, Player.STATE_ENDED) && player.playerError == null,
            ended = player.playbackState == Player.STATE_ENDED || v2GroupEnded,
            recordingId = item?.mediaId?.let(::safeV2RecordingId),
            title = metadata?.title?.toString()?.take(128), artist = metadata?.artist?.toString()?.take(128),
            durationMs = duration,
            error = if (player.playerError == null) null else "playback_error",
        ).also(::validateEngineObservation)
    }

    override suspend fun capture(): EngineCapture = withContext(Dispatchers.Main.immediate) {
        check(v2Group == null && v2PreparingOperationId == null && v2Stage == null && v2ActivatingOperationId == null) {
            "Finish the current connected playback operation before moving this player"
        }
        val fence = engineFence()
        val result = if (player.currentMediaItem == null || player.mediaItemCount == 0) {
            EngineCapture(fence, TransferQueue(emptyList(), RepeatModeV2.OFF, false), 0, false)
        } else {
            val library = repository.tracks.first().associateBy(Track::id)
            val physical = List(player.mediaItemCount) { index ->
                val item = player.getMediaItemAt(index)
                (item.localConfiguration?.tag as? Track) ?: library[item.mediaId]
                    ?: error("Queue position ${index + 1} (${item.mediaMetadata.title ?: "Unknown song"}) is unavailable in this library")
            }
            val currentIndex = player.currentMediaItemIndex
            val actualOrder = if (player.shuffleModeEnabled) fullShuffleOrder(player) else physical.indices.toList()
            val cursor = actualOrder.indexOf(currentIndex)
            check(cursor >= 0) { "The current song is not in the playback traversal" }
            val traversal = actualOrder.drop(cursor) + if (player.repeatMode == Player.REPEAT_MODE_ALL) actualOrder.take(cursor) else emptyList()
            val stablePhysical = v2Queue?.takeIf { prior ->
                prior.entries.size == physical.size && prior.entries.map { it.recording.id } == physical.map { it.id }
            }?.entries
            val cacheKey = v2QueueRevision to v2OccurrenceRevision
            val stable = if (stablePhysical != null) traversal.map(stablePhysical::get) else null
            val queue = when {
                stable != null -> TransferQueue(stable, player.repeatMode.toV2Repeat(), player.shuffleModeEnabled)
                v2CaptureKey == cacheKey -> v2CaptureQueue
                else -> null
            } ?: TransferQueue(traversal.mapIndexed { ordinal, physicalIndex ->
                val track = physical[physicalIndex]
                QueueOccurrence(UUID.randomUUID().toString(), portableAndroidRecording(track, ordinal))
            }, player.repeatMode.toV2Repeat(), player.shuffleModeEnabled).also {
                v2CaptureKey = cacheKey
                v2CaptureQueue = it
            }
            val duration = physical[currentIndex].durationMs.takeIf { it > 0 } ?: player.duration
            val position = player.currentPosition.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
            EngineCapture(fence, queue, position, player.isPlaying)
        }
        validateEngineCapture(result)
        result
    }

    override suspend fun prepare(operationId: String, queue: TransferQueue, expectedTarget: EngineFence,
        group: GroupRef?): EngineObservation = withContext(Dispatchers.Main.immediate) {
        require(operationId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid playback operation" }
        validateTransferQueue(queue)
        check(engineFence() == expectedTarget) { "The destination changed before preparation" }
        check(v2Group == null || group?.groupId == v2Group?.groupId) { "Leave the current Play together session first" }
        v2Stage?.let { existing ->
            check(existing.operationId == operationId && existing.queue == queue &&
                existing.expectedTarget == expectedTarget && existing.group == group) {
                "Another playback operation is already prepared; finish or cancel it first."
            }
            val current = queue.entries.first().recording
            return@withContext observeNow().copy(currentOccurrenceId = queue.entries.first().id,
                positionMs = 0, playing = false, ready = true, error = null,
                recordingId = current.id, title = current.title, artist = current.artist, durationMs = current.durationMs)
        }
        check(v2PreparingOperationId == null) {
            "Another playback operation is still preparing; try again after it finishes."
        }
        check(v2ActivatingOperationId == null) {
            "Another playback operation is being activated; try again after it finishes."
        }
        check(!offline.settings.value.offlineOnly) { "Turn off Offline mode before preparing shared playback" }
        v2PreparingOperationId = operationId
        try {
            val items = queue.entries.map { entry ->
                val track = portableAndroidTrack(entry.recording)
                resolve(track) ?: error("Could not prepare ${track.title} on this device")
            }
            val activeItems = if (group != null) items.take(1) else items
            val stagingPlayer = buildPlaybackEngine(requestAudioFocus = false)
            try {
                stagingPlayer.volume = 0f
                val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
                val stagingListener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) ready.complete(Unit)
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        ready.completeExceptionally(IllegalStateException("The recording could not be prepared on this device"))
                    }
                }
                stagingPlayer.addListener(stagingListener)
                stagingPlayer.setMediaItems(activeItems, 0, 0L)
                stagingPlayer.repeatMode = if (group != null) Player.REPEAT_MODE_OFF else when (queue.repeatMode) {
                    RepeatModeV2.ONE -> Player.REPEAT_MODE_ONE
                    RepeatModeV2.ALL -> Player.REPEAT_MODE_ALL
                    RepeatModeV2.OFF -> Player.REPEAT_MODE_OFF
                }
                stagingPlayer.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(IntArray(activeItems.size) { it }, 0L))
                stagingPlayer.shuffleModeEnabled = group == null && queue.shuffleEnabled
                stagingPlayer.prepare()
                withTimeout(30_000) { ready.await() }
                stagingPlayer.removeListener(stagingListener)
                check(engineFence() == expectedTarget) { "The destination changed while preparing" }
                v2Stage = V2Stage(operationId, queue, activeItems, stagingPlayer, expectedTarget, group)
                val current = queue.entries.first().recording
                observeNow().copy(currentOccurrenceId = queue.entries.first().id,
                    positionMs = 0, playing = false, ready = true, error = null,
                    recordingId = current.id, title = current.title, artist = current.artist,
                    durationMs = current.durationMs)
            } catch (error: Throwable) {
                stagingPlayer.release()
                throw error
            }
        } catch (error: Throwable) {
            throw error
        } finally {
            if (v2PreparingOperationId == operationId) v2PreparingOperationId = null
        }
    }

    override suspend fun activate(operationId: String, positionMs: Long, play: Boolean): EngineObservation = withContext(Dispatchers.Main.immediate) {
        val stage = v2Stage?.takeIf { it.operationId == operationId }
            ?: error("Playback preparation expired. Try again.")
        if (engineFence() != stage.expectedTarget) {
            v2Stage = null
            stage.stagingPlayer.release()
            error("The destination changed after preparation. Its current queue was kept.")
        }
        require(positionMs in 0..stage.queue.entries.first().recording.durationMs) { "Position is outside the current song" }
        val creatingGroupBackup = stage.group != null && v2Group?.groupId != stage.group.groupId && stage.group.groupId !in groupV2Backups
        val backupKey = if (stage.group == null) moveRecoveryKey(operationId) else groupRecoveryKey(stage.group.groupId)
        val previousQueue = v2Queue
        val previousGroup = v2Group
        val previousEnded = v2GroupEnded
        val priorPlayer = player
        val priorVolume = priorPlayer.volume
        if (stage.group == null || creatingGroupBackup) {
            val backup = capturePlaybackBackup()
            persistRecovery(backupKey, backup)
            if (stage.group == null) displacedV2[operationId] = backup
            else groupV2Backups[stage.group.groupId] = backup
        }
        if (engineFence() != stage.expectedTarget) {
            if (stage.group == null) displacedV2.remove(operationId)
            if (creatingGroupBackup) groupV2Backups.remove(stage.group!!.groupId)
            removeRecovery(backupKey)
            error("The destination changed while preparing; its current playback was kept")
        }
        val beforePause = engineFence()
        player.pause()
        awaitPausedIfPrepared(player)
        val pausedFence = engineFence()
        check(pausedFence.queueRevision == beforePause.queueRevision && pausedFence.occurrenceRevision == beforePause.occurrenceRevision &&
            pausedFence.transportRevision - beforePause.transportRevision in 0..1) {
            if (stage.group == null) displacedV2.remove(operationId)
            if (creatingGroupBackup) groupV2Backups.remove(stage.group!!.groupId)
            removeRecovery(backupKey)
            "The destination changed while preparing; its current playback was kept"
        }
        try {
            val stagedPlayer = stage.stagingPlayer
            stagedPlayer.setAudioAttributes(audioAttributes, true)
            stagedPlayer.volume = priorVolume
            stagedPlayer.seekTo(0, positionMs)
            stagedPlayer.playWhenReady = false
            v2Queue = stage.queue
            v2Group = stage.group
            v2GroupEnded = false
            v2ActivatingOperationId = operationId
            v2Stage = null
            v2QueueRevision++
            v2OccurrenceRevision++
            v2TransportRevision++
            priorPlayer.removeListener(playbackListener)
            session.setPlayer(stagedPlayer)
            player = stagedPlayer
            stagedPlayer.addListener(playbackListener)
            onPrimaryPlayerChanged(stagedPlayer)
            if (play) stagedPlayer.play() else stagedPlayer.pause()
            if (play) awaitPlaying(stagedPlayer) else awaitReadyPaused(stagedPlayer)
            persistQueue()
            val observed = observeNow().copy(currentOccurrenceId = stage.queue.entries.first().id,
                positionMs = stagedPlayer.currentPosition.coerceAtLeast(0), playing = stagedPlayer.isPlaying,
                ready = stagedPlayer.playbackState == Player.STATE_READY, ended = false)
            validateEngineObservation(observed)
            if (stage.group == null) connectedParticipation.updateDisplacedMoveOperationId(latestDisplacedMoveOperationId())
            v2ActivatingOperationId = null
            priorPlayer.release()
            observed
        } catch (error: Throwable) {
            runCatching {
                if (player === stage.stagingPlayer) {
                    stage.stagingPlayer.removeListener(playbackListener)
                    session.setPlayer(priorPlayer)
                    player = priorPlayer
                    priorPlayer.addListener(playbackListener)
                    onPrimaryPlayerChanged(priorPlayer)
                }
                priorPlayer.pause()
                v2Queue = previousQueue
                v2Group = previousGroup
                v2GroupEnded = previousEnded
                v2QueueRevision++
                v2OccurrenceRevision++
                v2TransportRevision++
                persistQueue()
            }.onFailure { rollbackError -> error.addSuppressed(rollbackError) }
            stage.stagingPlayer.release()
            if (stage.group != null && creatingGroupBackup) {
                groupV2Backups.remove(stage.group.groupId)
                removeRecovery(backupKey)
            } else if (stage.group == null) {
                displacedV2.remove(operationId)
                removeRecovery(backupKey)
            }
            v2ActivatingOperationId = null
            throw IllegalStateException("The destination could not start the staged recording; its prior queue was restored paused.", error)
        }
    }

    override suspend fun applyGroupTransport(ref: GroupRef, expectedOccurrenceId: String, positionMs: Long,
        play: Boolean, ended: Boolean): EngineObservation = withContext(Dispatchers.Main.immediate) {
        check(v2Group == ref) { "This player is no longer in that Play together session" }
        val current = v2Queue?.entries?.firstOrNull() ?: error("The shared queue is unavailable")
        check(current.id == expectedOccurrenceId) { "The shared song changed before the command arrived" }
        require(positionMs in 0..current.recording.durationMs) { "Position is outside the shared song" }
        player.seekTo(positionMs)
        v2GroupEnded = ended
        player.playWhenReady = play && !ended
        if (play && !ended) player.play() else player.pause()
        v2TransportRevision++
        if (play && !ended) awaitPlaying(player) else awaitReadyPaused(player)
        observeNow().copy(currentOccurrenceId = current.id, positionMs = player.currentPosition.coerceAtLeast(0),
            playing = player.isPlaying, ended = ended, ready = player.playbackState == Player.STATE_READY)
    }

    override suspend fun pauseIfUnchanged(expectedSource: EngineFence): EngineObservation? = withContext(Dispatchers.Main.immediate) {
        if (v2Group != null || v2Stage != null || engineFence() != expectedSource) return@withContext null
        val before = engineFence()
        player.pause()
        awaitReadyPaused(player)
        val after = engineFence()
        if (after.sessionId != before.sessionId || after.queueRevision != before.queueRevision ||
            after.occurrenceRevision != before.occurrenceRevision || after.transportRevision - before.transportRevision !in 0..1) {
            return@withContext null
        }
        observeNow().copy(playing = false)
    }

    override suspend fun abort(operationId: String): Unit = withContext(Dispatchers.Main.immediate) {
        v2Stage?.takeIf { it.operationId == operationId }?.let { stage ->
            v2Stage = null
            stage.stagingPlayer.release()
        }
    }

    override suspend fun restoreDisplacedPaused(operationId: String): EngineObservation = withContext(Dispatchers.Main.immediate) {
        check(v2Group == null && v2Stage == null && v2PreparingOperationId == null && v2ActivatingOperationId == null) {
            "Leave Play together or finish connected playback before restoring the previous queue."
        }
        val key = moveRecoveryKey(operationId)
        val backup = displacedV2[operationId] ?: readRecovery(key)
            ?: error("The displaced player state is no longer available")
        restoreBackupPaused(backup)
        displacedV2.remove(operationId)
        removeRecovery(key)
        observeNow()
    }

    private fun latestDisplacedMoveOperationId(): String? {
        val root = recoveryRoot()
        val stack = root.optJSONArray(MOVE_STACK_KEY) ?: return null
        require(stack.length() <= MAX_MOVE_BACKUPS) { "Saved Move recovery index is invalid" }
        return (stack.length() - 1 downTo 0).asSequence().mapNotNull { index ->
            stack.optString(index).takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) && root.has(moveRecoveryKey(it)) }
        }.firstOrNull()
    }

    override suspend fun leaveGroupPaused(groupId: String, epoch: Long): EngineObservation = withContext(Dispatchers.Main.immediate) {
        val ref = GroupRef(groupId, epoch)
        val active = v2Group
        if (active != null) check(active == ref) { "This player is no longer in that Play together session" }
        val stage = v2Stage
        if (stage != null) check(stage.group == ref) { "This player has a different playback stage" }
        check(v2PreparingOperationId == null && v2ActivatingOperationId == null) {
            "Playback preparation is still in progress; retry leaving after it finishes"
        }
        if (active == ref) {
            stage?.let {
                v2Stage = null
                it.stagingPlayer.release()
            }
            val key = groupRecoveryKey(groupId)
            val backup = groupV2Backups.remove(groupId) ?: readRecovery(key)
                ?: error("The independent player state is no longer available")
            restoreBackupPaused(backup)
            removeRecovery(key)
            return@withContext observeNow()
        }
        if (stage != null) {
            v2Stage = null
            stage.stagingPlayer.release()
            player.playWhenReady = false
            player.pause()
            awaitPausedIfPrepared(player)
            v2TransportRevision++
            persistQueue()
            return@withContext observeNow().copy(playing = false)
        }
        // No active group or matching stage means this is an idempotent retry. Do not
        // consume a group-id-only backup here: it may belong to a newer epoch.
        observeNow()
    }

    private suspend fun capturePlaybackBackup(): PlaybackBackup {
        val library = repository.tracks.first().associateBy(Track::id)
        val rows = List(player.mediaItemCount) { index ->
            val item = player.getMediaItemAt(index)
            val track = (item.localConfiguration?.tag as? Track) ?: library[item.mediaId]
                ?: error("Cannot preserve queue position ${index + 1} (${item.mediaMetadata.title ?: "Unknown song"})")
            RecoveryItem(safeV2RecordingId(track.id), track.provider, track.title.take(128), track.artist.take(128),
                track.album.take(128), track.durationMs.coerceAtLeast(0))
        }
        val order = if (player.shuffleModeEnabled && player.mediaItemCount > 0) fullShuffleOrder(player)
            else rows.indices.toList()
        return PlaybackBackup(rows, player.currentMediaItemIndex.takeIf { rows.isNotEmpty() } ?: -1,
            player.currentPosition.coerceAtLeast(0), player.repeatMode, player.shuffleModeEnabled, order)
    }

    private suspend fun restoreBackupPaused(backup: PlaybackBackup) {
        val library = repository.tracks.first()
        val items = backup.items.mapIndexed { index, row ->
            val track = when {
                row.provider == "youtube" && row.key.matches(Regex("youtube:[A-Za-z0-9_-]{11}")) ->
                    Track(row.key, row.title, row.artist, row.album, row.durationMs, provider = "youtube",
                        sourceUri = "https://www.youtube.com/watch?v=${row.key.removePrefix("youtube:")}")
                else -> library.firstOrNull { it.provider == row.provider && safeV2RecordingId(it.id) == row.key }
                    ?: error("Queue position ${index + 1} (${row.title}): its local recording is no longer available")
            }
            resolve(track) ?: error("Queue position ${index + 1} (${row.title}) could not be restored on this device")
        }
        v2Stage?.stagingPlayer?.release(); v2Stage = null
        if (items.isEmpty()) {
            player.clearMediaItems()
            v2Queue = null
        } else {
            require(backup.index in items.indices) { "The saved player cursor is invalid" }
            player.repeatMode = backup.repeatMode.takeIf { it in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL } ?: Player.REPEAT_MODE_OFF
            player.setMediaItems(items, backup.index, backup.positionMs)
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(
                backup.shuffleOrder.takeIf { it.size == items.size && it.toSet() == items.indices.toSet() }?.toIntArray()
                    ?: IntArray(items.size) { it }, 0L))
            player.shuffleModeEnabled = backup.shuffleEnabled
            player.playWhenReady = false
            player.prepare()
            player.pause()
            awaitReadyPaused(player)
        }
        v2Queue = null
        v2Group = null
        v2GroupEnded = false
        v2CaptureKey = null
        v2CaptureQueue = null
        v2QueueRevision++
        v2OccurrenceRevision++
        v2TransportRevision++
        persistQueue()
    }

    private fun recoveryRoot(): JSONObject = try {
        recoveryFile.openRead().use { stream ->
            require(stream.available() in 1..MAX_V2_RECOVERY_BYTES) { "Saved player recovery data is invalid" }
            JSONObject(stream.readBytes().toString(Charsets.UTF_8))
        }
    } catch (_: java.io.FileNotFoundException) { JSONObject() }

    private fun writeRecoveryRoot(root: JSONObject) {
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_V2_RECOVERY_BYTES) { "Saved player recovery data is too large" }
        val stream = recoveryFile.startWrite()
        try { stream.write(bytes); recoveryFile.finishWrite(stream) }
        catch (error: Exception) { recoveryFile.failWrite(stream); throw error }
    }

    private fun persistRecovery(key: String, backup: PlaybackBackup) {
        val rows = JSONArray()
        backup.items.forEach { row -> rows.put(JSONObject().put("key", row.key).put("provider", row.provider)
            .put("title", row.title).put("artist", row.artist).put("album", row.album).put("duration", row.durationMs)) }
        val root = recoveryRoot()
        root.put(key, JSONObject().put("items", rows).put("index", backup.index).put("position", backup.positionMs)
            .put("repeat", backup.repeatMode).put("shuffle", backup.shuffleEnabled)
            .put("order", JSONArray(backup.shuffleOrder)))
        if (key.startsWith("move:")) {
            val operationId = key.removePrefix("move:")
            require(operationId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid saved Move operation" }
            val stack = (0 until (root.optJSONArray(MOVE_STACK_KEY)?.length() ?: 0)).mapNotNull { index ->
                root.optJSONArray(MOVE_STACK_KEY)?.optString(index)?.takeIf {
                    it.matches(Regex("[A-Za-z0-9_-]{1,128}")) && root.has(moveRecoveryKey(it))
                }
            }.filterNot { it == operationId }.toMutableList()
            require(stack.size < MAX_MOVE_BACKUPS) { "Restore a previous queue before moving another queue to this device" }
            stack += operationId
            root.put(MOVE_STACK_KEY, JSONArray(stack))
        }
        writeRecoveryRoot(root)
    }

    private fun readRecovery(key: String): PlaybackBackup? {
        val payload = recoveryRoot().optJSONObject(key) ?: return null
        val rows = payload.optJSONArray("items") ?: error("Saved player recovery data is invalid")
        require(rows.length() <= 10_000) { "Saved player recovery queue is too large" }
        val items = List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            RecoveryItem(row.getString("key"), row.getString("provider"), row.getString("title"),
                row.getString("artist"), row.getString("album"), row.getLong("duration").coerceAtLeast(0))
        }
        val orderJson = payload.optJSONArray("order") ?: JSONArray()
        val order = List(orderJson.length()) { orderJson.getInt(it) }
        return PlaybackBackup(items, payload.optInt("index", -1), payload.optLong("position").coerceAtLeast(0),
            payload.optInt("repeat", Player.REPEAT_MODE_OFF), payload.optBoolean("shuffle"), order)
    }

    private fun removeRecovery(key: String) {
        val root = recoveryRoot()
        var changed = root.has(key)
        if (changed) root.remove(key)
        if (key.startsWith("move:")) {
            val operationId = key.removePrefix("move:")
            val stack = (0 until (root.optJSONArray(MOVE_STACK_KEY)?.length() ?: 0)).mapNotNull { index ->
                root.optJSONArray(MOVE_STACK_KEY)?.optString(index)?.takeIf {
                    it.matches(Regex("[A-Za-z0-9_-]{1,128}")) && it != operationId && root.has(moveRecoveryKey(it))
                }
            }
            if (stack.isEmpty()) root.remove(MOVE_STACK_KEY) else root.put(MOVE_STACK_KEY, JSONArray(stack))
            changed = true
        }
        if (changed) {
            writeRecoveryRoot(root)
            connectedParticipation.updateDisplacedMoveOperationId(latestDisplacedMoveOperationId())
        }
    }

    private fun moveRecoveryKey(operationId: String) = "move:$operationId"
    private fun groupRecoveryKey(groupId: String) = "group:$groupId"

    private suspend fun awaitReadyPaused(engine: ExoPlayer) {
        engine.pause()
        withTimeout(30_000) {
            while (engine.playbackState != Player.STATE_READY && engine.playbackState != Player.STATE_ENDED) {
                engine.playerError?.let { throw IllegalStateException("The recording could not be prepared on this device") }
                delay(25)
            }
            check(!engine.isPlaying) { "The player did not pause" }
        }
    }

    private suspend fun awaitPausedIfPrepared(engine: ExoPlayer) {
        if (engine.playbackState == Player.STATE_IDLE) {
            check(!engine.isPlaying) { "The player did not pause" }
        } else awaitReadyPaused(engine)
    }

    private suspend fun awaitPlaying(engine: ExoPlayer) = withTimeout(20_000) {
        while (!engine.isPlaying) {
            engine.playerError?.let { throw IllegalStateException("The recording could not start on this device") }
            delay(25)
        }
    }

    private companion object {
        const val MAX_V2_RECOVERY_BYTES = 16 * 1024 * 1024
        const val MAX_MOVE_BACKUPS = 16
        const val MOVE_STACK_KEY = "_moveStack"
    }

    override fun onDestroy() {
        sleepTimer.cancel()
        stopConnectedParticipation()
        connectedParticipation.detachNativeBridge(this)
        v2Stage?.stagingPlayer?.release()
        v2Stage = null
        if (::player.isInitialized) {
            if (queueWasSet) persistQueue()
            if (::session.isInitialized) session.release()
            player.release()
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun persistQueue() {
        val ids = JSONArray()
        repeat(player.mediaItemCount) { ids.put(player.getMediaItemAt(it).mediaId) }
        preferences.edit().putString("ids", ids.toString())
            .putInt("index", player.currentMediaItemIndex.coerceAtLeast(0))
            .putLong("position", player.currentPosition.coerceAtLeast(0))
            .putInt("repeat", player.repeatMode)
            .putBoolean("shuffle", player.shuffleModeEnabled).apply()
        runCatching { persistActiveTransfer() }.onFailure {
            if (v2Queue != null) runCatching { session.sendError(SessionError(SessionError.ERROR_IO,
                "The shared queue could not be saved for playback recovery.")) }
        }
    }

    private fun persistActiveTransfer() {
        val root = recoveryRoot()
        val queue = v2Queue
        if (queue == null) {
            if (root.has("activeTransfer")) { root.remove("activeTransfer"); writeRecoveryRoot(root) }
            return
        }
        val entries = JSONArray()
        queue.entries.forEach { entry -> entries.put(JSONObject().put("id", entry.id)
            .put("recording", JSONObject().put("id", entry.recording.id).put("title", entry.recording.title)
                .put("artist", entry.recording.artist).put("duration", entry.recording.durationMs))) }
        val value = JSONObject().put("entries", entries).put("repeat", queue.repeatMode.name)
            .put("shuffle", queue.shuffleEnabled).put("index", player.currentMediaItemIndex.coerceAtLeast(0))
        v2Group?.let { value.put("groupId", it.groupId).put("epoch", it.epoch) }
        root.put("activeTransfer", value)
        writeRecoveryRoot(root)
    }

    private fun readActiveTransfer(): ActiveTransfer? {
        val value = recoveryRoot().optJSONObject("activeTransfer") ?: return null
        val json = value.optJSONArray("entries") ?: error("Saved shared queue is invalid")
        val entries = List(json.length()) { index ->
            val row = json.getJSONObject(index)
            val recording = row.getJSONObject("recording")
            QueueOccurrence(row.getString("id"), PortableRecording(recording.getString("id"), recording.getString("title"),
                recording.getString("artist"), recording.getLong("duration")))
        }
        val group = value.optString("groupId").takeIf { it.isNotBlank() }?.let { GroupRef(it, value.getLong("epoch")) }
        return ActiveTransfer(TransferQueue(entries, runCatching { RepeatModeV2.valueOf(value.getString("repeat")) }
            .getOrDefault(RepeatModeV2.OFF), value.optBoolean("shuffle")), group)
            .also { validateTransferQueue(it.queue) }
    }

    private suspend fun resumeQueue(): MediaSession.MediaItemsWithStartPosition {
        readActiveTransfer()?.takeIf { it.group != null }?.let { saved ->
            val ref = requireNotNull(saved.group)
            val backup = readRecovery(groupRecoveryKey(ref.groupId))
                ?: error("The independent queue for the previous Play together session is unavailable")
            restoreBackupPaused(backup)
            removeRecovery(groupRecoveryKey(ref.groupId))
            return MediaSession.MediaItemsWithStartPosition(
                List(player.mediaItemCount) { player.getMediaItemAt(it) },
                player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0))
        }
        val ids = try {
            val stored = JSONArray(preferences.getString("ids", "[]"))
            List(stored.length()) { stored.getString(it) }
        } catch (_: org.json.JSONException) { emptyList() }
        val tracks = repository.tracks.first().associateBy(Track::id).toMutableMap()
        val active = readActiveTransfer()?.takeIf { it.group == null }
        active?.queue?.entries?.forEach { entry -> tracks.putIfAbsent(entry.recording.id, portableAndroidTrack(entry.recording)) }
        val resolved = mutableMapOf<String, MediaItem>()
        for (id in ids.distinct()) {
            val track = tracks[id] ?: continue
            resolve(track)?.let { resolved[id] = it }
        }
        val restored = restoreQueue(ids, preferences.getInt("index", 0),
            preferences.getLong("position", 0), resolved.keys)
            ?: throw IllegalStateException("No playable saved queue. Select a song from the library.")
        player.repeatMode = preferences.getInt("repeat", Player.REPEAT_MODE_OFF)
            .takeIf { it in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL } ?: Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = preferences.getBoolean("shuffle", false)
        if (active != null) {
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(IntArray(restored.ids.size) { it }, 0L))
            v2Queue = active.queue
            v2Group = null
        }
        return MediaSession.MediaItemsWithStartPosition(restored.ids.map { resolved.getValue(it) },
            restored.index, restored.positionMs)
    }

    private suspend fun resolve(track: Track): MediaItem? {
        offline.localSource(track)?.let { source ->
            return track.toMediaItem().buildUpon().setUri(source.uri).setTag(source).build()
        }
        if (offline.settings.value.offlineOnly && track.provider != "local") return null
        if (track.provider == "youtube") {
            val uri = track.sourceUri ?: return null
            // Force a fresh source on retries; Media3 otherwise reuses the same watch locator and signed URL cache.
            return track.toMediaItem().buildUpon().setUri(uri).setTag(track)
                .setCustomCacheKey(UUID.randomUUID().toString()).build()
        }
        val source = try { withContext(Dispatchers.IO) { resolver.resolve(track) } } catch (error: Exception) {
            if (error is CancellationException) throw error
            return null
        } ?: return null
        val scheme = Uri.parse(source.uri).scheme
        if (scheme !in setOf("content", "file", "android.resource", "https")) return null
        if (source.expiresAt?.let { it <= System.currentTimeMillis() } == true) return null
        val item = track.toMediaItem()
        return item.buildUpon()
            .setMediaMetadata(item.mediaMetadata.buildUpon().setIsPlayable(true).build())
            .setUri(source.uri).setMimeType(source.mimeType)
            .setTag(source).build()
    }

    private fun portableAndroidTrack(recording: PortableRecording): Track {
        require(recording.id.matches(Regex("youtube:[A-Za-z0-9_-]{11}"))) { "This recording cannot be resolved on Android" }
        require(recording.durationMs in 1..604_800_000L) { "The recording duration is not available for ${recording.title}" }
        return Track(recording.id, recording.title, recording.artist, durationMs = recording.durationMs,
            provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=${recording.id.removePrefix("youtube:")}")
    }

    private suspend fun search(query: String): List<Track> {
        val term = query.trim().take(200)
        if (term.isBlank()) return emptyList()
        return repository.tracks.first().filter {
            it.title.contains(term, true) || it.artist.contains(term, true) || it.album.contains(term, true)
        }
    }

    private suspend fun tracksFor(parentId: String): List<Track>? {
        val all = repository.tracks.first()
        return when {
            parentId == "local" -> all.filter { it.provider == "local" }
            parentId == "favorites" -> repository.favorites.first().let { ids -> all.filter { it.id in ids } }
            parentId == "recent" -> repository.recent.first()
            parentId.startsWith("playlist/") -> repository.playlistTracks(Uri.decode(parentId.removePrefix("playlist/")))
            parentId.startsWith("album/") -> {
                val parts = parentId.removePrefix("album/").split('/', limit = 2).map(Uri::decode)
                all.filter { parts.size == 2 && it.album == parts[0] && it.artist == parts[1] }
            }
            parentId.startsWith("artist/") -> all.filter { it.artist == Uri.decode(parentId.removePrefix("artist/")) }
            else -> null
        }
    }

    private suspend fun children(parentId: String): List<MediaItem>? = when (parentId) {
        "root" -> listOf(folder("local", "On this device"), folder("playlists", "Playlists"),
            folder("favorites", "Favorites"), folder("recent", "Recently played"),
            folder("albums", "Albums"), folder("artists", "Artists"))
        "playlists" -> repository.playlists.first().map { folder("playlist/${Uri.encode(it.id)}", it.name) }
        "albums" -> repository.tracks.first().map { it.album to it.artist }.distinct().sortedBy { it.first }.map { (album, artist) ->
            // shortcut: track artist identifies albums; use album artist when the domain supports compilations.
            folder("album/${Uri.encode(album)}/${Uri.encode(artist)}", "${album.ifBlank { "Unknown album" }} · $artist")
        }
        "artists" -> repository.tracks.first().map { it.artist }.distinct().sorted().map {
            folder("artist/${Uri.encode(it)}", it.ifBlank { "Unknown artist" })
        }
        else -> tracksFor(parentId)?.map(Track::toMediaItem)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            if (controller.uid == android.os.Process.myUid() && controller.packageName == packageName) {
                builder.setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(SessionCommand(QueueCommands.REORDER, Bundle.EMPTY))
                    .add(SessionCommand(QueueCommands.APPEND, Bundle.EMPTY))
                    .add(SessionCommand(QueueCommands.START_SHUFFLED, Bundle.EMPTY))
                    .add(SessionCommand(QueueCommands.PLAY_SELECTED, Bundle.EMPTY))
                    .add(SessionCommand(QueueCommands.CONNECTED_START, Bundle.EMPTY))
                    .add(SessionCommand(QueueCommands.CONNECTED_STOP, Bundle.EMPTY))
                    .add(SessionCommand(QueueCommands.CONNECTED_MOVE, Bundle.EMPTY))
                    .add(SessionCommand(QueueCommands.CONNECTED_TOGETHER, Bundle.EMPTY))
                    .add(SessionCommand(QueueCommands.CONNECTED_GROUP_ACTION, Bundle.EMPTY))
                    .add(SessionCommand(QueueCommands.CONNECTED_RESTORE_PREVIOUS, Bundle.EMPTY))
                    .add(SessionCommand(QueueCommands.CONNECTED_REMOTE_COMMAND, Bundle.EMPTY)).build())
            }
            return builder.build()
        }

        override fun onPlayerCommandRequest(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
            playerCommand: Int): Int {
            if ((v2Group != null || v2PreparingOperationId != null || v2Stage != null || v2ActivatingOperationId != null) && playerCommand in setOf(
                    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE,
                    Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_PREPARE)) return SessionResult.RESULT_ERROR_NOT_SUPPORTED
            return SessionResult.RESULT_SUCCESS
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> = scope.future {
            if (controller.uid != android.os.Process.myUid() || controller.packageName != packageName)
                return@future SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED)
            try {
                if (customCommand.customAction == QueueCommands.CONNECTED_START) {
                    startConnectedParticipation()
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }
                if (customCommand.customAction == QueueCommands.CONNECTED_STOP) {
                    stopConnectedParticipationGracefully()
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }
                if (customCommand.customAction == QueueCommands.CONNECTED_RESTORE_PREVIOUS) {
                    check(!connectedParticipation.v2State.value.pending) {
                        "Finish the current Move or Play together operation before restoring the previous queue."
                    }
                    check(!connectedParticipation.v2State.value.reconcilingMove) {
                        "This Move is still being reconciled. Wait for its outcome before restoring the previous queue."
                    }
                    val operationId = connectedParticipation.displacedMoveOperationId.value
                        ?: latestDisplacedMoveOperationId()
                        ?: error("There is no saved destination queue to restore")
                    restoreDisplacedPaused(operationId)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }
                when (customCommand.customAction) {
                    QueueCommands.CONNECTED_MOVE -> {
                        val target = args.getString(QueueCommands.CONNECTED_TARGET_ID).orEmpty()
                        require(target.isNotBlank()) { "Choose a destination player" }
                        (connectedV2 ?: error("Connected playback is still starting")).moveTo(target,
                            args.getBoolean(QueueCommands.CONNECTED_REPLACE, false))
                        return@future SessionResult(SessionResult.RESULT_SUCCESS)
                    }
                    QueueCommands.CONNECTED_TOGETHER -> {
                        val targets = args.getStringArrayList(QueueCommands.CONNECTED_TARGET_IDS)
                            ?: error("Choose at least one player to play together")
                        val sessions = args.getStringArrayList(QueueCommands.CONNECTED_TARGET_SESSIONS)
                            ?: error("Reconnect the selected players before starting Play together")
                        require(targets.isNotEmpty() && targets.size < MAX_CONNECTED_GROUP_MEMBERS && targets.distinct().size == targets.size &&
                            connectedParticipation.deviceId !in targets && sessions.size == targets.size &&
                            targets.all { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } &&
                            sessions.all { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }) {
                            "Choose up to ${MAX_CONNECTED_GROUP_MEMBERS - 1} other Echo players and reconnect any changed sessions"
                        }
                        val expectedSessions = targets.indices.associate { index -> targets[index] to sessions[index] }
                        (connectedV2 ?: error("Connected playback is still starting")).startTogether(targets,
                            args.getBoolean(QueueCommands.CONNECTED_REPLACE, false), expectedSessions)
                        return@future SessionResult(SessionResult.RESULT_SUCCESS)
                    }
                    QueueCommands.CONNECTED_GROUP_ACTION -> {
                        val action = GroupActionV2.valueOf(args.getString(QueueCommands.CONNECTED_ACTION).orEmpty())
                        (connectedV2 ?: error("Connected playback is still starting")).requestGroup(action,
                            args.getLong(QueueCommands.CONNECTED_POSITION, 0L))
                        return@future SessionResult(SessionResult.RESULT_SUCCESS)
                    }
                    QueueCommands.CONNECTED_REMOTE_COMMAND -> {
                        check(v2Group == null && v2PreparingOperationId == null && v2Stage == null && v2ActivatingOperationId == null) {
                            "This player is following Play together; use the shared controls."
                        }
                        val targetId = args.getString(QueueCommands.CONNECTED_TARGET_ID).orEmpty()
                        val target = (connectedV1 ?: error("Connected playback is still starting")).state.value.devices
                            .firstOrNull { it.id == targetId } ?: error("The selected player is reconnecting")
                        require(target.sessionId == args.getString(QueueCommands.CONNECTED_TARGET_SESSION) &&
                            target.player.trackRevision == args.getString(QueueCommands.CONNECTED_TRACK_REVISION) &&
                            target.player.queueRevision == args.getString(QueueCommands.CONNECTED_QUEUE_REVISION) && target.controlEnabled) {
                            "The selected player's state changed. Reconnect before sending another command."
                        }
                        val action = ConnectedPlaybackAction.valueOf(args.getString(QueueCommands.CONNECTED_REMOTE_ACTION).orEmpty())
                        (connectedV1 ?: error("Connected playback is still starting")).sendCommand(target, action,
                            args.getLong(QueueCommands.CONNECTED_POSITION, 0L))
                        return@future SessionResult(SessionResult.RESULT_SUCCESS)
                    }
                }
                check(v2Group == null && v2PreparingOperationId == null && v2Stage == null && v2ActivatingOperationId == null) {
                    "Leave or finish connected playback before changing this queue"
                }
                recoveryJob?.cancel()
                editQueue(player, repository, customCommand.customAction, args, ::resolve)
                SessionResult(SessionResult.RESULT_SUCCESS)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE, Bundle().apply { putString("error", error.message ?: "Queue action failed") })
            }
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            subscriptions.values.forEach { it.remove(controller) }
            subscriptions.entries.removeAll { it.value.isEmpty() }
        }

        override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = scope.future {
            val items = children(parentId) ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            subscriptions.getOrPut(parentId) { mutableSetOf() }.add(browser)
            session.notifyChildrenChanged(browser, parentId, items.size, params)
            LibraryResult.ofVoid(params)
        }

        override fun onUnsubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String): ListenableFuture<LibraryResult<Void>> = scope.future {
            subscriptions[parentId]?.remove(browser)
            if (subscriptions[parentId]?.isEmpty() == true) subscriptions.remove(parentId)
            LibraryResult.ofVoid()
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            LibraryResult.ofItem(folder("root", "Echo"), params)
        }

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, page: Int, pageSize: Int,
            params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            if (page < 0 || pageSize < 1) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            else children(parentId)?.let { LibraryResult.ofItemList(page(it, page, pageSize), params) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val track = repository.tracks.first().find { it.id == mediaId }
            val item = track?.toMediaItem() ?: when (mediaId) {
                "root" -> folder("root", "Echo")
                else -> children("root")?.find { it.mediaId == mediaId }
                    ?: children("playlists")?.find { it.mediaId == mediaId }
                    ?: children("albums")?.find { it.mediaId == mediaId }
                    ?: children("artists")?.find { it.mediaId == mediaId }
            }
            item?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = scope.future {
            session.notifySearchResultChanged(browser, query, search(query).size, params)
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, page: Int, pageSize: Int,
            params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            if (page < 0 || pageSize < 1) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            else LibraryResult.ofItemList(page(search(query), page, pageSize).map(Track::toMediaItem), params)
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> = scope.future {
            recoveryJob?.cancel()
            val all = repository.tracks.first().associateBy(Track::id)
            val result = mutableListOf<MediaItem>()
            for (item in mediaItems) {
                val query = item.requestMetadata.searchQuery
                val requested = when {
                    !query.isNullOrBlank() -> search(query).take(1)
                    item.mediaId in all -> listOf(all.getValue(item.mediaId))
                    else -> tracksFor(item.mediaId).orEmpty()
                }
                require(requested.isNotEmpty()) { "Unknown media id or no matching music" }
                for (track in requested) {
                    result += resolve(track) ?: throw IllegalArgumentException("No authorized playable source for ${track.title}")
                }
            }
            result
        }

        override fun onPlaybackResumption(mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            resumeQueue()
        }
    }
}

/** One extraction per prepared source; refresh just before a signed URL expires. */
internal class PlaybackSourceCache(private val resolve: () -> PlayableSource?) {
    private var source: PlayableSource? = null

    @Synchronized fun get(now: Long): PlayableSource {
        source?.takeIf { val expiry = it.expiresAt; expiry == null || expiry > now + 30_000 }?.let { return it }
        return (resolve() ?: throw IOException("No playable online source"))
            .also { source = it }
    }
}

internal fun shouldRefreshOnlineSource(provider: String?, status: Int?, attempts: Int): Boolean =
    provider == "youtube" && status == 403 && attempts == 0

internal fun qualifiedPlayIncreases(previous: Map<String, Int>?, current: Map<String, Int>): Set<String> =
    if (previous == null) emptySet() else current.filter { (id, count) -> count > (previous[id] ?: 0) }.keys

private fun portableAndroidRecording(track: Track, traversalPosition: Int): PortableRecording {
    require(track.provider == "youtube" && track.id.matches(Regex("youtube:[A-Za-z0-9_-]{11}"))) {
        "Queue position ${traversalPosition + 1} (${track.title.ifBlank { "Unknown song" }}): this recording is not portable to another Echo player"
    }
    return PortableRecording(track.id, track.title, track.artist, track.durationMs)
}

private fun safeV2RecordingId(mediaId: String): String = when {
    mediaId.matches(Regex("youtube:[A-Za-z0-9_-]{11}")) || mediaId.matches(Regex("spotify:[A-Za-z0-9]{22}")) -> mediaId
    else -> "local:" + MessageDigest.getInstance("SHA-256").digest(mediaId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun Int.toV2Repeat() = when (this) {
    Player.REPEAT_MODE_ONE -> RepeatModeV2.ONE
    Player.REPEAT_MODE_ALL -> RepeatModeV2.ALL
    else -> RepeatModeV2.OFF
}
