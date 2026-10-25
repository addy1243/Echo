package dev.echo.app

import dev.echo.core.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueSelectionTest {
    @Test fun playlistStartsAtFirstPlayableTrack() {
        val unavailable = Track("missing", "Missing", "Artist")
        val playable = Track("a", "A", "Artist", sourceUri = "content://a")
        assertEquals(1, firstPlayableIndex(listOf(unavailable, playable)))
        assertEquals(-1, firstPlayableIndex(listOf(unavailable)))
        assertEquals(-1, firstPlayableIndex(emptyList()))
    }
    @Test fun selectedDuplicateKeepsItsOccurrenceAfterUnavailableTracksAreFiltered() {
        val a = Track("a", "A", "Artist", sourceUri = "content://a")
        val b = Track("b", "B", "Artist", sourceUri = "content://b")
        val unavailable = Track("missing", "Missing", "Artist")
        assertEquals(2, playableStartIndex(listOf(a, unavailable, b, a), 3))
    }
    @Test(expected = IllegalArgumentException::class) fun unavailableSelectionIsRejected() {
        playableStartIndex(listOf(Track("missing", "Missing", "Artist")), 0)
    }
}
