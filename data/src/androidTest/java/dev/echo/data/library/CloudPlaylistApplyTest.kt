package dev.echo.data.library

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.core.Playlist
import dev.echo.core.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CloudPlaylistApplyTest {
    @Test fun appliesDuplicatesAndDeletionsWhileKeepingLocalUrisLikesAndMatches() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val repository = LibraryRepository(context, database)
            val local = Track("local:123", "Device recording", "Artist", sourceUri = "content://media/123")
            val imported = Track("spotify:song", "Imported recording", "Artist", provider = "spotify")
            val remote = Track("cloud-local:other:local:123", "Other phone's recording", "Artist", sourceUri = "content://other-phone/123")
            repository.upsertTracks(listOf(local))
            repository.importPlaylist("keep", "Keep", listOf(imported), "spotify-list")
            repository.importPlaylist("delete", "Delete", listOf(local))
            repository.importPlaylist("unrelated", "Unrelated", listOf(local))
            repository.matchLocalTrack(imported.id, local.id)
            repository.setFavorite(local.id, true)
            repository.setFavorite(imported.id, true)
            val expected = repository.exportBackup()
            val merged = LibraryBackup(listOf(local.copy(sourceUri = null), imported, remote),
                listOf(BackupPlaylist(Playlist("keep", "Synced", "spotify-list"), listOf(imported.id, remote.id, imported.id))),
                emptyList(), emptyList()).json()
            repository.applyCloudPlaylists(expected, merged, setOf("delete"))
            val songs = repository.playlistTracks("keep")
            assertEquals(listOf(imported.id, remote.id, imported.id), songs.map { it.id })
            assertEquals(local.sourceUri, songs.first().sourceUri)
            assertEquals(local.sourceUri, songs.last().sourceUri)
            assertNull(songs[1].sourceUri)
            assertEquals(local.sourceUri, database.libraryDao().track(local.id)!!.sourceUri)
            assertEquals(setOf(local.id, imported.id), repository.favorites.first())
            assertFalse(database.libraryDao().hasPlaylist("delete"))
            assertTrue(database.libraryDao().hasPlaylist("unrelated"))
            assertEquals(1, database.libraryDao().localMatches().size)
            repository.matchLocalTrack(remote.id, local.id)
            assertEquals(local.sourceUri, repository.playlistTracks("keep")[1].sourceUri)
        } finally { database.close() }
    }

    @Test fun rejectsInterveningEditsWithoutInsertingRemoteTracksOrDeletingPlaylists() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val repository = LibraryRepository(context, database)
            val local = Track("one", "One", "Artist")
            repository.importPlaylist("playlist", "Before", listOf(local))
            val expected = repository.exportBackup()
            repository.renamePlaylist("playlist", "User's new edit")
            val latest = repository.exportBackup()
            val remote = Track("remote", "Remote", "Artist")
            val merged = LibraryBackup(listOf(remote), listOf(BackupPlaylist(Playlist("new", "New"), listOf(remote.id))), emptyList(), emptyList()).json()
            assertTrue(runCatching { repository.applyCloudPlaylists(expected, merged, setOf("playlist")) }.isFailure)
            assertEquals(latest, repository.exportBackup())
            assertFalse(database.libraryDao().hasTrack(remote.id))
            assertFalse(database.libraryDao().hasPlaylist("new"))
        } finally { database.close() }
    }

    @Test fun rollsBackEntireSyncWhenEntryWriteFails() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val repository = LibraryRepository(context, database)
            val local = Track("local", "Local", "Artist", sourceUri = "content://media/local")
            repository.importPlaylist("playlist", "Before", listOf(local))
            val expected = repository.exportBackup()
            val rejected = Track("rejected", "Remote", "Artist")
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_cloud BEFORE INSERT ON playlist_tracks WHEN NEW.trackId = 'rejected' BEGIN SELECT RAISE(ABORT, 'test cloud failure'); END")
            val merged = LibraryBackup(listOf(rejected), listOf(BackupPlaylist(Playlist("playlist", "After"), listOf(rejected.id))), emptyList(), emptyList()).json()
            assertTrue(runCatching { repository.applyCloudPlaylists(expected, merged, emptySet()) }.isFailure)
            assertEquals(expected, repository.exportBackup())
            assertFalse(database.libraryDao().hasTrack(rejected.id))
        } finally { database.close() }
    }
}
