package dev.echo.data.spotify

import androidx.test.platform.app.InstrumentationRegistry
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SpotifyPlaylistLinksTest {
    private val id = "1234567890123456789012"
    private val track = JSONObject().put("uri", "spotify:track:$id").put("title", "Song").put("subtitle", "Artist")
        .put("duration", 1000).put("entityType", "track").put("audioPreview", JSONObject().put("url", "https://example.com/not-imported.mp3"))
    private fun embed(playlistId: String = id): String {
        val entity = JSONObject().put("uri", "spotify:playlist:$playlistId").put("name", "A playlist")
            .put("trackList", JSONArray().put(track).put(track))
        val root = JSONObject().put("props", JSONObject().put("pageProps", JSONObject().put("state", JSONObject().put("data", JSONObject().put("entity", entity)))))
        return "<script type=\"application/json\" id=\"__NEXT_DATA__\">$root</script>"
    }
    private fun page(total: Int): String {
        val root = JSONObject().put("entities", JSONObject().put("items", JSONObject().put("spotify:playlist:$id",
            JSONObject().put("uri", "spotify:playlist:$id").put("content", JSONObject().put("totalCount", total)))))
        return "<script id='initialState' type='text/plain'>${Base64.getEncoder().encodeToString(root.toString().toByteArray())}</script>"
    }
    @Test fun extractsMetadataPreservesDuplicatesAndIgnoresPreviewAudio() {
        val preview = parseSpotifyEmbed(embed(), id)
        assertEquals(2, preview.tracks.size)
        assertEquals(preview.tracks[0], preview.tracks[1])
        assertTrue(preview.tracks.all { it.sourceUri == null && it.provider == "spotify" })
        assertFalse(preview.complete)
        assertTrue(preview.copy(total = spotifyPublicPlaylistCount(page(2), id)).complete)
        assertFalse(preview.copy(total = spotifyPublicPlaylistCount(page(100), id)).complete)
    }
    @Test fun rejectsWrongPlaylistAndChangedOrInvalidData() {
        assertTrue(runCatching { parseSpotifyEmbed(embed("abcdefghijklmnopqrstuv"), id) }.isFailure)
        assertTrue(runCatching { parseSpotifyEmbed("<html>Sign in</html>", id) }.isFailure)
        track.put("duration", -1)
        assertTrue(runCatching { parseSpotifyEmbed(embed(), id) }.isFailure)
    }
    @Test fun livePublicPlaylistPreview() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e runLiveSpotifyLink true and -e playlistLink <public URL>", arguments.getString("runLiveSpotifyLink") == "true")
        val preview = SpotifyPlaylistLinks().preview(requireNotNull(arguments.getString("playlistLink")))
        assertTrue(preview.tracks.isNotEmpty())
        assertTrue(preview.tracks.all { it.sourceUri == null })
        arguments.getString("expectedCount")?.toInt()?.let { expected ->
            assertEquals(expected, preview.tracks.size)
            assertEquals(expected, preview.total)
            assertTrue(preview.complete)
        }
        Unit
    }
}
