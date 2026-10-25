package dev.echo.app

import dev.echo.core.Track
import org.junit.Assert.*
import org.junit.Test

class DiscoveryMixTest {
    private fun song(id: String) = Track(id, id, "Artist", sourceUri = "https://example.com/$id")
    @Test fun excludesKnownSongsUnavailableAndDuplicateDiscoveries() {
        val a = song("a")
        val b = song("b")
        assertEquals(listOf(b), discoveryCandidates(listOf(a.copy(id = "other"), b, b, song("c").copy(sourceUri = null)), listOf(a)))
    }
    @Test fun insertsAtMostOneDiscoveryPerThreePreservingOriginalOccurrences() {
        val original = listOf(song("a"), song("b"), song("a"), song("c"))
        val discoveries = listOf(song("x"), song("y"), song("z"), song("a"))
        val mixed = smartMix(original, discoveries, "b")
        assertEquals(5, mixed.size)
        assertEquals("b", mixed.first().id)
        assertEquals(original.groupingBy { it.id }.eachCount(), mixed.filter { it.id in setOf("a", "b", "c") }.groupingBy { it.id }.eachCount())
        assertTrue(mixed[3].id in setOf("x", "y", "z"))
        assertEquals("x", mixed[3].id)
    }
}
