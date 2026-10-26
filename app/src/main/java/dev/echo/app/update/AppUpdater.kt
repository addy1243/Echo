package dev.echo.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Call
import okhttp3.Callback
import org.json.JSONObject

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class Available(val release: UpdateRelease) : UpdateStatus
    data class Downloading(val release: UpdateRelease, val percent: Int) : UpdateStatus
    data class Ready(val release: UpdateRelease, val launchInstaller: Boolean = false) : UpdateStatus
    data class Failed(val message: String, val release: UpdateRelease? = null) : UpdateStatus
}

@Singleton
class AppUpdater @Inject constructor(@ApplicationContext private val context: Context) {
    private val preferences = context.getSharedPreferences("echo_updates", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status = mutableStatus.asStateFlow()
    private val directory get() = File(context.filesDir, "updates").apply { mkdirs() }
    private val apk get() = File(directory, "pending.apk")
    private val client = OkHttpClient.Builder().followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            require(url.isHttps && url.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "Update redirected outside GitHub" }
            chain.proceed(chain.request())
        }.build()

    suspend fun check(manual: Boolean): String? = mutex.withLock { withContext(Dispatchers.IO) {
        if (!manual) {
            if (apk.exists()) {
                try {
                    val info = archive(apk)
                    val version = PackageInfoCompat.getLongVersionCode(info)
                    if (version > installedVersion()) {
                        verifyIdentity(info, version)
                        mutableStatus.value = UpdateStatus.Ready(UpdateRelease(version, info.versionName ?: version.toString(), "", apk.length(), "", "Previously downloaded update"))
                        return@withContext null
                    }
                    apk.delete()
                } catch (_: Exception) { apk.delete() }
            }
            val elapsed = System.currentTimeMillis() - preferences.getLong("last_check", 0)
            if (elapsed in 0 until TimeUnit.HOURS.toMillis(24)) return@withContext null
        }
        if (System.currentTimeMillis() < preferences.getLong("retry_after", 0)) {
            if (manual) mutableStatus.value = UpdateStatus.Failed("GitHub's request limit was reached. Please try again later.")
            return@withContext null
        }
        mutableStatus.value = UpdateStatus.Checking
        preferences.edit().putLong("last_check", System.currentTimeMillis()).apply()
        try {
            val release = client.newCall(Request.Builder().url("https://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest")
                .header("Accept", "application/vnd.github+json").header("User-Agent", "EchoMusic-Updater")
                .header("X-GitHub-Api-Version", "2022-11-28").build()).useCancellable { response ->
                if (response.code == 404) return@useCancellable null
                requireResponse(response)
                val source = (response.body ?: error("Empty release response")).source()
                source.request(2_000_001)
                require(source.buffer.size <= 2_000_000) { "Release response is too large" }
                parseRelease(source.buffer.readUtf8(), installedVersion())
            }
            mutableStatus.value = release?.let(UpdateStatus::Available) ?: UpdateStatus.Idle
            if (manual && release == null) "No newer public APK is available on GitHub Releases." else null
        } catch (cancelled: CancellationException) { mutableStatus.value = UpdateStatus.Idle; throw cancelled }
        catch (error: Exception) {
            mutableStatus.value = if (manual) UpdateStatus.Failed(error.message ?: "Could not check for updates") else UpdateStatus.Idle
            null
        }
    } }

    suspend fun download(release: UpdateRelease) = mutex.withLock { withContext(Dispatchers.IO) {
        val partial = File(directory, "incoming.apk")
        try {
            require(System.currentTimeMillis() >= preferences.getLong("retry_after", 0)) { "GitHub's request limit was reached. Please try again later." }
            validateRelease(release)
            require(release.versionCode > installedVersion()) { "This version is already installed" }
            mutableStatus.value = UpdateStatus.Downloading(release, 0)
            val operationContext = currentCoroutineContext()
            client.newCall(Request.Builder().url(release.url).header("User-Agent", "EchoMusic-Updater").build()).useCancellable { response ->
                requireResponse(response)
                val body = response.body ?: throw IOException("Empty APK download")
                require(body.contentLength() == -1L || body.contentLength() == release.size) { "APK size differs from published release" }
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        operationContext.ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        received += count
                        require(received <= release.size) { "Download exceeds published APK size" }
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        mutableStatus.value = UpdateStatus.Downloading(release, (received * 100 / release.size).toInt())
                    }
                    output.flush(); output.fd.sync()
                } }
                require(received == release.size && digest.digest().hex().equals(release.sha256, true)) { "APK verification failed; download was incomplete or changed" }
            }
            currentCoroutineContext().ensureActive()
            verifyIdentity(archive(partial), release.versionCode)
            currentCoroutineContext().ensureActive()
            Files.move(partial.toPath(), apk.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            currentCoroutineContext().ensureActive()
            mutableStatus.value = UpdateStatus.Ready(release)
        } catch (cancelled: CancellationException) { mutableStatus.value = UpdateStatus.Available(release); throw cancelled }
        catch (error: Exception) {
            if (!currentCoroutineContext().isActive) { mutableStatus.value = UpdateStatus.Available(release); throw CancellationException("Update cancelled", error) }
            mutableStatus.value = UpdateStatus.Failed(error.message ?: "Update download failed", release)
        }
        finally { partial.delete() }
    } }

    suspend fun installationIntent(): Intent = withContext(Dispatchers.IO) {
        val ready = mutableStatus.value as? UpdateStatus.Ready ?: error("Download an update first")
        require(apk.isFile) { "Downloaded APK is missing; download it again" }
        verifyIdentity(archive(apk), ready.release.versionCode)
        if (!context.packageManager.canRequestPackageInstalls()) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun dismiss() { if (mutableStatus.value !is UpdateStatus.Downloading) mutableStatus.value = UpdateStatus.Idle }
    fun consumeInstallerLaunch() { (mutableStatus.value as? UpdateStatus.Ready)?.let { mutableStatus.value = it.copy(launchInstaller = false) } }

    private fun requireResponse(response: Response) {
        if (response.code == 429 || response.code == 403 && response.header("X-RateLimit-Remaining") == "0") {
            val until = response.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
                ?: (System.currentTimeMillis() + (response.header("Retry-After")?.toLongOrNull() ?: 3600) * 1000)
            preferences.edit().putLong("retry_after", until).apply()
            throw IOException("GitHub's request limit was reached. Please try again later.")
        }
        if (!response.isSuccessful) throw IOException("GitHub update request failed (${response.code})")
    }

    @Suppress("DEPRECATION")
    private fun installed() = context.packageManager.getPackageInfo(context.packageName, signatureFlags())
    private fun installedVersion() = PackageInfoCompat.getLongVersionCode(installed())
    @Suppress("DEPRECATION")
    private fun archive(file: File) = context.packageManager.getPackageArchiveInfo(file.path, signatureFlags()) ?: error("Downloaded file is not a valid APK")
    @Suppress("DEPRECATION")
    private fun signatureFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            .orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()

    private fun verifyIdentity(info: PackageInfo, expectedVersion: Long) {
        val current = installed()
        validateApkIdentity(info.packageName, context.packageName, PackageInfoCompat.getLongVersionCode(info),
            PackageInfoCompat.getLongVersionCode(current), expectedVersion, info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE,
            Build.VERSION.SDK_INT, signers(info), signers(current))
    }
}

internal fun parseRelease(json: String, installedVersion: Long): UpdateRelease? {
    val release = JSONObject(json)
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
    val assets = release.getJSONArray("assets")
    val candidates = (0 until assets.length()).mapNotNull { index ->
        val asset = assets.getJSONObject(index)
        val version = updateVersion(asset.optString("name")) ?: return@mapNotNull null
        if (version <= installedVersion || asset.optString("state") != "uploaded") return@mapNotNull null
        UpdateRelease(version, release.optString("name").ifBlank { release.getString("tag_name") }.take(100),
            asset.getString("browser_download_url"), asset.getLong("size"), asset.optString("digest").removePrefix("sha256:"),
            release.optString("body").takeIf { it != "null" }.orEmpty().take(3000)).also(::validateRelease)
    }
    return candidates.maxByOrNull(UpdateRelease::versionCode)
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal suspend fun <T> Call.useCancellable(block: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) { continuation.resumeWith(Result.failure(error)) }
        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) { response.close(); return }
            continuation.resumeWith(runCatching { response.use(block) })
        }
    })
}
