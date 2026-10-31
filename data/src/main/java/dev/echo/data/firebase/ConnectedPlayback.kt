package dev.echo.data.firebase

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class ConnectedPlayerSnapshot(
    val trackId: String?,
    val title: String?,
    val artist: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val trackRevision: String,
    val queueRevision: String,
)

enum class ConnectedPlaybackAction { PLAY, PAUSE, NEXT, PREVIOUS, SEEK }

data class ConnectedPlaybackCommand(
    val id: String,
    val action: ConnectedPlaybackAction,
    val targetSessionId: String,
    val expectedTrackRevision: String,
    val expectedQueueRevision: String,
    val positionMs: Long,
    val issuedAt: Long,
    val expiresAt: Long,
)

data class ConnectedPlaybackAck(val commandId: String, val status: String, val processedAt: Long, val reason: String? = null)

data class ConnectedDevice(
    val id: String,
    val label: String,
    val sessionId: String,
    val updatedAt: Long,
    val player: ConnectedPlayerSnapshot,
    val controlEnabled: Boolean = true,
    val ack: ConnectedPlaybackAck? = null,
)

data class ConnectedPlaybackState(
    val devices: List<ConnectedDevice> = emptyList(),
    val message: String? = null,
    val pending: Boolean = false,
)

/** REST-only live presence and remote control for one Firebase account. */
class ConnectedPlaybackClient(
    databaseUrl: String,
    val deviceId: String,
    label: String,
    private val sessionProvider: suspend () -> FirebaseSession,
    private val snapshot: suspend () -> ConnectedPlayerSnapshot,
    private val executeCommand: suspend (ConnectedPlaybackCommand) -> Unit,
    httpClient: OkHttpClient = defaultConnectedPlaybackHttpClient(),
    private val v2Participation: () -> Boolean = { false },
    private val controlEnabled: () -> Boolean = { true },
) {
    private val root = validateDatabaseUrl(databaseUrl)
    private val safeLabel = safeText(label, 48, "device")
    private val http = httpClient
    private val activeSessionId = UUID.randomUUID().toString()
    private val mutableState = MutableStateFlow(ConnectedPlaybackState())
    val state: StateFlow<ConnectedPlaybackState> = mutableState.asStateFlow()
    private var uid: String? = null
    private var lastPlayer: ConnectedPlayerSnapshot? = null
    private var lastHeartbeatAt = 0L
    private var serverOffsetMs = 0L
    private var closed = false
    private var running = false
    private val seenCommandIds = mutableSetOf<String>()
    private val pendingCommandIds = mutableMapOf<String, Pair<String, Long>>()
    private var lastAck: ConnectedPlaybackAck? = null
    private var commandMessage: String? = null

    init { require(deviceId.matches(DEVICE_ID)) { "Invalid device ID" } }

    /** Run from the owning playback service/app lifecycle; it exits when cancelled or closed. */
    suspend fun run() {
        check(!closed && !running) { "Connected playback client is already stopped or running" }
        val initial = sessionProvider()
        running = true
        uid = initial.uid
        mutableState.value = ConnectedPlaybackState(message = null)
        try {
            while (!closed) {
                try {
                val current = sessionProvider()
                if (current.uid != initial.uid) {
                    mutableState.value = ConnectedPlaybackState(message = "Account changed; reconnect connected playback.")
                    return
                }
                val player = sanitizeSnapshot(snapshot())
                val now = System.currentTimeMillis()
                var publishedAt = 0L
                if (shouldPublish(player, now)) {
                    publishedAt = System.currentTimeMillis()
                    writeDevice(current, player)
                    lastPlayer = player
                    lastHeartbeatAt = now
                }
                val devicesJson = requestJson(current, "users/${current.uid}/playback/devices.json", query = mapOf("orderBy" to "\"updatedAt\"", "limitToLast" to MAX_DEVICES.toString()))
                val commandsJson = requestJson(current, "users/${current.uid}/playback/commands/$deviceId.json", query = mapOf("orderBy" to "\"createdAt\"", "limitToLast" to MAX_COMMANDS.toString()))
                val serverUpdatedAt = devicesJson.optJSONObject(deviceId)?.optLong("updatedAt", 0L) ?: 0L
                if (publishedAt > 0L && serverUpdatedAt > 0L) serverOffsetMs = (serverUpdatedAt - publishedAt).coerceIn(-MAX_CLOCK_OFFSET_MS, MAX_CLOCK_OFFSET_MS)
                val devices = readDevices(devicesJson, System.currentTimeMillis() + serverOffsetMs)
                processCommands(current, commandsJson, devices.firstOrNull { it.id == deviceId })
                checkPendingCommands(current)
                val message = commandMessage ?: if (devices.any { it.id != deviceId }) null else "Waiting for another Echo device."
                mutableState.value = ConnectedPlaybackState(devices, message, pendingCommandIds.isNotEmpty())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!closed) mutableState.value = mutableState.value.copy(
                        devices = mutableState.value.devices.map { it.copy(controlEnabled = false) },
                        message = if (error is FirebaseRequestException) error.message else "Connected playback is unavailable.",
                    )
                }
                if (!closed) delay(POLL_MS)
            }
        } finally {
            runCatching {
                withContext(NonCancellable) {
                    withTimeoutOrNull(1_500L) {
                        val latest = sessionProvider()
                        if (latest.uid == initial.uid) writeDevice(latest, lastPlayer ?: sanitizeSnapshot(snapshot()), enabled = false)
                    }
                }
            }
            mutableState.value = ConnectedPlaybackState(message = "Connected playback stopped.")
            running = false
        }
    }

    suspend fun sendCommand(target: ConnectedDevice, action: ConnectedPlaybackAction, positionMs: Long = 0L) {
        check(!closed && uid != null) { "Connected playback is not running" }
        require(target.id.matches(DEVICE_ID) && target.sessionId.matches(DEVICE_ID)) { "Invalid target device" }
        require(target.id != deviceId && target.controlEnabled && isLive(target.updatedAt, System.currentTimeMillis() + serverOffsetMs)) {
            "Target device is offline or controls are disabled"
        }
        require(pendingCommandIds.isEmpty()) { "A remote command is already waiting for acknowledgment" }
        require(action != ConnectedPlaybackAction.SEEK || positionMs in 0..target.player.durationMs) { "Seek position is outside the track" }
        require(action == ConnectedPlaybackAction.SEEK || positionMs == 0L) { "Unexpected command position" }
        val current = sessionProvider()
        require(current.uid == uid) { "Account changed; reconnect connected playback" }
        val now = System.currentTimeMillis() + serverOffsetMs
        val command = ConnectedPlaybackCommand(
            id = UUID.randomUUID().toString(), action = action, targetSessionId = target.sessionId,
            expectedTrackRevision = target.player.trackRevision, expectedQueueRevision = target.player.queueRevision,
            positionMs = positionMs, issuedAt = now, expiresAt = now + COMMAND_TTL_MS,
        )
        val body = JSONObject().put("id", command.id).put("action", action.name)
            .put("targetSessionId", command.targetSessionId).put("expectedTrackRevision", command.expectedTrackRevision)
            .put("expectedQueueRevision", command.expectedQueueRevision).put("positionMs", command.positionMs)
            .put("issuedAt", command.issuedAt).put("expiresAt", command.expiresAt)
            .put("sourceDeviceId", deviceId).put("sourceSessionId", activeSessionId)
            .put("createdAt", JSONObject().put(".sv", "timestamp"))
        pendingCommandIds[command.id] = target.id to command.expiresAt
        commandMessage = null
        try {
            requestJson(current, "users/${current.uid}/playback/commands/${target.id}/${command.id}.json", "PUT", body)
        } catch (error: Exception) {
            pendingCommandIds.remove(command.id)
            throw error
        }
        mutableState.value = mutableState.value.copy(pending = true)
    }

    fun close() {
        closed = true
        pendingCommandIds.clear()
        mutableState.value = ConnectedPlaybackState(message = "Connected playback stopped.")
    }

    private suspend fun writeDevice(session: FirebaseSession, player: ConnectedPlayerSnapshot, enabled: Boolean = true) {
        val body = JSONObject().put("label", safeLabel).put("sessionId", activeSessionId)
            .put("updatedAt", JSONObject().put(".sv", "timestamp")).put("controlEnabled", enabled && controlEnabled())
            .put("player", playerJson(player))
        lastAck?.let { body.put("ack", ackJson(it)) }
        requestJson(session, "users/${session.uid}/playback/devices/$deviceId.json", "PUT", body)
    }

    private suspend fun processCommands(session: FirebaseSession, mailbox: JSONObject?, own: ConnectedDevice?) {
        if (mailbox == null) return
        val now = System.currentTimeMillis() + serverOffsetMs
        val entries = mailbox.keys().asSequence().take(MAX_COMMANDS).toList()
        for (id in entries) {
            if (!id.matches(COMMAND_ID)) {
                deleteCommand(session, deviceId, id)
                continue
            }
            val item = mailbox.optJSONObject(id)
            if (item == null) {
                deleteCommand(session, deviceId, id)
                seenCommandIds.remove(id)
                continue
            }
            if (item.has("ack")) {
                val validAck = runCatching { parseAck(item.getJSONObject("ack")) }.isSuccess
                if (!validAck || item.optLong("expiresAt", 0L) + COMMAND_RETENTION_MS <= now) {
                    deleteCommand(session, deviceId, id)
                }
                seenCommandIds.remove(id)
                continue
            }
            if (id in seenCommandIds) continue
            val action = ConnectedPlaybackAction.entries.firstOrNull { it.name.equals(item.optString("action"), true) }
            val command = runCatching {
                ConnectedPlaybackCommand(id, requireNotNull(action), item.getString("targetSessionId"),
                    item.getString("expectedTrackRevision"), item.getString("expectedQueueRevision"),
                    item.getLong("positionMs"), item.getLong("issuedAt"), item.getLong("expiresAt"))
            }.getOrNull()
            val reason = connectedCommandRejection(command, activeSessionId, own?.player, own?.controlEnabled == true, now)
            val accepted = reason == null && command != null
            val started = ConnectedPlaybackAck(id, if (accepted) "processing" else "rejected", now, reason)
            writeAck(session, id, started)
            seenCommandIds += id
            if (!accepted) {
                if (command == null) deleteCommand(session, deviceId, id)
                continue
            }
            val fresh = sanitizeSnapshot(snapshot())
            val staleReason = connectedCommandRejection(command, activeSessionId, fresh, !closed,
                System.currentTimeMillis() + serverOffsetMs)
            if (staleReason != null) {
                writeAck(session, id, ConnectedPlaybackAck(id, "rejected", System.currentTimeMillis() + serverOffsetMs, staleReason))
                seenCommandIds += id
                continue
            }
            if (v2Participation()) {
                writeAck(session, id, ConnectedPlaybackAck(id, "rejected", System.currentTimeMillis() + serverOffsetMs, "controls_disabled"))
                seenCommandIds += id
                continue
            }
            val applied = try {
                executeCommand(command!!)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            writeAck(session, id, ConnectedPlaybackAck(id, if (applied) "applied" else "rejected", System.currentTimeMillis() + serverOffsetMs,
                if (applied) null else "execution_failed"))
            seenCommandIds += id
            if (command.expiresAt + COMMAND_RETENTION_MS <= now) deleteCommand(session, deviceId, id)
        }
    }

    private suspend fun deleteCommand(session: FirebaseSession, targetId: String, commandId: String) {
        requestJson(session, "users/${session.uid}/playback/commands/$targetId/$commandId.json", "DELETE")
    }

    private suspend fun writeAck(session: FirebaseSession, commandId: String, ack: ConnectedPlaybackAck) {
        val body = ackJson(ack)
        lastAck = ack
        requestJson(session, "users/${session.uid}/playback/commands/$deviceId/$commandId/ack.json", "PUT", body)
        runCatching { requestJson(session, "users/${session.uid}/playback/devices/$deviceId/ack.json", "PUT", body) }
    }

    private suspend fun checkPendingCommands(session: FirebaseSession) {
        val now = System.currentTimeMillis() + serverOffsetMs
        for ((id, pending) in pendingCommandIds.toMap()) {
            val ackJson = requestJson(session, "users/${session.uid}/playback/commands/${pending.first}/$id/ack.json")
            val ack = runCatching { parseAck(ackJson) }.getOrNull()
            if (ack?.commandId == id && ack.status in setOf("applied", "rejected")) {
                pendingCommandIds.remove(id)
                commandMessage = if (ack.status == "applied") "Remote command applied." else "Remote command rejected; refresh the device state and try again."
            } else if (pending.second + COMMAND_RETENTION_MS < now) {
                pendingCommandIds.remove(id)
                commandMessage = "Remote command was not confirmed; check the device before retrying."
            }
        }
    }

    private fun shouldPublish(player: ConnectedPlayerSnapshot, now: Long): Boolean {
        val previous = lastPlayer ?: return true
        if (player.trackId != previous.trackId || player.title != previous.title || player.artist != previous.artist ||
            player.isPlaying != previous.isPlaying || player.durationMs != previous.durationMs ||
            player.trackRevision != previous.trackRevision || player.queueRevision != previous.queueRevision) return true
        val expected = if (previous.isPlaying) (previous.positionMs + now - lastHeartbeatAt).coerceAtMost(previous.durationMs) else previous.positionMs
        return now - lastHeartbeatAt >= HEARTBEAT_MS || kotlin.math.abs(player.positionMs - expected) > SEEK_TOLERANCE_MS
    }

    private fun readDevices(json: JSONObject?, serverNow: Long): List<ConnectedDevice> = json?.let { root ->
        root.keys().asSequence().take(MAX_DEVICES).mapNotNull { id ->
            if (!id.matches(DEVICE_ID)) return@mapNotNull null
            val row = root.optJSONObject(id) ?: return@mapNotNull null
            val player = row.optJSONObject("player") ?: return@mapNotNull null
            val updatedAt = row.optLong("updatedAt", 0L)
            runCatching {
                ConnectedDevice(id, safeText(row.getString("label"), 48, "device"), row.getString("sessionId"), updatedAt,
                    parsePlayer(player), row.optBoolean("controlEnabled") && isLive(updatedAt, serverNow),
                    row.optJSONObject("ack")?.let { runCatching { parseAck(it) }.getOrNull() })
            }.getOrNull()
        }.toList()
    } ?: emptyList()

    private suspend fun requestJson(
        session: FirebaseSession,
        path: String,
        method: String = "GET",
        json: JSONObject? = null,
        query: Map<String, String> = emptyMap(),
    ): JSONObject {
        val urlBuilder = root.newBuilder().addPathSegments(path).addQueryParameter("auth", session.idToken)
        query.forEach { (name, value) -> urlBuilder.addQueryParameter(name, value) }
        val url = urlBuilder.build()
        val body = json?.toString()?.toRequestBody(JSON)
        val request = Request.Builder().url(url).method(method, body).build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Connected playback request failed"))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try {
                        val result = response.use {
                            val bytes = it.body?.byteStream()?.use { stream -> readBounded(stream, MAX_RESPONSE_BYTES) }
                                ?: error("Missing response")
                            if (!it.isSuccessful) throw FirebaseRequestException(it.code, when (it.code) {
                                401, 403 -> "Realtime Database access was denied. Check Firebase sign-in and the published playback rules."
                                429 -> "Realtime Database is rate limiting requests. Try again shortly."
                                else -> "Connected playback request failed (HTTP ${it.code})."
                            })
                            if (bytes.isEmpty() || bytes.contentEquals("null".toByteArray())) JSONObject()
                            else JSONObject(bytes.toString(Charsets.UTF_8))
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(if (error is FirebaseRequestException) error else IllegalStateException("Connected playback request failed"))
                    }
                }
            })
        }
    }

    private fun playerJson(player: ConnectedPlayerSnapshot) = JSONObject().put("trackId", player.trackId ?: JSONObject.NULL)
        .put("title", player.title ?: JSONObject.NULL).put("artist", player.artist ?: JSONObject.NULL)
        .put("isPlaying", player.isPlaying).put("positionMs", player.positionMs).put("durationMs", player.durationMs)
        .put("trackRevision", player.trackRevision).put("queueRevision", player.queueRevision)

    private fun sanitizeSnapshot(value: ConnectedPlayerSnapshot): ConnectedPlayerSnapshot {
        val duration = value.durationMs.coerceIn(0, MAX_TRACK_MS)
        return value.copy(
            trackId = value.trackId?.takeIf { TRACK_ID.matches(it) }, title = value.title?.let { safeText(it, 500, "") },
            artist = value.artist?.let { safeText(it, 300, "") }, positionMs = value.positionMs.coerceIn(0, duration),
            durationMs = duration, trackRevision = REVISION.replace(value.trackRevision.take(256), "").ifBlank { "0" },
            queueRevision = REVISION.replace(value.queueRevision.take(256), "").ifBlank { "0" },
        )
    }

    private fun parsePlayer(json: JSONObject) = sanitizeSnapshot(ConnectedPlayerSnapshot(
        json.optString("trackId").takeUnless { it == "null" || it.isBlank() },
        json.optString("title").takeUnless { it == "null" || it.isBlank() },
        json.optString("artist").takeUnless { it == "null" || it.isBlank() }, json.optBoolean("isPlaying"),
        json.optLong("positionMs", 0L), json.optLong("durationMs", 0L), json.getString("trackRevision"), json.getString("queueRevision"),
    ))

    private fun ackJson(ack: ConnectedPlaybackAck) = JSONObject().put("commandId", ack.commandId).put("status", ack.status)
        .put("processedAt", JSONObject().put(".sv", "timestamp")).put("reason", ack.reason ?: JSONObject.NULL)

    private fun parseAck(json: JSONObject): ConnectedPlaybackAck {
        val keys = json.keys().asSequence().toSet()
        require(keys.containsAll(ACK_REQUIRED_KEYS) && keys.all { it in ACK_KEYS } && json.getString("commandId").matches(COMMAND_ID))
        val status = json.getString("status")
        require(status in ACK_STATUSES)
        val reason = json.optString("reason").takeUnless { it == "null" || it.isBlank() }
        require(reason == null || reason in ACK_REASONS)
        return ConnectedPlaybackAck(json.getString("commandId"), status, json.optLong("processedAt", 0L), reason)
    }

    companion object {
        private const val POLL_MS = 2_000L
        private const val HEARTBEAT_MS = 5_000L
        private const val COMMAND_TTL_MS = 20_000L
        private const val COMMAND_RETENTION_MS = 5_000L
        private const val MAX_CLOCK_SKEW_MS = 30_000L
        private const val MAX_CLOCK_OFFSET_MS = 5 * 60_000L
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val MAX_DEVICES = 32
        private const val MAX_COMMANDS = 32
        private const val SEEK_TOLERANCE_MS = 3_000L
        private const val MAX_TRACK_MS = 604_800_000L
        private val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val COMMAND_ID = Regex("[A-Fa-f0-9-]{36}")
        private val TRACK_ID = Regex("[A-Za-z0-9_:-]{1,256}")
        private val REVISION = Regex("[^A-Za-z0-9_:-]")
        private val ACK_KEYS = setOf("commandId", "status", "processedAt", "reason")
        private val ACK_REQUIRED_KEYS = setOf("commandId", "status", "processedAt")
        private val ACK_STATUSES = setOf("processing", "applied", "rejected")
        private val ACK_REASONS = setOf("invalid", "stale_session", "expired", "controls_disabled", "stale_track", "stale_queue", "invalid_seek", "execution_failed")
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private fun isLive(updatedAt: Long, now: Long) = updatedAt > 0 && now - updatedAt in 0..15_000L
        private fun safeText(value: String, maximum: Int, fallback: String): String = value.take(maximum)
            .filterNot(Char::isISOControl).ifBlank { fallback }

        private fun validateDatabaseUrl(value: String): okhttp3.HttpUrl {
            val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid Realtime Database URL")
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
                url.encodedPath in setOf("", "/") && url.port == 443 && url.host.matches(
                    Regex("echo-85da4-default-rtdb(?:\\.[a-z0-9-]+)?\\.firebasedatabase\\.app|echo-85da4-default-rtdb\\.firebaseio\\.com"))) {
                "Realtime Database URL is not an Echo Firebase database"
            }
            return url.newBuilder().encodedPath("/").build()
        }
    }
}

private fun defaultConnectedPlaybackHttpClient() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()

private fun readBounded(input: java.io.InputStream, maximum: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        require(output.size() + count <= maximum) { "Realtime Database response is too large" }
        output.write(buffer, 0, count)
    }
}

internal fun connectedCommandRejection(
    command: ConnectedPlaybackCommand?, sessionId: String, player: ConnectedPlayerSnapshot?,
    controlsEnabled: Boolean, now: Long,
): String? = when {
    command == null -> "invalid"
    command.targetSessionId != sessionId -> "stale_session"
    command.issuedAt > now + 30_000 || command.expiresAt < now ||
        command.expiresAt - command.issuedAt !in 1..20_000 -> "expired"
    player == null || !controlsEnabled -> "controls_disabled"
    command.expectedTrackRevision != player.trackRevision -> "stale_track"
    command.action in setOf(ConnectedPlaybackAction.NEXT, ConnectedPlaybackAction.PREVIOUS, ConnectedPlaybackAction.SEEK) &&
        command.expectedQueueRevision != player.queueRevision -> "stale_queue"
    command.action == ConnectedPlaybackAction.SEEK && command.positionMs !in 0..player.durationMs -> "invalid_seek"
    command.action != ConnectedPlaybackAction.SEEK && command.positionMs != 0L -> "invalid"
    else -> null
}
