package dev.echo.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaylistRulesTest {
    @Test fun `reordering preserves duplicates and does not mutate original`() {
        val original = listOf("a", "b", "a", "c")
        assertEquals(listOf("b", "a", "c", "a"), movedPlaylistIds(original, 0, 3))
        assertEquals(listOf("a", "b", "a", "c"), original)
        assertEquals(listOf("c", "a", "b", "a"), movedPlaylistIds(original, 3, 0))
    }

    @Test fun `same position leaves list unchanged`() {
        assertEquals(listOf("a", "a"), movedPlaylistIds(listOf("a", "a"), 1, 1))
    }

    @Test fun `invalid positions fail instead of discarding entries`() {
        assertThrows(IllegalArgumentException::class.java) { movedPlaylistIds(emptyList(), 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { movedPlaylistIds(listOf("a"), -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { movedPlaylistIds(listOf("a"), 0, 1) }
    }

    @Test fun `names trim whitespace and reject blank input`() {
        assertEquals("Road trip", checkedName("  Road trip  "))
        assertThrows(IllegalArgumentException::class.java) { checkedName(" \t\n") }
    }
}
