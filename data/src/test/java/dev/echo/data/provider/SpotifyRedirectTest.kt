package dev.echo.data.provider

import dev.echo.data.spotify.SPOTIFY_REDIRECT_URI
import dev.echo.data.spotify.supportedSpotifyRedirect
import org.junit.Assert.*
import org.junit.Test

class SpotifyRedirectTest {
    @Test fun registeredMobileCallbackIsExactAndHttpsRemainsSupported() {
        assertTrue(supportedSpotifyRedirect(SPOTIFY_REDIRECT_URI))
        assertTrue(supportedSpotifyRedirect("https://music.example.org/callback"))
    }
    @Test fun otherMobileRoutesInsecureAndAmbiguousCallbacksAreRejected() {
        listOf("echomusic://attacker/spotify", "echomusic://oauth/other", "echomusic://oauth/spotify?x=1",
            "other://oauth/spotify", "http://music.example.org/callback", "https://user@music.example.org/callback",
            "https://music.example.org/callback#fragment", "https://music.example.org/callback?code=old", "not a uri")
            .forEach { assertFalse(it, supportedSpotifyRedirect(it)) }
    }
}
