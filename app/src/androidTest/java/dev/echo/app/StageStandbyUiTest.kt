package dev.echo.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.VideoSize
import androidx.media3.common.Player
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import dev.echo.core.Track
import dev.echo.core.PlayableSource
import dev.echo.data.library.LibraryRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

class StageStandbyUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val standbyUiRules = RuleChain.outerRule(StandbyUiSafetyRule()).around(compose)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val fixtureId = "standby-ui-${UUID.randomUUID()}"
    private val fixtureArtist = "Standby UI fixture ${UUID.randomUUID()}"
    private var fixtureAudio: File? = null
    private var fixturePlaylistId: String? = null
    private var fixtureTrack: Track? = null
    private var fixtureVm: EchoViewModel? = null
    private fun dismissTutorialIfShown() {
        if (compose.onAllNodesWithTag("tutorial-skip").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("tutorial-skip").performClick()
        }
    }
    private fun expandStandby() {
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-section-standby"))
        compose.onNodeWithTag("settings-section-standby").performClick()
    }

    private fun startFixturePlayback() {
        val audio = File(context.filesDir, "$fixtureId.wav")
        fixtureAudio = audio
        val samples = 8_000 * 60
        val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        audio.writeBytes(wav.array())
        val song = Track(fixtureId, "Standby UI fixture", fixtureArtist, durationMs = 60_000,
            provider = "local", sourceUri = Uri.fromFile(audio).toString())
        fixtureTrack = song
        runBlocking {
            val library = LibraryRepository(context)
            library.upsertTracks(listOf(song))
            fixturePlaylistId = library.createPlaylist("$fixtureId playlist")
        }
        lateinit var vm: EchoViewModel
        compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[EchoViewModel::class.java] }
        fixtureVm = vm
        compose.waitUntil(15_000) { vm.controller != null }
        compose.activityRule.scenario.onActivity { vm.play(listOf(song), 0, false) }
        compose.waitUntil(15_000) { vm.playing && vm.current?.mediaId == fixtureId }
    }

    private fun cleanFixture() {
        val song = fixtureTrack ?: return
        fixtureVm?.let { vm ->
            compose.activityRule.scenario.onActivity {
                vm.controller?.let { player -> player.pause(); player.clearMediaItems(); player.repeatMode = Player.REPEAT_MODE_OFF }
            }
        }
        val libraryId = fixturePlaylistId
        SQLiteDatabase.openDatabase(context.getDatabasePath("echo-library.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            if (libraryId != null) db.delete("playlists", "id = ?", arrayOf(libraryId))
            db.delete("tracks", "id = ?", arrayOf(song.id))
            db.delete("artists", "name = ?", arrayOf(song.artist))
            db.delete("albums", "artist = ?", arrayOf(song.artist))
        }
        fixtureAudio?.delete()
        fixtureTrack = null
        fixtureVm = null
    }

    @After fun restoreFixtureState() = cleanFixture()

    @Test fun settingsSectionsExpandAndCollapse() {
        dismissTutorialIfShown()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("settings-section-standby").assertIsDisplayed()
        compose.onNodeWithTag("stage-standby-open").assertDoesNotExist()
        expandStandby()
        compose.onNodeWithTag("stage-standby-open").assertIsDisplayed()
        compose.onNodeWithTag("stage-standby-open").assertIsNotEnabled()
        compose.onNodeWithTag("stage-standby-unavailable").assertIsDisplayed()
        compose.onNodeWithTag("settings-section-standby").performClick()
        compose.onNodeWithTag("stage-standby-open").assertDoesNotExist()
    }

    @Test fun settingsStandbyEntryIsDisabledWithoutPlayback() {
        dismissTutorialIfShown()
        compose.onNodeWithContentDescription("Settings").performClick()
        expandStandby()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("stage-standby-open"))
        compose.onNodeWithTag("stage-standby-open").assertIsNotEnabled()
        compose.onNodeWithTag("stage-standby-unavailable").assertIsDisplayed()
    }

    @Test fun standbyReturnsToSettingsDuringFixturePlayback() {
        startFixturePlayback()
        dismissTutorialIfShown()
        compose.onNodeWithContentDescription("Settings").performClick()
        expandStandby()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("stage-standby-open"))
        compose.onNodeWithTag("stage-standby-open").assertIsEnabled().performClick()
        compose.onNodeWithTag("stage-standby-screen").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("stage-standby-open").assertIsEnabled()
    }

    @Test fun touchExitsStandbyDuringFixturePlayback() {
        startFixturePlayback()
        dismissTutorialIfShown()
        compose.onNodeWithContentDescription("Settings").performClick()
        expandStandby()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("stage-standby-open"))
        compose.onNodeWithTag("stage-standby-open").assertIsEnabled().performClick()
        compose.onNodeWithTag("stage-standby-screen").assertIsDisplayed()
        compose.onNodeWithText("Tap or press any key to exit").assertIsDisplayed()
        compose.onNodeWithTag("stage-standby-screen").performTouchInput { click() }
        compose.onNodeWithTag("stage-standby-screen").assertDoesNotExist()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test fun accessibilityCustomActionExitsStandbyDuringFixturePlayback() {
        startFixturePlayback()
        dismissTutorialIfShown()
        compose.onNodeWithContentDescription("Settings").performClick()
        expandStandby()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("stage-standby-open"))
        compose.onNodeWithTag("stage-standby-open").assertIsEnabled().performClick()
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

    @Test fun automaticStandbyDoesNotEnterWithoutActivePlayback() {
        val store = StageStandbyPreferences(context)
        val before = store.read()
        try {
            dismissTutorialIfShown()
            compose.onNodeWithContentDescription("Settings").performClick()
            expandStandby()
            if (before.chargingOnly) {
                compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("standby-charging"))
                compose.onNodeWithTag("standby-charging").performClick()
            }
            if (!before.automaticEnabled) {
                compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("standby-auto"))
                compose.onNodeWithTag("standby-auto").performClick()
            }
            val activity = compose.activity
            compose.waitUntil(10_000) { compose.runOnIdle { activity.hasWindowFocus() } }
            val foregroundProbe = StageStandbySettings(automaticEnabled = true, idleTimeoutMs = 1L)
            compose.activityRule.scenario.onActivity {
                // Simulate a resume callback that ran before the lifecycle reached RESUMED.
                it.standbyClock.setForeground(false, android.os.SystemClock.elapsedRealtime())
            }
            compose.waitUntil(10_000) {
                compose.runOnIdle {
                    val now = android.os.SystemClock.elapsedRealtime()
                    activity.standbyClock.shouldEnter(foregroundProbe, now, charging = null)
                }
            }
            compose.activityRule.scenario.onActivity {
                val now = android.os.SystemClock.elapsedRealtime()
                it.standbyClock.recordInteraction(now - 1_000_000L)
            }
            compose.mainClock.advanceTimeBy(6_000)
            compose.onNodeWithTag("stage-standby-screen").assertDoesNotExist()
            compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("stage-standby-open"))
            compose.onNodeWithTag("stage-standby-open").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithTag("stage-standby-unavailable").assertIsDisplayed()
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

    @Test fun cinemaFitsPortraitAndAnamorphicVideoUsingDecodedPixelAspect() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            Box(Modifier.size(200.dp, 100.dp).testTag("picture-pane"), contentAlignment = Alignment.Center) {
                Box(Modifier.stageCinemaVideoFit(VideoSize(300, 600)).testTag("portrait-video"))
                Box(Modifier.stageCinemaVideoFit(VideoSize(640, 480, 2f)).testTag("anamorphic-video"))
            }
        } }
        val pane = compose.onNodeWithTag("picture-pane").fetchSemanticsNode().boundsInRoot
        val portrait = compose.onNodeWithTag("portrait-video").fetchSemanticsNode().boundsInRoot
        assertEquals(pane.height, portrait.height, 1f)
        assertEquals(pane.height / 2, portrait.width, 1f)
        assertEquals(pane.center.x, portrait.center.x, 1f)

        val anamorphic = compose.onNodeWithTag("anamorphic-video").fetchSemanticsNode().boundsInRoot
        assertEquals(pane.width, anamorphic.width, 1f)
        assertEquals(pane.width * 3 / 8, anamorphic.height, 1f)
        assertEquals(pane.center.y, anamorphic.center.y, 1f)
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
