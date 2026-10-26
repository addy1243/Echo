package dev.echo.app

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.core.Track
import dev.echo.data.library.LibraryRepository
import dev.echo.data.library.ListeningEventType
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Clear-history deliberately changes history, so this regression runs only on a dedicated emulator. */
class HistoryOfflineUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = "echo-history-ui-${UUID.randomUUID()}"
    private var seeded = false

    @Test fun persistedHistorySurvivesRecreationClearRequiresConfirmationAndDownloadsAreReachable() {
        assumeTrue("History-clearing test requires an emulator", Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"))
        val repository = LibraryRepository(context)
        runBlocking {
            repository.upsertTracks(listOf(Track(fixture, "History fixture $fixture", fixture, album = fixture)))
            repository.recordListeningEvent(fixture, ListeningEventType.PLAYED)
            repository.recordListeningEvent(fixture, ListeningEventType.SKIPPED, 7000, 180_000)
            seeded = true
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithTag("tab-profile").performClick()
            compose.onNodeWithTag("profile-screen").performScrollToNode(hasText("Sign in with Google"))
            compose.onNodeWithText("Sign in with Google").assertIsEnabled()
            compose.onAllNodesWithText("Google sign-in setup is pending.", substring = true).assertCountEquals(0)
            compose.onNodeWithContentDescription("Settings").performClick()
            scrollSettingsTo("Listening history")
            compose.onNodeWithText("Listening history").performClick()
            waitForFixture()
            compose.onNodeWithText("Skipped · 7s listened").assertIsDisplayed()
            // Metadata-only imported songs must not offer an unusable Play action.
            compose.onAllNodesWithContentDescription("Play History fixture $fixture").onFirst().assertIsNotEnabled()
            scenario.recreate()
            waitForFixture()
            compose.onNodeWithText("Clear history").performClick()
            compose.onNodeWithText("Clear listening history?").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertTrue(runBlocking { repository.listeningHistory.first().any { it.track.id == fixture } })
            compose.onNodeWithText("Clear history").performClick()
            compose.onNodeWithText("Clear").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Play a song to begin your listening history.").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(runBlocking { repository.listeningHistory.first().isEmpty() })
            compose.onNodeWithContentDescription("Back").performClick()
            scrollSettingsTo("Downloads & cache")
            compose.onNodeWithText("Downloads & cache").performClick()
            compose.onNodeWithTag("downloads-screen").assertIsDisplayed()
            compose.onNodeWithText("Downloads").assertIsDisplayed()
            compose.onNodeWithTag("downloads-screen").performScrollToNode(hasText("Offline mode"))
            compose.onNodeWithTag("offline-mode").assertIsDisplayed()
            compose.onNodeWithTag("downloads-screen").performScrollToNode(hasText("Cache frequently played songs"))
            compose.onNodeWithTag("automatic-cache").assertIsDisplayed()
        }
    }

    private fun scrollSettingsTo(label: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
    }

    private fun waitForFixture() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("History fixture $fixture").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun removeOnlyOwnedFixture() {
        if (!seeded || !context.getDatabasePath("echo-library.db").exists()) return
        SQLiteDatabase.openDatabase(context.getDatabasePath("echo-library.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.setForeignKeyConstraintsEnabled(true)
            it.delete("tracks", "id = ?", arrayOf(fixture))
            it.delete("albums", "name = ? AND artist = ?", arrayOf(fixture, fixture))
            it.delete("artists", "name = ?", arrayOf(fixture))
        }
    }
}
