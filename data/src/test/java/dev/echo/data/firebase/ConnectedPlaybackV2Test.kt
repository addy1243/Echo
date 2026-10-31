package dev.echo.data.firebase

import dev.echo.core.connected.EngineFence
import dev.echo.core.connected.EngineObservation
import dev.echo.core.connected.GroupRef
import dev.echo.core.connected.GroupTimeline
import dev.echo.core.connected.PortableRecording
import dev.echo.core.connected.QueueOccurrence
import dev.echo.core.connected.RepeatModeV2
import dev.echo.core.connected.TransferQueue
import dev.echo.core.connected.CONNECTED_V2_CAPABILITY_GROUP
import dev.echo.core.connected.CONNECTED_V2_CAPABILITY_MOVE
import dev.echo.core.connected.validateTransferQueue
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedPlaybackV2Test {
    private val session = FirebaseSession("owner", "owner@example.test", "synthetic-token", "refresh", Long.MAX_VALUE)

    @Test fun inboxUsesOneServerAdjustedClockForCreatedAndExpiryTimes() {
        val id = "00000000-0000-4000-8000-000000000099"
        val estimatedServerNow = 1_000_000L
        val message = inboxMessage("MOVE", id, estimatedServerNow)
        assertEquals(estimatedServerNow, message.getLong("createdAt"))
        assertEquals(120_000L, message.getLong("expiresAt") - message.getLong("createdAt"))
        assertEquals(estimatedServerNow + 120_000L, parseInboxMessage(id, message).expiresAt)

        message.put("expiresAt", estimatedServerNow + 120_001L)
        assertTrue(runCatching { parseInboxMessage(id, message) }.isFailure)
    }

    @Test fun serverOffsetUsesCorrelatedWriteRoundTripInsteadOfStaleHeartbeatAge() {
        val localWriteStartedAt = 1_000_000L
        val localWriteFinishedAt = 1_004_000L
        val serverTimestamp = 1_032_000L
        assertEquals(30_000L, estimateServerOffsetMs(serverTimestamp, localWriteStartedAt, localWriteFinishedAt))
        // A later poll can happen long after this unchanged heartbeat; it must not be folded into the offset.
        assertEquals(30_000L, estimateServerOffsetMs(serverTimestamp, localWriteStartedAt, localWriteFinishedAt))
        assertTrue(runCatching { estimateServerOffsetMs(serverTimestamp, localWriteFinishedAt, localWriteStartedAt) }.isFailure)
    }

    @Test fun groupMemberParsingAcceptsTwoThroughEightAndRejectsOutsideBounds() {
        fun member(sessionId: String) = JSONObject().put("sessionId", sessionId).put("epoch", 1)
            .put("lastAppliedSeq", -1).put("status", "INVITED").put("observedOccurrenceId", "")
            .put("observedPositionMs", 0).put("updatedAt", 1)
        val members = JSONObject().put("one", member("session-one")).put("two", member("session-two"))
        assertEquals(2, parseGroupMembers(members).size)
        members.put("three", member("session-three"))
        assertEquals(3, parseGroupMembers(members).size)
        for (index in 4..8) members.put("member-$index", member("session-$index"))
        assertEquals(8, parseGroupMembers(members).size)
        members.put("member-9", member("session-9"))
        assertTrue("An over-cap membership set is rejected, never hidden by take(8).",
            runCatching { parseGroupMembers(members) }.isFailure)
    }

    @Test fun groupActivationParticipantsMustMatchEveryInvitedSession() {
        val members = (1..3).map { index ->
            GroupMemberV2("device-$index", "session-$index", 1, 0, GroupMemberV2Status.READY)
        }
        val expected = members.map { it.deviceId to it.sessionId }.toSet()
        assertTrue(groupMembershipMatches(expected, members))
        assertFalse(groupMembershipMatches(expected, members.dropLast(1)))
        assertFalse(groupMembershipMatches(expected, members.map {
            if (it.deviceId == "device-2") it.copy(sessionId = "replacement-session") else it
        }))
    }

    @Test fun takeoverCohortExcludesLeftStaleDisconnectedAndPriorEpochRows() {
        val now = 200_000L
        fun member(id: String, epoch: Long, status: GroupMemberV2Status, updatedAt: Long) =
            GroupMemberV2(id, "session-$id", epoch, 4, status, updatedAt = updatedAt)
        val members = listOf(
            member("coordinator", 2, GroupMemberV2Status.PAUSED, now),
            member("survivor", 2, GroupMemberV2Status.PLAYING, now),
            member("leaver", 2, GroupMemberV2Status.LEFT, now),
            member("stale", 2, GroupMemberV2Status.PLAYING, now - 20_000L),
            member("offline", 2, GroupMemberV2Status.DISCONNECTED, now),
            member("old-epoch", 1, GroupMemberV2Status.PAUSED, now),
        )
        assertEquals(setOf("coordinator", "survivor"), freshGroupMembers(members, 2, now).map { it.deviceId }.toSet())
    }

    @Test fun transferCodecPreservesDuplicateRecordingsAsDistinctOccurrencesAndRejectsOversize() {
        val recording = PortableRecording("youtube:abcdefghijk", "Song / live", "Artist/feat", 90_000)
        val queue = TransferQueue(listOf(
            QueueOccurrence("00000000-0000-4000-8000-000000000001", recording),
            QueueOccurrence("00000000-0000-4000-8000-000000000002", recording),
        ), RepeatModeV2.ALL, shuffleEnabled = true)
        assertEquals(queue, parseTransferQueue(transferQueueJson(queue)))

        val oversized = queue.copy(entries = (0 until 500).map { index ->
            QueueOccurrence("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                recording.copy(title = "t".repeat(128), artist = "a".repeat(128)))
        })
        assertTrue(runCatching { transferQueueJson(oversized) }.isFailure)
    }

    @Test fun groupTimelineDigestIsIndependentOfJsonObjectKeyOrder() {
        val recording = PortableRecording("youtube:abcdefghijk", "Song / live", "Artist/feat", 90_000)
        val timeline = GroupTimeline(GroupRef("00000000-0000-4000-8000-000000000099", 2), 4,
            TransferQueue(listOf(QueueOccurrence("00000000-0000-4000-8000-000000000001", recording)),
                RepeatModeV2.OFF, false), 0, 12_000, true, issuedAtMs = 123_000)
        val wire = groupTimelineJson(timeline)
        val reordered = reverseJson(wire)
        assertEquals(timeline, parseGroupTimeline(timeline.ref.groupId, reordered))
        assertEquals(wire.getString("digest"), reordered.getString("digest"))
    }

    @Test fun canonicalDigestEscapingIsStableAcrossJsonImplementations() {
        val value = JSONObject()
            .put("slash/key", "https://example.test/a/b")
            .put("quote\"", "say \"hi\" \\\n雪 ☃")
            .put("control", "a\u0001b")

        val canonical = """{"control":"a\u0001b","quote\"":"say \"hi\" \\\n雪 ☃","slash/key":"https://example.test/a/b"}"""
        assertEquals(canonical, canonicalJson(value))
        assertEquals("4b16145c21c37c2723e2f49db65aa80c354e5133ad9171cc93c639399ba5c099", jsonDigest(value))
    }

    @Test fun lateGroupActivationUsesFreshEstimatedPositionAndDoesNotStartExpiredSong() {
        val recording = PortableRecording("youtube:abcdefghijk", "Song", "Artist", 90_000)
        val timeline = GroupTimeline(GroupRef("00000000-0000-4000-8000-000000000099", 1), 2,
            TransferQueue(listOf(QueueOccurrence("00000000-0000-4000-8000-000000000001", recording)),
                RepeatModeV2.OFF, false), 0, 12_000, true, issuedAtMs = 100_000)
        assertEquals(17_000L, groupActivationPosition(timeline, 105_000))
        assertEquals(null, groupActivationPosition(timeline, 200_000))
        assertEquals(12_000L, groupActivationPosition(timeline.copy(playing = false), 200_000))
    }

    @Test fun deviceSnapshotAllowsOnlyHashedLocalIdentityForDiscovery() {
        val localId = "local:${"a".repeat(64)}"
        val snapshot = EngineObservation(EngineFence("local-session", 1, 2, 3),
            "00000000-0000-4000-8000-000000000001", 1000, false, true,
            recordingId = localId, title = "Local song", artist = "Local artist", durationMs = 120_000)
        val row = connectedV2DeviceJson("local-device", "Local device", snapshot,
            setOf(CONNECTED_V2_CAPABILITY_MOVE, CONNECTED_V2_CAPABILITY_GROUP))
        row.put("updatedAt", 123_000)
        assertEquals(localId, parseConnectedV2Device("local-device", row).snapshot.recordingId)
        assertTrue(runCatching { validateTransferQueue(TransferQueue(listOf(
            QueueOccurrence("00000000-0000-4000-8000-000000000001",
                PortableRecording(localId, "Local song", "Local artist", 120_000))), RepeatModeV2.OFF, false)) }.isFailure)
    }

    @Test fun transportAddsRealtimeDatabaseJsonSuffixAndUsesEtagCas() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        var nextCode = 200
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val code = nextCode
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message(if (code == 412) "Precondition Failed" else "OK")
                .header("ETag", "\"head-etag\"").body("null".toResponseBody()).build()
        }.build()
        val transport = ConnectedPlaybackV2Transport(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app/", client)
        val result = transport.get(session, "users/owner/playbackV2/devices", etag = true,
            query = mapOf("orderBy" to "\"updatedAt\"", "limitToLast" to "32"))
        assertNull(result.json)
        assertEquals("\"head-etag\"", result.etag)
        val get = requests.single()
        assertTrue(get.url.encodedPath.endsWith("/users/owner/playbackV2/devices.json"))
        assertEquals("synthetic-token", get.url.queryParameter("auth"))
        assertEquals("\"updatedAt\"", get.url.queryParameter("orderBy"))
        assertEquals("32", get.url.queryParameter("limitToLast"))
        assertEquals("true", get.header("X-Firebase-ETag"))
        assertNull(get.header("Authorization"))

        nextCode = 412
        assertFalse(transport.compareAndSet(session, "users/owner/playbackV2/groups/00000000-0000-4000-8000-000000000099/head",
            JSONObject().put("status", "ACTIVE"), "\"old\""))
        assertEquals("\"old\"", requests.last().header("If-Match"))
        assertTrue(runCatching { runBlocking { transport.get(session, "users/other/playbackV2/devices") } }.isFailure)
        assertEquals(2, requests.size)
    }

    private fun reverseJson(value: JSONObject): JSONObject {
        val keys = value.keys().asSequence().toList().asReversed()
        return JSONObject().also { target -> keys.forEach { key ->
            val child = value.get(key)
            target.put(key, when (child) {
                is JSONObject -> reverseJson(child)
                is JSONArray -> JSONArray().also { array -> (0 until child.length()).forEach { index ->
                    val element = child.get(index)
                    array.put(if (element is JSONObject) reverseJson(element) else element)
                } }
                else -> child
            })
        } }
    }
}
