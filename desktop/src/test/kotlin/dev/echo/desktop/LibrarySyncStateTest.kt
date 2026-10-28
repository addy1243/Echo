package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.data.firebase.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibrarySyncStateTest {
    private val device = "12345678-1234-1234-1234-123456789abc"
    private val secondDevice = "12345678-1234-1234-1234-123456789abd"
    private val now = 1_800_000_000_000L
    private val track = Track("youtube:abcdefghijk", "Song", "Artist", provider = "youtube", durationMs = 180_000,
        sourceUri = "https://www.youtube.com/watch?v=abcdefghijk")
    private val playlist = FirebasePlaylist("playlist", "Original", listOf(track.id, track.id))
    private val state = LibrarySyncState(tracks = listOf(track), playlists = listOf(playlist))
    private val event = FirebaseHistoryEvent("$device:42", track.id, "COMPLETED", now - 1000, 60_000, 180_000)

    @Test fun cloudRoundTripPreservesDuplicateOccurrencesAndFavorites() {
        val original = state.copy(favorites = setOf(track.id), history = listOf(event))
        val encoded = encodeLibrarySyncState(original)
        assertEquals(original, decodeLibrarySyncState(encoded))
        assertEquals(2, decodeLibrarySyncState(encoded).playlists.single().trackIds.size)
        assertEquals(encoded, encodeLibrarySyncState(decodeLibrarySyncState(encoded)))
    }

    @Test fun concurrentPlaylistEditsKeepBothVersionsAndConverge() {
        val local = state.copy(playlists = listOf(playlist.copy(name = "Local edit")))
        val remote = state.copy(playlists = listOf(playlist.copy(name = "Remote edit")))
        val merged = mergeLibrarySyncStates(state, local, remote, now)
        val reverse = mergeLibrarySyncStates(state, remote, local, now)
        assertEquals(merged, reverse)
        assertEquals(2, merged.playlists.size)
        assertEquals(setOf("Local edit", "Remote edit"), merged.playlists.map { it.name.removeSuffix(" (sync conflict)") }.toSet())
        assertTrue(merged.playlists.all { it.trackIds == playlist.trackIds })
        assertEquals(merged, mergeLibrarySyncStates(merged, merged, merged, now))
    }

    @Test fun deletedPlaylistCannotReturnFromAnUnchangedStaleDevice() {
        val deleted = state.copy(playlists = emptyList(), deletedPlaylists = setOf(playlist.id))
        val merged = mergeLibrarySyncStates(state, state, deleted, now)
        assertTrue(merged.playlists.isEmpty())
        assertTrue(playlist.id in merged.deletedPlaylists)
        val firstSyncOldDevice = mergeLibrarySyncStates(null, state, merged, now)
        assertTrue(firstSyncOldDevice.playlists.isEmpty())
    }

    @Test fun deletionAndConcurrentEditKeepEditAsConflictCopyWithoutResurrectingOriginal() {
        val remote = state.copy(playlists = emptyList(), deletedPlaylists = setOf(playlist.id))
        val local = state.copy(playlists = listOf(playlist.copy(name = "An offline edit")))
        val merged = mergeLibrarySyncStates(state, local, remote, now)
        assertTrue(playlist.id in merged.deletedPlaylists)
        assertEquals("An offline edit (sync conflict)", merged.playlists.single().name)
        assertNotEquals(playlist.id, merged.playlists.single().id)
    }

    @Test fun unlikeWinsAgainstStaleLikesAndKnownUnlikeCanBeExplicitlyReliked() {
        val liked = state.copy(favorites = setOf(track.id))
        val unlike = state.copy(unlikedTracks = setOf(track.id))
        val merged = mergeLibrarySyncStates(liked, liked, unlike, now)
        assertTrue(merged.favorites.isEmpty())
        assertTrue(track.id in merged.unlikedTracks)
        assertTrue(mergeLibrarySyncStates(null, liked, merged, now).favorites.isEmpty())
        val reliked = mergeLibrarySyncStates(merged, liked, merged, now)
        assertEquals(setOf(track.id), reliked.favorites)
        assertFalse(track.id in reliked.unlikedTracks)
    }

    @Test fun listeningEventsAreDeduplicatedAndConflictingEventIdsRejected() {
        val listened = state.copy(history = listOf(event))
        assertEquals(listOf(event), mergeLibrarySyncStates(null, listened, listened, now).history)
        assertTrue(runCatching {
            mergeLibrarySyncStates(null, listened, state.copy(history = listOf(event.copy(listenedMs = 1))), now)
        }.isFailure)
    }

    @Test fun historyClearEpochRejectsOfflineOldHistoryButAcceptsSubsequentNewEvents() {
        val listened = state.copy(history = listOf(event))
        val cleared = state.copy(historyEpoch = FirebaseHistoryEpoch(1, device))
        val merged = mergeLibrarySyncStates(listened, listened, cleared, now)
        assertTrue(merged.history.isEmpty())
        assertEquals(cleared.historyEpoch, merged.historyEpoch)
        val newListen = event.copy(id = "$secondDevice:43", occurredAt = now)
        val continued = merged.copy(history = listOf(newListen))
        assertEquals(listOf(newListen), mergeLibrarySyncStates(merged, continued, merged, now).history)
    }

    @Test fun localPathsAndTemporarySourcesNeverEnterCloudPayload() {
        val local = Track("desktop:file:///C:/private/song.mp3", "Local", "Artist", sourceUri = "file:///C:/private/song.mp3",
            artworkUri = "file:///C:/private/art.png")
        val temporary = track.copy(sourceUri = "https://r1.googlevideo.com/audio?expire=123&token=secret",
            artworkUri = "https://example.com/art?token=secret", sourcePage = "file:///C:/private/page")
        val original = LibrarySyncState(listOf(local, temporary), listOf(FirebasePlaylist("p", "Files", listOf(local.id, track.id))),
            favorites = setOf(local.id))
        val safe = sanitizeLibrarySyncState(original, device)
        val encoded = encodeLibrarySyncState(safe)
        assertFalse(encoded.contains("private")); assertFalse(encoded.contains("secret")); assertFalse(encoded.contains("googlevideo"))
        assertTrue(safe.tracks.first().id.startsWith("cloud-local:$device:"))
        assertEquals(cloudTrackId(local, device), safe.favorites.single())
        assertNotEquals(cloudTrackId(local, secondDevice), cloudTrackId(local, device))
        assertEquals(track.sourceUri, safe.tracks.last().sourceUri)
        assertNull(safe.tracks.first().sourceUri)
    }

    @Test fun cloudParserRejectsMissingReferencesDuplicateIdsAndUnknownHistoryOutcomes() {
        val encoded = encodeLibrarySyncState(state.copy(history = listOf(event)))
        val missing = JSONObject(encoded).apply { getJSONArray("favorites").put("missing") }
        val duplicate = JSONObject(encoded).apply { getJSONArray("tracks").put(getJSONArray("tracks").getJSONObject(0)) }
        val badEvent = JSONObject(encoded).apply { getJSONArray("history").getJSONObject(0).put("event", "MADE_UP") }
        listOf(missing, duplicate, badEvent).forEach { assertTrue(runCatching { decodeLibrarySyncState(it.toString()) }.isFailure) }
    }

    @Test fun retentionRemovesOldEventsAndHistoryEpochTieBreakIsDeterministic() {
        val first = FirebaseHistoryEpoch(2, device)
        val second = FirebaseHistoryEpoch(2, secondDevice)
        assertTrue(second > first)
        val old = event.copy(occurredAt = now - 91L * 24 * 60 * 60 * 1000)
        val merged = mergeLibrarySyncStates(null, state.copy(history = listOf(old)), state, now)
        assertTrue(merged.history.isEmpty())
    }
}
