package dev.echo.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class HomeLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeShowsMixesThenPlaylistsAndNewMusicOpensItsTab() {
        compose.onNodeWithText("Made for today").assertIsDisplayed()
        compose.onNodeWithTag("home-feed").performScrollToNode(hasText("Echo discovery"))
        compose.onNodeWithText("Echo discovery").assertIsDisplayed()
        compose.onNodeWithTag("home-feed").performScrollToNode(hasText("Your playlists"))
        compose.onNodeWithText("Your playlists").assertIsDisplayed()
        compose.onNodeWithTag("home-feed").performScrollToNode(hasText("Echo discovery"))
        compose.onNodeWithTag("home-mixes").performScrollToNode(hasText("New music"))
        compose.onNodeWithText("New music").performClick()
        compose.onNodeWithText("Discover with Echo").assertIsDisplayed()
        compose.onNodeWithText("New music").assertIsSelected()
    }
}
