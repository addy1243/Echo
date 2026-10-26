package dev.echo.data.library

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.core.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ListeningHistoryMigrationTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LibraryDatabase::class.java)

    @Test fun migrationPreservesExistingLibraryPlaylistsLikesAndHistory() {
        val name = "history-migration-test"
        migration.createDatabase(name, 1).apply {
            execSQL("INSERT INTO tracks (id,title,artist,album,durationMs,provider) VALUES ('a','Song','Artist','',123000,'local')")
            execSQL("INSERT INTO playlists (id,name) VALUES ('p','Favorites')")
            execSQL("INSERT INTO playlist_tracks (playlistId,position,trackId) VALUES ('p',0,'a'),('p',1,'a')")
            execSQL("INSERT INTO favorites (trackId) VALUES ('a')")
            execSQL("INSERT INTO playback_history (trackId,playedAt) VALUES ('a',123)")
            close()
        }
        migration.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2).apply {
            query("SELECT event,listenedMs,durationMs FROM playback_history").use {
                assertTrue(it.moveToFirst()); assertEquals("PLAYED", it.getString(0)); assertEquals(0L, it.getLong(1))
            }
            query("SELECT COUNT(*) FROM playlist_tracks").use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
            query("SELECT COUNT(*) FROM favorites").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            close()
        }
    }

    @Test fun eventsPersistAndClearAlsoResetsPreferences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val repository = LibraryRepository(context, database)
            repository.upsertTracks(listOf(Track("a", "Song", "Artist")))
            repository.recordListeningEvent("a", ListeningEventType.PLAYED)
            repository.recordListeningEvent("a", ListeningEventType.SKIPPED, 2000, 120_000)
            assertEquals(listOf(ListeningEventType.SKIPPED, ListeningEventType.PLAYED), repository.listeningHistory.first().map { it.event })
            assertEquals(1, repository.listeningPreferences.first().getValue("a").earlySkips)
            assertTrue(runCatching { repository.recordListeningEvent("a", ListeningEventType.ERROR, -1) }.isFailure)
            repository.clearListeningHistory()
            assertTrue(repository.listeningHistory.first().isEmpty())
            assertTrue(repository.listeningPreferences.first().isEmpty())
            assertTrue(repository.recent.first().isEmpty())
            database.libraryDao().record(HistoryEntity(trackId = "a", playedAt = 123, event = "COMPLETED", listenedMs = 100_000, durationMs = 120_000))
            assertTrue("Old retained rows must not influence preferences before the next write trims them", repository.listeningHistory.first().isEmpty())
            assertTrue(repository.listeningPreferences.first().isEmpty())
        } finally { database.close() }
    }
}
