package dev.echo.data.spotify

import dev.echo.core.Track
import org.json.JSONObject
import java.util.UUID

internal data class FilePlaylist(val name: String, val tracks: List<Track>)
internal data class PlaylistFile(val playlists: List<FilePlaylist>, val skipped: Int)

internal fun parsePlaylistFile(text: String, filename: String): PlaylistFile {
    require(text.length <= 10_000_000) { "Playlist file is larger than 10 MB" }
    val input = text.removePrefix("\uFEFF").trim()
    var skipped = 0
    val playlists = if (input.startsWith("{")) {
        val entries = JSONObject(input).getJSONArray("playlists")
        require(entries.length() <= 1000) { "Import up to 1,000 playlists at a time" }
        List(entries.length()) { index ->
            val playlist = entries.getJSONObject(index)
            val items = playlist.getJSONArray("items")
            val tracks = (0 until items.length()).mapNotNull { position ->
                val track = items.getJSONObject(position).optJSONObject("track")
                if (track == null) { skipped++; null } else fileTrack(
                    track.optString("trackName"), track.optString("artistName"),
                    track.optString("albumName"), track.optString("trackUri"), 0,
                )
            }
            FilePlaylist(playlist.getString("name").trim(), tracks)
        }
    } else {
        val rows = parseCsv(input)
        require(rows.isNotEmpty()) { "The playlist file is empty" }
        val headers = rows.first().map { it.trim() }
        require(listOf("Track URI", "Track Name", "Artist Name(s)").all { it in headers }) {
            "Choose an Exportify CSV or a Spotify playlist JSON export"
        }
        require(headers.distinct().size == headers.size) { "Duplicate CSV columns" }
        val tracks = rows.drop(1).filterNot { row -> row.all(String::isBlank) }.map { row ->
            require(row.size == headers.size) { "A CSV row has missing or extra columns" }
            fun value(name: String) = headers.indexOf(name).takeIf { it >= 0 }?.let(row::get).orEmpty()
            val duration = value("Track Duration (ms)").ifBlank { value("Duration (ms)") }
                .let { if (it.isBlank()) 0 else it.toLongOrNull() ?: error("Invalid track duration") }
            fileTrack(value("Track Name"), value("Artist Name(s)"), value("Album Name"), value("Track URI"), duration)
        }
        listOf(FilePlaylist(filename.substringBeforeLast('.').ifBlank { "Imported playlist" }, tracks))
    }
    require(playlists.isNotEmpty() && playlists.all { it.name.isNotBlank() && it.name.length <= 200 }) { "Playlists need names of 1–200 characters" }
    require(playlists.size <= 1000 && playlists.sumOf { it.tracks.size } <= 50_000) { "Import up to 1,000 playlists and 50,000 tracks at a time" }
    return PlaylistFile(playlists, skipped)
}

private fun fileTrack(title: String, artist: String, album: String, uri: String, duration: Long): Track {
    require(title.isNotBlank() && artist.isNotBlank() && duration >= 0) { "A song is missing its title/artist or has an invalid duration" }
    require(listOf(title, artist, album).all { it.length <= 1000 }) { "Song metadata fields must be 1,000 characters or fewer" }
    val spotifyId = Regex("spotify:track:([a-zA-Z0-9]{22})").matchEntire(uri)?.groupValues?.get(1)
    val id = spotifyId ?: "file:${UUID.nameUUIDFromBytes("$title\u0000$artist\u0000$album".toByteArray(Charsets.UTF_8))}"
    return Track("spotify:$id", title.trim(), artist.trim(), album.trim(), duration, provider = "spotify")
}

internal fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var closed = false
    var index = 0
    while (index < text.length) {
        val c = text[index++]
        if (quoted) {
            if (c == '"') {
                if (text.getOrNull(index) == '"') { field.append('"'); index++ }
                else { quoted = false; closed = true }
            } else field.append(c)
        } else when (c) {
            '"' -> { require(field.isEmpty() && !closed) { "Invalid CSV quoting" }; quoted = true }
            ',' -> { row.add(field.toString()); field.clear(); closed = false }
            '\r', '\n' -> {
                if (c == '\r' && text.getOrNull(index) == '\n') index++
                row.add(field.toString()); rows.add(row); row = mutableListOf(); field.clear(); closed = false
                require(rows.size <= 50_001) { "Import up to 50,000 CSV songs at a time" }
            }
            else -> { require(!closed) { "Unexpected text after a quoted CSV field" }; field.append(c) }
        }
    }
    require(!quoted) { "Unclosed CSV quote" }
    if (row.isNotEmpty() || field.isNotEmpty() || closed) { row.add(field.toString()); rows.add(row) }
    return rows
}
