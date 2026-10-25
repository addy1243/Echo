package dev.echo.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryTest {
    @Test fun `remembered search moves matching term to front without duplicates`() {
        assertEquals(listOf("Jazz", "Rock"), recentSearches(listOf("Rock", "jazz", "JAZZ"), "Jazz"))
    }

    @Test fun `history keeps only twenty most recent terms`() {
        val result = recentSearches(List(20) { "term$it" }, "new")
        assertEquals(20, result.size)
        assertEquals("new", result.first())
        assertEquals("term18", result.last())
    }
}
