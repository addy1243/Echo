package dev.echo.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import android.database.sqlite.SQLiteDatabase
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionError
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.core.Track
import dev.echo.data.library.LibraryRepository
import dev.echo.data.provider.YouTubeProvider
import dev.echo.playback.EchoPlaybackService
import dev.echo.playback.toMediaItem
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live test of the Blender Foundation's openly licensed Big Buck Bunny upload. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class LiveYouTubePlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val fixtureId = "youtube:aqz-KE-bpKQ"
    private var controller: MediaController? = null
    private var inserted = false
    @Volatile private var sourceError: String? = null
    private val sessionListener = object : MediaController.Listener {
        override fun onError(controller: MediaController, sessionError: SessionError) { sourceError = sessionError.message }
    }

    private fun <T> main(action: () -> T): T {
        val task = FutureTask(Callable(action))
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    @Test fun keyFreeMusicSearchReturnsStableSongIdentities() {
        assumeTrue("Opt in with -e runLiveYouTube true; requires live YouTube access",
            InstrumentationRegistry.getArguments().getString("runLiveYouTube") == "true")
        val songs = runBlocking { YouTubeProvider().search("Kevin MacLeod Sneaky Snitch") }
        assertTrue("Live YouTube Music search must return song metadata without a Data API key", songs.isNotEmpty())
        assertTrue(songs.all { song -> song.provider == "youtube" && song.durationMs > 0 &&
            song.sourceUri == "https://www.youtube.com/watch?v=${song.id.removePrefix("youtube:")}" })
    }

    @Suppress("DEPRECATION")
    @Test fun onlineAudioAdvancesSeeksAndLoadsTheNextQueueEntry() {
        assumeTrue("Opt in with -e runLiveYouTube true; requires live YouTube access",
            InstrumentationRegistry.getArguments().getString("runLiveYouTube") == "true")
        val repository = LibraryRepository(context)
        assumeTrue("Use an emulator without this recording already saved",
            runBlocking { repository.tracks.first().none { it.id == fixtureId } })
        val track = Track(fixtureId, "Big Buck Bunny", "Blender Foundation",
            sourceUri = "https://www.youtube.com/watch?v=aqz-KE-bpKQ", provider = "youtube",
            sourcePage = "https://www.youtube.com/watch?v=aqz-KE-bpKQ")
        runBlocking { repository.upsertTracks(listOf(track)) }
        inserted = true
        val player = main {
            MediaController.Builder(context, SessionToken(context,
                ComponentName(context, EchoPlaybackService::class.java))).setListener(sessionListener).buildAsync()
        }.get(20, TimeUnit.SECONDS)
        controller = player
        main {
            player.setMediaItems(listOf(track.toMediaItem(), track.toMediaItem()))
            player.prepare()
            player.play()
        }
        await("Online audio must advance") { player.isPlaying && player.currentPosition >= 1_000 }
        main { player.pause() }
        await("Online playback must pause") { !player.isPlaying && !player.playWhenReady }
        main { player.play() }
        await("Online playback must resume") { player.isPlaying }
        main { player.seekTo(0, 10_000) }
        await("Online source must support seeking") { player.isPlaying && player.currentPosition >= 10_500 }
        main { player.seekToNextMediaItem() }
        await("Next entry must resolve and play") {
            player.currentMediaItemIndex == 1 && player.isPlaying && player.currentPosition >= 1_000
        }
        // A new queue prepares a new source, which must resolve its own fresh stream URL.
        main { player.setMediaItem(track.toMediaItem()); player.prepare(); player.play() }
        await("A newly prepared online source must play again") {
            player.currentMediaItemIndex == 0 && player.isPlaying && player.currentPosition >= 1_000
        }
        main { player.pause(); player.seekTo(0, 2_000) }
        await("Online saved position must be established") { !player.isPlaying && player.currentPosition >= 1_900 }
        main {
            player.release()
            context.stopService(Intent(context, EchoPlaybackService::class.java))
        }
        controller = null
        val manager = context.getSystemService(ActivityManager::class.java)
        await("Online service must stop before testing persisted queue restoration") {
            manager.getRunningServices(Int.MAX_VALUE).none { it.service.className == EchoPlaybackService::class.java.name }
        }
        val restored = main {
            MediaController.Builder(context, SessionToken(context,
                ComponentName(context, EchoPlaybackService::class.java))).setListener(sessionListener).buildAsync()
        }.get(20, TimeUnit.SECONDS)
        controller = restored
        main { assertNull(restored.currentMediaItem); restored.play() }
        await("A recreated service must freshly resolve and resume the saved YouTube track") {
            restored.isPlaying && restored.currentMediaItem?.mediaId == fixtureId && restored.currentPosition >= 1_900
        }
    }

    @After fun removeOnlyThisFixture() {
        controller?.let { player -> main {
            if (player.isConnected) { player.stop(); player.clearMediaItems() }
            player.release()
        } }
        if (inserted) SQLiteDatabase.openDatabase(context.getDatabasePath("echo-library.db").path,
            null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.setForeignKeyConstraintsEnabled(true)
            database.delete("tracks", "id = ?", arrayOf(fixtureId))
            database.delete("albums", "name = '' AND artist = ? AND NOT EXISTS (SELECT 1 FROM tracks WHERE album = '' AND artist = ?)",
                arrayOf("Blender Foundation", "Blender Foundation"))
            database.delete("artists", "name = ? AND NOT EXISTS (SELECT 1 FROM tracks WHERE artist = ?)",
                arrayOf("Blender Foundation", "Blender Foundation"))
        }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            if (main {
                // A transient player error may still be undergoing the service's bounded URL refresh.
                sourceError?.let { fail("Online playback failed: $it") }
                predicate()
            }) return
            Thread.sleep(100)
        }
        fail(message)
    }
}
