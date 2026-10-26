package dev.echo.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueCommandsTest {
    @Test fun appendingKeepsExistingNextOrderBeforeNewOccurrences() {
        assertEquals(listOf(2, 0, 3, 1, 4, 5), appendedShuffleOrder(listOf(2, 0, 3, 1), 3, false, 2))
        assertEquals(listOf(3, 1, 2, 0, 4, 5), appendedShuffleOrder(listOf(2, 0, 3, 1), 3, true, 2))
        assertEquals(listOf(0, 1), appendedShuffleOrder(emptyList(), -1, false, 2))
    }
}
