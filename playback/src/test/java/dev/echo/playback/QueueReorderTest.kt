package dev.echo.playback

import org.junit.Assert.*
import org.junit.Test

class QueueReorderTest {
    @Test fun movingEitherDirectionPreservesOccurrencesAndShuffleTraversal() {
        assertEquals(listOf(2, 0, 3, 1), reorderedTraversal(listOf(2, 3, 1, 0), 0, 3))
        assertEquals(listOf(2, 1, 0, 3), reorderedTraversal(listOf(2, 3, 1, 0), 3, 0))
        assertEquals(listOf(0, 1, 2), reorderedTraversal(listOf(0, 1, 2), 1, 1))
    }
    @Test fun invalidPermutationOrMissingOccurrenceIsRejected() {
        assertTrue(runCatching { reorderedTraversal(listOf(0, 0), 0, 1) }.isFailure)
        assertTrue(runCatching { reorderedTraversal(listOf(0, 1), 1, 2) }.isFailure)
    }
}
