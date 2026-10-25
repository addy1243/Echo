package dev.echo.data.spotify

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.core.Track
import dev.echo.data.library.LibraryDatabase
import dev.echo.data.library.LibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaylistFileTest {
    private val json = """{"playlists":[{"name":"My songs","items":[
        {"track":{"trackName":"Song","artistName":"Artist","albumName":"Album","trackUri":"spotify:track:1234567890123456789012"}},
        {"episode":{"episodeName":"Podcast"}},
        {"track":{"trackName":"Song","artistName":"Artist","albumName":"Album","trackUri":"spotify:track:1234567890123456789012"}}
    ]}]}"""

    @Test fun jsonPreservesDuplicatesAndReportsSkippedEntries() {
        val parsed = parsePlaylistFile(json, "Playlist1.json")
        assertEquals(1, parsed.skipped)
        assertEquals("My songs", parsed.playlists.single().name)
        val songs = parsed.playlists.single().tracks
        assertEquals(2, songs.size)
        assertEquals(songs[0], songs[1])
        assertNull(songs[0].sourceUri)
    }

    @Test fun csvMetadataAndValidation() {
        val csv = "\uFEFFTrack URI,Track Name,Artist Name(s),Album Name,Track Duration (ms)\r\nspotify:track:1234567890123456789012,\"A, song\",Artist,Album,123000"
        val parsed = parsePlaylistFile(csv, "Favorites.csv").playlists.single()
        assertEquals("Favorites", parsed.name)
        assertEquals("A, song", parsed.tracks.single().title)
        assertEquals(123000L, parsed.tracks.single().durationMs)
        listOf(csv.replace("123000", "-1"), csv.replace("123000", "n/a"), csv.replace(",Artist,", ",,"), "bad,file").forEach {
            assertTrue(runCatching { parsePlaylistFile(it, "x.csv") }.isFailure)
        }
    }

    @Test fun importsAreAtomicNonDestructiveAndRecordingSelectionChecksPosition() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LibraryDatabase::class.java).build()
        try {
            val library = LibraryRepository(InstrumentationRegistry.getInstrumentation().targetContext, database)
            library.importPlaylistFile(json, "Playlist1.json")
            val first = library.playlists.first().single()
            val imported = library.playlistTracks(first.id)
            library.renamePlaylist(first.id, "My edit")
            library.importPlaylistFile(json, "Playlist1.json")
            assertEquals(2, library.playlists.first().size)
            assertTrue(library.playlists.first().any { it.name == "My edit" })
            val recording = Track("youtube:abcdefghijk", "Song", "Artist", provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=abcdefghijk")
            library.replacePlaylistTrack(first.id, 0, imported.first().id, recording)
            assertEquals(listOf(recording.id, imported.first().id), library.playlistTracks(first.id).map { it.id })
            assertTrue(runCatching { library.replacePlaylistTrack(first.id, 0, imported.first().id, recording) }.isFailure)
            val before = library.playlists.first()
            val malformed = """{"playlists":[{"name":"Valid","items":[]},{"name":"Bad","items":[{"track":{}}]}]}"""
            assertTrue(runCatching { library.importPlaylistFile(malformed, "broken.json") }.isFailure)
            assertEquals(before, library.playlists.first())
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_playlist BEFORE INSERT ON playlists WHEN NEW.name = 'Rejected' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            val rejected = """{"playlists":[{"name":"Accepted","items":[]},{"name":"Rejected","items":[]}]}"""
            assertTrue(runCatching { library.importPlaylistFile(rejected, "rejected.json") }.isFailure)
            assertEquals(before, library.playlists.first())
        } finally { database.close() }
    }
}
