package dev.echo.app

import android.content.ComponentName
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.core.Track
import dev.echo.data.library.LibraryRepository
import dev.echo.data.provider.CommonsProvider
import dev.echo.playback.EchoPlaybackService
import dev.echo.playback.toMediaItem
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** External network test: pass instrumentation argument runLiveCommons=true to opt in. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class LiveCommonsPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val fixtureId = "echo-live-commons-test-${UUID.randomUUID()}"
    private var track: Track? = null
    private var controller: MediaController? = null

    private fun <T> main(action: () -> T): T {
        val task = FutureTask(Callable(action))
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    @Test fun licensedCommonsAudioPlaysThroughRealServiceWithProviderHeaders() {
        assumeTrue("Opt in with -e runLiveCommons true; requires live Wikimedia access",
            InstrumentationRegistry.getArguments().getString("runLiveCommons") == "true")
        assertTrue(context.applicationContext is EchoApplication)
        val provider = CommonsProvider()
        val result = runBlocking { provider.search("Chopin") }.firstOrNull { candidate ->
            val path = Uri.parse(candidate.sourceUri.orEmpty()).path.orEmpty().lowercase()
            path.endsWith(".ogg") || path.endsWith(".oga") || path.endsWith(".mp3")
        }
        assertNotNull("Live Commons search must return a licensed Ogg or MP3 recording", result)
        val fixture = result!!.copy(id = fixtureId)
        assertTrue(CommonsProvider.authorizedLicense(fixture.licenseUrl.orEmpty()))
        assertFalse(fixture.attribution.isNullOrBlank())
        val source = runBlocking { provider.resolve(fixture) }
        assertNotNull("Licensed Commons metadata must resolve", source)
        assertTrue("Commons playback requires the descriptive provider User-Agent",
            source!!.headers["User-Agent"].orEmpty().startsWith("EchoMusic/"))
        track = fixture
        runBlocking { LibraryRepository(context).upsertTracks(listOf(fixture)) }
        val player = main {
            MediaController.Builder(context, SessionToken(context,
                ComponentName(context, EchoPlaybackService::class.java))).buildAsync()
        }.get(20, TimeUnit.SECONDS)
        controller = player
        main { player.setMediaItem(fixture.toMediaItem()); player.prepare(); player.play() }
        await("Live HTTPS recording never reached playing state") {
            if (player.playerError != null) fail("Live playback failed: ${player.playerError!!.errorCodeName}")
            player.isPlaying && player.currentMediaItem?.mediaId == fixtureId
        }
        val initialPosition = main { player.currentPosition }
        await("Live audio position must advance through the service renderer") {
            player.isPlaying && player.currentPosition >= initialPosition + 1_000
        }
    }

    @After fun removeOnlyLiveTestFixture() {
        controller?.let { player -> main {
            if (player.isConnected) { player.stop(); player.clearMediaItems() }
            player.release()
        } }
        track?.let { fixture ->
            SQLiteDatabase.openDatabase(context.getDatabasePath("echo-library.db").path, null,
                SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.setForeignKeyConstraintsEnabled(true)
                database.delete("tracks", "id = ?", arrayOf(fixtureId))
                database.delete("albums", "name = ? AND artist = ? AND NOT EXISTS (SELECT 1 FROM tracks WHERE album = ? AND artist = ?)",
                    arrayOf(fixture.album, fixture.artist, fixture.album, fixture.artist))
                database.delete("artists", "name = ? AND NOT EXISTS (SELECT 1 FROM tracks WHERE artist = ?)",
                    arrayOf(fixture.artist, fixture.artist))
            }
        }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            if (main(predicate)) return
            Thread.sleep(100)
        }
        fail(message)
    }
}
