package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.core.connected.EngineCapture
import dev.echo.core.connected.EngineFence
import dev.echo.core.connected.EngineObservation
import dev.echo.core.connected.GroupRef
import dev.echo.core.connected.GroupTimeline
import dev.echo.core.connected.GroupTimelineAction
import dev.echo.core.connected.PortableRecording
import dev.echo.core.connected.QueueOccurrence
import dev.echo.core.connected.RepeatModeV2
import dev.echo.core.connected.TransferQueue
import dev.echo.core.connected.advanceGroupTimeline
import dev.echo.core.connected.groupSyncDeadline
import dev.echo.core.connected.groupSyncPositionAt
import dev.echo.core.connected.validateGroupTimeline
import dev.echo.core.connected.validateTransferQueue
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedPlaybackAction
import dev.echo.data.firebase.ConnectedPlaybackState
import dev.echo.data.firebase.ConnectedPlaybackV2State
import dev.echo.data.firebase.ConnectedPlayerSnapshot
import dev.echo.data.firebase.ConnectedV2Device
import dev.echo.data.firebase.GroupActionV2
import dev.echo.data.firebase.GroupMemberV2
import dev.echo.data.firebase.GroupMemberV2Status
import dev.echo.data.firebase.ConnectedGroupV2
import dev.echo.data.firebase.GroupV2Status
import dev.echo.data.firebase.GroupQueueOperationV2
import dev.echo.data.firebase.GroupQueueOperationV2Status
import dev.echo.data.firebase.GroupSyncTimingV1
import dev.echo.data.firebase.MoveOperationV2
import dev.echo.data.firebase.MoveV2Status
import dev.echo.data.firebase.QueueOperationV2
import dev.echo.data.firebase.QueueOperationV2Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

internal enum class DemoLatencyPreset(val label: String, val rttMs: Long, val jitterMs: Long) {
    FAST_20("20 ms", 20L, 5L), SLOW_500("500 ms", 500L, 80L), VARIABLE("Variable", 180L, 60L),
}

/** In-memory connected-player backend for the Windows stage app. It never signs in or writes Firebase. */
internal class StageConnectedPlayback(
    private val clockMs: () -> Long = System::currentTimeMillis,
    startTicker: Boolean = true,
) : DesktopConnectedPlaybackBackend {
    override val deviceId: String = "stage-host"
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val catalog = mutableMapOf<String, Track>()
    private var deviceNumber = 0
    private var hostQueue: DemoQueue
    private var moveHostBackup: DemoQueue? = null
    private var displacedOperation: String? = null
    private var group: DemoGroup? = null
    private var lastTickMs = clockMs()
    private var volume = .7f
    private var sleepRemainingMs: Long? = null
    private var message: String? = "Stage simulation only · no Google account or Firebase connection"
    private val peers = linkedMapOf<String, DemoPeer>()
    private val mutableLocalPlayback: MutableStateFlow<DesktopPlaybackState>
    val localPlayback: StateFlow<DesktopPlaybackState> get() = mutableLocalPlayback.asStateFlow()
    private val mutableState = MutableStateFlow(ConnectedPlaybackState())
    override val state: StateFlow<ConnectedPlaybackState> = mutableState.asStateFlow()
    private val mutableV2State = MutableStateFlow(ConnectedPlaybackV2State())
    override val v2State: StateFlow<ConnectedPlaybackV2State> = mutableV2State.asStateFlow()

    init {
        hostQueue = seededQueue()
        mutableLocalPlayback = MutableStateFlow(localSnapshot())
        publish()
        if (startTicker) scope.launch {
            while (isActive) {
                delay(250)
                tick()
            }
        }
    }

    override fun enabled() = true
    override suspend fun run() = Unit

    fun addDemoDevice(platform: String): String = synchronized(lock) {
        require(platform in setOf("Android phone", "Windows laptop"))
        deviceNumber += 1
        val id = "stage-peer-$deviceNumber"
        peers[id] = DemoPeer(id, "Echo · $platform · Demo $deviceNumber", UUID.randomUUID().toString(),
            online = true, queue = seededQueue(deviceNumber))
        message = "Added a local demo device. No account or network connection was used."
        publish()
        id
    }

    fun setDemoDeviceOnline(id: String, online: Boolean) = synchronized(lock) {
        val peer = peers[id] ?: return@synchronized
        if (peer.online != online) {
            peer.online = online
            if (online) peer.sessionId = UUID.randomUUID().toString()
            message = if (online) "${peer.label} reconnected with a new demo session." else "${peer.label} is offline in this preview."
            publish()
        }
    }

    fun setDemoDeviceLatency(id: String, preset: DemoLatencyPreset) = synchronized(lock) {
        val peer = peers[id] ?: return@synchronized
        peer.latency = preset
        publish()
    }

    fun demoDeviceLatency(id: String): DemoLatencyPreset? = synchronized(lock) { peers[id]?.latency }

    internal fun tick(nowMs: Long = clockMs()) = synchronized(lock) { tickLocked(nowMs) }

    fun removeDemoDevice(id: String) = synchronized(lock) {
        require(group?.memberIds?.contains(id) != true) { "Leave Play together before removing a group member." }
        peers.remove(id)
        publish()
    }

    fun tracksForPresentation(): List<Track> = synchronized(lock) { catalog.values.toList() }

    fun playLocal(tracks: List<Track>, index: Int = 0) = synchronized(lock) {
        require(tracks.isNotEmpty() && index in tracks.indices)
        hostQueue = queueFromTracks(tracks, index, playing = true, base = hostQueue)
        sleepRemainingMs = null
        publish()
    }

    fun localCommand(action: ConnectedPlaybackAction, positionMs: Long = 0L) = synchronized(lock) {
        hostQueue = applyCommand(hostQueue, action, positionMs)
        publish()
    }

    fun appendLocal(tracks: List<Track>, next: Boolean) = synchronized(lock) {
        if (tracks.isEmpty()) return@synchronized
        val upcoming = hostQueue.entries.drop(hostQueue.cursor + 1)
        val additions = tracks.map(::occurrence)
        val entries = if (hostQueue.entries.isEmpty()) additions else if (next)
            hostQueue.entries.take(hostQueue.cursor + 1) + additions + upcoming
        else hostQueue.entries.take(hostQueue.cursor + 1) + upcoming + additions
        hostQueue = hostQueue.copy(entries = entries, cursor = hostQueue.cursor.coerceAtMost(entries.lastIndex),
            queueRevision = hostQueue.queueRevision + 1, ended = false)
        publish()
    }

    fun clearLocalUpcoming() = synchronized(lock) {
        if (hostQueue.entries.isNotEmpty()) hostQueue = hostQueue.copy(
            entries = hostQueue.entries.take(hostQueue.cursor + 1), queueRevision = hostQueue.queueRevision + 1)
        publish()
    }

    fun jumpLocal(index: Int, expectedQueueRevision: Long) = synchronized(lock) {
        require(hostQueue.queueRevision == expectedQueueRevision && index in hostQueue.entries.indices) { "The demo queue changed. Choose the song again." }
        hostQueue = hostQueue.copy(cursor = index, positionMs = 0, playing = true, ended = false,
            transportRevision = hostQueue.transportRevision + 1)
        publish()
    }

    fun moveLocal(from: Int, to: Int, occurrenceId: Long, expectedQueueRevision: Long) = synchronized(lock) {
        require(hostQueue.queueRevision == expectedQueueRevision && hostQueue.occurrenceRevision == occurrenceId &&
            from in hostQueue.entries.indices && to in hostQueue.entries.indices) { "The demo queue changed. Choose the song again." }
        if (from != to) {
            val entries = hostQueue.entries.toMutableList()
            entries.add(to, entries.removeAt(from))
            val cursor = when {
                hostQueue.cursor == from -> to
                from < hostQueue.cursor && to >= hostQueue.cursor -> hostQueue.cursor - 1
                from > hostQueue.cursor && to <= hostQueue.cursor -> hostQueue.cursor + 1
                else -> hostQueue.cursor
            }
            hostQueue = hostQueue.copy(entries = entries, cursor = cursor, queueRevision = hostQueue.queueRevision + 1)
        }
        publish()
    }

    fun removeLocal(index: Int, occurrenceId: Long, expectedQueueRevision: Long) = synchronized(lock) {
        require(hostQueue.queueRevision == expectedQueueRevision && hostQueue.occurrenceRevision == occurrenceId &&
            index in hostQueue.entries.indices) { "The demo queue changed. Choose the song again." }
        val entries = hostQueue.entries.toMutableList().also { it.removeAt(index) }
        val cursor = when {
            entries.isEmpty() -> -1
            index < hostQueue.cursor -> hostQueue.cursor - 1
            index == hostQueue.cursor -> index.coerceAtMost(entries.lastIndex)
            else -> hostQueue.cursor
        }
        hostQueue = hostQueue.copy(entries = entries, cursor = cursor, positionMs = if (cursor < 0) 0 else hostQueue.positionMs,
            playing = hostQueue.playing && cursor >= 0, ended = false, queueRevision = hostQueue.queueRevision + 1)
        publish()
    }

    fun setLocalVolume(value: Float) = synchronized(lock) {
        if (value.isFinite()) volume = value.coerceIn(0f, 1f)
        publish()
    }

    fun cycleLocalRepeat() = synchronized(lock) {
        hostQueue = hostQueue.copy(repeatMode = when (hostQueue.repeatMode) {
            RepeatModeV2.OFF -> RepeatModeV2.ALL
            RepeatModeV2.ALL -> RepeatModeV2.ONE
            RepeatModeV2.ONE -> RepeatModeV2.OFF
        }, queueRevision = hostQueue.queueRevision + 1)
        publish()
    }

    fun shuffleLocalUpcoming() = synchronized(lock) {
        val entries = hostQueue.entries.take(hostQueue.cursor + 1) + hostQueue.entries.drop(hostQueue.cursor + 1).reversed()
        hostQueue = hostQueue.copy(entries = entries, shuffleEnabled = !hostQueue.shuffleEnabled,
            queueRevision = hostQueue.queueRevision + 1)
        publish()
    }

    fun setLocalSleepTimer(minutes: Int?) = synchronized(lock) {
        sleepRemainingMs = minutes?.coerceIn(1, 240)?.times(60_000L)
        publish()
    }

    fun playTracksOnDemo(id: String, sessionId: String, tracks: List<Track>, startIndex: Int) = synchronized(lock) {
        val peer = requireLivePeer(id, sessionId)
        require(group?.memberIds?.contains(id) != true) { "A Play together member is controlled through the shared player." }
        require(tracks.isNotEmpty() && startIndex in tracks.indices)
        peer.queue = queueFromTracks(tracks, startIndex, playing = true, base = peer.queue)
        publish()
    }

    fun replaceDemoGroupQueue(ref: GroupRef, expectedSequence: Long, tracks: List<Track>, startIndex: Int) = synchronized(lock) {
        val active = requireGroup(ref, expectedSequence, null)
        require(tracks.isNotEmpty() && startIndex in tracks.indices)
        val queue = queueFromTracks(tracks, startIndex, playing = true, base = hostQueue)
        replaceGroupTimeline(active, ref, active.timeline.sequence, transferQueue(queue))
        Unit
    }

    fun setDemoShuffle(id: String) = synchronized(lock) {
        val peer = peers[id] ?: return@synchronized
        peer.queue = peer.queue.copy(entries = peer.queue.entries.take(peer.queue.cursor + 1) +
            peer.queue.entries.drop(peer.queue.cursor + 1).reversed(),
            shuffleEnabled = !peer.queue.shuffleEnabled, queueRevision = peer.queue.queueRevision + 1)
        publish()
    }

    override suspend fun sendCommand(target: ConnectedDevice, action: ConnectedPlaybackAction, positionMs: Long) = synchronized(lock) {
        val peer = requireLivePeer(target.id, target.sessionId)
        require(group?.memberIds?.contains(peer.id) != true) { "A Play together member is controlled through the shared player." }
        require(target.player.trackRevision == peer.queue.occurrenceRevision.toString() &&
            target.player.queueRevision == peer.queue.queueRevision.toString()) { "The demo player changed. Select it again." }
        peer.queue = applyCommand(peer.queue, action, positionMs)
        publish()
    }

    override suspend fun moveTo(targetDeviceId: String, replacePlaying: Boolean) = synchronized(lock) {
        require(group == null) { "Leave Play together before moving this player." }
        val peer = peers[targetDeviceId]?.takeIf { it.online } ?: error("The selected demo player is offline.")
        require(!peer.queue.playing || replacePlaying) { "The selected demo player is playing. Confirm replacing its playback first." }
        require(hostQueue.entries.isNotEmpty() && hostQueue.cursor in hostQueue.entries.indices) { "Start a demo song before moving the queue." }
        moveHostBackup = hostQueue.copy()
        val tail = hostQueue.entries.drop(hostQueue.cursor)
        peer.queue = hostQueue.copy(entries = tail, cursor = 0, playing = true, ended = false,
            queueRevision = peer.queue.queueRevision + 1, occurrenceRevision = peer.queue.occurrenceRevision + 1,
            transportRevision = peer.queue.transportRevision + 1)
        hostQueue = hostQueue.copy(playing = false, transportRevision = hostQueue.transportRevision + 1)
        displacedOperation = UUID.randomUUID().toString()
        message = "Moved the demo queue to ${peer.label}. Native audio was not started."
        publish()
    }

    override suspend fun startTogether(targetDeviceIds: List<String>, replacePlaying: Boolean,
        expectedTargetSessions: Map<String, String>) = synchronized(lock) {
        require(group == null) { "Leave the current Play together session first." }
        require(targetDeviceIds.size in 1..7 && targetDeviceIds.distinct().size == targetDeviceIds.size && deviceId !in targetDeviceIds)
        require(expectedTargetSessions.keys == targetDeviceIds.toSet()) { "The selected demo sessions changed. Select the players again." }
        require(hostQueue.entries.isNotEmpty() && hostQueue.cursor in hostQueue.entries.indices) { "Start a demo song before Play together." }
        val selected = targetDeviceIds.map { id -> requireLivePeer(id, expectedTargetSessions.getValue(id)) }
        require(replacePlaying || selected.none { it.queue.playing }) { "A selected demo player is playing. Confirm replacing it first." }
        val ref = GroupRef(UUID.randomUUID().toString(), 1)
        val sharedQueue = transferQueue(hostQueue.copy(entries = hostQueue.entries.drop(hostQueue.cursor), cursor = 0))
        val sampledAt = now()
        val members = setOf(deviceId) + selected.map { it.sessionId }
        val timing = groupSyncTiming(selected.map { it.id }.toSet(), sampledAt)
        val startAt = groupStartAt(members, timing, sampledAt)
        val timeline = GroupTimeline(ref, 0, sharedQueue, 0,
            groupSyncPositionAt(hostQueue.positionMs, sampledAt, startAt,
                sharedQueue.entries.first().recording.durationMs, hostQueue.playing), hostQueue.playing, false, startAt)
        validateGroupTimeline(timeline)
        val backups = selected.associate { it.id to it.queue.copy(playing = false) }
        val priorHost = hostQueue.copy(playing = false)
        hostQueue = priorHost
        selected.forEach { peer -> peer.queue = hostQueue.copy(entries = hostQueue.entries.drop(hostQueue.cursor), cursor = 0,
            positionMs = hostQueue.positionMs, playing = true, queueRevision = peer.queue.queueRevision + 1,
            occurrenceRevision = peer.queue.occurrenceRevision + 1, transportRevision = peer.queue.transportRevision + 1) }
        group = DemoGroup(ref, selected.map { it.id }.toSet(), backups, priorHost, timeline)
        message = "Play together is active in the local demo simulator."
        publish()
    }

    override suspend fun playQueueOn(targetDeviceId: String, expectedTargetSessionId: String,
        expectedTargetFence: EngineFence, queue: TransferQueue): QueueOperationV2 = synchronized(lock) {
        validateTransferQueue(queue)
        val peer = requireLivePeer(targetDeviceId, expectedTargetSessionId)
        require(peer.queue.fence(peer.sessionId) == expectedTargetFence) { "The demo player changed. Select it again." }
        require(group?.memberIds?.contains(peer.id) != true) { "A Play together member is controlled through the shared player." }
        peer.queue = queueFromTransfer(queue, playing = true)
        publish()
        QueueOperationV2(UUID.randomUUID().toString(), QueueOperationV2Status.COMPLETE, peer.id,
            capture = capture(peer))
    }

    override suspend fun readRemoteQueue(targetDeviceId: String, expectedTargetSessionId: String,
        expectedTargetFence: EngineFence): EngineCapture = synchronized(lock) {
        val peer = requireLivePeer(targetDeviceId, expectedTargetSessionId)
        require(peer.queue.fence(peer.sessionId) == expectedTargetFence) { "The demo player changed. Select it again." }
        capture(peer)
    }

    override suspend fun replaceGroupQueue(expectedGroup: GroupRef, expectedTimelineSequence: Long,
        queue: TransferQueue): GroupQueueOperationV2 = synchronized(lock) {
        validateTransferQueue(queue)
        val active = requireGroup(expectedGroup, expectedTimelineSequence, null)
        val next = replaceGroupTimeline(active, expectedGroup, expectedTimelineSequence, queue)
        GroupQueueOperationV2(UUID.randomUUID().toString(), expectedGroup, GroupQueueOperationV2Status.COMPLETE,
            sequence = next.sequence)
    }

    override suspend fun restoreLatestDisplacedPaused() = synchronized(lock) {
        val prior = moveHostBackup ?: error("There is no demo queue to restore.")
        require(group == null) { "Leave Play together before restoring the demo queue." }
        hostQueue = prior.copy(playing = false, transportRevision = prior.transportRevision + 1)
        moveHostBackup = null
        displacedOperation = null
        message = "Restored the previous demo queue paused."
        publish()
    }

    override suspend fun requestGroup(action: GroupActionV2, positionMs: Long,
        expectedGroup: GroupRef?, expectedTimelineSequence: Long?, expectedOccurrenceId: String?) = synchronized(lock) {
        val active = group ?: error("Play together is not active.")
        val current = currentTimeline(active)
        require(expectedGroup == null || expectedGroup == current.ref) { "Play together changed. Select the shared player again." }
        require(expectedTimelineSequence == null || expectedTimelineSequence == current.sequence &&
            current.queue.entries.getOrNull(current.cursor)?.id == expectedOccurrenceId) {
            "The shared track changed while you were scrubbing. Try again."
        }
        if (action == GroupActionV2.LEAVE) {
            leaveGroupLocked()
            publish()
            return@synchronized
        }
        if (action == GroupActionV2.SYNC) {
            val sampledAt = now()
            val members = setOf(deviceId) + active.memberIds.mapNotNull { peers[it]?.sessionId }
            val startAt = groupStartAt(members, groupSyncTiming(active.memberIds, sampledAt), sampledAt)
            val duration = current.queue.entries[current.cursor].recording.durationMs
            active.timeline = current.copy(sequence = current.sequence + 1,
                positionMs = groupSyncPositionAt(current.positionMs, sampledAt, startAt, duration, current.playing),
                issuedAtMs = startAt)
            message = "Adjusting timing for ${active.memberIds.size + 1} demo players."
        } else {
            val mapped = when (action) {
                GroupActionV2.PLAY -> GroupTimelineAction.PLAY
                GroupActionV2.PAUSE -> GroupTimelineAction.PAUSE
                GroupActionV2.SEEK -> GroupTimelineAction.SEEK
                GroupActionV2.NEXT -> GroupTimelineAction.NEXT
                GroupActionV2.PREVIOUS -> GroupTimelineAction.PREVIOUS
                GroupActionV2.SYNC, GroupActionV2.LEAVE -> error("Unexpected action")
            }
            active.timeline = advanceGroupTimeline(current, mapped, positionMs, now())
                ?: error("The demo shared timeline could not apply that action.")
            applyGroupQueue(active.timeline)
        }
        publish()
    }

    override suspend fun stopGracefully() = synchronized(lock) {
        if (group != null) leaveGroupLocked()
        publish()
    }

    override fun stop() { scope.cancel() }

    private data class DemoOccurrence(val id: String, val recordingId: String, val track: Track)
    private data class DemoQueue(
        val entries: List<DemoOccurrence>, val cursor: Int, val positionMs: Long, val playing: Boolean,
        val repeatMode: RepeatModeV2 = RepeatModeV2.OFF, val shuffleEnabled: Boolean = false,
        val queueRevision: Long = 0, val occurrenceRevision: Long = 0, val transportRevision: Long = 0,
        val ended: Boolean = false,
    ) {
        fun fence(sessionId: String) = EngineFence(sessionId, queueRevision, occurrenceRevision, transportRevision)
    }
    private data class DemoPeer(val id: String, val label: String, var sessionId: String, var online: Boolean,
        var queue: DemoQueue, var latency: DemoLatencyPreset = DemoLatencyPreset.FAST_20)
    private data class DemoGroup(
        val ref: GroupRef, val memberIds: Set<String>, val peerBackups: Map<String, DemoQueue>,
        val hostBackup: DemoQueue, var timeline: GroupTimeline,
    )

    private fun seededQueue(seed: Int = 0): DemoQueue {
        val songs = demoTracks()
        catalog.putAll(songs.associateBy { portableId(it.id) })
        val rotated = if (seed % 2 == 0) songs else songs.drop(1) + songs.first()
        val entries = listOf(rotated[0], rotated[1], rotated[0], rotated[2]).map(::occurrence)
        return DemoQueue(entries, cursor = 0, positionMs = 24_000L + seed * 7_000L, playing = seed % 2 == 1,
            queueRevision = 1, occurrenceRevision = 1)
    }

    private fun demoTracks() = listOf(
        Track("youtube:stage000001", "Stage Signal", "Echo Demo", durationMs = 190_000, provider = "youtube"),
        Track("youtube:stage000002", "Soft Current", "Echo Demo", durationMs = 213_000, provider = "youtube"),
        Track("youtube:stage000003", "Afterglow Loop", "Echo Demo", durationMs = 176_000, provider = "youtube"),
    )

    private fun queueFromTracks(tracks: List<Track>, index: Int, playing: Boolean, base: DemoQueue) = DemoQueue(
        entries = tracks.map(::occurrence), cursor = index, positionMs = 0, playing = playing,
        queueRevision = base.queueRevision + 1, occurrenceRevision = base.occurrenceRevision + 1,
        transportRevision = base.transportRevision + 1,
    )

    private fun occurrence(track: Track): DemoOccurrence {
        val id = portableId(track.id)
        val demoTrack = track.copy(id = id, artworkUri = null, sourceUri = null, provider = "youtube",
            durationMs = track.durationMs.coerceIn(1L, 604_800_000L))
        catalog[id] = demoTrack
        return DemoOccurrence(UUID.randomUUID().toString(), id, demoTrack)
    }

    private fun portableId(id: String): String = if (Regex("youtube:[A-Za-z0-9_-]{11}").matches(id)) id else {
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        "youtube:${hash.take(11)}"
    }

    private fun transferQueue(queue: DemoQueue): TransferQueue {
        val result = TransferQueue(queue.entries.drop(queue.cursor.coerceAtLeast(0)).map { entry ->
            QueueOccurrence(entry.id, PortableRecording(entry.recordingId, entry.track.title.ifBlank { "Demo track" },
                entry.track.artist, entry.track.durationMs.coerceIn(1L, 604_800_000L)))
        }, queue.repeatMode, queue.shuffleEnabled)
        if (result.entries.isNotEmpty()) validateTransferQueue(result)
        return result
    }

    private fun queueFromTransfer(queue: TransferQueue, playing: Boolean): DemoQueue = DemoQueue(
        entries = queue.entries.map { entry ->
            val track = catalog[entry.recording.id] ?: Track(entry.recording.id, entry.recording.title,
                entry.recording.artist, durationMs = entry.recording.durationMs, provider = "youtube")
                .also { catalog[entry.recording.id] = it }
            DemoOccurrence(entry.id, entry.recording.id, track)
        }, cursor = 0, positionMs = 0, playing = playing, repeatMode = queue.repeatMode,
        shuffleEnabled = queue.shuffleEnabled, queueRevision = hostQueue.queueRevision + 1,
        occurrenceRevision = hostQueue.occurrenceRevision + 1, transportRevision = hostQueue.transportRevision + 1,
    )

    private fun applyCommand(queue: DemoQueue, action: ConnectedPlaybackAction, positionMs: Long): DemoQueue {
        if (queue.entries.isEmpty()) return queue
        val cursor = queue.cursor.coerceIn(queue.entries.indices)
        val nextCursor = when (action) {
            ConnectedPlaybackAction.NEXT -> if (cursor < queue.entries.lastIndex) cursor + 1 else if (queue.repeatMode == RepeatModeV2.ALL) 0 else cursor
            ConnectedPlaybackAction.PREVIOUS -> if (cursor > 0) cursor - 1 else if (queue.repeatMode == RepeatModeV2.ALL) queue.entries.lastIndex else 0
            else -> cursor
        }
        val next = queue.copy(cursor = nextCursor, positionMs = when (action) {
            ConnectedPlaybackAction.SEEK -> positionMs.coerceIn(0, queue.entries[nextCursor].track.durationMs)
            ConnectedPlaybackAction.NEXT, ConnectedPlaybackAction.PREVIOUS -> 0
            else -> queue.positionMs
        }, playing = when (action) {
            ConnectedPlaybackAction.PLAY -> true
            ConnectedPlaybackAction.PAUSE -> false
            ConnectedPlaybackAction.NEXT -> queue.playing && (nextCursor != cursor || queue.repeatMode == RepeatModeV2.ALL)
            else -> queue.playing
        }, ended = false, transportRevision = queue.transportRevision + 1,
            occurrenceRevision = if (nextCursor != cursor) queue.occurrenceRevision + 1 else queue.occurrenceRevision)
        return next
    }

    private fun applyGroupQueue(timeline: GroupTimeline) {
        val queue = queueFromTransfer(timeline.queue, timeline.playing).copy(cursor = timeline.cursor,
            positionMs = timeline.positionMs, playing = timeline.playing)
        hostQueue = queue.copy(queueRevision = hostQueue.queueRevision + 1)
        group?.memberIds.orEmpty().forEach { id -> peers[id]?.let { peer ->
            peer.queue = queue.copy(queueRevision = peer.queue.queueRevision + 1)
        } }
    }

    private fun currentTimeline(active: DemoGroup): GroupTimeline {
        val timeline = active.timeline
        val currentAt = clockMs()
        if (!timeline.playing) return timeline
        if (currentAt < timeline.issuedAtMs) return timeline
        val elapsed = currentAt - timeline.issuedAtMs
        val duration = timeline.queue.entries[timeline.cursor].recording.durationMs
        val position = if (elapsed < 0) (timeline.positionMs + elapsed).coerceAtLeast(0) else timeline.positionMs + elapsed
        if (position < duration) return timeline.copy(positionMs = position, issuedAtMs = currentAt)
        return advanceGroupTimeline(timeline.copy(positionMs = duration), GroupTimelineAction.NATURAL_END, 0, now())
            ?: timeline.copy(positionMs = duration, playing = false, ended = true)
    }

    private fun groupSyncTiming(memberIds: Set<String>, sampledAtMs: Long) =
        listOf(GroupSyncTimingV1(deviceId, deviceId, 20, 5, 10, sampledAtMs)) + memberIds.mapNotNull { id ->
            peers[id]?.takeIf { it.online }?.let { peer ->
                val dynamicOffset = if (peer.latency == DemoLatencyPreset.VARIABLE) sampledAtMs / 1_000L % 300L else 0L
                val rttMs = peer.latency.rttMs + dynamicOffset
                GroupSyncTimingV1(peer.id, peer.sessionId, rttMs,
                    peer.latency.jitterMs, (rttMs + 1) / 2, sampledAtMs)
            }
        }

    private fun groupStartAt(memberSessions: Set<String>, timing: List<GroupSyncTimingV1>, sampledAtMs: Long) =
        groupSyncDeadline(sampledAtMs, memberSessions, timing.map { it.sample() }).startAtMs

    private fun replaceGroupTimeline(active: DemoGroup, ref: GroupRef, sequence: Long, queue: TransferQueue): GroupTimeline {
        val sampledAt = now()
        val members = setOf(deviceId) + active.memberIds.mapNotNull { peers[it]?.sessionId }
        val startAt = groupStartAt(members, groupSyncTiming(active.memberIds, sampledAt), sampledAt)
        val next = GroupTimeline(ref, sequence + 1, queue, 0, 0, true, false, startAt)
        active.timeline = next
        applyGroupQueue(next)
        publish()
        return next
    }

    private fun requireLivePeer(id: String, sessionId: String): DemoPeer {
        val peer = peers[id] ?: error("The selected demo device is no longer available.")
        require(peer.online && peer.sessionId == sessionId) { "The demo device changed sessions. Select it again." }
        return peer
    }

    private fun requireGroup(ref: GroupRef, sequence: Long, occurrenceId: String?): DemoGroup {
        val active = group ?: error("Play together is not active.")
        val timeline = currentTimeline(active)
        require(active.ref == ref && timeline.sequence == sequence &&
            (occurrenceId == null || timeline.queue.entries.getOrNull(timeline.cursor)?.id == occurrenceId)) {
            "The shared queue changed. Select the song again."
        }
        return active.also { it.timeline = timeline }
    }

    private fun leaveGroupLocked() {
        val active = group ?: return
        hostQueue = active.hostBackup.copy(playing = false, transportRevision = active.hostBackup.transportRevision + 1)
        active.peerBackups.forEach { (id, queue) -> peers[id]?.let {
            it.queue = queue.copy(playing = false, transportRevision = queue.transportRevision + 1)
        } }
        group = null
        message = "Left Play together. Demo players restored their private queues paused."
    }

    private fun capture(peer: DemoPeer): EngineCapture {
        val queue = transferQueue(peer.queue)
        return EngineCapture(peer.queue.fence(peer.sessionId), queue, peer.queue.positionMs, peer.queue.playing)
    }

    private fun localSnapshot(): DesktopPlaybackState {
        val current = hostQueue.entries.getOrNull(hostQueue.cursor)
        return DesktopPlaybackState(queue = hostQueue.entries.map { it.track }, index = hostQueue.cursor,
            playing = hostQueue.playing, positionMs = hostQueue.positionMs, durationMs = current?.track?.durationMs ?: 0,
            volume = volume, occurrence = hostQueue.occurrenceRevision, queueRevision = hostQueue.queueRevision,
            transportRevision = hostQueue.transportRevision, repeatMode = when (hostQueue.repeatMode) {
                RepeatModeV2.OFF -> DesktopRepeatMode.OFF
                RepeatModeV2.ALL -> DesktopRepeatMode.ALL
                RepeatModeV2.ONE -> DesktopRepeatMode.ONE
            }, ended = hostQueue.ended, sleepRemainingMs = sleepRemainingMs,
            displacedMoveOperationId = displacedOperation)
    }

    private fun peerSnapshot(peer: DemoPeer): ConnectedPlayerSnapshot {
        val current = peer.queue.entries.getOrNull(peer.queue.cursor)
        val fromGroup = group?.takeIf { peer.id in it.memberIds }?.let { currentTimeline(it) }
        val groupEntry = fromGroup?.queue?.entries?.getOrNull(fromGroup.cursor)?.recording
        return ConnectedPlayerSnapshot(
            trackId = groupEntry?.id ?: current?.recordingId,
            title = groupEntry?.title ?: current?.track?.title,
            artist = groupEntry?.artist ?: current?.track?.artist,
            isPlaying = fromGroup?.playing ?: peer.queue.playing,
            positionMs = fromGroup?.positionMs ?: peer.queue.positionMs,
            durationMs = groupEntry?.durationMs ?: current?.track?.durationMs ?: 0L,
            trackRevision = (fromGroup?.sequence ?: peer.queue.occurrenceRevision).toString(),
            queueRevision = (fromGroup?.sequence ?: peer.queue.queueRevision).toString(),
        )
    }

    private fun v2Device(id: String, label: String, session: String, online: Boolean, queue: DemoQueue): ConnectedV2Device {
        val entry = queue.entries.getOrNull(queue.cursor)
        val observation = EngineObservation(queue.fence(session), entry?.id, queue.positionMs, queue.playing,
            ready = online, ended = queue.ended, recordingId = entry?.recordingId,
            title = entry?.track?.title, artist = entry?.track?.artist, durationMs = entry?.track?.durationMs ?: 0)
        return ConnectedV2Device(id, label, session, now(), online,
            setOf("move", "group", "queue", "group_queue", "sync"), observation)
    }

    private fun publish() {
        val now = now()
        val memberIds = group?.memberIds.orEmpty()
        val legacyPeers = peers.values.map { peer ->
            ConnectedDevice(peer.id, peer.label, peer.sessionId, now, peerSnapshot(peer),
                controlEnabled = peer.online && peer.id !in memberIds)
        }
        mutableState.value = ConnectedPlaybackState(legacyPeers, message, pending = false)
        val groupValue = group?.let { active ->
            val timeline = currentTimeline(active)
            val members = (listOf(deviceId) + active.memberIds).map { id ->
                val online = id == deviceId || peers[id]?.online == true
                val status = if (!online) GroupMemberV2Status.DISCONNECTED
                    else if (timeline.playing) GroupMemberV2Status.PLAYING else GroupMemberV2Status.PAUSED
                val occurrence = timeline.queue.entries.getOrNull(timeline.cursor)?.id
                GroupMemberV2(id, if (id == deviceId) deviceId else peers.getValue(id).sessionId,
                    active.ref.epoch, timeline.sequence, status, occurrence, timeline.positionMs, now)
            }
            val timing = groupSyncTiming(active.memberIds, now)
            val preparing = now < (active.timeline?.issuedAtMs ?: now)
            ConnectedGroupV2(active.ref, deviceId, deviceId, now + 60_000, timeline.sequence,
                if (preparing) GroupV2Status.PREPARING else GroupV2Status.ACTIVE,
                timeline, members.map { member -> member.copy(status = when {
                    member.status == GroupMemberV2Status.DISCONNECTED -> GroupMemberV2Status.DISCONNECTED
                    preparing -> GroupMemberV2Status.BUFFERING
                    timeline.playing -> GroupMemberV2Status.PLAYING
                    else -> GroupMemberV2Status.PAUSED
                }) }, timing)
        }
        val v2Devices = listOf(v2Device(deviceId, "Stage host · virtual", deviceId, true, hostQueue)) + peers.values.map {
            v2Device(it.id, it.label, it.sessionId, it.online, it.queue)
        }
        val move = displacedOperation?.let { MoveOperationV2(it, MoveV2Status.COMPLETE, deviceId,
            peers.values.firstOrNull { peer -> peer.queue.playing }?.id ?: deviceId) }
        mutableV2State.value = ConnectedPlaybackV2State(v2Devices, move = move, group = groupValue,
            message = message, pending = groupValue?.status == GroupV2Status.PREPARING)
        mutableLocalPlayback.value = localSnapshot()
    }

    private fun tickLocked(now: Long) {
        val delta = (now - lastTickMs).coerceIn(0, 1_000)
        lastTickMs = now
        if (delta <= 0) return
        if (group != null) {
            val active = group!!
            if (now >= active.timeline.issuedAtMs) {
                val current = currentTimeline(active)
                if (current.sequence != active.timeline.sequence) applyGroupQueue(current)
                active.timeline = current
            }
            peers.values.filter { it.online && it.id !in active.memberIds }.forEach { it.queue = advanceQueue(it.queue, delta) }
        } else {
            hostQueue = advanceQueue(hostQueue, delta)
            peers.values.filter { it.online }.forEach { it.queue = advanceQueue(it.queue, delta) }
        }
        sleepRemainingMs = sleepRemainingMs?.let { remaining ->
            (remaining - delta).coerceAtLeast(0).also { if (it == 0L) hostQueue = hostQueue.copy(playing = false) }.takeIf { it > 0 }
        }
        publish()
    }

    private fun advanceQueue(queue: DemoQueue, deltaMs: Long): DemoQueue {
        if (!queue.playing || queue.entries.isEmpty() || queue.cursor !in queue.entries.indices) return queue
        var cursor = queue.cursor
        var position = queue.positionMs + deltaMs
        var ended = false
        while (position >= queue.entries[cursor].track.durationMs) {
            position -= queue.entries[cursor].track.durationMs
            when {
                queue.repeatMode == RepeatModeV2.ONE -> Unit
                cursor < queue.entries.lastIndex -> cursor++
                queue.repeatMode == RepeatModeV2.ALL -> cursor = 0
                else -> { position = queue.entries[cursor].track.durationMs; ended = true; break }
            }
        }
        return queue.copy(cursor = cursor, positionMs = position,
            playing = queue.playing && !ended, ended = ended,
            occurrenceRevision = if (cursor != queue.cursor) queue.occurrenceRevision + 1 else queue.occurrenceRevision,
            transportRevision = if (cursor != queue.cursor || ended) queue.transportRevision + 1 else queue.transportRevision)
    }

    private fun now() = clockMs().coerceAtLeast(1L)
}

internal fun seedStageLibraryIfEmpty(store: DesktopLibrary, demoTracks: List<Track>): DesktopLibraryState {
    val current = store.snapshot()
    if (current.tracks.isNotEmpty() || current.playlists.isNotEmpty()) return current
    val samples = demoTracks.distinctBy { it.id }
    if (samples.isNotEmpty()) store.importParsedPlaylists(
        DesktopPlaylistImport(listOf(DesktopImportPlaylist("Stage Mix", samples))),
    )
    return store.snapshot()
}
