package dev.echo.app.update

import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.app.EchoViewModel
import dev.echo.app.MainActivity
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class BackgroundUpdateTest {
    @Test fun cancelStopsTheBackgroundTransferAndRemovesPartialBytes() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runBackgroundUpdate") == "true")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: EchoViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[EchoViewModel::class.java] }
            vm.updater.check(manual = true)
            val release = (vm.updater.status.value as? UpdateStatus.Available)?.release ?: error("Install an older baseline")
            withContext(Dispatchers.Main) { vm.downloadUpdate(release) }
            withTimeout(30_000) { vm.updater.status.first { it is UpdateStatus.Downloading } }
            withContext(Dispatchers.Main) { vm.cancelUpdate() }
            withTimeout(30_000) { vm.updater.status.first { it is UpdateStatus.Available } }
            withTimeout(10_000) { while (File(context.filesDir, "updates/incoming.apk").exists()) delay(50) }
            assertFalse(File(context.filesDir, "updates/incoming.apk").exists())
        }
    }

    @Test fun verifiedDownloadFinishesAfterActivityClosesWithScreenOff() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runBackgroundUpdate") == "true")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var vm: EchoViewModel
        scenario.onActivity { vm = ViewModelProvider(it)[EchoViewModel::class.java] }
        try {
            vm.updater.check(manual = true)
            val release = (vm.updater.status.value as? UpdateStatus.Available)?.release ?: error("Install older baseline: ${vm.updater.status.value}")
            withContext(Dispatchers.Main) { vm.downloadUpdate(release) }
            withTimeout(30_000) { vm.updater.status.first { it is UpdateStatus.Downloading } }
            assertTrue(vm.updater.status.value is UpdateStatus.Downloading)
            scenario.close()
            instrumentation.uiAutomation.executeShellCommand("input keyevent 223").close()
            val result = withTimeout(180_000) { vm.updater.status.first { it is UpdateStatus.Ready || it is UpdateStatus.Failed } }
            assertTrue("Download failed: $result", result is UpdateStatus.Ready)
            assertFalse((result as UpdateStatus.Ready).launchInstaller)
            assertEquals(release.size, File(context.filesDir, "updates/pending.apk").length())
            vm.updater.installationIntent() // Revalidates identity; deliberately never launches the installer.
            Unit
        } finally {
            scenario.close()
            instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
        }
    }
}
