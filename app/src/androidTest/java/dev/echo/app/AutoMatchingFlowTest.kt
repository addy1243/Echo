package dev.echo.app

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.data.library.LibraryRepository
import dev.echo.data.spotify.SpotifyPlaylistLinks
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AutoMatchingFlowTest {
    @Test fun liveBatchMatchesSampleAndPreservesDuplicateOccurrence() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with runLiveRecommendations and playlistLink", args.getString("runLiveRecommendations") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(context)
        val sample = SpotifyPlaylistLinks().preview(requireNotNull(args.getString("playlistLink"))).tracks.take(3)
        val id = "matching-test-${UUID.randomUUID()}"
        repository.importPlaylist(id, "Matching test", sample + sample.first())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: EchoViewModel
                scenario.onActivity { model = ViewModelProvider(it)[EchoViewModel::class.java]; model.matchPlaylistAutomatically(id) }
                val end = android.os.SystemClock.elapsedRealtime() + 90_000
                while (repository.playlistTracks(id).any { it.sourceUri == null } && android.os.SystemClock.elapsedRealtime() < end) {
                    kotlinx.coroutines.delay(100)
                }
                val matched = repository.playlistTracks(id)
                assertEquals(4, matched.size)
                assertTrue("All three clear sample recordings should auto-match", matched.all { it.sourceUri != null })
                assertEquals(matched.first().id, matched.last().id)
                assertTrue(repository.tracks.first().any { it.id == sample.first().id })
                scenario.onActivity { if (model.matchingProgress != null) model.cancelMatching() }
            }
        } finally { repository.deletePlaylist(id) }
        Unit
    }
}
