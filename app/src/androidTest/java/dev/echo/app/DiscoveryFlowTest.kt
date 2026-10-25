package dev.echo.app

import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.data.library.LibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class DiscoveryFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "Import check ${UUID.randomUUID()}"
    private var playlistId: String? = null
    private val trackId = "spotify:1234567890123456789012"
    private var insertedTrack = false

    @Test fun fileImportRendersDuplicateOccurrencesWithRecordingChoice() {
        val repository = LibraryRepository(context)
        runBlocking {
            insertedTrack = repository.tracks.first().none { it.id == trackId }
            val csv = "Track URI,Track Name,Artist Name(s)\nspotify:track:1234567890123456789012,$name,Fixture Artist\nspotify:track:1234567890123456789012,$name,Fixture Artist"
            repository.importPlaylistFile(csv, "$name.csv")
            playlistId = repository.playlists.first().single { it.name == name }.id
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Import Spotify playlist file").assertIsDisplayed()
            compose.onNodeWithText("Playlists", useUnmergedTree = true).performClick()
            compose.onNodeWithText(name).performClick()
            compose.onNodeWithText("2 tracks", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Smart shuffle · Echo").assertIsNotEnabled()
            compose.onNodeWithTag("playlist-feed").performScrollToNode(hasText("Find recording"))
            compose.onAllNodesWithText("Find recording").onFirst().assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithTag("playlist-feed").performScrollToNode(hasText("Find recording"))
            compose.onAllNodesWithText("Find recording").onFirst().assertIsDisplayed()
        }
    }

    @Test fun liveDiscoveryRefreshAndSmartShuffleProduceAQueue() {
        assumeTrue("Opt in with -e runLiveDiscovery true", InstrumentationRegistry.getArguments().getString("runLiveDiscovery") == "true")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: EchoViewModel
            scenario.onActivity { model = ViewModelProvider(it)[EchoViewModel::class.java] }
            compose.onAllNodesWithText("See all").onFirst().performClick()
            compose.onNodeWithText("Discover with Echo").assertIsDisplayed()
            compose.waitUntil(90_000) { !model.discoveryLoading && model.newMusicResults.isNotEmpty() }
            assertTrue(model.newMusicResults.all { it.provider == "youtube" && it.sourceUri != null })
            scenario.onActivity { model.saveDiscovery() }
            compose.waitUntil(10_000) { !model.busy }
            val seeds = model.newMusicResults.take(3)
            scenario.onActivity { model.smartShuffle(seeds) }
            compose.waitUntil(90_000) {
                var ready = false
                scenario.onActivity { ready = !model.busy && (model.controller?.mediaItemCount ?: 0) > seeds.size }
                ready
            }
            scenario.onActivity {
                assertFalse(model.controller!!.shuffleModeEnabled)
                model.controller!!.pause()
            }
        }
    }

    @After fun removeOnlyImportFixture() {
        playlistId?.let { id -> runBlocking { LibraryRepository(context).deletePlaylist(id) } }
        if (insertedTrack) SQLiteDatabase.openDatabase(context.getDatabasePath("echo-library.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.setForeignKeyConstraintsEnabled(true)
            it.delete("tracks", "id = ?", arrayOf(trackId))
        }
    }
}
