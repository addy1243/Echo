package dev.echo.app

import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.core.Track
import dev.echo.data.library.LibraryRepository
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

class HomeHistoryRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureId = "echo-home-history-${UUID.randomUUID()}"

    @Test fun persistedRecentTrackCanAlsoAppearInLibraryAcrossActivityRecreation() {
        val repository = LibraryRepository(context)
        val title = runBlocking {
            // Home reverses the final twelve library rows, so this fixture is its first row.
            val lastTitle = repository.tracks.first().lastOrNull()?.title.orEmpty()
            val track = Track(fixtureId, "$lastTitle Echo history regression $fixtureId", fixtureId, album = fixtureId)
            repository.upsertTracks(listOf(track))
            repository.recordPlayed(track.id)
            track.title
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertBothHomeSectionsRender(title)
            scenario.recreate()
            assertBothHomeSectionsRender(title)
        }
    }

    private fun assertBothHomeSectionsRender(title: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("On repeat").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("home-feed").performScrollToNode(hasText("On repeat"))
        compose.onNodeWithText("On repeat").assertIsDisplayed()
        compose.onAllNodesWithText(title).onFirst().assertIsDisplayed()
        compose.onNodeWithTag("home-feed").performScrollToNode(hasText("From your library"))
        compose.onNodeWithText("From your library").assertIsDisplayed()
        compose.onAllNodesWithText(title).onLast().assertIsDisplayed()
        compose.waitForIdle()
    }

    @After fun removeOnlyOwnedHistoryFixture() {
        if (!context.getDatabasePath("echo-library.db").exists()) return
        SQLiteDatabase.openDatabase(context.getDatabasePath("echo-library.db").path, null,
            SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.setForeignKeyConstraintsEnabled(true)
            database.delete("tracks", "id = ?", arrayOf(fixtureId))
            database.delete("albums", "name = ? AND artist = ?", arrayOf(fixtureId, fixtureId))
            database.delete("artists", "name = ?", arrayOf(fixtureId))
        }
    }
}
