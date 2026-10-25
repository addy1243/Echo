package dev.echo.data.library

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.core.Track
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryTest {
    @Test fun manualMatchSurvivesRefreshCanBeCorrectedAndCleared() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val repository = LibraryRepository(context, database)
            val imported = Track("spotify:a", "Title", "Artist", provider = "spotify")
            val local = Track("local:1", "Local", "Artist", sourceUri = "content://media/1")
            val other = local.copy(id = "local:2", sourceUri = "content://media/2")
            repository.upsertTracks(listOf(local, other))
            repository.importPlaylist("p", "Imported", listOf(imported, imported))
            repository.matchLocalTrack(imported.id, local.id)
            repository.importPlaylist("p", "Refreshed", listOf(imported.copy(title = "Updated"), imported))
            assertEquals(listOf(local.sourceUri, local.sourceUri), repository.playlistTracks("p").map { it.sourceUri })
            repository.matchLocalTrack(imported.id, other.id)
            assertEquals(other.sourceUri, repository.playlistTracks("p").first().sourceUri)
            repository.clearLocalMatch(imported.id)
            assertEquals(listOf("spotify", "spotify"), repository.playlistTracks("p").map { it.provider })
            assertTrue(repository.playlistTracks("p").all { it.sourceUri == null })
        } finally { database.close() }
    }

    @Test fun backupRestoresDuplicatesFavoritesMatchesAndRollsBackInvalidMatch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val destination = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val repository = LibraryRepository(context, source)
            val restored = LibraryRepository(context, destination)
            val imported = Track("spotify:a", "Title", "Artist", provider = "spotify", attribution = "Credit")
            val local = Track("local:1", "Local", "Artist", sourceUri = "content://media/1")
            repository.upsertTracks(listOf(local))
            repository.importPlaylist("p", "Imported", listOf(imported, imported), "spotify-p")
            repository.matchLocalTrack(imported.id, local.id)
            repository.setFavorite(imported.id, true)
            val backup = repository.exportBackup()
            restored.importBackup(backup)
            assertEquals(repository.playlistTracks("p"), restored.playlistTracks("p"))
            assertEquals(setOf(imported.id), restored.favorites.first())
            val malformed = org.json.JSONObject(backup).apply {
                getJSONArray("tracks").getJSONObject(0).put("provider", "spotify")
            }.toString()
            assertTrue(runCatching { restored.importBackup(malformed) }.isFailure)
            assertEquals(repository.playlistTracks("p"), restored.playlistTracks("p"))
            restored.clearLocalMatch(imported.id)
            assertEquals(listOf(imported, imported), restored.playlistTracks("p"))
        } finally { source.close(); destination.close() }
    }

    @Test fun duplicateImportReorderAndRollback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val repository = LibraryRepository(context, database)
            val a = Track("a", "Alpha", "Artist", attribution = "Credit", licenseUrl = "https://example.org/license")
            val b = Track("b", "Beta", "Artist")
            repository.importPlaylist("p", "Imported", listOf(a, b, a), "spotify-p")
            assertEquals(listOf(a, b, a), repository.playlistTracks("p"))
            repository.movePlaylistTrack("p", 0, 2)
            assertEquals(listOf(b, a, a), repository.playlistTracks("p"))
            repository.removeFromPlaylist("p", 1)
            assertEquals(listOf(b, a), repository.playlistTracks("p"))
            val failed = runCatching { repository.importPlaylist("p", "Replacement", listOf(a.copy(id = ""))) }
            assertTrue(failed.isFailure)
            assertEquals(listOf(b, a), repository.playlistTracks("p"))
            repository.upsertTracks(listOf(a.copy(title = "Updated")))
            assertEquals(listOf("b", "a"), repository.playlistTracks("p").map { it.id })
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON playlist_tracks WHEN NEW.trackId = 'rejected' BEGIN SELECT RAISE(ABORT, 'test import failure'); END")
            val partialFailure = runCatching {
                repository.importPlaylist("p", "Must roll back", listOf(a, Track("rejected", "Rejected", "Artist")))
            }
            assertTrue(partialFailure.isFailure)
            assertEquals(listOf("b", "a"), repository.playlistTracks("p").map { it.id })
            assertEquals("Updated", repository.playlistTracks("p").last().title)
            assertTrue(!database.libraryDao().hasTrack("rejected"))
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_import")
            repository.deletePlaylist("p")
            assertTrue(repository.playlistTracks("p").isEmpty())
        } finally {
            database.close()
        }
    }
}
