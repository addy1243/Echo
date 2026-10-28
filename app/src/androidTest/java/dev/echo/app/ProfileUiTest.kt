package dev.echo.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class ProfileUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun profileTabExposesAccountPlaylistsAndHonestDeviceStatus() {
        compose.onNodeWithTag("tab-profile").performClick()
        compose.onNodeWithTag("profile-screen").assertIsDisplayed()
        compose.onNodeWithTag("profile-screen").performScrollToNode(hasText("Your playlists"))
        compose.onNodeWithText("Your playlists").assertIsDisplayed()
        compose.onNodeWithTag("profile-screen").performScrollToNode(hasText("Account sync"))
        compose.onNodeWithText("Account sync").assertIsDisplayed()
        compose.onNodeWithTag("profile-screen").performScrollToNode(hasText("Connected playback"))
        compose.onNodeWithText("Connected playback").assertIsDisplayed()
        compose.onNodeWithTag("connected-playback-toggle").assertExists()
        compose.onNodeWithTag("profile-screen").performScrollToNode(hasText("A saved library does not mean", substring = true))
        compose.onNodeWithText("A saved library does not mean", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("tab-home").performClick()
        compose.onNodeWithTag("tab-profile").performClick()
        compose.onNodeWithText("Your profile").assertIsDisplayed()
        compose.activityRule.scenario.onActivity {
            // Keep launch filters so ActivityScenario still tracks destruction after setIntent.
            it.startActivity(android.content.Intent(it.intent).setClass(it, MainActivity::class.java).putExtra("echoDownloads", true).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("downloads-screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("downloads-screen").assertIsDisplayed()
    }
}
