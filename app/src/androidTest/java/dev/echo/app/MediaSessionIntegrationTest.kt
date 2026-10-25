package dev.echo.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.echo.core.Track
import dev.echo.data.library.LibraryRepository
import dev.echo.playback.EchoPlaybackService
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class MediaSessionIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val fixture = "echo-session-test-${UUID.randomUUID()}"
    private lateinit var browser: MediaBrowser
    private lateinit var audio: File
    private lateinit var tracks: List<Track>

    private fun <T> main(action: () -> T): T {
        val task = FutureTask(Callable(action))
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    @Before fun connectRealServiceAndCreateAudio() {
        assertTrue("Run this against the app module's Hilt application", context.applicationContext is EchoApplication)
        val activities = main {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().filter { it != Stage.DESTROYED }.flatMap { monitor.getActivitiesInStage(it) }
                .filter { it.packageName == context.packageName }.toSet().onEach { it.finish() }
        }
        await("Existing Echo activities must finish and release their controller bindings") {
            activities.all { it.isDestroyed }
        }
        audio = File(context.filesDir, "$fixture.wav")
        val samples = 8_000 * 10
        // Silent PCM still exercises the actual local source, extractor, decoder and audio renderer.
        val wave = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        audio.writeBytes(wave.array())
        tracks = (1..2).map { index -> Track("$fixture-$index", "Fixture $index", fixture,
            album = fixture, durationMs = 10_000, sourceUri = Uri.fromFile(audio).toString()) }
        runBlocking { LibraryRepository(context).upsertTracks(tracks) }
        browser = main {
            MediaBrowser.Builder(context, SessionToken(context,
                ComponentName(context, EchoPlaybackService::class.java))).buildAsync()
        }.get(15, TimeUnit.SECONDS)
    }

    @After fun cleanUpOnlyOwnedFixtures() {
        if (::browser.isInitialized) main {
            if (browser.isConnected) {
                browser.stop()
                browser.clearMediaItems()
            }
            browser.release()
        }
        if (::tracks.isInitialized) {
            SQLiteDatabase.openDatabase(context.getDatabasePath("echo-library.db").path, null,
                SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.setForeignKeyConstraintsEnabled(true)
                tracks.forEach { database.delete("tracks", "id = ?", arrayOf(it.id)) }
                database.delete("albums", "name = ? AND artist = ?", arrayOf(fixture, fixture))
                database.delete("artists", "name = ?", arrayOf(fixture))
            }
        }
        if (::audio.isInitialized) audio.delete()
    }

    @Test fun realBrowserCanBrowseAndRejectUnknownIds() {
        val root = main { browser.getLibraryRoot(null) }.get(10, TimeUnit.SECONDS)
        assertEquals(0, root.resultCode)
        assertEquals("root", root.value!!.mediaId)
        val children = main { browser.getChildren("root", 0, 100, null) }.get(10, TimeUnit.SECONDS)
        assertEquals(setOf("local", "playlists", "favorites", "recent", "albums", "artists"),
            children.value!!.map { it.mediaId }.toSet())
        assertTrue(children.value!!.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
        val local = main { browser.getItem(tracks.first().id) }.get(10, TimeUnit.SECONDS)
        assertEquals(true, local.value!!.mediaMetadata.isPlayable)
        assertNotEquals(0, main { browser.getItem("$fixture-unknown") }.get(10, TimeUnit.SECONDS).resultCode)
    }

    @Test fun actualWavPlaysPausesSeeksAndKeepsQueueThroughControllerReconnect() {
        main {
            browser.setMediaItems(tracks.map(Track::toMediaItem))
            browser.prepare()
            browser.play()
        }
        await("Local PCM should reach playing state") { browser.isPlaying }
        main { browser.pause(); browser.seekTo(0, 1_000); browser.repeatMode = Player.REPEAT_MODE_ALL; browser.shuffleModeEnabled = true }
        await("Pause and seek must reach the remote player") { !browser.isPlaying && browser.currentPosition >= 900 }
        main {
            assertEquals(2, browser.mediaItemCount)
            assertEquals(Player.REPEAT_MODE_ALL, browser.repeatMode)
            assertTrue(browser.shuffleModeEnabled)
            browser.seekTo(1, 0)
        }
        await("Queue selection should reach the second item") { browser.currentMediaItem?.mediaId == tracks[1].id }
        main { browser.release() }
        browser = main {
            MediaBrowser.Builder(context, SessionToken(context,
                ComponentName(context, EchoPlaybackService::class.java))).buildAsync()
        }.get(15, TimeUnit.SECONDS)
        main {
            assertEquals(2, browser.mediaItemCount)
            assertEquals(tracks[1].id, browser.currentMediaItem?.mediaId)
            assertFalse(browser.playWhenReady)
        }
    }

    @Suppress("DEPRECATION")
    @Test fun serviceRecreationPreservesSavedQueueUntilExplicitResume() {
        main {
            browser.setMediaItems(tracks.map(Track::toMediaItem))
            browser.prepare()
            browser.play()
        }
        await("Fixture must play before saving a queue") { browser.isPlaying }
        main { browser.pause(); browser.seekTo(0, 2_000); browser.repeatMode = Player.REPEAT_MODE_ALL }
        await("Saved position should be established") { !browser.isPlaying && browser.currentPosition >= 1_900 }
        main {
            browser.release()
            context.stopService(Intent(context, EchoPlaybackService::class.java))
        }
        val manager = context.getSystemService(ActivityManager::class.java)
        await("Service must stop so the next connection creates a new player") {
            manager.getRunningServices(Int.MAX_VALUE).none { it.service.className == EchoPlaybackService::class.java.name }
        }
        val preferences = context.getSharedPreferences("echo_queue", Context.MODE_PRIVATE)
        val saved = preferences.getString("ids", null)
        assertTrue(saved!!.contains(tracks.first().id))
        browser = main {
            MediaBrowser.Builder(context, SessionToken(context,
                ComponentName(context, EchoPlaybackService::class.java))).buildAsync()
        }.get(15, TimeUnit.SECONDS)
        main {
            assertNull(browser.currentMediaItem)
            assertEquals("An empty controller connection must not overwrite saved queue IDs", saved,
                preferences.getString("ids", null))
            browser.play()
        }
        await("Explicit play should resolve and resume the persisted queue") {
            browser.isPlaying && browser.currentMediaItem?.mediaId == tracks.first().id && browser.currentPosition >= 1_900
        }
        main {
            assertEquals(2, browser.mediaItemCount)
            assertEquals(Player.REPEAT_MODE_ALL, browser.repeatMode)
        }
    }

    @Test fun realNotificationActionsControlPlaybackAndAudioContinuesWithScreenOff() {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        fun notification() = notifications.activeNotifications.firstOrNull {
            it.notification.category == Notification.CATEGORY_TRANSPORT
        }?.notification
        fun action(title: String) = notification()?.actions?.firstOrNull {
            it.title.toString().equals(title, ignoreCase = true)
        }
        fun keyEvent(key: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("input keyevent $key")).use { it.readBytes() }
        }
        main {
            browser.setMediaItem(tracks.first().toMediaItem())
            browser.prepare()
            browser.play()
        }
        await("Local audio must play before checking its notification") { browser.isPlaying }
        await("Media notification must expose its real Pause action on this English test device") {
            action("Pause") != null
        }
        assertTrue(notification()!!.actions.isNotEmpty())
        action("Pause")!!.actionIntent.send()
        await("Notification Pause PendingIntent must pause the service player") { !browser.isPlaying && !browser.playWhenReady }
        await("Paused notification must expose its real Play action") { action("Play") != null }
        action("Play")!!.actionIntent.send()
        await("Notification Play PendingIntent must resume the service player") { browser.isPlaying }
        main { browser.seekTo(0) }
        await("Start near the beginning before the screen-off check") { browser.isPlaying && browser.currentPosition < 1_000 }
        try {
            keyEvent("KEYCODE_SLEEP")
            await("Sleep command must actually turn the test device screen off") { !power.isInteractive }
            val before = main { browser.currentPosition }
            await("Playback must keep advancing for a second with the screen off") {
                !power.isInteractive && browser.isPlaying && browser.currentPosition >= before + 1_000
            }
        } finally {
            keyEvent("KEYCODE_WAKEUP")
        }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (main(predicate)) return
            Thread.sleep(50)
        }
        fail(message)
    }
}
