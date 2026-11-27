package dev.echo.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.semantics.SemanticsActions
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import androidx.lifecycle.Lifecycle
import dev.echo.core.Track
import dev.echo.core.PlayableSource
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

class StageStandbyUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val standbyUiRules = RuleChain.outerRule(StandbyUiSafetyRule()).around(compose)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun dismissTutorialIfShown() {
        if (compose.onAllNodesWithTag("tutorial-skip").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("tutorial-skip").performClick()
        }
    }

    @Test fun settingsStandbyReturnsToSameScreen() {
        dismissTutorialIfShown()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("stage-standby-open"))
        compose.onNodeWithTag("stage-standby-open").performClick()
        compose.onNodeWithTag("stage-standby-screen").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.standbyClock.recordInteraction(android.os.SystemClock.elapsedRealtime() - 600_000) }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.activityRule.scenario.onActivity { assertTrue(it.standbyClock.idleFor(android.os.SystemClock.elapsedRealtime()) < 5_000) }
        compose.onNodeWithTag("stage-standby-open").assertIsDisplayed()
    }

    @Test fun touchExitsStandbyAndPausedPlaybackRemainsPaused() {
        dismissTutorialIfShown()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("stage-standby-open"))
        compose.onNodeWithTag("stage-standby-open").performClick()
        compose.onNodeWithTag("stage-standby-screen").assertIsDisplayed()
        compose.onNodeWithText("Playback is paused · tap or press any key to exit").assertIsDisplayed()
        compose.onNodeWithTag("stage-standby-screen").performTouchInput { click() }
        compose.onNodeWithTag("stage-standby-screen").assertDoesNotExist()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test fun accessibilityCustomActionExitsStandbyAndResetsIdleClock() {
        dismissTutorialIfShown()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("stage-standby-open"))
        compose.onNodeWithTag("stage-standby-open").performClick()
        val actions = compose.onNodeWithTag("stage-standby-screen").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single { it.label == "Exit Standby" }.action()) }
        compose.onNodeWithTag("stage-standby-screen").assertDoesNotExist()
        compose.activityRule.scenario.onActivity {
            assertTrue(it.standbyClock.idleFor(android.os.SystemClock.elapsedRealtime()) < 5_000)
        }
    }

    @Test fun stageStandbyPreferencesRoundTripAndRestore() {
        val store = StageStandbyPreferences(context)
        val before = store.read()
        try {
            val selected = StageStandbySettings(
                automaticEnabled = true,
                chargingOnly = true,
                idleTimeoutMs = 60_000L,
                cinema = false,
            )
            store.update(selected)
            assertEquals(selected, store.read())
        } finally {
            store.update(before)
        }
        assertEquals(before, store.read())
    }

    @Test fun automaticStandbyEntersOnIdleAndExitResetsClock() {
        val store = StageStandbyPreferences(context)
        val before = store.read()
        try {
            dismissTutorialIfShown()
            compose.onNodeWithContentDescription("Settings").performClick()
            if (before.chargingOnly) {
                compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("standby-charging"))
                compose.onNodeWithTag("standby-charging").performClick()
            }
            if (!before.automaticEnabled) {
                compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("standby-auto"))
                compose.onNodeWithTag("standby-auto").performClick()
            }
            compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
            compose.activityRule.scenario.onActivity {
                val now = android.os.SystemClock.elapsedRealtime()
                it.standbyClock.setForeground(true, now)
                it.standbyClock.recordInteraction(now - 1_000_000L)
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("stage-standby-screen").fetchSemanticsNodes().isNotEmpty() }
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("stage-standby-open").assertIsDisplayed()
            compose.activityRule.scenario.onActivity { assertTrue(it.standbyClock.idleFor(android.os.SystemClock.elapsedRealtime()) < 5_000) }
        } finally { store.update(before) }
    }

    @Test fun standbyPolicyRequiresOptInForegroundIdleAndChargingWhenSelected() {
        val settings = StageStandbySettings(automaticEnabled = true, idleTimeoutMs = 10_000L)
        assertFalse(shouldEnterStageStandby(settings.copy(automaticEnabled = false), 20_000L, true, true))
        assertFalse(shouldEnterStageStandby(settings, 10_000L, false, true))
        assertFalse(shouldEnterStageStandby(settings, 9_999L, true, true))
        assertTrue(shouldEnterStageStandby(settings, 10_000L, true, false))
        val chargingOnly = settings.copy(chargingOnly = true)
        assertFalse(shouldEnterStageStandby(chargingOnly, 10_000L, true, null))
        assertFalse(shouldEnterStageStandby(chargingOnly, 10_000L, true, false))
        assertTrue(shouldEnterStageStandby(chargingOnly, 10_000L, true, true))
    }

    @Test fun emulatorChargingBroadcastFeedsStandbyPolicy() {
        val argument = InstrumentationRegistry.getArguments().getString("expectedCharging")
        assumeTrue("Run explicitly with an emulator charging-state argument", argument == "true" || argument == "false")
        val charging = readStageChargingState(context)
        assertEquals(argument.toBoolean(), charging)
        val settings = StageStandbySettings(automaticEnabled = true, chargingOnly = true, idleTimeoutMs = 1L)
        assertEquals(argument.toBoolean(), shouldEnterStageStandby(settings, 1L, true, charging))
    }

    @Test fun idleClockRestartsAtInteractionAndForegroundTransitions() {
        val clock = StageStandbyIdleClock(100L)
        val settings = StageStandbySettings(automaticEnabled = true, idleTimeoutMs = 50L)
        clock.setForeground(true, 100L)
        assertFalse(clock.shouldEnter(settings, 149L, true))
        assertTrue(clock.shouldEnter(settings, 150L, true))
        clock.recordInteraction(160L)
        assertFalse(clock.shouldEnter(settings, 209L, true))
        clock.setForeground(false, 210L)
        clock.setForeground(true, 300L)
        assertFalse(clock.shouldEnter(settings, 349L, true))
        assertTrue(clock.shouldEnter(settings, 350L, true))
        assertEquals(0L, clock.idleFor(299L))
    }

    @Test fun activityPauseAndResumeResetForegroundIdleWindow() {
        val settings = StageStandbySettings(automaticEnabled = true, idleTimeoutMs = 10_000L)
        compose.activityRule.scenario.onActivity { activity ->
            val old = android.os.SystemClock.elapsedRealtime() - 60_000L
            activity.standbyClock.recordInteraction(old)
            activity.standbyClock.setForeground(true, old)
            assertTrue(activity.standbyClock.shouldEnter(settings, android.os.SystemClock.elapsedRealtime(), true))
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.onActivity { activity ->
            val now = android.os.SystemClock.elapsedRealtime()
            assertFalse(activity.standbyClock.shouldEnter(settings, now, true))
            assertTrue(activity.standbyClock.idleFor(now) < 5_000L)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.activityRule.scenario.onActivity { activity ->
            val now = android.os.SystemClock.elapsedRealtime()
            assertFalse(activity.standbyClock.shouldEnter(settings, now, true))
            assertTrue(activity.standbyClock.idleFor(now) < 5_000L)
        }
        compose.activityRule.scenario.onActivity { activity ->
            val old = android.os.SystemClock.elapsedRealtime() - 60_000L
            activity.standbyClock.recordInteraction(old)
            activity.standbyClock.setForeground(true, old)
            assertTrue(activity.standbyClock.shouldEnter(settings, android.os.SystemClock.elapsedRealtime(), true))
            activity.onWindowFocusChanged(false)
            var now = android.os.SystemClock.elapsedRealtime()
            assertFalse(activity.standbyClock.shouldEnter(settings, now, true))
            assertTrue(activity.standbyClock.idleFor(now) < 5_000L)
            activity.onWindowFocusChanged(true)
            now = android.os.SystemClock.elapsedRealtime()
            assertFalse(activity.standbyClock.shouldEnter(settings, now, true))
        }
    }

    @Test fun ineligibleStageTrackUsesAmbientWithoutResolvingVideo() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme {
                StageCinema(
                    track = Track("stage:fixture", "Ambient fallback fixture", "Echo Stage", provider = "stage"),
                    occurrenceKey = "fixture-occurrence",
                    audioPositionMs = 12_000L,
                    audioPlaying = true,
                    resolveVideo = { _, _ -> error("Stage demo audio must not trigger video resolution") },
                )
            }
        } }
        compose.onNodeWithText("Ambient fallback fixture").assertIsDisplayed()
        compose.onNodeWithText("Echo Stage").assertIsDisplayed()
    }

    @Test fun cinemaRendersARealVideoFrameFromTestSource() {
        val frameReady = AtomicBoolean(false)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme {
                StageCinema(
                    track = Track("youtube:abcdefghijk", "Video fixture", "Test", provider = "youtube"),
                    occurrenceKey = "video-occurrence", audioPositionMs = 0, audioPlaying = true,
                    resolveVideo = { _, _ -> PlayableSource("https://storage.googleapis.com/exoplayer-test-media-1/mp4/android-screens-10s.mp4", mimeType = "video/mp4") },
                    onVideoReady = { frameReady.set(it) },
                )
            }
        } }
        compose.waitUntil(30_000) { frameReady.get() }
        assertTrue(frameReady.get())
    }
}
