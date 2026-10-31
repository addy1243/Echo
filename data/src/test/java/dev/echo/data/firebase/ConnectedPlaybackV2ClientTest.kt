package dev.echo.data.firebase

import dev.echo.core.connected.EngineCapture
import dev.echo.core.connected.EngineFence
import dev.echo.core.connected.EngineObservation
import dev.echo.core.connected.GroupRef
import dev.echo.core.connected.NativePlaybackBridgeV2
import dev.echo.core.connected.PortableRecording
import dev.echo.core.connected.QueueOccurrence
import dev.echo.core.connected.RepeatModeV2
import dev.echo.core.connected.TransferQueue
import dev.echo.core.connected.CONNECTED_V2_CAPABILITY_GROUP
import dev.echo.core.connected.CONNECTED_V2_CAPABILITY_MOVE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedPlaybackV2ClientTest {
    private val session = FirebaseSession("move-owner", "owner@example.test", "synthetic-token", "refresh", Long.MAX_VALUE)
    private val recording = PortableRecording("youtube:abcdefghijk", "Song", "Artist", 120_000)
    private val queue = TransferQueue(listOf(
        QueueOccurrence("00000000-0000-4000-8000-000000000001", recording),
        QueueOccurrence("00000000-0000-4000-8000-000000000002", recording),
    ), RepeatModeV2.ALL, shuffleEnabled = false)

    @Test fun moveActivatesTargetAndObservesStartBeforeGuardedSourcePause() = runBlocking {
        runMove(activationFails = false)
    }

    @Test fun samePairMovesInBothDirectionsAfterEachObservedHandoff() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val db = FakeRtdb()
        val aBridge = FakeBridge("roundtrip-a-session", queue, initialPlaying = true, events = events)
        val bBridge = FakeBridge("roundtrip-b-session", TransferQueue(emptyList(), RepeatModeV2.OFF, false),
            initialPlaying = false, events = events)
        val http = OkHttpClient.Builder().addInterceptor(db.interceptor).build()
        fun client(id: String, bridge: FakeBridge) = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", id, id,
            sessionProvider = { session.copy(idToken = "token-$id") }, bridge = bridge, httpClient = http)
        val a = client("roundtrip-a", aBridge)
        val b = client("roundtrip-b", bBridge)
        val jobs = listOf(launch { a.run() }, launch { b.run() })
        try {
            withTimeout(8_000) { a.state.first { state -> state.devices.any { it.id == "roundtrip-b" && it.enabled } } }
            a.moveTo("roundtrip-b")
            assertEquals(MoveV2Status.COMPLETE, a.state.value.move?.status)
            assertFalse(aBridge.playing)
            assertTrue(bBridge.playing)
            assertEquals(queue.entries.map { it.id }, bBridge.queue.entries.map { it.id })

            withTimeout(8_000) { b.state.first { state -> state.devices.any { it.id == "roundtrip-a" &&
                it.enabled && !it.snapshot.playing } } }
            b.moveTo("roundtrip-a", replacePlaying = false)
            assertEquals(MoveV2Status.COMPLETE, b.state.value.move?.status)
            assertTrue(aBridge.playing)
            assertFalse(bBridge.playing)
            assertEquals(queue.entries.map { it.id }, aBridge.queue.entries.map { it.id })
            assertEquals(queue.entries.first().id, aBridge.currentOccurrenceId)
            val observations = events.indices.filter { events[it] == "target-observed" }
            val pauses = events.indices.filter { events[it] == "source-paused" }
            assertEquals(2, observations.size)
            assertEquals(2, pauses.size)
            assertTrue(observations[0] < pauses[0])
            assertTrue(observations[1] < pauses[1])
            val operations = db.read("users/${session.uid}/playbackV2/ops")
            assertEquals(2, operations.length())
            assertTrue(operations.keys().asSequence().all { operations.getJSONObject(it).getJSONObject("result")
                .getString("status") == "COMPLETE" })
        } finally {
            a.close(); b.close(); jobs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun startTogetherRejectsChangedTargetSessionBeforeWritingGroupOrInvite() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val db = FakeRtdb()
        val sourceBridge = FakeBridge("session-guard-source", queue, initialPlaying = false, events = events)
        val targetBridge = FakeBridge("session-guard-target-current", TransferQueue(emptyList(), RepeatModeV2.OFF, false),
            initialPlaying = false, events = events)
        val http = OkHttpClient.Builder().addInterceptor(db.interceptor).build()
        fun client(id: String, bridge: FakeBridge) = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", id, id,
            sessionProvider = { session.copy(idToken = "token-$id") }, bridge = bridge, httpClient = http)
        val source = client("session-guard-source", sourceBridge)
        val target = client("session-guard-target", targetBridge)
        val jobs = listOf(launch { source.run() }, launch { target.run() })
        try {
            withTimeout(8_000) { source.state.first { state ->
                state.devices.any { it.id == "session-guard-target" && it.enabled }
            } }
            val result = runCatching {
                source.startTogether(listOf("session-guard-target"), expectedTargetSessions =
                    mapOf("session-guard-target" to "session-guard-target-stale"))
            }
            assertTrue("A restarted selected device must be reselected before group creation", result.isFailure)
            assertNull(db.readOrNull("users/${session.uid}/playbackV2/groups"))
            assertNull(db.readOrNull("users/${session.uid}/playbackV2/inbox/session-guard-target"))
            assertEquals("session-guard-target-current", targetBridge.sessionId)
        } finally {
            source.close(); target.close(); jobs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun groupsSupportEightMembersRejectNineAndKeepThreeMemberGroupAfterOneFollowerLeaves() = runBlocking {
        val db = FakeRtdb()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val http = OkHttpClient.Builder().addInterceptor(db.interceptor).build()
        fun client(id: String, bridge: FakeBridge) = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", id, id,
            sessionProvider = { session.copy(idToken = "token-$id") }, bridge = bridge, httpClient = http)
        val source = client("multi-source", FakeBridge("multi-source-session", queue, false, events))
        val targets = (1..8).map { index ->
            val id = "multi-target-$index"
            id to client(id, FakeBridge("multi-target-session-$index",
                TransferQueue(emptyList(), RepeatModeV2.OFF, false), false, events))
        }
        val allClients = listOf(source) + targets.map { it.second }
        val jobs = allClients.map { launch { it.run() } }
        try {
            withTimeout(12_000) {
                source.state.first { state -> targets.all { (id, _) -> state.devices.any { it.id == id && it.enabled } } }
            }
            val before = db.readOrNull("users/${session.uid}/playbackV2/groups")
            val nineAttempt = runCatching { source.startTogether(targets.map { it.first }) }
            assertTrue("A coordinator plus eight targets exceeds the eight-member group cap", nineAttempt.isFailure)
            assertEquals(before?.toString(), db.readOrNull("users/${session.uid}/playbackV2/groups")?.toString())

            withTimeout(35_000) { source.startTogether(targets.take(7).map { it.first }) }
            assertEquals(8, source.state.value.group?.members?.size)
            assertEquals(GroupV2Status.ACTIVE, source.state.value.group?.status)

            val selected = targets.take(2)
            // Use a fresh three-member group after the eight-member boundary check.
            source.leaveTogether()
            withTimeout(12_000) { source.state.first { it.group == null } }
            withTimeout(12_000) { allClients.forEach { it.state.first { state -> state.group == null } } }
            withTimeout(35_000) { source.startTogether(selected.map { it.first }) }
            assertEquals(3, source.state.value.group?.members?.size)
            assertEquals(GroupV2Status.ACTIVE, source.state.value.group?.status)
            selected.first().second.leaveTogether()
            withTimeout(12_000) {
                source.state.first { state -> state.group?.status == GroupV2Status.ACTIVE &&
                    state.group.members.count { it.status !in setOf(GroupMemberV2Status.LEFT, GroupMemberV2Status.DISCONNECTED) } == 2 }
            }
            assertTrue("The remaining follower stays in the shared group", selected.last().second.state.value.group != null)
            val oldRef = source.state.value.group!!.ref
            val groupPath = "users/${session.uid}/playbackV2/groups/${oldRef.groupId}"
            val expiredAt = System.currentTimeMillis() - 1
            db.seed("$groupPath/head", db.read("$groupPath/head").put("leaseUntil", expiredAt))
            selected.last().second.takeOverTogether()
            withTimeout(20_000) {
                source.state.first { it.group?.ref?.epoch == oldRef.epoch + 1 && it.group.status == GroupV2Status.ACTIVE }
            }
            withTimeout(20_000) {
                selected.last().second.state.first { it.group?.ref?.epoch == oldRef.epoch + 1 && it.group.status == GroupV2Status.ACTIVE }
            }
            val leftRow = db.read("$groupPath/members/${selected.first().first}")
            assertEquals(oldRef.epoch, leftRow.getLong("epoch"))
            assertEquals(GroupMemberV2Status.LEFT.name, leftRow.getString("status"))
            assertEquals(2, source.state.value.group?.members?.size)
            assertTrue("Takeover must not re-invite the prior leaver", selected.first().second.state.value.group == null)
        } finally {
            allClients.forEach { runCatching { it.leaveTogether() } }
            allClients.forEach { it.close() }
            jobs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun inviteToDifferentGroupCannotEvictAnOccupiedPlayer() = runBlocking {
        val db = FakeRtdb()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val http = OkHttpClient.Builder().addInterceptor(db.interceptor).build()
        fun client(id: String, bridge: FakeBridge) = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", id, id,
            sessionProvider = { session.copy(idToken = "token-$id") }, bridge = bridge, httpClient = http)
        val a = client("occupied-a", FakeBridge("occupied-a-session", queue, false, events))
        val b = client("occupied-b", FakeBridge("occupied-b-session", TransferQueue(emptyList(), RepeatModeV2.OFF, false), false, events))
        val c = client("occupied-c", FakeBridge("occupied-c-session", queue, false, events))
        val clients = listOf(a, b, c)
        val jobs = clients.map { launch { it.run() } }
        try {
            withTimeout(8_000) { a.state.first { it.devices.any { device -> device.id == "occupied-b" && device.enabled } } }
            withTimeout(8_000) { c.state.first { it.devices.any { device -> device.id == "occupied-b" && device.enabled } } }
            a.startTogether(listOf("occupied-b"))
            withTimeout(15_000) { a.state.first { it.group?.status == GroupV2Status.ACTIVE } }
            val originalRef = a.state.value.group!!.ref
            withTimeout(8_000) { b.state.first { it.group?.ref == originalRef && it.group?.status == GroupV2Status.ACTIVE } }

            val second = runCatching { c.startTogether(listOf("occupied-b"), replacePlaying = true) }
            assertTrue("A device already in another group cannot be silently moved", second.isFailure)
            assertEquals(originalRef, b.state.value.group?.ref)
            assertEquals(originalRef, a.state.value.group?.ref)
            assertNull(c.state.value.group)
            assertEquals(GroupV2Status.ACTIVE, a.state.value.group?.status)
            val secondGroup = db.readOrNull("users/${session.uid}/playbackV2/groups")!!
            val rejectedHead = secondGroup.keys().asSequence().mapNotNull { id ->
                db.readOrNull("users/${session.uid}/playbackV2/groups/$id/head")
            }.first { it.optString("status") == GroupV2Status.CLOSED.name }
            assertEquals(GroupV2Status.CLOSED.name, rejectedHead.getString("status"))
        } finally {
            clients.forEach { runCatching { it.leaveTogether() } }
            clients.forEach { it.close() }
            jobs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun incomingMoveWaitsForLocalGroupPreparationToReleaseTheEngine() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val db = FakeRtdb()
        val aBridge = FakeBridge("conflict-a-session", queue, initialPlaying = true, events = events,
            failConcurrentPreparation = true).apply { prepareDelayMs = 1_200 }
        val bBridge = FakeBridge("conflict-b-session", queue, initialPlaying = true, events = events,
            failConcurrentPreparation = true)
        val cBridge = FakeBridge("conflict-c-session", queue, initialPlaying = true, events = events,
            failConcurrentPreparation = true)
        val http = OkHttpClient.Builder().addInterceptor(db.interceptor).build()
        fun client(id: String, bridge: FakeBridge) = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", id, id,
            sessionProvider = { session.copy(idToken = "token-$id") }, bridge = bridge, httpClient = http)
        val a = client("conflict-a", aBridge)
        val b = client("conflict-b", bBridge)
        val c = client("conflict-c", cBridge)
        val jobs = listOf(launch { a.run() }, launch { b.run() }, launch { c.run() })
        try {
            withTimeout(8_000) { a.state.first { it.devices.any { device -> device.id == "conflict-b" && device.enabled } } }
            withTimeout(8_000) { c.state.first { it.devices.any { device -> device.id == "conflict-a" && device.enabled } } }
            val groupAttempt = async { runCatching { a.startTogether(listOf("conflict-b"), replacePlaying = true) } }
            awaitCondition(5_000) { events.contains("conflict-a-session-prepare-started") }
            val moveAttempt = async { runCatching { c.moveTo("conflict-a", replacePlaying = true) } }
            assertTrue("Group preparation should finish with its invited peer", withTimeout(20_000) { groupAttempt.await() }.isSuccess)
            moveAttempt.await().getOrThrow()
            assertEquals("The group stage owns A, so the unrelated Move must be rejected.",
                MoveV2Status.FAILED, c.state.value.move?.status)
            assertEquals("target_in_together", c.state.value.move?.reason)
            assertEquals(GroupV2Status.ACTIVE, a.state.value.group?.status)
            assertEquals(GroupV2Status.ACTIVE, b.state.value.group?.status)
            assertNull(c.state.value.group)
            assertTrue(aBridge.playing)
            assertTrue(bBridge.playing)
            assertTrue(cBridge.playing)
            assertEquals(queue.entries.map { it.id }, aBridge.queue.entries.map { it.id })
            assertEquals(queue.entries.map { it.id }, bBridge.queue.entries.map { it.id })
            assertEquals(queue.entries.map { it.id }, cBridge.queue.entries.map { it.id })
        } finally {
            a.close(); b.close(); c.close(); jobs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun targetActivationFailureLeavesSourcePlaying() = runBlocking {
        runMove(activationFails = true)
    }

    @Test fun observedAckFromWrongTargetSessionCannotAuthorizeSourcePause() = runBlocking {
        runMove(activationFails = false, corruptObservedAckSession = true)
    }

    @Test fun sourceReconcilesLateTargetActivationAfterPublishingUnknown() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val db = FakeRtdb()
        val sourceBridge = FakeBridge("late-source-session", queue, initialPlaying = true, events = events)
        val targetBridge = FakeBridge("late-target-session", TransferQueue(emptyList(), RepeatModeV2.OFF, false),
            initialPlaying = false, events = events).apply { activateDelayMs = 5_000 }
        val http = OkHttpClient.Builder().addInterceptor(db.interceptor).build()
        val source = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", "late-source", "late-source",
            sessionProvider = { session.copy(idToken = "token-late-source") }, bridge = sourceBridge, httpClient = http,
            moveWaitTimeoutMs = 2_000)
        val target = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", "late-target", "late-target",
            sessionProvider = { session.copy(idToken = "token-late-target") }, bridge = targetBridge, httpClient = http)
        val jobs = listOf(launch { source.run() }, launch { target.run() })
        try {
            withTimeout(8_000) { source.state.first { state -> state.devices.any { it.id == "late-target" && it.enabled } } }
            source.moveTo("late-target")
            assertEquals(MoveV2Status.UNKNOWN, source.state.value.move?.status)
            assertTrue(sourceBridge.playing)
            awaitCondition(10_000) {
                source.state.value.move?.status == MoveV2Status.COMPLETE && !sourceBridge.playing && targetBridge.playing
            }
            assertEquals(queue.entries.first().id, targetBridge.currentOccurrenceId)
            assertTrue(events.indexOf("target-observed") >= 0)
            assertTrue(events.indexOf("target-observed") < events.indexOf("source-paused"))
        } finally {
            source.close(); target.close(); jobs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun togetherCoordinatesDuplicateTraversalTransportRejoinTakeoverAndPausedRestore() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val db = FakeRtdb()
        val sharedQueue = queue.copy(entries = listOf(
            queue.entries[0], queue.entries[1], QueueOccurrence("00000000-0000-4000-8000-000000000003", recording)),
            repeatMode = RepeatModeV2.ALL)
        val targetRecording = PortableRecording("youtube:lmnopqrstuv", "Independent", "Target artist", 180_000)
        val targetQueue = TransferQueue(listOf(
            QueueOccurrence("00000000-0000-4000-8000-000000000101", targetRecording)), RepeatModeV2.OFF, false)
        val sourceBridge = FakeBridge("source-session", sharedQueue, initialPlaying = true, events = events).apply {
            setPlayback(42_000, true)
        }
        val targetBridge = FakeBridge("target-session", targetQueue, initialPlaying = false, events = events)
        val http = OkHttpClient.Builder().addInterceptor(db.interceptor).build()
        fun client(id: String, bridge: FakeBridge) = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", id, id,
            sessionProvider = { session.copy(idToken = "token-$id") }, bridge = bridge, httpClient = http)
        val source = client("source-device", sourceBridge)
        val target = client("target-device", targetBridge)
        val jobs = listOf(launch { source.run() }, launch { target.run() })
        try {
            withTimeout(8_000) { source.state.first { state -> state.devices.any { it.id == "target-device" && it.enabled } } }
            withTimeout(20_000) { source.startTogether(listOf("target-device")) }
            withTimeout(8_000) { source.state.first { it.group?.status == GroupV2Status.ACTIVE && it.group.timeline?.sequence == 1L } }
            withTimeout(8_000) { target.state.first { it.group?.status == GroupV2Status.ACTIVE } }
            val groupPath = "users/${session.uid}/playbackV2/groups/${source.state.value.group!!.ref.groupId}"
            val initial = source.state.value.group!!.timeline!!
            assertEquals(sharedQueue.entries.map { it.id }, initial.queue.entries.map { it.id })
            assertEquals(sharedQueue.entries[0].id, sourceBridge.currentOccurrenceId)
            assertEquals(sharedQueue.entries[0].id, targetBridge.currentOccurrenceId)
            assertTrue(sourceBridge.playing && targetBridge.playing)
            assertEquals("The coordinator should activate its already-ready stage without a second media fetch.", 1,
                events.count { it == "source-session-prepared" })
            assertEquals("The invited target should activate its already-ready stage without a second media fetch.", 1,
                events.count { it == "target-session-prepared" })
            assertEquals(targetQueue.entries.map { it.id }, targetBridge.displacedQueue()!!.entries.map { it.id })

            sourceBridge.setPlayback(50_000, true)
            target.requestGroup(GroupActionV2.PAUSE)
            awaitCondition(8_000) {
                val timeline = source.state.value.group?.timeline
                timeline != null && timeline.sequence > initial.sequence && !timeline.playing &&
                    sourceBridge.positionMs == 50_000L && targetBridge.positionMs == 50_000L &&
                    !sourceBridge.playing && !targetBridge.playing
            }
            awaitCondition(5_000) { !source.state.value.pending && !target.state.value.pending }
            val paused = source.state.value.group!!.timeline!!
            assertEquals(50_000L, paused.positionMs)

            source.requestGroup(GroupActionV2.PLAY)
            awaitCondition(8_000) { source.state.value.group?.timeline?.playing == true && sourceBridge.playing && targetBridge.playing }
            awaitCondition(5_000) { !source.state.value.pending && !target.state.value.pending }
            target.requestGroup(GroupActionV2.SEEK, 64_000)
            val seekApplied = runCatching { awaitCondition(8_000) {
                val timeline = source.state.value.group?.timeline ?: return@awaitCondition false
                val expectedPosition = groupActivationPosition(timeline, System.currentTimeMillis()) ?: return@awaitCondition false
                timeline.positionMs == 64_000L && timeline.cursor == 0 &&
                    kotlin.math.abs(sourceBridge.positionMs - expectedPosition) <= 2_000L &&
                    kotlin.math.abs(targetBridge.positionMs - expectedPosition) <= 2_000L
            } }.isSuccess
            val seekRequests = db.readOrNull("$groupPath/requests")
            assertTrue("Seek was not observed. source=${source.state.value}; target=${target.state.value}; requests=$seekRequests", seekApplied)
            awaitCondition(5_000) { !source.state.value.pending && !target.state.value.pending }
            assertEquals(null, source.state.value.message)
            sourceBridge.setPlayback(78_000, true)
            targetBridge.setPlayback(76_000, true)
            target.requestGroup(GroupActionV2.PAUSE)
            awaitCondition(8_000) {
                source.state.value.group?.timeline?.positionMs == 78_000L &&
                    source.state.value.group?.timeline?.playing == false && sourceBridge.positionMs == 78_000L &&
                    targetBridge.positionMs == 78_000L && !sourceBridge.playing && !targetBridge.playing
            }
            awaitCondition(5_000) { !source.state.value.pending && !target.state.value.pending }

            target.requestGroup(GroupActionV2.NEXT)
            awaitCondition(8_000) {
                source.state.value.group?.timeline?.cursor == 1 &&
                    sourceBridge.currentOccurrenceId == sharedQueue.entries[1].id &&
                    targetBridge.currentOccurrenceId == sharedQueue.entries[1].id
            }
            awaitCondition(5_000) { !source.state.value.pending && !target.state.value.pending }
            assertEquals(sharedQueue.entries.map { it.id }, source.state.value.group!!.timeline!!.queue.entries.map { it.id })
            sourceBridge.finishCurrent()
            awaitCondition(8_000) {
                source.state.value.group?.timeline?.cursor == 2 &&
                    sourceBridge.currentOccurrenceId == sharedQueue.entries[2].id &&
                    targetBridge.currentOccurrenceId == sharedQueue.entries[2].id
            }

            db.failToken = "token-target-device"
            source.requestGroup(GroupActionV2.NEXT)
            awaitCondition(8_000) { source.state.value.group?.timeline?.cursor == 0 }
            assertEquals(sharedQueue.entries[2].id, targetBridge.currentOccurrenceId)
            db.failToken = null
            awaitCondition(10_000) { targetBridge.currentOccurrenceId == sharedQueue.entries[0].id && targetBridge.playing }

            val oldGroup = source.state.value.group!!
            val oldTimeline = oldGroup.timeline!!
            val staleRequestId = java.util.UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            db.seed("$groupPath/requests/$staleRequestId", groupRequestJson(staleRequestId, "target-device",
                targetBridge.sessionId, oldGroup.ref.epoch, oldTimeline.sequence, GroupActionV2.PAUSE, 0,
                now, now + 20_000))
            val headPath = "$groupPath/head"
            db.seed(headPath, db.read(headPath).put("leaseUntil", now - 1))
            target.takeOverTogether()
            awaitCondition(8_000) { source.state.value.group?.ref?.epoch == oldGroup.ref.epoch + 1 }
            assertFalse("A prior-epoch player pauses and restores its private queue before joining takeover.", sourceBridge.playing)
            awaitCondition(20_000) {
                val timeline = target.state.value.group?.timeline
                target.state.value.group?.status == GroupV2Status.ACTIVE && target.state.value.group?.ref?.epoch == oldGroup.ref.epoch + 1 &&
                    timeline != null && timeline.cursor == oldTimeline.cursor &&
                    source.state.value.group?.ref?.epoch == oldGroup.ref.epoch + 1
            }
            awaitCondition(8_000) { db.readOrNull("$groupPath/requests/$staleRequestId") == null }
            assertEquals(sharedQueue.entries[0].id, sourceBridge.currentOccurrenceId)
            assertEquals(sharedQueue.entries[0].id, targetBridge.currentOccurrenceId)
            assertFalse(sourceBridge.playing || targetBridge.playing)

            target.leaveTogether()
            awaitCondition(8_000) { source.state.value.group == null }
            assertEquals(sharedQueue.entries.map { it.id }, sourceBridge.queue.entries.map { it.id })
            assertEquals(sharedQueue.entries[0].id, sourceBridge.currentOccurrenceId)
            assertEquals(42_000L, sourceBridge.positionMs)
            assertFalse(sourceBridge.playing)
            assertEquals(targetQueue.entries.map { it.id }, targetBridge.queue.entries.map { it.id })
            assertEquals(targetQueue.entries[0].id, targetBridge.currentOccurrenceId)
            assertEquals(12_000L, targetBridge.positionMs)
            assertFalse(targetBridge.playing)
        } finally {
            source.close(); target.close(); jobs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun nextRequestWaitsForCoordinatorToObserveTheJustCommittedPlayTimeline() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val db = FakeRtdb()
        val sourceBridge = FakeBridge("fast-source-session", queue, initialPlaying = false, events = events)
        val targetBridge = FakeBridge("fast-target-session", TransferQueue(emptyList(), RepeatModeV2.OFF, false),
            initialPlaying = false, events = events)
        val http = OkHttpClient.Builder().addInterceptor(db.interceptor).build()
        fun client(id: String, bridge: FakeBridge) = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", id, id,
            sessionProvider = { session.copy(idToken = "token-$id") }, bridge = bridge, httpClient = http)
        val source = client("fast-source", sourceBridge)
        val target = client("fast-target", targetBridge)
        val jobs = listOf(launch { source.run() }, launch { target.run() })
        try {
            withTimeout(8_000) { source.state.first { state -> state.devices.any { it.id == "fast-target" && it.enabled } } }
            withTimeout(20_000) { source.startTogether(listOf("fast-target")) }
            withTimeout(8_000) { source.state.first { it.group?.status == GroupV2Status.ACTIVE } }
            val groupPath = "users/${session.uid}/playbackV2/groups/${source.state.value.group!!.ref.groupId}"
            val initialSequence = source.state.value.group!!.timeline!!.sequence
            sourceBridge.groupTransportDelayMs = 1_500

            source.requestGroup(GroupActionV2.PLAY)
            withTimeout(8_000) {
                source.state.first { it.group?.timeline?.let { timeline ->
                    timeline.sequence == initialSequence + 1 && timeline.playing
                } == true && !it.pending }
            }
            assertEquals(initialSequence, db.read("$groupPath/members/fast-source").getLong("lastAppliedSeq"))

            source.requestGroup(GroupActionV2.NEXT)
            var nextRequestId: String? = null
            withTimeout(3_000) {
                while (nextRequestId == null) {
                    val requests = db.readOrNull("$groupPath/requests")
                    if (requests != null && requests.length() == 1) nextRequestId = requests.keys().next()
                    delay(10)
                }
            }
            val queuedNextRequestId = requireNotNull(nextRequestId)
            withTimeout(8_000) {
                while (!events.contains("fast-source-session-group-transport-start")) delay(10)
            }
            assertTrue("A current-sequence request must remain queued while the coordinator catches up",
                db.readOrNull("$groupPath/requests")?.has(queuedNextRequestId) == true)
            withTimeout(12_000) {
                source.state.first { state -> state.group?.timeline?.let { it.cursor == 1 } == true &&
                    sourceBridge.currentOccurrenceId == queue.entries[1].id && targetBridge.currentOccurrenceId == queue.entries[1].id }
            }
            awaitCondition(5_000) { db.readOrNull("$groupPath/requests")?.has(queuedNextRequestId) != true }
        } finally {
            source.close(); target.close(); jobs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun togetherRenewsPreparingLeaseDuringSlowNativeStage() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val db = FakeRtdb()
        val sourceBridge = FakeBridge("slow-source-session", queue, initialPlaying = true, events = events,
            failConcurrentPreparation = true, captureRejectsWhenPrepared = true).apply { prepareDelayMs = 17_000 }
        val serializedSourceBridge = SerializedPrepareBridge(sourceBridge)
        val targetBridge = FakeBridge("slow-target-session", TransferQueue(emptyList(), RepeatModeV2.OFF, false),
            initialPlaying = false, events = events).apply { prepareDelayMs = 18_000 }
        val http = OkHttpClient.Builder().addInterceptor(db.interceptor).build()
        fun client(id: String, bridge: NativePlaybackBridgeV2) = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", id, id,
            sessionProvider = { session.copy(idToken = "token-$id") }, bridge = bridge, httpClient = http)
        val source = client("slow-source", serializedSourceBridge)
        val target = client("slow-target", targetBridge)
        val jobs = listOf(launch { source.run() }, launch { target.run() })
        try {
            withTimeout(8_000) { source.state.first { state -> state.devices.any { it.id == "slow-target" && it.enabled } } }
            val starting = kotlinx.coroutines.CompletableDeferred<Pair<String, Long>>()
            val startJob = launch {
                source.startTogether(listOf("slow-target"))
            }
            awaitCondition(4_000) {
                val row = db.readOrNull("users/${session.uid}/playbackV2/groups")
                if (row == null) false else {
                    val groupId = row.keys().next()
                    val head = db.read("users/${session.uid}/playbackV2/groups/$groupId/head")
                    if (head.optString("status") == "PREPARING") {
                        if (!starting.isCompleted) starting.complete(groupId to head.getLong("leaseUntil"))
                        true
                    } else false
                }
            }
            val (groupId, initialLease) = starting.await()
            awaitCondition(14_000) {
                val head = db.read("users/${session.uid}/playbackV2/groups/$groupId/head")
                if (head.getLong("leaseUntil") > initialLease) {
                    assertTrue("Lease renewal must happen while the native prepare still owns its dispatcher",
                        sourceBridge.isPreparing())
                    assertTrue("Play together remains pending while either player is staging", source.state.value.pending)
                    true
                } else false
            }
            assertTrue("The run-loop observation must queue behind the serial native preparation",
                serializedSourceBridge.observationQueuedDuringPrepare.get())
            withTimeout(45_000) { startJob.join() }
            val activeHead = db.read("users/${session.uid}/playbackV2/groups/$groupId/head")
            assertTrue("PREPARING lease must be renewed while native staging exceeds the 15 second lease",
                activeHead.getLong("leaseUntil") >= initialLease)
            assertEquals(GroupV2Status.ACTIVE, source.state.value.group?.status)
        } finally {
            source.close(); target.close(); jobs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun preparationTimeoutRacingActiveHeadClosesAndReconcilesLateNativeActivation() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val db = FakeRtdb()
        val prepareTimeoutMs = 6_000L
        val sourceActivationGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val targetActivationGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val sourceActivationStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val targetActivationStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val sourceBridge = FakeBridge("timeout-source-session", queue, initialPlaying = false, events = events).apply {
            activateGate = sourceActivationGate
            activateStarted = sourceActivationStarted
        }
        val targetQueue = TransferQueue(listOf(QueueOccurrence("00000000-0000-4000-8000-000000000301",
            PortableRecording("youtube:lmnopqrstuv", "Independent", "Artist", 180_000))), RepeatModeV2.OFF, false)
        val targetBridge = FakeBridge("timeout-target-session", targetQueue, initialPlaying = false, events = events).apply {
            activateGate = targetActivationGate
            activateStarted = targetActivationStarted
        }
        val http = OkHttpClient.Builder().addInterceptor(db.interceptor).build()
        fun client(id: String, bridge: FakeBridge) = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", id, id,
            sessionProvider = { session.copy(idToken = "token-$id") }, bridge = bridge, httpClient = http,
            groupPrepareTimeoutMs = prepareTimeoutMs)
        val source = client("timeout-source", sourceBridge)
        val target = client("timeout-target", targetBridge)
        val jobs = listOf(launch { source.run() }, launch { target.run() })
        var startJob: kotlinx.coroutines.Job? = null
        try {
            withTimeout(8_000) { source.state.first { state -> state.devices.any { it.id == "timeout-target" && it.enabled } } }
            val outcome = kotlinx.coroutines.CompletableDeferred<Throwable?>()
            startJob = launch { outcome.complete(runCatching { source.startTogether(listOf("timeout-target")) }.exceptionOrNull()) }
            awaitCondition(8_000) {
                val groups = db.readOrNull("users/${session.uid}/playbackV2/groups")
                groups?.keys()?.asSequence()?.any { id ->
                    db.read("users/${session.uid}/playbackV2/groups/$id/head").optString("status") == "ACTIVE"
                } == true
            }
            // Hold both native activations after ACTIVE is committed. Let the caller's
            // preparation deadline expire, then release native work so CLOSED wins and
            // any late activation is reconciled against the original queues.
            withTimeout(8_000) { sourceActivationStarted.await() }
            withTimeout(8_000) { targetActivationStarted.await() }
            delay(prepareTimeoutMs + 200)
            sourceActivationGate.complete(Unit)
            targetActivationGate.complete(Unit)
            assertTrue("The caller should not report success when readiness acknowledgements miss the deadline.",
                withTimeout(15_000) { outcome.await() } != null)
            awaitCondition(10_000) {
                val groups = db.readOrNull("users/${session.uid}/playbackV2/groups") ?: return@awaitCondition false
                val closed = groups.keys().asSequence().any { id ->
                    db.read("users/${session.uid}/playbackV2/groups/$id/head").optString("status") == "CLOSED"
                }
                closed && source.state.value.group == null && target.state.value.group == null &&
                    !sourceBridge.playing && !targetBridge.playing && sourceBridge.queue.entries.map { it.id } == queue.entries.map { it.id } &&
                    targetBridge.queue.entries.map { it.id } == targetQueue.entries.map { it.id }
            }
        } finally {
            sourceActivationGate.complete(Unit)
            targetActivationGate.complete(Unit)
            startJob?.cancelAndJoin()
            source.close(); target.close(); jobs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun followerCanCancelItsNativeStageDuringPreparingWithoutActivating() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val db = FakeRtdb()
        val sourceObserveGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val sourceObserveStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val sourceBridge = FakeBridge("preparing-leave-source-session", queue, initialPlaying = false, events = events).apply {
            observeGate = sourceObserveGate
            observeStarted = sourceObserveStarted
        }
        val targetQueue = TransferQueue(listOf(QueueOccurrence("00000000-0000-4000-8000-000000000401",
            PortableRecording("youtube:lmnopqrstuv", "Independent", "Artist", 180_000))), RepeatModeV2.OFF, false)
        val targetBridge = FakeBridge("preparing-leave-target-session", targetQueue, initialPlaying = false, events = events)
        val http = OkHttpClient.Builder().addInterceptor(db.interceptor).build()
        fun client(id: String, bridge: FakeBridge) = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", id, id,
            sessionProvider = { session.copy(idToken = "token-$id") }, bridge = bridge, httpClient = http,
            groupPrepareTimeoutMs = 30_000)
        val source = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", "preparing-leave-source", "preparing-leave-source",
            sessionProvider = { session.copy(idToken = "token-preparing-leave-source") }, bridge = sourceBridge, httpClient = http,
            groupPrepareTimeoutMs = 30_000, testDiagnosticLogger = { line ->
                if (line.contains("event=coordinator_ready_check") && line.contains("members=READY, READY")) {
                    sourceBridge.blockNextPreparedObservation = true
                }
            })
        val target = client("preparing-leave-target", targetBridge)
        val jobs = listOf(launch { source.run() }, launch { target.run() })
        var startJob: kotlinx.coroutines.Job? = null
        try {
            withTimeout(8_000) { source.state.first { state -> state.devices.any { it.id == "preparing-leave-target" && it.enabled } } }
            val outcome = kotlinx.coroutines.CompletableDeferred<Throwable?>()
            startJob = launch { outcome.complete(runCatching { source.startTogether(listOf("preparing-leave-target")) }.exceptionOrNull()) }
            withTimeout(8_000) { sourceObserveStarted.await() }
            val groupId = withTimeout(5_000) {
                while (true) db.readOrNull("users/${session.uid}/playbackV2/groups")?.keys()?.asSequence()?.firstOrNull()
                    ?.let { return@withTimeout it }
                delay(25)
                ""
            }
            assertTrue(groupId.isNotBlank())
            assertEquals(GroupV2Status.PREPARING.name,
                db.read("users/${session.uid}/playbackV2/groups/$groupId/head").getString("status"))
            assertTrue("Coordinator's native-ready check must serialize before cancellation",
                db.read("users/${session.uid}/playbackV2/groups/$groupId/members").let { members ->
                    members.getJSONObject("preparing-leave-source").getString("status") == GroupMemberV2Status.READY.name &&
                        members.getJSONObject("preparing-leave-target").getString("status") == GroupMemberV2Status.READY.name
                })

            target.leaveTogether()
            assertNull(target.state.value.group)
            assertEquals(targetQueue.entries.map { it.id }, targetBridge.queue.entries.map { it.id })
            assertFalse(targetBridge.playing)
            assertNull(targetBridge.displacedQueue())
            assertEquals(GroupV2Status.CLOSED.name,
                db.read("users/${session.uid}/playbackV2/groups/$groupId/head").getString("status"))
            sourceObserveGate.complete(Unit)
            assertTrue("Coordinator startup must abort after a selected follower leaves preparation",
                withTimeout(20_000) { outcome.await() } != null)
            awaitCondition(8_000) {
                db.read("users/${session.uid}/playbackV2/groups/$groupId/head").getString("status") == GroupV2Status.CLOSED.name &&
                    source.state.value.group == null && !sourceBridge.playing && sourceBridge.queue.entries.map { it.id } == queue.entries.map { it.id }
            }
        } finally {
            sourceObserveGate.complete(Unit)
            startJob?.cancelAndJoin()
            source.close(); target.close(); jobs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun failedCancellationCloseCannotActivateThePreparedGroupAfterReconnect() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val db = FakeRtdb()
        val sourceBridge = FakeBridge("cancel-source-session", queue, initialPlaying = true, events = events)
        val targetBridge = FakeBridge("cancel-target-session", TransferQueue(emptyList(), RepeatModeV2.OFF, false),
            initialPlaying = false, events = events).apply { prepareDelayMs = 3_000 }
        val http = OkHttpClient.Builder().addInterceptor(db.interceptor).build()
        fun client(id: String, bridge: FakeBridge) = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", id, id,
            sessionProvider = { session.copy(idToken = "token-$id") }, bridge = bridge, httpClient = http)
        val source = client("cancel-source", sourceBridge)
        val target = client("cancel-target", targetBridge)
        val jobs = listOf(launch { source.run() }, launch { target.run() })
        try {
            withTimeout(8_000) { source.state.first { state -> state.devices.any { it.id == "cancel-target" && it.enabled } } }
            val start = launch { source.startTogether(listOf("cancel-target")) }
            awaitCondition(5_000) { events.contains("cancel-source-session-prepare-started") }
            val groupId = withTimeout(5_000) {
                while (true) db.readOrNull("users/${session.uid}/playbackV2/groups")?.keys()?.asSequence()?.firstOrNull()
                    ?.let { return@withTimeout it }
                delay(25)
                ""
            }
            assertTrue(groupId.isNotBlank())
            db.failAllRequests = true
            start.cancelAndJoin()
            db.failAllRequests = false

            awaitCondition(10_000) {
                db.read("users/${session.uid}/playbackV2/groups/$groupId/head").optString("status") == "CLOSED" &&
                    source.state.value.group == null && target.state.value.group == null
            }
            assertTrue(sourceBridge.playing)
            assertFalse(targetBridge.playing)
            assertEquals(queue.entries.map { it.id }, sourceBridge.queue.entries.map { it.id })
            assertTrue(targetBridge.queue.entries.isEmpty())
            assertFalse(events.contains("target-observed"))
        } finally {
            db.failAllRequests = false
            source.close(); target.close(); jobs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun followerLeaveClosesGroupEvenIfLeftMemberRowArrivesBeforeRequest() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val db = FakeRtdb()
        val targetQueue = TransferQueue(listOf(QueueOccurrence(
            "00000000-0000-4000-8000-000000000101",
            PortableRecording("youtube:lmnopqrstuv", "Independent", "Artist", 180_000))), RepeatModeV2.OFF, false)
        val sourceBridge = FakeBridge("leave-source-session", queue, initialPlaying = true, events = events)
        val targetBridge = FakeBridge("leave-target-session", targetQueue, initialPlaying = false, events = events)
        val http = OkHttpClient.Builder().addInterceptor(db.interceptor).build()
        fun client(id: String, bridge: FakeBridge) = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", id, id,
            sessionProvider = { session.copy(idToken = "token-$id") }, bridge = bridge, httpClient = http)
        val source = client("leave-source", sourceBridge)
        val target = client("leave-target", targetBridge)
        val jobs = listOf(launch { source.run() }, launch { target.run() })
        try {
            withTimeout(8_000) { source.state.first { state -> state.devices.any { it.id == "leave-target" && it.enabled } } }
            withTimeout(20_000) { source.startTogether(listOf("leave-target")) }
            withTimeout(8_000) { source.state.first { it.group?.status == GroupV2Status.ACTIVE && it.group.timeline?.sequence == 1L } }
            val groupPath = "users/${session.uid}/playbackV2/groups/${source.state.value.group!!.ref.groupId}"
            db.failToken = "token-leave-source"
            target.requestGroup(GroupActionV2.PAUSE)
            assertTrue(target.state.value.pending)
            target.leaveTogether()
            assertFalse(target.state.value.pending)
            val member = db.read("$groupPath/members/leave-target")
            assertEquals(GroupMemberV2Status.LEFT.name, member.getString("status"))
            db.failToken = null
            awaitCondition(10_000) {
                source.state.value.group == null &&
                    db.read("$groupPath/head").getString("status") == GroupV2Status.CLOSED.name
            }
            assertEquals(queue.entries.map { it.id }, sourceBridge.queue.entries.map { it.id })
            assertEquals(targetQueue.entries.map { it.id }, targetBridge.queue.entries.map { it.id })
            assertFalse(sourceBridge.playing || targetBridge.playing)
        } finally {
            source.close(); target.close(); jobs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun expiredGroupActionClearsPendingInsteadOfLeavingControlsStuck() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val db = FakeRtdb()
        val sourceBridge = FakeBridge("expiry-source-session", queue, initialPlaying = true, events = events)
        val targetBridge = FakeBridge("expiry-target-session", TransferQueue(emptyList(), RepeatModeV2.OFF, false),
            initialPlaying = false, events = events)
        val http = OkHttpClient.Builder().addInterceptor(db.interceptor).build()
        fun client(id: String, bridge: FakeBridge, requestTtl: Long = 20_000) = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", id, id,
            sessionProvider = { session.copy(idToken = "token-$id") }, bridge = bridge, httpClient = http,
            groupRequestTtlMs = requestTtl)
        val source = client("expiry-source", sourceBridge)
        val target = client("expiry-target", targetBridge, requestTtl = 1_000)
        val jobs = listOf(launch { source.run() }, launch { target.run() })
        try {
            withTimeout(8_000) { source.state.first { state -> state.devices.any { it.id == "expiry-target" && it.enabled } } }
            withTimeout(20_000) { source.startTogether(listOf("expiry-target")) }
            withTimeout(8_000) { target.state.first { it.group?.status == GroupV2Status.ACTIVE } }
            db.failToken = "token-expiry-source"
            target.requestGroup(GroupActionV2.PAUSE)
            assertTrue(target.state.value.pending)
            val cleared = runCatching { awaitCondition(4_000) { !target.state.value.pending } }.isSuccess
            val groupId = target.state.value.group?.ref?.groupId
            val requestRows = groupId?.let { db.readOrNull("users/${session.uid}/playbackV2/groups/$it/requests") }
            assertTrue("Pending action did not expire. target=${target.state.value}; rows=$requestRows", cleared)
            assertTrue(target.state.value.message.orEmpty().contains("expired"))
        } finally {
            db.failToken = null
            if (target.state.value.group != null) runCatching { target.leaveTogether() }
            source.close(); target.close(); jobs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun expiredLeaseFencesRestoresBackupsAndEpochChangeRestoresDisplacedQueues() = runBlocking {
        suspend fun paired(prefix: String, runCase: suspend (ConnectedPlaybackV2Client, ConnectedPlaybackV2Client,
            FakeBridge, FakeBridge, FakeRtdb) -> Unit) {
            val events = java.util.Collections.synchronizedList(mutableListOf<String>())
            val db = FakeRtdb()
            val targetQueue = TransferQueue(listOf(QueueOccurrence(
                "00000000-0000-4000-8000-000000000201",
                PortableRecording("youtube:lmnopqrstuv", "Independent", "Artist", 180_000))), RepeatModeV2.OFF, false)
            val sourceBridge = FakeBridge("$prefix-source-session", queue, initialPlaying = true, events = events)
            val targetBridge = FakeBridge("$prefix-target-session", targetQueue, initialPlaying = false, events = events)
            val http = OkHttpClient.Builder().addInterceptor(db.interceptor).build()
            fun client(id: String, bridge: FakeBridge) = ConnectedPlaybackV2Client(
                "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", id, id,
                sessionProvider = { session.copy(idToken = "token-$id") }, bridge = bridge, httpClient = http)
            val source = client("$prefix-source", sourceBridge)
            val target = client("$prefix-target", targetBridge)
            val jobs = listOf(launch { source.run() }, launch { target.run() })
            try {
                withTimeout(8_000) { source.state.first { state -> state.devices.any { it.id == "$prefix-target" && it.enabled } } }
                withTimeout(20_000) { source.startTogether(listOf("$prefix-target")) }
                withTimeout(8_000) { source.state.first { it.group?.status == GroupV2Status.ACTIVE } }
                runCase(source, target, sourceBridge, targetBridge, db)
            } finally {
                source.close(); target.close(); jobs.forEach { it.cancelAndJoin() }
            }
        }

        paired("expired") { source, target, sourceBridge, targetBridge, db ->
            val group = source.state.value.group!!
            db.failAllRequests = true
            val leaseUntil = source.state.value.group!!.leaseUntil
            delay((leaseUntil - System.currentTimeMillis() + 1_000).coerceAtLeast(1))
            awaitCondition(8_000) {
                source.state.value.group?.status == GroupV2Status.FENCED &&
                    target.state.value.group?.status == GroupV2Status.FENCED && !sourceBridge.playing && !targetBridge.playing
            }
            assertEquals(group.timeline!!.queue.entries[0].id, sourceBridge.currentOccurrenceId)
            db.failAllRequests = false
            awaitCondition(8_000) { source.state.value.group == null && target.state.value.group == null }
            assertEquals(queue.entries.map { it.id }, sourceBridge.queue.entries.map { it.id })
            assertFalse(sourceBridge.playing || targetBridge.playing)
            assertEquals("Independent", targetBridge.queue.entries.first().recording.title)
            assertEquals(12_000L, sourceBridge.positionMs)
            assertEquals(12_000L, targetBridge.positionMs)
        }

        paired("epoch") { source, target, sourceBridge, targetBridge, db ->
            val group = source.state.value.group!!
            val path = "users/${session.uid}/playbackV2/groups/${group.ref.groupId}"
            db.seed("$path/head", db.read("$path/head").put("epoch", group.ref.epoch + 1))
            awaitCondition(8_000) { source.state.value.group == null && target.state.value.group == null }
            assertEquals(queue.entries.map { it.id }, sourceBridge.queue.entries.map { it.id })
            assertEquals(queue.entries[0].id, sourceBridge.currentOccurrenceId)
            assertFalse(sourceBridge.playing || targetBridge.playing)
            assertEquals("Independent", targetBridge.queue.entries.first().recording.title)
        }
    }

    private suspend fun CoroutineScope.runMove(activationFails: Boolean, corruptObservedAckSession: Boolean = false) {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val db = FakeRtdb()
        db.failNextObservedAckBeforeCommit = !activationFails
        db.corruptObservedAckSession = corruptObservedAckSession
        val sourceBridge = FakeBridge("source-session", queue, initialPlaying = true, events = events)
        val targetBridge = FakeBridge("target-session", TransferQueue(emptyList(), RepeatModeV2.OFF, false), initialPlaying = false,
            events = events, failActivation = activationFails).apply {
            if (!activationFails && (db.failNextObservedAckBeforeCommit || corruptObservedAckSession)) {
                activateDelayMs = 5_000
                advanceWhilePlaying = true
            }
        }
        val http = OkHttpClient.Builder().addInterceptor(db.interceptor).build()
        fun client(id: String, bridge: FakeBridge) = ConnectedPlaybackV2Client(
            "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app", id, id,
            sessionProvider = { session.copy(idToken = "token-$id") }, bridge = bridge, httpClient = http)
        val source = client("source-device", sourceBridge)
        val target = client("target-device", targetBridge)
        val jobs = listOf(launch { source.run() }, launch { target.run() })
        try {
            withTimeout(8_000) { source.state.first { state -> state.devices.any { it.id == "target-device" && it.enabled } } }
            withTimeout(15_000) { source.moveTo("target-device") }
            val status = source.state.value.move?.status
            if (activationFails) {
                assertEquals(MoveV2Status.FAILED, status)
                assertTrue(sourceBridge.playing)
                assertFalse(events.contains("source-paused"))
                assertTrue(targetBridge.queue.entries.isEmpty())
            } else {
                if (corruptObservedAckSession) {
                    awaitCondition(10_000) { source.state.value.move?.status == MoveV2Status.COMPLETE }
                    assertEquals("target_snapshot_confirmed_after_ack_loss", source.state.value.move?.reason)
                } else assertEquals(MoveV2Status.COMPLETE, status)
                assertEquals(false, sourceBridge.playing)
                assertEquals(queue.entries.first().id, targetBridge.currentOccurrenceId)
                assertTrue(events.indexOf("target-observed") >= 0)
                assertTrue(events.indexOf("target-observed") < events.indexOf("source-paused"))
                assertEquals(1, events.count { it == "target-observed" })
                val operations = db.read("users/move-owner/playbackV2/ops")
                assertEquals("COMPLETE", operations.getJSONObject(source.state.value.move!!.id)
                    .getJSONObject("result").getString("status"))
                assertEquals(queue.entries.map { it.id }, targetBridge.queue.entries.map { it.id })
            }
        } finally {
            source.close(); target.close(); jobs.forEach { it.cancelAndJoin() }
        }
    }

    private suspend fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(50)
        }
    }

    private class FakeBridge(
        override val sessionId: String,
        initialQueue: TransferQueue,
        initialPlaying: Boolean,
        private val events: MutableList<String>,
        private val failActivation: Boolean = false,
        private val failConcurrentPreparation: Boolean = false,
        private val captureRejectsWhenPrepared: Boolean = false,
    ) : NativePlaybackBridgeV2 {
        private var currentFence = EngineFence(sessionId, 1, 2, 3)
        var queue: TransferQueue = initialQueue
            private set
        var playing = initialPlaying
            private set
        var currentOccurrenceId: String? = initialQueue.entries.firstOrNull()?.id
            private set
        var positionMs = if (initialQueue.entries.isEmpty()) 0L else 12_000L
            private set
        var ended = false
            private set
        private var staged: TransferQueue? = null
        private var stagedGroup: GroupRef? = null
        private var activeGroup: GroupRef? = null
        private data class Displaced(val queue: TransferQueue, val occurrenceId: String?, val positionMs: Long)
        private var displaced: Displaced? = null
        private val preparing = AtomicBoolean(false)
        var prepareDelayMs = 0L
        var activateDelayMs = 0L
        var activateGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var activateStarted: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var observeGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var observeStarted: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        @Volatile var blockNextPreparedObservation = false
        var groupTransportDelayMs = 0L
        var advanceWhilePlaying = false
        private var playbackClockNanos = System.nanoTime()

        override suspend fun fence() = currentFence
        override suspend fun capabilities() = setOf(CONNECTED_V2_CAPABILITY_MOVE, CONNECTED_V2_CAPABILITY_GROUP)
        override suspend fun observe(): EngineObservation {
            if (blockNextPreparedObservation && stagedGroup != null && activeGroup == null) {
                blockNextPreparedObservation = false
                observeStarted?.complete(Unit)
                observeGate?.await()
            }
            return observation()
        }
        override suspend fun capture(): EngineCapture {
            check(!captureRejectsWhenPrepared || staged == null) { "capture unavailable while native stage is ready" }
            return EngineCapture(currentFence, queue, positionMs, playing)
        }

        override suspend fun prepare(operationId: String, queue: TransferQueue, expectedTarget: EngineFence, group: GroupRef?): EngineObservation {
            check(expectedTarget == currentFence)
            if (failConcurrentPreparation) check(preparing.compareAndSet(false, true)) { "Concurrent native preparations" }
            try {
                events += "${sessionId}-prepare-started"
                if (group != null) {
                    if (displaced == null) displaced = Displaced(this.queue, currentOccurrenceId, positionMs)
                    stagedGroup = group
                }
                staged = queue
                if (prepareDelayMs > 0) delay(prepareDelayMs)
                events += "${sessionId}-prepared"
                return observation(ready = true)
            } finally {
                if (failConcurrentPreparation) preparing.set(false)
            }
        }

        override suspend fun activate(operationId: String, positionMs: Long, play: Boolean): EngineObservation {
            if (failActivation) error("simulated native activation failure")
            activateStarted?.complete(Unit)
            activateGate?.await()
            if (activateDelayMs > 0) delay(activateDelayMs)
            queue = staged ?: error("no staged queue")
            currentOccurrenceId = queue.entries.first().id
            this.positionMs = positionMs
            playing = play
            ended = false
            playbackClockNanos = System.nanoTime()
            currentFence = currentFence.copy(queueRevision = currentFence.queueRevision + 1,
                occurrenceRevision = currentFence.occurrenceRevision + 1, transportRevision = currentFence.transportRevision + 1)
            activeGroup = stagedGroup
            events += "target-observed"
            return observation(ready = true)
        }

        override suspend fun applyGroupTransport(ref: GroupRef, expectedOccurrenceId: String, positionMs: Long,
            play: Boolean, ended: Boolean): EngineObservation {
            check(displaced != null && (stagedGroup == ref || activeGroup == ref))
            if (groupTransportDelayMs > 0) {
                events += "${sessionId}-group-transport-start"
                delay(groupTransportDelayMs)
            }
            staged?.let { queue = it }
            currentOccurrenceId = expectedOccurrenceId
            this.positionMs = positionMs
            playing = play
            this.ended = ended
            activeGroup = ref
            currentFence = currentFence.copy(transportRevision = currentFence.transportRevision + 1)
            events += "${sessionId}-group-transport"
            return observation(ready = true)
        }

        override suspend fun pauseIfUnchanged(expectedSource: EngineFence): EngineObservation? {
            if (currentFence != expectedSource) return null
            playing = false
            events += "source-paused"
            return observation(ready = true)
        }

        override suspend fun abort(operationId: String) {
            staged = null
            if (displaced != null && stagedGroup?.groupId == operationId) {
                stagedGroup = null
                displaced = null
            }
        }
        override suspend fun restoreDisplacedPaused(operationId: String) = observation(ready = true)
        override suspend fun leaveGroupPaused(groupId: String, epoch: Long): EngineObservation {
            val requested = GroupRef(groupId, epoch)
            check(activeGroup == null || activeGroup == requested) { "This player is no longer in that Play together session" }
            check(stagedGroup == null || stagedGroup == requested) { "This player has a different playback stage" }
            val backup = displaced
            if (backup != null && (activeGroup == requested || stagedGroup == requested)) {
                queue = backup.queue
                currentOccurrenceId = backup.occurrenceId
                positionMs = backup.positionMs
                playing = false
                ended = false
                activeGroup = null
                stagedGroup = null
                staged = null
                displaced = null
                currentFence = currentFence.copy(queueRevision = currentFence.queueRevision + 1,
                    occurrenceRevision = currentFence.occurrenceRevision + 1, transportRevision = currentFence.transportRevision + 1)
            }
            return observation(ready = true)
        }

        fun setPlayback(positionMs: Long, playing: Boolean) {
            this.positionMs = positionMs
            this.playing = playing
            ended = false
            playbackClockNanos = System.nanoTime()
        }

        fun finishCurrent() {
            positionMs = queue.entries.first().recording.durationMs
            playing = false
            ended = true
        }

        fun displacedQueue() = displaced?.queue

        private fun observation(ready: Boolean = true): EngineObservation {
            val now = System.nanoTime()
            if (advanceWhilePlaying && playing) {
                positionMs = (positionMs + (now - playbackClockNanos) / 1_000_000L).coerceAtMost(queue.entries.first().recording.durationMs)
                playbackClockNanos = now
            }
            return EngineObservation(currentFence, currentOccurrenceId, positionMs,
            playing, ready, ended = ended, recordingId = queue.entries.firstOrNull()?.recording?.id,
            title = queue.entries.firstOrNull()?.recording?.title, artist = queue.entries.firstOrNull()?.recording?.artist,
            durationMs = queue.entries.firstOrNull()?.recording?.durationMs ?: 0L)
        }

        fun isPreparing() = preparing.get()
    }

    /** Models VLC's single dispatcher: a long prepare holds later observe calls off the engine thread. */
    private class SerializedPrepareBridge(private val delegate: FakeBridge) : NativePlaybackBridgeV2 by delegate {
        private val nativeDispatcher = Mutex()
        val observationQueuedDuringPrepare = AtomicBoolean(false)

        override suspend fun prepare(operationId: String, queue: TransferQueue, expectedTarget: EngineFence,
            group: GroupRef?): EngineObservation = nativeDispatcher.withLock {
            delegate.prepare(operationId, queue, expectedTarget, group)
        }

        override suspend fun observe(): EngineObservation {
            if (delegate.isPreparing()) observationQueuedDuringPrepare.set(true)
            return nativeDispatcher.withLock { delegate.observe() }
        }
    }

    /** Minimal REST fake: keeps exact leaf rows and emulates RTDB ETag preconditions and timestamps. */
    private class FakeRtdb {
        private val values = ConcurrentHashMap<String, JSONObject>()
        private val versions = ConcurrentHashMap<String, Long>()
        private val failNextObservedAck = AtomicBoolean(false)
        private val failTokenValue = java.util.concurrent.atomic.AtomicReference<String?>(null)
        var failToken: String?
            get() = failTokenValue.get()
            set(value) { failTokenValue.set(value) }
        @Volatile var failAllRequests = false
        @Volatile var corruptObservedAckSession = false
        var failNextObservedAckBeforeCommit: Boolean
            get() = failNextObservedAck.get()
            set(value) { failNextObservedAck.set(value) }
        val interceptor = okhttp3.Interceptor { chain ->
            val request = chain.request()
            if (failAllRequests || failToken != null && request.url.queryParameter("auth") == failToken) {
                return@Interceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(503)
                    .message("Simulated disconnected device").body("".toResponseBody()).build()
            }
            val path = request.url.encodedPath.removePrefix("/").removeSuffix(".json")
            val current = values[path]
            val etag = current?.let { "\"v${versions[path] ?: 1}\"" } ?: "null_etag"
            val ifMatch = request.header("If-Match")
            if (request.method == "PUT" && ifMatch != null && ifMatch != etag) {
                return@Interceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(412)
                    .message("Precondition Failed").body("".toResponseBody()).build()
            }
            val body = when (request.method) {
                "GET" -> node(path)?.toString() ?: "null"
                "PUT" -> {
                    val parsed = JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                    if (path.endsWith("/target") && parsed.optString("status") == "OBSERVED" && failNextObservedAck.compareAndSet(true, false)) {
                        return@Interceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(500)
                            .message("Simulated lost acknowledgement").body("".toResponseBody()).build()
                    }
                    if (path.endsWith("/target") && parsed.optString("status") == "OBSERVED" && corruptObservedAckSession) {
                        parsed.put("sessionId", "wrong-target-session")
                    }
                    values[path] = resolveServerValues(parsed)
                    versions.merge(path, 1L, Long::plus)
                    "null"
                }
                "DELETE" -> {
                    values.keys.filter { it == path || it.startsWith("$path/") }.forEach(values::remove)
                    versions.merge(path, 1L, Long::plus)
                    "null"
                }
                else -> error("Unexpected HTTP method ${request.method}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("ETag", if (request.method == "GET") etag else "\"v${versions[path]}\"")
                .body(body.toResponseBody()).build()
        }

        fun read(path: String): JSONObject = node(path) ?: error("No data at $path")

        fun readOrNull(path: String): JSONObject? = node(path)

        fun seed(path: String, value: JSONObject) {
            values[path] = JSONObject(value.toString())
            versions.merge(path, 1L, Long::plus)
        }

        private fun node(path: String): JSONObject? {
            values[path]?.let { return JSONObject(it.toString()) }
            val prefix = "$path/"
            val children = values.keys.filter { it.startsWith(prefix) }
            if (children.isEmpty()) return null
            val childKeys = children.map { it.removePrefix(prefix).substringBefore('/') }.toSet()
            return JSONObject().also { out -> childKeys.forEach { child -> node("$path/$child")?.let { out.put(child, it) } } }
        }

        private fun resolveServerValues(value: JSONObject): JSONObject = JSONObject().also { out ->
            value.keys().forEach { key ->
                val child = value.get(key)
                out.put(key, when {
                    child is JSONObject && child.length() == 1 && child.optString(".sv") == "timestamp" -> System.currentTimeMillis()
                    child is JSONObject -> resolveServerValues(child)
                    child is org.json.JSONArray -> child
                    else -> child
                })
            }
        }
    }
}
