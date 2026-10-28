package dev.echo.data.library

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.core.Track
import dev.echo.data.firebase.FirebaseHistoryEpoch
import dev.echo.data.firebase.FirebaseHistoryEvent
import dev.echo.data.firebase.FirebasePlaylist
import dev.echo.data.firebase.cloudTrackId
import dev.echo.data.firebase.encodeLibrarySyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FirebaseLibraryApplyTest {
    @Test fun remoteClearAlsoRemovesLegacyRowsBeyondTheSyncHistoryLimit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val library = LibraryRepository(context, database)
            val song = Track("youtube:abcdefghijk", "Song", "Artist", provider = "youtube")
            library.upsertTracks(listOf(song))
            val now = System.currentTimeMillis()
            database.withTransaction {
                repeat(5001) { index -> database.libraryDao().record(HistoryEntity(trackId = song.id, playedAt = now - index)) }
            }
            val device = library.firebaseDeviceId()
            val expected = library.exportFirebaseState(device)
            assertEquals(5000, expected.history.size)
            library.applyFirebaseState(expected, expected.copy(history = emptyList(), historyEpoch = FirebaseHistoryEpoch(1, device)), device)
            assertTrue(database.libraryDao().allHistory().isEmpty())
            assertTrue(library.exportFirebaseState(device).history.isEmpty())
            assertTrue(database.libraryDao().firebaseHistoryMappings().isEmpty())
        } finally { database.close() }
    }

    @Test fun canonicalMetadataReplacesOldRemoteMetadataAndKeepsOwnFileAssociations() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val library = LibraryRepository(context, database)
            val recording = Track("youtube:abcdefghijk", "Old title", "Artist", durationMs = 50_000,
                provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=abcdefghijk")
            val file = Track("local:one", "Local title", "Artist", sourceUri = "content://media/one")
            val imported = Track("spotify:one", "Imported title", "Artist", provider = "spotify")
            library.importPlaylist("playlist", "Playlist", listOf(recording, file, imported, recording))
            library.matchLocalTrack(imported.id, file.id)
            library.setFavorite(recording.id, true)
            val device = library.firebaseDeviceId()
            val before = library.exportFirebaseState(device)
            val canonical = before.copy(tracks = before.tracks.map { track -> when (track.id) {
                recording.id -> track.copy(title = "Canonical recording", durationMs = 60_000)
                imported.id -> track.copy(title = "Canonical imported")
                else -> track.copy(title = "Canonical file metadata")
            } })
            library.applyFirebaseState(before, canonical, device)
            assertEquals(encodeLibrarySyncState(canonical), encodeLibrarySyncState(library.exportFirebaseState(device)))
            val tracks = library.playlistTracks("playlist")
            assertEquals(listOf(recording.id, file.id, imported.id, recording.id), tracks.map { it.id })
            assertEquals("Canonical recording", tracks.first().title)
            assertEquals(60_000L, tracks.first().durationMs)
            assertEquals(file.sourceUri, tracks[1].sourceUri)
            assertEquals(file.sourceUri, tracks[2].sourceUri)
            assertEquals("Canonical imported", tracks[2].title)
            assertEquals(setOf(recording.id), library.favorites.first())
            assertEquals(1, database.libraryDao().localMatches().size)
        } finally { database.close() }
    }

    @Test fun fullStateApplyPreservesLocalPlaybackDuplicatesMatchesAndStableHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val library = LibraryRepository(context, database)
            val local = Track("local:1", "Local", "Artist", sourceUri = "content://media/external/audio/1")
            val imported = Track("spotify:recording", "Imported", "Artist", provider = "spotify")
            library.importPlaylist("playlist", "Before", listOf(local, imported, local))
            library.matchLocalTrack(imported.id, local.id)
            library.setFavorite(local.id, true)
            library.recordListeningEvent(local.id, ListeningEventType.COMPLETED, 45_000, 60_000)
            val device = library.firebaseDeviceId()
            val expected = library.exportFirebaseState(device)
            val localId = cloudTrackId(local, device)
            assertFalse(encodeLibrarySyncState(expected).contains("content://"))
            assertEquals(FirebaseHistoryEpoch(), expected.historyEpoch)
            assertEquals(expected.history.single().id, library.exportFirebaseState(device).history.single().id)
            val remote = Track("youtube:abcdefghijk", "Remote", "Artist", provider = "youtube",
                sourceUri = "https://www.youtube.com/watch?v=abcdefghijk")
            val remoteEvent = FirebaseHistoryEvent("other-installation:event", remote.id, "COMPLETED", System.currentTimeMillis(), 35_000, 100_000)
            val merged = expected.copy(tracks = expected.tracks + remote,
                playlists = listOf(FirebasePlaylist("playlist", "After", listOf(localId, remote.id, imported.id, localId))),
                favorites = setOf(remote.id), history = expected.history + remoteEvent,
                unlikedTracks = setOf(localId))
            library.applyFirebaseState(expected, merged, device)
            val playlist = library.playlistTracks("playlist")
            assertEquals(listOf(local.id, remote.id, imported.id, local.id), playlist.map { it.id })
            assertEquals(local.sourceUri, playlist.first().sourceUri)
            assertEquals(local.sourceUri, playlist[2].sourceUri)
            assertEquals(setOf(remote.id), library.favorites.first())
            val applied = library.exportFirebaseState(device)
            assertEquals(2, applied.history.size)
            assertTrue(applied.history.any { it.id == remoteEvent.id })
            library.applyFirebaseState(applied, applied, device)
            assertEquals(2, database.libraryDao().allHistory().size)
            assertEquals(1, database.libraryDao().localMatches().size)
            val collision = applied.copy(tracks = applied.tracks + local.copy(provider = "spotify", sourceUri = null))
            assertTrue(runCatching { library.applyFirebaseState(applied, collision, device) }.isFailure)
            assertEquals(local.sourceUri, database.libraryDao().track(local.id)!!.sourceUri)
        } finally { database.close() }
    }

    @Test fun rejectsStaleStateAndRollsBackAllTablesOnApplyFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val library = LibraryRepository(context, database)
            val track = Track("youtube:abcdefghijk", "Song", "Artist", provider = "youtube")
            library.importPlaylist("playlist", "Before", listOf(track, track))
            val device = library.firebaseDeviceId()
            val expected = library.exportFirebaseState(device)
            library.renamePlaylist("playlist", "Local edit")
            val latest = library.exportFirebaseState(device)
            assertTrue(runCatching { library.applyFirebaseState(expected, expected, device) }.isFailure)
            assertEquals(encodeLibrarySyncState(latest), encodeLibrarySyncState(library.exportFirebaseState(device)))
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_firebase BEFORE INSERT ON playlist_tracks BEGIN SELECT RAISE(ABORT, 'test apply failure'); END")
            assertTrue(runCatching { library.applyFirebaseState(latest, latest.copy(playlists = emptyList()), device) }.isSuccess)
            val empty = library.exportFirebaseState(device)
            assertTrue(runCatching { library.applyFirebaseState(empty, latest, device) }.isFailure)
            assertEquals(encodeLibrarySyncState(empty), encodeLibrarySyncState(library.exportFirebaseState(device)))
        } finally { database.close() }
    }

    @Test fun clearAdvancesEpochAndRemoteHistoryDoesNotDuplicateOrReviveOldEvents() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val library = LibraryRepository(context, database)
            val track = Track("youtube:abcdefghijk", "Song", "Artist", provider = "youtube")
            library.importPlaylist("playlist", "Playlist", listOf(track))
            library.recordPlayed(track.id)
            val device = library.firebaseDeviceId()
            val before = library.exportFirebaseState(device)
            library.clearListeningHistory()
            val cleared = library.exportFirebaseState(device)
            assertEquals(FirebaseHistoryEpoch(1, device), cleared.historyEpoch)
            assertTrue(cleared.history.isEmpty())
            assertTrue(runCatching { library.applyFirebaseState(cleared, before, device) }.isFailure)
            assertTrue(library.exportFirebaseState(device).history.isEmpty())
            assertEquals(1, library.playlistTracks("playlist").size)
            library.applyFirebaseState(cleared, before, device, bootstrapAccount = true)
            assertEquals(before.historyEpoch, library.exportFirebaseState(device).historyEpoch)
            assertEquals(before.history, library.exportFirebaseState(device).history)
        } finally { database.close() }
    }

    @Test fun offlineDeletionAndUnlikeAreJournaledBeforeFirstSync() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val library = LibraryRepository(context, database)
            val track = Track("local:one", "Song", "Artist", sourceUri = "content://media/one")
            library.importPlaylist("playlist", "Playlist", listOf(track, track))
            library.setFavorite(track.id, true)
            library.setFavorite(track.id, false)
            library.deletePlaylist("playlist")
            val device = library.firebaseDeviceId()
            val state = library.exportFirebaseState(device)
            assertTrue("playlist" in state.deletedPlaylists)
            assertTrue(cloudTrackId(track, device) in state.unlikedTracks)
            library.setFavorite(track.id, true)
            library.importPlaylist("playlist", "Explicit restore", listOf(track, track))
            val restored = library.exportFirebaseState(device)
            assertFalse("playlist" in restored.deletedPlaylists)
            assertTrue(restored.unlikedTracks.isEmpty())
            assertEquals(listOf(cloudTrackId(track, device), cloudTrackId(track, device)), restored.playlists.single().trackIds)
        } finally { database.close() }
    }
}
