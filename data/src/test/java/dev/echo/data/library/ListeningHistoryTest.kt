package dev.echo.data.library

import dev.echo.core.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningHistoryTest {
    @Test fun preferenceSeparatesEarlySkipsFromCompletionsAndNeutralErrors() {
        val track = Track("a", "Song", "Artist")
        val entries = listOf(
            ListeningEvent(1, track, ListeningEventType.PLAYED, 0, 0, 180_000),
            ListeningEvent(2, track, ListeningEventType.ERROR, 0, 1000, 180_000),
            ListeningEvent(3, track, ListeningEventType.SKIPPED, 0, 1000, 180_000),
            ListeningEvent(4, track, ListeningEventType.COMPLETED, 0, 180_000, 180_000),
            ListeningEvent(5, track, ListeningEventType.SKIPPED, 0, 60_000, 180_000),
        )
        assertEquals(ListeningPreference(1, 1, 1, 2, 241_000), listeningPreferences(entries)["a"])
        assertEquals(1, listeningPreferences(entries).getValue("a").score)
    }

    @Test fun seekingToEndDoesNotTeachThatASongWasEnjoyed() {
        val track = Track("a", "Song", "Artist")
        val end = ListeningEvent(1, track, ListeningEventType.COMPLETED, 0, 500, 180_000)
        assertEquals(0, listeningPreferences(listOf(end)).getValue("a").qualifiedPlays)
        assertEquals(0, listeningPreferences(listOf(end)).getValue("a").score)
    }
}
