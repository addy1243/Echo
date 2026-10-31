package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.core.connected.EngineCapture
import dev.echo.core.connected.EngineFence
import dev.echo.core.connected.EngineObservation
import dev.echo.core.connected.GroupRef
import dev.echo.core.connected.CONNECTED_V2_CAPABILITY_GROUP
import dev.echo.core.connected.CONNECTED_V2_CAPABILITY_MOVE
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
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.UUID
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

enum class DesktopRepeatMode { OFF, ALL, ONE }

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
) {
    val current: Track? get() = queue.getOrNull(index)
    val canRetainCurrent: Boolean get() = current != null && !ended && !loading && error == null
}

internal fun upcomingDesktopTracks(state: DesktopPlaybackState): List<Track> =
    if (state.current == null) emptyList() else state.queue.drop(state.index + 1) +
        if (state.repeatMode == DesktopRepeatMode.ALL) state.queue.take(state.index) else emptyList()

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

class DesktopPlayback(
    private val provider: YouTubeProvider = YouTubeProvider(),
    private val offline: DesktopOfflineRepository? = null,
    recoveryFile: File = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "EchoMusic/playback-v2-recovery.json"),
    findLocalTrack: (String) -> Track? = { null },
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
    private data class NativeSlot(val player: MediaPlayer?, val source: DesktopAudioSource?, val state: DesktopPlaybackState)
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
    private var generation = 0L
    private var listenedMs = 0L
    private var playedRecorded = false
    private var terminalRecorded = false
    private var wantsPlaying = false
    private var sleepJob: Job? = null
    private var playingSince: TimeSource.Monotonic.ValueTimeMark? = null
    @Volatile private var closed = false
    private var remoteActive = false

    init {
        mutableState.value = mutableState.value.copy(displacedMoveOperationId =
            runCatching { recovery.latestMoveOperationId() }.getOrNull())
    }

    private fun fenceNow(): EngineFence = EngineFence(bridgeSessionId, state.value.queueRevision,
        state.value.occurrence, state.value.transportRevision)

    private fun checkFence(expected: EngineFence) {
        validateEngineFence(expected)
        check(fenceNow() == expected) { "Playback changed before the operation could be applied" }
    }

    override suspend fun fence(): EngineFence = withContext(dispatcher) {
        check(!closed) { "This player session has closed" }
        fenceNow()
    }

    override suspend fun capture(): EngineCapture = withContext(dispatcher) {
        check(!closed) { "This player session has closed" }
        check(activeGroup == null && preparedTransfer == null && preparingTransferOperationId == null && activatingTransferOperationId == null) {
            "Finish the current connected playback operation before moving this queue."
        }
        val current = state.value
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

    override suspend fun capabilities(): Set<String> = setOf(CONNECTED_V2_CAPABILITY_MOVE, CONNECTED_V2_CAPABILITY_GROUP)

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
            val stagedSource = DesktopAudioSource(resolved) { provider.resolve(track) }
            val createdFactory = factory ?: MediaPlayerFactory("--no-video", "--quiet", "--no-media-library", "--no-metadata-network-access").also { factory = it }
            val stagedPlayer = createdFactory.mediaPlayers().newMediaPlayer()
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

    override suspend fun activate(operationId: String, positionMs: Long, play: Boolean): EngineObservation = withContext(dispatcher) {
        val candidate = preparedTransfer?.takeIf { it.operationId == operationId }
        val stage = candidate ?: error("Playback preparation expired. Try again.")
        if (fenceNow() != stage.expectedTarget) {
            discardPrepared(stage)
            error("The destination changed after preparation. Its current queue was kept.")
        }
        require(positionMs in 0..stage.queue.entries.first().recording.durationMs) { "Position is outside the current song" }
        val prior = player?.let { native ->
            NativeSlot(native, source, state.value.copy(positionMs = native.status().time().coerceAtLeast(0), playing = false))
        } ?: NativeSlot(null, source, state.value.copy(playing = false))
        var newlySavedBackupKey: String? = null
        if (stage.group == null) {
            val key = moveRecoveryKey(operationId)
            recovery.save(key, prior.state)
            newlySavedBackupKey = key
            displacedTransfers[operationId] = prior
        } else if (activeGroup?.groupId != stage.group.groupId && stage.group.groupId !in groupBackups) {
            val key = groupRecoveryKey(stage.group)
            recovery.save(key, prior.state)
            newlySavedBackupKey = key
            groupBackups[stage.group.groupId] = prior
        }

        val oldPlayer = player
        val oldSource = source
        val oldGroup = activeGroup
        val oldTransferQueue = activeTransferQueue
        val oldIntent = state.value.playing
        oldPlayer?.controls()?.setPause(true)
        oldPlayer?.let { awaitNativePaused(it) }
        if (fenceNow() != stage.expectedTarget) {
            if (oldIntent && oldPlayer != null && oldPlayer === player && state.value.occurrence == stage.expectedTarget.occurrenceRevision &&
                state.value.queueRevision == stage.expectedTarget.queueRevision) oldPlayer.controls().play()
            newlySavedBackupKey?.let(recovery::remove)
            displacedTransfers.remove(operationId)
            stage.group?.let { ref -> if (newlySavedBackupKey == groupRecoveryKey(ref)) groupBackups.remove(ref.groupId) }
            update { it.copy(displacedMoveOperationId = recovery.latestMoveOperationId()) }
            error("The destination changed while preparing. Its current playback was kept.")
        }
        if (oldPlayer != null && oldPlayer !== prior.player) oldPlayer.release()
        if (oldSource != null && oldSource !== prior.source && oldSource !== stage.source) oldSource.close()
        load?.cancel()
        val ticket = ++generation
        player = stage.player
        source = stage.source
        attachPlaybackEvents(stage.player, ticket)
        wantsPlaying = play
        activeTransferQueue = stage.queue
        activeGroup = stage.group
        activatingTransferOperationId = operationId
        update { it.copy(queue = stage.tracks, index = 0, playing = false, loading = false,
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
            if (stage.group == null) {
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
        }
        activatingTransferOperationId = null
        if (stage.group == null) update { it.copy(displacedMoveOperationId = recovery.latestMoveOperationId()) }
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
        if (currentPlayer !== backup.player) currentPlayer?.release()
        if (currentSource !== backup.source) currentSource?.close()
        load?.cancel()
        generation++
        val nextQueueRevision = state.value.queueRevision + 1
        val nextTransportRevision = state.value.transportRevision + 1
        player = backup.player
        source = backup.source
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

    private fun discardPrepared(stage: PreparedTransfer) {
        if (preparedTransfer === stage) preparedTransfer = null
        stage.player.release()
        stage.source.close()
    }

    private fun discardBackups() {
        (displacedTransfers.values + groupBackups.values).distinctBy { it.player }.forEach { slot ->
            if (slot.player != null && slot.player !== player) runCatching { slot.player.release() }
            if (slot.source != null && slot.source !== source) runCatching { slot.source.close() }
        }
        displacedTransfers.clear()
        groupBackups.clear()
    }

    init { offline?.let { repository -> scope.launch {
        repository.state.collect { settings ->
            if (settings.offlineOnly && remoteActive) {
                ++generation; load?.cancel(); wantsPlaying = false; record("INTERRUPTED")
                source?.close(); source = null; remoteActive = false
                player?.controls()?.stop()
                update { it.copy(playing = false, loading = false, error = "Offline mode is enabled. Choose downloaded music or a local file.") }
            }
        }
    } } }

    private fun update(transform: (DesktopPlaybackState) -> DesktopPlaybackState) { mutableState.value = transform(mutableState.value) }

    private fun nativePlayer(ticket: Long): MediaPlayer {
        player?.let { return it }
        val createdFactory = factory ?: MediaPlayerFactory("--no-video", "--quiet", "--no-media-library", "--no-metadata-network-access")
        factory = createdFactory
        return createdFactory.mediaPlayers().newMediaPlayer().also { created ->
            player = created
            attachPlaybackEvents(created, ticket)
        }
    }

    private fun attachPlaybackEvents(created: MediaPlayer, ticket: Long) {
        fun event(mediaPlayer: MediaPlayer, action: () -> Unit) {
            scope.launch { if (generation == ticket && player === mediaPlayer && !closed) action() }
        }
        created.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) { event(mediaPlayer) {
                    if (!wantsPlaying) player?.controls()?.setPause(true)
                    else if (!state.value.loading && state.value.current != null) {
                        if (playingSince == null) playingSince = TimeSource.Monotonic.markNow()
                        recordStart()
                        update { it.copy(playing = true) }
                    }
                } }
                override fun paused(mediaPlayer: MediaPlayer) { event(mediaPlayer) { stopCounting(); update { it.copy(playing = false) } } }
                override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { event(mediaPlayer) {
                    if (newCache < 100f) { stopCounting(); update { it.copy(playing = false) } }
                    else if (wantsPlaying && !state.value.loading && player?.status()?.isPlaying == true) {
                        if (playingSince == null) playingSince = TimeSource.Monotonic.markNow()
                        recordStart()
                        update { it.copy(playing = true) }
                    }
                } }
                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { event(mediaPlayer) { update { it.copy(positionMs = newTime.coerceAtLeast(0)) } } }
                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { event(mediaPlayer) { update { it.copy(durationMs = newLength.coerceAtLeast(0)) } } }
                override fun finished(mediaPlayer: MediaPlayer) { event(mediaPlayer) {
                    if (!state.value.loading) {
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
                    if (!state.value.loading) {
                        record("ERROR")
                        update { it.copy(playing = false, loading = false, error = "Audio could not play. Try another recording or check your connection.") }
                    }
                } }
            })
    }

    fun play(tracks: List<Track>, index: Int = 0) {
        if (closed || index !in tracks.indices || tracks.size > 10_000) return
        val snapshot = tracks.toList()
        scope.launch { if (activeGroup == null) { record("SKIPPED"); start(snapshot, index) } }
    }

    private fun start(tracks: List<Track>, index: Int, preserveBridgeQueue: Boolean = false,
        requestedPositionMs: Long = 0, playOnStart: Boolean = true) {
        load?.cancel()
        val ticket = ++generation
        if (!preserveBridgeQueue) { activeGroup = null; activeTransferQueue = null; discardBackups() }
        wantsPlaying = playOnStart
        update { it.copy(queue = tracks, index = index, playing = false, loading = true, ended = false, positionMs = requestedPositionMs.coerceAtLeast(0), durationMs = tracks[index].durationMs, error = null, occurrence = ticket, queueRevision = it.queueRevision + 1) }
        player?.controls()?.stop(); player?.release(); player = null
        source?.close(); source = null
        remoteActive = false
        listenedMs = 0; playingSince = null; playedRecorded = false; terminalRecorded = false
        load = scope.launch {
            try {
                val track = tracks[index]
                val saved = offline?.savedFile(track)
                val locator = if (saved != null) saved.absolutePath else when (track.provider) {
                    "local" -> localAudioFile(track).absolutePath
                    "youtube" -> {
                        check(offline?.state?.value?.offlineOnly != true) { "Offline mode is enabled" }
                        remoteActive = true
                        val resolved = provider.resolve(track) ?: error("Recording has no playable audio")
                        ensureActive()
                        if (generation != ticket) return@launch
                        DesktopAudioSource(resolved) { provider.resolve(track) }.also { source = it }.uri
                    }
                    else -> error("This provider is not supported on desktop yet")
                }
                ensureActive()
                if (generation != ticket) return@launch
                val native = nativePlayer(ticket)
                native.audio().setVolume((state.value.volume * 100).toInt())
                // Native callbacks are queued onto this dispatcher after the loading flag is cleared.
                check(if (wantsPlaying) native.media().play(locator) else native.media().play(locator, ":start-paused")) { "VLC could not start playback" }
                if (requestedPositionMs > 0) native.controls().setTime(requestedPositionMs)
                update { it.copy(loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                if (generation == ticket) {
                    record("ERROR")
                    update { it.copy(playing = false, loading = false, error =
                    if (error is UnsatisfiedLinkError || error is NoClassDefFoundError) "Install 64-bit VLC 3, then restart Echo to enable playback."
                    else if (offline?.state?.value?.offlineOnly == true) "Offline mode is enabled. Choose downloaded music or a local file."
                    else if (error is dev.echo.data.provider.YouTubeRequestException) error.message
                    else "Could not start audio. Check that VLC 3 is installed and the recording is available.") }
                }
            }
        }
    }

    fun toggle() { if (!closed) scope.launch { if (!state.value.loading) setPlayingNow(!state.value.playing) } }

    private fun setPlayingNow(playing: Boolean) {
        if (activeGroup != null) return
        if (state.value.playing != playing) update { it.copy(transportRevision = it.transportRevision + 1) }
        if (state.value.loading) { wantsPlaying = playing; return }
        if (!playing) { wantsPlaying = false; stopCounting(); player?.controls()?.setPause(true); update { it.copy(playing = false) } }
        else if (state.value.current != null && !state.value.playing) {
            wantsPlaying = true
            if (state.value.error != null || state.value.positionMs >= state.value.durationMs && state.value.durationMs > 0) start(state.value.queue, state.value.index)
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
    fun seek(positionMs: Long) { if (!closed) scope.launch {
        if (!state.value.loading && state.value.durationMs > 0) seekNow(positionMs)
    } }
    private fun seekNow(positionMs: Long) {
        if (activeGroup != null) return
        val native = player ?: return
        val target = positionMs.coerceIn(0, state.value.durationMs.coerceAtLeast(0))
        native.controls().setTime(target)
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
            ++generation; load?.cancel(); record("INTERRUPTED")
            preparedTransfer?.let(::discardPrepared)
            discardBackups()
            source?.close(); source = null
            player?.controls()?.stop(); player?.release(); player = null
            factory?.release(); factory = null
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
