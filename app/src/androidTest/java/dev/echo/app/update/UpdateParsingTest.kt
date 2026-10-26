package dev.echo.app.update

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class UpdateParsingTest {
    @Suppress("DEPRECATION")
    @Test fun preparedUpdateHasTheInstalledIdentityAndANewerVersion() {
        val arguments = InstrumentationRegistry.getArguments()
        val path = arguments.getString("preparedUpdatePath")
        assumeTrue("Provide a prepared update APK to verify a real upgrade candidate", path != null)
        val expected = requireNotNull(arguments.getString("preparedUpdateCode")).toLong()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val candidate = requireNotNull(context.packageManager.getPackageArchiveInfo(path!!, flags)) { "Cannot read update APK" }
        fun certificates(info: android.content.pm.PackageInfo) =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo!!.apkContentsSigners else info.signatures!!).map { it.toCharsString() }.toSet()
        validateApkIdentity(candidate.packageName, context.packageName, PackageInfoCompat.getLongVersionCode(candidate),
            PackageInfoCompat.getLongVersionCode(installed), expected, candidate.applicationInfo!!.minSdkVersion,
            Build.VERSION.SDK_INT, certificates(candidate), certificates(installed))
    }

    private fun release(version: Long = 3) = JSONObject().put("tag_name", "v0.1.2").put("name", "Echo 0.1.2")
        .put("body", "Playback fixes").put("draft", false).put("prerelease", false)
        .put("assets", JSONArray().put(JSONObject().put("name", "EchoMusic-$version.apk").put("state", "uploaded")
            .put("size", 123).put("digest", "sha256:" + "a".repeat(64))
            .put("browser_download_url", "https://github.com/addy1243/Echo/releases/download/v0.1.2/EchoMusic-$version.apk")))

    @Test fun githubMetadataSelectsOnlyNewerPublishedStableApks() {
        assertEquals(3L, parseRelease(release().toString(), 2)!!.versionCode)
        assertNull(parseRelease(release(2).toString(), 2))
        assertNull(parseRelease(release().put("draft", true).toString(), 2))
        assertNull(parseRelease(release().put("prerelease", true).toString(), 2))
        val invalid = release().apply { getJSONArray("assets").getJSONObject(0).put("digest", JSONObject.NULL) }
        assertThrows(IllegalArgumentException::class.java) { parseRelease(invalid.toString(), 2) }
    }

    @Test fun updateFileProviderExposesOnlyThePrivateUpdateDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", File(context.filesDir, "updates/pending.apk"))
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.updates", uri.authority)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", File(context.filesDir, "private-data.db"))
        }
    }

    @Suppress("DEPRECATION")
    @Test fun androidCanReadAndPinTheBuiltApksSigningCertificate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archive = context.packageManager.getPackageArchiveInfo(context.applicationInfo.sourceDir, flags)!!
        fun signers(info: android.content.pm.PackageInfo) =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo!!.apkContentsSigners else info.signatures!!).map { it.toCharsString() }.toSet()
        val code = PackageInfoCompat.getLongVersionCode(archive)
        validateApkIdentity(archive.packageName, context.packageName, code, code - 1, code,
            archive.applicationInfo!!.minSdkVersion, Build.VERSION.SDK_INT, signers(archive), signers(installed))
    }
}
