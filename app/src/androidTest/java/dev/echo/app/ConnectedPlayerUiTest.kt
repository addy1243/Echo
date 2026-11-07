package dev.echo.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedPlaybackState
import dev.echo.data.firebase.ConnectedPlayerSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ConnectedPlayerUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun remoteCanBeSelectedFromIdlePlayerAndFreezesWhenPeerDisconnects() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: EchoViewModel
            var previousEnabled = false
            var previousState = ConnectedPlaybackState()
            var previousTargetId: String? = null
            var previousTarget: ConnectedDevice? = null
            var previousItems = emptyList<androidx.media3.common.MediaItem>()
            var previousIndex = 0
            var previousPosition = 0L
            var previousPlaying = false
            var capturedQueue = false
            val remote = ConnectedDevice(
                id = "preview-laptop", label = "My laptop", sessionId = "preview-session", updatedAt = System.currentTimeMillis(),
                player = ConnectedPlayerSnapshot("youtube:abcdefghijk", "Remote song", "Remote artist", true, 31_000, 180_000, "track-r1", "queue-r1"),
                controlEnabled = true,
            )
            val secondRemote = ConnectedDevice(
                id = "preview-phone", label = "My phone", sessionId = "phone-session", updatedAt = System.currentTimeMillis(),
                player = ConnectedPlayerSnapshot("youtube:lmnopqrstuv", "Second song", "Second artist", false, 0, 205_000, "track-r2", "queue-r2"),
                controlEnabled = true,
            )
            scenario.onActivity { activity ->
                vm = ViewModelProvider(activity)[EchoViewModel::class.java]
                assumeTrue("Keep player UI fixtures on an emulator", android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk", true))
                previousEnabled = vm.connectedPlaybackEnabled.value
                previousState = vm.connectedPlaybackState.value
                previousTargetId = vm.selectedConnectedPlaybackTargetId.value
                previousTarget = vm.selectedConnectedPlaybackDevice.value
                    ?: previousState.devices.firstOrNull { it.id == previousTargetId }
                assumeTrue("Preserve an existing selected device snapshot", previousTargetId == null || previousTarget != null)
            }
            try {
                scenario.onActivity {
                    vm.setConnectedPlaybackEnabled(false)
                    vm.stopConnectedPlayback()
                }
                compose.waitUntil(5_000) { vm.connectedPlaybackState.value.devices.isEmpty() && vm.connectedPlaybackState.value.message == "Connected playback stopped." }
                compose.waitUntil(5_000) { vm.controller != null && !vm.accountBusy }
                scenario.onActivity {
                    requireNotNull(vm.controller).let { player ->
                        previousItems = List(player.mediaItemCount) { player.getMediaItemAt(it) }
                        previousIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                        previousPosition = player.currentPosition.coerceAtLeast(0)
                        previousPlaying = player.playWhenReady
                        capturedQueue = true
                        player.pause()
                        player.clearMediaItems()
                    }
                    vm.connectedPlaybackState.value = ConnectedPlaybackState(devices = listOf(remote, secondRemote))
                    vm.selectConnectedPlaybackTarget(null)
                }
                compose.waitUntil(5_000) { vm.current == null }
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithContentDescription("Connected player controls").assertIsDisplayed() }.isSuccess
                }
                compose.onNodeWithContentDescription("Connected player controls").performClick()
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithTag("player-screen").assertIsDisplayed() }.isSuccess
                }
                compose.onNodeWithTag("player-track-title").assertTextContains("Choose a song")
                compose.onNodeWithTag("connected-devices-action").performClick()
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithTag("connected-device-${remote.id}").assertIsDisplayed() }.isSuccess
                }
                compose.onNodeWithTag("connected-device-${remote.id}").performClick()
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithTag("player-track-title").assertTextContains("Remote song") }.isSuccess
                }
                scenario.onActivity {
                    assertTrue(vm.canControlConnectedDevice(remote))
                    vm.selectConnectedPlaybackTarget(null)
                    assertFalse(vm.canControlConnectedDevice(remote))
                    vm.selectConnectedPlaybackTarget(remote)
                    assertTrue(vm.canControlConnectedDevice(remote))
                }
                compose.onNodeWithTag("player-track-title").assertTextContains("Remote song")
                compose.onNodeWithTag("player-output-label").assertTextContains("PLAYING ON MY LAPTOP")
                compose.onNodeWithTag("connected-full-controls").assertIsDisplayed()
                compose.onNodeWithTag("connected-player-status").assertTextContains("Playing on My laptop")
                compose.onNodeWithTag("connected-devices-action").assertExists()
                compose.onNodeWithTag("connected-move-action").assertExists()
                compose.onNodeWithTag("connected-together-action").assertExists()
                compose.onNodeWithContentDescription("Pause on My laptop").assertIsDisplayed()
                compose.onNodeWithContentDescription("Song position").assertIsDisplayed()
                capture("unified-player-full.png")

                scenario.onActivity {
                    vm.connectedPlaybackState.value = ConnectedPlaybackState()
                    assertFalse(vm.canControlConnectedDevice(remote))
                }
                compose.waitUntil(5_000) {
                    runCatching {
                        compose.onNodeWithTag("connected-player-status").assertTextContains("Reconnecting to My laptop")
                        true
                    }.getOrDefault(false)
                }
                compose.onNodeWithTag("player-track-title").assertTextContains("Remote song")
                compose.onNodeWithContentDescription("Pause on My laptop").assertIsNotEnabled()

                scenario.onActivity { vm.connectedPlaybackState.value = ConnectedPlaybackState(devices = listOf(remote, secondRemote)) }
                compose.onNodeWithContentDescription("Close player").performScrollTo().performClick()
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithTag("mini-player").assertIsDisplayed() }.isSuccess
                }
                compose.onNodeWithContentDescription("My laptop, Remote song, Remote artist, Playing, player 2 of 3").assertIsDisplayed()
                capture("unified-player-mini-before.png")
                compose.onNodeWithContentDescription("Previous track on My laptop").assertIsDisplayed()
                compose.onNodeWithContentDescription("Pause on My laptop").assertIsDisplayed()
                compose.onNodeWithContentDescription("Next track on My laptop").assertIsDisplayed()
                compose.onNodeWithContentDescription("Song position").assertIsDisplayed()
                compose.onNodeWithTag("mini-player-next-player").assertIsDisplayed()
                compose.onNodeWithContentDescription("Next player: My phone, Second song, Second artist, Paused, player 3 of 3").performClick()
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithContentDescription("My phone, Second song, Second artist, Paused, player 3 of 3").assertIsDisplayed() }.isSuccess
                }
                compose.onNodeWithTag("mini-player").performTouchInput { swipeLeft() }
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithContentDescription("This phone, Choose a song, Ready to play, player 1 of 3").assertIsDisplayed() }.isSuccess
                }
                compose.onNodeWithTag("mini-player").performTouchInput { swipeRight() }
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithContentDescription("My phone, Second song, Second artist, Paused, player 3 of 3").assertIsDisplayed() }.isSuccess
                }
                compose.onNodeWithTag("mini-player").performClick()
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithTag("player-screen").assertIsDisplayed() }.isSuccess
                }
                compose.onNodeWithContentDescription("Close player").performScrollTo().performClick()
                scenario.onActivity { vm.selectConnectedPlaybackTarget(remote) }
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithContentDescription("My laptop, Remote song, Remote artist, Playing, player 2 of 3").assertIsDisplayed() }.isSuccess
                }
                capture("unified-player-mini.png")

            } finally {
                scenario.onActivity {
                    if (capturedQueue) {
                        vm.controller?.apply {
                            pause(); clearMediaItems()
                            if (previousItems.isNotEmpty()) {
                                setMediaItems(previousItems, previousIndex, previousPosition)
                                prepare()
                                playWhenReady = previousPlaying
                            }
                        }
                    }
                    vm.connectedPlaybackState.value = previousState
                    vm.selectConnectedPlaybackTarget(if (previousTargetId == null) null else previousTarget)
                    vm.setConnectedPlaybackEnabled(previousEnabled)
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val outputDir = instrumentation.targetContext.getExternalFilesDir(null) ?: instrumentation.targetContext.filesDir
        File(outputDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
