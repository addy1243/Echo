package dev.echo.data.spotify

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpotifyPlaylistLinksTest {
    private val id = "1234567890123456789012"

    @Test fun skipsOnlyKnownUnplayablePlaceholderAndKeepsOccurrences() {
        val valid = JSONObject().put("uri", "spotify:track:$id").put("title", "Song").put("subtitle", "Artist")
            .put("duration", 1000).put("entityType", "track")
        val placeholder = JSONObject().put("uri", "spotify:track:abcdefghijklmnopqrstuv").put("title", "").put("subtitle", "")
            .put("duration", 0).put("entityType", "track").put("isPlayable", false)
        val preview = parseSpotifyEmbed(embed(JSONArray().put(valid).put(placeholder).put(valid)), id)

        assertEquals(listOf("spotify:$id", "spotify:$id"), preview.tracks.map { it.id })
        assertTrue(preview.skippedUnplayablePlaceholder)
        assertEquals(3, preview.copy(total = 3).total)
        assertFalse(preview.copy(total = 3).complete)
    }

    @Test fun rejectsOtherInvalidTrackMetadata() {
        val invalid = JSONObject().put("uri", "spotify:track:$id").put("title", "Song").put("subtitle", "Artist")
            .put("duration", -1).put("entityType", "track").put("isPlayable", false)
        assertTrue(runCatching { parseSpotifyEmbed(embed(JSONArray().put(invalid)), id) }.isFailure)
    }

    private fun embed(tracks: JSONArray): String {
        val entity = JSONObject().put("uri", "spotify:playlist:$id").put("name", "A playlist").put("trackList", tracks)
        val root = JSONObject().put("props", JSONObject().put("pageProps", JSONObject().put("state", JSONObject().put("data", JSONObject().put("entity", entity)))))
        return "<script id='__NEXT_DATA__'>$root</script>"
    }
}
