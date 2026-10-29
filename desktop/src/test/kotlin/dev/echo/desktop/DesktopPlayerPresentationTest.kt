package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedPlayerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPlayerPresentationTest {
    private fun device(id: String, enabled: Boolean = true) = ConnectedDevice(id, id, "session", 1,
        ConnectedPlayerSnapshot("youtube:abcdefghijk", "Song", "Artist", true, 1000, 90000, "1", "2"), enabled)

    @Test fun remoteSelectionFreezesOnDisconnectAndDoesNotFallBackToAnotherDevice() {
        val selected = device("remote")
        assertFalse(selectedRemotePlayer("remote", listOf(selected), null)!!.reconnecting)
        assertTrue(selectedRemotePlayer("remote", emptyList(), selected)!!.reconnecting)
        assertNull(selectedRemotePlayer("remote", emptyList(), null))
        assertNull(selectedRemotePlayer(null, listOf(selected), selected))
    }

    @Test fun remoteArtworkUsesKnownLibraryMetadataOrCanonicalYoutubeIdOnly() {
        val snapshot = device("remote").player
        val known = Track("youtube:abcdefghijk", "Library title", "Library artist", artworkUri = "https://example.test/cover.jpg")
        assertEquals(known, connectedPresentationTrack(snapshot, mapOf(known.id to known)))
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg",
            connectedPresentationTrack(snapshot, emptyMap())!!.artworkUri)
        assertNull(connectedPresentationTrack(snapshot.copy(trackId = "youtube:bad", title = "Song"), emptyMap()))
    }
}
