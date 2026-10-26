package dev.echo.app.account

import org.junit.Assert.*
import org.junit.Test

class PlaylistSyncMergeTest {
    private val original = SyncPlaylist("playlist", "My songs", listOf("one", "two", "one"))

    @Test fun mergesIndependentChangesAndPreservesOrderAndDuplicates() {
        val other = SyncPlaylist("second", "Second", listOf("three"))
        val renamed = original.copy(name = "Renamed")
        assertEquals(listOf(renamed, other), mergePlaylists(listOf(original), listOf(renamed), listOf(original, other)))
        assertEquals(original.trackIds, mergePlaylists(emptyList(), emptyList(), listOf(original)).single().trackIds)
    }

    @Test fun propagatesUnchangedSideDeletionsInEitherDirection() {
        assertTrue(mergePlaylists(listOf(original), emptyList(), listOf(original)).isEmpty())
        assertTrue(mergePlaylists(listOf(original), listOf(original), emptyList()).isEmpty())
        assertTrue(mergePlaylists(listOf(original), emptyList(), emptyList()).isEmpty())
    }

    @Test fun concurrentEditsPreserveBothAndDoNotRepeatConflictCopies() {
        val ours = original.copy(name = "Local")
        val theirs = original.copy(trackIds = listOf("two", "one", "one"))
        val merged = mergePlaylists(listOf(original), listOf(ours), listOf(theirs))
        assertEquals(2, merged.size)
        assertEquals(ours, merged.first())
        assertEquals(theirs.trackIds, merged.last().trackIds)
        assertEquals("My songs (sync conflict)", merged.last().name)
        assertNotEquals(original.id, merged.last().id)
        assertEquals(merged, mergePlaylists(listOf(original), merged, listOf(theirs)))
        assertEquals(merged, mergePlaylists(merged, merged, merged))
    }

    @Test fun editsSurviveConcurrentDeletionOnEitherSide() {
        val edited = original.copy(trackIds = listOf("new", "one", "one"))
        assertEquals(listOf(edited), mergePlaylists(listOf(original), emptyList(), listOf(edited)))
        assertEquals(listOf(edited), mergePlaylists(listOf(original), listOf(edited), emptyList()))
    }

    @Test fun concurrentNewPlaylistsWithTheSameIdArePreserved() {
        val other = original.copy(name = "Other")
        val merged = mergePlaylists(emptyList(), listOf(original), listOf(other))
        assertEquals(original, merged.first())
        assertEquals(other.trackIds, merged.last().trackIds)
        assertEquals(merged, mergePlaylists(emptyList(), merged, listOf(other)))
    }

    @Test fun spotifyMetadataSurvivesBothCopiesAndAffectsConflictIdentity() {
        val ours = original.copy(spotifyId = "local")
        val theirs = original.copy(spotifyId = "remote")
        val merged = mergePlaylists(emptyList(), listOf(ours), listOf(theirs))
        assertEquals("local", merged.first().spotifyId)
        assertEquals("remote", merged.last().spotifyId)
        val changed = mergePlaylists(emptyList(), listOf(ours), listOf(theirs.copy(spotifyId = "different")))
        assertNotEquals(merged.last().id, changed.last().id)
    }

    @Test fun duplicateSnapshotIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { mergePlaylists(emptyList(), listOf(original, original), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { mergePlaylists(emptyList(), emptyList(), listOf(original.copy(id = ""))) }
    }
}
