package dev.echo.app

import java.net.URI

internal fun playerArtwork(uri: String?): String? {
    val source = uri ?: return null
    val url = runCatching { URI(source) }.getOrNull() ?: return uri
    val host = url.host ?: return uri
    if (url.scheme != "https" || url.rawUserInfo != null || url.port !in listOf(-1, 443) ||
        (host != "googleusercontent.com" && !host.endsWith(".googleusercontent.com")) ||
        url.rawQuery != null || url.rawFragment != null) return uri
    // shortcut: only recognized Music resize suffixes, fall back to the supplied image on failure.
    val suffix = Regex("=w[0-9]+-h[0-9]+(-[a-zA-Z0-9]+)*$")
    return suffix.replace(source, "=w1200-h1200-l90-rj")
}
