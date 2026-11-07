package dev.echo.desktop

import dev.echo.data.firebase.ConnectedPlaybackAction
import dev.echo.data.firebase.GroupActionV2
import dev.echo.core.Track
import dev.echo.core.connected.groupSyncDeadline
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageConnectedPlaybackTest {
    @Test fun stageUsesAdaptiveSharedDeadlinesAndDoesNotJumpPausedPlayback() = runBlocking {
        var now = 10_000L
        val stage = StageConnectedPlayback(clockMs = { now }, startTicker = false)
        try {
            val fastId = stage.addDemoDevice("Android phone")
            val slowId = stage.addDemoDevice("Windows laptop")
            stage.setDemoDeviceLatency(fastId, DemoLatencyPreset.FAST_20)
            stage.setDemoDeviceLatency(slowId, DemoLatencyPreset.SLOW_500)
            val sessions = stage.v2State.value.devices.filter { it.id == fastId || it.id == slowId }
                .associate { it.id to it.sessionId }
            val sourcePosition = stage.localPlayback.value.positionMs
            stage.startTogether(listOf(fastId, slowId), replacePlaying = true, expectedTargetSessions = sessions)

            var group = stage.v2State.value.group!!
            val memberSessions = group.members.map { it.sessionId }.toSet()
            var deadline = groupSyncDeadline(now, memberSessions, group.syncTiming.map { it.sample() }).startAtMs
            assertEquals(now + 5_580L, deadline)
            assertEquals(sourcePosition, group.timeline!!.positionMs)
            assertEquals(deadline, group.timeline!!.issuedAtMs)
            assertFalse(group.timeline!!.playing)
            assertEquals(dev.echo.data.firebase.GroupV2Status.PREPARING, group.status)
            assertTrue(stage.v2State.value.pending)

            now = deadline - 1
            stage.tick(now)
            group = stage.v2State.value.group!!
            assertEquals(sourcePosition, group.timeline!!.positionMs)
            assertEquals(deadline, group.timeline!!.issuedAtMs)
            assertEquals(dev.echo.data.firebase.GroupV2Status.PREPARING, group.status)
            assertTrue(stage.v2State.value.pending)
            now = deadline
            stage.tick(now)
            group = stage.v2State.value.group!!
            assertEquals(sourcePosition, group.timeline!!.positionMs)
            assertFalse(group.timeline!!.playing)
            assertEquals(dev.echo.data.firebase.GroupV2Status.ACTIVE, group.status)
            assertFalse(stage.v2State.value.pending)

            stage.requestGroup(GroupActionV2.SYNC)
            group = stage.v2State.value.group!!
            deadline = groupSyncDeadline(now, memberSessions, group.syncTiming.map { it.sample() }).startAtMs
            assertEquals(sourcePosition, group.timeline!!.positionMs)
            assertEquals(deadline, group.timeline!!.issuedAtMs)
            assertFalse(group.timeline!!.playing)
            assertEquals(dev.echo.data.firebase.GroupV2Status.PREPARING, group.status)
            assertTrue(stage.v2State.value.pending)
            now = deadline - 1
            stage.tick(now)
            assertEquals(sourcePosition, stage.v2State.value.group!!.timeline!!.positionMs)
            assertEquals(deadline, stage.v2State.value.group!!.timeline!!.issuedAtMs)
            assertEquals(dev.echo.data.firebase.GroupV2Status.PREPARING, stage.v2State.value.group!!.status)
            assertTrue(stage.v2State.value.pending)
            now = deadline
            stage.tick(now)
            assertEquals(sourcePosition, stage.v2State.value.group!!.timeline!!.positionMs)
            assertEquals(dev.echo.data.firebase.GroupV2Status.ACTIVE, stage.v2State.value.group!!.status)

            group = stage.v2State.value.group!!
            val replacementTracks = stage.localPlayback.value.queue.take(2)
            stage.replaceDemoGroupQueue(group.ref, group.timeline!!.sequence, replacementTracks, 0)
            group = stage.v2State.value.group!!
            deadline = groupSyncDeadline(now, memberSessions, group.syncTiming.map { it.sample() }).startAtMs
            assertEquals(listOf(replacementTracks[0].id, replacementTracks[1].id),
                group.timeline!!.queue.entries.map { it.recording.id })
            assertEquals(dev.echo.data.firebase.GroupV2Status.PREPARING, group.status)
            assertTrue(stage.v2State.value.pending)
            now = deadline - 1
            stage.tick(now)
            assertEquals(dev.echo.data.firebase.GroupV2Status.PREPARING, stage.v2State.value.group!!.status)
            assertTrue(stage.v2State.value.pending)
            now = deadline
            stage.tick(now)
            assertEquals(dev.echo.data.firebase.GroupV2Status.ACTIVE, stage.v2State.value.group!!.status)
            assertFalse(stage.v2State.value.pending)
            assertTrue(stage.v2State.value.group!!.timeline!!.playing)
            assertEquals(0L, stage.v2State.value.group!!.timeline!!.positionMs)

            now += 1_000L
            stage.tick(now)
            group = stage.v2State.value.group!!
            val playingPosition = group.timeline!!.positionMs
            assertTrue(group.timeline!!.playing)
            stage.requestGroup(GroupActionV2.SYNC)
            group = stage.v2State.value.group!!
            deadline = groupSyncDeadline(now, memberSessions, group.syncTiming.map { it.sample() }).startAtMs
            val expectedPlayingAnchor = playingPosition + (deadline - now)
            assertEquals(expectedPlayingAnchor, group.timeline!!.positionMs)
            assertTrue(group.timeline!!.playing)
            assertEquals(dev.echo.data.firebase.GroupV2Status.PREPARING, group.status)
            now = deadline - 1
            stage.tick(now)
            group = stage.v2State.value.group!!
            assertEquals(expectedPlayingAnchor, group.timeline!!.positionMs)
            assertEquals(deadline, group.timeline!!.issuedAtMs)
            assertEquals(dev.echo.data.firebase.GroupV2Status.PREPARING, group.status)
            now = deadline
            stage.tick(now)
            group = stage.v2State.value.group!!
            assertEquals(expectedPlayingAnchor, group.timeline!!.positionMs)
            assertEquals(dev.echo.data.firebase.GroupV2Status.ACTIVE, group.status)
        } finally { stage.stop() }
    }

    @Test fun togetherPublishesLatencyForEachCurrentDemoSession() = runBlocking {
        val stage = StageConnectedPlayback(clockMs = { 4_000L }, startTicker = false)
        try {
            val slowId = stage.addDemoDevice("Android phone")
            val variableId = stage.addDemoDevice("Windows laptop")
            val sessions = stage.v2State.value.devices.filter { it.id == slowId || it.id == variableId }
                .associate { it.id to it.sessionId }
            stage.startTogether(listOf(slowId, variableId), replacePlaying = true, expectedTargetSessions = sessions)
            stage.setDemoDeviceLatency(slowId, DemoLatencyPreset.SLOW_500)
            stage.setDemoDeviceLatency(variableId, DemoLatencyPreset.VARIABLE)

            val group = stage.v2State.value.group!!
            val slow = group.syncTiming.single { it.deviceId == slowId }
            val variable = group.syncTiming.single { it.deviceId == variableId }
            assertEquals(sessions.getValue(slowId), slow.sessionId)
            assertEquals(500L, slow.roundTripP95Ms)
            assertEquals(sessions.getValue(variableId), variable.sessionId)
            assertTrue(variable.roundTripP95Ms in 180L..480L)
            assertTrue(group.syncTiming.any { it.deviceId == stage.deviceId && it.sessionId == stage.deviceId })
        } finally { stage.stop() }
    }

    @Test fun stageLibrarySeedAddsSelectableSongsAndAPlaylistWithoutReplacingExistingContent() {
        val directory = Files.createTempDirectory("echo-stage-library-test")
        try {
            val library = DesktopLibrary(directory.resolve("library.json"))
            val songs = listOf(
                Track("youtube:stage000001", "Stage Signal", "Echo Demo", durationMs = 190_000, provider = "youtube"),
                Track("youtube:stage000002", "Soft Current", "Echo Demo", durationMs = 213_000, provider = "youtube"),
            )
            val seeded = seedStageLibraryIfEmpty(library, songs)
            assertEquals(songs.map { it.id }.toSet(), seeded.tracks.map { it.id }.toSet())
            assertEquals("Stage Mix", seeded.playlists.single().name)
            assertEquals(songs.map { it.id }, seeded.playlists.single().trackIds)
            assertEquals(1, seedStageLibraryIfEmpty(library, songs).playlists.size)

            val userTrack = Track("youtube:stage000003", "Afterglow Loop", "Echo Demo", durationMs = 176_000, provider = "youtube")
            library.saveTrack(userTrack)
            val retained = seedStageLibraryIfEmpty(library, songs)
            assertTrue(userTrack.id in retained.tracks.map { it.id })
            assertEquals(1, retained.playlists.size)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun demoPeersAreIndependentAndRejectAReplacedSession() = runBlocking {
        val stage = StageConnectedPlayback(clockMs = { 1_000L }, startTicker = false)
        try {
            val id = stage.addDemoDevice("Android phone")
            val oldSession = stage.state.value.devices.single()
            val oldTitle = oldSession.player.title
            stage.sendCommand(oldSession, ConnectedPlaybackAction.NEXT)
            assertNotEquals(oldTitle, stage.state.value.devices.single().player.title)

            stage.setDemoDeviceOnline(id, false)
            stage.setDemoDeviceOnline(id, true)
            val stale = runCatching { stage.sendCommand(oldSession, ConnectedPlaybackAction.PAUSE) }
            assertTrue(stale.isFailure)
            assertTrue(stage.state.value.devices.single().controlEnabled)
        } finally { stage.stop() }
    }

    @Test fun moveRetainsHostQueueAndRestoreReturnsItPaused() = runBlocking {
        val stage = StageConnectedPlayback(clockMs = { 2_000L }, startTicker = false)
        try {
            val before = stage.localPlayback.value
            val peerId = stage.addDemoDevice("Windows laptop")
            stage.moveTo(peerId, replacePlaying = true)
            assertFalse(stage.localPlayback.value.playing)
            assertEquals(before.current?.id, stage.state.value.devices.single().player.trackId)

            stage.restoreLatestDisplacedPaused()
            val restored = stage.localPlayback.value
            assertEquals(before.queue.map { it.id }, restored.queue.map { it.id })
            assertEquals(before.positionMs, restored.positionMs)
            assertFalse(restored.playing)
        } finally { stage.stop() }
    }

    @Test fun togetherUsesOneTimelineKeepsPeersIndependentAndRestoresPausedQueues() = runBlocking {
        val stage = StageConnectedPlayback(clockMs = { 3_000L }, startTicker = false)
        try {
            val first = stage.addDemoDevice("Android phone")
            val second = stage.addDemoDevice("Windows laptop")
            val independent = stage.addDemoDevice("Android phone")
            val independentBefore = stage.state.value.devices.first { it.id == independent }.player
            val selected = listOf(first, second)
            val sessions = stage.v2State.value.devices.filter { it.id in selected }.associate { it.id to it.sessionId }
            stage.startTogether(selected, replacePlaying = true, expectedTargetSessions = sessions)

            val group = stage.v2State.value.group!!
            assertEquals(3, group.members.size)
            val timeline = group.timeline!!
            stage.requestGroup(GroupActionV2.NEXT, expectedGroup = group.ref,
                expectedTimelineSequence = timeline.sequence,
                expectedOccurrenceId = timeline.queue.entries[timeline.cursor].id)
            val afterNext = stage.v2State.value.group!!.timeline!!
            assertEquals(timeline.sequence + 1, afterNext.sequence)
            assertEquals(independentBefore.title, stage.state.value.devices.first { it.id == independent }.player.title)

            val hostTracks = stage.localPlayback.value.queue
            stage.replaceDemoGroupQueue(afterNext.ref, afterNext.sequence,
                listOf(hostTracks[0], hostTracks[1], hostTracks[0], hostTracks[0]), startIndex = 1)
            val replaced = stage.v2State.value.group!!.timeline!!
            assertEquals(listOf(hostTracks[1].id, hostTracks[0].id, hostTracks[0].id),
                replaced.queue.entries.map { it.recording.id })
            assertEquals(3, replaced.queue.entries.map { it.id }.toSet().size)
            assertTrue(replaced.playing)
            assertEquals(dev.echo.data.firebase.GroupV2Status.PREPARING, stage.v2State.value.group!!.status)

            val stale = runCatching { stage.requestGroup(GroupActionV2.SEEK, 1_000,
                group.ref, timeline.sequence, timeline.queue.entries[timeline.cursor].id) }
            assertTrue(stale.isFailure)
            stage.requestGroup(GroupActionV2.LEAVE)
            assertNull(stage.v2State.value.group)
            assertFalse(stage.state.value.devices.first { it.id == first }.player.isPlaying)
            assertFalse(stage.state.value.devices.first { it.id == second }.player.isPlaying)
            assertEquals(independentBefore.title, stage.state.value.devices.first { it.id == independent }.player.title)
        } finally { stage.stop() }
    }
}
