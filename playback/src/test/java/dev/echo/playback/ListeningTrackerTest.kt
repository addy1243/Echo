package dev.echo.playback

import dev.echo.data.library.ListeningEventType
import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningTrackerTest {
    private data class Event(val id: String, val type: ListeningEventType, val listened: Long)

    @Test fun pauseResumeAndRetriesDoNotCreateNewPlaysOrCountPausedTime() {
        val events = mutableListOf<Event>()
        val tracker = ListeningTracker { id, type, listened, _ -> events += Event(id, type, listened) }
        tracker.transition("a", 100_000, 0, ListeningEventType.INTERRUPTED)
        tracker.playing("a", 100_000, true, 100)
        tracker.playing("a", 100_000, false, 1100)
        tracker.transition("a", 100_000, 2000, ListeningEventType.INTERRUPTED, replacement = true)
        tracker.playing("a", 100_000, true, 5100)
        tracker.transition("b", 100_000, 7100, ListeningEventType.SKIPPED)
        assertEquals(listOf(Event("a", ListeningEventType.PLAYED, 0), Event("a", ListeningEventType.SKIPPED, 3000)), events)
    }

    @Test fun repeatCountsSeparateOccurrencesAndUnplayedItemsHaveNoHistory() {
        val events = mutableListOf<Event>()
        val tracker = ListeningTracker { id, type, listened, _ -> events += Event(id, type, listened) }
        tracker.transition("unplayed", 1000, 0, ListeningEventType.INTERRUPTED)
        tracker.transition("a", 1000, 0, ListeningEventType.SKIPPED)
        tracker.playing("a", 1000, true, 0)
        tracker.transition("a", 1000, 1000, ListeningEventType.COMPLETED)
        tracker.playing("a", 1000, true, 1000)
        tracker.finish(ListeningEventType.COMPLETED, 2000)
        tracker.finish(ListeningEventType.COMPLETED, 2000)
        assertEquals(listOf(ListeningEventType.PLAYED, ListeningEventType.COMPLETED,
            ListeningEventType.PLAYED, ListeningEventType.COMPLETED), events.map { it.type })
        assertEquals(listOf(1000L, 1000L), events.filter { it.type == ListeningEventType.COMPLETED }.map { it.listened })
    }

    @Test fun failureIsNeutralAndDoesNotCountAsSkip() {
        val events = mutableListOf<Event>()
        val tracker = ListeningTracker { id, type, listened, _ -> events += Event(id, type, listened) }
        tracker.playing("a", 1000, true, 0)
        tracker.error(200)
        tracker.playing("a", 1000, false, 200)
        tracker.transition("b", 1000, 1000, ListeningEventType.SKIPPED)
        assertEquals(listOf(ListeningEventType.PLAYED, ListeningEventType.ERROR, ListeningEventType.INTERRUPTED), events.map { it.type })
        assertEquals(200L, events.last().listened)
    }

    @Test fun explicitSkipAfterSuccessfulRecoveryIsUserPreferenceAgain() {
        val events = mutableListOf<Event>()
        val tracker = ListeningTracker { id, type, listened, _ -> events += Event(id, type, listened) }
        tracker.playing("a", 1000, true, 0)
        tracker.error(100)
        tracker.playing("a", 1000, false, 100)
        tracker.playing("a", 1000, true, 300)
        tracker.transition("b", 1000, 400, ListeningEventType.SKIPPED)
        assertEquals(ListeningEventType.SKIPPED, events.last().type)
        assertEquals(200L, events.last().listened)
    }
}
