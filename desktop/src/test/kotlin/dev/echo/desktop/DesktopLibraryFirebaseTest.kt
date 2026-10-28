package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.data.firebase.*
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopLibraryFirebaseTest {
    @get:Rule val temporary = TemporaryFolder()
    private val song = Track("youtube:abcdefghijk", "Song", "Artist", provider = "youtube", durationMs = 180_000,
        sourceUri = "https://www.youtube.com/watch?v=abcdefghijk")
    private fun library(name: String) = DesktopLibrary(temporary.root.toPath().resolve("$name.json"))

    @Test fun twoIndependentStoresKeepPlaylistIdentityDuplicatesFavoritesAndStableHistory() {
        val phone = library("phone")
        val desktop = library("desktop")
        val playlist = phone.createPlaylist("From another device")
        phone.addToPlaylist(playlist.id, song); phone.addToPlaylist(playlist.id, song)
        phone.toggleFavorite(song); phone.recordHistory(song, "COMPLETED", 50_000, 180_000)
        val expected = desktop.exportFirebaseState()
        val shared = mergeLibrarySyncStates(null, expected, phone.exportFirebaseState())
        desktop.applyFirebaseState(expected, shared)
        assertEquals(playlist.id, desktop.snapshot().playlists.single().id)
        assertEquals(listOf(song.id, song.id), desktop.snapshot().playlists.single().trackIds)
        assertEquals(setOf(song.id), desktop.snapshot().favorites)
        assertEquals(phone.snapshot().history.single().id, desktop.snapshot().history.single().id)
        repeat(3) {
            val current = desktop.exportFirebaseState()
            desktop.applyFirebaseState(current, mergeLibrarySyncStates(shared, current, shared))
        }
        val restarted = library("desktop")
        assertEquals(1, restarted.snapshot().history.size)
        assertEquals(1, restarted.snapshot().playlists.size)
        assertEquals(desktop.firebaseDeviceId, restarted.firebaseDeviceId)
    }

    @Test fun unlikeDeleteAndHistoryClearConvergeAndDoNotReturnFromStaleCopies() {
        val first = library("first")
        val second = library("second")
        val playlist = first.createPlaylist("Synced")
        first.addToPlaylist(playlist.id, song); first.toggleFavorite(song); first.recordHistory(song, "PLAYED")
        val initial = first.exportFirebaseState()
        second.applyFirebaseState(second.exportFirebaseState(), initial)
        second.toggleFavorite(song); second.deletePlaylist(playlist.id); second.clearHistory()
        val cleared = mergeLibrarySyncStates(initial, second.exportFirebaseState(), initial)
        first.applyFirebaseState(initial, cleared)
        assertTrue(first.snapshot().playlists.isEmpty())
        assertTrue(first.snapshot().favorites.isEmpty())
        assertTrue(first.snapshot().history.isEmpty())
        assertEquals(second.snapshot().historyEpoch, first.snapshot().historyEpoch)
        val stale = mergeLibrarySyncStates(null, initial, first.exportFirebaseState())
        assertTrue(stale.playlists.isEmpty()); assertTrue(stale.favorites.isEmpty()); assertTrue(stale.history.isEmpty())
        assertEquals(1, first.snapshot().tracks.size)
    }

    @Test fun ownFileAssociationSurvivesCloudRoundtripButIsUnavailableOnAnotherInstallation() {
        val first = library("first")
        val second = library("second")
        val audio = temporary.newFile("private-recording.wav")
        val local = DesktopLibrary.localTrack(audio)
        val playlist = first.createPlaylist("Local file")
        first.addToPlaylist(playlist.id, local); first.toggleFavorite(local); first.recordHistory(local, "PLAYED")
        val exported = first.exportFirebaseState()
        val payload = encodeLibrarySyncState(exported)
        assertEquals(local.title, exported.tracks.single().title)
        assertNull(exported.tracks.single().sourceUri)
        assertNull(exported.tracks.single().artworkUri)
        assertFalse(exported.tracks.single().id.contains("private-recording"))
        assertFalse(payload.contains("file:"))
        first.applyFirebaseState(exported, exported)
        assertEquals(local.sourceUri, first.snapshot().tracks.single().sourceUri)
        assertEquals(local.id, first.snapshot().favorites.single())
        assertEquals(exported, first.exportFirebaseState())
        second.applyFirebaseState(second.exportFirebaseState(), exported)
        assertNull(second.snapshot().tracks.single().sourceUri)
        assertTrue(second.snapshot().tracks.single().id.startsWith("cloud-local:"))
        assertEquals(exported.tracks.single().id, second.exportFirebaseState().tracks.single().id)
    }

    @Test fun applyRejectsConcurrentLocalChangesAndPreservesBothMemoryAndDisk() {
        val store = library("store")
        val expected = store.exportFirebaseState()
        store.toggleFavorite(song)
        val bytes = Files.readAllBytes(store.file)
        assertTrue(runCatching { store.applyFirebaseState(expected, LibrarySyncState()) }.isFailure)
        assertEquals(setOf(song.id), store.snapshot().favorites)
        assertArrayEquals(bytes, Files.readAllBytes(store.file))
    }

    @Test fun legacyVersionOneLibraryGetsDurableIdentityWithoutDroppingData() {
        val original = library("legacy")
        original.toggleFavorite(song)
        val legacy = JSONObject(Files.readString(original.file)).apply {
            listOf("installationId", "historyEpoch", "deletedPlaylists", "unlikedTracks").forEach(::remove)
        }
        Files.writeString(original.file, legacy.toString())
        val upgraded = library("legacy")
        val id = upgraded.firebaseDeviceId
        assertEquals(id, library("legacy").firebaseDeviceId)
        assertEquals(setOf(song.id), upgraded.snapshot().favorites)
        assertEquals(FirebaseHistoryEpoch(), upgraded.snapshot().historyEpoch)
        val backup = JSONObject(upgraded.exportBackup())
        assertFalse(backup.has("installationId")); assertFalse(backup.has("historyEpoch"))
    }

    @Test fun explicitRelikeRemovesPersistedUnlikeAndHistoryClearRevisionSurvivesRestart() {
        val store = library("store")
        store.toggleFavorite(song); store.toggleFavorite(song)
        assertEquals(setOf(song.id), store.snapshot().unlikedTracks)
        store.toggleFavorite(song)
        assertTrue(store.snapshot().unlikedTracks.isEmpty())
        store.clearHistory(); store.clearHistory()
        val restarted = library("store")
        assertEquals(2L, restarted.snapshot().historyEpoch.counter)
        assertEquals(restarted.firebaseDeviceId, restarted.snapshot().historyEpoch.deviceId)
    }

    @Test fun onlyExplicitAccountBootstrapCanResetHistoryClearRevision() {
        val store = library("account-bootstrap")
        store.recordHistory(song, "PLAYED")
        val before = store.exportFirebaseState()
        store.clearHistory()
        val cleared = store.exportFirebaseState()
        assertTrue(runCatching { store.applyFirebaseState(cleared, before) }.isFailure)
        assertTrue(store.snapshot().history.isEmpty())
        store.applyFirebaseState(cleared, before, bootstrapAccount = true)
        assertEquals(before, store.exportFirebaseState())
    }
}
