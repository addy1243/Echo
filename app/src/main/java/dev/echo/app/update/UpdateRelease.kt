package dev.echo.app.update

import java.net.URI

internal const val UPDATE_REPOSITORY = "addy1243/Echo"
internal const val MAX_APK_BYTES = 250L * 1024 * 1024

data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val notes: String,
)

internal fun updateVersion(name: String): Long? =
    Regex("EchoMusic-([1-9][0-9]*)\\.apk").matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

internal fun validateRelease(release: UpdateRelease) {
    val uri = URI(release.url)
    require(uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
        uri.path.startsWith("/$UPDATE_REPOSITORY/releases/download/") &&
        uri.path.substringAfterLast('/') == "EchoMusic-${release.versionCode}.apk") { "Update APK must belong to Echo's GitHub Releases" }
    require(release.size in 1..MAX_APK_BYTES) { "Invalid update size" }
    require(release.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "GitHub did not supply a valid SHA-256 digest; upload the APK again" }
}

internal fun validateApkIdentity(
    packageName: String, expectedPackage: String, version: Long, installedVersion: Long,
    expectedVersion: Long, minimumSdk: Int, deviceSdk: Int, signers: Set<String>, installedSigners: Set<String>,
) {
    require(packageName == expectedPackage) { "This APK is not Echo Music" }
    require(version == expectedVersion && version > installedVersion) { "Update version does not match or is not newer" }
    require(minimumSdk <= deviceSdk) { "This update requires a newer Android version" }
    require(signers.isNotEmpty() && signers == installedSigners) { "Update signing key differs from installed Echo; keep using the original signing key" }
}
