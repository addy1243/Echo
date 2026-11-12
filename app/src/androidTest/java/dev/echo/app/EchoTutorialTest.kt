package dev.echo.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

class EchoTutorialTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nextWalksThroughAllFivePagesAndEndsAtGetStarted() {
        compose.activity.setContent {
            val page = remember { mutableIntStateOf(0) }
            MaterialTheme { EchoTutorial(page.intValue, onNext = { if (page.intValue < 4) page.intValue++ }, onSkip = {}) }
        }
        compose.onNodeWithTag("tutorial-github").assertIsDisplayed()
        repeat(4) { compose.onNodeWithTag("tutorial-next").performClick() }
        compose.onNodeWithText("Ready when you are").assertIsDisplayed()
        compose.onNodeWithText("Get started").assertIsDisplayed()
    }

    @Test fun skipIsAnExplicitAction() {
        var skipped = false
        compose.activity.setContent {
            MaterialTheme { EchoTutorial(page = 2, onNext = {}, onSkip = { skipped = true }) }
        }
        compose.onNodeWithTag("tutorial-skip").performClick()
        compose.runOnIdle { assertTrue(skipped) }
    }
}
