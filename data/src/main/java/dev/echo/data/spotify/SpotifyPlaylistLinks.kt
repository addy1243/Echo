package dev.echo.data.spotify

import dev.echo.core.Track
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class SpotifyLinkPreview(val name: String, val tracks: List<Track>, val total: Int?) {
    val complete: Boolean get() = total != null && total == tracks.size
}

internal fun spotifyPlaylistLinkId(input: String): String {
    val uri = runCatching { URI(input.trim()) }.getOrNull()
    require(uri != null && uri.scheme == "https" && uri.host == "open.spotify.com" &&
        uri.userInfo == null && uri.port in setOf(-1, 443) && uri.fragment == null) {
        "Paste a full https://open.spotify.com/playlist/… link from Spotify"
    }
    return Regex("/playlist/([a-zA-Z0-9]{22})/?").matchEntire(uri.path)?.groupValues?.get(1)
        ?: error("This is not a Spotify playlist link")
}

@Singleton
class SpotifyPlaylistLinks @Inject constructor() {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    suspend fun preview(link: String): SpotifyLinkPreview = withContext(Dispatchers.IO) {
        val id = spotifyPlaylistLinkId(link)
        val embedded = parseSpotifyEmbed(page("https://open.spotify.com/embed/playlist/$id"), id)
        currentCoroutineContext().ensureActive()
        // shortcut: public page formats can change; unknown totals must remain visibly unverified.
        val total = try { spotifyPublicPlaylistCount(page("https://open.spotify.com/playlist/$id"), id) }
            catch (_: IOException) { null }
            catch (_: IllegalArgumentException) { null }
            catch (_: org.json.JSONException) { null }
        currentCoroutineContext().ensureActive()
        require(total == null || total >= embedded.tracks.size) { "Spotify returned inconsistent playlist counts. Try again later." }
        embedded.copy(total = total)
    }

    private fun page(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "EchoMusic/0.1.5 (public playlist metadata)").build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 429) throw IOException("Spotify is rate limiting requests. Try importing later.")
            if (!response.isSuccessful) throw IOException("Spotify couldn't expose this public playlist (${response.code}). Try a playlist file instead.")
            (response.body ?: throw IOException("Spotify returned an empty page")).charStream().use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(text.length + count <= 4_000_000) { "Spotify's page is too large to import safely" }
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
        }
    }
}

internal fun parseSpotifyEmbed(html: String, id: String): SpotifyLinkPreview {
    val root = JSONObject(spotifyPageScript(html, "__NEXT_DATA__"))
    val entity = root.getJSONObject("props").getJSONObject("pageProps").getJSONObject("state")
        .getJSONObject("data").getJSONObject("entity")
    require(entity.optString("uri") == "spotify:playlist:$id") { "Spotify returned a different playlist" }
    val name = entity.getString("name").trim()
    require(name.isNotEmpty() && name.length <= 200) { "Spotify returned an invalid playlist name" }
    val list = entity.getJSONArray("trackList")
    require(list.length() <= 50_000) { "Spotify returned too many tracks" }
    val tracks = (0 until list.length()).mapNotNull { index ->
        val item = list.getJSONObject(index)
        if (item.optString("entityType") != "track") return@mapNotNull null
        val trackId = Regex("spotify:track:([a-zA-Z0-9]{22})").matchEntire(item.getString("uri"))?.groupValues?.get(1)
            ?: error("Spotify returned an invalid song identity")
        val title = item.getString("title").trim()
        val artist = item.getString("subtitle").trim()
        val duration = item.getLong("duration")
        require(title.isNotEmpty() && artist.isNotEmpty() && title.length <= 1000 && artist.length <= 1000 && duration >= 0) { "Spotify returned incomplete song metadata" }
        Track("spotify:$trackId", title, artist, durationMs = duration, provider = "spotify")
    }
    require(tracks.isNotEmpty()) { "No public song list is available. Private or restricted playlists need a file export." }
    return SpotifyLinkPreview(name, tracks, null)
}

internal fun spotifyPublicPlaylistCount(html: String, id: String): Int? {
    val decoded = String(Base64.getDecoder().decode(spotifyPageScript(html, "initialState")), Charsets.UTF_8)
    val playlist = JSONObject(decoded).getJSONObject("entities").getJSONObject("items").getJSONObject("spotify:playlist:$id")
    require(playlist.getString("uri") == "spotify:playlist:$id")
    return playlist.getJSONObject("content").getInt("totalCount").takeIf { it >= 0 }
}

private fun spotifyPageScript(html: String, id: String): String =
    Regex("<script\\b[^>]*\\bid=[\"']$id[\"'][^>]*>(.*?)</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        .find(html)?.groupValues?.get(1) ?: throw IllegalArgumentException("Spotify's public page format changed or is unavailable. Try a file export.")
