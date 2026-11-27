package dev.echo.app

import android.content.ComponentName
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Pairs the muted service audio player with the independent Cinema picture player. */
@UnstableApi
class StageStandbyAudioContinuityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
    private val fixture = "stage-cinema-$suffix"
    private val fixtureArtist = "Echo instrumentation $suffix"
    private val mediaIds = listOf("file:$fixture-a", "file:$fixture-b")
    private lateinit var audio: File
    private var controller: MediaController? = null
    private var fixtureTracks: List<Track> = emptyList()
    private var previousItems = emptyList<androidx.media3.common.MediaItem>()
    private var previousIndex = 0
    private var previousPosition = 0L
    private var previousPlayWhenReady = false
    private var previousVolume = 1f

    @Test fun cinemaEntryExitTrackChangeAndPauseLeaveServiceAudioInControl() {
        assumeTrue("Opt in only on a dedicated emulator with -e runStageServiceFixture true",
            InstrumentationRegistry.getArguments().getString("runStageServiceFixture") == "true")
        assumeTrue("Service continuity fixture uses the isolated Stage app", BuildConfig.STAGING)
        assumeTrue("Queue-replacing media fixture requires a dedicated emulator",
            android.os.Build.HARDWARE in setOf("ranchu", "goldfish") || android.os.Build.FINGERPRINT.startsWith("generic"))

        audio = File(context.filesDir, "$fixture.wav")
        writeSilentWave(audio, durationSeconds = 90)
        fixtureTracks = mediaIds.mapIndexed { index, id ->
            Track(id, "Stage fixture ${index + 1}", fixtureArtist, album = fixture,
                durationMs = 90_000, provider = "local", sourceUri = Uri.fromFile(audio).toString())
        }
        val repository = LibraryRepository(context)
        assumeTrue("Never replace an existing fixture recording",
            runBlocking { repository.tracks.first().none { it.id in mediaIds } })
        runBlocking { repository.upsertTracks(fixtureTracks) }

        val player = main {
            MediaController.Builder(context, SessionToken(context,
                ComponentName(context, EchoPlaybackService::class.java))).buildAsync()
        }.get(15, TimeUnit.SECONDS)
        controller = player
        main {
            previousItems = List(player.mediaItemCount) { player.getMediaItemAt(it) }
            previousIndex = player.currentMediaItemIndex.coerceAtLeast(0)
            previousPosition = player.currentPosition
            previousPlayWhenReady = player.playWhenReady
            previousVolume = player.volume
            // The fixture is intentionally silent and the emulator output remains muted.
            player.volume = 0f
            player.setMediaItems(fixtureTracks.map(Track::toMediaItem))
            player.prepare()
            player.play()
        }
        await("The local audio service must begin playing the generated fixture") {
            main { player.isPlaying && player.currentMediaItem?.mediaId == mediaIds.first() && player.currentPosition >= 500 }
        }

        var standby by mutableStateOf(false)
        var videoTrack by mutableStateOf(fixtureTracks.first().copy(provider = "youtube"))
        var audioPosition by mutableStateOf(0L)
        var audioPlaying by mutableStateOf(true)
        var videoReady by mutableStateOf(false)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme {
                LaunchedEffect(standby) {
                    while (standby) {
                        audioPosition = player.currentPosition
                        audioPlaying = player.isPlaying
                        delay(400)
                    }
                }
                Button(modifier = Modifier.testTag("standby-toggle"), onClick = { standby = !standby }) {
                    Text(if (standby) "Exit standby" else "Enter standby")
                }
                if (standby) StageCinema(
                    track = videoTrack,
                    occurrenceKey = videoTrack.id,
                    audioPositionMs = audioPosition,
                    audioPlaying = audioPlaying,
                    resolveVideo = { _, _ ->
                        dev.echo.core.PlayableSource(
                            "https://storage.googleapis.com/exoplayer-test-media-1/mp4/frame-counter-one-hour.mp4",
                            mimeType = "video/mp4",
                        )
                    },
                    onVideoReady = { videoReady = it },
                )
            }
        } }

        compose.onNodeWithTag("standby-toggle").performClick()
        await("Cinema must render its public video fixture's first frame") { videoReady }
        val entryPosition = main { player.currentPosition }
        await("Audio position must advance while Cinema is showing video") {
            main { player.isPlaying && player.currentPosition >= entryPosition + 700 }
        }
        main { assertEquals(mediaIds.first(), player.currentMediaItem?.mediaId); assertEquals(2, player.mediaItemCount) }

        // Move the authoritative service player to another occurrence while Cinema is mounted.
        main { player.seekTo(1, 0); player.play() }
        await("Service track change must take effect without replacing its queue") {
            main { player.isPlaying && player.currentMediaItem?.mediaId == mediaIds[1] && player.currentPosition >= 300 }
        }
        compose.runOnIdle {
            videoTrack = fixtureTracks[1].copy(provider = "youtube")
            audioPosition = player.currentPosition
            audioPlaying = true
            videoReady = false
        }
        await("Cinema must render the changed occurrence's first frame") { videoReady }
        val changedPosition = main { player.currentPosition }
        await("The changed track must keep advancing in the service player") {
            main { player.isPlaying && player.currentPosition >= changedPosition + 700 }
        }
        main { assertEquals(mediaIds[1], player.currentMediaItem?.mediaId); assertEquals(2, player.mediaItemCount) }

        main { player.pause() }
        compose.runOnIdle { audioPlaying = false; audioPosition = player.currentPosition }
        await("The service player must remain paused after Cinema input is updated") { main { !player.isPlaying && !player.playWhenReady } }
        val pausedPosition = main { player.currentPosition }
        Thread.sleep(700)
        assertEquals("A paused local audio position must remain fixed", pausedPosition, main { player.currentPosition })

        compose.runOnIdle { standby = false }
        compose.waitUntil(5_000) { !videoReady }
        main {
            assertFalse(player.isPlaying)
            assertEquals(mediaIds[1], player.currentMediaItem?.mediaId)
            assertEquals(2, player.mediaItemCount)
        }
        main { player.play() }
        await("Exiting Cinema must leave the paused service occurrence resumable") {
            main { player.isPlaying && player.currentMediaItem?.mediaId == mediaIds[1] && player.currentPosition > pausedPosition }
        }
    }

    @After fun restoreServiceQueueAndRemoveOnlyOwnedFixture() {
        controller?.let { player -> main {
            if (player.isConnected) {
                player.stop()
                if (previousItems.isNotEmpty()) {
                    player.setMediaItems(previousItems, previousIndex.coerceIn(0, previousItems.lastIndex), previousPosition)
                    player.prepare()
                    player.playWhenReady = previousPlayWhenReady
                } else {
                    player.clearMediaItems()
                }
                player.volume = previousVolume
            }
            player.release()
        } }
        if (fixtureTracks.isNotEmpty()) {
            SQLiteDatabase.openDatabase(context.getDatabasePath("echo-library.db").path, null,
                SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.setForeignKeyConstraintsEnabled(true)
                fixtureTracks.forEach { database.delete("tracks", "id = ?", arrayOf(it.id)) }
                database.delete("albums", "name = ? AND artist = ?", arrayOf(fixture, fixtureArtist))
                database.delete("artists", "name = ?", arrayOf(fixtureArtist))
            }
        }
        if (::audio.isInitialized) audio.delete()
    }

    private fun writeSilentWave(file: File, durationSeconds: Int) {
        val samples = 8_000 * durationSeconds
        val wave = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        file.writeBytes(wave.array())
    }

    private fun <T> main(action: () -> T): T {
        val task = FutureTask(Callable(action))
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    private fun await(message: String, predicate: () -> Boolean) {
        try { compose.waitUntil(30_000) { predicate() } }
        catch (error: AssertionError) { throw AssertionError(message, error) }
    }
}
