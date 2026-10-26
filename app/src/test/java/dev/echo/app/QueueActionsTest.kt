package dev.echo.app

import dev.echo.core.Track
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class QueueActionsTest {
    private fun song(id: String) = Track(id, id, "Artist", provider = "youtube", sourceUri = "https://example.com/$id")
    @Test fun shuffleSelectsAnOccurrenceFromAvailableSongsInsteadOfTheFirst() {
        val songs = listOf(song("unavailable").copy(sourceUri = null), song("a"), song("a"), song("b"))
        val expected = listOf(1, 2, 3).random(Random(42))
        assertEquals(expected, randomPlayableIndex(songs, random = Random(42)))
        assertEquals(3, randomPlayableIndex(songs, true, setOf("b"), Random(42)))
        assertEquals(-1, randomPlayableIndex(songs, true, emptySet(), Random(42)))
    }
    @Test fun smartShuffleKeepsAnActiveSongAndRandomizesANewSessionAnchor() {
        val songs = listOf(song("a"), song("b"), song("c"), song("d"))
        assertEquals(songs[2], smartShuffleAnchor(songs, "c", Random(42)))
        assertEquals(songs.random(Random(42)), smartShuffleAnchor(songs, null, Random(42)))
        assertEquals(songs.random(Random(42)), smartShuffleAnchor(songs, "not-in-playlist", Random(42)))
    }
}
