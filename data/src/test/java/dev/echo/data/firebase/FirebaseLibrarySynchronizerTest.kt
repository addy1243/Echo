package dev.echo.data.firebase

import dev.echo.core.Track
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FirebaseLibrarySynchronizerTest {
    private val config = FirebaseConfig("echo-music-test", "synthetic-public-api-key")
    private val session = FirebaseSession("user_one", "owner@example.com", "synthetic-id-token", "synthetic-refresh-token", 9_999_999_999_999)
    private val song = Track("youtube:12345678901", "Song", "Artist", provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=12345678901")
    private val original = LibrarySyncState(tracks = listOf(song), playlists = listOf(FirebasePlaylist("playlist", "Original", listOf(song.id, song.id))))

    @Test fun identicalCanonicalStateDoesNotWriteCloud() = runBlocking {
        var writes = 0
        var checks = 0
        val cloud = cloud { request ->
            if (request.method != "GET") writes++
            200 to document(original, 1)
        }
        val merged = synchronizeFirebaseLibrary(cloud, session, original, original) { checks++ }
        assertEquals(original, merged)
        assertEquals(0, writes)
        assertEquals(3, checks)
    }

    @Test fun missingInitialDocumentDoesNotDeleteLocalPlaylistsUsingAnOldBaseline() = runBlocking {
        var writes = 0
        val cloud = cloud { request ->
            if (request.method == "GET") 404 to missing()
            else {
                writes++
                val commit = JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                assertFalse(commit.getJSONArray("writes").getJSONObject(0).getJSONObject("currentDocument").getBoolean("exists"))
                200 to committed()
            }
        }
        assertEquals(original, synchronizeFirebaseLibrary(cloud, session, original, original) { })
        assertEquals(1, writes)
    }

    @Test fun casConflictRefetchesCloudAndPreservesBothConcurrentEditsAndDuplicates() = runBlocking {
        var reads = 0
        var writes = 0
        val local = original.copy(favorites = setOf(song.id))
        val remoteEdit = original.copy(playlists = original.playlists.map { it.copy(name = "Remote rename") })
        val cloud = cloud { request ->
            if (request.method == "GET") {
                reads++
                200 to document(if (reads == 1) original else remoteEdit, reads)
            } else {
                writes++
                if (writes == 1) 409 to "{\"error\":{\"status\":\"ABORTED\"}}"
                else 200 to committed()
            }
        }
        val merged = synchronizeFirebaseLibrary(cloud, session, original, local) { }
        assertEquals(2, reads)
        assertEquals(2, writes)
        assertEquals(setOf(song.id), merged.favorites)
        assertEquals("Remote rename", merged.playlists.single().name)
        assertEquals(listOf(song.id, song.id), merged.playlists.single().trackIds)
    }

    @Test fun persistentContentionStopsAfterThreeAttempts() = runBlocking {
        var reads = 0
        var writes = 0
        val cloud = cloud { request ->
            if (request.method == "GET") { reads++; 404 to missing() }
            else { writes++; 409 to "{\"error\":{\"status\":\"ABORTED\"}}" }
        }
        val error = runCatching { synchronizeFirebaseLibrary(cloud, session, null, original) { } }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("Local data was kept"))
        assertEquals(3, reads)
        assertEquals(3, writes)
    }

    @Test fun accountChangeAfterReadStopsBeforeWrite() = runBlocking {
        var checks = 0
        var writes = 0
        val cloud = cloud { request ->
            if (request.method != "GET") writes++
            404 to missing()
        }
        assertTrue(runCatching {
            synchronizeFirebaseLibrary(cloud, session, null, original) {
                checks++
                check(checks < 2) { "Account changed" }
            }
        }.isFailure)
        assertEquals(0, writes)
    }

    @Test fun missingEmptyCloudDoesNotCreateAnEmptyDocument() = runBlocking {
        var writes = 0
        val cloud = cloud { request ->
            if (request.method != "GET") writes++
            404 to missing()
        }
        assertEquals(LibrarySyncState(), synchronizeFirebaseLibrary(cloud, session, null, LibrarySyncState()) { })
        assertEquals(0, writes)
    }

    @Test fun firstEnableDoesNotCarryAnotherAccountsDeletionsOrHistoryClear() = runBlocking {
        val now = System.currentTimeMillis()
        val device = "12345678-1234-1234-1234-123456789012"
        val oldEvent = FirebaseHistoryEvent("remote-event", song.id, "PLAYED", now - 1000, 0, 100_000)
        val localEvent = FirebaseHistoryEvent("local-event", song.id, "COMPLETED", now, 40_000, 100_000)
        val remote = original.copy(favorites = setOf(song.id), history = listOf(oldEvent),
            historyEpoch = FirebaseHistoryEpoch(1, device))
        val local = original.copy(playlists = emptyList(), favorites = emptySet(), history = listOf(localEvent),
            deletedPlaylists = setOf("playlist"), unlikedTracks = setOf(song.id),
            historyEpoch = FirebaseHistoryEpoch(99, device))
        val cloud = cloud { request -> if (request.method == "GET") 200 to document(remote, 1) else 200 to committed() }
        val merged = synchronizeFirebaseLibrary(cloud, session, null, local, importDeviceHistory = true) { }
        assertEquals(remote.playlists, merged.playlists)
        assertEquals(remote.favorites, merged.favorites)
        assertEquals(setOf(oldEvent.id, localEvent.id), merged.history.map { it.id }.toSet())
        assertEquals(remote.historyEpoch, merged.historyEpoch)
        assertTrue(merged.deletedPlaylists.isEmpty())
        assertTrue(merged.unlikedTracks.isEmpty())
    }

    @Test fun unknownClientDoesNotRestoreHistoryFromBeforeTheAccountsClear() = runBlocking {
        val now = System.currentTimeMillis()
        val remoteEvent = FirebaseHistoryEvent("remote-after-clear", song.id, "PLAYED", now, 0, 100_000)
        val staleEvent = FirebaseHistoryEvent("local-before-clear", song.id, "PLAYED", now - 1000, 0, 100_000)
        val remote = original.copy(history = listOf(remoteEvent), historyEpoch =
            FirebaseHistoryEpoch(1, "12345678-1234-1234-1234-123456789012"))
        val local = original.copy(history = listOf(staleEvent))
        val cloud = cloud { request -> if (request.method == "GET") 200 to document(remote, 1) else 200 to committed() }
        val merged = synchronizeFirebaseLibrary(cloud, session, null, local) { }
        assertEquals(listOf(remoteEvent), merged.history)
        assertEquals(remote.historyEpoch, merged.historyEpoch)
    }

    private fun cloud(block: (okhttp3.Request) -> Pair<Int, String>): FirebaseLibraryCloud = FirebaseLibraryCloud(config,
        OkHttpClient.Builder().addInterceptor { chain ->
            val result = block(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(result.first).message("Fixture")
                .body(result.second.toResponseBody()).build()
        }.build())
    private fun missing() = "{\"error\":{\"status\":\"NOT_FOUND\",\"message\":\"Document not found\"}}"
    private fun committed() = "{\"writeResults\":[{\"updateTime\":\"2026-10-27T00:00:03Z\"}]}"
    private fun document(state: LibrarySyncState, revision: Int) = JSONObject()
        .put("name", "projects/${config.projectId}/databases/(default)/documents/users/${session.uid}/sync/library-v1")
        .put("updateTime", "2026-10-27T00:00:0${revision}Z")
        .put("fields", JSONObject().put("uid", string(session.uid)).put("format", string("echo-library-state"))
            .put("version", JSONObject().put("integerValue", "1")).put("encoding", string("gzip-base64"))
            .put("payload", string(encodeFirebasePayload(encodeLibrarySyncState(state))))).toString()
    private fun string(value: String) = JSONObject().put("stringValue", value)
}
