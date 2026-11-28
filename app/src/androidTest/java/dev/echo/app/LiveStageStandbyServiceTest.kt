package dev.echo.app

import android.os.Build
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import dev.echo.core.Track
import dev.echo.data.library.LibraryRepository
import dev.echo.playback.toMediaItem
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in live check of YouTube audio and Stage Cinema sharing the activity's authoritative service occurrence. */
@UnstableApi
class LiveStageStandbyServiceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var vm: EchoViewModel
    private lateinit var player: MediaController
    private var priorItems = emptyList<androidx.media3.common.MediaItem>()
    private var priorIndex = 0
    private var priorPosition = 0L
    private var priorPlayWhenReady = false
    private var priorShuffle = false
    private var priorRepeat = Player.REPEAT_MODE_OFF
    private var priorVolume = 1f
    private var priorCinema: StageStandbySettings? = null
    private var capturedState = false
    private var priorOrientation: Int? = null
    private var fixtureInserted = false
    private val fixtureSuffix = UUID.randomUUID().toString()

    @Test fun liveAudioContinuesThroughStandbyCinemaAndPauseThenReturnsToEcho() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e runLiveStageStandbyService true",
            args.getString("runLiveStageStandbyService") == "true")
        assumeTrue("Live Stage playback is emulator-only",
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic"))
        assumeTrue("Service continuity fixture uses the isolated Stage app", BuildConfig.STAGING)

        compose.activityRule.scenario.onActivity {
            priorOrientation = it.requestedOrientation
            it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(15_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[EchoViewModel::class.java]
        }
        await("Echo must connect its service controller") { vm.controller?.isConnected == true }
        player = requireNotNull(vm.controller)
        onMain {
            priorItems = List(player.mediaItemCount) { player.getMediaItemAt(it) }
            priorIndex = player.currentMediaItemIndex.coerceAtLeast(0)
            priorPosition = player.currentPosition.coerceAtLeast(0)
            priorPlayWhenReady = player.playWhenReady
            priorShuffle = player.shuffleModeEnabled
            priorRepeat = player.repeatMode
            priorVolume = player.volume
            player.volume = 0f
            capturedState = true
        }

        val track = Track(
            id = "youtube:$VIDEO_ID",
            title = "Big Buck Bunny",
            artist = "Echo live Stage test $fixtureSuffix",
            album = "Echo live Stage test $fixtureSuffix",
            sourceUri = "https://www.youtube.com/watch?v=$VIDEO_ID",
            provider = "youtube",
            sourcePage = "https://www.youtube.com/watch?v=$VIDEO_ID",
        )
        val repository = vm.library
        assumeTrue("Use an emulator without Big Buck Bunny already saved; existing library rows are preserved",
            runBlocking { repository.tracks.first().none { it.id == track.id } })
        runBlocking { repository.upsertTracks(listOf(track)) }
        fixtureInserted = true
        await("Fixture must appear in the app library flow") { vm.tracks.value.any { it.id == track.id } }

        val settings = StageStandbyPreferences(context)
        priorCinema = settings.read()
        settings.update(settings.read().copy(cinema = true))
        onMain {
            player.setMediaItems(listOf(track.toMediaItem()))
            player.prepare()
            player.play()
        }
        await("EchoPlaybackService must play live Big Buck Bunny audio") {
            onMain { player.isPlaying && player.currentMediaItem?.mediaId == track.id && player.currentPosition >= 1_000 && vm.current?.mediaId == track.id }
        }
        val startingPosition = onMain { player.currentPosition }
        val occurrence = onMain {
            player.currentTimeline.getWindow(player.currentMediaItemIndex, androidx.media3.common.Timeline.Window()).uid.toString()
        }
        assertTrue("Service audio resolution must populate the VM video cache for the exact active occurrence",
            onMain { runBlocking { vm.resolveStageCinemaVideo(track, occurrence) != null } })

        if (compose.onAllNodesWithTag("tutorial-skip").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("tutorial-skip").performClick()
        }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-section-standby"))
        compose.onNodeWithTag("settings-section-standby").performClick()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("stage-standby-open"))
        compose.onNodeWithTag("stage-standby-open").performClick()
        compose.onNodeWithTag("stage-standby-screen").assertIsDisplayed()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("stage-cinema-video-surface").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("stage-cinema-video-ready").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(90_000) {
            compose.onAllNodesWithTag("stage-cinema-video-ready").fetchSemanticsNodes().isNotEmpty()
        }
        val detailsBounds = compose.onNodeWithTag("stage-cinema-track").fetchSemanticsNode().boundsInRoot
        val pictureBounds = compose.onNodeWithTag("stage-cinema-picture").fetchSemanticsNode().boundsInRoot
        val videoBounds = compose.onNodeWithTag("stage-cinema-video-ready").fetchSemanticsNode().boundsInRoot
        assertTrue("Track details must be left of the larger picture pane", detailsBounds.right <= pictureBounds.left &&
            pictureBounds.width > detailsBounds.width)
        assertTrue("The decoded video must remain within its pane",
            videoBounds.left >= pictureBounds.left && videoBounds.right <= pictureBounds.right &&
                videoBounds.top >= pictureBounds.top && videoBounds.bottom <= pictureBounds.bottom)
        val cinemaPosition = onMain { player.currentPosition }
        await("Service audio position must advance while Cinema renders") {
            onMain { player.isPlaying && player.currentPosition >= cinemaPosition + 1_000 }
        }
        assertEquals("Cinema must remain on the exact active service occurrence", occurrence, onMain {
            player.currentTimeline.getWindow(player.currentMediaItemIndex, androidx.media3.common.Timeline.Window()).uid.toString()
        })
        assertTrue("Live service audio must have advanced from its starting position", onMain { player.currentPosition > startingPosition })

        onMain { player.pause() }
        await("Service audio must pause while Standby is open") { onMain { !player.isPlaying && !player.playWhenReady } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("stage-cinema-artwork-fallback").fetchSemanticsNodes().isNotEmpty()
        }
        val pausedAt = onMain { player.currentPosition }
        Thread.sleep(1_000)
        assertTrue("Paused service position must remain stable", onMain { kotlin.math.abs(player.currentPosition - pausedAt) < 250 })

        compose.onNodeWithTag("stage-standby-screen").performTouchInput { click() }
        compose.onNodeWithTag("stage-standby-screen").assertDoesNotExist()
        assertEquals(track.id, onMain { player.currentMediaItem?.mediaId })
        assertEquals(occurrence, onMain {
            player.currentTimeline.getWindow(player.currentMediaItemIndex, androidx.media3.common.Timeline.Window()).uid.toString()
        })
        assertFalse("Leaving Standby must not resume audio that was paused", onMain { player.isPlaying })
    }

    @After fun restoreEchoPlaybackAndStandbySettings() {
        if (::player.isInitialized && capturedState) onMain {
            if (player.isConnected) {
                player.pause()
                if (priorItems.isNotEmpty()) {
                    player.setMediaItems(priorItems, priorIndex.coerceIn(0, priorItems.lastIndex), priorPosition)
                    player.shuffleModeEnabled = priorShuffle
                    player.repeatMode = priorRepeat
                    player.volume = priorVolume
                    player.prepare()
                    player.playWhenReady = priorPlayWhenReady
                } else {
                    player.clearMediaItems()
                    player.volume = priorVolume
                }
            }
        }
        priorOrientation?.let { previous -> compose.activityRule.scenario.onActivity { it.requestedOrientation = previous } }
        priorCinema?.let { StageStandbyPreferences(context).update(it) }
        if (fixtureInserted) {
            SQLiteDatabase.openDatabase(context.getDatabasePath("echo-library.db").path, null,
                SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.setForeignKeyConstraintsEnabled(true)
                database.delete("tracks", "id = ?", arrayOf("youtube:$VIDEO_ID"))
                database.delete("albums", "name = ? AND artist = ?", arrayOf("Echo live Stage test $fixtureSuffix", "Echo live Stage test $fixtureSuffix"))
                database.delete("artists", "name = ?", arrayOf("Echo live Stage test $fixtureSuffix"))
            }
        }
    }

    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask(Callable(action))
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    private fun await(message: String, condition: () -> Boolean) {
        try { compose.waitUntil(60_000) { condition() } }
        catch (error: AssertionError) { throw AssertionError(message, error) }
    }

    companion object { private const val VIDEO_ID = "aqz-KE-bpKQ" }
}
