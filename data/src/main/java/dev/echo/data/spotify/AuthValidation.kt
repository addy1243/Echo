package dev.echo.data.spotify

import java.security.MessageDigest
import java.net.URI

const val SPOTIFY_REDIRECT_URI = "echomusic://oauth/spotify"

internal fun supportedSpotifyRedirect(value: String): Boolean {
    if (value == SPOTIFY_REDIRECT_URI) return true
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
}

internal fun validateAuthState(expected: String, actual: String?, createdAt: Long, now: Long) {
    require(actual != null && MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))) { "Sign-in state mismatch" }
    require(now - createdAt in 0..600_000) { "Sign-in expired; reconnect Spotify" }
}
