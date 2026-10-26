package dev.echo.app.update

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit network opt-in; verifies public release download without accepting system installation. */
class LiveGitHubUpdateTest {
    @Suppress("DEPRECATION")
    @Test fun publicReleaseDownloadsVerifiesAndHandsOffWithoutSilentInstallation() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with runLiveEchoUpdate=true", arguments.getString("runLiveEchoUpdate") == "true")
        assumeTrue("Use the dedicated emulator", Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"))
        val expectedCode = requireNotNull(arguments.getString("expectedUpdateCode")).toLong()
        val expectedHash = requireNotNull(arguments.getString("expectedUpdateSha256"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun installedCode() = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        val originalCode = installedCode()
        assertTrue("Install an older same-key baseline before testing a newer release", originalCode < expectedCode)
        val updater = AppUpdater(context)
        updater.check(manual = true)
        val available = updater.status.value as? UpdateStatus.Available ?: error("Public release is unavailable: ${updater.status.value}")
        assertEquals(expectedCode, available.release.versionCode)
        assertEquals(expectedHash.lowercase(), available.release.sha256.lowercase())
        updater.download(available.release)
        val ready = updater.status.value as? UpdateStatus.Ready ?: error("Verified update was not staged: ${updater.status.value}")
        assertEquals(expectedCode, ready.release.versionCode)
        val file = File(context.filesDir, "updates/pending.apk")
        assertEquals(available.release.size, file.length())
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        assertEquals(expectedHash.lowercase(), digest.digest().joinToString("") { "%02x".format(it) })
        val intent = updater.installationIntent()
        assertTrue(intent.action in setOf(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Intent.ACTION_VIEW))
        if (intent.action == Intent.ACTION_VIEW) {
            assertEquals("content", intent.data?.scheme)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
        assertEquals("System confirmation must remain required; the test never starts the installer", originalCode, installedCode())
    }
}
