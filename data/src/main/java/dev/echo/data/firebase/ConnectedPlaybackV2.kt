package dev.echo.data.firebase

import dev.echo.core.connected.EngineCapture
import dev.echo.core.connected.EngineFence
import dev.echo.core.connected.EngineObservation
import dev.echo.core.connected.GroupRef
import dev.echo.core.connected.GroupTimeline
import dev.echo.core.connected.NativePlaybackBridgeV2
import dev.echo.core.connected.PortableRecording
import dev.echo.core.connected.QueueOccurrence
import dev.echo.core.connected.RepeatModeV2
import dev.echo.core.connected.TransferQueue
import dev.echo.core.connected.groupStagedTraversal
import dev.echo.core.connected.validateEngineCapture
import dev.echo.core.connected.validateEngineFence
import dev.echo.core.connected.validateGroupTimeline
import dev.echo.core.connected.validateEngineObservation
import dev.echo.core.connected.validateTransferQueue
import dev.echo.core.connected.MAX_TRANSFER_OCCURRENCES
import dev.echo.core.connected.MAX_CONNECTED_GROUP_MEMBERS
import dev.echo.core.connected.CONNECTED_V2_CAPABILITY_GROUP
import dev.echo.core.connected.CONNECTED_V2_CAPABILITY_MOVE
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

enum class MoveV2Status { PREPARING, PREPARED, ACTIVATING, OBSERVED, PARTIAL, FAILED, COMPLETE, UNKNOWN }
enum class GroupV2Status { PREPARING, ACTIVE, FENCED, CLOSED }
enum class GroupMemberV2Status { INVITED, PREPARING, READY, PLAYING, PAUSED, BUFFERING, DISCONNECTED, LEFT }
enum class GroupActionV2 { PLAY, PAUSE, SEEK, NEXT, PREVIOUS, LEAVE }

data class ConnectedV2Device(
    val id: String,
    val label: String,
    val sessionId: String,
    val updatedAt: Long,
    val enabled: Boolean,
    val capabilities: Set<String>,
    val snapshot: EngineObservation,
)

data class MoveOperationV2(
    val id: String,
    val status: MoveV2Status,
    val sourceDeviceId: String,
    val targetDeviceId: String,
    val reason: String? = null,
)

data class GroupMemberV2(
    val deviceId: String,
    val sessionId: String,
    val epoch: Long,
    val lastAppliedSequence: Long,
    val status: GroupMemberV2Status,
    val observedOccurrenceId: String? = null,
    val observedPositionMs: Long = 0,
    val updatedAt: Long = 0,
)

data class ConnectedGroupV2(
    val ref: GroupRef,
    val coordinatorDeviceId: String,
    val coordinatorSessionId: String,
    val leaseUntil: Long,
    val timelineSequence: Long,
    val status: GroupV2Status,
    val timeline: GroupTimeline? = null,
    val members: List<GroupMemberV2> = emptyList(),
)

data class ConnectedPlaybackV2State(
    val devices: List<ConnectedV2Device> = emptyList(),
    val devicesTruncated: Boolean = false,
    val move: MoveOperationV2? = null,
    val group: ConnectedGroupV2? = null,
    val message: String? = null,
    val pending: Boolean = false,
    val reconcilingMove: Boolean = false,
)

data class RtdbV2Value(val json: JSONObject?, val etag: String?)

internal class ConnectedPlaybackV2Transport(databaseUrl: String, private val client: OkHttpClient) {
    private val root = validateV2DatabaseUrl(databaseUrl)

    suspend fun get(session: FirebaseSession, path: String, etag: Boolean = false,
        query: Map<String, String> = emptyMap()): RtdbV2Value = request(session, path, "GET", null, etag, null, query)

    suspend fun put(session: FirebaseSession, path: String, json: JSONObject,
        ifMatch: String? = null): Boolean = request(session, path, "PUT", json, false, ifMatch).let { true }

    suspend fun compareAndSet(session: FirebaseSession, path: String, json: JSONObject, expectedEtag: String): Boolean =
        try { request(session, path, "PUT", json, false, expectedEtag); true }
        catch (conflict: RtdbV2Conflict) { false }

    suspend fun delete(session: FirebaseSession, path: String) { request(session, path, "DELETE", null, false, null) }

    private suspend fun request(session: FirebaseSession, path: String, method: String, json: JSONObject?, wantEtag: Boolean,
        ifMatch: String?, query: Map<String, String> = emptyMap()): RtdbV2Value {
        validateV2Path(path, session.uid)
        val urlBuilder = root.newBuilder().addPathSegments("$path.json").addQueryParameter("auth", session.idToken)
        query.forEach { (name, value) -> urlBuilder.addQueryParameter(name, value) }
        val url = urlBuilder.build()
        val body = json?.toString()?.toRequestBody(JSON)
        val request = Request.Builder().url(url).method(method, body).apply {
            if (wantEtag) header("X-Firebase-ETag", "true")
            if (ifMatch != null) header("If-Match", ifMatch)
        }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Connected playback v2 request failed"))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try {
                        response.use {
                            if (it.code == 412) throw RtdbV2Conflict()
                            if (!it.isSuccessful) throw FirebaseRequestException(it.code, when (it.code) {
                                401, 403 -> "Realtime Database access was denied. Check Firebase sign-in and published playback v2 rules."
                                429 -> "Realtime Database is rate limiting requests. Try again shortly."
                                else -> "Connected playback v2 request failed (HTTP ${it.code})."
                            })
                            val bytes = it.body?.byteStream()?.use { input -> readV2Bounded(input, MAX_V2_RESPONSE_BYTES) } ?: ByteArray(0)
                            val text = bytes.toString(Charsets.UTF_8)
                            val value = if (text.isBlank() || text == "null") null else JSONObject(text)
                            val result = RtdbV2Value(value, it.header("ETag"))
                            if (continuation.isActive) continuation.resume(result)
                        }
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    private class RtdbV2Conflict : java.io.IOException()
    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_V2_RESPONSE_BYTES = 160 * 1024
    }
}

internal fun validateV2DatabaseUrl(value: String): HttpUrl {
    val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid Realtime Database URL")
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
        url.encodedPath in setOf("", "/") && url.port == 443 && url.host.matches(
            Regex("echo-85da4-default-rtdb(?:\\.[a-z0-9-]+)?\\.firebasedatabase\\.app|echo-85da4-default-rtdb\\.firebaseio\\.com"))) {
        "Realtime Database URL is not an Echo Firebase database"
    }
    return url.newBuilder().encodedPath("/").build()
}

private fun validateV2Path(path: String, uid: String) {
    require(path.startsWith("users/$uid/playbackV2/") && path.length <= 512 &&
        path.split('/').all { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }) { "Invalid playback v2 database path" }
}

private fun readV2Bounded(input: java.io.InputStream, maximum: Int): ByteArray {
    val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        require(output.size() + count <= maximum) { "Realtime Database playback v2 response is too large" }
        output.write(buffer, 0, count)
    }
}

private fun defaultConnectedPlaybackV2HttpClient() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()

private const val MAX_V2_QUEUE_BYTES = 128 * 1024
private val V2_DEVICE_ID = Regex("[A-Za-z0-9_-]{1,128}")
private val V2_OPERATION_ID = Regex("[0-9a-fA-F-]{36}")
private val V2_GROUP_ID = Regex("[0-9a-fA-F-]{36}")
private const val V2_DEVICE_LEASE_MS = 15_000L
private const val V2_CLOCK_SKEW_TOLERANCE_MS = 5_000L
private const val V2_GROUP_LEASE_MS = 15_000L
private const val V2_REQUEST_TTL_MS = 20_000L
private const val V2_MOVE_OPERATION_TTL_MS = 60_000L
private const val V2_RECONCILE_MS = 120_000L
private const val V2_GROUP_PREPARE_TIMEOUT_MS = 60_000L
private const val V2_POLL_MS = 1_000L
private const val MAX_V2_DEVICES = 32
private const val MAX_V2_INBOX = 32
private const val V2_OBSERVATION_TOLERANCE_MS = 2_000L

private fun isRecentServerTimestamp(timestamp: Long, now: Long) =
    now - timestamp in -V2_CLOCK_SKEW_TOLERANCE_MS..V2_DEVICE_LEASE_MS

internal fun estimateServerOffsetMs(serverTimestamp: Long, localRequestStartedAt: Long, localResponseReceivedAt: Long): Long {
    require(serverTimestamp > 0 && localRequestStartedAt > 0 && localResponseReceivedAt >= localRequestStartedAt)
    val midpoint = localRequestStartedAt + (localResponseReceivedAt - localRequestStartedAt) / 2
    return (serverTimestamp - midpoint).coerceIn(-5 * 60_000L, 5 * 60_000L)
}

internal fun engineFenceJson(value: EngineFence) = JSONObject().put("sessionId", value.sessionId)
    .put("queueRevision", value.queueRevision).put("occurrenceRevision", value.occurrenceRevision)
    .put("transportRevision", value.transportRevision)

internal fun parseEngineFence(value: JSONObject): EngineFence {
    requireV2Keys(value, setOf("sessionId", "queueRevision", "occurrenceRevision", "transportRevision"))
    return EngineFence(value.getString("sessionId"), value.getLong("queueRevision"), value.getLong("occurrenceRevision"),
        value.getLong("transportRevision")).also(::validateEngineFence)
}

internal fun transferQueueJson(value: TransferQueue): JSONObject {
    validateTransferQueue(value)
    val json = JSONObject().put("entries", JSONArray().also { entries -> value.entries.forEach { entry ->
        entries.put(JSONObject().put("id", entry.id).put("recording", JSONObject().put("id", entry.recording.id)
            .put("title", entry.recording.title).put("artist", entry.recording.artist).put("durationMs", entry.recording.durationMs)))
    } }).put("repeatMode", value.repeatMode.name).put("shuffleEnabled", value.shuffleEnabled)
    require(json.toString().toByteArray(Charsets.UTF_8).size <= MAX_V2_QUEUE_BYTES) { "Queue is too large to move between devices" }
    return json
}

internal fun parseTransferQueue(value: JSONObject): TransferQueue {
    requireV2Keys(value, setOf("entries", "repeatMode", "shuffleEnabled"))
    val rows = value.getJSONArray("entries")
    require(rows.length() <= MAX_TRANSFER_OCCURRENCES) { "Queue exceeds the transfer entry limit" }
    val queue = TransferQueue((0 until rows.length()).map { index ->
        val entry = rows.getJSONObject(index); requireV2Keys(entry, setOf("id", "recording"))
        val recording = entry.getJSONObject("recording"); requireV2Keys(recording, setOf("id", "title", "artist", "durationMs"))
        QueueOccurrence(entry.getString("id"), PortableRecording(recording.getString("id"), recording.getString("title"),
            recording.getString("artist"), recording.getLong("durationMs")))
    }, RepeatModeV2.valueOf(value.getString("repeatMode")), value.getBoolean("shuffleEnabled"))
    validateTransferQueue(queue)
    require(value.toString().toByteArray(Charsets.UTF_8).size <= MAX_V2_QUEUE_BYTES) { "Queue is too large to move between devices" }
    return queue
}

internal fun observationJson(value: EngineObservation) = JSONObject().put("fence", engineFenceJson(value.fence))
    .put("currentOccurrenceId", value.currentOccurrenceId ?: "").put("positionMs", value.positionMs)
    .put("playing", value.playing).put("ready", value.ready).put("error", value.error ?: "")
    .put("ended", value.ended).put("recordingId", value.recordingId ?: "")
    .put("title", value.title ?: "").put("artist", value.artist ?: "").put("durationMs", value.durationMs)

internal fun parseObservation(value: JSONObject): EngineObservation {
    requireV2Keys(value, setOf("fence", "currentOccurrenceId", "positionMs", "playing", "ready", "error", "ended",
        "recordingId", "title", "artist", "durationMs"))
    fun optional(name: String) = value.optString(name).takeUnless { it.isBlank() || it == "null" }
    return EngineObservation(parseEngineFence(value.getJSONObject("fence")), optional("currentOccurrenceId"), value.getLong("positionMs"),
        value.getBoolean("playing"), value.getBoolean("ready"), optional("error"), value.getBoolean("ended"), optional("recordingId"),
        optional("title"), optional("artist"), value.getLong("durationMs")).also(::validateEngineObservation)
}

private fun requireV2Keys(value: JSONObject, keys: Set<String>) {
    val actual = value.keys().asSequence().toSet()
    require(actual == keys) { "Connected playback v2 data has an invalid field set" }
}

private data class MoveRequestV2(
    val id: String, val sourceDeviceId: String, val sourceSessionId: String,
    val targetDeviceId: String, val targetSessionId: String,
    val sourceFence: EngineFence, val targetFence: EngineFence, val queue: TransferQueue,
    val positionMs: Long, val playing: Boolean, val createdAt: Long, val expiresAt: Long,
)

private fun moveRequestJson(request: MoveRequestV2): JSONObject = JSONObject().put("schema", 2).put("id", request.id)
    .put("type", "MOVE").put("sourceDeviceId", request.sourceDeviceId).put("sourceSessionId", request.sourceSessionId)
    .put("targetDeviceId", request.targetDeviceId).put("targetSessionId", request.targetSessionId)
    .put("sourceFence", engineFenceJson(request.sourceFence)).put("targetFence", engineFenceJson(request.targetFence))
    .put("queue", transferQueueJson(request.queue)).put("positionMs", request.positionMs).put("playing", request.playing)
    .put("createdAt", request.createdAt).put("expiresAt", request.expiresAt).also {
        require(it.toString().toByteArray(Charsets.UTF_8).size <= MAX_V2_QUEUE_BYTES) { "Playback transfer request exceeds 128 KiB" }
    }

private fun parseMoveRequest(value: JSONObject): MoveRequestV2 {
    requireV2Keys(value, setOf("schema", "id", "type", "sourceDeviceId", "sourceSessionId", "targetDeviceId", "targetSessionId",
        "sourceFence", "targetFence", "queue", "positionMs", "playing", "createdAt", "expiresAt"))
    val request = MoveRequestV2(value.getString("id"), value.getString("sourceDeviceId"), value.getString("sourceSessionId"),
        value.getString("targetDeviceId"), value.getString("targetSessionId"), parseEngineFence(value.getJSONObject("sourceFence")),
        parseEngineFence(value.getJSONObject("targetFence")), parseTransferQueue(value.getJSONObject("queue")),
        value.getLong("positionMs"), value.getBoolean("playing"), value.getLong("createdAt"), value.getLong("expiresAt"))
    require(value.getInt("schema") == 2 && value.getString("type") == "MOVE" && V2_OPERATION_ID.matches(request.id) &&
        runCatching { UUID.fromString(request.id) }.isSuccess && V2_DEVICE_ID.matches(request.sourceDeviceId) &&
        V2_DEVICE_ID.matches(request.targetDeviceId) && request.sourceDeviceId != request.targetDeviceId &&
        request.sourceSessionId == request.sourceFence.sessionId && request.targetSessionId == request.targetFence.sessionId &&
        request.positionMs in 0..request.queue.entries.first().recording.durationMs && request.createdAt > 0 &&
        request.expiresAt - request.createdAt in 1..V2_MOVE_OPERATION_TTL_MS) { "Invalid playback transfer request" }
    require(value.toString().toByteArray(Charsets.UTF_8).size <= MAX_V2_QUEUE_BYTES) { "Playback transfer request exceeds 128 KiB" }
    return request
}

private fun moveDecisionJson(request: MoveRequestV2, positionMs: Long, playing: Boolean, now: Long) = JSONObject()
    .put("action", "ACTIVATE").put("sourceFence", engineFenceJson(request.sourceFence))
    .put("targetFence", engineFenceJson(request.targetFence)).put("positionMs", positionMs).put("playing", playing)
    .put("issuedAt", now).put("expiresAt", now + V2_REQUEST_TTL_MS)

private data class MoveIntentV2(val positionMs: Long, val playing: Boolean, val issuedAt: Long, val expiresAt: Long)

private fun parseMoveIntent(value: JSONObject, request: MoveRequestV2): MoveIntentV2? = runCatching {
    requireV2Keys(value, setOf("action", "sourceFence", "targetFence", "positionMs", "playing", "issuedAt", "expiresAt"))
    require(value.getString("action") == "ACTIVATE" && parseEngineFence(value.getJSONObject("sourceFence")) == request.sourceFence &&
        parseEngineFence(value.getJSONObject("targetFence")) == request.targetFence)
    val position = value.getLong("positionMs")
    require(position in 0..request.queue.entries.first().recording.durationMs)
    val issuedAt = value.getLong("issuedAt")
    val expiresAt = value.getLong("expiresAt")
    require(issuedAt > 0 && expiresAt - issuedAt in 1..V2_REQUEST_TTL_MS)
    MoveIntentV2(position, value.getBoolean("playing"), issuedAt, expiresAt)
}.getOrNull()

private fun parseMoveDecision(value: JSONObject, request: MoveRequestV2, now: Long): MoveIntentV2? =
    parseMoveIntent(value, request)?.takeIf { it.issuedAt <= now + 30_000L && it.expiresAt >= now }

private fun targetAckJson(status: String, sessionId: String, observation: EngineObservation? = null, reason: String? = null) =
    JSONObject().put("status", status).put("sessionId", sessionId).put("observation", observation?.let(::observationJson) ?: "")
        .put("reason", reason ?: "").put("updatedAt", serverTimestamp())

private fun sourceAckJson(status: String, observation: EngineObservation? = null, reason: String? = null) =
    JSONObject().put("status", status).put("observation", observation?.let(::observationJson) ?: "")
        .put("reason", reason ?: "").put("updatedAt", serverTimestamp())

private fun resultJson(status: String, reason: String? = null) =
    JSONObject().put("status", status).put("reason", reason ?: "").put("updatedAt", serverTimestamp())

private data class GroupHeadV2(
    val groupId: String, val epoch: Long, val coordinatorDeviceId: String, val coordinatorSessionId: String,
    val leaseUntil: Long, val timelineSequence: Long, val timelineDigest: String, val status: GroupV2Status,
)

private fun parseGroupHead(value: JSONObject): GroupHeadV2 {
    requireV2Keys(value, setOf("schema", "id", "epoch", "coordinatorDeviceId", "coordinatorSessionId", "leaseUntil",
        "timelineSeq", "timelineDigest", "status", "updatedAt"))
    val head = GroupHeadV2(value.getString("id"), value.getLong("epoch"), value.getString("coordinatorDeviceId"),
        value.getString("coordinatorSessionId"), value.getLong("leaseUntil"), value.getLong("timelineSeq"),
        value.getString("timelineDigest"), GroupV2Status.valueOf(value.getString("status")))
    require(value.getInt("schema") == 2 && V2_GROUP_ID.matches(head.groupId) && head.epoch > 0 &&
        runCatching { UUID.fromString(head.groupId) }.isSuccess && V2_DEVICE_ID.matches(head.coordinatorDeviceId) &&
        V2_DEVICE_ID.matches(head.coordinatorSessionId) && head.timelineSequence >= 0 && head.leaseUntil >= 0 &&
        head.timelineDigest.matches(Regex("[a-f0-9]{64}"))) { "Invalid playback group head" }
    return head
}

private fun parseGroupMember(deviceId: String, value: JSONObject): GroupMemberV2 {
    require(V2_DEVICE_ID.matches(deviceId))
    requireV2Keys(value, setOf("sessionId", "epoch", "lastAppliedSeq", "status", "observedOccurrenceId", "observedPositionMs", "updatedAt"))
    val occurrence = value.optString("observedOccurrenceId").takeUnless { it.isBlank() || it == "null" }
    val member = GroupMemberV2(deviceId, value.getString("sessionId"), value.getLong("epoch"), value.getLong("lastAppliedSeq"),
        GroupMemberV2Status.valueOf(value.getString("status")), occurrence, value.getLong("observedPositionMs"), value.getLong("updatedAt"))
    require(V2_DEVICE_ID.matches(member.sessionId) && member.epoch > 0 && member.lastAppliedSequence >= -1 &&
        (member.observedOccurrenceId == null || member.observedOccurrenceId.matches(Regex("[0-9a-fA-F-]{36}"))) &&
        member.observedPositionMs >= 0 && member.updatedAt > 0) { "Invalid playback group member" }
    return member
}

internal fun parseGroupMembers(value: JSONObject): List<GroupMemberV2> {
    require(value.length() in 2..MAX_CONNECTED_GROUP_MEMBERS) { "Play together groups must have 2 to $MAX_CONNECTED_GROUP_MEMBERS members" }
    val members = value.keys().asSequence().map { id -> parseGroupMember(id, value.getJSONObject(id)) }.toList()
    require(members.map { it.deviceId }.toSet().size == members.size) { "Play together member IDs must be unique" }
    require(members.map { it.sessionId }.toSet().size == members.size) { "Play together player sessions must be unique" }
    return members
}

internal fun groupMembershipMatches(expected: Set<Pair<String, String>>, members: List<GroupMemberV2>): Boolean =
    expected.size in 2..MAX_CONNECTED_GROUP_MEMBERS && expected.map { it.second }.toSet().size == expected.size &&
        members.size == expected.size &&
        members.map { it.deviceId to it.sessionId }.toSet() == expected

internal fun freshGroupMembers(members: List<GroupMemberV2>, epoch: Long, now: Long): List<GroupMemberV2> =
    members.filter { it.epoch == epoch && it.status !in setOf(GroupMemberV2Status.LEFT, GroupMemberV2Status.DISCONNECTED) &&
        now - it.updatedAt <= V2_DEVICE_LEASE_MS }

private data class GroupRequestRecord(val id: String, val sourceDeviceId: String, val sourceSessionId: String,
    val expectedEpoch: Long, val expectedSeq: Long, val action: GroupActionV2, val positionMs: Long,
    val createdAt: Long, val expiresAt: Long)

private fun parseGroupRequest(id: String, value: JSONObject, now: Long): GroupRequestRecord {
    require(V2_OPERATION_ID.matches(id))
    requireV2Keys(value, setOf("id", "sourceDeviceId", "sourceSessionId", "expectedEpoch", "expectedSeq", "action", "positionMs", "createdAt", "expiresAt"))
    val request = GroupRequestRecord(value.getString("id"), value.getString("sourceDeviceId"), value.getString("sourceSessionId"),
        value.getLong("expectedEpoch"), value.getLong("expectedSeq"), GroupActionV2.valueOf(value.getString("action")),
        value.getLong("positionMs"), value.getLong("createdAt"), value.getLong("expiresAt"))
    require(request.id == id && V2_DEVICE_ID.matches(request.sourceDeviceId) && V2_DEVICE_ID.matches(request.sourceSessionId) &&
        request.expectedEpoch > 0 && request.expectedSeq >= 0 && request.positionMs >= 0 && request.createdAt <= now + 30_000L &&
        request.expiresAt >= now && request.expiresAt - request.createdAt in 1..V2_REQUEST_TTL_MS) { "Invalid playback group request" }
    return request
}

internal fun inboxMessage(type: String, refId: String, serverNowMs: Long) = JSONObject().put("type", type).put("refId", refId)
    .put("createdAt", serverNowMs).put("expiresAt", serverNowMs + V2_RECONCILE_MS)

internal data class InboxMessageV2(val type: String, val refId: String, val createdAt: Long, val expiresAt: Long)
internal fun parseInboxMessage(id: String, value: JSONObject): InboxMessageV2 {
    require(V2_OPERATION_ID.matches(id)); requireV2Keys(value, setOf("type", "refId", "createdAt", "expiresAt"))
    val message = InboxMessageV2(value.getString("type"), value.getString("refId"), value.getLong("createdAt"), value.getLong("expiresAt"))
    require(message.type in setOf("MOVE", "GROUP") && V2_OPERATION_ID.matches(message.refId) &&
        message.createdAt > 0 && message.expiresAt > message.createdAt && message.expiresAt - message.createdAt <= V2_RECONCILE_MS) {
        "Invalid playback v2 inbox message"
    }
    return message
}

internal fun connectedV2DeviceJson(id: String, label: String, snapshot: EngineObservation, capabilities: Set<String>, enabled: Boolean = true) =
    JSONObject().put("sessionId", snapshot.fence.sessionId).put("label", label.take(48))
        .put("updatedAt", serverTimestamp()).put("enabled", enabled)
        .put("capabilities", JSONArray(capabilities.sorted())).put("snapshot", observationJson(snapshot))
        .also { require(V2_DEVICE_ID.matches(id) && capabilities.all { cap -> cap in V2_CAPABILITIES }) }

internal fun parseConnectedV2Device(id: String, value: JSONObject): ConnectedV2Device {
    require(V2_DEVICE_ID.matches(id)); requireV2Keys(value, setOf("sessionId", "label", "updatedAt", "enabled", "capabilities", "snapshot"))
    val caps = value.getJSONArray("capabilities")
    require(caps.length() <= V2_CAPABILITIES.size)
    val capabilities = (0 until caps.length()).map { caps.getString(it) }.toSet()
    require(capabilities.size == caps.length() && capabilities.all { it in V2_CAPABILITIES })
    val snapshot = parseObservation(value.getJSONObject("snapshot"))
    require(value.getString("sessionId") == snapshot.fence.sessionId)
    val label = value.getString("label")
    require(label.isNotBlank() && label.length <= 48 && label.none(Char::isISOControl))
    return ConnectedV2Device(id, label, snapshot.fence.sessionId, value.getLong("updatedAt"), value.getBoolean("enabled"), capabilities, snapshot)
}

internal fun groupTimelineJson(value: GroupTimeline, includeDigest: Boolean = true): JSONObject {
    validateGroupTimeline(value)
    val json = JSONObject().put("epoch", value.ref.epoch).put("sequence", value.sequence)
        .put("queue", transferQueueJson(value.queue)).put("cursor", value.cursor).put("positionMs", value.positionMs)
        .put("playing", value.playing).put("ended", value.ended).put("issuedAtMs", value.issuedAtMs)
    if (includeDigest) json.put("digest", jsonDigest(json))
    require(json.toString().toByteArray(Charsets.UTF_8).size <= MAX_V2_QUEUE_BYTES) { "Shared queue exceeds the 128 KiB playback limit" }
    return json
}

internal fun parseGroupTimeline(groupId: String, value: JSONObject): GroupTimeline {
    requireV2Keys(value, setOf("epoch", "sequence", "queue", "cursor", "positionMs", "playing", "ended", "issuedAtMs", "digest"))
    require(value.getString("digest") == jsonDigest(JSONObject(value.toString()).also { it.remove("digest") }))
    val timeline = GroupTimeline(GroupRef(groupId, value.getLong("epoch")), value.getLong("sequence"),
        parseTransferQueue(value.getJSONObject("queue")), value.getInt("cursor"), value.getLong("positionMs"),
        value.getBoolean("playing"), value.getBoolean("ended"), value.getLong("issuedAtMs"))
    validateGroupTimeline(timeline)
    require(value.toString().toByteArray(Charsets.UTF_8).size <= MAX_V2_QUEUE_BYTES)
    return timeline
}

/** Estimate the current song position from a timeline heartbeat without restarting an expired song. */
internal fun groupActivationPosition(timeline: GroupTimeline, now: Long): Long? {
    if (!timeline.playing || timeline.ended) return timeline.positionMs
    val duration = timeline.queue.entries[timeline.cursor].recording.durationMs
    val elapsed = (now - timeline.issuedAtMs).coerceAtLeast(0)
    val position = (timeline.positionMs + elapsed).coerceAtMost(duration)
    return position.takeIf { it < duration }
}

internal fun groupHeadJson(groupId: String, epoch: Long, coordinatorDeviceId: String, coordinatorSessionId: String,
    leaseUntil: Long, timeline: GroupTimeline, status: GroupV2Status) = JSONObject()
    .put("schema", 2).put("id", groupId).put("epoch", epoch).put("coordinatorDeviceId", coordinatorDeviceId)
    .put("coordinatorSessionId", coordinatorSessionId).put("leaseUntil", leaseUntil).put("timelineSeq", timeline.sequence)
    .put("timelineDigest", groupTimelineJson(timeline).getString("digest")).put("status", status.name).put("updatedAt", serverTimestamp())

internal fun groupMemberJson(member: GroupMemberV2) = JSONObject().put("sessionId", member.sessionId).put("epoch", member.epoch)
    .put("lastAppliedSeq", member.lastAppliedSequence).put("status", member.status.name)
    .put("observedOccurrenceId", member.observedOccurrenceId ?: "")
    .put("observedPositionMs", member.observedPositionMs).put("updatedAt", serverTimestamp())

internal fun groupRequestJson(id: String, sourceDeviceId: String, sourceSessionId: String, expectedEpoch: Long,
    expectedSeq: Long, action: GroupActionV2, positionMs: Long, createdAt: Long, expiresAt: Long) = JSONObject()
    .put("id", id).put("sourceDeviceId", sourceDeviceId).put("sourceSessionId", sourceSessionId)
    .put("expectedEpoch", expectedEpoch).put("expectedSeq", expectedSeq).put("action", action.name)
    .put("positionMs", positionMs).put("createdAt", createdAt).put("expiresAt", expiresAt)

internal fun jsonDigest(value: JSONObject): String = MessageDigest.getInstance("SHA-256")
    .digest(canonicalJson(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun canonicalJson(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().sorted().joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
        "${quoteCanonicalJsonString(key)}:${canonicalJson(value.get(key))}"
    }
    is JSONArray -> (0 until value.length()).joinToString(separator = ",", prefix = "[", postfix = "]") { index -> canonicalJson(value.get(index)) }
    null, JSONObject.NULL -> "null"
    is String -> quoteCanonicalJsonString(value)
    is Number -> value.toString()
    is Boolean -> value.toString()
    else -> error("Unsupported playback v2 JSON value")
}

/** RFC 8259 string escaping kept independent of Android/JVM org.json implementations. */
private fun quoteCanonicalJsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            in '\u0000'..'\u001F' -> append("\\u%04x".format(char.code))
            else -> append(char)
        }
    }
    append('"')
}

private fun serverTimestamp() = JSONObject().put(".sv", "timestamp")
private val V2_CAPABILITIES = setOf(CONNECTED_V2_CAPABILITY_MOVE, CONNECTED_V2_CAPABILITY_GROUP)

/** Owner-account v2 orchestration; payloads never include local paths, URLs, streams, or credentials. */
class ConnectedPlaybackV2Client(
    databaseUrl: String,
    val deviceId: String,
    label: String,
    private val sessionProvider: suspend () -> FirebaseSession,
    private val bridge: NativePlaybackBridgeV2,
    httpClient: OkHttpClient = defaultConnectedPlaybackV2HttpClient(),
    private val groupPrepareTimeoutMs: Long = V2_GROUP_PREPARE_TIMEOUT_MS,
    private val moveWaitTimeoutMs: Long = V2_MOVE_OPERATION_TTL_MS + V2_REQUEST_TTL_MS,
    private val groupRequestTtlMs: Long = V2_REQUEST_TTL_MS,
    private val testDiagnosticLogger: ((String) -> Unit)? = null,
) {
    private val transport = ConnectedPlaybackV2Transport(databaseUrl, httpClient)
    private val safeLabel = label.take(48).filterNot(Char::isISOControl).ifBlank { "Echo player" }
    private val stateFlow = MutableStateFlow(ConnectedPlaybackV2State())
    val state: StateFlow<ConnectedPlaybackV2State> = stateFlow.asStateFlow()
    private val mutation = Mutex()
    @Volatile private var closed = false
    private var running = false
    private var uid: String? = null
    private var lastPublished: EngineObservation? = null
    private var lastHeartbeat = 0L
    private var lastGroupHeartbeat = 0L
    @Volatile private var groupRef: GroupRef? = null
    @Volatile private var abortRequestedGroupRef: GroupRef? = null
    private var groupSourceCapture: EngineCapture? = null
    @Volatile private var localGroup: ConnectedGroupV2? = null
    private var expectedGroupMembers: Set<Pair<String, String>>? = null
    @Volatile private var serverOffsetMs = 0L
    private var lastV2Devices = emptyList<ConnectedV2Device>()
    private var devicesTruncated = false
    private var lastError: String? = null
    private var pendingGroupSequence: Long? = null
    private var pendingGroupRequestId: String? = null
    private var pendingGroupRequestExpiresAt = 0L
    @Volatile private var unresolvedMove: MoveRequestV2? = null
    private var lastLateMoveCheckAt = 0L

    private fun diagnose(ref: GroupRef, event: String, detail: String = "") {
        val logger = testDiagnosticLogger ?: return
        runCatching {
            logger("V2_PHASE at=${System.currentTimeMillis()} device=$deviceId group=${ref.groupId} epoch=${ref.epoch} event=$event $detail")
        }
    }

    init {
        require(V2_DEVICE_ID.matches(deviceId)) { "Invalid connected playback device ID" }
        require(groupPrepareTimeoutMs in 1_000L..120_000L) { "Invalid group preparation timeout" }
        require(moveWaitTimeoutMs in 1_000L..120_000L) { "Invalid transfer wait timeout" }
        require(groupRequestTtlMs in 1_000L..30_000L) { "Invalid group request timeout" }
    }

    suspend fun run() {
        check(!closed && !running) { "Connected playback v2 client is already stopped or running" }
        val initial = sessionProvider(); uid = initial.uid; running = true
        try {
            while (!closed) {
                try {
                    if (mutation.tryLock()) {
                        try { if (unresolvedMove == null) enforceLocalGroupLease() }
                        finally { mutation.unlock() }
                    }
                    val session = sessionProvider()
                    check(session.uid == initial.uid) { "Account changed; reconnect connected playback" }
                    val observed = bridge.observe().also(::validateEngineObservation)
                    publishDevice(session, observed)
                    val devicesValue = transport.get(session, "users/${session.uid}/playbackV2/devices", query = mapOf(
                        "orderBy" to "\"updatedAt\"", "limitToLast" to (MAX_V2_DEVICES + 1).toString()))
                    val devices = readV2Devices(devicesValue.json, System.currentTimeMillis() + serverOffsetMs)
                    devicesTruncated = (devicesValue.json?.length() ?: 0) > MAX_V2_DEVICES
                    lastV2Devices = devices.takeLast(MAX_V2_DEVICES)
                    val inbox = transport.get(session, "users/${session.uid}/playbackV2/inbox/$deviceId", query = mapOf(
                        "orderBy" to "\"createdAt\"", "limitToLast" to MAX_V2_INBOX.toString())).json
                    if (mutation.isLocked) {
                        val preparingRef = groupRef?.takeIf { ref -> localGroup?.ref == ref &&
                            localGroup?.status == GroupV2Status.PREPARING && localGroup?.coordinatorDeviceId == deviceId &&
                            localGroup?.coordinatorSessionId == bridge.sessionId }
                        if (preparingRef != null && unresolvedMove == null) renewPreparingGroupLease(session, preparingRef)
                    }
                    // Defer inbound and group engine work instead of replacing a stage held by a public mutation.
                    if (mutation.tryLock()) {
                        try {
                            if (unresolvedMove == null) {
                                processInbox(session, inbox)
                                processLocalGroup(session)
                                refreshPendingGroupRequest(session)
                            } else if (stateFlow.value.move?.status == MoveV2Status.UNKNOWN) {
                                monitorLateMove(session)
                            }
                        } finally { mutation.unlock() }
                    }
                    val groupPending = localGroup?.let { group -> group.status == GroupV2Status.PREPARING ||
                        group.members.any { it.status in setOf(GroupMemberV2Status.INVITED, GroupMemberV2Status.PREPARING, GroupMemberV2Status.READY, GroupMemberV2Status.BUFFERING) } } == true
                    stateFlow.value = stateFlow.value.copy(devices = lastV2Devices, devicesTruncated = devicesTruncated,
                        group = localGroup, message = lastError ?: stateFlow.value.message, pending = stateFlow.value.move?.status in setOf(
                            MoveV2Status.PREPARING, MoveV2Status.PREPARED, MoveV2Status.ACTIVATING) || groupPending || pendingGroupSequence != null,
                        reconcilingMove = unresolvedMove != null)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    lastError = if (error is FirebaseRequestException) error.message else error.message?.take(200) ?: "Connected playback v2 is unavailable."
                    stateFlow.value = stateFlow.value.copy(devices = lastV2Devices.map { it.copy(enabled = false) },
                        group = localGroup, message = lastError)
                }
                if (!closed) delay(V2_POLL_MS)
            }
        } finally {
            runCatching { withContext(NonCancellable) {
                val session = sessionProvider(); if (session.uid == initial.uid) {
                    val observed = bridge.observe().also(::validateEngineObservation)
                    transport.put(session, "users/${session.uid}/playbackV2/devices/$deviceId",
                        connectedV2DeviceJson(deviceId, safeLabel, observed, bridge.capabilities(), enabled = false))
                }
            } }
            uid = null; running = false; stateFlow.value = ConnectedPlaybackV2State(message = "Connected playback v2 stopped.")
        }
    }

    fun close() { closed = true; stateFlow.value = stateFlow.value.copy(message = "Connected playback v2 stopped.") }

    suspend fun startTogether(targetDeviceIds: List<String>, replacePlaying: Boolean = false,
        expectedTargetSessions: Map<String, String>? = null) {
        val (session, ref, deadline) = mutation.withLock {
            check(running && !closed && uid != null) { "Connected playback v2 is not running" }
            require(groupRef == null && localGroup == null) { "Leave the current Play together session first" }
            require(unresolvedMove == null) { "Finish reconciling the playback transfer before starting Play together" }
            require(targetDeviceIds.size in 1 until MAX_CONNECTED_GROUP_MEMBERS &&
                targetDeviceIds.distinct().size == targetDeviceIds.size && deviceId !in targetDeviceIds) {
                "Play together supports this player and up to ${MAX_CONNECTED_GROUP_MEMBERS - 1} selected players"
            }
            require(expectedTargetSessions == null || expectedTargetSessions.keys == targetDeviceIds.toSet()) {
                "The selected player sessions changed; select the players again"
            }
            val currentSession = sessionProvider(); check(currentSession.uid == uid) { "Account changed; reconnect connected playback" }
            val capture = bridge.capture().also(::validateEngineCapture)
            require(capture.queue.entries.isNotEmpty()) { "Play a portable song before starting Play together" }
            val now = System.currentTimeMillis() + serverOffsetMs
            val targets = targetDeviceIds.map { id ->
                lastV2Devices.firstOrNull { it.id == id } ?: error("A selected player has no current v2 session")
            }
            require((targets.map { it.sessionId } + bridge.sessionId).toSet().size == targets.size + 1) {
                "Selected players must have distinct current sessions"
            }
            require(expectedTargetSessions == null || targets.all { expectedTargetSessions[it.id] == it.sessionId }) {
                "A selected player's session changed; select the players again"
            }
            require(targets.all { it.enabled && "group" in it.capabilities && isRecentServerTimestamp(it.updatedAt, now) }) {
                "A selected player is offline or cannot join a group"
            }
            require(replacePlaying || targets.none { it.snapshot.playing }) {
                "A selected player is playing. Confirm replacing its playback first."
            }
            val base = "users/${currentSession.uid}/playbackV2/groups"
            val newRef = GroupRef(UUID.randomUUID().toString(), 1)
            val staging = GroupTimeline(newRef, 0, capture.queue, 0, capture.positionMs, false, false, now)
            val digest = groupTimelineJson(staging).getString("digest")
            val path = "$base/${newRef.groupId}"
            check(transport.compareAndSet(currentSession, "$path/timelines/0", groupTimelineJson(staging), "null_etag")) {
                "Group ID collision"
            }
            val head = GroupHeadV2(newRef.groupId, newRef.epoch, deviceId, bridge.sessionId,
                now + V2_GROUP_LEASE_MS, 0, digest, GroupV2Status.PREPARING)
            check(transport.compareAndSet(currentSession, "$path/head", groupHeadJson(head.groupId, head.epoch,
                head.coordinatorDeviceId, head.coordinatorSessionId, head.leaseUntil, staging, head.status), "null_etag")) {
                "Group ID collision"
            }
            val members = listOf(deviceId to bridge.sessionId) + targets.map { it.id to it.sessionId }
            for ((memberId, memberSession) in members) {
                transport.put(currentSession, "$path/members/$memberId", groupMemberJson(
                    GroupMemberV2(memberId, memberSession, newRef.epoch, -1, GroupMemberV2Status.INVITED)))
            }
            groupRef = newRef; abortRequestedGroupRef = null; groupSourceCapture = capture
            lastError = null
            expectedGroupMembers = members.toSet()
            localGroup = ConnectedGroupV2(newRef, deviceId, bridge.sessionId, head.leaseUntil, 0,
                GroupV2Status.PREPARING, staging, members.map { (memberId, memberSession) ->
                    GroupMemberV2(memberId, memberSession, newRef.epoch, -1, GroupMemberV2Status.INVITED)
                })
            val coordinatorMemberPath = "$path/members/$deviceId"
            try {
                transport.put(currentSession, coordinatorMemberPath, groupMemberJson(
                    GroupMemberV2(deviceId, bridge.sessionId, newRef.epoch, -1, GroupMemberV2Status.PREPARING)))
                for ((memberId, _) in members.filter { it.first != deviceId }) {
                    val messageId = UUID.randomUUID().toString()
                    transport.put(currentSession, "users/${currentSession.uid}/playbackV2/inbox/$memberId/$messageId",
                        inboxMessage("GROUP", newRef.groupId, now))
                }
                stateFlow.value = stateFlow.value.copy(group = localGroup, pending = true,
                    message = "Preparing all selected players for Play together.")
                val prepared = prepareCoordinatorGroup(currentSession, newRef) {
                    val startedAt = System.currentTimeMillis()
                    diagnose(newRef, "coordinator_prepare_start")
                    try {
                        bridge.prepare(newRef.groupId, groupStagedTraversal(staging), bridge.fence(), newRef).also {
                            diagnose(newRef, "coordinator_prepare_ready", "elapsedMs=${System.currentTimeMillis() - startedAt} ready=${it.ready}")
                        }
                    } catch (error: Exception) {
                        diagnose(newRef, "coordinator_prepare_failed", "elapsedMs=${System.currentTimeMillis() - startedAt} error=${error.javaClass.simpleName}")
                        throw error
                    }
                }.also(::validateEngineObservation)
                require(prepared.ready && prepared.fence.sessionId == bridge.sessionId) {
                    "Coordinator could not stage group playback"
                }
                transport.put(currentSession, coordinatorMemberPath, groupMemberJson(GroupMemberV2(deviceId,
                    bridge.sessionId, newRef.epoch, 0, GroupMemberV2Status.READY,
                    staging.queue.entries[staging.cursor].id, staging.positionMs)))
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    runCatching { abortPreparingGroup(currentSession, path, newRef,
                        "Play together preparation was cancelled") }
                }
                throw cancelled
            } catch (error: Exception) {
                abortPreparingGroup(currentSession, path, newRef, "The current player could not prepare for Play together")
                throw IllegalStateException("The current player could not prepare for Play together", error)
            }
            Triple(currentSession, newRef, now + groupPrepareTimeoutMs)
        }
        try {
            awaitTogetherReady(session, ref, deadline)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { mutation.withLock {
                    if (groupRef == ref) abortPreparingGroup(session,
                        "users/${session.uid}/playbackV2/groups/${ref.groupId}", ref,
                        "Play together preparation was cancelled")
                } }
            }
            throw cancelled
        }
    }

    private suspend fun awaitTogetherReady(session: FirebaseSession, ref: GroupRef, deadline: Long) {
        val path = "users/${session.uid}/playbackV2/groups/${ref.groupId}"
        while (!closed && System.currentTimeMillis() + serverOffsetMs < deadline) {
            check(groupRef == ref) { "Play together preparation was superseded" }
            val current = transport.get(session, "$path/members").json
            if (current != null && current.length() > MAX_CONNECTED_GROUP_MEMBERS) {
                mutation.withLock { if (groupRef == ref) abortPreparingGroup(session, path, ref,
                    "Play together exceeds the $MAX_CONNECTED_GROUP_MEMBERS player limit") }
                error("Play together exceeds the $MAX_CONNECTED_GROUP_MEMBERS player limit")
            }
            val allMembers = current?.let { runCatching { parseGroupMembers(it) }.getOrNull() }.orEmpty()
            val currentHead = transport.get(session, "$path/head").json?.let { runCatching { parseGroupHead(it) }.getOrNull() }
            if (currentHead != null && (currentHead.epoch != ref.epoch ||
                    currentHead.status in setOf(GroupV2Status.CLOSED, GroupV2Status.FENCED))) {
                mutation.withLock { if (groupRef == ref) abortPreparingGroup(session, path, ref,
                    "Play together could not be activated") }
                error("Play together could not be activated; independent queues were restored paused")
            }
            val ready = allMembers.filter { it.epoch == currentHead?.epoch }
            if (currentHead?.status == GroupV2Status.ACTIVE &&
                groupMembershipMatches(expectedGroupMembers.orEmpty(), ready) &&
                ready.all { it.lastAppliedSequence >= currentHead.timelineSequence &&
                    it.status in setOf(GroupMemberV2Status.PLAYING, GroupMemberV2Status.PAUSED) }) {
                lastError = null
                stateFlow.value = stateFlow.value.copy(pending = false, message = null, group = localGroup)
                return
            }
            if (ready.any { it.status in setOf(GroupMemberV2Status.BUFFERING, GroupMemberV2Status.DISCONNECTED,
                    GroupMemberV2Status.LEFT) }) {
                mutation.withLock { if (groupRef == ref) abortPreparingGroup(session, path, ref,
                    "A selected player could not prepare") }
                error("A selected player could not prepare for Play together")
            }
            delay(200)
        }
        mutation.withLock { if (groupRef == ref) abortPreparingGroup(session, path, ref,
            "Play together preparation timed out") }
        error("Play together preparation timed out; both independent queues were kept")
    }

    suspend fun requestGroup(action: GroupActionV2, positionMs: Long = 0L) {
        check(running && !closed && uid != null) { "Connected playback v2 is not running" }
        check(unresolvedMove == null) { "Finish reconciling the playback transfer before controlling Play together" }
        val ref = groupRef ?: error("This player is not in a Play together session")
        if (action == GroupActionV2.LEAVE) { leaveTogether(); return }
        val group = localGroup ?: error("Play together state is unavailable")
        require(group.status == GroupV2Status.ACTIVE) { "Play together is preparing or reconnecting" }
        require(pendingGroupRequestId == null) { "Wait for the previous shared-playback action to finish" }
        require(action == GroupActionV2.SEEK || positionMs == 0L) { "Unexpected group command position" }
        val session = sessionProvider(); check(session.uid == uid) { "Account changed; reconnect connected playback" }
        val path = "users/${session.uid}/playbackV2/groups/${ref.groupId}"
        val head = transport.get(session, "$path/head").json?.let(::parseGroupHead) ?: error("Play together state disappeared")
        val timeline = transport.get(session, "$path/timelines/${head.timelineSequence}").json
            ?.let { parseGroupTimeline(ref.groupId, it) } ?: error("Play together timeline is unavailable")
        val now = System.currentTimeMillis() + serverOffsetMs
        require(head.epoch == ref.epoch && head.status == GroupV2Status.ACTIVE && head.leaseUntil >= now &&
            timeline.ref.epoch == head.epoch && timeline.sequence == head.timelineSequence &&
            head.timelineDigest == groupTimelineJson(timeline).getString("digest")) {
            "Play together is reconnecting; wait for the current timeline before sending another action"
        }
        require(action != GroupActionV2.SEEK || positionMs in 0..timeline.queue.entries[timeline.cursor].recording.durationMs) {
            "Seek position is outside the shared recording"
        }
        val id = UUID.randomUUID().toString()
        val request = groupRequestJson(id, deviceId, bridge.sessionId, ref.epoch, timeline.sequence,
            action, positionMs, now, now + groupRequestTtlMs)
        check(transport.compareAndSet(session, "$path/requests/$id", request, "null_etag")) { "Group command ID collision" }
        stateFlow.value = stateFlow.value.copy(pending = true)
        pendingGroupSequence = timeline.sequence
        pendingGroupRequestId = id
        pendingGroupRequestExpiresAt = now + groupRequestTtlMs
    }

    suspend fun leaveTogether() = mutation.withLock {
        val ref = groupRef ?: return@withLock
        val session = sessionProvider(); check(session.uid == uid) { "Account changed; reconnect connected playback" }
        val path = "users/${session.uid}/playbackV2/groups/${ref.groupId}"
        val group = localGroup
        val headBeforeLeave = transport.get(session, "$path/head").json?.let { runCatching { parseGroupHead(it) }.getOrNull() }
        val isCoordinator = headBeforeLeave?.coordinatorDeviceId == deviceId && headBeforeLeave.coordinatorSessionId == bridge.sessionId ||
            group?.coordinatorDeviceId == deviceId && group.coordinatorSessionId == bridge.sessionId
        val localStagePending = group?.status == GroupV2Status.PREPARING
        if (localStagePending && headBeforeLeave != null && headBeforeLeave.epoch != ref.epoch) {
            bridge.abort(ref.groupId)
            groupRef = null; groupSourceCapture = null; localGroup = null
            expectedGroupMembers = null
            clearPendingGroupRequest()
            stateFlow.value = stateFlow.value.copy(group = null, pending = false,
                message = "The group changed while preparing. This player's independent queue was kept paused.")
            return@withLock
        }
        if (localStagePending && headBeforeLeave == null) {
            bridge.abort(ref.groupId)
            groupRef = null; groupSourceCapture = null; localGroup = null
            expectedGroupMembers = null
            clearPendingGroupRequest()
            stateFlow.value = stateFlow.value.copy(group = null, pending = false,
                message = "The group disappeared while preparing. This player's independent queue was kept paused.")
            return@withLock
        }
        if (headBeforeLeave?.epoch == ref.epoch &&
            (headBeforeLeave.status == GroupV2Status.PREPARING || localStagePending)) {
            if (isCoordinator) {
                abortPreparingGroup(session, path, ref, "Play together preparation was cancelled")
                return@withLock
            }
            val memberPath = "$path/members/$deviceId"
            var authoritativeHead: GroupHeadV2? = null
            repeat(3) {
                if (authoritativeHead != null) return@repeat
                val currentRead = transport.get(session, "$path/head", etag = true)
                val currentHead = currentRead.json?.let(::parseGroupHead) ?: error("Group state disappeared")
                if (currentHead.epoch != ref.epoch || currentHead.status != GroupV2Status.PREPARING) {
                    authoritativeHead = currentHead
                    return@repeat
                }
                val timeline = transport.get(session, "$path/timelines/${currentHead.timelineSequence}").json
                    ?.let { parseGroupTimeline(ref.groupId, it) } ?: error("Group timeline is unavailable")
                if (transport.compareAndSet(session, "$path/head", groupHeadJson(currentHead.groupId, currentHead.epoch,
                        currentHead.coordinatorDeviceId, currentHead.coordinatorSessionId,
                        System.currentTimeMillis() + serverOffsetMs, timeline, GroupV2Status.CLOSED),
                        currentRead.etag ?: error("Group head has no ETag"))) {
                    authoritativeHead = currentHead.copy(status = GroupV2Status.CLOSED)
                }
            }
            val currentHead = authoritativeHead ?: error("The group changed repeatedly while leaving preparation")
            if (currentHead.epoch != ref.epoch) {
                bridge.abort(ref.groupId)
                groupRef = null; groupSourceCapture = null; localGroup = null
                expectedGroupMembers = null
                clearPendingGroupRequest()
                stateFlow.value = stateFlow.value.copy(group = null, pending = false,
                    message = "The group changed while preparing. This player's independent queue was kept paused.")
                return@withLock
            }
            val member = transport.get(session, memberPath).json
                ?.let { runCatching { parseGroupMember(deviceId, it) }.getOrNull() }
                ?: error("This player's preparation state disappeared")
            require(member.sessionId == bridge.sessionId && member.epoch == ref.epoch) {
                "This player was replaced; reconnect before leaving the old preparation"
            }
            transport.put(session, memberPath, groupMemberJson(member.copy(status = GroupMemberV2Status.LEFT)))
            try {
                bridge.abort(ref.groupId)
                val restored = bridge.observe().also(::validateEngineObservation)
                transport.put(session, memberPath, groupMemberJson(member.copy(status = GroupMemberV2Status.LEFT,
                    observedOccurrenceId = restored.currentOccurrenceId, observedPositionMs = restored.positionMs)))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                lastError = "This player's prepared playback could not be cancelled yet. Retry leaving to recover."
                stateFlow.value = stateFlow.value.copy(group = localGroup, pending = true, message = lastError)
                throw error
            }
            groupRef = null; groupSourceCapture = null; localGroup = null
            expectedGroupMembers = null
            clearPendingGroupRequest()
            stateFlow.value = stateFlow.value.copy(group = null, pending = false,
                message = "Left Play together preparation. This player's independent queue was kept paused.")
            return@withLock
        }
        // A member that has already been fenced must only clean up its local native state.
        // In particular, an old coordinator must never close a newer epoch's group.
        if (headBeforeLeave?.epoch == ref.epoch && group?.coordinatorDeviceId == deviceId && group.coordinatorSessionId == bridge.sessionId) {
            val headRead = transport.get(session, "$path/head", etag = true)
            val head = headRead.json?.let(::parseGroupHead) ?: error("Group state disappeared")
            val timeline = transport.get(session, "$path/timelines/${head.timelineSequence}").json
                ?.let { parseGroupTimeline(ref.groupId, it) } ?: error("Group timeline is unavailable")
            val closedHead = head.copy(status = GroupV2Status.CLOSED, leaseUntil = System.currentTimeMillis() + serverOffsetMs)
            transport.compareAndSet(session, "$path/head", groupHeadJson(closedHead.groupId, closedHead.epoch,
                closedHead.coordinatorDeviceId, closedHead.coordinatorSessionId, closedHead.leaseUntil, timeline, closedHead.status),
                headRead.etag ?: error("Group head has no ETag"))
        } else if (headBeforeLeave?.epoch == ref.epoch && headBeforeLeave.status == GroupV2Status.ACTIVE &&
            headBeforeLeave.leaseUntil >= System.currentTimeMillis() + serverOffsetMs) {
            val now = System.currentTimeMillis() + serverOffsetMs
            val id = UUID.randomUUID().toString()
            val request = groupRequestJson(id, deviceId, bridge.sessionId, ref.epoch, group?.timelineSequence ?: 0,
                GroupActionV2.LEAVE, 0, now, now + V2_REQUEST_TTL_MS)
            check(transport.compareAndSet(session, "$path/requests/$id", request, "null_etag")) { "Group command ID collision" }
        }
        val restored = bridge.leaveGroupPaused(ref.groupId, ref.epoch).also(::validateEngineObservation)
        transport.put(session, "$path/members/$deviceId", groupMemberJson(
            GroupMemberV2(deviceId, bridge.sessionId, ref.epoch, group?.timelineSequence ?: 0, GroupMemberV2Status.LEFT,
                restored.currentOccurrenceId, restored.positionMs)))
        groupRef = null; groupSourceCapture = null; localGroup = null
        expectedGroupMembers = null
        clearPendingGroupRequest()
        stateFlow.value = stateFlow.value.copy(group = null, pending = false, message = "Left Play together. This player's independent queue is paused.")
    }

    private suspend fun refreshPendingGroupRequest(session: FirebaseSession) {
        val requestId = pendingGroupRequestId ?: return
        val group = localGroup
        if (group == null || group.status in setOf(GroupV2Status.FENCED, GroupV2Status.CLOSED)) {
            clearPendingGroupRequest()
            lastError = "The shared-playback action was not applied because the group was fenced or left."
            return
        }
        if (group.timelineSequence > (pendingGroupSequence ?: Long.MAX_VALUE)) {
            clearPendingGroupRequest()
            // A timeline advance is the normal acknowledgement path for coordinated actions.
            // Do not surface it as an error when the coordinator removes the request after CAS.
            lastError = null
            return
        }
        val now = System.currentTimeMillis() + serverOffsetMs
        if (now >= pendingGroupRequestExpiresAt) {
            clearPendingGroupRequest()
            lastError = "The shared-playback action expired before the coordinator applied it."
            return
        }
        val request = transport.get(session, "users/${session.uid}/playbackV2/groups/${group.ref.groupId}/requests/$requestId").json
        if (request == null) {
            clearPendingGroupRequest()
            lastError = "The coordinator did not apply the shared-playback action."
        }
    }

    private fun clearPendingGroupRequest() {
        pendingGroupSequence = null
        pendingGroupRequestId = null
        pendingGroupRequestExpiresAt = 0L
    }

    suspend fun takeOverTogether() = mutation.withLock {
        val oldRef = groupRef ?: error("This player is not in a Play together session")
        val session = sessionProvider(); check(session.uid == uid) { "Account changed; reconnect connected playback" }
        val path = "users/${session.uid}/playbackV2/groups/${oldRef.groupId}"
        val headRead = transport.get(session, "$path/head", etag = true)
        val head = headRead.json?.let(::parseGroupHead) ?: error("Group state disappeared")
        val now = System.currentTimeMillis() + serverOffsetMs
        require(head.epoch == oldRef.epoch && head.status != GroupV2Status.CLOSED && head.leaseUntil < now) {
            "The current coordinator lease is still active or this group has ended"
        }
        val current = transport.get(session, "$path/timelines/${head.timelineSequence}").json?.let { parseGroupTimeline(oldRef.groupId, it) }
            ?: error("Group timeline is unavailable")
        val observation = bridge.observe().also(::validateEngineObservation)
        require(observation.ready && observation.currentOccurrenceId == current.queue.entries[current.cursor].id) {
            "This player has not observed the current shared song; wait for it to catch up before taking over"
        }
        val timeline = current.copy(ref = GroupRef(oldRef.groupId, head.epoch + 1), sequence = head.timelineSequence + 1, cursor = current.cursor,
            positionMs = observation.positionMs, playing = false, ended = false, issuedAtMs = now)
        val membersJson = transport.get(session, "$path/members").json ?: error("Group members are unavailable")
        val historicalMembers = parseGroupMembers(membersJson)
        val members = freshGroupMembers(historicalMembers, head.epoch, now)
        require(members.size in 2..MAX_CONNECTED_GROUP_MEMBERS) { "Too few current players remain to take over this group" }
        require(members.any { it.deviceId == deviceId && it.sessionId == bridge.sessionId }) { "This device is not a current group member" }
        expectedGroupMembers = members.map { it.deviceId to it.sessionId }.toSet()
        val newRef = timeline.ref
        check(transport.compareAndSet(session, "$path/timelines/${timeline.sequence}", groupTimelineJson(timeline), "null_etag")) { "Takeover timeline already exists" }
        val newHead = GroupHeadV2(newRef.groupId, newRef.epoch, deviceId, bridge.sessionId, now + V2_GROUP_LEASE_MS, timeline.sequence,
            groupTimelineJson(timeline).getString("digest"), GroupV2Status.PREPARING)
        check(transport.compareAndSet(session, "$path/head", groupHeadJson(newHead.groupId, newHead.epoch, newHead.coordinatorDeviceId,
            newHead.coordinatorSessionId, newHead.leaseUntil, timeline, newHead.status), headRead.etag ?: error("Group head has no ETag"))) {
            "Another player already took over this group"
        }
        for (member in members) {
            transport.put(session, "$path/members/${member.deviceId}", groupMemberJson(member.copy(epoch = newRef.epoch,
                lastAppliedSequence = -1, status = GroupMemberV2Status.INVITED)))
            val messageId = UUID.randomUUID().toString()
            transport.put(session, "users/${session.uid}/playbackV2/inbox/${member.deviceId}/$messageId",
                inboxMessage("GROUP", newRef.groupId, now))
        }
        groupRef = newRef; abortRequestedGroupRef = null; groupSourceCapture = null
        expectedGroupMembers = members.map { it.deviceId to it.sessionId }.toSet()
        clearPendingGroupRequest()
        localGroup = ConnectedGroupV2(newRef, deviceId, bridge.sessionId, newHead.leaseUntil, 0, GroupV2Status.PREPARING, timeline,
            members.map { it.copy(epoch = newRef.epoch, lastAppliedSequence = -1, status = GroupMemberV2Status.INVITED) })
        stateFlow.value = stateFlow.value.copy(group = localGroup, pending = true,
            message = "Re-preparing the group under a new coordinator epoch. Playback stays paused until ready.")
    }

    private suspend fun abortPreparingGroup(session: FirebaseSession, path: String, ref: GroupRef, message: String) {
        abortRequestedGroupRef = ref
        var cleanupConfirmed = false
        var nativeGroupMayBeActive = false
        try {
            repeat(3) {
                if (cleanupConfirmed) return@repeat
                val headRead = transport.get(session, "$path/head", etag = true)
                val head = headRead.json?.let(::parseGroupHead) ?: return@repeat
                if (head.epoch != ref.epoch || head.status == GroupV2Status.CLOSED) {
                    cleanupConfirmed = true
                    nativeGroupMayBeActive = localGroup?.status == GroupV2Status.ACTIVE ||
                        head.status == GroupV2Status.ACTIVE && localGroup?.status != GroupV2Status.PREPARING
                    return@repeat
                }
                val timeline = transport.get(session, "$path/timelines/${head.timelineSequence}").json
                    ?.let { parseGroupTimeline(ref.groupId, it) } ?: return@repeat
                if (transport.compareAndSet(session, "$path/head", groupHeadJson(head.groupId, head.epoch,
                        head.coordinatorDeviceId, head.coordinatorSessionId, head.leaseUntil, timeline, GroupV2Status.CLOSED),
                        headRead.etag ?: return@repeat)) {
                    cleanupConfirmed = true
                    nativeGroupMayBeActive = localGroup?.status == GroupV2Status.ACTIVE ||
                        head.status == GroupV2Status.ACTIVE && localGroup?.status != GroupV2Status.PREPARING
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Preserve the group reference until the outcome can be reconciled. */ }
        if (!cleanupConfirmed) {
            lastError = "Play together preparation ended, but its server state is uncertain. Reconnect to reconcile before starting another group."
            stateFlow.value = stateFlow.value.copy(group = localGroup, pending = true, message = lastError)
            return
        }
        try {
            if (nativeGroupMayBeActive) bridge.leaveGroupPaused(ref.groupId, ref.epoch).also(::validateEngineObservation)
            else bridge.abort(ref.groupId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            localGroup = localGroup?.copy(status = GroupV2Status.FENCED)
            lastError = "The group was closed, but this player's independent queue could not be restored yet. Retry leaving to recover."
            stateFlow.value = stateFlow.value.copy(group = localGroup, pending = true, message = lastError)
            return
        }
        groupRef = null; groupSourceCapture = null; localGroup = null
        if (abortRequestedGroupRef == ref) abortRequestedGroupRef = null
        expectedGroupMembers = null
        clearPendingGroupRequest()
        lastError = null
        stateFlow.value = stateFlow.value.copy(group = null, pending = false, message = message)
    }

    suspend fun moveTo(targetDeviceId: String, replacePlaying: Boolean = false) {
        val (session, request) = mutation.withLock {
            check(running && !closed && uid != null) { "Connected playback v2 is not running" }
            require(groupRef == null) { "Leave Play together before moving this player" }
            require(unresolvedMove == null) { "A previous transfer is still being reconciled; inspect both players before retrying" }
            val currentSession = sessionProvider(); check(currentSession.uid == uid) { "Account changed; reconnect connected playback" }
            val source = bridge.capture().also(::validateEngineCapture)
            val target = lastV2Devices.firstOrNull { it.id == targetDeviceId }
                ?: error("The selected player has no current v2 session")
            require(target.enabled && target.sessionId == target.snapshot.fence.sessionId &&
                isRecentServerTimestamp(target.updatedAt, System.currentTimeMillis() + serverOffsetMs)) { "The selected player is offline" }
            require("move" in target.capabilities) { "The selected player cannot stage a portable song" }
            require(target.snapshot.fence.sessionId != source.fence.sessionId) { "Choose another player" }
            require(!target.snapshot.playing || replacePlaying) { "The selected player is playing. Confirm replacing its playback first." }
            val now = System.currentTimeMillis() + serverOffsetMs
            val id = UUID.randomUUID().toString()
            val moveRequest = MoveRequestV2(id, deviceId, source.fence.sessionId, target.id, target.sessionId, source.fence,
                target.snapshot.fence, source.queue, source.positionMs, source.playing, now, now + V2_MOVE_OPERATION_TTL_MS)
            val requestJson = moveRequestJson(moveRequest)
            val base = "users/${currentSession.uid}/playbackV2"
            check(transport.compareAndSet(currentSession, "$base/ops/$id/request", requestJson, "null_etag")) {
                "Transfer operation ID already exists"
            }
            transport.put(currentSession, "$base/inbox/${target.id}/$id", inboxMessage("MOVE", id, now))
            unresolvedMove = moveRequest
            stateFlow.value = stateFlow.value.copy(move = MoveOperationV2(id, MoveV2Status.PREPARING, deviceId, target.id),
                pending = true, reconcilingMove = true)
            currentSession to moveRequest
        }
        try { finishMove(session, request) }
        catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runCatching { mutation.withLock { reconcileMove(sessionProvider(), request) } } }
            throw cancelled
        } catch (error: Exception) {
            val reason = "Transfer outcome is uncertain; inspect both players before retrying"
            lastError = error.message?.take(200) ?: reason
            stateFlow.value = stateFlow.value.copy(move = MoveOperationV2(request.id, MoveV2Status.UNKNOWN,
                request.sourceDeviceId, request.targetDeviceId, reason), pending = false, message = lastError,
                reconcilingMove = unresolvedMove != null)
            throw error
        }
    }

    private suspend fun finishMove(session: FirebaseSession, request: MoveRequestV2) {
        val base = "users/${session.uid}/playbackV2/ops/${request.id}"
        val deadline = minOf(request.expiresAt + V2_REQUEST_TTL_MS, request.createdAt + moveWaitTimeoutMs)
        while (!closed && System.currentTimeMillis() + serverOffsetMs < deadline) {
            val targetAck = transport.get(session, "$base/target").json
            val status = targetAck?.optString("status")
            if (status == "REJECTED" || status == "FAILED") {
                val reason = targetAck.optString("reason").takeUnless { it == "null" }
                transport.put(session, "$base/result", resultJson("FAILED", reason))
                unresolvedMove = null
                stateFlow.value = stateFlow.value.copy(move = MoveOperationV2(request.id, MoveV2Status.FAILED,
                    request.sourceDeviceId, request.targetDeviceId, reason), pending = false, reconcilingMove = false)
                return
            }
            if (status == "PREPARED") {
                val current = bridge.capture().also(::validateEngineCapture)
                if (current.fence != request.sourceFence || current.queue != request.queue) {
                    transport.put(session, "$base/result", resultJson("FAILED", "source_changed_before_activation"))
                    transport.put(session, "$base/source", sourceAckJson("FAILED", reason = "source_changed_before_activation"))
                    unresolvedMove = null
                    stateFlow.value = stateFlow.value.copy(move = MoveOperationV2(request.id, MoveV2Status.FAILED,
                        request.sourceDeviceId, request.targetDeviceId, "Source changed before activation"), pending = false,
                        reconcilingMove = false)
                    return
                }
                val now = System.currentTimeMillis() + serverOffsetMs
                val decision = moveDecisionJson(request, current.positionMs, current.playing, now)
                check(transport.compareAndSet(session, "$base/decision", decision, "null_etag") ||
                    transport.get(session, "$base/decision").json != null) { "Could not commit playback transfer" }
                stateFlow.value = stateFlow.value.copy(move = MoveOperationV2(request.id, MoveV2Status.ACTIVATING,
                    request.sourceDeviceId, request.targetDeviceId))
            } else if (status == "OBSERVED") {
                reconcileMove(session, request)
                return
            }
            delay(200)
        }
        reconcileMove(session, request)
    }

    private suspend fun reconcileMove(session: FirebaseSession, request: MoveRequestV2) {
        val path = "users/${session.uid}/playbackV2/ops/${request.id}"
        val ack = transport.get(session, "$path/target").json
        val decision = transport.get(session, "$path/decision").json?.let { parseMoveIntent(it, request) }
        val ackObserved = ack != null && decision != null && moveAckConfirmsStart(ack, request, decision,
            System.currentTimeMillis() + serverOffsetMs)
        val snapshotObserved = !ackObserved && targetSnapshotConfirmsMove(session, request)
        if (ackObserved || snapshotObserved) {
            val source = bridge.capture().also(::validateEngineCapture)
            val result = if (source.fence == request.sourceFence && source.queue == request.queue) {
                bridge.pauseIfUnchanged(request.sourceFence)?.takeIf {
                    !it.playing && it.currentOccurrenceId == request.queue.entries.first().id
                }
            } else null
            val complete = result != null
            val reason = when {
                !complete -> "target_started_but_source_outcome_requires_reconciliation"
                snapshotObserved -> "target_snapshot_confirmed_after_ack_loss"
                else -> null
            }
            transport.put(session, "$path/source", sourceAckJson(if (complete) if (request.playing) "PAUSED" else "UNCHANGED" else "FAILED", result, reason))
            transport.put(session, "$path/result", resultJson(if (complete) "COMPLETE" else "PARTIAL", reason))
            unresolvedMove = null
            stateFlow.value = stateFlow.value.copy(move = MoveOperationV2(request.id,
                if (complete) MoveV2Status.COMPLETE else MoveV2Status.PARTIAL, request.sourceDeviceId, request.targetDeviceId, reason),
                pending = false, reconcilingMove = false)
        } else {
            val reason = "Transfer outcome is unknown; inspect both players before retrying"
            transport.put(session, "$path/result", resultJson("UNKNOWN", reason))
            stateFlow.value = stateFlow.value.copy(move = MoveOperationV2(request.id, MoveV2Status.UNKNOWN,
                request.sourceDeviceId, request.targetDeviceId, reason), pending = false, reconcilingMove = true)
        }
    }

    /** Keep reconciling an UNKNOWN handoff while its target inbox can still activate. */
    private suspend fun monitorLateMove(session: FirebaseSession) {
        val request = unresolvedMove ?: return
        val now = System.currentTimeMillis() + serverOffsetMs
        if (now >= request.createdAt + V2_RECONCILE_MS) {
            unresolvedMove = null
            stateFlow.value = stateFlow.value.copy(reconcilingMove = false,
                message = "Automatic transfer reconciliation expired. Inspect both players before retrying.")
            return
        }
        if (now - lastLateMoveCheckAt < 1_000L) return
        val checkSnapshot = now - lastLateMoveCheckAt >= 5_000L
        lastLateMoveCheckAt = now
        val ack = transport.get(session, "users/${session.uid}/playbackV2/ops/${request.id}/target").json
        when (ack?.optString("status")) {
            "OBSERVED" -> reconcileMove(session, request)
            "FAILED", "REJECTED" -> {
                unresolvedMove = null
                stateFlow.value = stateFlow.value.copy(move = MoveOperationV2(request.id, MoveV2Status.FAILED,
                    request.sourceDeviceId, request.targetDeviceId, ack.optString("reason").takeUnless { it == "null" }),
                    pending = false, reconcilingMove = false)
            }
            else -> if (checkSnapshot && targetSnapshotConfirmsMove(session, request)) {
                reconcileMove(session, request)
            }
        }
    }

    private suspend fun targetSnapshotConfirmsMove(session: FirebaseSession, request: MoveRequestV2): Boolean {
        val now = System.currentTimeMillis() + serverOffsetMs
        val target = transport.get(session, "users/${session.uid}/playbackV2/devices/${request.targetDeviceId}").json
            ?.let { runCatching { parseConnectedV2Device(request.targetDeviceId, it) }.getOrNull() } ?: return false
        val intentJson = transport.get(session, "users/${session.uid}/playbackV2/ops/${request.id}/decision").json ?: return false
        val intent = parseMoveIntent(intentJson, request) ?: return false
        return target.sessionId == request.targetSessionId && isRecentServerTimestamp(target.updatedAt, now) &&
            moveObservationConfirmsStart(target.snapshot, request, intent, target.updatedAt)
    }

    private fun moveAckConfirmsStart(value: JSONObject, request: MoveRequestV2, intent: MoveIntentV2, now: Long): Boolean = runCatching {
        requireV2Keys(value, setOf("status", "sessionId", "observation", "reason", "updatedAt"))
        require(value.getString("status") == "OBSERVED" && value.getString("sessionId") == request.targetSessionId)
        val updatedAt = value.getLong("updatedAt")
        require(updatedAt > 0 && updatedAt <= now + 10_000L)
        moveObservationConfirmsStart(parseObservation(value.getJSONObject("observation")), request, intent, updatedAt)
    }.getOrDefault(false)

    private fun moveObservationConfirmsStart(observation: EngineObservation, request: MoveRequestV2,
        intent: MoveIntentV2, observedAt: Long): Boolean {
        val expectedPosition = expectedMovePosition(intent, request, observedAt) ?: return false
        val duration = request.queue.entries.first().recording.durationMs
        val playingMatches = if (intent.playing && expectedPosition >= duration - V2_OBSERVATION_TOLERANCE_MS)
            (observation.playing || observation.ended) else observation.playing == intent.playing
        val positionMatches = if (intent.playing) observation.positionMs >= (intent.positionMs - V2_OBSERVATION_TOLERANCE_MS).coerceAtLeast(0) &&
            observation.positionMs <= expectedPosition + V2_OBSERVATION_TOLERANCE_MS
        else kotlin.math.abs(observation.positionMs - intent.positionMs) <= V2_OBSERVATION_TOLERANCE_MS
        return observation.fence.sessionId == request.targetSessionId && observation.fence != request.targetFence &&
            observation.ready && observation.currentOccurrenceId == request.queue.entries.first().id &&
            observation.recordingId == request.queue.entries.first().recording.id && playingMatches &&
            positionMatches
    }

    private fun expectedMovePosition(intent: MoveIntentV2, request: MoveRequestV2, observedAt: Long): Long? {
        if (observedAt + 10_000L < intent.issuedAt) return null
        val duration = request.queue.entries.first().recording.durationMs
        return if (intent.playing) (intent.positionMs + (observedAt - intent.issuedAt).coerceAtLeast(0)).coerceAtMost(duration)
        else intent.positionMs
    }

    private suspend fun processInbox(session: FirebaseSession, inbox: JSONObject?) {
        if (inbox == null) return
        val entries = inbox.keys().asSequence().take(MAX_V2_INBOX).toList()
        for (messageId in entries) {
            if (!V2_OPERATION_ID.matches(messageId)) { transport.delete(session, "users/${session.uid}/playbackV2/inbox/$deviceId/$messageId"); continue }
            val row = inbox.optJSONObject(messageId) ?: continue
            val message = runCatching { parseInboxMessage(messageId, row) }.getOrNull()
            if (message == null) { transport.delete(session, "users/${session.uid}/playbackV2/inbox/$deviceId/$messageId"); continue }
            if (message.expiresAt < System.currentTimeMillis() + serverOffsetMs) {
                transport.delete(session, "users/${session.uid}/playbackV2/inbox/$deviceId/$messageId"); continue
            }
            when (message.type) {
                "MOVE" -> processMoveInbox(session, message.refId)
                "GROUP" -> processGroupInvite(session, message.refId, messageId)
            }
        }
    }

    private suspend fun processMoveInbox(session: FirebaseSession, operationId: String) {
        val base = "users/${session.uid}/playbackV2/ops/$operationId"
        val requestJson = transport.get(session, "$base/request").json ?: return
        val request = runCatching { parseMoveRequest(requestJson) }.getOrNull() ?: run {
            transport.put(session, "$base/target", targetAckJson("REJECTED", bridge.sessionId, reason = "invalid_request")); return
        }
        val prior = transport.get(session, "$base/target").json
        if (request.targetDeviceId != deviceId || request.targetSessionId != bridge.sessionId ||
            request.expiresAt < System.currentTimeMillis() + serverOffsetMs && prior?.optString("status") !in setOf("PROCESSING", "PREPARED")) {
            transport.put(session, "$base/target", targetAckJson("REJECTED", bridge.sessionId, reason = "stale_target_or_expired")); return
        }
        if (groupRef != null || localGroup != null) {
            transport.put(session, "$base/target", targetAckJson("REJECTED", bridge.sessionId, reason = "target_in_together"))
            transport.delete(session, "users/${session.uid}/playbackV2/inbox/$deviceId/$operationId")
            return
        }
        when (prior?.optString("status")) {
            "OBSERVED", "FAILED", "REJECTED" -> {
                transport.delete(session, "users/${session.uid}/playbackV2/inbox/$deviceId/$operationId")
                return
            }
        }
        if (prior?.optString("status") != "PREPARED") {
            transport.put(session, "$base/target", targetAckJson("PROCESSING", bridge.sessionId))
            if (bridge.fence() != request.targetFence) {
                transport.put(session, "$base/target", targetAckJson("REJECTED", bridge.sessionId, reason = "stale_target")); return
            }
            try {
                val prepared = bridge.prepare(operationId, request.queue, request.targetFence).also(::validateEngineObservation)
                require(prepared.ready && prepared.fence.sessionId == request.targetSessionId) { "Target did not prepare the selected recording" }
                transport.put(session, "$base/target", targetAckJson("PREPARED", bridge.sessionId, prepared))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                transport.put(session, "$base/target", targetAckJson("FAILED", bridge.sessionId, reason = "prepare_failed"))
                return
            }
        }
        val decisionJson = transport.get(session, "$base/decision").json ?: return
        val decision = parseMoveDecision(decisionJson, request, System.currentTimeMillis() + serverOffsetMs)
        if (decision == null) {
            bridge.abort(operationId)
            transport.put(session, "$base/target", targetAckJson("REJECTED", bridge.sessionId, reason = "invalid_decision")); return
        }
        if (prior?.optString("status") == "PREPARED") {
            val actual = bridge.observe().also(::validateEngineObservation)
            if (moveObservationConfirmsStart(actual, request, decision, System.currentTimeMillis() + serverOffsetMs)) {
                // The target may have started successfully while the OBSERVED acknowledgement was lost.
                // Publish the observation instead of retrying the activation or declaring failure.
                try {
                    transport.put(session, "$base/target", targetAckJson("OBSERVED", bridge.sessionId, actual))
                    transport.delete(session, "users/${session.uid}/playbackV2/inbox/$deviceId/$operationId")
                }
                catch (error: Exception) { if (error is CancellationException) throw error }
                return
            }
        }
        val result = transport.get(session, "$base/result").json
        if (result?.optString("status") in setOf("FAILED", "UNKNOWN")) {
            if (prior?.optString("status") == "PREPARED") runCatching { bridge.abort(operationId) }
            transport.put(session, "$base/target", targetAckJson("FAILED", bridge.sessionId, reason = "source_aborted"))
            return
        }
        val started = try {
            require(bridge.fence() == request.targetFence) { "Target changed after preparation" }
            bridge.activate(operationId, decision.positionMs, decision.playing).also(::validateEngineObservation)
            bridge.observe().also(::validateEngineObservation)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            transport.put(session, "$base/target", targetAckJson("FAILED", bridge.sessionId, reason = "activation_failed"))
            return
        }
        try {
            require(started.ready && started.fence.sessionId == request.targetSessionId &&
                started.currentOccurrenceId == request.queue.entries.first().id &&
                kotlin.math.abs(started.positionMs - decision.positionMs) <= V2_OBSERVATION_TOLERANCE_MS &&
                started.playing == decision.playing) { "Target did not confirm the requested playback state" }
            transport.put(session, "$base/target", targetAckJson("OBSERVED", bridge.sessionId, started))
            transport.delete(session, "users/${session.uid}/playbackV2/inbox/$deviceId/$operationId")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Do not overwrite PREPARED with FAILED after an observed activation merely because
            // the acknowledgement write failed; the next pass can reconcile native state.
            lastError = "The target started playback, but its acknowledgement could not be saved yet."
        }
    }

    private suspend fun publishDevice(session: FirebaseSession, observation: EngineObservation) {
        val now = System.currentTimeMillis()
        if (shouldPublishObservation(observation, now)) {
            val capabilities = bridge.capabilities()
            require(capabilities.all { it in V2_CAPABILITIES }) { "Native playback reported an unsupported v2 capability" }
            val localWriteStartedAt = System.currentTimeMillis()
            transport.put(session, "users/${session.uid}/playbackV2/devices/$deviceId",
                connectedV2DeviceJson(deviceId, safeLabel, observation, capabilities))
            val localWriteCompletedAt = System.currentTimeMillis()
            val confirmed = transport.get(session, "users/${session.uid}/playbackV2/devices/$deviceId").json
                ?.let { runCatching { parseConnectedV2Device(deviceId, it) }.getOrNull() }
            if (confirmed != null && confirmed.snapshot.fence == observation.fence) serverOffsetMs = estimateServerOffsetMs(
                confirmed.updatedAt, localWriteStartedAt, localWriteCompletedAt)
            lastPublished = observation
            lastHeartbeat = now
        }
    }

    private fun shouldPublishObservation(current: EngineObservation, now: Long): Boolean {
        val previous = lastPublished ?: return true
        if (current.fence != previous.fence || current.currentOccurrenceId != previous.currentOccurrenceId ||
            current.recordingId != previous.recordingId || current.title != previous.title || current.artist != previous.artist ||
            current.durationMs != previous.durationMs || current.playing != previous.playing || current.ready != previous.ready ||
            current.ended != previous.ended || current.error != previous.error) return true
        val expected = if (previous.playing) (previous.positionMs + now - lastHeartbeat).coerceAtMost(previous.durationMs) else previous.positionMs
        return now - lastHeartbeat >= 5_000L || kotlin.math.abs(current.positionMs - expected) > 3_000L
    }

    private fun readV2Devices(json: JSONObject?, now: Long): List<ConnectedV2Device> {
        if (json == null) return emptyList()
        val rows = json.keys().asSequence().take(MAX_V2_DEVICES + 1).mapNotNull { id ->
            runCatching { parseConnectedV2Device(id, json.getJSONObject(id)) }.getOrNull()
        }.filter { it.enabled && isRecentServerTimestamp(it.updatedAt, now) }.toList()
        return rows.sortedBy { it.updatedAt }
    }

    private suspend fun processGroupInvite(session: FirebaseSession, groupId: String, inboxId: String) {
        val path = "users/${session.uid}/playbackV2/groups/$groupId"
        val headJson = transport.get(session, "$path/head").json ?: return
        val head = runCatching { parseGroupHead(headJson) }.getOrNull() ?: return
        if (head.status == GroupV2Status.CLOSED) {
            val member = transport.get(session, "$path/members/$deviceId").json
                ?.let { runCatching { parseGroupMember(deviceId, it) }.getOrNull() }
            if (member?.sessionId == bridge.sessionId && member.epoch == head.epoch) {
                runCatching { bridge.abort(groupId) }
                transport.put(session, "$path/members/$deviceId", groupMemberJson(member.copy(status = GroupMemberV2Status.LEFT)))
                transport.delete(session, "users/${session.uid}/playbackV2/inbox/$deviceId/$inboxId")
            }
            return
        }
        if (head.groupId != groupId || head.status !in setOf(GroupV2Status.PREPARING, GroupV2Status.ACTIVE) ||
            head.leaseUntil < System.currentTimeMillis() + serverOffsetMs) return
        val memberPath = "$path/members/$deviceId"
        val memberJson = transport.get(session, memberPath).json ?: return
        val member = runCatching { parseGroupMember(deviceId, memberJson) }.getOrNull() ?: return
        if (member.sessionId != bridge.sessionId || member.epoch != head.epoch || member.status in setOf(GroupMemberV2Status.LEFT, GroupMemberV2Status.DISCONNECTED)) return
        val timelineJson = transport.get(session, "$path/timelines/${head.timelineSequence}").json ?: return
        val timeline = runCatching { parseGroupTimeline(groupId, timelineJson) }.getOrNull() ?: return
        if (timeline.ref.epoch != head.epoch || timeline.sequence != head.timelineSequence ||
            groupTimelineJson(timeline).getString("digest") != head.timelineDigest) return
        val previousRef = groupRef
        if (previousRef != null && previousRef.groupId != timeline.ref.groupId) {
            // Never evict a healthy local group for an unrelated invite. The new
            // coordinator sees BUFFERING and can abort its all-ready startup.
            transport.put(session, memberPath, groupMemberJson(member.copy(status = GroupMemberV2Status.BUFFERING)))
            transport.delete(session, "users/${session.uid}/playbackV2/inbox/$deviceId/$inboxId")
            return
        }
        if (previousRef != null && previousRef != timeline.ref) {
            // A takeover can arrive before the old lease watchdog sees its epoch change.
            // Restore the private independent queue paused before joining the replacement epoch.
            runCatching { leaveOrAbortSupersededGroup(previousRef) }
                .onFailure {
                    lastError = "The previous Play together session could not be paused and restored; this invite was not applied."
                    return
                }
            groupRef = null; groupSourceCapture = null; localGroup = null
            if (abortRequestedGroupRef == previousRef) abortRequestedGroupRef = null
        }
        groupRef = timeline.ref
        localGroup = ConnectedGroupV2(timeline.ref, head.coordinatorDeviceId, head.coordinatorSessionId, head.leaseUntil,
            head.timelineSequence, head.status, timeline)
        if (member.lastAppliedSequence < timeline.sequence && member.status !in setOf(GroupMemberV2Status.READY,
                GroupMemberV2Status.PLAYING, GroupMemberV2Status.PAUSED)) {
            transport.put(session, memberPath, groupMemberJson(member.copy(status = GroupMemberV2Status.PREPARING)))
            try {
                val targetFence = bridge.fence()
                val prepareStartedAt = System.currentTimeMillis()
                diagnose(timeline.ref, "follower_prepare_start")
                val prepared = try {
                    bridge.prepare(groupId, groupStagedTraversal(timeline), targetFence, timeline.ref).also(::validateEngineObservation)
                } catch (error: Exception) {
                    diagnose(timeline.ref, "follower_prepare_failed", "elapsedMs=${System.currentTimeMillis() - prepareStartedAt} error=${error.javaClass.simpleName}")
                    throw error
                }
                diagnose(timeline.ref, "follower_prepare_ready", "elapsedMs=${System.currentTimeMillis() - prepareStartedAt} ready=${prepared.ready}")
                require(prepared.ready && prepared.fence.sessionId == bridge.sessionId) { "Group member could not stage playback" }
                val staged = member.copy(status = GroupMemberV2Status.READY,
                    lastAppliedSequence = if (head.status == GroupV2Status.PREPARING) timeline.sequence else timeline.sequence - 1,
                    observedOccurrenceId = timeline.queue.entries[timeline.cursor].id, observedPositionMs = timeline.positionMs)
                transport.put(session, memberPath, groupMemberJson(staged))
                transport.delete(session, "users/${session.uid}/playbackV2/inbox/$deviceId/$inboxId")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                transport.put(session, memberPath, groupMemberJson(member.copy(status = GroupMemberV2Status.BUFFERING)))
            }
        }
    }

    private suspend fun leaveOrAbortSupersededGroup(ref: GroupRef): EngineObservation {
        val previous = localGroup
        val restored = if (previous?.ref == ref && previous.status == GroupV2Status.PREPARING) {
            // A replacement epoch can fence an engine that only has a silent preload.
            // It has no active-group backup to restore yet, so discard that stage directly.
            bridge.abort(ref.groupId)
            bridge.observe()
        } else bridge.leaveGroupPaused(ref.groupId, ref.epoch)
        return restored.also(::validateEngineObservation)
    }

    private suspend fun processLocalGroup(session: FirebaseSession) {
        val ref = groupRef ?: return
        val path = "users/${session.uid}/playbackV2/groups/${ref.groupId}"
        val headRead = transport.get(session, "$path/head", etag = true)
        val head = headRead.json?.let { runCatching { parseGroupHead(it) }.getOrNull() } ?: return
        if (head.epoch != ref.epoch) {
            // A replacement epoch owns the group now. Restore this engine's displaced
            // independent queue paused; a still-pending inbox invite can then join the new epoch.
            leaveOrAbortSupersededGroup(ref)
            groupRef = null; groupSourceCapture = null; localGroup = null
            if (abortRequestedGroupRef == ref) abortRequestedGroupRef = null
            expectedGroupMembers = null
            clearPendingGroupRequest()
            stateFlow.value = stateFlow.value.copy(group = null, pending = false,
                message = "The Play together coordinator changed. This player's independent queue was restored paused.")
            return
        }
        if (head.status == GroupV2Status.CLOSED) {
            val wasPreparing = localGroup?.status == GroupV2Status.PREPARING
            val restored = if (wasPreparing) { bridge.abort(ref.groupId); bridge.observe() }
                else bridge.leaveGroupPaused(ref.groupId, ref.epoch)
            validateEngineObservation(restored)
            groupRef = null; groupSourceCapture = null; localGroup = null
            if (abortRequestedGroupRef == ref) abortRequestedGroupRef = null
            expectedGroupMembers = null
            clearPendingGroupRequest()
            lastError = null
            stateFlow.value = stateFlow.value.copy(group = null, pending = false,
                message = "Play together ended. Your independent queue is paused.")
            transport.put(session, "$path/members/$deviceId", groupMemberJson(
                GroupMemberV2(deviceId, bridge.sessionId, ref.epoch, head.timelineSequence, GroupMemberV2Status.LEFT,
                    restored.currentOccurrenceId, restored.positionMs)))
            return
        }
        if (abortRequestedGroupRef == ref) {
            abortPreparingGroup(session, path, ref, "Cancelling the incomplete Play together session")
            return
        }
        val leaseNow = System.currentTimeMillis() + serverOffsetMs
        if (head.status == GroupV2Status.FENCED || head.leaseUntil < leaseNow) {
            val timeline = transport.get(session, "$path/timelines/${head.timelineSequence}").json
                ?.let { runCatching { parseGroupTimeline(ref.groupId, it) }.getOrNull() } ?: return
            if (timeline.ref.epoch != ref.epoch || timeline.sequence != head.timelineSequence ||
                groupTimelineJson(timeline).getString("digest") != head.timelineDigest) return
            if (head.status != GroupV2Status.FENCED && !transport.compareAndSet(session, "$path/head",
                    groupHeadJson(head.groupId, head.epoch, head.coordinatorDeviceId, head.coordinatorSessionId,
                        leaseNow, timeline, GroupV2Status.FENCED), headRead.etag ?: return)) return
            try {
                val restored = if (localGroup?.status == GroupV2Status.PREPARING) {
                    bridge.abort(ref.groupId)
                    bridge.observe()
                } else bridge.leaveGroupPaused(ref.groupId, ref.epoch)
                validateEngineObservation(restored)
                groupRef = null; groupSourceCapture = null; localGroup = null
                if (abortRequestedGroupRef == ref) abortRequestedGroupRef = null
                expectedGroupMembers = null
                clearPendingGroupRequest()
                lastError = null
                stateFlow.value = stateFlow.value.copy(group = null, pending = false,
                    message = "Play together lease expired. Your independent queue was restored paused.")
                runCatching { transport.put(session, "$path/members/$deviceId", groupMemberJson(GroupMemberV2(deviceId,
                    bridge.sessionId, ref.epoch, head.timelineSequence, GroupMemberV2Status.LEFT,
                    restored.currentOccurrenceId, restored.positionMs))) }.onFailure {
                    lastError = "Your independent queue was restored paused; remote group cleanup is pending."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                localGroup = localGroup?.copy(status = GroupV2Status.FENCED)
                lastError = "The group was fenced, but the independent queue could not be restored yet. Reconnect to retry."
            }
            return
        }
        val timelineValue = transport.get(session, "$path/timelines/${head.timelineSequence}").json ?: return
        val timeline = runCatching { parseGroupTimeline(ref.groupId, timelineValue) }.getOrNull() ?: return
        if (timeline.ref.epoch != head.epoch || timeline.sequence != head.timelineSequence ||
            groupTimelineJson(timeline).getString("digest") != head.timelineDigest) return
        val membersJson = transport.get(session, "$path/members").json ?: return
        val members = runCatching { parseGroupMembers(membersJson) }.getOrElse {
            localGroup = localGroup?.copy(status = GroupV2Status.FENCED)
            runCatching { pauseFencedGroup(ref) }
            lastError = "This Play together group has an unsupported member count; playback was fenced."
            return
        }
        val now = System.currentTimeMillis() + serverOffsetMs
        val currentMembers = members.filter { it.epoch == head.epoch }
        val liveMembers = freshGroupMembers(currentMembers, head.epoch, now)
        if (head.status == GroupV2Status.ACTIVE && head.coordinatorDeviceId == deviceId &&
            head.coordinatorSessionId == bridge.sessionId && liveMembers.size < 2) {
            // A follower restores locally before its request can be processed. A LEFT or
            // stale member closes the group only when fewer than two fresh players remain.
            transport.compareAndSet(session, "$path/head", groupHeadJson(head.groupId, head.epoch,
                head.coordinatorDeviceId, head.coordinatorSessionId, System.currentTimeMillis() + serverOffsetMs,
                timeline, GroupV2Status.CLOSED), headRead.etag ?: return)
            return
        }
        heartbeatGroupMember(session, path, timeline, currentMembers.firstOrNull { it.deviceId == deviceId })
        val visibleMembers = currentMembers.map { member -> if (now - member.updatedAt > V2_DEVICE_LEASE_MS && member.status != GroupMemberV2Status.LEFT)
            member.copy(status = GroupMemberV2Status.DISCONNECTED) else member }
        localGroup = ConnectedGroupV2(ref, head.coordinatorDeviceId, head.coordinatorSessionId, head.leaseUntil,
            head.timelineSequence, head.status, timeline, visibleMembers)
        if (head.coordinatorDeviceId == deviceId && head.coordinatorSessionId == bridge.sessionId) {
            if (head.status == GroupV2Status.PREPARING) {
                if (!activateGroupWhenReady(session, path, headRead, head, timeline, currentMembers)) renewGroupLease(session, path, headRead, head)
            }
            else if (head.status == GroupV2Status.ACTIVE) {
                val observed = bridge.observe().also(::validateEngineObservation)
                val ended = if (observed.ended && !timeline.ended && observed.currentOccurrenceId == timeline.queue.entries[timeline.cursor].id) {
                    val next = dev.echo.core.connected.advanceGroupTimeline(timeline,
                        dev.echo.core.connected.GroupTimelineAction.NATURAL_END, 0, System.currentTimeMillis() + serverOffsetMs)
                    next != null && commitGroupTimeline(session, path, headRead, head, next)
                } else false
                val committed = ended || processGroupRequests(session, path, head, timeline)
                if (committed) return
                if (!committed) renewGroupLease(session, path, headRead, head)
            }
        }
        applyGroupTimeline(session, path, head, timeline)
    }

    private suspend fun pauseFencedGroup(ref: GroupRef) {
        val observed = bridge.observe().also(::validateEngineObservation)
        val occurrenceId = observed.currentOccurrenceId
        if (observed.playing && occurrenceId != null) {
            bridge.applyGroupTransport(ref, occurrenceId, observed.positionMs, play = false, ended = false)
                .also(::validateEngineObservation)
        }
    }

    /** Enforce the last confirmed lease locally, even when RTDB is temporarily unreachable. */
    private suspend fun enforceLocalGroupLease() {
        val ref = groupRef ?: return
        val group = localGroup ?: return
        if (group.status != GroupV2Status.ACTIVE || group.leaseUntil <= 0L ||
            System.currentTimeMillis() + serverOffsetMs < group.leaseUntil) return
        localGroup = group.copy(status = GroupV2Status.FENCED)
        stateFlow.value = stateFlow.value.copy(group = localGroup, pending = false,
            message = "The Play together lease expired while disconnected. Playback was paused; reconnect or leave to recover.")
        runCatching { pauseFencedGroup(ref) }.onFailure {
            lastError = "The Play together lease expired; native playback could not be confirmed paused. Leave or take over to recover."
        }
    }

    private suspend fun activateGroupWhenReady(session: FirebaseSession, path: String, headRead: RtdbV2Value,
        head: GroupHeadV2, timeline: GroupTimeline, members: List<GroupMemberV2>): Boolean {
        diagnose(timeline.ref, "coordinator_ready_check", "head=${head.status} members=${members.joinToString { it.status.name }} leaseRemainingMs=${head.leaseUntil - (System.currentTimeMillis() + serverOffsetMs)}")
        if (!groupMembershipMatches(expectedGroupMembers.orEmpty(), members)) {
            diagnose(timeline.ref, "coordinator_membership_mismatch")
            abortPreparingGroup(session, path, timeline.ref, "Group membership changed while preparing")
            return false
        }
        if (members.any { it.epoch != head.epoch || it.status != GroupMemberV2Status.READY }) {
            diagnose(timeline.ref, "coordinator_waiting_ready")
            return false
        }
        val initial = groupSourceCapture
        val playState = if (initial != null) {
            // Both native engines reject capture while their coordinator preload is staged.
            // The unchanged revision fence protects the captured traversal without recapturing it.
            val fresh = bridge.observe().also(::validateEngineObservation)
            diagnose(timeline.ref, "coordinator_source_observed", "fenceMatches=${fresh.fence == initial.fence} occurrenceMatches=${fresh.currentOccurrenceId == initial.queue.entries.firstOrNull()?.id}")
            if (fresh.fence != initial.fence || fresh.currentOccurrenceId != initial.queue.entries.firstOrNull()?.id) {
                abortPreparingGroup(session, path, timeline.ref, "Source changed while preparing the group")
                return false
            }
            fresh.positionMs to fresh.playing
        } else {
            val current = bridge.observe().also(::validateEngineObservation)
            require(current.fence.sessionId == bridge.sessionId) { "Coordinator session changed during takeover" }
            current.positionMs to false
        }
        // The native observation above can block behind a serialized player operation.
        // Re-read the head and exact READY cohort before committing ACTIVE so a follower
        // cancellation or epoch change during that wait cannot activate stale membership.
        val latestHeadRead = transport.get(session, "$path/head", etag = true)
        val latestHead = latestHeadRead.json?.let { runCatching { parseGroupHead(it) }.getOrNull() }
        val latestMembers = transport.get(session, "$path/members").json
            ?.let { runCatching { parseGroupMembers(it) }.getOrNull() }.orEmpty()
            .filter { it.epoch == head.epoch }
        val now = System.currentTimeMillis() + serverOffsetMs
        if (latestHead == null || latestHead.epoch != head.epoch || latestHead.status != GroupV2Status.PREPARING ||
            latestHead.leaseUntil < now || latestHead.timelineSequence != head.timelineSequence ||
            latestHead.timelineDigest != head.timelineDigest ||
            !groupMembershipMatches(expectedGroupMembers.orEmpty(), latestMembers) ||
            latestMembers.any { it.status != GroupMemberV2Status.READY }) {
            diagnose(timeline.ref, "coordinator_ready_recheck_rejected", "head=${latestHead?.status} members=${latestMembers.joinToString { it.status.name }}")
            return false
        }
        val active = timeline.copy(sequence = timeline.sequence + 1, positionMs = playState.first,
            playing = playState.second, ended = false, issuedAtMs = now)
        diagnose(timeline.ref, "coordinator_activate_commit_start", "sequence=${active.sequence}")
        if (!commitGroupTimeline(session, path, latestHeadRead, latestHead, active, GroupV2Status.ACTIVE)) {
            diagnose(timeline.ref, "coordinator_activate_commit_rejected", "sequence=${active.sequence}")
            return false
        }
        diagnose(timeline.ref, "coordinator_activate_committed", "sequence=${active.sequence}")
        groupSourceCapture = null
        return true
    }

    private suspend fun applyGroupTimeline(session: FirebaseSession, path: String, head: GroupHeadV2, timeline: GroupTimeline) {
        if (head.status != GroupV2Status.ACTIVE || head.epoch != groupRef?.epoch) return
        val memberPath = "$path/members/$deviceId"
        val member = transport.get(session, memberPath).json?.let { runCatching { parseGroupMember(deviceId, it) }.getOrNull() } ?: return
        if (member.sessionId != bridge.sessionId || member.epoch != head.epoch || member.status == GroupMemberV2Status.LEFT ||
            member.lastAppliedSequence >= timeline.sequence) return
        diagnose(timeline.ref, "timeline_apply_start", "head=${head.status} member=${member.status} sequence=${timeline.sequence}")
        val nativeGroupNeedsActivation = member.status == GroupMemberV2Status.READY
        val expectedOccurrence = timeline.queue.entries[timeline.cursor].id
        try {
            val current = bridge.observe().also(::validateEngineObservation)
            val activationPosition = groupActivationPosition(timeline, System.currentTimeMillis() + serverOffsetMs)
            if (timeline.playing && !timeline.ended && activationPosition == null) {
                transport.put(session, memberPath, groupMemberJson(member.copy(status = GroupMemberV2Status.BUFFERING)))
                return
            }
            val positionMs = activationPosition ?: timeline.positionMs
            if (!nativeGroupNeedsActivation && current.currentOccurrenceId == expectedOccurrence && current.ready && current.playing == timeline.playing &&
                kotlin.math.abs(current.positionMs - positionMs) <= V2_OBSERVATION_TOLERANCE_MS) {
                updateGroupMemberObserved(session, memberPath, member, timeline, current)
                return
            }
            val operationId = "${timeline.ref.groupId}-${timeline.ref.epoch}-${timeline.sequence}"
            var expectedStartPosition = positionMs
            val started = if (nativeGroupNeedsActivation) {
                // READY means this member already staged the exact timeline during the invite
                // handshake. Activate that prepared engine directly instead of refetching media.
                val freshHead = transport.get(session, "$path/head").json?.let { runCatching { parseGroupHead(it) }.getOrNull() }
                val freshTimeline = freshHead?.let { transport.get(session, "$path/timelines/${it.timelineSequence}").json }
                    ?.let { runCatching { parseGroupTimeline(timeline.ref.groupId, it) }.getOrNull() }
                if (freshHead == null || freshTimeline == null || freshHead.epoch != timeline.ref.epoch ||
                    freshHead.status != GroupV2Status.ACTIVE || freshHead.leaseUntil < System.currentTimeMillis() + serverOffsetMs ||
                    freshHead.timelineSequence != timeline.sequence || freshHead.timelineDigest != groupTimelineJson(timeline).getString("digest") ||
                    freshTimeline.sequence != timeline.sequence || freshTimeline.ref.epoch != timeline.ref.epoch) {
                    transport.put(session, memberPath, groupMemberJson(member.copy(status = GroupMemberV2Status.BUFFERING)))
                    return
                }
                val freshPosition = groupActivationPosition(freshTimeline, System.currentTimeMillis() + serverOffsetMs)
                if (freshTimeline.playing && !freshTimeline.ended && freshPosition == null) {
                    transport.put(session, memberPath, groupMemberJson(member.copy(status = GroupMemberV2Status.BUFFERING)))
                    return
                }
                expectedStartPosition = freshPosition ?: freshTimeline.positionMs
                val nativeStartedAt = System.currentTimeMillis()
                diagnose(timeline.ref, "native_activate_start", "sequence=${timeline.sequence} mode=staged")
                bridge.activate(timeline.ref.groupId, expectedStartPosition,
                    freshTimeline.playing && !freshTimeline.ended).also(::validateEngineObservation).also {
                    diagnose(timeline.ref, "native_activate_observed", "sequence=${timeline.sequence} elapsedMs=${System.currentTimeMillis() - nativeStartedAt} ready=${it.ready} playing=${it.playing} positionMs=${it.positionMs}")
                }
            } else if (current.currentOccurrenceId == expectedOccurrence && current.ready) {
                bridge.applyGroupTransport(timeline.ref, expectedOccurrence, positionMs,
                    timeline.playing && !timeline.ended, timeline.ended).also(::validateEngineObservation)
            } else {
                val targetFence = bridge.fence()
                val prepared = bridge.prepare(operationId, groupStagedTraversal(timeline), targetFence, timeline.ref).also(::validateEngineObservation)
                require(prepared.ready && prepared.fence.sessionId == bridge.sessionId) { "Group timeline could not be staged" }
                // Native preparation may take tens of seconds. Recheck the authoritative
                // head after staging so a delayed follower cannot activate a superseded song.
                val freshHead = transport.get(session, "$path/head").json?.let { runCatching { parseGroupHead(it) }.getOrNull() }
                val freshTimeline = freshHead?.let { transport.get(session, "$path/timelines/${it.timelineSequence}").json }
                    ?.let { runCatching { parseGroupTimeline(timeline.ref.groupId, it) }.getOrNull() }
                if (freshHead == null || freshTimeline == null || freshHead.epoch != timeline.ref.epoch ||
                    freshHead.status != GroupV2Status.ACTIVE || freshHead.leaseUntil < System.currentTimeMillis() + serverOffsetMs ||
                    freshHead.timelineSequence != timeline.sequence || freshHead.timelineDigest != groupTimelineJson(timeline).getString("digest") ||
                    freshTimeline.sequence != timeline.sequence || freshTimeline.ref.epoch != timeline.ref.epoch) {
                    bridge.abort(operationId)
                    transport.put(session, memberPath, groupMemberJson(member.copy(status = GroupMemberV2Status.BUFFERING)))
                    return
                }
                val freshPosition = groupActivationPosition(freshTimeline, System.currentTimeMillis() + serverOffsetMs)
                if (freshTimeline.playing && !freshTimeline.ended && freshPosition == null) {
                    bridge.abort(operationId)
                    transport.put(session, memberPath, groupMemberJson(member.copy(status = GroupMemberV2Status.BUFFERING)))
                    return
                }
                expectedStartPosition = freshPosition ?: freshTimeline.positionMs
                val nativeStartedAt = System.currentTimeMillis()
                diagnose(timeline.ref, "native_activate_start", "sequence=${timeline.sequence} mode=prepared")
                bridge.activate(operationId, expectedStartPosition,
                    freshTimeline.playing && !freshTimeline.ended).also(::validateEngineObservation).also {
                    diagnose(timeline.ref, "native_activate_observed", "sequence=${timeline.sequence} elapsedMs=${System.currentTimeMillis() - nativeStartedAt} ready=${it.ready} playing=${it.playing} positionMs=${it.positionMs}")
                }
            }
            val latestHead = transport.get(session, "$path/head").json?.let { runCatching { parseGroupHead(it) }.getOrNull() }
            if (latestHead == null || latestHead.epoch != timeline.ref.epoch || latestHead.status == GroupV2Status.CLOSED ||
                latestHead.leaseUntil < System.currentTimeMillis() + serverOffsetMs) {
                bridge.leaveGroupPaused(timeline.ref.groupId, timeline.ref.epoch).also(::validateEngineObservation)
                return
            }
            if (latestHead.status != GroupV2Status.ACTIVE || latestHead.timelineSequence != timeline.sequence ||
                latestHead.timelineDigest != groupTimelineJson(timeline).getString("digest")) {
                val actual = bridge.observe().also(::validateEngineObservation)
                val actualOccurrence = actual.currentOccurrenceId
                if (actualOccurrence != null) {
                    bridge.applyGroupTransport(timeline.ref, actualOccurrence, actual.positionMs, play = false, ended = false)
                        .also(::validateEngineObservation)
                }
                transport.put(session, memberPath, groupMemberJson(member.copy(status = GroupMemberV2Status.BUFFERING)))
                return
            }
            require(started.ready && started.currentOccurrenceId == expectedOccurrence &&
                kotlin.math.abs(started.positionMs - expectedStartPosition) <= V2_OBSERVATION_TOLERANCE_MS &&
                started.playing == (timeline.playing && !timeline.ended) && (!timeline.ended || started.ended ||
                    kotlin.math.abs(started.positionMs - timeline.queue.entries[timeline.cursor].recording.durationMs) <= V2_OBSERVATION_TOLERANCE_MS)) {
                "Native player did not confirm the shared timeline"
            }
            updateGroupMemberObserved(session, memberPath, member, timeline, started)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            transport.put(session, memberPath, groupMemberJson(member.copy(status = GroupMemberV2Status.BUFFERING)))
            lastError = "A group player is buffering or reconnecting. The coordinator is holding the current shared timeline."
        }
    }

    private suspend fun updateGroupMemberObserved(session: FirebaseSession, memberPath: String, member: GroupMemberV2,
        timeline: GroupTimeline, observation: EngineObservation) {
        val status = if (observation.playing) GroupMemberV2Status.PLAYING else GroupMemberV2Status.PAUSED
        transport.put(session, memberPath, groupMemberJson(member.copy(epoch = timeline.ref.epoch,
            lastAppliedSequence = timeline.sequence, status = status, observedOccurrenceId = observation.currentOccurrenceId,
            observedPositionMs = observation.positionMs)))
        lastGroupHeartbeat = System.currentTimeMillis()
    }

    private suspend fun heartbeatGroupMember(session: FirebaseSession, path: String, timeline: GroupTimeline, member: GroupMemberV2?) {
        val now = System.currentTimeMillis()
        if (member == null || member.sessionId != bridge.sessionId || member.status !in setOf(GroupMemberV2Status.PLAYING,
                GroupMemberV2Status.PAUSED, GroupMemberV2Status.READY) || now - lastGroupHeartbeat < 5_000L) return
        if (member.status == GroupMemberV2Status.READY) {
            // Keep READY until this staged engine is actually activated. Reporting the
            // independent pre-group player state here would prematurely revoke readiness.
            transport.put(session, "$path/members/$deviceId", groupMemberJson(member))
            lastGroupHeartbeat = now
            return
        }
        val observation = bridge.observe().also(::validateEngineObservation)
        val current = member.copy(status = if (observation.playing) GroupMemberV2Status.PLAYING else GroupMemberV2Status.PAUSED,
            observedOccurrenceId = observation.currentOccurrenceId, observedPositionMs = observation.positionMs)
        transport.put(session, "$path/members/$deviceId", groupMemberJson(current))
        lastGroupHeartbeat = now
    }

    private suspend fun processGroupRequests(session: FirebaseSession, path: String, head: GroupHeadV2, timeline: GroupTimeline): Boolean {
        val rows = transport.get(session, "$path/requests", query = mapOf("orderBy" to "\"createdAt\"", "limitToLast" to "32")).json ?: return false
        val now = System.currentTimeMillis() + serverOffsetMs
        for (id in rows.keys().asSequence().take(32).toList()) {
            val value = rows.optJSONObject(id) ?: continue
            val request = runCatching { parseGroupRequest(id, value, now) }.getOrNull()
            if (request == null || request.expectedEpoch != head.epoch || request.expectedSeq != timeline.sequence) {
                transport.delete(session, "$path/requests/$id")
                continue
            }
            val member = transport.get(session, "$path/members/${request.sourceDeviceId}").json
                ?.let { runCatching { parseGroupMember(request.sourceDeviceId, it) }.getOrNull() }
            val allowedMemberStatuses = if (request.action == GroupActionV2.LEAVE)
                setOf(GroupMemberV2Status.PLAYING, GroupMemberV2Status.PAUSED, GroupMemberV2Status.READY, GroupMemberV2Status.LEFT)
            else setOf(GroupMemberV2Status.PLAYING, GroupMemberV2Status.PAUSED, GroupMemberV2Status.READY)
            if (member == null || member.sessionId != request.sourceSessionId || member.epoch != head.epoch ||
                member.status !in allowedMemberStatuses) {
                transport.delete(session, "$path/requests/$id")
                continue
            }
            // Keep a valid current-sequence request until this member has applied the committed timeline.
            if (member.lastAppliedSequence < timeline.sequence) return false
            if (member.lastAppliedSequence > timeline.sequence) {
                transport.delete(session, "$path/requests/$id")
                continue
            }
            if (request.action == GroupActionV2.LEAVE) {
                transport.put(session, "$path/members/${request.sourceDeviceId}", groupMemberJson(member.copy(status = GroupMemberV2Status.LEFT)))
                transport.delete(session, "$path/requests/$id")
                val memberRows = transport.get(session, "$path/members").json
                    ?.let { runCatching { parseGroupMembers(it) }.getOrNull() }.orEmpty()
                val remaining = freshGroupMembers(memberRows, head.epoch, now)
                    .count { it.deviceId != request.sourceDeviceId }
                if (remaining < 2) {
                    val headRead = transport.get(session, "$path/head", etag = true)
                    val latestHead = headRead.json?.let { runCatching { parseGroupHead(it) }.getOrNull() }
                    if (latestHead != null && latestHead.epoch == head.epoch && latestHead.status == GroupV2Status.ACTIVE &&
                        latestHead.timelineSequence == timeline.sequence && latestHead.timelineDigest == groupTimelineJson(timeline).getString("digest")) {
                        transport.compareAndSet(session, "$path/head", groupHeadJson(latestHead.groupId, latestHead.epoch,
                            latestHead.coordinatorDeviceId, latestHead.coordinatorSessionId, now, timeline, GroupV2Status.CLOSED),
                            headRead.etag ?: return true)
                    }
                }
                return true
            }
            val action = when (request.action) {
                GroupActionV2.PLAY -> dev.echo.core.connected.GroupTimelineAction.PLAY
                GroupActionV2.PAUSE -> dev.echo.core.connected.GroupTimelineAction.PAUSE
                GroupActionV2.SEEK -> dev.echo.core.connected.GroupTimelineAction.SEEK
                GroupActionV2.NEXT -> dev.echo.core.connected.GroupTimelineAction.NEXT
                GroupActionV2.PREVIOUS -> dev.echo.core.connected.GroupTimelineAction.PREVIOUS
                GroupActionV2.LEAVE -> continue
            }
            val observation = bridge.observe().also(::validateEngineObservation)
            if (!observation.ready || observation.fence.sessionId != head.coordinatorSessionId ||
                observation.currentOccurrenceId != timeline.queue.entries[timeline.cursor].id) continue
            val base = if (request.action in setOf(GroupActionV2.PLAY, GroupActionV2.PAUSE))
                timeline.copy(positionMs = observation.positionMs) else timeline
            val next = runCatching { dev.echo.core.connected.advanceGroupTimeline(base, action, request.positionMs, now) }.getOrNull()
            if (next != null && commitGroupTimeline(session, path, transport.get(session, "$path/head", etag = true), head, next)) {
                transport.delete(session, "$path/requests/$id")
                return true
            }
        }
        return false
    }

    private suspend fun renewGroupLease(session: FirebaseSession, path: String, headRead: RtdbV2Value, head: GroupHeadV2) {
        val now = System.currentTimeMillis() + serverOffsetMs
        val ref = GroupRef(head.groupId, head.epoch)
        if (head.leaseUntil - now > V2_GROUP_LEASE_MS / 2) {
            diagnose(ref, "lease_renew_skip_margin", "status=${head.status} remainingMs=${head.leaseUntil - now}")
            return
        }
        diagnose(ref, "lease_renew_cas_start", "status=${head.status} remainingMs=${head.leaseUntil - now}")
        val timeline = transport.get(session, "$path/timelines/${head.timelineSequence}").json
            ?.let { runCatching { parseGroupTimeline(head.groupId, it) }.getOrNull() }
        if (timeline == null) {
            diagnose(ref, "lease_renew_skip_timeline")
            return
        }
        val renewed = head.copy(leaseUntil = now + V2_GROUP_LEASE_MS)
        val etag = headRead.etag
        if (etag == null) {
            diagnose(ref, "lease_renew_skip_no_etag")
            return
        }
        val committed = transport.compareAndSet(session, "$path/head", groupHeadJson(renewed.groupId, renewed.epoch,
            renewed.coordinatorDeviceId, renewed.coordinatorSessionId, renewed.leaseUntil, timeline, renewed.status), etag)
        diagnose(ref, if (committed) "lease_renew_cas_success" else "lease_renew_cas_rejected",
            "status=${head.status} oldUntil=${head.leaseUntil} newUntil=${renewed.leaseUntil}")
        if (!committed) {
            val fresh = transport.get(session, "$path/head").json?.let { runCatching { parseGroupHead(it) }.getOrNull() }
            if (fresh != null && (fresh.epoch != head.epoch || fresh.coordinatorDeviceId != deviceId || fresh.coordinatorSessionId != bridge.sessionId)) {
                localGroup = localGroup?.copy(status = GroupV2Status.FENCED)
            }
        } else if (localGroup?.ref?.epoch == head.epoch) {
            localGroup = localGroup?.copy(leaseUntil = renewed.leaseUntil)
        }
    }

    /** Renew coordinator readiness while the public Together call owns the engine staging lock. */
    private suspend fun prepareCoordinatorGroup(session: FirebaseSession, ref: GroupRef,
        prepare: suspend () -> EngineObservation): EngineObservation = coroutineScope {
        diagnose(ref, "preparing_renewal_child_start")
        val renewal = launch(Dispatchers.IO) {
            while (isActive && !closed && groupRef == ref && abortRequestedGroupRef != ref) {
                delay(V2_POLL_MS)
                try { renewPreparingGroupLease(session, ref) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    diagnose(ref, "preparing_renewal_error", "error=${error.javaClass.simpleName}")
                }
            }
        }
        try { prepare() } finally {
            renewal.cancelAndJoin()
            diagnose(ref, "preparing_renewal_child_end", "aborted=${abortRequestedGroupRef == ref} closed=$closed")
        }
    }

    private suspend fun renewPreparingGroupLease(session: FirebaseSession, ref: GroupRef) {
        if (abortRequestedGroupRef == ref || groupRef != ref || localGroup?.ref != ref ||
            localGroup?.status != GroupV2Status.PREPARING || localGroup?.coordinatorDeviceId != deviceId ||
            localGroup?.coordinatorSessionId != bridge.sessionId) {
            diagnose(ref, "preparing_renewal_skip_local_guard", "aborted=${abortRequestedGroupRef == ref} localStatus=${localGroup?.status} localCoordinator=${localGroup?.coordinatorDeviceId}")
            return
        }
        diagnose(ref, "preparing_renewal_check")
        val path = "users/${session.uid}/playbackV2/groups/${ref.groupId}"
        val headRead = transport.get(session, "$path/head", etag = true)
        val head = headRead.json?.let { runCatching { parseGroupHead(it) }.getOrNull() }
        if (head == null) {
            diagnose(ref, "preparing_renewal_skip_missing_head")
            return
        }
        val now = System.currentTimeMillis() + serverOffsetMs
        if (head.epoch == ref.epoch && head.status == GroupV2Status.PREPARING &&
            head.coordinatorDeviceId == deviceId && head.coordinatorSessionId == bridge.sessionId &&
            head.leaseUntil > now) {
            val timeline = transport.get(session, "$path/timelines/${head.timelineSequence}").json
                ?.let { runCatching { parseGroupTimeline(ref.groupId, it) }.getOrNull() }
            if (timeline == null) {
                diagnose(ref, "preparing_renewal_skip_missing_timeline")
                return
            }
            if (timeline.ref != ref || timeline.sequence != head.timelineSequence ||
                groupTimelineJson(timeline).getString("digest") != head.timelineDigest) {
                diagnose(ref, "preparing_renewal_skip_timeline_mismatch", "headSequence=${head.timelineSequence} timelineSequence=${timeline.sequence}")
                return
            }
            if (abortRequestedGroupRef == ref || groupRef != ref || localGroup?.ref != ref ||
                localGroup?.status != GroupV2Status.PREPARING || localGroup?.coordinatorDeviceId != deviceId ||
                localGroup?.coordinatorSessionId != bridge.sessionId ||
                head.leaseUntil <= System.currentTimeMillis() + serverOffsetMs) {
                diagnose(ref, "preparing_renewal_skip_final_guard", "aborted=${abortRequestedGroupRef == ref} remainingMs=${head.leaseUntil - (System.currentTimeMillis() + serverOffsetMs)}")
                return
            }
            renewGroupLease(session, path, headRead, head)
        } else {
            diagnose(ref, "preparing_renewal_skip_head_guard", "headEpoch=${head.epoch} status=${head.status} coordinator=${head.coordinatorDeviceId} sessionMatches=${head.coordinatorSessionId == bridge.sessionId} remainingMs=${head.leaseUntil - now}")
        }
    }

    private suspend fun commitGroupTimeline(session: FirebaseSession, path: String, headRead: RtdbV2Value,
        expectedHead: GroupHeadV2, timeline: GroupTimeline, status: GroupV2Status = expectedHead.status): Boolean {
        val observedHead = headRead.json?.let { runCatching { parseGroupHead(it) }.getOrNull() } ?: return false
        if (timeline.ref.groupId != expectedHead.groupId || timeline.ref.epoch != expectedHead.epoch ||
            timeline.sequence != expectedHead.timelineSequence + 1 || expectedHead.coordinatorDeviceId != deviceId ||
            expectedHead.coordinatorSessionId != bridge.sessionId || expectedHead.leaseUntil < System.currentTimeMillis() + serverOffsetMs ||
            observedHead.groupId != expectedHead.groupId || observedHead.epoch != expectedHead.epoch ||
            observedHead.coordinatorDeviceId != expectedHead.coordinatorDeviceId ||
            observedHead.coordinatorSessionId != expectedHead.coordinatorSessionId ||
            observedHead.timelineSequence != expectedHead.timelineSequence || observedHead.timelineDigest != expectedHead.timelineDigest ||
            observedHead.status != expectedHead.status || observedHead.leaseUntil < System.currentTimeMillis() + serverOffsetMs) return false
        val timelinePath = "$path/timelines/${timeline.sequence}"
        val timelineJson = groupTimelineJson(timeline)
        val digest = timelineJson.getString("digest")
        val wrote = transport.compareAndSet(session, timelinePath, timelineJson, "null_etag")
        if (!wrote) {
            val existing = transport.get(session, timelinePath).json ?: return false
            if (existing.optString("digest") != digest) return false
        }
        val next = expectedHead.copy(leaseUntil = System.currentTimeMillis() + serverOffsetMs + V2_GROUP_LEASE_MS,
            timelineSequence = timeline.sequence, timelineDigest = digest, status = status)
        val success = transport.compareAndSet(session, "$path/head", groupHeadJson(next.groupId, next.epoch, next.coordinatorDeviceId,
            next.coordinatorSessionId, next.leaseUntil, timeline, next.status), headRead.etag ?: return false)
        if (success) {
            if (localGroup?.ref?.epoch == next.epoch) {
                localGroup = localGroup?.copy(leaseUntil = next.leaseUntil, timelineSequence = timeline.sequence,
                    status = status, timeline = timeline)
            }
            return true
        }
        val fresh = transport.get(session, "$path/head").json?.let { runCatching { parseGroupHead(it) }.getOrNull() }
        if (fresh != null && (fresh.epoch != expectedHead.epoch || fresh.coordinatorDeviceId != deviceId || fresh.coordinatorSessionId != bridge.sessionId)) {
            localGroup = localGroup?.copy(status = GroupV2Status.FENCED)
        }
        return false
    }

}
