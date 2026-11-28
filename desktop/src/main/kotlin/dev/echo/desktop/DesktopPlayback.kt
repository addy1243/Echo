package dev.echo.desktop

import dev.echo.core.PlayableSource

import dev.echo.core.Track
import dev.echo.core.connected.EngineCapture
import dev.echo.core.connected.EngineFence
import dev.echo.core.connected.EngineObservation
import dev.echo.core.connected.GroupRef
import dev.echo.core.connected.CONNECTED_V2_CAPABILITY_GROUP
import dev.echo.core.connected.CONNECTED_V2_CAPABILITY_GROUP_QUEUE
import dev.echo.core.connected.CONNECTED_V2_CAPABILITY_MOVE
import dev.echo.core.connected.CONNECTED_V2_CAPABILITY_QUEUE
import dev.echo.core.connected.CONNECTED_V2_CAPABILITY_SYNC
import dev.echo.core.connected.NativePlaybackBridgeV2
import dev.echo.core.connected.PortableRecording
import dev.echo.core.connected.QueueOccurrence
import dev.echo.core.connected.RepeatModeV2
import dev.echo.core.connected.TransferQueue
import dev.echo.core.connected.validateEngineCapture
import dev.echo.core.connected.validateEngineFence
import dev.echo.core.connected.validateTransferQueue
import dev.echo.data.firebase.ConnectedPlaybackAction
import dev.echo.data.firebase.ConnectedPlaybackCommand
import dev.echo.data.provider.YouTubeProvider
import java.awt.Canvas
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.UUID
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

enum class DesktopRepeatMode { OFF, ALL, ONE }

internal fun desktopPlaybackRetryDelayMs(attempt: Int): Long = when (attempt) {
    1 -> 1_000L
    2 -> 3_000L
    else -> minOf(60_000L, 6_000L * (1L shl (attempt - 3).coerceIn(0, 10)))
}

internal fun desktopVideoSwitchSnapshotMatches(state: DesktopPlaybackState, trackId: String, occurrence: Long,
    transportRevision: Long? = null): Boolean = state.current?.id == trackId && state.occurrence == occurrence &&
    (transportRevision == null || state.transportRevision == transportRevision)

internal fun DesktopPlaybackState.canEnableFocusedVideo(expectedOccurrence: Long, attemptedOccurrence: Long? = null): Boolean =
    occurrence == expectedOccurrence && occurrence != attemptedOccurrence && current?.provider == "youtube" &&
        !loading && !videoLoading && !videoMode && !ended && error == null

internal fun desktopVideoSwitchError(currentError: String?, recoveringVideoFailure: Boolean): String? =
    if (recoveringVideoFailure) currentError else null

internal fun desktopHasSelectedAudioTrack(trackId: Int, tracks: List<uk.co.caprica.vlcj.player.base.TrackDescription>): Boolean =
    trackId >= 0 && tracks.any { it.id() == trackId }

internal fun desktopPlaybackIsActive(state: DesktopPlaybackState): Boolean = state.playing || state.retrying

internal fun finishDesktopPlaybackRetry(state: DesktopPlaybackState, trackId: String, occurrence: Long) =
    if (state.retrying && state.current?.id == trackId && state.occurrence == occurrence) state.copy(retrying = false) else state

internal fun desktopPlaybackRetryIsCurrent(attempt: Long, currentAttempt: Long) = attempt == currentAttempt

data class DesktopPlaybackState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float = 0.7f,
    val error: String? = null,
    val occurrence: Long = 0,
    val queueRevision: Long = 0,
    val transportRevision: Long = 0,
    val repeatMode: DesktopRepeatMode = DesktopRepeatMode.OFF,
    val ended: Boolean = false,
    val sleepRemainingMs: Long? = null,
    val displacedMoveOperationId: String? = null,
    val retrying: Boolean = false,
    val videoMode: Boolean = false,
    val videoLoading: Boolean = false,
    val videoError: String? = null,
    val videoAccentArgb: Int? = null,
) {
    val current: Track? get() = queue.getOrNull(index)
    val canRetainCurrent: Boolean get() = current != null && !ended && !loading && error == null
}

internal fun upcomingDesktopTracks(state: DesktopPlaybackState): List<Track> =
    if (state.current == null) emptyList() else state.queue.drop(state.index + 1) +
        if (state.repeatMode == DesktopRepeatMode.ALL) state.queue.take(state.index) else emptyList()

internal fun stopDesktopPlaybackForOffline(offlineOnly: Boolean, remoteActive: Boolean, cachedAudio: Boolean) =
    offlineOnly && (remoteActive || cachedAudio)

internal fun stageShouldYieldToPlayback(buffering: Boolean, wantsPlaying: Boolean, videoMode: Boolean,
    streamedAudio: Boolean, streamedVideo: Boolean, videoNeedsAudio: Boolean): Boolean =
    buffering && wantsPlaying && if (videoMode) streamedVideo || (videoNeedsAudio && streamedAudio) else streamedAudio

internal fun stageCanPrepareFast(state: DesktopPlaybackState, wantsPlaying: Boolean, localAudio: Boolean,
    localVideo: Boolean, videoNeedsAudio: Boolean): Boolean = !wantsPlaying ||
    (!state.loading && !state.videoLoading && state.current != null && state.error == null &&
        if (state.videoMode) localVideo && (!videoNeedsAudio || localAudio) else localAudio)

internal fun shouldSuspendStageSpeculation(state: DesktopPlaybackState, wantsPlaying: Boolean, localAudio: Boolean,
    localVideo: Boolean, videoNeedsAudio: Boolean): Boolean = wantsPlaying &&
    (state.videoLoading || state.videoMode && !(localVideo && (!videoNeedsAudio || localAudio)))

internal fun stageCacheableTrack(track: Track) = track.provider == "youtube" && track.id.matches(Regex("youtube:[A-Za-z0-9_-]{11}"))

internal fun stageBufferPositions(state: DesktopPlaybackState): List<Pair<Int, Track?>> = (-2..2).map { offset ->
    val index = state.index + offset
    val actual = when {
        state.current == null -> -1
        state.repeatMode == DesktopRepeatMode.ALL -> Math.floorMod(index, state.queue.size)
        index in state.queue.indices -> index
        else -> -1
    }
    offset to state.queue.getOrNull(actual)
}

internal fun stagePreparationWindow(state: DesktopPlaybackState): List<Track> {
    val current = state.current ?: return emptyList()
    fun step(index: Int, direction: Int): Int? = when {
        state.repeatMode == DesktopRepeatMode.ALL && state.queue.isNotEmpty() ->
            (index + direction + state.queue.size) % state.queue.size
        index + direction in state.queue.indices -> index + direction
        else -> null
    }
    val behind = buildList {
        var previous = state.index
        repeat(2) {
            previous = step(previous, -1) ?: return@repeat
            add(state.queue[previous])
        }
    }
    val ahead = buildList {
        var next = state.index
        repeat(2) {
            next = step(next, 1) ?: return@repeat
            add(state.queue[next])
        }
    }
    return behind + current + ahead
}

internal fun stagePreparationPriority(state: DesktopPlaybackState): List<Track> {
    val window = stagePreparationWindow(state)
    if (window.isEmpty()) return emptyList()
    val behindCount = if (state.repeatMode == DesktopRepeatMode.ALL) 2 else minOf(2, state.index)
    val ahead = window.drop(behindCount + 1)
    return ahead + window.drop(behindCount).take(1) + window.take(behindCount)
}

internal data class StagePreparationFence(
    val windowIds: List<String>,
    val targetEnabled: Boolean = true, val offlineOnly: Boolean = false, val connectedGroup: Boolean = false,
)

internal fun stagePreparationFence(state: DesktopPlaybackState, targetEnabled: Boolean = true,
    wantsPlaying: Boolean? = null, offlineOnly: Boolean = false, connectedGroup: Boolean = false) = StagePreparationFence(
    stagePreparationWindow(state).map { it.id }, targetEnabled, offlineOnly, connectedGroup,
)

internal fun stagePreparationFenceIsCurrent(captured: StagePreparationFence, current: DesktopPlaybackState,
    targetEnabled: Boolean = true, wantsPlaying: Boolean? = null, offlineOnly: Boolean = false, connectedGroup: Boolean = false) =
    captured == stagePreparationFence(current, targetEnabled, wantsPlaying, offlineOnly, connectedGroup)

internal data class StagePreparationFlowKey(
    val fence: StagePreparationFence,
    val version: Long,
)

internal data class StagePreparationPlan(
    val fence: StagePreparationFence,
    val eligible: Boolean,
    val reconcile: Boolean,
    val window: List<Track>,
    val order: List<Track>,
    val slots: List<Pair<Int, Track?>>,
    val videoFirst: Boolean = false,
    val refreshVersion: Long = 0,
    val videoCachingEnabled: Boolean = true,
    val speculationEnabled: Boolean = true,
)

internal fun stagePreparationFlowKey(state: DesktopPlaybackState, version: Long, targetEnabled: Boolean,
    wantsPlaying: Boolean? = null, offlineOnly: Boolean = false, connectedGroup: Boolean = false) = StagePreparationFlowKey(
    stagePreparationFence(state, targetEnabled, wantsPlaying, offlineOnly, connectedGroup), version,
)

internal fun shouldPrepareStageMedia(state: DesktopPlaybackState, targetEnabled: Boolean,
    wantsPlaying: Boolean = false, offlineOnly: Boolean = false, connectedGroup: Boolean = false) =
    targetEnabled && !state.loading && !state.ended && state.current != null && !offlineOnly && !connectedGroup

internal suspend fun runStagePreparationWorker(
    desired: StateFlow<StagePreparationPlan?>,
    videoEnabled: Boolean,
    isReady: (Track, Boolean) -> Boolean,
    beginTransfer: (Track, Boolean) -> Long,
    reportProgress: (Track, Boolean, Long, StageCacheProgress) -> Unit,
    finishTransfer: (Track, Boolean, Long, StageBufferTransferPhase?) -> Unit,
    prepare: suspend (Track, Boolean, (StageCacheProgress) -> Unit) -> Boolean,
    afterTransfer: (StagePreparationPlan, Track) -> Unit,
) {
    val failed = mutableSetOf<Triple<StagePreparationFence, String, Boolean>>()
    var preemptionRetryDelayMs = 300L
    while (currentCoroutineContext().isActive) {
        val plan = desired.filterNotNull().first { it.eligible && it.speculationEnabled }
        val prepareVideo = videoEnabled && plan.videoCachingEnabled
        val mediaOrder = if (plan.videoFirst) plan.order.flatMap { track ->
            listOf(track to false) + if (prepareVideo) listOf(track to true) else emptyList()
        } else plan.order.map { it to false } + if (prepareVideo) plan.order.map { it to true } else emptyList()
        val nextMedia = mediaOrder.firstOrNull { (candidate, isVideo) ->
            !isReady(candidate, isVideo) && Triple(plan.fence, candidate.id, isVideo) !in failed
        }
        if (nextMedia == null) {
            desired.first { it == null || it.fence != plan.fence || !it.speculationEnabled || it.videoFirst != plan.videoFirst ||
                it.refreshVersion != plan.refreshVersion || it.videoCachingEnabled != plan.videoCachingEnabled || !it.eligible }
            failed.clear()
            continue
        }
        val (next, isVideo) = nextMedia
        val token = beginTransfer(next, isVideo)
        var terminalPhase: StageBufferTransferPhase? = null
        try {
            val completed = coroutineScope {
                val transfer = async(Dispatchers.IO) {
                    prepare(next, isVideo) { progress ->
                        if (progress.phase in setOf(StageBufferTransferPhase.READY, StageBufferTransferPhase.FAILED,
                                StageBufferTransferPhase.UNAVAILABLE)) terminalPhase = progress.phase
                        reportProgress(next, isVideo, token, progress)
                    }
                }
                val obsolete = async {
                    desired.filterNotNull().first { latest ->
                        !latest.reconcile || !latest.speculationEnabled || next.id !in latest.window.map { it.id } ||
                            isVideo && !latest.videoCachingEnabled
                    }
                }
                val result = select<Boolean?> {
                    transfer.onAwait { it }
                    obsolete.onAwait { null }
                }
                if (result == null) transfer.cancelAndJoin() else obsolete.cancel()
                result
            }
            finishTransfer(next, isVideo, token, when (completed) {
                true -> StageBufferTransferPhase.READY
                false -> terminalPhase ?: StageBufferTransferPhase.FAILED
                null -> null
            })
            if (completed == false) failed += Triple(plan.fence, next.id, isVideo)
            if (completed == true) preemptionRetryDelayMs = 300L
        } catch (_: StageStreamCapturePending) {
            finishTransfer(next, isVideo, token, StageBufferTransferPhase.CAPTURING)
        } catch (_: StageStreamCaptureDiscarded) {
            finishTransfer(next, isVideo, token, StageBufferTransferPhase.WAITING)
            delay(300)
        } catch (cancelled: CancellationException) {
            finishTransfer(next, isVideo, token, null)
            if (!currentCoroutineContext().isActive) throw cancelled
            delay(preemptionRetryDelayMs)
            preemptionRetryDelayMs = (preemptionRetryDelayMs * 2).coerceAtMost(5_000L)
        } catch (_: Exception) {
            finishTransfer(next, isVideo, token, StageBufferTransferPhase.FAILED)
            failed += Triple(plan.fence, next.id, isVideo)
        }
        afterTransfer(plan, next)
        if (desired.value?.fence != plan.fence) failed.clear()
    }
}

internal fun appendedDesktopQueue(state: DesktopPlaybackState, items: List<Track>, next: Boolean): DesktopPlaybackState? {
    val current = state.current ?: return null
    val upcoming = upcomingDesktopTracks(state)
    val combined = listOf(current) + if (next) items + upcoming else upcoming + items
    if (combined.size > 10_000) return null
    return state.copy(queue = combined, index = 0, queueRevision = state.queueRevision + 1)
}

internal fun retainedDesktopQueue(state: DesktopPlaybackState, items: List<Track>, expectedOccurrence: Long,
    anchorId: String?, expectedQueueRevision: Long?): DesktopPlaybackState? {
    if (state.occurrence != expectedOccurrence || expectedQueueRevision != null && state.queueRevision != expectedQueueRevision ||
        items.isEmpty() || items.size > 10_000 || items.any { it.sourceUri == null || it.provider !in setOf("local", "youtube") }) return null
    if (anchorId == null) return state.copy(queue = items.toList(), index = 0, queueRevision = state.queueRevision + 1)
    if (!state.canRetainCurrent || anchorId != state.current?.id || items.first().id != anchorId) return null
    return state.copy(queue = listOf(state.current!!) + items.drop(1), index = 0, queueRevision = state.queueRevision + 1)
}

internal fun editDesktopUpcoming(state: DesktopPlaybackState, from: Int, to: Int?, expectedOccurrence: Long,
    expectedQueueRevision: Long): DesktopPlaybackState? {
    val upcoming = (state.index + 1 until state.queue.size).toList() +
        if (state.repeatMode == DesktopRepeatMode.ALL) (0 until state.index).toList() else emptyList()
    if (state.current == null || state.occurrence != expectedOccurrence || state.queueRevision != expectedQueueRevision ||
        from !in upcoming || to != null && to !in upcoming) return null
    val order = if (state.repeatMode == DesktopRepeatMode.ALL) listOf(state.index) + upcoming else state.queue.indices.toList()
    val items = order.map { state.queue[it] }.toMutableList()
    val moved = items.removeAt(order.indexOf(from))
    if (to != null) items.add(order.indexOf(to), moved)
    return state.copy(queue = items, index = order.indexOf(state.index), queueRevision = state.queueRevision + 1)
}

internal fun portableDesktopRecording(track: Track): PortableRecording {
    require(track.provider == "youtube" && track.id.matches(Regex("youtube:[A-Za-z0-9_-]{11}"))) {
        "${track.title.ifBlank { "This song" }} is not available on the other device. Only public YouTube recordings can move between Echo players."
    }
    return PortableRecording(track.id, track.title, track.artist, track.durationMs)
}

internal fun transferQueueForDesktop(state: DesktopPlaybackState, stableEntries: List<QueueOccurrence>? = null): TransferQueue {
    val current = state.current ?: error("There is no current song to move")
    val traversal = listOf(current) + upcomingDesktopTracks(state)
    require(traversal.size <= 500) { "The queue has more than 500 entries and cannot be moved" }
    val entries = if (stableEntries != null && stableEntries.map { it.recording.id } == traversal.map { it.id }) stableEntries
    else traversal.mapIndexed { index, track ->
        val recording = try { portableDesktopRecording(track) }
        catch (error: IllegalArgumentException) { throw IllegalArgumentException("Queue position ${index + 1} (${track.title.ifBlank { "Unknown song" }}): ${error.message}") }
        QueueOccurrence(UUID.randomUUID().toString(), recording)
    }
    return TransferQueue(entries, when (state.repeatMode) {
        DesktopRepeatMode.OFF -> RepeatModeV2.OFF
        DesktopRepeatMode.ONE -> RepeatModeV2.ONE
        DesktopRepeatMode.ALL -> RepeatModeV2.ALL
    }, shuffleEnabled = false)
}

private fun portableTrack(recording: PortableRecording) = Track(
    id = recording.id, title = recording.title, artist = recording.artist, durationMs = recording.durationMs,
    provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=${recording.id.removePrefix("youtube:")}",
)

internal fun safeEngineRecordingId(track: Track): String = if (track.provider == "youtube" &&
    track.id.matches(Regex("youtube:[A-Za-z0-9_-]{11}"))) track.id else "local:" +
    java.security.MessageDigest.getInstance("SHA-256").digest(track.id.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

class DesktopPlayback internal constructor(
    private val provider: YouTubeProvider = YouTubeProvider(),
    private val offline: DesktopOfflineRepository? = null,
    recoveryFile: File = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "EchoMusic/playback-v2-recovery.json"),
    findLocalTrack: (String) -> Track? = { null },
    private val stageVideoEnabled: Boolean = false,
    private val stageMediaCache: DesktopStageMediaCache? = null,
    private val stageVideoCache: DesktopStageVideoCache? = null,
    private val onHistory: (Track, String, Long) -> Unit = { _, _, _ -> },
) : Closeable, NativePlaybackBridgeV2 {
    fun isV2Participating(): Boolean = activeGroup != null || preparedTransfer != null ||
        preparingTransferOperationId != null || activatingTransferOperationId != null
    fun isInConnectedGroup(): Boolean = activeGroup != null

    /** Restore the saved independent queue paused when presence is stopped or the account changes. */
    suspend fun leaveConnectedGroupPaused() {
        val ref = activeGroup ?: run {
            withContext(dispatcher) { preparedTransfer?.let(::discardPrepared) }
            return
        }
        leaveGroupPaused(ref.groupId, ref.epoch)
    }
    private val dispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "Echo audio").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(DesktopPlaybackState())
    val state: StateFlow<DesktopPlaybackState> = mutableState
    private var factory: MediaPlayerFactory? = null
    private var player: MediaPlayer? = null
    private var source: DesktopAudioSource? = null
    private var currentAudioUri: String? = null
    private var cachedLease: CachedStageFile? = null
    private var videoSource: DesktopAudioSource? = null
    private var videoRelayCandidate: DesktopAudioSource? = null
    private var cachedVideoLease: CachedStageVideo? = null
    private var videoLocator: String? = null
    private var videoRequiresAudioSlave = false
    val videoCanvas = Canvas()
    private var videoSurface: uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurface? = null
    private var boundVideoCanvas: Canvas? = null
    private var videoCanvasHandoffId = 0L
    @Volatile private var switchingVideoGeneration: Long? = null
    private var videoPlaybackFailedGeneration: Long? = null
    private data class NativeSlot(val player: MediaPlayer?, val source: DesktopAudioSource?, val state: DesktopPlaybackState,
        val cachedLease: CachedStageFile? = null, val audioUri: String? = null)
    private data class PreparedTransfer(
        val operationId: String,
        val queue: TransferQueue,
        val tracks: List<Track>,
        val player: MediaPlayer,
        val source: DesktopAudioSource,
        val expectedTarget: EngineFence,
        val group: GroupRef?,
    )
    private val bridgeSessionId = UUID.randomUUID().toString()
    @Volatile private var preparedTransfer: PreparedTransfer? = null
    @Volatile private var preparingTransferOperationId: String? = null
    @Volatile private var activatingTransferOperationId: String? = null
    private val displacedTransfers = mutableMapOf<String, NativeSlot>()
    private val groupBackups = mutableMapOf<String, NativeSlot>()
    private val recovery = DesktopPlaybackRecovery(recoveryFile, findLocalTrack)
    @Volatile private var activeGroup: GroupRef? = null
    private var activeTransferQueue: TransferQueue? = null
    private var cachedCaptureKey: Pair<Long, Long>? = null
    private var cachedCaptureQueue: TransferQueue? = null
    override val sessionId: String get() = bridgeSessionId
    private var load: Job? = null
    private var retryJob: Job? = null
    private var retryAttempt = 0
    private var retryToken = 0L
    private var generation = 0L
    private var listenedMs = 0L
    private var playedRecorded = false
    private var terminalRecorded = false
    private var wantsPlaying = false
        set(value) {
            val changed = field != value
            field = value
            refreshStageTransferRate()
            if (changed) stagePreparationVersion.value++
        }
    private var sleepJob: Job? = null
    private var playingSince: TimeSource.Monotonic.ValueTimeMark? = null
    @Volatile private var closed = false
    private var remoteActive = false
    @Volatile private var stagePreparationEnabled = false
    @Volatile private var stageVideoPreparationPriority = false
    @Volatile private var stageVideoCachingEnabled = true
    private val stagePreparationVersion = MutableStateFlow(0L)
    private val mutableStageBufferStatus = MutableStateFlow(StageBufferStatus(emptyList()))
    internal val stageBufferStatus: StateFlow<StageBufferStatus> = mutableStageBufferStatus

    init {
        mutableState.value = mutableState.value.copy(displacedMoveOperationId =
            runCatching { recovery.latestMoveOperationId() }.getOrNull())
        stageMediaCache?.let { cache ->
            val desired = MutableStateFlow<StagePreparationPlan?>(null)
            val trackerLock = Any()
            val audio = mutableMapOf<String, StageBufferTransfer>()
            val video = mutableMapOf<String, StageBufferTransfer>()
            val transferTokens = mutableMapOf<Pair<String, Boolean>, Long>()
            var nextTransferToken = 0L
            var lastReconciledIds: Set<String>? = null
            var visibleSlots = emptyList<Pair<Int, Track?>>()
            var visibleIds = emptySet<String>()
            fun publishLocked() {
                val idle = StageBufferTransfer(StageBufferTransferPhase.IDLE)
                mutableStageBufferStatus.value = StageBufferStatus(visibleSlots.mapNotNull { (offset, track) ->
                    track?.let { StageBufferPosition(offset, it, audio[it.id] ?: idle, video[it.id] ?: idle) }
                })
            }
            fun beginTransfer(id: String, isVideo: Boolean): Long = synchronized(trackerLock) {
                val token = ++nextTransferToken
                transferTokens[id to isVideo] = token
                val transfers = if (isVideo) video else audio
                transfers[id] = StageBufferTransfer(StageBufferTransferPhase.PREPARING)
                publishLocked()
                token
            }
            fun progress(id: String, isVideo: Boolean, token: Long, value: StageCacheProgress) {
                scope.launch {
                    synchronized(trackerLock) {
                        if (id !in visibleIds || transferTokens[id to isVideo] != token) return@synchronized
                        val transfers = if (isVideo) video else audio
                        if (transfers[id]?.phase == StageBufferTransferPhase.READY &&
                            value.phase != StageBufferTransferPhase.READY) return@synchronized
                        transfers[id] = StageBufferTransfer(value.phase, value.receivedBytes, value.totalBytes)
                        publishLocked()
                    }
                }
            }
            fun finishTransfer(id: String, isVideo: Boolean, token: Long, phase: StageBufferTransferPhase?) {
                synchronized(trackerLock) {
                    if (transferTokens[id to isVideo] != token) return@synchronized
                    transferTokens.remove(id to isVideo)
                    val cached = if (isVideo) stageVideoCache?.contains(id) == true else cache.contains(id)
                    (if (isVideo) video else audio)[id] = StageBufferTransfer(
                        phase ?: if (cached) StageBufferTransferPhase.READY else StageBufferTransferPhase.IDLE,
                    )
                    publishLocked()
                }
            }

            scope.launch {
                combine(state, stagePreparationVersion) { playback, version -> playback to version }
                    .collect { (playback, version) ->
                        val offlineOnly = offline?.state?.value?.offlineOnly == true
                        val inLocalWindow = stagePreparationEnabled && !offlineOnly && activeGroup == null &&
                            playback.current != null && !playback.ended
                        val window = if (inLocalWindow) stagePreparationWindow(playback).filter(::stageCacheableTrack) else emptyList()
                        val eligible = shouldPrepareStageMedia(playback, stagePreparationEnabled,
                            offlineOnly = offlineOnly, connectedGroup = activeGroup != null)
                        val order = if (eligible) stagePreparationPriority(playback)
                            .filter(::stageCacheableTrack).distinctBy { it.id } else emptyList()
                        val fence = stagePreparationFence(playback, targetEnabled = stagePreparationEnabled,
                            offlineOnly = offlineOnly, connectedGroup = activeGroup != null)
                        val localAudio = currentAudioUri != null && (source == null || source?.isFullyLocal == true)
                        val localVideo = cachedVideoLease != null && videoSource == null || videoSource?.isFullyLocal == true
                        val suspendSpeculation = shouldSuspendStageSpeculation(playback, wantsPlaying,
                            localAudio, localVideo, videoRequiresAudioSlave)
                        val plan = StagePreparationPlan(fence, eligible, inLocalWindow, window, order,
                            stageBufferPositions(playback), stageVideoPreparationPriority, version,
                            videoCachingEnabled = stageVideoCachingEnabled, speculationEnabled = !suspendSpeculation)
                        desired.value = plan
                        if (plan.reconcile) {
                            val ids = window.mapTo(mutableSetOf()) { it.id }
                            if (lastReconciledIds != ids) {
                                val audioReconciled = runCatching { cache.reconcile(ids) }.isSuccess
                                val videoReconciled = runCatching { stageVideoCache?.reconcile(ids) }.isSuccess
                                if (audioReconciled && videoReconciled) lastReconciledIds = ids
                            }
                        } else lastReconciledIds = null
                        synchronized(trackerLock) {
                            visibleSlots = plan.slots
                            visibleIds = plan.slots.mapNotNullTo(mutableSetOf()) { it.second?.id }
                            transferTokens.keys.retainAll { it.first in visibleIds }
                            audio.keys.retainAll(visibleIds)
                            video.keys.retainAll(visibleIds)
                            plan.slots.mapNotNull { it.second }.distinctBy { it.id }.forEach { track ->
                                val cacheable = stageCacheableTrack(track)
                                audio.putIfAbsent(track.id, StageBufferTransfer(when {
                                    !cacheable -> StageBufferTransferPhase.UNAVAILABLE
                                    cache.contains(track.id) -> StageBufferTransferPhase.READY
                                    eligible -> StageBufferTransferPhase.WAITING
                                    else -> StageBufferTransferPhase.IDLE
                                }))
                                val captureState = cache.streamCaptureStatus(track.id)
                                when {
                                    cache.contains(track.id) -> audio[track.id] = StageBufferTransfer(StageBufferTransferPhase.READY)
                                    captureState == DesktopStageStreamCapture.Status.CAPTURING ->
                                        audio[track.id] = StageBufferTransfer(StageBufferTransferPhase.CAPTURING)
                                    audio[track.id]?.phase == StageBufferTransferPhase.CAPTURING ->
                                        audio[track.id] = StageBufferTransfer(if (eligible) StageBufferTransferPhase.WAITING else StageBufferTransferPhase.IDLE)
                                }
                                video.putIfAbsent(track.id, StageBufferTransfer(when {
                                    !cacheable || stageVideoCache == null -> StageBufferTransferPhase.UNAVAILABLE
                                    stageVideoCache.contains(track.id) -> StageBufferTransferPhase.READY
                                    eligible -> StageBufferTransferPhase.WAITING
                                    else -> StageBufferTransferPhase.IDLE
                                }))
                                when {
                                    stageVideoCache?.contains(track.id) == true -> video[track.id] = StageBufferTransfer(StageBufferTransferPhase.READY)
                                    stageVideoCache?.streamCaptureStatus(track.id) == DesktopStageStreamCapture.Status.CAPTURING ->
                                        video[track.id] = StageBufferTransfer(StageBufferTransferPhase.CAPTURING)
                                    video[track.id]?.phase == StageBufferTransferPhase.CAPTURING ->
                                        video[track.id] = StageBufferTransfer(if (eligible) StageBufferTransferPhase.WAITING else StageBufferTransferPhase.IDLE)
                                }
                            }
                            publishLocked()
                        }
                    }
            }

            scope.launch {
                runStagePreparationWorker(
                    desired = desired,
                    videoEnabled = stageVideoCache != null,
                    isReady = { track, isVideo -> if (isVideo) stageVideoCache?.contains(track.id) == true ||
                        stageVideoCache?.streamCaptureStatus(track.id) == DesktopStageStreamCapture.Status.CAPTURING else
                        cache.contains(track.id) || cache.streamCaptureStatus(track.id) == DesktopStageStreamCapture.Status.CAPTURING },
                    beginTransfer = { track, isVideo -> beginTransfer(track.id, isVideo) },
                    reportProgress = { track, isVideo, token, value -> progress(track.id, isVideo, token, value) },
                    finishTransfer = { track, isVideo, token, phase -> finishTransfer(track.id, isVideo, token, phase) },
                    prepare = { track, isVideo, report ->
                        if (isVideo) {
                            val prepared = stageVideoCache?.prepare(track, report)
                            prepared?.close()
                            prepared != null
                        } else {
                            cache.prepare(track, report).close()
                            true
                        }
                    },
                    afterTransfer = { _, next ->
                    val latest = desired.value
                    if (latest?.reconcile == true && next.id !in latest.window.map { it.id }) {
                        val ids = latest.window.mapTo(mutableSetOf()) { it.id }
                        runCatching { cache.reconcile(ids) }
                        runCatching { stageVideoCache?.reconcile(ids) }
                    }
                    },
                )
            }
        }
    }

    internal fun setStagePreparationEnabled(enabled: Boolean) {
        if (stageMediaCache == null || closed || stagePreparationEnabled == enabled) return
        stagePreparationEnabled = enabled
        if (!enabled) {
            stageMediaCache?.setForegroundBuffering(false)
            state.value.current?.id?.let { id ->
                stageMediaCache?.cancelStreamCapture(id)
                stageVideoCache?.cancelStreamCapture(id)
            }
        }
        stagePreparationVersion.value++
    }

    @Synchronized
    internal fun setStageVideoCachingEnabled(enabled: Boolean) {
        if (closed || stageVideoCachingEnabled == enabled) return
        stageVideoCachingEnabled = enabled
        if (!enabled) state.value.current?.id?.let { stageVideoCache?.cancelStreamCapture(it) }
        stagePreparationVersion.value++
    }

    @Synchronized
    internal fun setStageVideoPreparationPriority(enabled: Boolean) {
        if (closed || stageVideoPreparationPriority == enabled) return
        stageVideoPreparationPriority = enabled
        stagePreparationVersion.value++
    }

    private fun fenceNow(): EngineFence = EngineFence(bridgeSessionId, state.value.queueRevision,
        state.value.occurrence, state.value.transportRevision)

    private fun checkFence(expected: EngineFence) {
        validateEngineFence(expected)
        check(!state.value.videoLoading) { "Wait for the video switch to finish before moving this queue." }
        check(!state.value.videoMode) { "Turn off video before connected playback operations." }
        check(fenceNow() == expected) { "Playback changed before the operation could be applied" }
    }

    override suspend fun fence(): EngineFence = withContext(dispatcher) {
        check(!closed) { "This player session has closed" }
        check(!state.value.videoLoading) { "Wait for the video switch to finish before moving this queue." }
        check(!state.value.videoMode) { "Turn off video before connected playback operations." }
        fenceNow()
    }

    override suspend fun capture(): EngineCapture = withContext(dispatcher) {
        check(!closed) { "This player session has closed" }
        check(activeGroup == null && preparedTransfer == null && preparingTransferOperationId == null && activatingTransferOperationId == null) {
            "Finish the current connected playback operation before moving this queue."
        }
        val current = state.value
        check(!current.videoLoading) { "Wait for the video switch to finish before moving this queue." }
        check(!current.videoMode) { "Turn off video before connected playback operations." }
        require(current.current == null || current.canRetainCurrent) { "The current song is not ready to move" }
        val capture = if (current.current == null) EngineCapture(fenceNow(),
            TransferQueue(emptyList(), RepeatModeV2.OFF, false), 0, false)
        else {
            val physical = activeTransferQueue?.entries
            val order = (current.index until current.queue.size).toList() +
                if (current.repeatMode == DesktopRepeatMode.ALL) (0 until current.index).toList() else emptyList()
            val stable = physical?.takeIf { it.size == current.queue.size }?.let { entries -> order.map(entries::get) }
            val cacheKey = current.queueRevision to current.occurrence
            val queue = cachedCaptureQueue?.takeIf { cachedCaptureKey == cacheKey && stable == null }
                ?: transferQueueForDesktop(current, stable).copy(shuffleEnabled = activeTransferQueue?.shuffleEnabled == true).also {
                    cachedCaptureKey = cacheKey
                    cachedCaptureQueue = it
                }
            queue.let {
                EngineCapture(fenceNow(), it,
                    current.positionMs.coerceIn(0, current.durationMs.coerceAtLeast(0)), current.playing)
            }
        }
        validateEngineCapture(capture)
        capture
    }

    override suspend fun observe(): EngineObservation = withContext(dispatcher) { observationNow() }

    override suspend fun capabilities(): Set<String> = setOf(CONNECTED_V2_CAPABILITY_MOVE, CONNECTED_V2_CAPABILITY_GROUP,
        CONNECTED_V2_CAPABILITY_QUEUE, CONNECTED_V2_CAPABILITY_GROUP_QUEUE, CONNECTED_V2_CAPABILITY_SYNC)

    private fun observationNow(): EngineObservation {
        val current = state.value
        val track = current.current
        val occurrenceId = activeTransferQueue?.entries?.getOrNull(current.index)?.id
            ?: cachedCaptureQueue?.takeIf { cachedCaptureKey == (current.queueRevision to current.occurrence) }?.entries?.firstOrNull()?.id
        val nativeState = player?.status()?.state()?.name
        return EngineObservation(
            fence = fenceNow(), currentOccurrenceId = occurrenceId,
            positionMs = current.positionMs.coerceAtLeast(0), playing = current.playing,
            ready = track != null && !current.loading && current.error == null && nativeState in setOf("PLAYING", "PAUSED"),
            recordingId = track?.let(::safeEngineRecordingId), title = track?.title?.take(128),
            artist = track?.artist?.take(128), durationMs = current.durationMs.coerceAtLeast(0),
            ended = current.ended, error = current.error?.let { "playback_error" },
        )
    }

    override suspend fun prepare(operationId: String, queue: TransferQueue, expectedTarget: EngineFence,
        group: GroupRef?): EngineObservation = withContext(dispatcher) {
        require(operationId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid playback operation" }
        validateTransferQueue(queue)
        checkFence(expectedTarget)
        preparedTransfer?.let { existing ->
            check(existing.operationId == operationId && existing.queue == queue &&
                existing.expectedTarget == expectedTarget && existing.group == group) {
                "Another playback operation is already prepared; finish or cancel it first."
            }
            val track = existing.tracks.first()
            return@withContext observationNow().copy(currentOccurrenceId = queue.entries.first().id,
                positionMs = 0, playing = false, ready = true, error = null,
                recordingId = track.id, title = track.title.take(128), artist = track.artist.take(128),
                durationMs = track.durationMs)
        }
        check(preparingTransferOperationId == null) {
            "Another playback operation is still preparing; try again after it finishes."
        }
        check(activatingTransferOperationId == null) {
            "Another playback operation is being activated; try again after it finishes."
        }
        check(activeGroup == null || group?.groupId == activeGroup?.groupId) {
            "Leave the current Play together session before preparing a Move."
        }
        check(!closed && offline?.state?.value?.offlineOnly != true) { "Turn off Offline mode before preparing shared playback" }
        preparingTransferOperationId = operationId
        try {
            val track = portableTrack(queue.entries.first().recording)
            val resolved = provider.resolve(track) ?: error("Could not resolve ${track.title} on this device")
            check(validDesktopYouTubeSource(resolved)) { "The recording returned an unsupported audio source" }
            val stagedSource = relayedSource(resolved) {
                provider.resolve(track)?.takeIf { !stageVideoEnabled || sameDesktopYouTubeRepresentation(resolved, it) }
            }
            val createdFactory = playbackFactory()
            val stagedPlayer = if (stageVideoEnabled) createdFactory.mediaPlayers().newEmbeddedMediaPlayer()
                else createdFactory.mediaPlayers().newMediaPlayer()
            try {
                val ready = CompletableDeferred<Unit>()
                stagedPlayer.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                    override fun positionChanged(mediaPlayer: MediaPlayer, newPosition: Float) { ready.complete(Unit) }
                    override fun error(mediaPlayer: MediaPlayer) { ready.completeExceptionally(IllegalStateException("VLC could not prepare ${track.title}")) }
                })
                stagedPlayer.audio().setMute(true)
                check(stagedPlayer.media().play(stagedSource.uri)) { "VLC could not prepare ${track.title}" }
                withTimeout(20_000) { ready.await() }
                stagedPlayer.controls().setPause(true)
                awaitNativePaused(stagedPlayer)
                stagedPlayer.controls().setTime(0)
                stagedPlayer.audio().setMute(false)
                checkFence(expectedTarget)
                val stage = PreparedTransfer(operationId, queue, queue.entries.map { portableTrack(it.recording) },
                    stagedPlayer, stagedSource, expectedTarget, group)
                preparedTransfer = stage
                observationNow().copy(currentOccurrenceId = queue.entries.first().id, positionMs = 0, playing = false,
                    ready = true, error = null, recordingId = track.id, title = track.title.take(128),
                    artist = track.artist.take(128), durationMs = track.durationMs)
            } catch (error: Throwable) {
                stagedPlayer.release()
                stagedSource.close()
                throw error
            }
        } finally {
            if (preparingTransferOperationId == operationId) preparingTransferOperationId = null
        }
    }

    override suspend fun activate(operationId: String, positionMs: Long, play: Boolean): EngineObservation =
        activateStagedQueue(operationId, positionMs, play, preserveMoveBackup = true)

    override suspend fun activateQueueReplacement(operationId: String, positionMs: Long, play: Boolean): EngineObservation =
        activateStagedQueue(operationId, positionMs, play, preserveMoveBackup = false)

    private suspend fun activateStagedQueue(operationId: String, positionMs: Long, play: Boolean,
        preserveMoveBackup: Boolean): EngineObservation = withContext(dispatcher) {
        check(!state.value.videoLoading && !state.value.videoMode) { "Turn off video before changing this queue." }
        val candidate = preparedTransfer?.takeIf { it.operationId == operationId }
        val stage = candidate ?: error("Playback preparation expired. Try again.")
        if (state.value.videoLoading || fenceNow() != stage.expectedTarget) {
            discardPrepared(stage)
            error("The destination changed after preparation. Its current queue was kept.")
        }
        require(positionMs in 0..stage.queue.entries.first().recording.durationMs) { "Position is outside the current song" }
        val prior = player?.let { native ->
            NativeSlot(native, source, state.value.copy(positionMs = native.status().time().coerceAtLeast(0), playing = false), cachedLease, currentAudioUri)
        } ?: NativeSlot(null, source, state.value.copy(playing = false), cachedLease, currentAudioUri)
        var newlySavedBackupKey: String? = null
        if (stage.group == null && preserveMoveBackup) {
            val key = moveRecoveryKey(operationId)
            recovery.save(key, prior.state)
            newlySavedBackupKey = key
            displacedTransfers[operationId] = prior
        } else if (stage.group != null && activeGroup?.groupId != stage.group.groupId && stage.group.groupId !in groupBackups) {
            val key = groupRecoveryKey(stage.group)
            recovery.save(key, prior.state)
            newlySavedBackupKey = key
            groupBackups[stage.group.groupId] = prior
        }

        val oldPlayer = player
        val oldSource = source
        val oldCachedLease = cachedLease
        val oldGroup = activeGroup
        val oldTransferQueue = activeTransferQueue
        val oldIntent = state.value.playing
        oldPlayer?.controls()?.setPause(true)
        oldPlayer?.let { awaitNativePaused(it) }
        if (state.value.videoLoading || fenceNow() != stage.expectedTarget) {
            if (oldIntent && oldPlayer != null && oldPlayer === player && state.value.occurrence == stage.expectedTarget.occurrenceRevision &&
                state.value.queueRevision == stage.expectedTarget.queueRevision) oldPlayer.controls().play()
            newlySavedBackupKey?.let(recovery::remove)
            displacedTransfers.remove(operationId)
            stage.group?.let { ref -> if (newlySavedBackupKey == groupRecoveryKey(ref)) groupBackups.remove(ref.groupId) }
            if (preserveMoveBackup) update { it.copy(displacedMoveOperationId = recovery.latestMoveOperationId()) }
            error("The destination changed while preparing. Its current playback was kept.")
        }
        if (oldPlayer != null && oldPlayer !== prior.player) oldPlayer.release()
        if (oldSource != null && oldSource !== prior.source && oldSource !== stage.source) oldSource.close()
        load?.cancel()
        val ticket = ++generation
        player = stage.player
        source = stage.source
        cachedLease = null
        currentAudioUri = stage.source.uri
        attachPlaybackEvents(stage.player, ticket)
        wantsPlaying = play
        activeTransferQueue = stage.queue
        activeGroup = stage.group
        activatingTransferOperationId = operationId
        update { it.copy(queue = stage.tracks, index = 0, playing = false, loading = false,
            videoMode = false, videoLoading = false, videoError = null,
            positionMs = positionMs, durationMs = stage.queue.entries.first().recording.durationMs,
            error = null, occurrence = ticket, queueRevision = it.queueRevision + 1,
            transportRevision = it.transportRevision + 1,
            repeatMode = when (stage.queue.repeatMode) {
                RepeatModeV2.OFF -> DesktopRepeatMode.OFF
                RepeatModeV2.ONE -> DesktopRepeatMode.ONE
                RepeatModeV2.ALL -> DesktopRepeatMode.ALL
            }, ended = false) }
        stage.player.controls().setTime(positionMs)
        if (play) stage.player.controls().play() else stage.player.controls().setPause(true)
        preparedTransfer = null
        try {
            if (play) withTimeout(15_000) {
                while (!stage.player.status().isPlaying || generation != ticket) delay(25)
            }
            else withTimeout(5_000) { awaitNativePaused(stage.player) }
        } catch (error: Throwable) {
            // Preserve the original independent slot, and never strand a failed group replacement.
            stage.player.controls().setPause(true)
            stage.player.release()
            if (stage.source !== prior.source) stage.source.close()
            player = prior.player
            source = prior.source
            cachedLease = prior.cachedLease
            currentAudioUri = prior.audioUri
            activeTransferQueue = oldTransferQueue
            activeGroup = oldGroup
            if (prior.player != null) {
                val restoreTicket = ++generation
                attachPlaybackEvents(prior.player, restoreTicket)
                prior.player.controls().setTime(prior.state.positionMs)
                prior.player.controls().setPause(true)
                update { prior.state.copy(playing = false, loading = false, occurrence = restoreTicket,
                    queueRevision = it.queueRevision + 1, transportRevision = it.transportRevision + 1) }
            } else {
                update { prior.state.copy(playing = false, loading = false, ended = false,
                    queueRevision = it.queueRevision + 1, transportRevision = it.transportRevision + 1) }
            }
            if (stage.group == null && preserveMoveBackup) {
                displacedTransfers.remove(operationId)
                recovery.remove(moveRecoveryKey(operationId))
                update { it.copy(displacedMoveOperationId = recovery.latestMoveOperationId()) }
            }
            activatingTransferOperationId = null
            throw IllegalStateException("The new player could not start; the previous queue was restored paused.", error)
        }
        if (stage.group != null && groupBackups[stage.group.groupId]?.player !== oldPlayer) {
            oldPlayer?.release()
            if (oldSource !== stage.source) oldSource?.close()
            if (oldCachedLease !== cachedLease) oldCachedLease?.close()
        } else if (stage.group == null && !preserveMoveBackup) {
            if (oldCachedLease !== cachedLease) oldCachedLease?.close()
        }
        activatingTransferOperationId = null
        if (stage.group == null && preserveMoveBackup) update { it.copy(displacedMoveOperationId = recovery.latestMoveOperationId()) }
        observationNow().copy(currentOccurrenceId = stage.queue.entries.first().id, ready = true,
            positionMs = stage.player.status().time().coerceAtLeast(0), playing = stage.player.status().isPlaying)
    }

    override suspend fun applyGroupTransport(ref: GroupRef, expectedOccurrenceId: String, positionMs: Long,
        play: Boolean, ended: Boolean): EngineObservation = withContext(dispatcher) {
        check(activeGroup == ref) { "This player is no longer in that Play together session" }
        val queue = activeTransferQueue ?: error("The shared queue is unavailable")
        val current = queue.entries.firstOrNull() ?: error("The shared queue is empty")
        check(current.id == expectedOccurrenceId) { "The shared song changed before the command arrived" }
        val native = player ?: error("The group player is unavailable")
        require(positionMs in 0..current.recording.durationMs) { "Position is outside the shared song" }
        native.controls().setTime(positionMs)
        wantsPlaying = play && !ended
        if (wantsPlaying) native.controls().play() else native.controls().setPause(true)
        update { it.copy(positionMs = positionMs, playing = false, ended = ended,
            transportRevision = it.transportRevision + 1) }
        if (wantsPlaying) withTimeout(15_000) { while (!native.status().isPlaying) delay(25) }
        else awaitNativePaused(native)
        observationNow().copy(currentOccurrenceId = current.id, positionMs = native.status().time().coerceAtLeast(0),
            playing = native.status().isPlaying, ended = ended, ready = true)
    }

    override suspend fun pauseIfUnchanged(expectedSource: EngineFence): EngineObservation? = withContext(dispatcher) {
        if (closed || activeGroup != null || fenceNow() != expectedSource) return@withContext null
        val active = player ?: return@withContext null
        active.controls().setPause(true)
        awaitNativePaused(active)
        if (fenceNow() != expectedSource) return@withContext null
        wantsPlaying = false
        stopCounting()
        update { it.copy(playing = false, transportRevision = it.transportRevision + 1) }
        observationNow().copy(playing = false)
    }

    override suspend fun abort(operationId: String): Unit = withContext(dispatcher) {
        preparedTransfer?.takeIf { it.operationId == operationId }?.let(::discardPrepared)
    }

    override suspend fun restoreDisplacedPaused(operationId: String): EngineObservation = withContext(dispatcher) {
        check(activeGroup == null && preparedTransfer == null && preparingTransferOperationId == null && activatingTransferOperationId == null) {
            "Leave Play together or finish connected playback before restoring the previous queue."
        }
        val backup = displacedTransfers[operationId]
            ?: recovery.restore(moveRecoveryKey(operationId))?.let { NativeSlot(null, null, it) }
            ?: error("The displaced player state is no longer available")
        restoreSlotPaused(backup)
        displacedTransfers.remove(operationId)
        recovery.remove(moveRecoveryKey(operationId))
        update { state -> state.copy(displacedMoveOperationId = recovery.latestMoveOperationId()) }
        observationNow()
    }

    suspend fun restoreLatestDisplacedPaused(): EngineObservation = withContext(dispatcher) {
        check(activeGroup == null && preparedTransfer == null && preparingTransferOperationId == null && activatingTransferOperationId == null) {
            "Leave Play together or finish connected playback before restoring the previous queue."
        }
        val operationId = recovery.latestMoveOperationId()
            ?: error("There is no saved destination queue to restore")
        restoreDisplacedPaused(operationId)
    }

    override suspend fun leaveGroupPaused(groupId: String, epoch: Long): EngineObservation = withContext(dispatcher) {
        val ref = GroupRef(groupId, epoch)
        val active = activeGroup
        if (active != null) check(active == ref) { "This player is no longer in that Play together session" }
        val stage = preparedTransfer
        if (stage != null) check(stage.group == ref) { "This player has a different playback stage" }
        check(preparingTransferOperationId == null && activatingTransferOperationId == null) {
            "Playback preparation is still in progress; retry leaving after it finishes"
        }
        if (active == ref) {
            stage?.let(::discardPrepared)
            val backup = groupBackups.remove(groupId)
                ?: recovery.restore(groupRecoveryKey(ref))
                    ?.let { NativeSlot(null, null, it) }
                ?: error("The independent player state is no longer available")
            return@withContext restoreSlotPaused(backup).also { recovery.remove(groupRecoveryKey(ref)) }
        }
        if (stage != null) {
            discardPrepared(stage)
            val current = player
            current?.controls()?.setPause(true)
            current?.let { awaitNativePaused(it) }
            wantsPlaying = false
            stopCounting()
            update { it.copy(playing = false, transportRevision = it.transportRevision + 1) }
            return@withContext observationNow().copy(playing = false)
        }
        // No active group or matching stage means this is an idempotent retry. Do not
        // consume a group-id-only backup here: it may belong to a newer epoch.
        observationNow()
    }

    private suspend fun restoreSlotPaused(backup: NativeSlot): EngineObservation {
        val currentPlayer = player
        val currentSource = source
        val currentLease = cachedLease
        if (currentPlayer !== backup.player) currentPlayer?.release()
        if (currentSource !== backup.source) currentSource?.close()
        if (currentLease !== backup.cachedLease) currentLease?.close()
        load?.cancel()
        generation++
        videoPlaybackFailedGeneration = null
        val nextQueueRevision = state.value.queueRevision + 1
        val nextTransportRevision = state.value.transportRevision + 1
        player = backup.player
        source = backup.source
        cachedLease = backup.cachedLease
        currentAudioUri = backup.audioUri
        activeGroup = null
        activeTransferQueue = null
        wantsPlaying = false
        if (backup.player == null) {
            if (backup.state.current == null) update { backup.state.copy(playing = false, loading = false,
                occurrence = generation, queueRevision = nextQueueRevision, transportRevision = nextTransportRevision) }
            else {
                val restored = backup.state
                update { restored.copy(playing = false, loading = true, occurrence = generation,
                    queueRevision = nextQueueRevision, transportRevision = nextTransportRevision) }
                start(restored.queue, restored.index, requestedPositionMs = restored.positionMs, playOnStart = false)
                load?.join()
                player?.let { awaitNativePaused(it) }
                check(state.value.error == null && state.value.current == restored.current && !state.value.playing && observationNow().ready) {
                    state.value.error ?: "The independent queue could not be restored paused"
                }
            }
        } else {
            attachPlaybackEvents(backup.player, generation)
            backup.player.controls().setPause(true)
            backup.player.controls().setTime(backup.state.positionMs)
            update { backup.state.copy(playing = false, loading = false, ended = false, occurrence = generation,
                queueRevision = nextQueueRevision, transportRevision = nextTransportRevision) }
        }
        return observationNow().copy(playing = false)
    }

    private fun moveRecoveryKey(operationId: String) = "move:$operationId"
    private fun groupRecoveryKey(ref: GroupRef) = "group:${ref.groupId}"

    private suspend fun awaitNativePaused(native: MediaPlayer) {
        withTimeout(5_000) {
            while (native.status().isPlaying || native.status().state().name !in setOf("PAUSED", "NOTHING_SPECIAL", "STOPPED")) delay(25)
        }
    }

    // VLC's default demux can resume a video-only stream at an earlier fragment after seeking.
    private fun videoMediaOptions(audioSlaveUri: String?): List<String> =
        if (audioSlaveUri == null) emptyList() else listOf(":input-slave=$audioSlaveUri", ":demux=avformat")

    private fun mediaMrlMatches(actual: String?, requested: String): Boolean =
        actual == requested || runCatching { File(requested).toURI().toString() == actual }.getOrDefault(false) ||
            runCatching {
                actual?.let { java.net.URI(it).scheme == "file" && File(java.net.URI(it)).canonicalFile == File(requested).canonicalFile } == true
            }.getOrDefault(false)

    private suspend fun playNativePaused(
        native: MediaPlayer,
        uri: String,
        options: List<String> = emptyList(),
        requireAudioTrack: Boolean = false,
        startPositionMs: Long = 0,
        isCurrent: () -> Boolean = { true },
    ) {
        val ready = CompletableDeferred<Boolean>()
        val listener = object : MediaPlayerEventAdapter() {
            override fun paused(mediaPlayer: MediaPlayer) {
                if (runCatching { mediaMrlMatches(mediaPlayer.media().info().mrl(), uri) }.getOrDefault(false)) ready.complete(true)
            }
            override fun error(mediaPlayer: MediaPlayer) {
                if (runCatching { mediaMrlMatches(mediaPlayer.media().info().mrl(), uri) }.getOrDefault(false)) ready.complete(false)
            }
        }
        native.events().addMediaPlayerEventListener(listener)
        try {
            val startOptions = if (startPositionMs > 0) options + ":start-time=${startPositionMs / 1_000.0}" else options
            check(native.media().play(uri, *(startOptions + ":start-paused").toTypedArray())) { "VLC could not open this media stream" }
            val opened = try { withTimeout(20_000) { ready.await() } }
            catch (timeout: TimeoutCancellationException) { throw IllegalStateException("VLC timed out opening this media stream", timeout) }
            check(opened) { "VLC could not open this media stream" }
            if (requireAudioTrack) try {
                withTimeout(4_000) {
                    while (true) {
                        check(isCurrent()) { "Playback changed before VLC selected an audio track" }
                        if (desktopHasSelectedAudioTrack(native.audio().track(), native.audio().trackDescriptions())) break
                        delay(50)
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                throw IllegalStateException("VLC did not select an audio track", timeout)
            }
        } finally { native.events().removeMediaPlayerEventListener(listener) }
    }

    private fun discardPrepared(stage: PreparedTransfer) {
        if (preparedTransfer === stage) preparedTransfer = null
        stage.player.release()
        stage.source.close()
    }

    private fun discardBackups() {
        (displacedTransfers.values + groupBackups.values).distinctBy { it.player }.forEach { slot ->
            if (slot.player != null && slot.player !== player) runCatching { slot.player.release() }
            if (slot.source != null && slot.source !== source) runCatching { slot.source.close() }
            if (slot.cachedLease != null && slot.cachedLease !== cachedLease) runCatching { slot.cachedLease.close() }
        }
        displacedTransfers.clear()
        groupBackups.clear()
    }

    init { offline?.let { repository -> scope.launch {
        var previousOfflineOnly: Boolean? = null
        repository.state.collect { settings ->
            if (previousOfflineOnly != settings.offlineOnly) {
                previousOfflineOnly = settings.offlineOnly
                stagePreparationVersion.value++
                if (settings.offlineOnly) state.value.current?.id?.let { id ->
                    stageMediaCache?.cancelStreamCapture(id)
                    stageVideoCache?.cancelStreamCapture(id)
                }
            }
            if (stopDesktopPlaybackForOffline(settings.offlineOnly, remoteActive, cachedLease != null)) {
                ++generation; load?.cancel(); wantsPlaying = false; record("INTERRUPTED")
                videoPlaybackFailedGeneration = null
                source?.close(); source = null; cachedLease?.close(); cachedLease = null; currentAudioUri = null
                videoSource?.close(); videoSource = null; cachedVideoLease?.close(); cachedVideoLease = null
                videoLocator = null; videoRequiresAudioSlave = false; remoteActive = false
                player?.controls()?.stop()
                videoSurface = null
                boundVideoCanvas = null
                update { it.copy(playing = false, loading = false, videoMode = false, videoLoading = false, videoError = null,
                    error = "Offline mode is enabled. Choose downloaded music or a local file.") }
            }
        }
    } } }

    private fun update(transform: (DesktopPlaybackState) -> DesktopPlaybackState) {
        mutableState.value = transform(mutableState.value)
        refreshStageTransferRate()
    }

    private fun refreshStageTransferRate() {
        stageMediaCache?.setFastTransfersAllowed(stageCanPrepareFast(state.value, wantsPlaying,
            localAudio = currentAudioUri != null && (source == null || source?.isFullyLocal == true),
            localVideo = cachedVideoLease != null && videoSource == null || videoSource?.isFullyLocal == true,
            videoNeedsAudio = videoRequiresAudioSlave))
    }

    private fun relayedSource(initial: PlayableSource, capture: DesktopStageStreamCapture? = null,
        onLocalPlaybackChanged: () -> Unit = {}, refresh: suspend () -> PlayableSource?): DesktopAudioSource {
        val relayKey = Any()
        return DesktopAudioSource(initial, stageCapture = capture,
            onUpstreamActivityChanged = { active -> stageMediaCache?.setUpstreamRelayActive(relayKey, active) },
            onLocalPlaybackChanged = onLocalPlaybackChanged, refresh = refresh)
    }

    private fun createVideoRelay(track: Track, ticket: Long, occurrence: Long, initial: PlayableSource,
        requiresAudioSlave: Boolean, candidateIsCurrent: (DesktopAudioSource) -> Boolean,
        refresh: suspend () -> PlayableSource?): DesktopAudioSource {
        val capture = if (stagePreparationEnabled && stageVideoCachingEnabled && activeGroup == null) runCatching {
            stageVideoCache?.beginStreamCapture(track, initial, requiresAudioSlave)
        }.getOrNull() else null
        lateinit var relay: DesktopAudioSource
        relay = relayedSource(initial, capture = capture,
            onLocalPlaybackChanged = {
                scope.launch {
                    if (!closed && generation == ticket && (videoSource === relay || videoRelayCandidate === relay || candidateIsCurrent(relay))) {
                        refreshStageTransferRate()
                        stagePreparationVersion.value++
                    }
                }
            }, refresh = refresh)
        videoRelayCandidate = relay
        if (capture != null) scope.launch {
            try {
                val completed = capture.awaitComplete()
                if (!closed && stagePreparationEnabled && stageVideoCachingEnabled && activeGroup == null && generation == ticket && state.value.occurrence == occurrence &&
                    (videoSource === relay || videoRelayCandidate === relay || candidateIsCurrent(relay))) {
                    val pin = stageVideoCache?.pinStreamCapture(track.id, capture, completed)
                    if (pin != null) {
                        if (!stagePreparationEnabled || activeGroup != null) {
                            pin.close()
                            stageVideoCache?.discardStreamCapture(track.id, capture, completed)
                        } else if (!relay.attachCapturedFile(capture, completed, pin)) {
                            stageVideoCache?.discardStreamCapture(track.id, capture, completed)
                        }
                    }
                    refreshStageTransferRate()
                    stagePreparationVersion.value++
                } else stageVideoCache?.discardStreamCapture(track.id, capture, completed)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Incomplete or unverified capture leaves the live relay active. */ }
            finally {
                capture.awaitSettled()
                stageVideoCache?.clearDiscardedStreamCapture(track.id)
                stagePreparationVersion.value++
            }
        }
        return relay
    }

    private fun playbackFactory(): MediaPlayerFactory = factory ?: (
        if (stageVideoEnabled) MediaPlayerFactory("--quiet", "--no-media-library", "--no-metadata-network-access", "--no-osd", "--no-snapshot-preview")
        else MediaPlayerFactory("--no-video", "--quiet", "--no-media-library", "--no-metadata-network-access")
    ).also { factory = it }

    private fun nativePlayer(ticket: Long): MediaPlayer {
        player?.let { return it }
        val players = playbackFactory().mediaPlayers()
        return (if (stageVideoEnabled) players.newEmbeddedMediaPlayer() else players.newMediaPlayer()).also { created ->
            player = created
            attachPlaybackEvents(created, ticket)
        }
    }

    private fun attachPlaybackEvents(created: MediaPlayer, ticket: Long) {
        fun event(mediaPlayer: MediaPlayer, action: () -> Unit) {
            if (switchingVideoGeneration == ticket) return
            scope.launch { if (generation == ticket && player === mediaPlayer && !closed) action() }
        }
        created.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) { event(mediaPlayer) {
                    if (switchingVideoGeneration == ticket) return@event
                    stageMediaCache?.setForegroundBuffering(false)
                    if (!wantsPlaying) player?.controls()?.setPause(true)
                    else if (!state.value.loading && state.value.current != null) {
                        retryJob?.cancel(); retryJob = null
                        if (playingSince == null) playingSince = TimeSource.Monotonic.markNow()
                        recordStart()
                        update { it.copy(playing = true, error = null) }
                    }
                } }
                override fun paused(mediaPlayer: MediaPlayer) { event(mediaPlayer) {
                    if (switchingVideoGeneration == ticket) return@event
                    if (!wantsPlaying) stageMediaCache?.setForegroundBuffering(false)
                    stopCounting(); update { it.copy(playing = false) }
                } }
                override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { event(mediaPlayer) {
                    if (switchingVideoGeneration == ticket) return@event
                    stageMediaCache?.setForegroundBuffering(stageShouldYieldToPlayback(newCache < 100f, wantsPlaying,
                        state.value.videoMode, source != null, videoSource != null, videoRequiresAudioSlave))
                    if (newCache < 100f) { stopCounting(); update { it.copy(playing = false) } }
                    else if (wantsPlaying && !state.value.loading && player?.status()?.isPlaying == true) {
                        if (playingSince == null) playingSince = TimeSource.Monotonic.markNow()
                        recordStart()
                        update { it.copy(playing = true) }
                    }
                } }
                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { event(mediaPlayer) {
                    if (switchingVideoGeneration == ticket) return@event
                    if ((playingSince?.elapsedNow()?.inWholeSeconds ?: 0) >= 30) { retryAttempt = 0 }
                    update { it.copy(positionMs = newTime.coerceAtLeast(0)) }
                } }
                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { event(mediaPlayer) {
                    if (switchingVideoGeneration != ticket) update { it.copy(durationMs = newLength.coerceAtLeast(0)) }
                } }
                override fun finished(mediaPlayer: MediaPlayer) { event(mediaPlayer) {
                    stageMediaCache?.setForegroundBuffering(false)
                    if (!state.value.loading && switchingVideoGeneration != ticket) {
                        record("COMPLETED")
                        val next = when {
                            activeGroup != null -> -1
                            state.value.repeatMode == DesktopRepeatMode.ONE -> state.value.index
                            state.value.repeatMode == DesktopRepeatMode.ALL -> (state.value.index + 1) % state.value.queue.size
                            else -> state.value.index + 1
                        }
                        if (next in state.value.queue.indices) start(state.value.queue, next, preserveBridgeQueue = true)
                        else update { it.copy(playing = false, ended = true, positionMs = it.durationMs) }
                    }
                } }
                override fun error(mediaPlayer: MediaPlayer) { event(mediaPlayer) {
                    stageMediaCache?.setForegroundBuffering(false)
                    if (!state.value.loading) {
                        if (switchingVideoGeneration == ticket) return@event
                        if (state.value.videoMode) {
                            videoPlaybackFailedGeneration = ticket
                            update { it.copy(videoError = "Video could not play. Showing artwork.") }
                            if (!state.value.videoLoading) setVideoMode(false)
                            return@event
                        }
                        val message = "Audio could not play. Try another recording or check your connection."
                        if (retryJob != null) return@event
                        update { it.copy(playing = false, loading = false, error = message) }
                        if (schedulePlaybackRetry(ticket, source?.terminalResponseCode)) stopCounting()
                        else record("ERROR")
                    }
                } }
            })
    }

    fun play(tracks: List<Track>, index: Int = 0) {
        if (closed || index !in tracks.indices || tracks.size > 10_000) return
        val snapshot = tracks.toList()
        scope.launch { if (activeGroup == null) { record("SKIPPED"); start(snapshot, index) } }
    }

    fun setVideoMode(enabled: Boolean) {
        if (!closed) scope.launch { setVideoModeOnPlayer(enabled) }
    }

    internal suspend fun enableFocusedVideo(expectedOccurrence: Long): Boolean = withContext(dispatcher) {
        if (closed || !state.value.canEnableFocusedVideo(expectedOccurrence)) return@withContext false
        setVideoModeOnPlayer(true)
    }

    private suspend fun setVideoModeOnPlayer(enabled: Boolean, target: Canvas = videoCanvas): Boolean =
        coroutineScope {
            var fallbackFailed = false
            val snapshot = state.value
            val track = snapshot.current
            if (!stageVideoEnabled || snapshot.videoMode == enabled || snapshot.loading || snapshot.videoLoading || track?.provider != "youtube" ||
                activeGroup != null || preparedTransfer != null || preparingTransferOperationId != null ||
                activatingTransferOperationId != null || snapshot.ended || offline?.state?.value?.offlineOnly == true) return@coroutineScope false
            val ticket = generation
            val occurrence = snapshot.occurrence
            val recoveringVideoFailure = !enabled && videoPlaybackFailedGeneration == ticket
            update { it.copy(videoLoading = true, videoError = desktopVideoSwitchError(it.videoError, recoveringVideoFailure)) }
            var replacementVideo: DesktopAudioSource? = null
            var replacementVideoLease: CachedStageVideo? = null
            try {
                replacementVideoLease = if (enabled && stagePreparationEnabled) runCatching {
                    stageVideoCache?.pin(track.id)
                }.getOrNull() else null
                val selection = if (enabled && replacementVideoLease == null) provider.resolveVideoSelection(track)
                    ?: error("No compatible video is available") else null
                selection?.let { check(validDesktopYouTubeSource(it.source)) { "The recording returned an unsupported media source" } }
                if (offline?.state?.value?.offlineOnly == true || generation != ticket ||
                    !desktopVideoSwitchSnapshotMatches(state.value, track.id, occurrence) || state.value.error != null) {
                    if (generation == ticket && state.value.occurrence == occurrence) update { it.copy(videoLoading = false) }
                    return@coroutineScope false
                }
                val native = player ?: error("Playback is not ready")
                val previousSource = source
                val previousCachedLease = cachedLease
                val previousAudioLocator = previousCachedLease?.file?.absolutePath ?: previousSource?.uri ?: currentAudioUri
                    ?: error("Playback source is not ready")
                fun isCurrent() = generation == ticket && state.value.occurrence == occurrence &&
                    player === native && source === previousSource && cachedLease === previousCachedLease
                if (enabled) {
                    val canvasReady = withTimeoutOrNull(5_000) {
                        while (!target.isDisplayable) delay(20)
                        true
                    }
                    check(canvasReady == true) { "The video surface is unavailable" }
                    if (!isCurrent()) return@coroutineScope false
                }
                val embedded = native as uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
                val nextVideoSurface = if (enabled) factory!!.videoSurfaces().newVideoSurface(target) else null
                val transportRevision = state.value.transportRevision
                val position = state.value.positionMs.coerceAtLeast(0)
                val oldVideoSource = videoSource
                val oldVideoLease = cachedVideoLease
                val oldMediaSource = if (snapshot.videoMode) videoLocator ?: error("Video source is not ready") else previousAudioLocator
                val oldAudioSlave = if (snapshot.videoMode && videoRequiresAudioSlave) previousAudioLocator else null
                var activeVideoRequiresAudioSlave = false
                try {
                    switchingVideoGeneration = ticket
                    if (wantsPlaying) stageMediaCache?.setForegroundBuffering(true)
                    embedded.videoSurface().set(nextVideoSurface)
                    native.controls().setPause(true)
                    if (enabled) {
                        val videoSelection = selection
                        if (replacementVideoLease == null) {
                            val resolvedSelection = videoSelection ?: error("Video selection is missing")
                            replacementVideo = createVideoRelay(track, ticket, occurrence, resolvedSelection.source,
                                resolvedSelection.requiresAudioSlave, candidateIsCurrent = { replacementVideo === it }) {
                                provider.resolveVideoSelection(track)?.takeIf { refreshed ->
                                    refreshed.requiresAudioSlave == resolvedSelection.requiresAudioSlave &&
                                        sameDesktopYouTubeRepresentation(resolvedSelection.source, refreshed.source)
                                }?.source
                            }
                            activeVideoRequiresAudioSlave = resolvedSelection.requiresAudioSlave
                        } else activeVideoRequiresAudioSlave = replacementVideoLease!!.requiresAudioSlave
                        val videoLocator = replacementVideoLease?.file?.absolutePath ?: replacementVideo?.uri
                            ?: error("Video source is not ready")
                        try {
                            val options = videoMediaOptions(previousAudioLocator.takeIf { activeVideoRequiresAudioSlave })
                            playNativePaused(native, videoLocator, options, requireAudioTrack = true,
                                startPositionMs = position, isCurrent = ::isCurrent)
                        } catch (videoError: Throwable) {
                            if (videoError is CancellationException || !isCurrent()) throw videoError
                            val fallback = videoSelection?.muxedFallback
                            if (replacementVideoLease != null || videoSelection?.requiresAudioSlave != true || fallback == null) throw videoError
                            replacementVideo?.close()
                            replacementVideo = createVideoRelay(track, ticket, occurrence, fallback, false,
                                candidateIsCurrent = { replacementVideo === it }) {
                                provider.resolveVideoSelection(track)?.muxedFallback?.takeIf {
                                    sameDesktopYouTubeRepresentation(fallback, it)
                                }
                            }
                            activeVideoRequiresAudioSlave = false
                            playNativePaused(native, replacementVideo!!.uri, requireAudioTrack = true,
                                startPositionMs = position, isCurrent = ::isCurrent)
                        }
                    } else playNativePaused(native, previousAudioLocator, startPositionMs = position)
                } catch (error: Throwable) {
                    replacementVideo?.close(); replacementVideo = null
                    replacementVideoLease?.close(); replacementVideoLease = null
                    if (!isCurrent()) return@coroutineScope false
                    embedded.videoSurface().set(videoSurface)
                    try {
                        val oldOptions = videoMediaOptions(oldAudioSlave)
                        withContext(NonCancellable) { playNativePaused(native, oldMediaSource, oldOptions, startPositionMs = position) }
                    }
                    catch (restoreError: Throwable) {
                        fallbackFailed = true
                        error.addSuppressed(restoreError)
                        throw error
                    }
                    if (!isCurrent()) return@coroutineScope false
                    val restorePosition = if (state.value.transportRevision == transportRevision) position
                        else state.value.positionMs.coerceAtLeast(0)
                    val resumeAfterFailure = wantsPlaying
                    if (restorePosition != position) native.controls().setTime(restorePosition)
                    native.controls().setPause(!resumeAfterFailure)
                    throw error
                } finally {
                    if (switchingVideoGeneration == ticket) {
                        switchingVideoGeneration = null
                        stageMediaCache?.setForegroundBuffering(false)
                    }
                }
                if (generation != ticket || !desktopVideoSwitchSnapshotMatches(state.value, track.id, occurrence) ||
                    player !== native || source !== previousSource || cachedLease !== previousCachedLease || activeGroup != null || offline?.state?.value?.offlineOnly == true) {
                    replacementVideo?.close(); replacementVideo = null
                    if (player === native) (native as uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer)
                        .videoSurface().set(videoSurface)
                    if (generation == ticket && state.value.occurrence == occurrence) update { it.copy(videoLoading = false) }
                    return@coroutineScope false
                }
                val targetPosition = if (state.value.transportRevision == transportRevision) position
                    else state.value.positionMs.coerceAtLeast(0)
                if (targetPosition != position) native.controls().setTime(targetPosition)
                native.controls().setPause(!wantsPlaying)
                if (enabled) {
                    check(replacementVideo != null || replacementVideoLease != null) { "The video source was not created" }
                    videoSource = replacementVideo
                    replacementVideo = null
                    cachedVideoLease = replacementVideoLease
                    replacementVideoLease = null
                    videoLocator = cachedVideoLease?.file?.absolutePath ?: videoSource?.uri
                        ?: error("The video source locator is missing")
                    videoRequiresAudioSlave = activeVideoRequiresAudioSlave
                } else {
                    videoSource?.close()
                    videoSource = null
                    cachedVideoLease?.close()
                    cachedVideoLease = null
                    videoLocator = null
                    videoRequiresAudioSlave = false
                }
                videoSurface = nextVideoSurface
                boundVideoCanvas = target.takeIf { enabled }
                update { it.copy(videoMode = enabled, videoLoading = false,
                    videoError = desktopVideoSwitchError(it.videoError, recoveringVideoFailure), positionMs = targetPosition) }
                videoPlaybackFailedGeneration = null
                true
            } catch (cancelled: CancellationException) {
                replacementVideo?.close()
                replacementVideoLease?.close()
                if (generation == ticket && state.value.occurrence == occurrence) {
                    update { it.copy(videoLoading = false) }
                    if (fallbackFailed && !schedulePlaybackRetry(ticket, source?.terminalResponseCode)) {
                        record("ERROR")
                    }
                }
                throw cancelled
            }
            catch (error: Throwable) {
                replacementVideo?.close()
                replacementVideoLease?.close()
                if (generation == ticket && state.value.occurrence == occurrence) update {
                    (player as? uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer)?.videoSurface()?.set(videoSurface)
                    it.copy(videoLoading = false, videoMode = snapshot.videoMode,
                        videoError = error.message ?: "Could not switch playback mode")
                }
                if ((fallbackFailed || (!enabled && snapshot.videoMode && videoPlaybackFailedGeneration == ticket)) &&
                    generation == ticket && state.value.occurrence == occurrence &&
                    !schedulePlaybackRetry(ticket, source?.terminalResponseCode)) record("ERROR")
                false
            } finally {
                replacementVideoLease?.close()
            }
        }

    suspend fun bindVideoCanvas(
        target: Canvas,
        enableIfNeeded: Boolean = false,
        expectedOccurrence: Long? = null,
    ): Boolean = withContext(dispatcher) {
        val snapshot = state.value
        if (expectedOccurrence != null && snapshot.occurrence != expectedOccurrence) return@withContext false
        if (!snapshot.videoMode && !enableIfNeeded && !snapshot.videoLoading) return@withContext true
        if (closed || snapshot.videoLoading || snapshot.loading ||
            activeGroup != null || preparedTransfer != null || preparingTransferOperationId != null ||
            activatingTransferOperationId != null) return@withContext false
        if (!snapshot.videoMode) return@withContext setVideoModeOnPlayer(true, target)
        if (boundVideoCanvas === target) return@withContext true
        val native = player as? uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer ?: return@withContext false
        val currentSource = source
        val currentCachedLease = cachedLease
        val currentVideoSource = videoSource
        val currentCachedVideoLease = cachedVideoLease
        if (currentVideoSource == null && currentCachedVideoLease == null) return@withContext false
        val ticket = generation
        val occurrence = snapshot.occurrence
        val oldSurface = videoSurface
        val nextSurface: uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurface
        val handoffId = ++videoCanvasHandoffId
        update { it.copy(videoLoading = true) }
        try {
            withTimeout(5_000) { while (!target.isDisplayable) delay(20) }
            if (generation != ticket || !desktopVideoSwitchSnapshotMatches(state.value, snapshot.current?.id.orEmpty(), occurrence) ||
                player !== native || source !== currentSource || cachedLease !== currentCachedLease ||
                    videoSource !== currentVideoSource || cachedVideoLease !== currentCachedVideoLease || !state.value.videoMode) {
                if (videoCanvasHandoffId == handoffId && generation == ticket) update { it.copy(videoLoading = false) }
                return@withContext false
            }
            nextSurface = factory?.videoSurfaces()?.newVideoSurface(target) ?: error("VLC video surface is unavailable")
            val transportRevision = state.value.transportRevision
            val position = state.value.positionMs.coerceAtLeast(0)
            val mediaUri = videoLocator ?: currentVideoSource?.uri ?: return@withContext false
            val audioUri = currentCachedLease?.file?.absolutePath ?: currentSource?.uri ?: currentAudioUri
            val audioOptions = videoMediaOptions(audioUri.takeIf { videoRequiresAudioSlave })
            switchingVideoGeneration = ticket
            if (wantsPlaying) stageMediaCache?.setForegroundBuffering(true)
            try {
                native.controls().setPause(true)
                native.videoSurface().set(nextSurface)
                playNativePaused(native, mediaUri, audioOptions, requireAudioTrack = true, startPositionMs = position)
                if (generation != ticket || state.value.occurrence != occurrence || player !== native ||
                    source !== currentSource || cachedLease !== currentCachedLease ||
                    videoSource !== currentVideoSource || cachedVideoLease !== currentCachedVideoLease || !state.value.videoMode) {
                    native.videoSurface().set(oldSurface)
                    if (videoCanvasHandoffId == handoffId && generation == ticket) update { it.copy(videoLoading = false) }
                    return@withContext false
                }
                val targetPosition = if (state.value.transportRevision == transportRevision) position
                    else state.value.positionMs.coerceAtLeast(0)
                if (targetPosition != position) native.controls().setTime(targetPosition)
                native.controls().setPause(!wantsPlaying)
                videoSurface = nextSurface
                boundVideoCanvas = target
                update { it.copy(videoLoading = false, positionMs = targetPosition) }
                true
            } catch (error: Throwable) {
                native.videoSurface().set(oldSurface)
                withContext(NonCancellable) {
                    if (generation == ticket && state.value.occurrence == occurrence && player === native &&
                        source === currentSource && cachedLease === currentCachedLease &&
                        videoSource === currentVideoSource && cachedVideoLease === currentCachedVideoLease) {
                        try {
                            playNativePaused(native, mediaUri, audioOptions, requireAudioTrack = true, startPositionMs = position)
                            val restorePosition = if (state.value.transportRevision == transportRevision) position
                                else state.value.positionMs.coerceAtLeast(0)
                            if (restorePosition != position) native.controls().setTime(restorePosition)
                            native.controls().setPause(!wantsPlaying)
                        } catch (restoreError: Throwable) {
                            error.addSuppressed(restoreError)
                            // Keep the same queue and audio source available if the video stream cannot be reopened.
                            try {
                                native.videoSurface().set(null)
                                val restorePosition = state.value.positionMs.coerceAtLeast(0)
                                playNativePaused(native, audioUri ?: error("Audio source is unavailable"), startPositionMs = restorePosition)
                                val latestPosition = state.value.positionMs.coerceAtLeast(0)
                                if (latestPosition != restorePosition) native.controls().setTime(latestPosition)
                                native.controls().setPause(!wantsPlaying)
                                videoSource?.close()
                                videoSource = null
                                cachedVideoLease?.close()
                                cachedVideoLease = null
                                videoLocator = null
                                videoRequiresAudioSlave = false
                                videoSurface = null
                                boundVideoCanvas = null
                                update { it.copy(videoMode = false, videoError = error.message ?: "Could not move the video surface") }
                            } catch (audioError: Throwable) {
                                error.addSuppressed(audioError)
                                videoSource?.close()
                                videoSource = null
                                cachedVideoLease?.close()
                                cachedVideoLease = null
                                videoLocator = null
                                videoRequiresAudioSlave = false
                                videoSurface = null
                                boundVideoCanvas = null
                                update { it.copy(videoMode = false, playing = false, videoError = "Video and audio could not recover; playback recovery started.") }
                                if (!schedulePlaybackRetry(ticket, currentSource?.terminalResponseCode)) record("ERROR")
                            }
                        }
                    }
                }
                if (videoCanvasHandoffId == handoffId && generation == ticket) update { it.copy(videoLoading = false) }
                if (error is CancellationException) throw error
                false
            } finally {
                if (switchingVideoGeneration == ticket) {
                    switchingVideoGeneration = null
                    stageMediaCache?.setForegroundBuffering(false)
                }
            }
        } catch (error: Throwable) {
            if (videoCanvasHandoffId == handoffId && generation == ticket && state.value.occurrence == occurrence) {
                update { it.copy(videoLoading = false,
                    videoError = if (error is CancellationException) it.videoError else error.message ?: "Could not move the video surface") }
            }
            if (error is CancellationException) throw error
            false
        }
    }

    suspend fun hasDecodedVideoFrame(target: Canvas): Boolean = withContext(dispatcher) {
        if (closed || !state.value.videoMode || state.value.videoLoading || boundVideoCanvas !== target) return@withContext false
        player?.media()?.info()?.statistics()?.picturesDisplayed()?.let { it > 0 } == true
    }

    suspend fun returnVideoCanvas(saverCanvas: Canvas, restoreVideo: Boolean): Boolean = withContext(dispatcher) {
        if (state.value.videoLoading) return@withContext false
        if (boundVideoCanvas !== saverCanvas) return@withContext true
        if (restoreVideo) bindVideoCanvas(videoCanvas)
        else setVideoModeOnPlayer(false)
    }

    suspend fun recoverMissingVideoFrame(target: Canvas, occurrence: Long) = withContext(dispatcher) {
        val current = state.value
        if (closed || !current.videoMode || current.videoLoading || current.occurrence != occurrence ||
            boundVideoCanvas !== target) return@withContext
        videoPlaybackFailedGeneration = generation
        update { it.copy(videoError = "Video could not display a frame. Showing artwork.") }
        setVideoModeOnPlayer(false)
        Unit
    }

    suspend fun sampleVideoAccent() = withContext(dispatcher) {
        val native = player?.takeIf { !closed && state.value.videoMode } ?: return@withContext
        val accent = runCatching {
            val image = java.nio.file.Files.createTempFile("echo-video-frame-", ".png")
            try {
                if (!native.snapshots().save(image.toFile(), 64, 36)) null
                else javax.imageio.ImageIO.read(image.toFile())?.let(::desktopVideoFrameAccent)
            } finally { runCatching { java.nio.file.Files.deleteIfExists(image) } }
        }.getOrNull()
        if (player === native && state.value.videoMode) update { it.copy(videoAccentArgb = accent) }
    }

    private fun start(tracks: List<Track>, index: Int, preserveBridgeQueue: Boolean = false,
        requestedPositionMs: Long = 0, playOnStart: Boolean = true, automaticRetry: Boolean = false) {
        videoCanvasHandoffId++
        stageMediaCache?.setForegroundBuffering(false)
        if (!automaticRetry) cancelPlaybackRetry()
        load?.cancel()
        val ticket = ++generation
        videoPlaybackFailedGeneration = null
        if (!preserveBridgeQueue) { activeGroup = null; activeTransferQueue = null; discardBackups() }
        wantsPlaying = playOnStart
        update { it.copy(queue = tracks, index = index, playing = false, retrying = false, loading = true, ended = false,
            videoMode = false, videoLoading = false, videoError = null,
            positionMs = requestedPositionMs.coerceAtLeast(0), durationMs = tracks[index].durationMs,
            error = if (automaticRetry) it.error else null,
            occurrence = if (automaticRetry) it.occurrence else ticket,
            queueRevision = if (automaticRetry) it.queueRevision else it.queueRevision + 1,
            transportRevision = if (automaticRetry) it.transportRevision + 1 else it.transportRevision) }
        player?.controls()?.stop(); player?.release(); player = null
        source?.close(); source = null; cachedLease?.close(); cachedLease = null; currentAudioUri = null
        videoSource?.close(); videoSource = null; cachedVideoLease?.close(); cachedVideoLease = null
        videoLocator = null; videoRequiresAudioSlave = false
        videoSurface = null
        boundVideoCanvas = null
        remoteActive = false
        if (automaticRetry) stopCounting()
        else { listenedMs = 0; playingSince = null; playedRecorded = false; terminalRecorded = false }
        load = scope.launch {
            try {
                val track = tracks[index]
                val saved = offline?.savedFile(track)
                val locator = if (saved != null) saved.absolutePath else when (track.provider) {
                    "local" -> localAudioFile(track).absolutePath
                    "youtube" -> {
                        check(offline?.state?.value?.offlineOnly != true) { "Offline mode is enabled" }
                        val cached = if (stagePreparationEnabled && activeGroup == null) runCatching {
                            stageMediaCache?.pin(track.id)
                        }.getOrNull() else null
                        if (cached != null) {
                            cachedLease = cached
                            currentAudioUri = cached.file.absolutePath
                            cached.file.absolutePath
                        } else {
                            remoteActive = true
                            val resolved = provider.resolve(track) ?: error("Recording has no playable audio")
                            ensureActive()
                            if (generation != ticket) return@launch
                            val capture = if (stagePreparationEnabled && activeGroup == null) runCatching {
                                stageMediaCache?.beginStreamCapture(track, resolved)
                            }.getOrNull() else null
                            try {
                                lateinit var relay: DesktopAudioSource
                                relay = relayedSource(resolved, capture = capture,
                                    onLocalPlaybackChanged = {
                                        scope.launch { if (!closed && generation == ticket && source === relay) {
                                            refreshStageTransferRate()
                                            stagePreparationVersion.value++
                                        } }
                                    }) {
                                    provider.resolve(track)?.takeIf { !stageVideoEnabled || sameDesktopYouTubeRepresentation(resolved, it) }
                                }
                                source = relay
                                currentAudioUri = relay.uri
                                if (capture != null) {
                                    stagePreparationVersion.value++
                                    scope.launch {
                                        try {
                                            val completed = capture.awaitComplete()
                                            if (!closed && stagePreparationEnabled && activeGroup == null && generation == ticket && source === relay) {
                                                val pin = stageMediaCache?.pinStreamCapture(track.id, capture, completed)
                                                if (pin != null) {
                                                    if (!stagePreparationEnabled || activeGroup != null) {
                                                        pin.close()
                                                        stageMediaCache?.discardStreamCapture(track.id, capture, completed)
                                                    } else if (!relay.attachCapturedFile(capture, completed, pin)) {
                                                        stageMediaCache?.discardStreamCapture(track.id, capture, completed)
                                                    }
                                                }
                                                refreshStageTransferRate()
                                            } else stageMediaCache?.discardStreamCapture(track.id, capture, completed)
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { /* Capture failure leaves the live relay untouched. */ }
                                        finally {
                                            capture.awaitSettled()
                                            stageMediaCache?.clearDiscardedStreamCapture(track.id)
                                            stagePreparationVersion.value++
                                        }
                                    }
                                }
                                relay.uri
                            } catch (failure: Throwable) {
                                capture?.close()
                                throw failure
                            }
                        }
                    }
                    else -> error("This provider is not supported on desktop yet")
                }
                ensureActive()
                if (generation != ticket) return@launch
                currentAudioUri = locator
                val native = nativePlayer(ticket)
                native.audio().setVolume((state.value.volume * 100).toInt())
                // Native callbacks are queued onto this dispatcher after the loading flag is cleared.
                check(if (wantsPlaying) native.media().play(locator) else native.media().play(locator, ":start-paused")) { "VLC could not start playback" }
                if (requestedPositionMs > 0) native.controls().setTime(requestedPositionMs)
                update { it.copy(loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                if (generation == ticket) {
                    if (cachedLease != null) {
                        player?.controls()?.stop(); player?.release(); player = null
                        cachedLease?.close(); cachedLease = null; currentAudioUri = null
                    }
                    val message =
                    if (error is UnsatisfiedLinkError || error is NoClassDefFoundError) "Install 64-bit VLC 3, then restart Echo to enable playback."
                    else if (offline?.state?.value?.offlineOnly == true) "Offline mode is enabled. Choose downloaded music or a local file."
                    else if (error is dev.echo.data.provider.YouTubeRequestException) error.message
                    else "Could not start audio. Check that VLC 3 is installed and the recording is available."
                    val retrying = isTransientYouTubeFailure(error) && schedulePlaybackRetry(ticket, source?.terminalResponseCode)
                    if (!retrying) record("ERROR") else stopCounting()
                    update { it.copy(playing = false, loading = false, error = message) }
                }
            }
        }
    }

    private fun isTransientYouTubeFailure(error: Throwable): Boolean {
        if (state.value.current?.provider != "youtube") return false
        val causes = generateSequence(error) { it.cause }
        val providerErrors = causes.filterIsInstance<dev.echo.data.provider.YouTubeRequestException>().toList()
        if (providerErrors.any { !it.retryable }) return false
        return providerErrors.any { it.retryable } || causes.any { it is java.io.IOException }
    }

    private fun schedulePlaybackRetry(ticket: Long, responseCode: Int?): Boolean {
        val current = state.value
        val track = current.current ?: return false
        val failedSource = source
        if (closed || !wantsPlaying || activeGroup != null || !remoteActive || current.current?.provider != "youtube" ||
            responseCode?.let { it in 400..499 } == true) {
            cancelPlaybackRetry()
            return false
        }
        val occurrence = current.occurrence
        val trackId = track.id
        retryAttempt = (retryAttempt + 1).coerceAtMost(100)
        val delayMs = desktopPlaybackRetryDelayMs(retryAttempt)
        val attempt = ++retryToken
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            if (!desktopPlaybackRetryIsCurrent(attempt, retryToken)) return@launch
            val latest = state.value
            if (closed || !wantsPlaying || generation != ticket || activeGroup != null || latest.occurrence != occurrence ||
                latest.current?.id != trackId || offline?.state?.value?.offlineOnly == true ||
                failedSource?.terminalResponseCode?.let { it in 400..499 } == true) {
                if (desktopPlaybackRetryIsCurrent(attempt, retryToken) && generation == ticket) {
                    retryJob = null
                    update { finishDesktopPlaybackRetry(it, trackId, occurrence) }
                }
                return@launch
            }
            if (!desktopPlaybackRetryIsCurrent(attempt, retryToken)) return@launch
            retryJob = null
            start(latest.queue, latest.index, preserveBridgeQueue = true,
                requestedPositionMs = latest.positionMs, automaticRetry = true)
        }
        update { it.copy(retrying = true) }
        return true
    }

    private fun cancelPlaybackRetry() {
        retryToken++
        retryJob?.cancel()
        retryJob = null
        retryAttempt = 0
        if (state.value.retrying) update { it.copy(retrying = false) }
    }

    fun toggle() { if (!closed) scope.launch {
        val active = if (switchingVideoGeneration == generation) wantsPlaying else desktopPlaybackIsActive(state.value)
        if (!state.value.loading) setPlayingNow(!active)
    } }

    fun pause() { if (!closed) scope.launch { setPlayingNow(false) } }

    private fun setPlayingNow(playing: Boolean) {
        if (activeGroup != null) return
        val wasActive = if (switchingVideoGeneration == generation) wantsPlaying else desktopPlaybackIsActive(state.value)
        if (!playing) {
            stageMediaCache?.setForegroundBuffering(false)
            cancelPlaybackRetry()
        }
        if (wasActive != playing) update { it.copy(transportRevision = it.transportRevision + 1) }
        if (state.value.loading || switchingVideoGeneration == generation) {
            wantsPlaying = playing
            if (!playing) { stopCounting(); update { it.copy(playing = false) } }
            return
        }
        if (!playing) { wantsPlaying = false; stopCounting(); player?.controls()?.setPause(true); update { it.copy(playing = false, retrying = false) } }
        else if (state.value.current != null && !state.value.playing) {
            wantsPlaying = true
            if (state.value.error != null || state.value.positionMs >= state.value.durationMs && state.value.durationMs > 0)
                start(state.value.queue, state.value.index, requestedPositionMs = state.value.positionMs)
            else player?.controls()?.play()
        }
    }
    fun next() { if (!closed) scope.launch { nextNow() } }
    private fun nextNow() {
        if (activeGroup != null) return
        val next = if (state.value.index + 1 < state.value.queue.size) state.value.index + 1
            else if (state.value.repeatMode == DesktopRepeatMode.ALL && state.value.queue.isNotEmpty()) 0 else -1
        if (next >= 0) { record("SKIPPED"); start(state.value.queue, next, preserveBridgeQueue = true) }
    }
    fun previous() { if (!closed) scope.launch { previousNow() } }
    private fun previousNow() {
        if (activeGroup != null) return
        if (state.value.positionMs > 3000) seekNow(0)
        else if (state.value.index > 0) { record("SKIPPED"); start(state.value.queue, state.value.index - 1, preserveBridgeQueue = true) }
        else if (state.value.repeatMode == DesktopRepeatMode.ALL && state.value.queue.isNotEmpty()) {
            record("SKIPPED"); start(state.value.queue, state.value.queue.lastIndex, preserveBridgeQueue = true)
        }
    }

    internal suspend fun executeConnectedCommand(command: ConnectedPlaybackCommand) = withContext(dispatcher) {
        check(activeGroup == null) { "This player is following a shared queue; use the group controls" }
        val current = state.value
        check(!closed && current.current != null && current.occurrence.toString() == command.expectedTrackRevision &&
            current.queueRevision.toString() == command.expectedQueueRevision) { "Playback changed before the command arrived" }
        when (command.action) {
            ConnectedPlaybackAction.PLAY -> setPlayingNow(true)
            ConnectedPlaybackAction.PAUSE -> setPlayingNow(false)
            ConnectedPlaybackAction.NEXT -> nextNow()
            ConnectedPlaybackAction.PREVIOUS -> previousNow()
            ConnectedPlaybackAction.SEEK -> {
                require(command.positionMs in 0..current.durationMs) { "Seek position is outside the song" }
                seekNow(command.positionMs)
            }
        }
    }
    fun seek(positionMs: Long, expectedOccurrence: Long? = null) { if (!closed) scope.launch {
        val current = state.value
        if ((expectedOccurrence == null || current.occurrence == expectedOccurrence) &&
            !current.loading && current.durationMs > 0) seekNow(positionMs)
    } }
    private fun seekNow(positionMs: Long) {
        if (activeGroup != null) return
        val native = player ?: return
        val target = positionMs.coerceIn(0, state.value.durationMs.coerceAtLeast(0))
        if (switchingVideoGeneration != generation) native.controls().setTime(target)
        // VLC does not emit timeChanged while paused; resume callbacks reconcile the requested position.
        update { it.copy(positionMs = target, transportRevision = it.transportRevision + 1) }
    }
    fun setVolume(volume: Float) { if (!closed && volume.isFinite()) scope.launch {
        val bounded = volume.coerceIn(0f, 1f)
        player?.audio()?.setVolume((bounded * 100).toInt())
        update { it.copy(volume = bounded) }
    } }
    fun searchSelected(track: Track) { if (!closed && playableDesktopTrack(track)) scope.launch {
        if (activeGroup != null) return@launch
        val upcoming = upcomingDesktopTracks(state.value)
        if (upcoming.size < 10_000) { record("SKIPPED"); start(listOf(track) + upcoming, 0) }
    } }
    fun append(items: List<Track>, next: Boolean = false) {
        if (closed || items.isEmpty() || items.size > 5000 || !items.all(::playableDesktopTrack)) return
        val snapshot = items.toList()
        scope.launch {
            if (activeGroup != null) return@launch
            val current = state.value.current
            if (current == null || state.value.ended) { record("INTERRUPTED"); start(snapshot, 0) }
            else {
                appendedDesktopQueue(state.value, snapshot, next)?.let { activeTransferQueue = null; mutableState.value = it }
            }
        }
    }
    suspend fun applySmartQueue(items: List<Track>, expectedOccurrence: Long, anchorId: String?,
        expectedQueueRevision: Long? = null): Boolean {
        if (closed || activeGroup != null) return false
        val snapshot = items.toList()
        return withContext(dispatcher) {
            val replacement = retainedDesktopQueue(state.value, snapshot, expectedOccurrence, anchorId, expectedQueueRevision) ?: return@withContext false
            if (anchorId != null) { activeTransferQueue = null; mutableState.value = replacement }
            else { record("INTERRUPTED"); start(replacement.queue, 0) }
            true
        }
    }
    fun setRepeatMode(mode: DesktopRepeatMode) { if (!closed) scope.launch { if (activeGroup == null) update { it.copy(repeatMode = mode, queueRevision = it.queueRevision + 1) } } }
    fun setSleepTimer(minutes: Int?) {
        require(minutes == null || minutes in setOf(15, 30, 45, 60)) { "Choose a supported sleep duration" }
        setSleepTimerMillis(minutes?.times(60_000L))
    }
    internal fun setSleepTimerMillis(durationMs: Long?) {
        require(durationMs == null || durationMs in 1L..3_600_000L)
        if (closed) return
        scope.launch {
            sleepJob?.cancel()
            update { it.copy(sleepRemainingMs = durationMs) }
            sleepJob = durationMs?.let { duration -> scope.launch {
                val started = TimeSource.Monotonic.markNow()
                while (isActive) {
                    val remaining = (duration - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(0)
                    if (remaining == 0L) {
                        if (activeGroup != null) { update { it.copy(sleepRemainingMs = null) }; break }
                        wantsPlaying = false
                        stopCounting()
                        player?.controls()?.setPause(true)
                        update { it.copy(playing = false, sleepRemainingMs = null) }
                        break
                    }
                    update { it.copy(sleepRemainingMs = remaining) }
                    delay(minOf(remaining, 1000L))
                }
            } }
        }
    }
    fun shuffleUpcoming() { if (!closed) scope.launch {
        if (activeGroup != null) return@launch
        val current = state.value.current ?: return@launch
        activeTransferQueue = null
        update { it.copy(queue = listOf(current) + upcomingDesktopTracks(it).shuffled(), index = 0, queueRevision = it.queueRevision + 1) }
    } }
    fun clearUpcoming() { if (!closed) scope.launch {
        if (activeGroup != null) return@launch
        val current = state.value.current ?: return@launch
        activeTransferQueue = null
        update { it.copy(queue = listOf(current), index = 0, queueRevision = it.queueRevision + 1) }
    } }
    suspend fun removeUpcoming(index: Int, expectedOccurrence: Long, expectedQueueRevision: Long): Boolean {
        if (closed || activeGroup != null) return false
        return withContext(dispatcher) {
            mutableState.value = editDesktopUpcoming(state.value, index, null, expectedOccurrence, expectedQueueRevision) ?: return@withContext false
            activeTransferQueue = null
            true
        }
    }
    suspend fun moveUpcoming(from: Int, to: Int, expectedOccurrence: Long, expectedQueueRevision: Long): Boolean {
        if (closed || activeGroup != null) return false
        return withContext(dispatcher) {
            mutableState.value = editDesktopUpcoming(state.value, from, to, expectedOccurrence, expectedQueueRevision) ?: return@withContext false
            activeTransferQueue = null
            true
        }
    }
    suspend fun jumpTo(index: Int, expectedQueueRevision: Long): Boolean {
        if (closed || activeGroup != null) return false
        return withContext(dispatcher) {
            if (state.value.queueRevision != expectedQueueRevision || index !in state.value.queue.indices) return@withContext false
            if (index != state.value.index || state.value.ended) { record("SKIPPED"); start(state.value.queue, index) }
            true
        }
    }
    private fun stopCounting() {
        playingSince?.let { listenedMs += it.elapsedNow().inWholeMilliseconds.coerceAtLeast(0) }
        playingSince = null
    }
    private fun record(event: String) {
        stopCounting()
        val track = state.value.current
        if (track != null && !terminalRecorded && (playedRecorded || event == "ERROR")) {
            terminalRecorded = true
            runCatching { onHistory(track, event, listenedMs) }.onFailure {
                update { it.copy(error = "Playback continued, but listening history could not be saved.") }
            }
            listenedMs = 0
        }
    }
    private fun recordStart() {
        if (playedRecorded) return
        playedRecorded = true
        state.value.current?.let { track -> runCatching { onHistory(track, "PLAYED", 0) }.onFailure {
            update { it.copy(error = "Playback continued, but listening history could not be saved.") }
        } }
    }
    override fun close() {
        if (closed) return
        closed = true
        runBlocking { withContext(dispatcher) {
            ++generation; load?.cancel(); cancelPlaybackRetry(); record("INTERRUPTED")
            preparedTransfer?.let(::discardPrepared)
            discardBackups()
            source?.close(); source = null
            cachedLease?.close(); cachedLease = null
            currentAudioUri = null
            videoSource?.close(); videoSource = null
            cachedVideoLease?.close(); cachedVideoLease = null
            videoLocator = null; videoRequiresAudioSlave = false
            player?.controls()?.stop(); player?.release(); player = null
            factory?.release(); factory = null
            stageMediaCache?.close()
            stageVideoCache?.close()
            scope.cancel()
        } }
        dispatcher.close()
    }
}

private fun playableDesktopTrack(track: Track) = track.sourceUri != null && track.provider in setOf("local", "youtube")

internal fun localAudioFile(track: Track): File {
    val uri = java.net.URI(track.sourceUri ?: error("Local audio path is missing"))
    require(uri.scheme == "file" && uri.host.isNullOrEmpty()) { "Choose a local audio file" }
    return File(uri).also { require(it.isFile && it.canRead()) { "Local audio file is missing or unreadable" } }
}
