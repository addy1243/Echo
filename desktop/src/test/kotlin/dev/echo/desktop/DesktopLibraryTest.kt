package dev.echo.desktop

import dev.echo.core.Track
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class DesktopLibraryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val song = Track("youtube:abcdefghijk", "Song", "Artist", provider = "youtube", sourceUri = "https://cdn.example/audio?signature=secret")
    private fun path() = temporary.root.toPath().resolve("library.json")

    @Test fun duplicatesFavoritesAndHistorySurviveRestartWithoutSignedSources() {
        val library = DesktopLibrary(path())
        val playlist = library.createPlaylist("My songs")
        library.addToPlaylist(playlist.id, song)
        library.addToPlaylist(playlist.id, song)
        library.toggleFavorite(song)
        library.recordHistory(song, "COMPLETED", 32_000, 60_000)

        val saved = DesktopLibrary(path()).snapshot()
        assertEquals(listOf(song.id, song.id), saved.playlists.single().trackIds)
        assertEquals(setOf(song.id), saved.favorites)
        assertEquals(32_000L, saved.history.single().listenedMs)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", saved.tracks.single().sourceUri)
        assertFalse(Files.readString(path()).contains("signature=secret"))
    }

    @Test fun invalidImportPreservesDiskAndMemory() {
        val library = DesktopLibrary(path())
        library.toggleFavorite(song)
        val before = Files.readString(path())
        val bad = backup().put("favorites", JSONArray(listOf("missing")))
        try { library.importBackup(bad.toString()); fail("Expected rejected import") } catch (_: IllegalArgumentException) { }
        assertEquals(before, Files.readString(path()))
        assertEquals(setOf(song.id), library.snapshot().favorites)
    }

    @Test fun backupImportIsAdditiveAndPhonePathsAreMetadataOnly() {
        val library = DesktopLibrary(path())
        library.createPlaylist("Existing")
        val imported = backup()
        imported.getJSONArray("tracks").getJSONObject(0).put("provider", "local").put("id", "local:42").put("sourceUri", "content://media/audio/42")
        imported.getJSONArray("playlists").getJSONObject(0).put("trackIds", JSONArray(listOf("local:42", "local:42")))
        imported.put("favorites", JSONArray(listOf("local:42")))
        assertEquals(1, library.importBackup(imported.toString()))
        val saved = DesktopLibrary(path()).snapshot()
        assertEquals(2, saved.playlists.size)
        assertEquals(listOf("local:42", "local:42"), saved.playlists.last().trackIds)
        assertNull(saved.tracks.single().sourceUri)
    }

    @Test fun localFilesRemainPlayableAndClearHistoryPreservesFavorites() {
        val audio = temporary.newFile("music.wav")
        val track = DesktopLibrary.localTrack(audio)
        val library = DesktopLibrary(path())
        library.toggleFavorite(track)
        library.recordHistory(track, "PLAYED")
        library.clearHistory()
        val saved = DesktopLibrary(path()).snapshot()
        assertTrue(saved.history.isEmpty())
        assertEquals(setOf(track.id), saved.favorites)
        assertEquals(audio.canonicalFile.toURI().toASCIIString(), saved.tracks.single().sourceUri)
    }

    @Test fun failedWriteLeavesMemoryUntouched() {
        val directory = temporary.newFolder("cannot-replace-directory").toPath()
        val target = directory.resolve("library.json")
        val library = DesktopLibrary(target)
        Files.createDirectory(target)
        Files.writeString(target.resolve("keep.txt"), "keep")
        try { library.toggleFavorite(song); fail("Expected write failure") } catch (_: java.io.IOException) { }
        assertTrue(library.snapshot().favorites.isEmpty())
        assertEquals("keep", Files.readString(target.resolve("keep.txt")))
    }

    @Test fun malformedExistingLibraryIsPreserved() {
        Files.writeString(path(), "broken-json")
        try { DesktopLibrary(path()); fail("Expected load failure") } catch (_: org.json.JSONException) { }
        assertEquals("broken-json", Files.readString(path()))
    }

    @Test fun playlistRenameAndDeletionPreserveSongsFavoritesAndHistory() {
        val library = DesktopLibrary(path())
        val playlist = library.createPlaylist("Old")
        library.addToPlaylist(playlist.id, song)
        library.toggleFavorite(song)
        library.recordHistory(song, "PLAYED")
        library.renamePlaylist(playlist.id, "  New name  ")
        assertEquals("New name", library.snapshot().playlists.single().name)
        try { library.renamePlaylist(playlist.id, "  "); fail("Expected invalid name rejection") } catch (_: IllegalArgumentException) { }
        assertEquals("New name", library.snapshot().playlists.single().name)
        library.deletePlaylist(playlist.id)
        val saved = DesktopLibrary(path()).snapshot()
        assertTrue(saved.playlists.isEmpty())
        assertEquals(song.id, saved.tracks.single().id)
        assertEquals(setOf(song.id), saved.favorites)
        assertEquals(song.id, saved.history.single().trackId)
    }

    @Test fun occurrenceEditsPreserveDuplicatesAndRejectStaleSnapshots() {
        val library = DesktopLibrary(path())
        val playlist = library.createPlaylist("Ordered")
        val other = song.copy(id = "youtube:lmnopqrstuv", title = "Other")
        listOf(song, other, song).forEach { library.addToPlaylist(playlist.id, it) }
        val original = listOf(song.id, other.id, song.id)
        library.movePlaylistTrack(playlist.id, 2, 1, original)
        val moved = listOf(song.id, song.id, other.id)
        assertEquals(moved, library.snapshot().playlists.single().trackIds)
        val disk = Files.readString(path())
        try { library.removeFromPlaylist(playlist.id, 1, original); fail("Expected stale removal rejection") } catch (_: IllegalArgumentException) { }
        try { library.movePlaylistTrack(playlist.id, 0, 2, original); fail("Expected stale move rejection") } catch (_: IllegalArgumentException) { }
        assertEquals(disk, Files.readString(path()))
        library.removeFromPlaylist(playlist.id, 1, moved)
        assertEquals(listOf(song.id, other.id), DesktopLibrary(path()).snapshot().playlists.single().trackIds)
        assertEquals(2, library.snapshot().tracks.size)
    }

    @Test fun portableBackupExcludesHistoryAndLocalPathsAndPreservesOrder() {
        val library = DesktopLibrary(path())
        val local = DesktopLibrary.localTrack(temporary.newFile("private-music.wav"))
        val playlist = library.createPlaylist("Portable")
        listOf(local, song, local).forEach { library.addToPlaylist(playlist.id, it) }
        library.toggleFavorite(local)
        library.recordHistory(local, "COMPLETED", 90_000, 180_000)
        val text = library.exportBackup()
        val backup = JSONObject(text)
        assertEquals("echo-library", backup.getString("format"))
        assertEquals(1, backup.getInt("version"))
        assertFalse(backup.has("history"))
        assertEquals(0, backup.getJSONArray("localMatches").length())
        assertFalse(text.contains(temporary.root.toURI().toString()))
        val localJson = backup.getJSONArray("tracks").let { tracks ->
            (0 until tracks.length()).map { tracks.getJSONObject(it) }.single { it.getString("provider") == "local" }
        }
        assertTrue(localJson.getString("id").startsWith("desktop-local:"))
        assertFalse(localJson.getString("id").contains("private-music"))
        assertTrue(localJson.isNull("sourceUri"))
        assertTrue(localJson.isNull("artworkUri"))
        val ids = backup.getJSONArray("playlists").getJSONObject(0).getJSONArray("trackIds")
        assertEquals(localJson.getString("id"), ids.getString(0))
        assertEquals(ids.getString(0), ids.getString(2))
        assertEquals(song.id, ids.getString(1))
        val imported = DesktopLibrary(temporary.root.toPath().resolve("imported.json"))
        imported.importBackup(text)
        assertEquals(3, imported.snapshot().playlists.single().trackIds.size)
        assertTrue(imported.snapshot().tracks.single { it.provider == "local" }.sourceUri == null)
        assertEquals(1, library.snapshot().history.size)
        assertEquals(text, library.exportBackup())
    }

    private fun backup() = JSONObject().put("format", "echo-library").put("version", 1)
        .put("tracks", JSONArray(listOf(JSONObject().put("id", song.id).put("title", song.title).put("artist", song.artist)
            .put("provider", song.provider).put("sourceUri", song.sourceUri))))
        .put("playlists", JSONArray(listOf(JSONObject().put("id", "original").put("name", "Imported")
            .put("trackIds", JSONArray(listOf(song.id, song.id))))))
        .put("favorites", JSONArray(listOf(song.id)))
}
