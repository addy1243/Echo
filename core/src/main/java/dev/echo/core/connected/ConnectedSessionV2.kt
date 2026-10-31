package dev.echo.core.connected

import java.util.UUID

data class EngineFence(
    val sessionId: String,
    val queueRevision: Long,
    val occurrenceRevision: Long,
    val transportRevision: Long,
)

data class PortableRecording(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
)

data class QueueOccurrence(val id: String, val recording: PortableRecording)

enum class RepeatModeV2 { OFF, ONE, ALL }
enum class GroupTimelineAction { PLAY, PAUSE, SEEK, NEXT, PREVIOUS, NATURAL_END }

/** Exact finite playback traversal beginning with the current occurrence. */
data class TransferQueue(
    val entries: List<QueueOccurrence>,
    val repeatMode: RepeatModeV2,
    val shuffleEnabled: Boolean,
)

data class EngineCapture(
    val fence: EngineFence,
    val queue: TransferQueue,
    val positionMs: Long,
    val playing: Boolean,
)

data class EngineObservation(
    val fence: EngineFence,
    val currentOccurrenceId: String?,
    val positionMs: Long,
    val playing: Boolean,
    val ready: Boolean,
    val error: String? = null,
    val ended: Boolean = false,
    val recordingId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long = 0,
)

data class GroupRef(val groupId: String, val epoch: Long)

data class GroupTimeline(
    val ref: GroupRef,
    val sequence: Long,
    val queue: TransferQueue,
    val cursor: Int,
    val positionMs: Long,
    val playing: Boolean,
    val ended: Boolean = false,
    val issuedAtMs: Long,
)

/** Adapter over an engine's serialized playback dispatcher. */
interface NativePlaybackBridgeV2 {
    val sessionId: String
    suspend fun fence(): EngineFence
    suspend fun observe(): EngineObservation
    suspend fun capabilities(): Set<String>
    suspend fun capture(): EngineCapture
    suspend fun prepare(operationId: String, queue: TransferQueue, expectedTarget: EngineFence,
        group: GroupRef? = null): EngineObservation
    suspend fun activate(operationId: String, positionMs: Long, play: Boolean): EngineObservation
    suspend fun applyGroupTransport(ref: GroupRef, expectedOccurrenceId: String, positionMs: Long,
        play: Boolean, ended: Boolean): EngineObservation
    suspend fun pauseIfUnchanged(expectedSource: EngineFence): EngineObservation?
    suspend fun abort(operationId: String)
    suspend fun restoreDisplacedPaused(operationId: String): EngineObservation
    suspend fun leaveGroupPaused(groupId: String, epoch: Long): EngineObservation
}

const val MAX_TRANSFER_OCCURRENCES = 500
const val MAX_TRANSFER_DURATION_MS = 604_800_000L
const val MAX_CONNECTED_GROUP_MEMBERS = 8
const val CONNECTED_V2_CAPABILITY_MOVE = "move"
const val CONNECTED_V2_CAPABILITY_GROUP = "group"
private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,128}")
private val YOUTUBE_ID = Regex("youtube:[A-Za-z0-9_-]{11}")
private val SAFE_RECORDING_ID = Regex("[A-Za-z0-9_:-]{1,256}")
private val OCCURRENCE_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")

fun validateEngineFence(fence: EngineFence) {
    require(SESSION_ID.matches(fence.sessionId) && fence.queueRevision >= 0 && fence.occurrenceRevision >= 0 &&
        fence.transportRevision >= 0) { "Invalid playback session fence" }
}

fun validateTransferQueue(queue: TransferQueue) {
    require(queue.entries.isNotEmpty()) { "There is no current song to move" }
    require(queue.entries.size <= MAX_TRANSFER_OCCURRENCES) { "The queue has more than $MAX_TRANSFER_OCCURRENCES entries and cannot be moved" }
    require(queue.entries.map { it.id }.toSet().size == queue.entries.size) { "Queue occurrence IDs must be unique" }
    for (entry in queue.entries) {
        require(OCCURRENCE_ID.matches(entry.id) && runCatching { UUID.fromString(entry.id) }.isSuccess) {
            "Queue occurrence ID is invalid"
        }
        val recording = entry.recording
        require(YOUTUBE_ID.matches(recording.id)) { "This queue contains a recording the other device cannot resolve" }
        require(recording.title.isNotBlank() && recording.title.length <= 128 && recording.title.none(Char::isISOControl)) {
            "Queue title is invalid"
        }
        require(recording.artist.length <= 128 && recording.artist.none(Char::isISOControl)) { "Queue artist is invalid" }
        require(recording.durationMs in 1..MAX_TRANSFER_DURATION_MS) { "Queue duration is invalid" }
    }
}

fun validateEngineCapture(capture: EngineCapture) {
    validateEngineFence(capture.fence)
    if (capture.queue.entries.isEmpty()) {
        require(!capture.playing && capture.positionMs == 0L) { "An empty engine capture must be idle" }
    } else {
        validateTransferQueue(capture.queue)
        require(capture.positionMs in 0..capture.queue.entries.first().recording.durationMs) { "Playback position is outside the current recording" }
    }
}

fun validateGroupTimeline(timeline: GroupTimeline) {
    require(OCCURRENCE_ID.matches(timeline.ref.groupId) && timeline.ref.epoch > 0 && timeline.sequence >= 0) {
        "Invalid group timeline fence"
    }
    validateTransferQueue(timeline.queue)
    require(timeline.cursor in timeline.queue.entries.indices && timeline.positionMs in 0..timeline.queue.entries[timeline.cursor].recording.durationMs &&
        timeline.issuedAtMs > 0 && (!timeline.ended || !timeline.playing)) {
        "Invalid group timeline position"
    }
}

fun validateEngineObservation(observation: EngineObservation) {
    validateEngineFence(observation.fence)
    require(observation.positionMs >= 0 && observation.durationMs in 0..MAX_TRANSFER_DURATION_MS &&
        (observation.durationMs == 0L && observation.positionMs == 0L || observation.positionMs <= observation.durationMs)) {
        "Invalid playback observation position"
    }
    require(observation.currentOccurrenceId == null || OCCURRENCE_ID.matches(observation.currentOccurrenceId)) {
        "Invalid playback observation occurrence"
    }
    require(observation.recordingId == null || SAFE_RECORDING_ID.matches(observation.recordingId)) { "Invalid playback recording identity" }
    require(observation.title == null || observation.title.length <= 128 && observation.title.none(Char::isISOControl)) { "Invalid playback title" }
    require(observation.artist == null || observation.artist.length <= 128 && observation.artist.none(Char::isISOControl)) { "Invalid playback artist" }
    require(observation.error == null || observation.error.length <= 64 && observation.error.matches(Regex("[A-Za-z0-9_-]+"))) {
        "Invalid playback observation error"
    }
}

/** Stage the current group occurrence followed by its remaining shared traversal. */
fun groupStagedTraversal(timeline: GroupTimeline): TransferQueue {
    validateGroupTimeline(timeline)
    val entries = timeline.queue.entries.drop(timeline.cursor)
    return timeline.queue.copy(entries = entries, repeatMode = RepeatModeV2.OFF, shuffleEnabled = false)
}

/** Pure coordinator transition; null means the requested next occurrence does not exist. */
fun advanceGroupTimeline(timeline: GroupTimeline, action: GroupTimelineAction, positionMs: Long, issuedAtMs: Long): GroupTimeline? {
    validateGroupTimeline(timeline)
    val last = timeline.queue.entries.lastIndex
    val nextCursor = when (action) {
        GroupTimelineAction.NEXT -> when {
            timeline.cursor < last -> timeline.cursor + 1
            timeline.queue.repeatMode == RepeatModeV2.ALL -> 0
            else -> last
        }
        GroupTimelineAction.PREVIOUS -> when {
            timeline.cursor > 0 -> timeline.cursor - 1
            timeline.queue.repeatMode == RepeatModeV2.ALL -> last
            else -> 0
        }
        GroupTimelineAction.NATURAL_END -> when {
            timeline.queue.repeatMode == RepeatModeV2.ONE -> timeline.cursor
            timeline.cursor < last -> timeline.cursor + 1
            timeline.queue.repeatMode == RepeatModeV2.ALL -> 0
            else -> last
        }
        else -> timeline.cursor
    }
    val ended = when (action) {
        GroupTimelineAction.PLAY -> false
        GroupTimelineAction.SEEK, GroupTimelineAction.PREVIOUS -> false
        GroupTimelineAction.NEXT -> timeline.cursor == last && timeline.queue.repeatMode != RepeatModeV2.ALL
        GroupTimelineAction.NATURAL_END -> timeline.cursor == last && timeline.queue.repeatMode == RepeatModeV2.OFF
        else -> timeline.ended
    }
    val newPosition = when (action) {
        GroupTimelineAction.SEEK -> {
            require(positionMs in 0..timeline.queue.entries[timeline.cursor].recording.durationMs) { "Seek position is outside the current recording" }
            positionMs
        }
        GroupTimelineAction.PREVIOUS -> 0L
        GroupTimelineAction.NATURAL_END -> if (ended) timeline.queue.entries[timeline.cursor].recording.durationMs else 0L
        GroupTimelineAction.NEXT -> if (ended) timeline.queue.entries[timeline.cursor].recording.durationMs else 0L
        GroupTimelineAction.PLAY -> if (timeline.ended) 0L else timeline.positionMs
        else -> timeline.positionMs
    }
    val playing = when (action) {
        GroupTimelineAction.PLAY -> true
        GroupTimelineAction.PAUSE -> false
        GroupTimelineAction.NATURAL_END -> timeline.queue.repeatMode != RepeatModeV2.OFF || timeline.cursor < last
        GroupTimelineAction.NEXT -> if (ended) false else timeline.playing
        else -> timeline.playing
    }
    return timeline.copy(sequence = timeline.sequence + 1, cursor = nextCursor, positionMs = newPosition,
        playing = playing, ended = ended, issuedAtMs = issuedAtMs)
}
