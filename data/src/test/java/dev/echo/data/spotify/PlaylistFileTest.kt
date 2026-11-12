package dev.echo.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PlaylistFileTest {
    @Test fun importsOnlyRecognizedZipFilesAndKeepsOccurrencesInArchiveOrder() {
        val zip = accountZip(
            "ignored.txt" to "private ignored contents",
            "Spotify/Playlist1.json" to """{"playlists":[{"name":"Road","items":[{"track":{"trackName":"A","artistName":"B","trackUri":""}},{"track":{"trackName":"A","artistName":"B","trackUri":""}}]}]}""",
            "YourLibrary.json" to """{"tracks":[{"track":"C","artist":"D","album":"","uri":""}]}""",
        )
        val result = parseSpotifyAccountZip(zip)
        assertEquals(listOf("Road", "Spotify Liked Songs"), result.playlists.map { it.name })
        assertEquals(2, result.playlists.first().tracks.size)
        assertEquals(result.playlists.first().tracks[0], result.playlists.first().tracks[1])
    }

    @Test fun rejectsDuplicateRecognizedFilesAndMissingRecognizedFiles() {
        assertFailure("duplicate") { parseSpotifyAccountZip(accountZip("one/Playlist1.json" to "{\"playlists\":[{\"name\":\"P\",\"items\":[]}]}", "two/Playlist1.json" to "{}")) }
        val historyOnly = parseSpotifyAccountZip(accountZip("StreamingHistory_music_0.json" to "[]"))
        assertEquals(emptyList<FilePlaylist>(), historyOnly.playlists)
    }

    @Test fun rejectsInvalidZipAndCompressedOversize() {
        assertFailure("ZIP") { parseSpotifyAccountZip(byteArrayOf(1, 2, 3)) }
        assertFailure("50 MB") { parseSpotifyAccountZip(ByteArray(50 * 1024 * 1024 + 1)) }
        val zip = accountZip("StreamingHistory_music_0.json" to "[]")
        assertFailure("ZIP") { parseSpotifyAccountZip(zip.copyOf(zip.size - 22)) }
    }

    @Test fun importsSpotifyAccountYourLibraryTracks() {
        val input = """{"tracks":[
            {"artist":"The Cranberries","album":"No Need To Argue","track":"Zombie","uri":"spotify:track:2IZZqH4K02UIYg5EohpNHF"},
            {"artist":"The Cranberries","album":"No Need To Argue","track":"Zombie","uri":"spotify:track:2IZZqH4K02UIYg5EohpNHF"},
            {"artist":"","album":"Album","track":"Missing artist","uri":"spotify:track:1234567890123456789012"}
        ]}"""

        val imported = parsePlaylistFile(input, "YourLibrary.json")

        assertEquals("Spotify Liked Songs", imported.playlists.single().name)
        assertEquals(1, imported.skipped)
        val tracks = imported.playlists.single().tracks
        assertEquals(2, tracks.size)
        assertEquals("Zombie", tracks.first().title)
        assertEquals("The Cranberries", tracks.first().artist)
        assertEquals("No Need To Argue", tracks.first().album)
        assertEquals("spotify:2IZZqH4K02UIYg5EohpNHF", tracks.first().id)
        assertEquals(tracks[0], tracks[1])
    }

    @Test fun importsSavedArtistsAlbumsAndStandardAndExtendedHistoryWithoutPrivateFields() {
        val zip = accountZip(
            "YourLibrary.json" to """{"tracks":[],"artists":[{"name":"Artist","uri":"spotify:artist:1234567890123456789012"}],"albums":[{"album":"Record","artist":"Artist","uri":"spotify:album:1234567890123456789012"}]}""",
            "StreamingHistory_music_0.json" to """[{"endTime":"2024-01-02 03:04","trackName":"Basic","artistName":"A","albumName":"B","trackUri":"spotify:track:1234567890123456789012","msPlayed":1200,"ipAddress":"private","username":"private"}]""",
            "Streaming_History_Audio_2020-2024_0.json" to """[{"ts":"2024-02-03T04:05:06Z","master_metadata_track_name":"Extended","master_metadata_album_artist_name":"C","master_metadata_album_album_name":"D","spotify_track_uri":"spotify:track:abcdefghijklmnopqrstuv","ms_played":3400,"ip_addr":"private","user_agent":"private"}]""",
        )
        val result = parseSpotifyAccountZip(zip)
        assertEquals(listOf("Artist"), result.savedArtists.map { it.name })
        assertEquals(listOf("Record"), result.savedAlbums.map { it.name })
        assertEquals(listOf("Artist"), result.savedAlbums.map { it.artist })
        assertEquals(listOf("Basic", "Extended"), result.history.map { it.track.title })
        assertEquals(listOf("2024-01-02 03:04", "2024-02-03T04:05:06Z"), result.history.map { it.timestamp })
        assertEquals(listOf(1200L, 3400L), result.history.map { it.msPlayed })
        assertEquals(0, result.skipped)
    }

    @Test fun existingPlaylistFileConstructionGetsEmptyMetadataDefaults() {
        assertEquals(emptyList<SpotifyHistoryEntry>(), PlaylistFile(emptyList(), 0).history)
        assertEquals(emptyList<SpotifySavedItem>(), PlaylistFile(emptyList(), 0).savedArtists)
        assertEquals(emptyList<SpotifySavedItem>(), PlaylistFile(emptyList(), 0).savedAlbums)
    }

    @Test fun importsLegacyHistoryAndAllowsHistoryChunksAbovePlaylistFileLimit() {
        val padded = " ".repeat(10_500_000) + "[]"
        val result = parseSpotifyAccountZip(accountZip(
            "StreamingHistory0.json" to """[{"endTime":"2023-01-01 00:00","trackName":"Legacy","artistName":"A","msPlayed":12}]""",
            "Streaming_History_Audio_2020-2024_0.json" to padded,
        ))
        assertEquals(listOf("Legacy"), result.history.map { it.track.title })
    }

    @Test fun rejectsDuplicateHistoryFilesAndSkipsInvalidTimestamps() {
        assertFailure("duplicate") { parseSpotifyAccountZip(accountZip("one/StreamingHistory0.json" to "[]", "two/StreamingHistory0.json" to "[]")) }
        val result = parseSpotifyAccountZip(accountZip("StreamingHistory0.json" to
            """[{"endTime":"not a time","trackName":"Song","artistName":"Artist","msPlayed":1},{"endTime":"2024-99-01 01:00","trackName":"Song","artistName":"Artist","msPlayed":1}]"""))
        assertEquals(emptyList<SpotifyHistoryEntry>(), result.history)
        assertEquals(2, result.skipped)
    }

    private fun accountZip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip -> files.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
        output.toByteArray()
    }

    private fun assertFailure(message: String, block: () -> Unit) {
        try { block(); throw AssertionError("Expected failure containing: $message") }
        catch (error: IllegalArgumentException) { assertEquals(true, error.message.orEmpty().contains(message, ignoreCase = true)) }
    }
}
