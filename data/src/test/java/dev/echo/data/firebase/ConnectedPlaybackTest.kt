package dev.echo.data.firebase

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedPlaybackTest {
    private val session = FirebaseSession("user_one", "owner@example.com", "synthetic-id-token", "synthetic-refresh-token", 9_999_999_999_999)

    @Test fun acceptsOnlyTheConfiguredRealtimeDatabaseHost() {
        val client = makeClient("https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app/")
        assertEquals("device_one", client.deviceId)
        listOf(
            "http://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app",
            "https://evil.example/",
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app.evil.example/",
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app/?auth=leak",
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app/users/other",
        ).forEach { assertTrue(runCatching { makeClient(it) }.isFailure) }
    }

    @Test fun publishesOnlySafePlayerFieldsAndSendsAuthenticatedBoundedCommand(): Unit = runBlocking {
        val now = System.currentTimeMillis()
        val remote = JSONObject().put("label", "Laptop").put("sessionId", "remote-session")
            .put("updatedAt", now).put("controlEnabled", true)
            .put("player", JSONObject().put("trackId", "youtube:abc123").put("title", "Song")
                .put("artist", "Artist").put("isPlaying", true).put("positionMs", 1000).put("durationMs", 90_000)
                .put("trackRevision", "track-4").put("queueRevision", "queue-7"))
            .put("ack", JSONObject().put("commandId", "00000000-0000-0000-0000-000000000001")
                .put("status", "applied").put("processedAt", now).put("reason", JSONObject.NULL).put("unexpected", "rejected"))
        val captured = mutableListOf<okhttp3.Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            synchronized(captured) { captured += request }
            val text = when {
                request.method == "GET" && request.url.encodedPath.endsWith("/devices.json") -> JSONObject().put("laptop", remote).toString()
                request.method == "GET" && request.url.encodedPath.endsWith("/ack.json") -> JSONObject()
                    .put("commandId", request.url.pathSegments[request.url.pathSegments.lastIndex - 1])
                    .put("status", "applied").put("processedAt", System.currentTimeMillis()).toString()
                else -> "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(text.toResponseBody()).build()
        }.build()
        val client = ConnectedPlaybackClient(
            databaseUrl = "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app/",
            deviceId = "device_one", label = "Phone\n",
            sessionProvider = { session },
            snapshot = { ConnectedPlayerSnapshot("youtube:local", "Local song", "Local artist", true, 12_000, 180_000, "t1", "q2") },
            executeCommand = {}, httpClient = http,
        )
        val job = launch { client.run() }
        try {
            val target = withTimeout(3_000) { client.state.first { state -> state.devices.any { it.id == "laptop" } }.devices.first { it.id == "laptop" } }
            assertEquals(null, target.ack)
            assertTrue(runCatching { client.sendCommand(target.copy(id = "../other-user"), ConnectedPlaybackAction.PAUSE) }.isFailure)
            client.sendCommand(target, ConnectedPlaybackAction.SEEK, 5_000)
            val sent = synchronized(captured) { captured.last { it.method == "PUT" && it.url.encodedPath.contains("/commands/laptop/") } }
            assertEquals("synthetic-id-token", sent.url.queryParameter("auth"))
            assertEquals(null, sent.header("Authorization"))
            val payload = JSONObject(okio.Buffer().also { sent.body!!.writeTo(it) }.readUtf8())
            assertEquals("SEEK", payload.getString("action"))
            assertEquals("remote-session", payload.getString("targetSessionId"))
            assertEquals("track-4", payload.getString("expectedTrackRevision"))
            assertEquals("queue-7", payload.getString("expectedQueueRevision"))
            assertEquals(5_000L, payload.getLong("positionMs"))
            val published = synchronized(captured) { captured.first { it.method == "PUT" && it.url.encodedPath.endsWith("/devices/device_one.json") } }
            val own = JSONObject(okio.Buffer().also { published.body!!.writeTo(it) }.readUtf8())
            assertEquals("Local song", own.getJSONObject("player").getString("title"))
            assertFalse(own.toString().contains("synthetic-id-token"))
            assertFalse(own.toString().contains("resolved"))
            withTimeout(6_000) { client.state.first { !it.pending && it.message == "Remote command applied." } }
        } finally {
            client.close()
            job.cancelAndJoin()
        }
    }

    @Test fun delayedCommandsCannotChangeAnotherSessionTrackOrQueue() {
        val now = 100_000L
        val player = ConnectedPlayerSnapshot("youtube:abc", "Song", "Artist", true, 1000, 90_000, "t1", "q1")
        val command = ConnectedPlaybackCommand("command", ConnectedPlaybackAction.SEEK, "session", "t1", "q1", 5000, now, now + 20_000)
        fun reason(value: ConnectedPlaybackCommand? = command, enabled: Boolean = true) =
            connectedCommandRejection(value, "session", player, enabled, now)
        assertEquals(null, reason())
        assertEquals("invalid", reason(null))
        assertEquals("stale_session", reason(command.copy(targetSessionId = "old")))
        assertEquals("stale_track", reason(command.copy(expectedTrackRevision = "old")))
        assertEquals("stale_queue", reason(command.copy(expectedQueueRevision = "old")))
        assertEquals("expired", reason(command.copy(issuedAt = 1, expiresAt = 20_001)))
        assertEquals("expired", reason(command.copy(issuedAt = now + 31_000, expiresAt = now + 32_000)))
        assertEquals("controls_disabled", reason(enabled = false))
        assertEquals("invalid_seek", reason(command.copy(positionMs = 90_001)))
        assertEquals("invalid", reason(command.copy(action = ConnectedPlaybackAction.PAUSE)))
        assertEquals(null, reason(command.copy(action = ConnectedPlaybackAction.PAUSE, positionMs = 0)))
    }

    @Test fun twoClientsApplyOnceAcknowledgeAndRejectAnOldTrack(): Unit = runBlocking {
        val database = JSONObject()
        val applied = mutableListOf<ConnectedPlaybackAction>()
        fun serverValue(value: Any?): Any? = when (value) {
            is JSONObject -> if (value.optString(".sv") == "timestamp") System.currentTimeMillis() else JSONObject().also { copy ->
                value.keys().forEach { key -> serverValue(value.opt(key))?.let { copy.put(key, it) } }
            }
            JSONObject.NULL, null -> null
            else -> value
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val parts = request.url.pathSegments.map { it.removeSuffix(".json") }
            val text = synchronized(database) {
                var parent = database
                parts.dropLast(1).forEach { key -> parent = parent.optJSONObject(key) ?: JSONObject().also { parent.put(key, it) } }
                val key = parts.last()
                if (request.method == "PUT") {
                    val body = JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                    parent.put(key, serverValue(body))
                } else if (request.method == "DELETE") parent.remove(key)
                parent.opt(key)?.toString() ?: "null"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(text.toResponseBody()).build()
        }.build()
        var player = ConnectedPlayerSnapshot("youtube:abcdefghijk", "Song", "Artist", true, 1000, 90_000, "t1", "q1")
        fun client(id: String, target: Boolean) = ConnectedPlaybackClient(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", id, id, { session }, { player },
            { command -> if (target) { applied += command.action; player = player.copy(isPlaying = false) } }, http)
        val sender = client("sender", false)
        val receiver = client("receiver", true)
        val jobs = listOf(launch { sender.run() }, launch { receiver.run() })
        try {
            val target = withTimeout(6_000) { sender.state.first { it.devices.any { device -> device.id == "receiver" } }
                .devices.first { it.id == "receiver" } }
            sender.sendCommand(target, ConnectedPlaybackAction.PAUSE)
            withTimeout(8_000) { sender.state.first { !it.pending && it.message == "Remote command applied." } }
            delay(2_200)
            assertEquals(listOf(ConnectedPlaybackAction.PAUSE), applied)
            sender.sendCommand(target.copy(player = target.player.copy(trackRevision = "old-track")), ConnectedPlaybackAction.PAUSE)
            withTimeout(8_000) { sender.state.first { !it.pending && it.message?.startsWith("Remote command rejected") == true } }
            assertEquals(listOf(ConnectedPlaybackAction.PAUSE), applied)
        } finally {
            sender.close(); receiver.close()
            jobs.forEach { it.cancelAndJoin() }
        }
    }

    private fun makeClient(url: String) = ConnectedPlaybackClient(url, "device_one", "Phone", { session },
        { ConnectedPlayerSnapshot(null, null, null, false, 0, 0, "t", "q") }, {})
}
