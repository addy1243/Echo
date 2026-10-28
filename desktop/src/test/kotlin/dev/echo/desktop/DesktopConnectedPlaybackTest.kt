package dev.echo.desktop

import dev.echo.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopConnectedPlaybackTest {
    @Test fun localTrackIdsNeverPublishFilePaths() {
        val first = safeConnectedTrackId(Track("desktop:file:///C:/Users/Alex/Music/song.flac", "Song", "Artist"))
        val same = safeConnectedTrackId(Track("desktop:file:///C:/Users/Alex/Music/song.flac", "Song", "Artist"))

        assertEquals(first, same)
        assertTrue(first.startsWith("local:"))
        assertFalse(first.contains("Alex", ignoreCase = true))
        assertFalse(first.contains("song.flac", ignoreCase = true))
    }

    @Test fun canonicalProviderIdsRemainStable() {
        assertEquals("youtube:abcdefghijk", safeConnectedTrackId(Track("youtube:abcdefghijk", "Song", "Artist", provider = "youtube")))
        assertEquals("spotify:1234567890123456789012", safeConnectedTrackId(Track(
            "spotify:1234567890123456789012", "Song", "Artist", provider = "spotify")))
    }
}
