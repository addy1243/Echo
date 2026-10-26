package dev.echo.app.account

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudPlaylistSnapshotTest {
    @Test fun namespacesDeviceFilesAndRestoresOnlyTheirOwningInstallation() {
        val first = "11111111-1111-4111-8111-111111111111"
        val second = "22222222-2222-4222-8222-222222222222"
        val source = snapshot("Device", listOf(track("local:123", "local", "content://media/123")), listOf("local:123", "local:123")).toString()
        val firstCloud = qualifyCloudLocalTracks(source, first)
        val secondCloud = qualifyCloudLocalTracks(source, second)
        assertNotEquals(ids(JSONObject(firstCloud).getJSONArray("playlists").getJSONObject(0)), ids(JSONObject(secondCloud).getJSONArray("playlists").getJSONObject(0)))
        assertEquals(firstCloud, qualifyCloudLocalTracks(firstCloud, second))
        val restored = JSONObject(restoreOwnLocalTracks(firstCloud, first))
        assertEquals(listOf("local:123", "local:123"), ids(restored.getJSONArray("playlists").getJSONObject(0)))
        assertTrue(restored.getJSONArray("tracks").getJSONObject(0).isNull("sourceUri"))
        assertEquals(firstCloud, restoreOwnLocalTracks(firstCloud, second))
    }

    @Test fun rejectsMalformedOrNonLocalCloudIdentityPrefixes() {
        val device = "11111111-1111-4111-8111-111111111111"
        listOf("cloud-local:bad:local:1" to "local", "cloud-local:$device:local:1" to "youtube").forEach { (id, provider) ->
            val input = snapshot("Invalid", listOf(track(id, provider, null)), listOf(id)).toString()
            assertTrue(runCatching { qualifyCloudLocalTracks(input, device) }.isFailure)
        }
    }
    @Test fun stripsDevicePathsMatchesHistoryAndUnusedTracks() {
        val input = snapshot("Local", listOf(
            track("local:one", "local", "content://media/external/audio/1").put("artworkUri", "file:///private/photo.jpg"),
            track("spotify:two", "spotify", "file:///private/music.mp3"),
            track("unused", "local", "file:///private/unused.mp3")), listOf("local:one", "spotify:two", "local:one"))
            .put("history", JSONArray().put("private listening"))
            .put("favorites", JSONArray().put("unused"))
            .put("localMatches", JSONArray().put(JSONObject().put("importedTrackId", "spotify:two").put("localTrackId", "local:one")))
        val output = JSONObject(sanitizeCloudBackup(input.toString()))
        assertEquals("echo-library", output.getString("format"))
        assertEquals(1, output.getInt("version"))
        assertEquals(2, output.getJSONArray("tracks").length())
        repeat(2) { assertTrue(output.getJSONArray("tracks").getJSONObject(it).isNull("sourceUri")) }
        assertTrue(output.getJSONArray("tracks").getJSONObject(0).isNull("artworkUri"))
        assertFalse(output.has("history"))
        assertEquals(0, output.getJSONArray("favorites").length())
        assertEquals(0, output.getJSONArray("localMatches").length())
        assertEquals(listOf("local:one", "spotify:two", "local:one"), ids(output.getJSONArray("playlists").getJSONObject(0)))
        assertEquals(output.toString(), sanitizeCloudBackup(output.toString()))
    }

    @Test fun permitsOnlyStableIdentityCheckedYoutubeAndTrustedCommonsSources() {
        val video = "abcdefghijk"
        val sources = listOf(
            track("youtube:$video", "youtube", "https://www.youtube.com/watch?v=$video"),
            track("commons:one", "commons", "https://upload.wikimedia.org/audio/song.ogg"),
            track("youtube:wrong", "youtube", "https://www.youtube.com/watch?v=$video"),
            track("youtube:query", "youtube", "https://www.youtube.com/watch?v=$video&token=secret"),
            track("commons:evil", "commons", "https://upload.wikimedia.org.evil.test/audio.ogg"),
            track("unknown", "custom", "https://example.com/audio.mp3"))
        val output = JSONObject(sanitizeCloudBackup(snapshot("Sources", sources, sources.map { it.getString("id") }).toString())).getJSONArray("tracks")
        assertEquals("https://www.youtube.com/watch?v=$video", output.getJSONObject(0).getString("sourceUri"))
        assertEquals("https://upload.wikimedia.org/audio/song.ogg", output.getJSONObject(1).getString("sourceUri"))
        (2 until sources.size).forEach { assertTrue(output.getJSONObject(it).isNull("sourceUri")) }
    }

    @Test fun mergesConflictsWithLocalMetadataPriorityAndRemoteNewTracks() {
        val baseline = snapshot("Original", listOf(track("one", "local", "content://one")), listOf("one")).toString()
        val local = snapshot("Local edit", listOf(track("one", "local", "content://one").put("title", "Local title")), listOf("one", "one")).toString()
        val remote = snapshot("Remote edit", listOf(track("one", "local", null).put("title", "Remote title"), track("two", "local", "file:///remote")), listOf("two", "one")).toString()
        val result = JSONObject(mergeCloudBackups(baseline, local, remote))
        assertEquals(2, result.getJSONArray("playlists").length())
        assertEquals(listOf("one", "one"), ids(result.getJSONArray("playlists").getJSONObject(0)))
        assertEquals(listOf("two", "one"), ids(result.getJSONArray("playlists").getJSONObject(1)))
        val songs = result.getJSONArray("tracks")
        val first = (0 until songs.length()).map { songs.getJSONObject(it) }.first { it.getString("id") == "one" }
        assertEquals("Local title", first.getString("title"))
        val repeated = JSONObject(mergeCloudBackups(baseline, result.toString(), remote))
        assertEquals(result.getJSONArray("playlists").toString(), repeated.getJSONArray("playlists").toString())
    }

    @Test fun rejectsMissingReferencesAndDuplicateIdentities() {
        val valid = snapshot("Valid", listOf(track("one", "local", null)), listOf("one"))
        val invalid = snapshot("Invalid", emptyList(), listOf("missing"))
        assertTrue(runCatching { sanitizeCloudBackup(invalid.toString()) }.isFailure)
        valid.getJSONArray("tracks").put(track("one", "local", null))
        assertTrue(runCatching { sanitizeCloudBackup(valid.toString()) }.isFailure)
        valid.put("version", 100)
        assertTrue(runCatching { sanitizeCloudBackup(valid.toString()) }.isFailure)
    }

    private fun ids(playlist: JSONObject): List<String> = playlist.getJSONArray("trackIds").let { array -> List(array.length()) { array.getString(it) } }
    private fun snapshot(name: String, tracks: List<JSONObject>, ids: List<String>): JSONObject = JSONObject()
        .put("format", "echo-library").put("version", 1).put("tracks", JSONArray(tracks))
        .put("playlists", JSONArray().put(JSONObject().put("id", "playlist").put("name", name).put("spotifyId", "spotify-playlist").put("trackIds", JSONArray(ids))))
        .put("favorites", JSONArray()).put("localMatches", JSONArray())
    private fun track(id: String, provider: String, uri: String?): JSONObject = JSONObject()
        .put("id", id).put("title", "Song").put("artist", "Artist").put("album", "Album").put("durationMs", 180_000)
        .put("provider", provider).put("sourceUri", uri ?: JSONObject.NULL).put("artworkUri", JSONObject.NULL)
        .put("isrc", JSONObject.NULL).put("attribution", JSONObject.NULL).put("licenseUrl", JSONObject.NULL).put("sourcePage", JSONObject.NULL)
}
