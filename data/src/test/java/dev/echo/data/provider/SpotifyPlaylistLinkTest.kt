package dev.echo.data.provider

import dev.echo.data.spotify.spotifyPlaylistLinkId
import org.junit.Assert.*
import org.junit.Test

class SpotifyPlaylistLinkTest {
    private val id = "1234567890123456789012"
    @Test fun acceptsPlaylistShareQueriesAndStripsTracking() {
        assertEquals(id, spotifyPlaylistLinkId(" https://open.spotify.com/playlist/$id?si=shared&utm_source=copy-link "))
        assertEquals(id, spotifyPlaylistLinkId("https://open.spotify.com:443/playlist/$id/"))
    }
    @Test fun rejectsOtherHostsSchemesCredentialsAndNonPlaylists() {
        listOf("http://open.spotify.com/playlist/$id", "https://open.spotify.com.evil.test/playlist/$id",
            "https://someone@open.spotify.com/playlist/$id", "https://open.spotify.com:8080/playlist/$id",
            "https://open.spotify.com/track/$id", "https://open.spotify.com/playlist/short", "file:///playlist/$id",
            "https://open.spotify.com/playlist/$id#fragment", "https://spotify.link/example").forEach {
            assertTrue(it, runCatching { spotifyPlaylistLinkId(it) }.isFailure)
        }
    }
}
