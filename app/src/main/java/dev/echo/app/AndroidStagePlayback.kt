package dev.echo.app

import dev.echo.core.Track
import dev.echo.core.connected.EngineCapture
import dev.echo.core.connected.QueueOccurrence
import dev.echo.core.connected.RepeatModeV2
import dev.echo.core.connected.TransferQueue
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedPlaybackAction
import dev.echo.data.firebase.ConnectedPlaybackState
import dev.echo.data.firebase.ConnectedPlayerSnapshot
import dev.echo.data.firebase.ConnectedPlaybackV2State
import dev.echo.data.firebase.ConnectedV2Device
import dev.echo.data.firebase.ConnectedGroupV2
import dev.echo.data.firebase.GroupSyncTimingV1
import dev.echo.data.firebase.GroupMemberV2
import dev.echo.data.firebase.GroupMemberV2Status
import dev.echo.data.firebase.GroupV2Status
import dev.echo.core.connected.EngineFence
import dev.echo.core.connected.EngineObservation
import dev.echo.core.connected.GroupTimeline
import dev.echo.core.connected.groupSyncDeadline
import dev.echo.core.connected.PortableRecording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

private fun stageMonotonicNow() = System.nanoTime() / 1_000_000L

/** In-process Android demo peers. No Firebase or audio source is involved. */
internal class AndroidStagePlayback {
    internal enum class LatencyPreset(val rttMs: Long, val jitterMs: Long) {
        FAST_20(20, 3), SLOW_500(500, 35), VARIABLE(180, 90)
    }
    internal data class AdminPeer(val id: String, val label: String, val online: Boolean,
        val latency: LatencyPreset = LatencyPreset.FAST_20)
    private data class Peer(
        val id: String,
        val label: String,
        val session: String,
        val online: Boolean,
        val latency: LatencyPreset = LatencyPreset.FAST_20,
        val queue: List<Track> = emptyList(),
        val occurrenceIds: List<String> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val updatedAtMs: Long = stageMonotonicNow(),
        val trackRevision: Long = 0,
        val queueRevision: Long = 0,
        val transportRevision: Long = 0,
    )

    private val peers = mutableListOf<Peer>()
    private var group: ConnectedGroupV2? = null
    private var groupPeerBackups: Map<String, Peer> = emptyMap()
    private val mutableState = MutableStateFlow(ConnectedPlaybackState())
    val state = mutableState.asStateFlow()
    private val mutableAdminPeers = MutableStateFlow<List<AdminPeer>>(emptyList())
    val adminPeers = mutableAdminPeers.asStateFlow()
    private val mutableV2State = MutableStateFlow(ConnectedPlaybackV2State())
    val v2State = mutableV2State.asStateFlow()
    private fun latencySample(peer: Peer, nowMs: Long): Pair<Long, Long> = when (peer.latency) {
        LatencyPreset.FAST_20 -> 20L to 3L
        LatencyPreset.SLOW_500 -> 500L to 35L
        LatencyPreset.VARIABLE -> 80L + Math.floorMod(peer.id.hashCode().toLong(), 5) * 50L to
            (20L + Math.floorMod(nowMs / 2_000L + peer.id.hashCode(), 7) * 15L)
    }

    fun add(label: String) {
        val id = "android-stage-${UUID.randomUUID()}"
        peers += Peer(id, label, UUID.randomUUID().toString(), true)
        publish()
    }
    fun setLatency(id: String, preset: LatencyPreset) {
        val index = peers.indexOfFirst { it.id == id }
        if (index >= 0) peers[index] = peers[index].copy(latency = preset)
        publish()
    }
    fun toggle(id: String, nowMs: Long = stageMonotonicNow()) {
        val index = peers.indexOfFirst { it.id == id }
        if (index >= 0) {
            val peer = advance(peers[index], nowMs)
            peers[index] = peer.copy(online = !peer.online,
                session = if (peer.online) peer.session else UUID.randomUUID().toString(), updatedAtMs = nowMs)
        }
        publish(nowMs)
    }
    fun remove(id: String, nowMs: Long = stageMonotonicNow()) {
        if (group?.members?.any { it.deviceId == id } == true) {
            restoreGroupPeers(nowMs)
            group = null
        }
        peers.removeAll { it.id == id }
        publish(nowMs)
    }
    fun has(id: String, session: String): Boolean = peers.any { it.id == id && it.session == session && it.online }

    private fun replaceQueue(id: String, tracks: List<Track>, start: Int, nowMs: Long = stageMonotonicNow()) {
        require(tracks.isNotEmpty() && start in tracks.indices)
        peers.replace(id) { peer -> peer.copy(
            queue = tracks,
            occurrenceIds = tracks.map { UUID.randomUUID().toString() },
            index = start,
            playing = true,
            positionMs = 0,
            updatedAtMs = nowMs,
            trackRevision = peer.trackRevision + 1,
            queueRevision = peer.queueRevision + 1,
            transportRevision = peer.transportRevision + 1,
        ) }
        publish(nowMs)
    }

    fun replaceQueue(target: ConnectedDevice, tracks: List<Track>, start: Int, nowMs: Long = stageMonotonicNow()): Boolean {
        val index = peers.indexOfFirst { it.id == target.id && it.session == target.sessionId && it.online }
        if (index < 0) return false
        replaceQueue(target.id, tracks, start, nowMs)
        return true
    }

    fun command(target: ConnectedDevice, action: ConnectedPlaybackAction, position: Long = 0, nowMs: Long = stageMonotonicNow()): Boolean {
        val index = peers.indexOfFirst { it.id == target.id && it.session == target.sessionId && it.online }
        if (index < 0) return false
        val peer = advance(peers[index], nowMs)
        if (target.player.trackRevision != revision(peer, peer.trackRevision) ||
            target.player.queueRevision != revision(peer, peer.queueRevision)) return false
        peers[index] = when (action) {
            ConnectedPlaybackAction.PLAY -> peer.queue.getOrNull(peer.index)?.let { track -> peer.copy(
                playing = true,
                positionMs = if (peer.positionMs >= track.durationMs) 0 else peer.positionMs,
                updatedAtMs = nowMs,
                transportRevision = peer.transportRevision + 1,
            ) } ?: peer
            ConnectedPlaybackAction.PAUSE -> peer.copy(playing = false, updatedAtMs = nowMs, transportRevision = peer.transportRevision + 1)
            ConnectedPlaybackAction.NEXT -> if (peer.index < peer.queue.lastIndex) peer.copy(index = peer.index + 1, positionMs = 0,
                updatedAtMs = nowMs, trackRevision = peer.trackRevision + 1, transportRevision = peer.transportRevision + 1) else peer
            ConnectedPlaybackAction.PREVIOUS -> peer.copy(index = (peer.index - 1).coerceAtLeast(0), positionMs = 0,
                updatedAtMs = nowMs, trackRevision = peer.trackRevision + 1, transportRevision = peer.transportRevision + 1)
            ConnectedPlaybackAction.SEEK -> peer.copy(positionMs = position.coerceIn(0, peer.queue.getOrNull(peer.index)?.durationMs ?: 0),
                updatedAtMs = nowMs, transportRevision = peer.transportRevision + 1)
        }
        publish(nowMs)
        return true
    }

    fun readQueue(target: ConnectedDevice, nowMs: Long = stageMonotonicNow()): EngineCapture? {
        val index = peers.indexOfFirst { it.id == target.id && it.session == target.sessionId && it.online }
        if (index < 0) return null
        val peer = advance(peers[index], nowMs)
        peers[index] = peer
        if (target.player.trackRevision != revision(peer, peer.trackRevision) ||
            target.player.queueRevision != revision(peer, peer.queueRevision)) return null
        val entries = peer.queue.drop(peer.index.coerceAtLeast(0)).mapIndexed { offset, track ->
            QueueOccurrence(peer.occurrenceIds.getOrNull(peer.index.coerceAtLeast(0) + offset) ?: UUID.randomUUID().toString(),
                PortableRecording(track.id, track.title, track.artist, track.durationMs))
        }
        return EngineCapture(
            EngineFence(peer.session, peer.queueRevision, peer.trackRevision, peer.transportRevision),
            TransferQueue(entries, RepeatModeV2.OFF, shuffleEnabled = false),
            peer.positionMs,
            peer.playing,
        )
    }

    fun tick(nowMs: Long = stageMonotonicNow()) {
        var changed = false
        peers.indices.forEach { index ->
            val updated = advance(peers[index], nowMs)
            if (updated != peers[index]) { peers[index] = updated; changed = true }
        }
        if (changed || group != null) publish(nowMs)
    }

    fun startTogether(ids: List<String>, sessions: Map<String, String>, tracks: List<Track>, nowMs: Long = stageMonotonicNow()) {
        require(ids.size in 1..7 && ids.distinct().size == ids.size && tracks.isNotEmpty())
        val selected = ids.map { id -> peers.firstOrNull { it.id == id && it.online && it.session == sessions[id] }
            ?: error("A selected Stage player disconnected. Select it again.") }
        groupPeerBackups = selected.associate { it.id to advance(it, nowMs).copy(playing = false, updatedAtMs = nowMs) }
        val ref = dev.echo.core.connected.GroupRef(UUID.randomUUID().toString(), 1)
        val localId = "android-stage-local"
        val localSession = "android-stage-local-session"
        val entries = tracks.map { track -> QueueOccurrence(UUID.randomUUID().toString(),
            PortableRecording(track.id, track.title, track.artist, track.durationMs)) }
        val queue = TransferQueue(entries, RepeatModeV2.OFF, shuffleEnabled = false)
        val members = listOf(GroupMemberV2(localId, localSession, 1, 0, GroupMemberV2Status.BUFFERING)) + selected.map {
            GroupMemberV2(it.id, it.session, 1, 0, if (it.online) GroupMemberV2Status.BUFFERING else GroupMemberV2Status.DISCONNECTED)
        }
        val syncTiming = groupSyncTiming(members, nowMs)
        val startAt = groupSyncStartAt(nowMs, members, syncTiming)
        val timeline = GroupTimeline(ref, 0, queue, 0, 0, true, issuedAtMs = startAt)
        group = ConnectedGroupV2(ref, localId, localSession, startAt + 60_000, 0, GroupV2Status.PREPARING,
            timeline, members, syncTiming)
        selected.forEach { peer -> replaceQueue(peer.id, tracks, 0, startAt) }
        publish(nowMs)
    }

    fun replaceGroupQueue(tracks: List<Track>, start: Int = 0, nowMs: Long = stageMonotonicNow()): Boolean {
        val current = group ?: return false
        if (tracks.isEmpty() || start !in tracks.indices) return false
        val syncTiming = groupSyncTiming(current.members, nowMs)
        val scheduledStart = groupSyncStartAt(nowMs, current.members, syncTiming)
        val entries = tracks.map { QueueOccurrence(UUID.randomUUID().toString(), PortableRecording(it.id, it.title, it.artist, it.durationMs)) }
        group = current.copy(status = GroupV2Status.PREPARING, timelineSequence = current.timelineSequence + 1,
            timeline = GroupTimeline(current.ref, current.timelineSequence + 1, TransferQueue(entries, RepeatModeV2.OFF, false), start, 0, true, issuedAtMs = scheduledStart),
            syncTiming = syncTiming)
        current.members.filter { it.deviceId != "android-stage-local" }.forEach { member ->
            val peer = peers.firstOrNull { it.id == member.deviceId && it.session == member.sessionId && it.online }
            if (peer != null) replaceQueue(peer.id, tracks, start, scheduledStart)
        }
        publish(nowMs); return true
    }

    fun controlTogether(action: dev.echo.data.firebase.GroupActionV2, position: Long = 0, nowMs: Long = stageMonotonicNow()): Boolean {
        val current = group ?: return false
        val timeline = current.timeline ?: return false
        val syncTiming = if (action == dev.echo.data.firebase.GroupActionV2.SYNC)
            groupSyncTiming(current.members, nowMs) else current.syncTiming
        val syncStartAt = if (action == dev.echo.data.firebase.GroupActionV2.SYNC)
            groupSyncStartAt(nowMs, current.members, syncTiming) else nowMs
        val next = when (action) {
            dev.echo.data.firebase.GroupActionV2.PLAY -> timeline.copy(playing = true, issuedAtMs = nowMs)
            dev.echo.data.firebase.GroupActionV2.PAUSE -> timeline.copy(playing = false, positionMs = timeline.positionMs + (nowMs - timeline.issuedAtMs).coerceAtLeast(0), issuedAtMs = nowMs)
            dev.echo.data.firebase.GroupActionV2.SEEK -> timeline.copy(positionMs = position.coerceAtLeast(0), issuedAtMs = nowMs)
            dev.echo.data.firebase.GroupActionV2.NEXT -> timeline.copy(cursor = (timeline.cursor + 1).coerceAtMost(timeline.queue.entries.lastIndex), positionMs = 0, playing = true, issuedAtMs = nowMs)
            dev.echo.data.firebase.GroupActionV2.PREVIOUS -> timeline.copy(cursor = (timeline.cursor - 1).coerceAtLeast(0), positionMs = 0, playing = true, issuedAtMs = nowMs)
            dev.echo.data.firebase.GroupActionV2.SYNC -> {
                val duration = timeline.queue.entries.getOrNull(timeline.cursor)?.recording?.durationMs ?: Long.MAX_VALUE
                val projectedPosition = if (timeline.playing)
                    (timeline.positionMs + (syncStartAt - timeline.issuedAtMs).coerceAtLeast(0)).coerceAtMost(duration)
                else timeline.positionMs
                timeline.copy(positionMs = projectedPosition, issuedAtMs = syncStartAt)
            }
            dev.echo.data.firebase.GroupActionV2.LEAVE -> {
                restoreGroupPeers(nowMs)
                group = null
                publish(nowMs)
                return true
            }
        }
        group = current.copy(status = if (action == dev.echo.data.firebase.GroupActionV2.SYNC) GroupV2Status.PREPARING else GroupV2Status.ACTIVE,
            timelineSequence = current.timelineSequence + 1, timeline = next.copy(sequence = current.timelineSequence + 1),
            syncTiming = syncTiming)
        publish(nowMs); return true
    }

    private fun restoreGroupPeers(nowMs: Long) {
        groupPeerBackups.forEach { (id, backup) ->
            val index = peers.indexOfFirst { it.id == id }
            if (index >= 0) {
                val current = peers[index]
                peers[index] = backup.copy(label = current.label, session = current.session, online = current.online,
                    latency = current.latency, playing = false, updatedAtMs = nowMs,
                    trackRevision = maxOf(backup.trackRevision, current.trackRevision) + 1,
                    queueRevision = maxOf(backup.queueRevision, current.queueRevision) + 1,
                    transportRevision = maxOf(backup.transportRevision, current.transportRevision) + 1)
            }
        }
        groupPeerBackups = emptyMap()
    }

    private fun advance(peer: Peer, nowMs: Long): Peer {
        val track = peer.queue.getOrNull(peer.index)
        if (!peer.online || !peer.playing || track == null || nowMs < peer.updatedAtMs) return peer
        val position = (peer.positionMs + (nowMs - peer.updatedAtMs).coerceAtLeast(0)).coerceAtMost(track.durationMs)
        if (position < track.durationMs) return peer.copy(positionMs = position, updatedAtMs = nowMs)
        // shortcut: one 500 ms tick can lose boundary overrun; carry it if Stage needs tighter timing.
        return if (peer.index < peer.queue.lastIndex) peer.copy(index = peer.index + 1, positionMs = 0, updatedAtMs = nowMs,
            trackRevision = peer.trackRevision + 1, transportRevision = peer.transportRevision + 1)
        else peer.copy(positionMs = track.durationMs, playing = false, updatedAtMs = nowMs, transportRevision = peer.transportRevision + 1)
    }

    private fun revision(peer: Peer, value: Long) = "stage:${peer.session}:$value"

    private fun groupSyncTiming(members: List<GroupMemberV2>, nowMs: Long): List<GroupSyncTimingV1> = members.mapNotNull { member ->
        val timing = if (member.deviceId == "android-stage-local") 20L to 3L else
            peers.firstOrNull { it.id == member.deviceId && it.session == member.sessionId && it.online }
                ?.let { latencySample(it, nowMs) } ?: return@mapNotNull null
        GroupSyncTimingV1(member.deviceId, member.sessionId, timing.first, timing.second,
            (timing.first + 1) / 2, System.currentTimeMillis())
    }

    private fun groupSyncStartAt(nowMs: Long, members: List<GroupMemberV2>, timings: List<GroupSyncTimingV1>) =
        groupSyncDeadline(nowMs, members.map { it.sessionId }.toSet(),
            timings.map { it.sample().copy(sampledAtMs = nowMs) }).startAtMs

    private fun MutableList<Peer>.replace(id: String, transform: (Peer) -> Peer) {
        val index = indexOfFirst { it.id == id }
        if (index >= 0) this[index] = transform(this[index])
    }

    private fun publish(nowMs: Long = stageMonotonicNow()) {
        peers.indices.forEach { peers[it] = advance(peers[it], nowMs) }
        group = group?.let { active -> active.copy(syncTiming = groupSyncTiming(active.members, nowMs)) }
        mutableAdminPeers.value = peers.map { AdminPeer(it.id, it.label, it.online, it.latency) }
        val devices = peers.filter { it.online }.map { peer ->
            val track = peer.queue.getOrNull(peer.index)
            ConnectedDevice(peer.id, peer.label, peer.session, System.currentTimeMillis(), ConnectedPlayerSnapshot(
                track?.id, track?.title ?: "Ready for demo", track?.artist ?: "Stage player", peer.playing,
                peer.positionMs, track?.durationMs ?: 0, revision(peer, peer.trackRevision), revision(peer, peer.queueRevision)))
        }
        mutableState.value = ConnectedPlaybackState(devices = devices)
        val v2Devices = devices.map { device ->
            val track = device.player.trackId?.let { id -> peers.firstOrNull { it.id == device.id }?.queue?.firstOrNull { it.id == id } }
            val fence = EngineFence(device.sessionId, 0, 0, 0)
            ConnectedV2Device(device.id, device.label, device.sessionId, device.updatedAt, true,
                setOf("group", "group-queue", "queue", "sync"), EngineObservation(fence, device.player.trackId,
                    device.player.positionMs, device.player.isPlaying, true, recordingId = device.player.trackId,
                    title = device.player.title, artist = device.player.artist, durationMs = device.player.durationMs))
        }
        val groupNow = group?.let { active ->
            val timeline = active.timeline?.let { current ->
                val duration = current.queue.entries.getOrNull(current.cursor)?.recording?.durationMs ?: 0L
                if (current.playing && duration > 0 && nowMs > current.issuedAtMs)
                    current.copy(positionMs = (current.positionMs + nowMs - current.issuedAtMs).coerceAtMost(duration), issuedAtMs = nowMs)
                else current
            }
            val status = active.members.map { member -> if (member.deviceId == "android-stage-local") member.copy(status = when {
                nowMs < (timeline?.issuedAtMs ?: nowMs) -> GroupMemberV2Status.BUFFERING
                timeline?.playing == true -> GroupMemberV2Status.PLAYING
                else -> GroupMemberV2Status.PAUSED
            }) else {
                val p = peers.firstOrNull { it.id == member.deviceId && it.session == member.sessionId && it.online }
                member.copy(status = when { p == null -> GroupMemberV2Status.DISCONNECTED
                    timeline?.playing == true && nowMs < (timeline.issuedAtMs) -> GroupMemberV2Status.BUFFERING
                    timeline?.playing == true -> GroupMemberV2Status.PLAYING
                    else -> GroupMemberV2Status.PAUSED })
            } }
            active.copy(status = if (nowMs < (timeline?.issuedAtMs ?: nowMs)) GroupV2Status.PREPARING else GroupV2Status.ACTIVE,
                timeline = timeline, members = status)
        }
        mutableV2State.value = ConnectedPlaybackV2State(devices = v2Devices, group = groupNow,
            pending = groupNow?.status == GroupV2Status.PREPARING)
    }
}

internal val androidStageTracks = listOf(
    Track("stage:neon", "Neon Avenue", "Echo Stage", durationMs = 186_000, provider = "stage"),
    Track("stage:blue", "Blue Hour", "Echo Stage", durationMs = 213_000, provider = "stage"),
    Track("stage:orbit", "Orbiting Home", "Echo Stage", durationMs = 198_000, provider = "stage"),
)
