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
    @Test fun offlinePlaylistStartsAtFirstSavedOccurrenceAndStillAllowsDeviceFiles() {
        val remote = Track("online", "Online", "Artist", provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=abcdefghijk")
        val saved = remote.copy(id = "saved")
        val local = Track("local", "Local", "Artist", sourceUri = "content://local")
        assertEquals(1, firstPlayableIndex(listOf(remote, saved, saved), true, setOf("saved")))
        assertEquals(1, firstPlayableIndex(listOf(remote, local), true))
        assertEquals(-1, firstPlayableIndex(listOf(remote), true))
        assertEquals(0, firstPlayableIndex(listOf(remote, saved), false))
    }
    @Test(expected = IllegalArgumentException::class) fun unavailableSelectionIsRejected() {
        playableStartIndex(listOf(Track("missing", "Missing", "Artist")), 0)
    }
}
