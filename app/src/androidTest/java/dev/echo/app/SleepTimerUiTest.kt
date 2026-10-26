package dev.echo.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class SleepTimerUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun durationsStartCountdownAndActiveTimerCanBeReplacedAndCancelled() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: EchoViewModel
            scenario.onActivity {
                model = ViewModelProvider(it)[EchoViewModel::class.java]
                assumeTrue("Do not replace an existing timer", model.sleepTimerRemainingMs.value == null)
                it.setContent { MaterialTheme { SleepTimerControls(model) } }
            }
            try {
                compose.waitUntil(10_000) { model.controller != null }
                compose.onNodeWithTag("sleep-timer-button").performClick()
                listOf(15, 30, 45, 60).forEach { compose.onNodeWithText("$it minutes").assertIsDisplayed() }
                compose.onNodeWithText("30 minutes").performClick()
                compose.waitUntil(5_000) { model.sleepTimerRemainingMs.value?.let { it in 1L..1_800_000L } == true }
                compose.onNodeWithTag("sleep-timer-button").assertTextContains("Sleep timer ·", substring = true).performClick()
                compose.onNodeWithText("45 minutes").performClick()
                compose.waitUntil(5_000) { (model.sleepTimerRemainingMs.value ?: 0) > 2_600_000 }
                compose.onNodeWithTag("sleep-timer-button").performClick()
                compose.onNodeWithText("Cancel timer").performClick()
                compose.waitUntil(5_000) { model.sleepTimerRemainingMs.value == null }
                compose.onNodeWithTag("sleep-timer-button").assertTextEquals("Sleep timer")
            } finally { scenario.onActivity { model.cancelSleepTimer() } }
        }
    }
}
