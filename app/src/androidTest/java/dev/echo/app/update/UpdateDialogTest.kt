package dev.echo.app.update

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.echo.app.MainActivity
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UpdateDialogTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun availableDownloadCancelAndReadyStatesExposeTheCorrectActions() {
        val release = UpdateRelease(3, "Test version", "", 1024 * 1024, "", "Test release notes")
        val status = mutableStateOf<UpdateStatus>(UpdateStatus.Available(release))
        var action = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent {
                MaterialTheme { UpdateDialog(status.value, { action = "download" }, { action = "cancel" }, { action = "dismiss" }, { action = "install" }, { action = "check" }) }
            }
        }
        compose.onNodeWithText("Test release notes").assertIsDisplayed()
        compose.onNodeWithText("Update app").performClick()
        compose.runOnIdle { assertEquals("download", action); status.value = UpdateStatus.Downloading(release, 50) }
        compose.onNodeWithText("50% · downloading in the background").assertIsDisplayed()
        compose.onNodeWithText("Hide").performClick()
        compose.runOnIdle { assertEquals("dismiss", action) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals("cancel", action); status.value = UpdateStatus.Ready(release) }
        compose.onNodeWithText("Install update").performClick()
        compose.runOnIdle { assertEquals("install", action); status.value = UpdateStatus.Failed("No connection") }
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals("check", action) }
    }
}
