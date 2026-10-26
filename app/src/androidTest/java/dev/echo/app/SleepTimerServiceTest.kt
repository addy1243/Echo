package dev.echo.app

import android.content.ComponentName
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.core.Track
import dev.echo.data.library.LibraryRepository
import dev.echo.playback.EchoPlaybackService
import dev.echo.playback.SleepTimerController
import dev.echo.playback.toMediaItem
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

@UnstableApi
class SleepTimerServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val fixture = "echo-sleep-timer-${UUID.randomUUID()}"
    private lateinit var browser: MediaBrowser
    private lateinit var timer: SleepTimerController
    private lateinit var audio: File
    private lateinit var songs: List<Track>

    @Test fun servicePausesAfterActivityClosesPreservingQueueAndCancellationLeavesPlaybackRunning() {
        assumeTrue("Queue fixture test requires an emulator", Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"))
        audio = File(context.filesDir, "$fixture.wav")
        val samples = 8_000 * 30
        val wave = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        audio.writeBytes(wave.array())
        songs = (1..2).map { Track("$fixture-$it", "Sleep fixture $it", fixture, album = fixture,
            durationMs = 30_000, sourceUri = Uri.fromFile(audio).toString()) }
        runBlocking { LibraryRepository(context).upsertTracks(songs) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { timer = ViewModelProvider(it)[EchoViewModel::class.java].sleepTimer }
            browser = main { MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, EchoPlaybackService::class.java))).buildAsync() }
                .get(15, TimeUnit.SECONDS)
            main {
                browser.setMediaItems(songs.map(Track::toMediaItem))
                browser.repeatMode = Player.REPEAT_MODE_ALL
                browser.shuffleModeEnabled = true
                browser.prepare()
                browser.play()
            }
            await("Real local fixture audio should begin") {
                browser.isPlaying && browser.currentMediaItem?.mediaId in songs.map { it.id } && browser.currentPosition < 2000
            }
        }
        // The activity/ViewModel is gone; a real service still owns the pause deadline.
        val before = main {
            Triple(List(browser.mediaItemCount) { browser.getMediaItemAt(it).mediaId }, browser.currentMediaItemIndex, browser.currentPosition)
        }
        main { timer.startAfter(1500) }
        val power = context.getSystemService(PowerManager::class.java)
        try {
            keyEvent("KEYCODE_SLEEP")
            await("The emulator screen should be off") { !power.isInteractive }
            await("Service sleep timer should pause the real player with the screen off") {
                !power.isInteractive && !browser.playWhenReady && !browser.isPlaying && timer.remainingMs.value == null
            }
        } finally { keyEvent("KEYCODE_WAKEUP") }
        main {
            assertEquals(before.first, List(browser.mediaItemCount) { browser.getMediaItemAt(it).mediaId })
            assertEquals(before.second, browser.currentMediaItemIndex)
            assertEquals(before.first[before.second], browser.currentMediaItem?.mediaId)
            assertTrue(browser.currentPosition >= before.third)
            assertEquals(Player.REPEAT_MODE_ALL, browser.repeatMode)
            assertTrue(browser.shuffleModeEnabled)
            browser.play()
        }
        await("Playback should resume only after the explicit play request") { browser.isPlaying }
        val resumed = main { browser.currentPosition }
        main { timer.startAfter(1000); timer.cancel() }
        await("Cancelled deadline must not pause playback") { browser.isPlaying && browser.currentPosition >= resumed + 1800 }
        main { assertNull(timer.remainingMs.value); assertTrue(browser.playWhenReady) }
    }

    private fun <T> main(action: () -> T): T {
        val task = FutureTask(Callable(action))
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    private fun keyEvent(key: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent $key")).use { it.readBytes() }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (main(predicate)) return
            Thread.sleep(50)
        }
        fail(message)
    }

    @After fun removeOnlyOwnedFixtures() {
        if (::timer.isInitialized) main { timer.cancel() }
        if (::browser.isInitialized) main {
            if (browser.isConnected) { browser.stop(); browser.clearMediaItems() }
            browser.release()
        }
        if (::songs.isInitialized) SQLiteDatabase.openDatabase(context.getDatabasePath("echo-library.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.setForeignKeyConstraintsEnabled(true)
            songs.forEach { song -> it.delete("tracks", "id = ?", arrayOf(song.id)) }
            it.delete("albums", "name = ? AND artist = ?", arrayOf(fixture, fixture))
            it.delete("artists", "name = ?", arrayOf(fixture))
        }
        if (::audio.isInitialized) audio.delete()
    }
}
