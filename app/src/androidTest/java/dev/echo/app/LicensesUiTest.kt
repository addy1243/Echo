package dev.echo.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import org.junit.Rule
import org.junit.Test

class LicensesUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun packagedLicenseGrantAndDependencyNoticesAreReachableOffline() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Licenses & source"))
            compose.onNodeWithText("Licenses & source").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("NOTICE-Echo.txt").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("NOTICE-Echo.txt").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("GNU General Public", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("License list").performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("runtime/DEPENDENCIES.txt"))
            compose.onNodeWithText("runtime/DEPENDENCIES.txt").assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("androidx", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
