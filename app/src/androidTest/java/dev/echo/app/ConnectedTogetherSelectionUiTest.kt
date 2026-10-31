package dev.echo.app

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.MutableState
import androidx.test.core.app.ActivityScenario
import dev.echo.core.connected.EngineFence
import dev.echo.core.connected.EngineObservation
import dev.echo.data.firebase.ConnectedPlaybackState
import dev.echo.data.firebase.ConnectedPlaybackV2State
import dev.echo.data.firebase.ConnectedV2Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry

class ConnectedTogetherSelectionUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun capsEightTotalRejectsChangedSessionConfirmsPlayingAndRetainsSelectionAfterCommandFailure() {
        assumeTrue("Keep connected-player UI fixtures on an emulator",
            android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk", true))
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val accountPreferences = appContext.getSharedPreferences("google-account", 0)
        val connectedPreferences = appContext.getSharedPreferences("connected-playback", 0)
        val email = accountPreferences.getString("email", null)
        assumeTrue("Use the clean, signed-out emulator with automatic sync and connected playback off",
            email == null && !accountPreferences.getBoolean("firebase-sync:${email.orEmpty()}", false) &&
                !connectedPreferences.getBoolean("enabled", false))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: EchoViewModel
            var previousConnectedEnabled = false
            var previousConnectedState = ConnectedPlaybackState()
            var previousV2State = ConnectedPlaybackV2State()
            var previousMessage: String? = null
            var previousCurrent: MediaItem? = null
            var previousController: androidx.media3.session.MediaController? = null
            var previousTargetId: String? = null
            var previousTarget: dev.echo.data.firebase.ConnectedDevice? = null
            var capturedUiState = false
            val remotes = (1..8).map { index -> previewDevice(index, playing = index == 1) }

            scenario.onActivity { activity ->
                vm = ViewModelProvider(activity)[EchoViewModel::class.java]
            }
            compose.waitUntil(10_000) { vm.controller != null && !vm.accountBusy }
            scenario.onActivity {
                previousConnectedEnabled = vm.connectedPlaybackEnabled.value
                previousConnectedState = vm.connectedPlaybackState.value
                previousV2State = vm.connectedPlaybackV2State.value
                previousMessage = vm.message
                previousCurrent = vm.current
                previousController = vm.controller
                previousTargetId = vm.selectedConnectedPlaybackTargetId.value
                previousTarget = vm.selectedConnectedPlaybackDevice.value
                    ?: previousConnectedState.devices.firstOrNull { it.id == previousTargetId }
                assumeTrue("Do not interrupt an existing connected session", !previousConnectedEnabled &&
                    previousV2State.group == null && !previousV2State.pending)
                assumeTrue("Preserve the selected player snapshot", previousTargetId == null || previousTarget != null)
                vm.controller?.let { player ->
                    assumeTrue("Only seed an empty, paused player queue", player.mediaItemCount == 0 &&
                        !player.isPlaying && !player.playWhenReady)
                }
                assumeTrue("Do not interrupt an active sleep timer", vm.sleepTimerRemainingMs.value == null)
            }
            try {
                scenario.onActivity {
                    capturedUiState = true
                    setDelegatedValue(vm, "controller", null)
                    setDelegatedValue(vm, "current", MediaItem.Builder().setMediaId("connected-together-ui-fixture")
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("UI fixture queue").setArtist("No playback").build())
                        .build())
                    vm.connectedPlaybackState.value = ConnectedPlaybackState()
                    vm.connectedPlaybackV2State.value = ConnectedPlaybackV2State(devices = remotes)
                    vm.message = null
                    vm.selectConnectedPlaybackTarget(null)
                }
                compose.waitUntil(5_000) { vm.current != null }
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithTag("mini-player").assertIsDisplayed() }.isSuccess
                }
                compose.onNodeWithTag("mini-player").performClick()
                compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("player-screen").assertIsDisplayed() }.isSuccess }
                compose.onNodeWithTag("connected-together-action").performClick()
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithTag("connected-together-select-${remotes.first().id}").assertIsDisplayed() }.isSuccess
                }

                remotes.take(7).forEach { device ->
                    compose.onNodeWithTag("connected-together-select-${device.id}").performScrollTo().performClick()
                }
                compose.onNodeWithText("7 of 7 other players selected").assertIsDisplayed()
                compose.onNodeWithTag("connected-together-start").performScrollTo().assertIsEnabled()
                compose.onNodeWithTag("connected-together-select-${remotes[7].id}").performScrollTo().performClick()
                compose.onNodeWithTag("connected-together-select-${remotes[7].id}").assertIsOff()
                compose.onNodeWithText("7 of 7 other players selected").assertIsDisplayed()
                capture("connected-together-multiselect-7-of-7.png")

                val changed = remotes.first().copy(sessionId = "preview-group-session-1-restarted",
                    snapshot = remotes.first().snapshot.copy(fence = EngineFence("preview-group-session-1-restarted", 1, 1, 1)))
                scenario.onActivity {
                    vm.connectedPlaybackV2State.value = ConnectedPlaybackV2State(devices = listOf(changed) + remotes.drop(1))
                }
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText("Session changed · remove and select again").performScrollTo().assertIsDisplayed() }.isSuccess
                }
                compose.onNodeWithTag("connected-together-start").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithTag("connected-together-select-${changed.id}").performScrollTo().performClick()
                compose.onNodeWithText("6 of 7 other players selected").assertIsDisplayed()
                compose.onNodeWithTag("connected-together-start").performScrollTo().assertIsEnabled()
                compose.onNodeWithTag("connected-together-select-${changed.id}").performScrollTo().performClick()
                compose.onNodeWithText("7 of 7 other players selected").assertIsDisplayed()

                compose.onNodeWithTag("connected-together-start").performScrollTo().performClick()
                compose.onNodeWithText("Replace playing devices?").assertIsDisplayed()
                compose.onNodeWithText("Cancel").performClick()
                compose.onNodeWithText("7 of 7 other players selected").assertIsDisplayed()
                compose.onNodeWithTag("connected-together-start").performScrollTo().performClick()
                compose.onNodeWithTag("connected-together-confirm").performClick()
                compose.waitUntil(10_000) { vm.message != null }
                compose.onNodeWithText("7 of 7 other players selected").assertIsDisplayed()
                remotes.take(7).forEach { device ->
                    compose.onNodeWithTag("connected-together-select-${device.id}").performScrollTo().assertIsOn()
                }
                assertTrue(vm.connectedPlaybackV2State.value.group == null)
            } finally {
                scenario.onActivity {
                    if (capturedUiState) {
                        setDelegatedValue(vm, "current", previousCurrent)
                        setDelegatedValue(vm, "controller", previousController)
                    }
                    vm.connectedPlaybackState.value = previousConnectedState
                    vm.connectedPlaybackV2State.value = previousV2State
                    vm.selectConnectedPlaybackTarget(if (previousTargetId == null) null else previousTarget)
                    vm.message = previousMessage
                }
            }
            scenario.onActivity {
                assertSame(previousController, vm.controller)
                assertEquals(previousCurrent, vm.current)
                assertEquals(previousConnectedEnabled, vm.connectedPlaybackEnabled.value)
            }
        }
    }

    private fun previewDevice(index: Int, playing: Boolean): ConnectedV2Device {
        val id = "preview-group-device-$index"
        val sessionId = "preview-group-session-$index"
        val fence = EngineFence(sessionId, queueRevision = 1, occurrenceRevision = 1, transportRevision = 1)
        return ConnectedV2Device(id, "Preview device $index", sessionId, System.currentTimeMillis(), true,
            setOf("group"), EngineObservation(fence, null, 0, playing, true,
                recordingId = "youtube:abcdefghijk", title = "Preview song $index", artist = "Preview artist", durationMs = 180_000))
    }

    @Suppress("UNCHECKED_CAST")
    private fun setDelegatedValue(viewModel: EchoViewModel, property: String, value: Any?) {
        val field = EchoViewModel::class.java.getDeclaredField("$property\$delegate")
        field.isAccessible = true
        (field.get(viewModel) as MutableState<Any?>).value = value
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val outputDir = instrumentation.targetContext.getExternalFilesDir(null) ?: instrumentation.targetContext.filesDir
        File(outputDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
