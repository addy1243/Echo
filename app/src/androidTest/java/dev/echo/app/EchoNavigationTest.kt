package dev.echo.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test

class EchoNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun libraryAndSearchRemainAccessibleWithoutAccount() {
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Your collection").assertIsDisplayed()
        compose.onNodeWithText("Import Spotify playlist file").assertIsDisplayed()
        compose.onNodeWithText("New playlist").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Search", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Find your next favorite").assertIsDisplayed()
        compose.onNodeWithText("Search YouTube").assertIsDisplayed()
        compose.onNodeWithText("No API key needed", substring = true).assertIsDisplayed()
        compose.onNodeWithText("YouTube Data API key").assertDoesNotExist()
        compose.onNodeWithText("Licensed audio").performClick()
        compose.onNodeWithText("Search licensed audio").assertIsDisplayed()
    }
}
