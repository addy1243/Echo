package dev.echo.app

import dev.echo.core.Track
import dev.echo.data.firebase.ConnectedPlaybackAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStagePlaybackTest {
    @Test fun adminLabelsReuseTheLowestFreeNumber() {
        val peers = listOf(
            AndroidStagePlayback.AdminPeer("android-1", "Android · Stage 1", true),
            AndroidStagePlayback.AdminPeer("android-3", "Android · Stage 3", false),
        )
        assertEquals("Android · Stage 2", nextStagePeerLabel(peers, "Android"))
        assertEquals("Laptop · Stage 1", nextStagePeerLabel(peers, "Laptop"))
    }

    @Test fun peerLifecycleAndQueueAreLocalAndSessionFenced() {
        val stage = AndroidStagePlayback()
        stage.add("Android · Stage 1")
        val peer = stage.adminPeers.value.single()
        val original = stage.state.value.devices.single()
        stage.replaceQueue(original, androidStageTracks, 1, nowMs = 1_000)
        assertEquals("Blue Hour", stage.state.value.devices.single().player.title)
        assertTrue(stage.state.value.devices.single().player.isPlaying)
        stage.command(stage.state.value.devices.single(), ConnectedPlaybackAction.PAUSE, nowMs = 1_100)
        assertFalse(stage.state.value.devices.single().player.isPlaying)
        assertEquals(100, stage.state.value.devices.single().player.positionMs)
        stage.toggle(peer.id)
        assertTrue(stage.state.value.devices.isEmpty())
        stage.toggle(peer.id)
        val reconnected = stage.state.value.devices.single()
        assertNotEquals(original.sessionId, reconnected.sessionId)
        assertFalse(stage.has(peer.id, original.sessionId))
        stage.remove(peer.id)
        assertTrue(stage.adminPeers.value.isEmpty())
    }

    @Test fun reconnectDoesNotAdvanceAPlayerWhileItWasOffline() {
        val stage = AndroidStagePlayback()
        stage.add("Android · Stage 1")
        val original = stage.state.value.devices.single()
        val short = androidStageTracks[0].copy(durationMs = 1_000)
        stage.replaceQueue(original, listOf(short, androidStageTracks[1]), 0, nowMs = 1_000)
        stage.toggle(original.id, nowMs = 1_100)
        assertTrue(stage.state.value.devices.isEmpty())

        stage.toggle(original.id, nowMs = 100_000)
        val reconnected = stage.state.value.devices.single()
        assertNotEquals(original.sessionId, reconnected.sessionId)
        assertEquals(100, reconnected.player.positionMs)
        assertEquals("Neon Avenue", reconnected.player.title)
        assertTrue(reconnected.player.isPlaying)
    }

    @Test fun queueSnapshotKeepsDuplicateOccurrencesAndRejectsStaleRevisions() {
        val stage = AndroidStagePlayback()
        stage.add("Laptop · Stage 1")
        val peer = stage.state.value.devices.single()
        stage.replaceQueue(peer, listOf(androidStageTracks[0], androidStageTracks[1], androidStageTracks[0]), 0, nowMs = 1_000)
        val current = stage.state.value.devices.single()
        val capture = stage.readQueue(current, nowMs = 1_000)!!
        assertEquals(listOf("stage:neon", "stage:blue", "stage:neon"), capture.queue.entries.map { it.recording.id })
        assertEquals(3, capture.queue.entries.map { it.id }.toSet().size)

        stage.replaceQueue(current, listOf(androidStageTracks[2], androidStageTracks[1], androidStageTracks[0]), 0, nowMs = 1_000)
        assertNotEquals(current.player.queueRevision, stage.state.value.devices.single().player.queueRevision)
        assertNull(stage.readQueue(current, nowMs = 1_000))
        assertFalse(stage.command(current, ConnectedPlaybackAction.SEEK, 50_000, nowMs = 1_000))
    }

    @Test fun nextPreviousResetPositionAndMonotonicTicksAdvanceAndFinish() {
        val stage = AndroidStagePlayback()
        stage.add("Android · Stage 1")
        val peer = stage.state.value.devices.single()
        val short = androidStageTracks[0].copy(durationMs = 1_000)
        stage.replaceQueue(peer, listOf(short, short), 0, nowMs = 1_000)
        stage.tick(1_600)
        assertEquals(600, stage.state.value.devices.single().player.positionMs)
        stage.command(stage.state.value.devices.single(), ConnectedPlaybackAction.NEXT, nowMs = 1_600)
        assertEquals(0, stage.state.value.devices.single().player.positionMs)
        stage.command(stage.state.value.devices.single(), ConnectedPlaybackAction.PREVIOUS, nowMs = 1_600)
        assertEquals(0, stage.state.value.devices.single().player.positionMs)

        stage.tick(2_600)
        stage.tick(3_600)
        assertFalse(stage.state.value.devices.single().player.isPlaying)
        assertEquals(1_000, stage.state.value.devices.single().player.positionMs)
        assertTrue(stage.command(stage.state.value.devices.single(), ConnectedPlaybackAction.PLAY, nowMs = 3_600))
        assertTrue(stage.state.value.devices.single().player.isPlaying)
        assertEquals(0, stage.state.value.devices.single().player.positionMs)
        stage.tick(3_850)
        assertEquals(250, stage.state.value.devices.single().player.positionMs)
    }

    @Test fun stageGroupUsesOneFutureStartForFastAndSlowPeersAndReplacesSelectedQueue() {
        val stage = AndroidStagePlayback()
        stage.add("Android · Stage 1")
        stage.add("Laptop · Stage 1")
        val peers = stage.state.value.devices
        stage.setLatency(peers[0].id, AndroidStagePlayback.LatencyPreset.FAST_20)
        stage.setLatency(peers[1].id, AndroidStagePlayback.LatencyPreset.SLOW_500)
        val captured = stage.state.value.devices.associate { it.id to it.sessionId }
        stage.startTogether(captured.keys.toList(), captured, androidStageTracks, nowMs = 10_000)
        val group = stage.v2State.value.group!!
        assertEquals(15_535L, group.timeline!!.issuedAtMs)
        assertEquals(setOf(20L, 500L), group.syncTiming.map { it.roundTripP95Ms }.toSet())
        assertTrue(group.members.filter { it.deviceId != "android-stage-local" }.all {
            it.status == dev.echo.data.firebase.GroupMemberV2Status.BUFFERING
        })
        assertTrue(stage.v2State.value.pending)

        stage.tick(16_000)
        assertEquals(465L, stage.v2State.value.group!!.timeline!!.positionMs)
        assertTrue(stage.controlTogether(dev.echo.data.firebase.GroupActionV2.SYNC, nowMs = 16_000))
        val synced = stage.v2State.value.group!!
        assertEquals(21_535L, synced.timeline!!.issuedAtMs)
        assertEquals(6_000L, synced.timeline!!.positionMs)
        assertTrue(stage.v2State.value.pending)
        assertTrue(synced.members.all { it.status == dev.echo.data.firebase.GroupMemberV2Status.BUFFERING })

        stage.tick(22_000)
        assertEquals(6_465L, stage.v2State.value.group!!.timeline!!.positionMs)
        assertFalse(stage.v2State.value.pending)
        assertTrue(stage.controlTogether(dev.echo.data.firebase.GroupActionV2.PAUSE, nowMs = 22_000))
        val pausedPosition = stage.v2State.value.group!!.timeline!!.positionMs
        assertEquals(6_465L, pausedPosition)
        assertTrue(stage.controlTogether(dev.echo.data.firebase.GroupActionV2.SYNC, nowMs = 22_000))
        val pausedSync = stage.v2State.value.group!!
        assertEquals(27_535L, pausedSync.timeline!!.issuedAtMs)
        assertEquals(pausedPosition, pausedSync.timeline!!.positionMs)

        assertTrue(stage.replaceGroupQueue(listOf(androidStageTracks[2], androidStageTracks[0]), 0, nowMs = 22_100))
        val replacement = stage.v2State.value.group!!.timeline!!
        assertEquals(listOf("stage:orbit", "stage:neon"), replacement.queue.entries.map { it.recording.id })
        assertEquals(group.ref, replacement.ref)
        assertEquals(group.timelineSequence + 4, replacement.sequence)
    }

    @Test fun simulatedTimingEstimatesStayFreshWhileTheGroupPlays() {
        val stage = AndroidStagePlayback()
        stage.add("Android · Stage 1")
        stage.add("Laptop · Stage 1")
        val peers = stage.state.value.devices
        stage.setLatency(peers[0].id, AndroidStagePlayback.LatencyPreset.FAST_20)
        stage.setLatency(peers[1].id, AndroidStagePlayback.LatencyPreset.SLOW_500)
        val sessions = peers.associate { it.id to it.sessionId }
        stage.startTogether(peers.map { it.id }, sessions, androidStageTracks, nowMs = 10_000)

        stage.tick(30_000)

        val timing = stage.v2State.value.group!!.syncTiming
        assertEquals(setOf(20L, 500L), timing.map { it.roundTripP95Ms }.toSet())
        assertTrue(timing.all { System.currentTimeMillis() - it.sampledAtMs in 0..15_000L })
    }

    @Test fun leavingStageGroupRestoresEachPrivateQueuePausedAfterSharedQueueReplacement() {
        val stage = AndroidStagePlayback()
        stage.add("Android · Stage 1")
        stage.add("Laptop · Stage 1")
        val peers = stage.state.value.devices
        stage.replaceQueue(peers[0], listOf(androidStageTracks[0], androidStageTracks[1], androidStageTracks[0]), 0, nowMs = 1_000)
        stage.command(stage.state.value.devices.first { it.id == peers[0].id }, ConnectedPlaybackAction.PAUSE, nowMs = 1_100)
        stage.replaceQueue(stage.state.value.devices.first { it.id == peers[1].id },
            listOf(androidStageTracks[2], androidStageTracks[2], androidStageTracks[1]), 0, nowMs = 1_100)
        val privateQueues = peers.associate { peer ->
            peer.id to stage.readQueue(stage.state.value.devices.first { it.id == peer.id }, nowMs = 2_000)!!
        }
        val sessions = peers.associate { it.id to it.sessionId }
        stage.startTogether(peers.map { it.id }, sessions, androidStageTracks, nowMs = 2_000)
        assertTrue(stage.replaceGroupQueue(listOf(androidStageTracks[1], androidStageTracks[2]), nowMs = 2_100))

        assertTrue(stage.controlTogether(dev.echo.data.firebase.GroupActionV2.LEAVE, nowMs = 2_200))
        assertNull(stage.v2State.value.group)
        privateQueues.forEach { (id, expected) ->
            val restored = stage.readQueue(stage.state.value.devices.first { it.id == id }, nowMs = 2_200)!!
            assertEquals(expected.queue.entries.map { it.id }, restored.queue.entries.map { it.id })
            assertEquals(expected.queue.entries.map { it.recording.id }, restored.queue.entries.map { it.recording.id })
            assertEquals(expected.positionMs, restored.positionMs)
            assertFalse(restored.playing)
            assertTrue(restored.fence.queueRevision > expected.fence.queueRevision)
        }
    }

    @Test fun removingStageGroupMemberRestoresOtherPrivateQueuesPaused() {
        val stage = AndroidStagePlayback()
        stage.add("Android · Stage 1")
        stage.add("Laptop · Stage 1")
        val peers = stage.state.value.devices
        peers.forEachIndexed { index, peer ->
            val tracks = if (index == 0) listOf(androidStageTracks[0], androidStageTracks[0])
                else listOf(androidStageTracks[2], androidStageTracks[1], androidStageTracks[2])
            stage.replaceQueue(peer, tracks, 0, nowMs = 1_000)
        }
        val removedId = peers[0].id
        val keptId = peers[1].id
        val expected = stage.readQueue(stage.state.value.devices.first { it.id == keptId }, nowMs = 2_000)!!
        stage.startTogether(peers.map { it.id }, peers.associate { it.id to it.sessionId }, androidStageTracks, nowMs = 2_000)

        stage.remove(removedId, nowMs = 2_100)

        assertNull(stage.v2State.value.group)
        assertEquals(setOf(keptId), stage.state.value.devices.map { it.id }.toSet())
        val restored = stage.readQueue(stage.state.value.devices.single(), nowMs = 2_100)!!
        assertEquals(expected.queue.entries.map { it.id }, restored.queue.entries.map { it.id })
        assertEquals(expected.queue.entries.map { it.recording.id }, restored.queue.entries.map { it.recording.id })
        assertEquals(expected.positionMs, restored.positionMs)
        assertFalse(restored.playing)
        assertTrue(restored.fence.queueRevision > expected.fence.queueRevision)
    }
}
