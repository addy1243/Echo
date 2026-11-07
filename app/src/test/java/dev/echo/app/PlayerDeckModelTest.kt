package dev.echo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.echo.core.connected.GroupRef
import dev.echo.core.connected.GroupTimeline
import dev.echo.core.connected.PortableRecording
import dev.echo.core.connected.QueueOccurrence
import dev.echo.core.connected.RepeatModeV2
import dev.echo.core.connected.TransferQueue
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedGroupV2
import dev.echo.data.firebase.ConnectedPlayerSnapshot
import dev.echo.data.firebase.GroupV2Status

class PlayerDeckModelTest {
    @Test fun carouselSentinelsWrapUsingStablePlayerKeys() {
        val entries = listOf(entry("local"), entry("phone:session-a"), entry("laptop:session-b"))

        assertEquals(5, playerDeckPageCount(entries.size))
        assertEquals("laptop:session-b", playerDeckEntryAtPage(entries, 0)?.key)
        assertEquals("local", playerDeckEntryAtPage(entries, 1)?.key)
        assertEquals("laptop:session-b", playerDeckEntryAtPage(entries, 3)?.key)
        assertEquals("local", playerDeckEntryAtPage(entries, 4)?.key)
        assertEquals(3, playerDeckCanonicalPage(entries, 0))
        assertEquals(1, playerDeckCanonicalPage(entries, 4))
        assertEquals(2, playerDeckPageForKey(entries, "phone:session-a"))
    }

    @Test fun onePlayerHasNoSentinelsOrPeekPages() {
        val entries = listOf(entry("local"))

        assertEquals(1, playerDeckPageCount(entries.size))
        assertEquals("local", playerDeckEntryAtPage(entries, 0)?.key)
        assertEquals(0, playerDeckCanonicalPage(entries, 12))
    }

    @Test fun insertingAnEarlierPlayerWaitsForKeyReconciliationBeforeSelection() {
        val oldKeys = listOf("local", "device:focused:session-a", "device:last:session-z")
        val updatedKeys = listOf("local", "device:new:session-0", "device:focused:session-a", "device:last:session-z")

        assertFalse(playerDeckRevisionMatches(updatedKeys, "device:focused:session-a", oldKeys, "device:focused:session-a"))
        assertTrue(playerDeckRevisionMatches(updatedKeys, "device:focused:session-a", updatedKeys, "device:focused:session-a"))
        assertEquals(3, playerDeckPageForKey(updatedKeys.map(::entry), "device:focused:session-a"))
    }

    @Test fun localSeekCannotCrossIntoAnotherQueueOccurrence() {
        assertTrue(localSeekWindowMatches("window-a", "window-a"))
        assertFalse(localSeekWindowMatches("window-a", "window-b"))
        assertFalse(localSeekWindowMatches("window-a", null))
    }

    @Test fun seekTargetRemainsBoundToRemoteSessionAndTrackRevisions() {
        val base = entry("local").presentation
        val remote = ConnectedDevice(
            id = "remote-a", label = "My phone", sessionId = "session-a", updatedAt = 1,
            player = ConnectedPlayerSnapshot("youtube:abcdefghijk", "Song", "Artist", true, 0, 180_000, "track-a", "queue-a"),
        )
        val presentation = base.copy(selectedRemoteId = remote.id, remote = remote, durationMs = 180_000)
        val target = PlayerSeekTarget.Remote(remote)

        assertTrue(playerSeekTargetMatches(target, presentation, null))
        assertFalse(playerSeekTargetMatches(target, presentation.copy(remote = remote.copy(sessionId = "session-b")), null))
        assertFalse(playerSeekTargetMatches(target, presentation.copy(remote = remote.copy(
            player = remote.player.copy(trackRevision = "track-b"),
        )), null))
        assertFalse(playerSeekTargetMatches(target, presentation.copy(remote = remote.copy(
            player = remote.player.copy(queueRevision = "queue-b"),
        )), null))
    }

    @Test fun groupSeekRemainsBoundToTimelineAndOccurrence() {
        val base = entry("local").presentation
        val ref = GroupRef("group-a", 1)
        val occurrence = QueueOccurrence("occurrence-a", PortableRecording("youtube:abcdefghijk", "Song", "Artist", 180_000))
        val queue = TransferQueue(listOf(occurrence), RepeatModeV2.OFF, shuffleEnabled = false)
        val timeline = GroupTimeline(ref, 7, queue, cursor = 0, positionMs = 0, playing = true, issuedAtMs = 1)
        val group = ConnectedGroupV2(ref, "coordinator", "session-a", leaseUntil = 10_000,
            timelineSequence = 7, status = GroupV2Status.ACTIVE, timeline = timeline)
        val presentation = base.copy(group = group, durationMs = 180_000)
        val target = PlayerSeekTarget.Group(ref, 7, occurrence.id, 180_000)

        assertTrue(playerSeekTargetMatches(target, presentation, null))
        assertFalse(playerSeekTargetMatches(target, presentation.copy(group = group.copy(timelineSequence = 8)), null))
        assertFalse(playerSeekTargetMatches(target, presentation.copy(group = group.copy(
            timeline = timeline.copy(queue = queue.copy(entries = listOf(occurrence.copy(id = "occurrence-b")))),
        )), null))
    }

    private fun entry(key: String) = PlayerDeckEntry(
        key = key,
        target = null,
        deviceLabel = key,
        isGroup = false,
        presentation = PlayerPresentationState(
            selectedRemoteId = null, remote = null, group = null, current = null,
            title = "Choose a song", artist = "", artworkUri = null, playing = false, buffering = false,
            positionMs = 0, durationMs = 0, remoteEnabled = false, reconnecting = false,
            independentDevices = emptyList(),
        ),
    )
}
