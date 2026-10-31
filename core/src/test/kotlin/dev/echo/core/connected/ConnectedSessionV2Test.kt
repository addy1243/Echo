package dev.echo.core.connected

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectedSessionV2Test {
    private fun occurrence(id: String = "00000000-0000-4000-8000-000000000001", track: String = "youtube:abcdefghijk") =
        QueueOccurrence(id, PortableRecording(track, "Song", "Artist", 120_000))

    @Test fun acceptsBoundedPortableTraversalAndCapture() {
        val capture = EngineCapture(EngineFence("session-1", 4, 7, 2),
            TransferQueue(listOf(occurrence()), RepeatModeV2.ALL, shuffleEnabled = true), 5_000, playing = true)
        validateEngineCapture(capture)
    }

    @Test fun rejectsMissingOrOversizedTraversalAndUnsupportedRecordings() {
        val empty = TransferQueue(emptyList(), RepeatModeV2.OFF, shuffleEnabled = false)
        assertTrue(runCatching { validateTransferQueue(empty) }.isFailure)
        val repeatedOccurrenceId = TransferQueue(listOf(occurrence(), occurrence()), RepeatModeV2.OFF, false)
        assertTrue(runCatching { validateTransferQueue(repeatedOccurrenceId) }.isFailure)
        val nonPortable = TransferQueue(listOf(occurrence(track = "local:/private/song.mp3")), RepeatModeV2.OFF, false)
        assertTrue(runCatching { validateTransferQueue(nonPortable) }.isFailure)
        val overLimit = TransferQueue((1..501).map { index ->
            occurrence("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}")
        }, RepeatModeV2.OFF, false)
        assertTrue(runCatching { validateTransferQueue(overLimit) }.isFailure)
    }

    @Test fun capturePositionMustBelongToCurrentOccurrenceAndFenceMustBeValid() {
        val capture = EngineCapture(EngineFence("session-1", 4, 7, 2),
            TransferQueue(listOf(occurrence()), RepeatModeV2.OFF, false), 120_001, playing = false)
        assertTrue(runCatching { validateEngineCapture(capture) }.isFailure)
        assertTrue(runCatching { validateEngineFence(EngineFence("bad/session", 0, 0, 0)) }.isFailure)
        assertTrue(runCatching { validateEngineFence(EngineFence("session-1", -1, 0, 0)) }.isFailure)
        validateEngineCapture(EngineCapture(EngineFence("session-1", 0, 0, 0),
            TransferQueue(emptyList(), RepeatModeV2.OFF, false), 0, playing = false))
        assertTrue(runCatching { validateEngineCapture(EngineCapture(EngineFence("session-1", 0, 0, 0),
            TransferQueue(emptyList(), RepeatModeV2.OFF, false), 1, playing = true)) }.isFailure)
    }

    @Test fun groupTraversalUsesStableCursorAndRepeatPolicy() {
        val rows = listOf(occurrence(), occurrence("00000000-0000-4000-8000-000000000002"),
            occurrence("00000000-0000-4000-8000-000000000003"))
        val all = GroupTimeline(GroupRef("00000000-0000-4000-8000-000000000099", 1), 0,
            TransferQueue(rows, RepeatModeV2.ALL, shuffleEnabled = true), 0, 0, true, issuedAtMs = 1)
        val next = advanceGroupTimeline(all, GroupTimelineAction.NEXT, 0, 2)!!
        assertEquals(1, next.cursor)
        assertEquals(rows.drop(1).map { it.id }, groupStagedTraversal(next).entries.map { it.id })
        val previous = advanceGroupTimeline(next, GroupTimelineAction.PREVIOUS, 0, 3)!!
        assertEquals(0, previous.cursor)
        assertEquals(0, advanceGroupTimeline(all.copy(cursor = 2), GroupTimelineAction.NEXT, 0, 4)!!.cursor)
        assertEquals(2, advanceGroupTimeline(all.copy(cursor = 0), GroupTimelineAction.PREVIOUS, 0, 4)!!.cursor)
        assertEquals(2, advanceGroupTimeline(all.copy(queue = all.queue.copy(repeatMode = RepeatModeV2.ONE), cursor = 2),
            GroupTimelineAction.NATURAL_END, 0, 5)!!.cursor)
        val oneRepeat = advanceGroupTimeline(all.copy(queue = all.queue.copy(repeatMode = RepeatModeV2.ONE), cursor = 2),
            GroupTimelineAction.NATURAL_END, 0, 5)!!
        assertEquals(0L, oneRepeat.positionMs)
        assertTrue(oneRepeat.playing)
        val offEnd = advanceGroupTimeline(all.copy(queue = all.queue.copy(repeatMode = RepeatModeV2.OFF), cursor = 2),
            GroupTimelineAction.NATURAL_END, 0, 6)!!
        assertTrue(offEnd.ended)
        assertTrue(!offEnd.playing)
        assertEquals(rows.last().recording.durationMs, offEnd.positionMs)
        val previousFromEnd = advanceGroupTimeline(offEnd, GroupTimelineAction.PREVIOUS, 0, 6)!!
        assertEquals(1, previousFromEnd.cursor)
        assertEquals(0L, previousFromEnd.positionMs)
        assertTrue(!previousFromEnd.ended)
        assertTrue(advanceGroupTimeline(all.copy(queue = all.queue.copy(repeatMode = RepeatModeV2.OFF), cursor = 2),
            GroupTimelineAction.NEXT, 0, 7)!!.let { it.ended && !it.playing })
        val running = all.copy(positionMs = 45_000, playing = true)
        val paused = advanceGroupTimeline(running, GroupTimelineAction.PAUSE, running.positionMs, 8)!!
        assertEquals(45_000L, paused.positionMs)
        assertTrue(!paused.playing)
        val resumed = advanceGroupTimeline(paused, GroupTimelineAction.PLAY, paused.positionMs, 9)!!
        assertEquals(45_000L, resumed.positionMs)
        assertTrue(resumed.playing)
        val seeked = advanceGroupTimeline(running, GroupTimelineAction.SEEK, 64_000, 10)!!
        val progressedAfterSeek = seeked.copy(positionMs = 71_000)
        val pausedAfterSeek = advanceGroupTimeline(progressedAfterSeek, GroupTimelineAction.PAUSE,
            progressedAfterSeek.positionMs, 11)!!
        assertEquals(71_000L, pausedAfterSeek.positionMs)
        assertEquals(71_000L, advanceGroupTimeline(pausedAfterSeek, GroupTimelineAction.PLAY, 0, 12)!!.positionMs)
    }
}
