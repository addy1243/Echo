package dev.echo.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.data.library.LibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class SpotifyLinkImportTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun invalidLinkShowsInlineErrorAndKeepsFileImport() {
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Import Spotify link").performClick()
        compose.onNodeWithText("Playlist share link").performTextInput("https://example.com/playlist/fake")
        compose.onNodeWithText("Preview playlist").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Paste a full https://open.spotify.com/playlist/", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Import Spotify playlist file").assertIsEnabled()
    }

    @Test fun liveLinkPreviewsCountAndImportsIntoPlaylistScreen() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with runLiveSpotifyLink and playlistLink", args.getString("runLiveSpotifyLink") == "true")
        val count = requireNotNull(args.getString("expectedCount")).toInt()
        val repository = LibraryRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val before = runBlocking { repository.playlists.first().map { it.id }.toSet() }
        try {
            compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Import Spotify link").performClick()
            compose.onNodeWithText("Playlist share link").performTextInput(requireNotNull(args.getString("playlistLink")))
            compose.onNodeWithText("Preview playlist").performClick()
            compose.waitUntil(90_000) { compose.onAllNodesWithText("Import $count tracks").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("$count of $count listed tracks available.").assertIsDisplayed()
            compose.onNodeWithText("Auto-match clear recordings").performClick()
            compose.onNodeWithText("Import $count tracks").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Import $count tracks").fetchSemanticsNodes().isEmpty() }
            val created = runBlocking { repository.playlists.first().filter { it.id !in before } }
            assertEquals(1, created.size)
            val songs = runBlocking { repository.playlistTracks(created.single().id) }
            assertEquals(count, songs.size)
            assertTrue(songs.all { it.provider == "spotify" && it.sourceUri == null })
            compose.waitForIdle()
            compose.onNodeWithText(created.single().name).performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("$count tracks", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.onNodeWithText("$count tracks", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("playlist-feed").performScrollToNode(hasText("Find recording"))
            compose.onAllNodesWithText("Find recording").onFirst().assertIsDisplayed()
        } catch (failure: Throwable) {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(target.getExternalFilesDir(null), "link-import-failure.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            throw failure
        } finally {
            runBlocking { repository.playlists.first().filter { it.id !in before }.forEach { repository.deletePlaylist(it.id) } }
        }
    }
}
