package dev.echo.data.spotify

import dev.echo.core.Track
import org.json.JSONObject
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

data class FilePlaylist(val name: String, val tracks: List<Track>)
data class SpotifyHistoryEntry(val timestamp: String, val track: Track, val msPlayed: Long)
data class SpotifySavedItem(val name: String, val uri: String, val artist: String = "")
data class PlaylistFile(
    val playlists: List<FilePlaylist>,
    val skipped: Int,
    val history: List<SpotifyHistoryEntry> = emptyList(),
    val savedArtists: List<SpotifySavedItem> = emptyList(),
    val savedAlbums: List<SpotifySavedItem> = emptyList(),
)

/** Parses supported library, playlist, and listening-history JSON from a Spotify account export in memory. */
fun parseSpotifyAccountZip(bytes: ByteArray): PlaylistFile {
    require(bytes.isNotEmpty() && bytes.size <= 50 * 1024 * 1024) { "Spotify export ZIP must be 50 MB or smaller" }
    require(bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
        ((bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) || (bytes[2] == 5.toByte() && bytes[3] == 6.toByte()))) {
        "Invalid ZIP archive"
    }
    val expectedEntries = validateSpotifyZipDirectory(bytes)
    val playlists = mutableListOf<FilePlaylist>()
    val history = mutableListOf<SpotifyHistoryEntry>()
    val savedArtists = mutableListOf<SpotifySavedItem>()
    val savedAlbums = mutableListOf<SpotifySavedItem>()
    val recognized = mutableSetOf<String>()
    var skipped = 0
    var totalUncompressed = 0L
    var entries = 0
    ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            require(++entries <= 1100) { "Spotify export contains too many files" }
            val name = entry.name.substringAfterLast('/')
            val isLibrary = name.equals("YourLibrary.json", ignoreCase = true)
            val isPlaylist = Regex("Playlist\\d*\\.json", RegexOption.IGNORE_CASE).matches(name)
            val isHistory = Regex("StreamingHistory\\d+\\.json", RegexOption.IGNORE_CASE).matches(name) ||
                Regex("Streaming[_]?History[_]?music[_]?\\d+\\.json", RegexOption.IGNORE_CASE).matches(name) ||
                Regex("Streaming[_]?History[_]?Audio[_]?[^/]+\\.json", RegexOption.IGNORE_CASE).matches(name)
            if (!entry.isDirectory && (isLibrary || isPlaylist || isHistory)) {
                require(recognized.add(name.lowercase())) { "Spotify export contains duplicate $name files" }
            }
            val captured = if (!entry.isDirectory && (isLibrary || isPlaylist || isHistory)) ByteArrayOutputStream() else null
            val buffer = ByteArray(8192)
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                totalUncompressed += count
                require(totalUncompressed <= 50L * 1024 * 1024) { "Spotify export expands beyond 50 MB" }
                if (captured != null) {
                    require(captured.size() + count <= if (isHistory) 20_000_000 else 10 * 1024 * 1024) {
                        if (isHistory) "Spotify history file exceeds 20 MB" else "$name is larger than 10 MB"
                    }
                    captured.write(buffer, 0, count)
                }
            }
            if (captured != null) {
                val text = captured.toString(Charsets.UTF_8.name())
                if (isHistory) {
                    val parsed = parseSpotifyHistory(text)
                    history += parsed.first
                    skipped += parsed.second
                } else {
                    val parsed = parsePlaylistFile(text, name)
                    playlists += parsed.playlists
                    skipped += parsed.skipped
                    savedArtists += parsed.savedArtists
                    savedAlbums += parsed.savedAlbums
                }
            }
            zip.closeEntry()
        }
    }
    require(entries == expectedEntries) { "Invalid ZIP archive: entry count mismatch" }
    require(playlists.size <= 1000 && playlists.sumOf { it.tracks.size } <= 50_000 && history.size <= 50_000 &&
        savedArtists.size <= 50_000 && savedAlbums.size <= 50_000) {
        "Spotify export exceeds the 50,000 item import limit"
    }
    return PlaylistFile(playlists, skipped, history, savedArtists, savedAlbums)
}

private fun parseSpotifyHistory(text: String): Pair<List<SpotifyHistoryEntry>, Int> {
    require(text.length <= 20_000_000) { "Spotify history file exceeds 20 MB" }
    val rows = org.json.JSONArray(text.removePrefix("\uFEFF").trim())
    require(rows.length() <= 50_000) { "Spotify history file exceeds 50,000 entries" }
    var skipped = 0
    val history = (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index)
        val timestamp = row?.optString("ts")?.takeIf { !it.isNullOrBlank() }
            ?: row?.optString("endTime")?.takeIf { !it.isNullOrBlank() }
        val title = row?.optString("master_metadata_track_name")?.takeIf { !it.isNullOrBlank() }
            ?: row?.optString("trackName").orEmpty()
        val artist = row?.optString("master_metadata_album_artist_name")?.takeIf { !it.isNullOrBlank() }
            ?: row?.optString("artistName").orEmpty()
        val album = row?.optString("master_metadata_album_album_name")?.takeIf { !it.isNullOrBlank() }
            ?: row?.optString("albumName").orEmpty()
        val uri = row?.optString("spotify_track_uri")?.takeIf { !it.isNullOrBlank() }
            ?: row?.optString("trackUri").orEmpty()
        val played = row?.optLong("ms_played", -1)?.takeIf { it >= 0 }
            ?: row?.optLong("msPlayed", -1)?.takeIf { it >= 0 }
        if (row == null || timestamp == null || !validSpotifyTimestamp(timestamp) ||
            title.isBlank() || artist.isBlank() || played == null) {
            skipped++
            null
        } else runCatching { SpotifyHistoryEntry(timestamp, fileTrack(title, artist, album, uri, 0), played) }
            .getOrElse { skipped++; null }
    }
    return history to skipped
}

private fun validSpotifyTimestamp(value: String): Boolean {
    if (value.length !in 16..40) return false
    val normalized = value.replace(' ', 'T')
    return runCatching {
        if (normalized.endsWith("Z") || Regex("[+-]\\d{2}:\\d{2}$").containsMatchIn(normalized))
            java.time.OffsetDateTime.parse(normalized)
        else java.time.LocalDateTime.parse(normalized)
    }.isSuccess
}

private fun validateSpotifyZipDirectory(bytes: ByteArray): Int {
    fun u16(offset: Int): Int = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    fun u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
    val eocd = (bytes.size - 22 downTo (bytes.size - 65_557).coerceAtLeast(0)).firstOrNull {
        u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == bytes.size
    } ?: throw IllegalArgumentException("Invalid ZIP archive: missing directory")
    val count = u16(eocd + 10)
    require(u16(eocd + 4) == 0 && u16(eocd + 6) == 0 && u16(eocd + 8) == count && count <= 1100) { "Invalid ZIP archive: unsupported directory" }
    val size = u32(eocd + 12)
    val offset = u32(eocd + 16)
    require(offset + size == eocd.toLong() && offset <= Int.MAX_VALUE && size <= Int.MAX_VALUE) { "Invalid ZIP archive: directory bounds" }
    var cursor = offset.toInt()
    repeat(count) {
        require(cursor.toLong() + 46 <= eocd && u32(cursor) == 0x02014b50L) { "Invalid ZIP archive: directory entry" }
        val length = 46L + u16(cursor + 28) + u16(cursor + 30) + u16(cursor + 32)
        require(cursor.toLong() + length <= eocd && u32(cursor + 42) < offset) { "Invalid ZIP archive: directory entry bounds" }
        cursor += length.toInt()
    }
    require(cursor == eocd) { "Invalid ZIP archive: directory size mismatch" }
    return count
}

internal fun parsePlaylistFile(text: String, filename: String): PlaylistFile {
    require(text.length <= 10_000_000) { "Playlist file is larger than 10 MB" }
    val input = text.removePrefix("\uFEFF").trim()
    var skipped = 0
    var savedArtists = emptyList<SpotifySavedItem>()
    var savedAlbums = emptyList<SpotifySavedItem>()
    val playlists = if (input.startsWith("{")) {
        val root = JSONObject(input)
        if (root.has("tracks")) {
            fun saved(key: String, kind: String): List<SpotifySavedItem> {
                val entries = root.optJSONArray(key) ?: return emptyList()
                require(entries.length() <= 50_000) { "Import up to 50,000 saved items at a time" }
                return (0 until entries.length()).mapNotNull { index ->
                    val entry = entries.optJSONObject(index) ?: run { skipped++; return@mapNotNull null }
                    val name = entry.optString(if (kind == "album") "album" else "name").trim()
                    val artist = if (kind == "album") entry.optString("artist").trim() else ""
                    val uri = entry.optString("uri").trim()
                    if (name.isBlank() || name.length > 1000 || artist.length > 1000 || !Regex("spotify:$kind:[a-zA-Z0-9]{22}").matches(uri)) {
                        skipped++
                        null
                    } else SpotifySavedItem(name, uri, artist)
                }
            }
            savedArtists = saved("artists", "artist")
            savedAlbums = saved("albums", "album")
            val entries = root.getJSONArray("tracks")
            require(entries.length() <= 50_000) { "Import up to 50,000 tracks at a time" }
            val tracks = (0 until entries.length()).mapNotNull { index ->
                val entry = entries.optJSONObject(index)
                val title = entry?.optString("track").orEmpty()
                val artist = entry?.optString("artist").orEmpty()
                val album = entry?.optString("album").orEmpty()
                val uri = entry?.optString("uri").orEmpty()
                if (title.isBlank() || artist.isBlank()) { skipped++; null }
                else runCatching { fileTrack(title, artist, album, uri, 0) }
                    .getOrElse { skipped++; null }
            }
            // Metadata is attached by the ZIP parser; direct single-file imports retain the old playlist contract.
            listOf(FilePlaylist("Spotify Liked Songs", tracks))
        } else {
            val entries = root.getJSONArray("playlists")
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
    return PlaylistFile(playlists, skipped, savedArtists = savedArtists, savedAlbums = savedAlbums)
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
