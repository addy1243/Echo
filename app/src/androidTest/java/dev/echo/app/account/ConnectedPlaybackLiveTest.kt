package dev.echo.app.account

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.app.EchoViewModel
import dev.echo.app.MainActivity
import dev.echo.core.Track
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedPlaybackAction
import dev.echo.data.library.LibraryRepository
import dev.echo.playback.toMediaItem
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in emulator-only check, paired with :desktop:connectedPlaybackLiveCheck -PechoConnectedAndroidPeer=true. */
class ConnectedPlaybackLiveTest {
    @Test fun realAndroidAndWindowsPlayersControlEachOther(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("echoConnectedLive") == "true")
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", true)) { "Emulator only" }
        val context = instrumentation.targetContext
        val accounts = GoogleAccounts(context)
        check(accounts.email.value != null) { "Sign into Firebase on the emulator first" }
        val automatic = accounts.automatic.value
        accounts.setAutomatic(false)
        val fixture = "echo-connected-check-${UUID.randomUUID()}"
        val audio = File(context.filesDir, "$fixture.wav")
        val bytes = ByteBuffer.allocate(44 + 8_000 * 300 * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes.capacity() - 44)
        audio.writeBytes(bytes.array())
        val tracks = (1..2).map { Track("$fixture-$it", "Echo mobile acceptance $it", fixture, album = fixture,
            durationMs = 300_000, sourceUri = Uri.fromFile(audio).toString()) }
        fun <T> main(action: () -> T): T {
            val task = FutureTask(Callable(action)); instrumentation.runOnMainSync(task)
            return task.get(10, TimeUnit.SECONDS)
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var vm: EchoViewModel
        scenario.onActivity { vm = ViewModelProvider(it)[EchoViewModel::class.java] }
        val previouslyEnabled = vm.connectedPlaybackEnabled.value
        suspend fun await(condition: () -> Boolean) = withTimeout(35_000) { while (!main(condition)) delay(100) }
        var originalItems = emptyList<androidx.media3.common.MediaItem>()
        var originalPosition = 0L
        var originalIndex = 0
        var originalPlaying = false
        try {
            await { vm.controller != null && !vm.accountBusy }
            main {
                val player = requireNotNull(vm.controller)
                originalItems = List(player.mediaItemCount) { player.getMediaItemAt(it) }
                originalIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                originalPosition = player.currentPosition.coerceAtLeast(0)
                originalPlaying = player.playWhenReady
                vm.setConnectedPlaybackEnabled(true)
            }
            suspend fun remote(): ConnectedDevice {
                await { vm.connectedPlaybackState.value.devices.any { it.label == "Echo for Windows" &&
                    it.player.title?.startsWith("Acceptance ") == true && it.controlEnabled } }
                return vm.connectedPlaybackState.value.devices.first { it.label == "Echo for Windows" &&
                    it.player.title?.startsWith("Acceptance ") == true && it.controlEnabled }
            }
            suspend fun command(action: ConnectedPlaybackAction, position: Long = 0, condition: (ConnectedDevice) -> Boolean) {
                val target = remote()
                main { vm.sendConnectedPlaybackCommand(target, action, position) }
                await { vm.connectedPlaybackState.value.message == "Remote command applied." &&
                    !vm.connectedPlaybackState.value.pending && vm.connectedPlaybackState.value.devices.any { it.id == target.id && condition(it) } }
                delay(2_500)
            }
            remote()
            fun findText(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.text?.toString()?.contains(text) == true) return node
                for (index in 0 until node.childCount) findText(node.getChild(index), text)?.let { return it }
                return null
            }
            val banner = withTimeout(10_000) {
                var found = findText(instrumentation.uiAutomation.rootInActiveWindow, "on Echo for Windows:")
                while (found == null) { delay(100); found = findText(instrumentation.uiAutomation.rootInActiveWindow, "on Echo for Windows:") }
                found
            }
            var clickable = banner
            while (!clickable.isClickable && clickable.parent != null) clickable = clickable.parent
            assertTrue("The laptop-song banner must open device controls", clickable.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            withTimeout(10_000) {
                while (findText(instrumentation.uiAutomation.rootInActiveWindow, "Your profile") == null) delay(100)
            }
            command(ConnectedPlaybackAction.PAUSE) { !it.player.isPlaying }
            command(ConnectedPlaybackAction.SEEK, 25_000) { it.player.positionMs in 24_000..26_000 }
            command(ConnectedPlaybackAction.PLAY) { it.player.isPlaying && it.player.positionMs >= 25_000 }
            command(ConnectedPlaybackAction.NEXT) { it.player.title == "Acceptance 2" }
            command(ConnectedPlaybackAction.PAUSE) { !it.player.isPlaying }
            command(ConnectedPlaybackAction.SEEK, 0) { it.player.positionMs < 1000 }
            command(ConnectedPlaybackAction.PREVIOUS) { it.player.title == "Acceptance 1" }

            // Disable foreground library upload while the owned local playback fixture exists.
            LibraryRepository(context).upsertTracks(tracks)
            main { requireNotNull(vm.controller).apply { setMediaItems(tracks.map(Track::toMediaItem)); prepare(); play() } }
            await { vm.controller?.isPlaying == true }
            await { vm.controller?.isPlaying == false }
            await { (vm.controller?.currentPosition ?: 0L) in 24_000L..26_000L }
            await { vm.controller?.isPlaying == true && (vm.controller?.currentPosition ?: 0) >= 25_000 }
            await { vm.controller?.currentMediaItem?.mediaId == tracks[1].id }
            await { vm.controller?.currentMediaItem?.mediaId == tracks[0].id }
            await { vm.connectedPlaybackState.value.devices.any { it.id == vm.connectedPlaybackDeviceIdForUi &&
                it.player.title == tracks[0].title && it.ack?.status == "applied" } }
            delay(3_000)
            assertTrue("Signed-in account must be retained", accounts.email.value != null)
        } finally {
            main {
                vm.setConnectedPlaybackEnabled(previouslyEnabled)
                vm.controller?.apply {
                    pause(); clearMediaItems()
                    if (originalItems.isNotEmpty()) {
                        setMediaItems(originalItems, originalIndex, originalPosition); prepare(); playWhenReady = originalPlaying
                    }
                }
            }
            delay(500)
            SQLiteDatabase.openDatabase(context.getDatabasePath("echo-library.db").path, null,
                SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.setForeignKeyConstraintsEnabled(true)
                tracks.forEach { database.delete("tracks", "id = ?", arrayOf(it.id)) }
                database.delete("albums", "name = ? AND artist = ?", arrayOf(fixture, fixture))
                database.delete("artists", "name = ?", arrayOf(fixture))
            }
            audio.delete()
            accounts.setAutomatic(automatic)
            scenario.close()
        }
    }
}
