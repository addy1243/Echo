package dev.echo.data.library

import android.util.AtomicFile
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.data.firebase.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in live checks use a separate test database; they never open Echo's real library. */
class FirebaseCloudRoundtripTest {
    @Test fun sharedCloudRoundtripWithIsolatedAndroidRoom() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Live Firebase checks require explicit opt-in", arguments.getString("echoFirebaseLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phase = arguments.getString("echoFirebasePhase") ?: "download"
        require(phase in setOf("download", "upload-owned-playlist", "cleanup-owned-playlist")) { "Unsupported Firebase test phase" }
        val playlistId = arguments.getString("echoFirebasePlaylistId")
            ?: "echo-android-sync-check-710f571d-9bd6-4f20-bbd3-10cb735f1fd2"
        require(playlistId.matches(Regex("echo-android-sync-check-[a-f0-9-]{36}"))) { "Use a dedicated owned test playlist ID" }
        if (phase != "download") require(arguments.getString("echoFirebaseWriteOwnedPlaylist") == "true") {
            "Live mutation requires explicit owned-playlist opt-in"
        }
        val session = consumeSession(File(context.cacheDir, "echo-firebase-session.json"))
        val cloud = FirebaseLibraryCloud(EchoFirebaseProject.configuration())
        val marker = File(context.cacheDir, "echo-firebase-check-baseline.json")
        val prior = readMarker(marker)
        if (prior != null) require(prior.getString("uid") == session.uid && prior.getString("playlistId") == playlistId) {
            "Test baseline belongs to another account or fixture; preserve it for recovery"
        }
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, DATABASE).addMigrations(MIGRATION_1_2).build()
        try {
            val repository = LibraryRepository(context, database)
            val deviceId = repository.firebaseDeviceId()
            val document = cloud.read(session)
            val remote = document.json?.let(::decodeLibrarySyncState)
                ?: error("Upload the real desktop library before running the Android roundtrip check")
            repository.applyFirebaseState(repository.exportFirebaseState(deviceId), remote, deviceId, bootstrapAccount = true)
            assertTrue("Cloud metadata, favorites, history or duplicate occurrences differ after Room apply",
                encodeLibrarySyncState(remote) == encodeLibrarySyncState(repository.exportFirebaseState(deviceId)))
            var owned = prior?.optBoolean("owned") == true
            when (phase) {
                "upload-owned-playlist" -> {
                    require(!owned && remote.playlists.none { it.id == playlistId }) { "Fixture already exists; clean up before another upload" }
                    val track = remote.tracks.firstOrNull { it.provider == "youtube" && it.sourceUri != null }
                        ?: remote.tracks.firstOrNull()
                        ?: error("The cloud library needs a known track for duplicate-occurrence verification")
                    repository.importPlaylist(playlistId, "Echo Android sync verification", listOf(track, track))
                    // Record ownership before the network write so an interrupted check remains recoverable.
                    writeMarker(marker, session.uid, playlistId, remote, owned = true)
                    val merged = synchronizeFirebaseLibrary(cloud, session, remote, repository.exportFirebaseState(deviceId)) { }
                    repository.applyFirebaseState(repository.exportFirebaseState(deviceId), merged, deviceId)
                    assertTrue("Owned duplicate playlist did not survive cloud merge",
                        merged.playlists.single { it.id == playlistId }.trackIds == listOf(track.id, track.id))
                    owned = true
                    writeMarker(marker, session.uid, playlistId, merged, owned)
                }
                "cleanup-owned-playlist" -> {
                    require(owned) { "No ownership marker exists; refusing to delete a cloud playlist" }
                    repository.deletePlaylist(playlistId)
                    val merged = synchronizeFirebaseLibrary(cloud, session, remote, repository.exportFirebaseState(deviceId)) { }
                    repository.applyFirebaseState(repository.exportFirebaseState(deviceId), merged, deviceId)
                    assertTrue("Owned playlist cleanup did not converge", merged.playlists.none { it.id == playlistId })
                    assertTrue("Cleanup changed favorites", merged.favorites == remote.favorites)
                    assertTrue("Cleanup changed history", merged.history == remote.history)
                    assertTrue("Cleanup removed an unrelated playlist",
                        remote.playlists.filterNot { it.id == playlistId }.all { it in merged.playlists })
                    writeMarker(marker, session.uid, playlistId, merged, owned = false)
                }
                else -> writeMarker(marker, session.uid, playlistId, remote, owned)
            }
            val confirmed = cloud.read(session).json?.let(::decodeLibrarySyncState)
                ?: error("Cloud library disappeared during verification")
            assertTrue("Android and cloud changed during final verification; retry without overwriting",
                encodeLibrarySyncState(confirmed) == encodeLibrarySyncState(repository.exportFirebaseState(deviceId)))
            val expectedName = arguments.getString("echoFirebaseExpectedPlaylistName")
            if (expectedName != null) assertTrue("Desktop playlist edit was not received by Android Room",
                confirmed.playlists.any { it.id == playlistId && it.name == expectedName })
            println("PASS Firebase Android Room phase=$phase playlists=${confirmed.playlists.size} favorites=${confirmed.favorites.size} history=${confirmed.history.size}")
        } finally { database.close() }
    }

    private fun consumeSession(file: File): FirebaseSession {
        require(file.isFile && file.length() in 1..65_536) { "Provide the short-lived session in the test app's private cache" }
        val encoded = try { file.readText() } finally { check(file.delete()) { "Could not remove the temporary session handoff" } }
        return try {
            val value = JSONObject(encoded)
            val expiresAt = value.getLong("expiresAt")
            require(expiresAt > System.currentTimeMillis() + 60_000)
            FirebaseSession(value.getString("uid"), value.getString("email"), value.getString("idToken"), "not-refreshable-test-handoff", expiresAt)
        } catch (_: Exception) { error("The short-lived Firebase session is invalid or expired") }
    }

    private fun readMarker(file: File): JSONObject? {
        if (!file.exists()) return null
        require(file.length() <= 8 * 1024 * 1024) { "Fixture baseline is oversized; preserve it for recovery" }
        return try { JSONObject(file.readText()) } catch (_: Exception) { error("Fixture baseline is unreadable; preserve it for recovery") }
    }

    private fun writeMarker(file: File, uid: String, playlistId: String, baseline: LibrarySyncState, owned: Boolean) {
        val bytes = JSONObject().put("uid", uid).put("playlistId", playlistId).put("owned", owned)
            .put("baseline", JSONObject(encodeLibrarySyncState(baseline))).toString().toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

    private companion object { const val DATABASE = "echo-firebase-cross-device-check.db" }
}
