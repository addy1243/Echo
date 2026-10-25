package dev.echo.playback

import org.junit.Assert.*
import org.junit.Test

class QueueRestoreTest {
    @Test fun keepsDuplicateEntriesAndTheExactSelectedOccurrence() {
        assertEquals(RestoredQueue(listOf("a", "b", "a"), 2, 710),
            restoreQueue(listOf("a", "b", "a"), 2, 710, setOf("a", "b")))
    }

    @Test fun skipsDeletedTracksWithoutSeekingTheNextSongToTheOldPosition() {
        assertEquals(RestoredQueue(listOf("a", "c"), 1, 0),
            restoreQueue(listOf("a", "b", "c"), 1, 900, setOf("a", "c")))
        assertNull(restoreQueue(listOf("gone"), 0, 900, emptySet()))
    }

    @Test fun handlesTruncatedQueuesAndInvalidSavedValues() {
        assertEquals(RestoredQueue(listOf("a"), 0, 0), restoreQueue(listOf("a"), 999, -5, setOf("a")))
        assertNull(restoreQueue(emptyList(), 0, 0, emptySet()))
    }

    @Test fun paginationDoesNotOverflowOrExposePartialPages() {
        assertEquals(listOf(3, 4), page(listOf(1, 2, 3, 4, 5), 1, 2))
        assertEquals(listOf(5), page(listOf(1, 2, 3, 4, 5), 2, 2))
        assertTrue(page(listOf(1), Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
    }
}
