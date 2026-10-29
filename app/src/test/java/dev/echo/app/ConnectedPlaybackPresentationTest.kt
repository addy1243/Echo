package dev.echo.app

import dev.echo.core.Track
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedPlaybackState
import dev.echo.data.firebase.ConnectedPlayerSnapshot
import org.junit.Assert.*
import org.junit.Test

class ConnectedPlaybackPresentationTest {
    private val player = ConnectedPlayerSnapshot(
        trackId = "youtube:abcdefghijk", title = "Song", artist = "Artist", isPlaying = true,
        positionMs = 12_000, durationMs = 180_000, trackRevision = "track-a", queueRevision = "queue-a",
    )
    private val target = ConnectedDevice("laptop-1", "My laptop", "session-a", 100, player, controlEnabled = true)

    @Test fun remoteCommandRequiresFreshMatchingSessionAndRevisions() {
        assertTrue(isFreshConnectedPlaybackTarget(target, stateWith(target)))
        assertFalse(isFreshConnectedPlaybackTarget(target, ConnectedPlaybackState()))
        assertFalse(isFreshConnectedPlaybackTarget(target, stateWith(target, pending = true)))
        val disabled = target.copy(controlEnabled = false)
        assertFalse(isFreshConnectedPlaybackTarget(target, stateWith(disabled)))
        val newSession = target.copy(sessionId = "session-b")
        assertFalse(isFreshConnectedPlaybackTarget(target, stateWith(newSession)))
        val changedTrack = target.copy(player = player.copy(trackRevision = "track-b"))
        assertFalse(isFreshConnectedPlaybackTarget(target, stateWith(changedTrack)))
        val changedQueue = target.copy(player = player.copy(queueRevision = "queue-b"))
        assertFalse(isFreshConnectedPlaybackTarget(target, stateWith(changedQueue)))
        assertFalse(isFreshConnectedPlaybackTarget(target.copy(updatedAt = 101), stateWith(target)))
        assertTrue(isFreshConnectedPlaybackTarget(target, stateWith(target.copy(updatedAt = 101))))
    }

    @Test fun remoteArtworkOnlyUsesKnownLibraryArtOrCanonicalYoutubeId() {
        val known = Track("youtube:abcdefghijk", "Song", "Artist", artworkUri = "https://art.example/known.jpg")
        assertEquals("https://art.example/known.jpg", connectedArtworkUri(known.id, listOf(known)))
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", connectedArtworkUri(known.id, listOf(known.copy(artworkUri = null))))
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", connectedArtworkUri("youtube:abcdefghijk", emptyList()))
        assertNull(connectedArtworkUri("youtube:bad", emptyList()))
        assertNull(connectedArtworkUri("local:/private/path", emptyList()))
        assertNull(connectedArtworkUri(null, emptyList()))
    }

    private fun stateWith(device: ConnectedDevice, pending: Boolean = false) =
        ConnectedPlaybackState(devices = listOf(device), pending = pending)
}
