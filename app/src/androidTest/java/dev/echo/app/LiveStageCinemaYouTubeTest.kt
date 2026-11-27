package dev.echo.app

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.data.provider.YouTubeProvider
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.runner.RunWith

/** Explicit opt-in live extraction and Stage Cinema frame check. Does not touch the library or queue. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class LiveStageCinemaYouTubeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun publicAudioExtractionPopulatesCinemaVideoCacheAndRendersFirstFrame() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e runLiveStageCinemaYouTube true",
            args.getString("runLiveStageCinemaYouTube") == "true")
        assumeTrue("Live media check is emulator-only",
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic"))

        val provider = YouTubeProvider()
        val track = runBlocking {
            provider.search("https://www.youtube.com/watch?v=aqz-KE-bpKQ").single()
        }
        val audio = runBlocking { provider.resolve(track) }
        assertNotNull("Public recording must expose an audio stream", audio)
        val video = provider.cachedVideo(track)
        assertNotNull("Successful audio extraction must populate the Cinema video cache", video)

        val firstFrameRendered = AtomicBoolean(false)
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    StageCinema(
                        track = track,
                        occurrenceKey = "live-stage-cinema",
                        audioPositionMs = 0,
                        audioPlaying = true,
                        resolveVideo = { _, _ -> video },
                        onVideoReady = { ready -> if (ready) firstFrameRendered.set(true) },
                    )
                }
            }
        }
        compose.waitUntil(90_000) { firstFrameRendered.get() }
        assertTrue("Stage Cinema must report its first rendered video frame", firstFrameRendered.get())
    }
}
