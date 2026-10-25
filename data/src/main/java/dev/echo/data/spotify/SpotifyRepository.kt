package dev.echo.data.spotify

import android.content.Context
import android.net.Uri
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.echo.core.Track
import dev.echo.data.library.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class SpotifyPlaylist(val id: String, val name: String)

@Singleton
class SpotifyRepository @Inject constructor(@ApplicationContext context: Context, private val library: LibraryRepository) {
    private val credentials = SecureCredentials(context)
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private val lock = Mutex()
    private val credentialRevision = AtomicLong()
    private val _connected = MutableStateFlow(runCatching { credentials.get("spotify.tokens") }.getOrNull() != null)
    val connected = _connected.asStateFlow()

    @Synchronized fun beginAuth(clientId: String, redirectUri: String): Uri {
        require(clientId.matches(Regex("[a-zA-Z0-9]{32}"))) { "Enter your Spotify client ID" }
        require(supportedSpotifyRedirect(redirectUri)) { "Register Echo's exact callback URI in the Spotify developer dashboard" }
        fun random() = Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val verifier = random()
        val state = random()
        credentialRevision.incrementAndGet()
        credentials.set("spotify.pending", JSONObject().put("client", clientId).put("redirect", redirectUri).put("verifier", verifier).put("state", state).put("created", System.currentTimeMillis()).toString())
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId).appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri).appendQueryParameter("scope", "playlist-read-private playlist-read-collaborative")
            .appendQueryParameter("code_challenge_method", "S256").appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state).build()
    }

    suspend fun handleCallback(uri: Uri) = withContext(Dispatchers.IO) { lock.withLock {
        val (revision, pending) = synchronized(this@SpotifyRepository) {
            val revision = credentialRevision.get()
            val pending = credentials.get("spotify.pending")?.let(::JSONObject) ?: error("No pending Spotify sign-in")
            val redirect = Uri.parse(pending.getString("redirect"))
            require(uri.scheme == redirect.scheme && uri.authority == redirect.authority && uri.path == redirect.path) { "Unexpected sign-in callback" }
            require(uri.getQueryParameters("state").size == 1) { "Sign-in state mismatch" }
            validateAuthState(pending.getString("state"), uri.getQueryParameter("state"), pending.getLong("created"), System.currentTimeMillis())
            credentials.set("spotify.pending", null)
            revision to pending
        }
        require(uri.getQueryParameter("error") == null) { "Spotify sign-in was not authorized" }
        val code = uri.getQueryParameter("code") ?: error("Missing Spotify authorization code")
        val tokens = token(FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
            .add("client_id", pending.getString("client")).add("redirect_uri", pending.getString("redirect"))
            .add("code_verifier", pending.getString("verifier")).build(), revision)
        saveTokens(tokens, pending.getString("client"), expectedRevision = revision)
    } }

    @Synchronized fun disconnect() {
        credentialRevision.incrementAndGet()
        credentials.set("spotify.tokens", null)
        credentials.set("spotify.pending", null)
        _connected.value = false
    }

    @Synchronized private fun disconnectIfCurrent(expectedRevision: Long) {
        if (credentialRevision.get() == expectedRevision) disconnect()
    }

    @Synchronized private fun saveTokens(tokens: JSONObject, clientId: String, oldRefresh: String? = null, expectedRevision: Long) {
        check(credentialRevision.get() == expectedRevision) { "Spotify connection changed; reconnect if needed" }
        require(tokens.optString("access_token").isNotBlank()) { "Spotify returned no token" }
        val refresh = tokens.optString("refresh_token").ifBlank { oldRefresh ?: error("Spotify returned no refresh token") }
        credentials.set("spotify.tokens", JSONObject().put("access", tokens.getString("access_token")).put("refresh", refresh)
            .put("client", clientId).put("expires", System.currentTimeMillis() + tokens.getLong("expires_in") * 1000).toString())
        _connected.value = true
    }

    private fun token(body: FormBody, expectedRevision: Long): JSONObject = client.newCall(Request.Builder().url("https://accounts.spotify.com/api/token").post(body).build()).execute().use {
        if (!it.isSuccessful) {
            if (it.code == 400 || it.code == 401) disconnectIfCurrent(expectedRevision)
            throw IOException("Spotify authorization failed (${it.code}); reconnect if access expired or was revoked")
        }
        JSONObject(it.body?.string() ?: throw IOException("Empty token response"))
    }

    private suspend fun accessToken(): Pair<Long, String> = lock.withLock {
        val revision = credentialRevision.get()
        var saved = credentials.get("spotify.tokens")?.let(::JSONObject) ?: error("Connect Spotify first")
        if (saved.getLong("expires") <= System.currentTimeMillis() + 60_000) {
            val tokens = token(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", saved.getString("refresh")).add("client_id", saved.getString("client")).build(), revision)
            saveTokens(tokens, saved.getString("client"), saved.getString("refresh"), revision)
            saved = JSONObject(credentials.get("spotify.tokens")!!)
        }
        check(credentialRevision.get() == revision) { "Spotify connection changed; retry the request" }
        revision to saved.getString("access")
    }

    private suspend fun get(url: String): JSONObject {
        val parsed = url.toHttpUrl()
        require(parsed.isHttps && parsed.host == "api.spotify.com" && parsed.encodedPath.startsWith("/v1/"))
        val (revision, access) = accessToken()
        val request = Request.Builder().url(parsed).header("Authorization", "Bearer $access").build()
        return client.newCall(request).execute().use {
            if (it.code == 401) disconnectIfCurrent(revision)
            if (it.code == 429) throw IOException("Spotify rate limit; retry after ${it.header("Retry-After") ?: "a few"} seconds")
            if (!it.isSuccessful) throw IOException("Spotify request failed (${it.code}); playlist access may be restricted")
            JSONObject(it.body?.string() ?: throw IOException("Empty Spotify response"))
        }
    }

    private suspend fun pages(first: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val visited = mutableSetOf<String>()
        var next: String? = first
        while (next != null) {
            check(visited.add(next)) { "Spotify returned repeated pagination" }
            val page = get(next)
            val items = page.getJSONArray("items")
            for (index in 0 until items.length()) items.optJSONObject(index)?.let(result::add)
            next = page.optString("next").takeIf { it.isNotBlank() && it != "null" }
        }
        return result
    }

    suspend fun playlists(): List<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        pages("https://api.spotify.com/v1/me/playlists?limit=50").map { SpotifyPlaylist(it.getString("id"), it.getString("name")) }
    }

    suspend fun importPlaylist(id: String) = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[a-zA-Z0-9]{22}"))) { "Invalid Spotify playlist ID" }
        val metadata = get("https://api.spotify.com/v1/playlists/$id")
        val tracks = pages("https://api.spotify.com/v1/playlists/$id/items?limit=50").mapNotNull { entry ->
            val item = entry.optJSONObject("item") ?: return@mapNotNull null
            if (item.optString("type") != "track") return@mapNotNull null
            val trackId = item.optString("id").takeIf { it.isNotBlank() && it != "null" }
            val artists = item.optJSONArray("artists")
            val artist = (0 until (artists?.length() ?: 0)).joinToString(", ") { artists!!.getJSONObject(it).optString("name") }
            val album = item.optJSONObject("album")
            Track(id = "spotify:${trackId ?: item.optString("uri")}", title = item.optString("name", "Unavailable track"), artist = artist,
                album = album?.optString("name").orEmpty(), durationMs = item.optLong("duration_ms"),
                artworkUri = album?.optJSONArray("images")?.optJSONObject(0)?.optString("url"), provider = "spotify",
                isrc = item.optJSONObject("external_ids")?.optString("isrc"))
        }
        library.importPlaylist("spotify:$id", metadata.getString("name"), tracks, id)
    }
}
