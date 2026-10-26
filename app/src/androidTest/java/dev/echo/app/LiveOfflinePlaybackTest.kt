package dev.echo.app

import android.content.ComponentName
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.core.Track
import dev.echo.data.library.LibraryRepository
import dev.echo.data.offline.OfflineRepository
import dev.echo.data.offline.OfflineSettings
import dev.echo.playback.EchoPlaybackService
import dev.echo.playback.toMediaItem
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in download of the Blender Foundation's public Big Buck Bunny recording. Emulator only. */
@UnstableApi
class LiveOfflinePlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val fixtureId = "youtube:aqz-KE-bpKQ"
    private var offline: OfflineRepository? = null
    private var previous: OfflineSettings? = null
    private var inserted = false
    private var downloadStarted = false
    private var controller: MediaController? = null

    @Test fun downloadIsVerifiedAndRealAudioAdvancesAndSeeksInOfflineMode() {
        assumeTrue("Opt in with -e runLiveOffline true", InstrumentationRegistry.getArguments().getString("runLiveOffline") == "true")
        assumeTrue("This queue-replacing fixture requires the dedicated emulator",
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic"))
        val repository = LibraryRepository(context)
        assumeTrue("Do not overwrite an existing saved recording", runBlocking { repository.tracks.first().none { it.id == fixtureId } })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> offline = ViewModelProvider(activity)[EchoViewModel::class.java].offline }
            val storage = offline!!
            assumeTrue("Do not overwrite an existing downloaded recording", storage.songs.value.none { it.trackId == fixtureId })
            previous = storage.settings.value
            storage.setAutoCache(false)
            storage.setOfflineOnly(false)
            val track = Track(fixtureId, "Big Buck Bunny", "Blender Foundation", provider = "youtube",
                sourceUri = "https://www.youtube.com/watch?v=aqz-KE-bpKQ", sourcePage = "https://www.youtube.com/watch?v=aqz-KE-bpKQ")
            runBlocking { repository.upsertTracks(listOf(track)) }
            inserted = true
            storage.download(track)
            downloadStarted = true
            await("Public audio download must complete", 360) {
                val song = storage.songs.value.find { it.trackId == fixtureId }
                if (song?.status == "failed") fail(song.message ?: "Live download failed")
                song?.status == "saved" && song.bytes > 0
            }
            val local = runBlocking { storage.localSource(track) }
            assertNotNull("Complete file must pass byte count and SHA-256 verification", local)
            assertEquals("file", android.net.Uri.parse(local!!.uri).scheme)
            main { storage.setOfflineOnly(true) }
            val player = main { MediaController.Builder(context, SessionToken(context,
                ComponentName(context, EchoPlaybackService::class.java))).buildAsync() }.get(20, TimeUnit.SECONDS)
            controller = player
            main { player.setMediaItem(track.toMediaItem()); player.prepare(); player.play() }
            await("Saved audio must advance with all remote audio opens blocked", 40) {
                main {
                    player.playerError?.let { fail("Offline playback failed: ${it.errorCodeName}") }
                    player.isPlaying && player.currentPosition >= 1_000
                }
            }
            main { player.seekTo(10_000) }
            await("Saved audio must seek without source extraction", 20) { main { player.isPlaying && player.currentPosition >= 10_500 } }
            assertTrue(storage.settings.value.offlineOnly)
        }
    }

    @After fun removeOnlyFixtureAndRestoreSettings() {
        controller?.let { player -> main {
            if (player.isConnected) { player.stop(); player.clearMediaItems() }
            player.release()
        } }
        offline?.let { storage ->
            if (downloadStarted) runBlocking { storage.remove(fixtureId) }
            previous?.let { settings -> storage.setAutoCache(settings.autoCache); storage.setOfflineOnly(settings.offlineOnly) }
        }
        if (inserted) SQLiteDatabase.openDatabase(context.getDatabasePath("echo-library.db").path, null,
            SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.setForeignKeyConstraintsEnabled(true)
            database.delete("tracks", "id = ?", arrayOf(fixtureId))
            database.delete("albums", "name = '' AND artist = ? AND NOT EXISTS (SELECT 1 FROM tracks WHERE album = '' AND artist = ?)", arrayOf("Blender Foundation", "Blender Foundation"))
            database.delete("artists", "name = ? AND NOT EXISTS (SELECT 1 FROM tracks WHERE artist = ?)", arrayOf("Blender Foundation", "Blender Foundation"))
        }
    }

    private fun <T> main(action: () -> T): T {
        val task = FutureTask(Callable(action))
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    private fun await(message: String, seconds: Long, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail(message)
    }
}
