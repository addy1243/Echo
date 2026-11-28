package dev.echo.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test

class StandbyProductionUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val standbyUiRules = RuleChain.outerRule(StandbyUiSafetyRule()).around(compose)

    @Test fun normalBuildShowsStandbyDisabledWithoutActivePlayback() {
        assumeFalse("This check targets the normal debug build", BuildConfig.STAGING)
        if (compose.onAllNodesWithTag("tutorial-skip").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("tutorial-skip").performClick()
        }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-section-standby"))
        compose.onNodeWithTag("settings-section-standby").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("stage-standby-open"))
        compose.onNodeWithTag("stage-standby-open").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("stage-standby-unavailable").assertIsDisplayed()
    }
}
