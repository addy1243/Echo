package dev.echo.data.firebase

import dev.echo.core.Track
import org.junit.Assert.*
import org.junit.Test

class LibrarySyncMergeTest {
    private val now = 1_800_000_000_000L
    private val song = Track("youtube:abcdefghijk", "Song", "Artist", provider = "youtube",
        sourceUri = "https://www.youtube.com/watch?v=abcdefghijk")
    private val playlist = FirebasePlaylist("playlist", "Original", listOf(song.id, song.id))
    private val original = LibrarySyncState(listOf(song), listOf(playlist))

    @Test fun metadataChangesConvergeRegardlessOfWhichDeviceUploadsFirst() {
        val first = original.copy(tracks = listOf(song.copy(title = "First edit", durationMs = 123_000)))
        val second = original.copy(tracks = listOf(song.copy(title = "Second edit", album = "Album")))
        for (baseline in listOf(null, original)) {
            val merged = mergeLibrarySyncStates(baseline, first, second, now)
            assertEquals(merged, mergeLibrarySyncStates(baseline, second, first, now))
            assertEquals(merged, mergeLibrarySyncStates(merged, merged, merged, now))
            assertTrue(merged.tracks.single() in first.tracks + second.tracks)
        }
        assertEquals(first, mergeLibrarySyncStates(original, original, first, now))
        assertEquals(first, mergeLibrarySyncStates(original, first, original, now))
    }

    @Test fun deletingAConflictCopyDoesNotResurrectItFromTheSameStaleConcurrentEdit() {
        val local = original.copy(playlists = listOf(playlist.copy(name = "Local edit")))
        val remote = original.copy(playlists = listOf(playlist.copy(name = "Remote edit")))
        val merged = mergeLibrarySyncStates(original, local, remote, now)
        val conflict = merged.playlists.single { it.id != playlist.id }
        val removed = merged.copy(playlists = merged.playlists.filterNot { it.id == conflict.id },
            deletedPlaylists = setOf(conflict.id))
        val stale = if (local.playlists.single().name == conflict.name.removeSuffix(" (sync conflict)")) local else remote
        val repeated = mergeLibrarySyncStates(original, stale, removed, now)
        assertEquals(removed, repeated)
        assertEquals(repeated, mergeLibrarySyncStates(original, removed, stale, now))
    }

    @Test fun standaloneSavedTracksSyncAndRemainAfterPlaylistFavoriteOrHistoryRemoval() {
        val standalone = song.copy(id = "youtube:abcdefghij2", title = "Saved independently",
            sourceUri = "https://www.youtube.com/watch?v=abcdefghij2")
        val fromDesktop = LibrarySyncState(tracks = listOf(song))
        val fromAndroid = LibrarySyncState(tracks = listOf(standalone))
        val merged = mergeLibrarySyncStates(null, fromDesktop, fromAndroid, now)
        assertEquals(setOf(song.id, standalone.id), merged.tracks.map { it.id }.toSet())
        assertTrue(merged.playlists.isEmpty()); assertTrue(merged.favorites.isEmpty()); assertTrue(merged.history.isEmpty())
        assertEquals(merged, mergeLibrarySyncStates(merged, merged, merged, now))
        val deleted = original.copy(playlists = emptyList(), deletedPlaylists = setOf(playlist.id))
        assertEquals(listOf(song), mergeLibrarySyncStates(original, original, deleted, now).tracks)
    }
}
