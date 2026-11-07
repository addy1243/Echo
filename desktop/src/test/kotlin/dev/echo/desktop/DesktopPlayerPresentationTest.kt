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

    @Test fun independentPlayerCyclingUsesTheSameLocalAndPeerOrderAsTheSidecar() {
        val phone = device("phone")
        val speaker = device("speaker")
        val peers = listOf(phone, speaker)

        assertEquals(phone, nextIndependentPlayer(peers, null, 1))
        assertEquals(speaker, nextIndependentPlayer(peers, "phone", 1))
        assertNull(nextIndependentPlayer(peers, "speaker", 1))
        assertEquals(speaker, nextIndependentPlayer(peers, null, -1))
        assertNull(nextIndependentPlayer(peers, "phone", -1))
    }

    @Test fun groupModeCyclesOnlyIndependentPeers() {
        val phone = device("phone")
        val speaker = device("speaker")
        assertEquals(speaker, nextIndependentPlayer(listOf(phone, speaker), "phone", 1, includeLocal = false))
        assertNull(nextIndependentPlayer(listOf(phone), "phone", 1, includeLocal = false))
        assertNull(nextIndependentPlayer(listOf(phone), null, 1, includeLocal = false))
    }

    @Test fun cyclingCannotSilentlyAdoptAReplacementSession() {
        val freshPhone = device("phone").copy(sessionId = "new-session")
        assertNull(nextIndependentPlayer(listOf(freshPhone), "phone", 1,
            selectedRemoteSessionId = "previous-session"))
    }

    @Test fun playerDeckListsEveryOtherTargetInSwipeOrder() {
        val phone = device("phone")
        val speaker = device("speaker")
        val peers = listOf(phone, speaker)
        assertEquals(listOf(phone, speaker), independentPlayersAfterFocus(peers, null, 1))
        assertEquals(listOf(speaker, null), independentPlayersAfterFocus(peers, "phone", 1))
        assertEquals(listOf(null, phone), independentPlayersAfterFocus(peers, "speaker", 1))
        assertEquals(listOf(speaker), independentPlayersAfterFocus(peers, "phone", 1, includeLocal = false))
        assertEquals(listOf(phone, speaker), independentPlayersAfterFocus(peers, null, 1, includeLocal = false))
    }

    @Test fun playerDeckKeepsTheFocusedTargetInFrontAndPinsItsSession() {
        val phone = device("phone")
        val speaker = device("speaker")
        assertEquals(listOf(null, phone, speaker), independentPlayerDeckOrder(listOf(phone, speaker), null))
        assertEquals(listOf(phone, speaker, null), independentPlayerDeckOrder(listOf(phone, speaker), "phone"))
        assertEquals(listOf(speaker, null, phone), independentPlayerDeckOrder(listOf(phone, speaker), "speaker"))
        assertEquals(listOf(phone, speaker), independentPlayerDeckOrder(listOf(phone, speaker), "phone", includeLocal = false))
        assertEquals(listOf(phone, speaker), independentPlayerDeckOrder(listOf(phone, speaker), null, includeLocal = false))

        val stalePhone = phone.copy(sessionId = "previous-session", controlEnabled = false)
        val freshPhone = phone.copy(sessionId = "new-session")
        assertEquals(listOf(stalePhone, speaker, null, freshPhone), independentPlayerDeckOrder(
            listOf(freshPhone, stalePhone, speaker), "phone", selectedRemoteSessionId = "previous-session",
        ))
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
