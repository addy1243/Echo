package dev.echo.desktop

import java.io.Closeable
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

const val DESKTOP_VERSION = "0.2.3"
const val DESKTOP_UPGRADE_UUID = "a810ba6c-3581-4e29-a09d-f344ca0230ad"
private const val UPDATE_REPOSITORY = "addy1243/Echo"
private const val MAX_INSTALLER_BYTES = 512L * 1024 * 1024
private val UPDATE_HOSTS = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

enum class DesktopUpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, INSTALLING, INSTALLATION_COMPLETE, INSTALLATION_REBOOT_REQUIRED, INSTALLATION_CANCELLED, INSTALLATION_FAILED, FAILED }
data class DesktopUpdateRelease(val version: String, val name: String, val url: String, val size: Long,
    val sha256: String, val notes: String)
data class DesktopUpdateState(val phase: DesktopUpdatePhase = DesktopUpdatePhase.IDLE,
    val release: DesktopUpdateRelease? = null, val receivedBytes: Long = 0, val totalBytes: Long = 0,
    val message: String? = null, val verifiedInstallerCopy: Path? = null)

/** Downloads never change application or library files; Windows Installer handles an explicit installation. */
class DesktopUpdater internal constructor(
    private val root: Path, private val currentVersion: String, private val client: OkHttpClient,
    private val verifyMsi: (Path, DesktopUpdateRelease) -> Unit,
    private val launch: (Path, Path) -> Process,
    private val now: () -> Long,
) : Closeable {
    constructor(root: Path = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "EchoMusic", "updates"),
        currentVersion: String = DESKTOP_VERSION) : this(root, currentVersion,
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build(),
        ::verifyDesktopMsi, { file, log -> ProcessBuilder(desktopInstallerCommand(file, log))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start() }, System::currentTimeMillis)

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(DesktopUpdateState())
    val state: StateFlow<DesktopUpdateState> = mutableState
    @Volatile private var activeCall: Call? = null
    @Volatile private var cancelRequested = false
    @Volatile private var closed = false
    @Volatile private var activeInstaller: Process? = null
    @Volatile private var installerExit: java.util.concurrent.CompletableFuture<Process>? = null
    private val installer get() = root.resolve("pending.msi")
    private val pending get() = root.resolve("pending.json")
    private val preferences get() = root.resolve("preferences.json")

    init { desktopVersionParts(currentVersion) }

    suspend fun check(manual: Boolean) = mutex.withLock { withContext(Dispatchers.IO) {
        if (closed || activeInstaller != null) return@withContext
        cancelRequested = false
        try {
            Files.createDirectories(root)
            if (Files.exists(pending)) {
                val release = runCatching {
                    releaseFromJson(readJson(pending)).also { candidate ->
                        validateDesktopRelease(candidate)
                        require(compareDesktopVersions(candidate.version, currentVersion) > 0)
                        verifyFile(installer, candidate); verifyMsi(installer, candidate)
                    }
                }.getOrNull()
                if (release != null) {
                    mutableState.value = DesktopUpdateState(DesktopUpdatePhase.READY, release,
                        release.size, release.size, "Downloaded update verified. Ready to install.")
                    return@withContext
                }
                Files.deleteIfExists(pending); Files.deleteIfExists(installer)
            }
            val settings = if (Files.exists(preferences)) readJson(preferences) else JSONObject()
            val elapsed = now() - settings.optLong("lastCheck")
            if (!manual && settings.has("lastCheck") && elapsed in 0 until TimeUnit.HOURS.toMillis(24)) return@withContext
            require(now() >= settings.optLong("retryAfter")) { "GitHub's request limit was reached. Try again later." }
            mutableState.value = DesktopUpdateState(DesktopUpdatePhase.CHECKING)
            settings.put("lastCheck", now()); writeJson(preferences, settings)
            val release = request("https://api.github.com/repos/$UPDATE_REPOSITORY/releases?per_page=100") { response ->
                if (response.code == 404) null else {
                    requireResponse(response)
                    val source = response.body?.source() ?: error("Empty update response")
                    source.request(2_000_001)
                    require(source.buffer.size <= 2_000_000) { "Update response is too large" }
                    parseDesktopReleases(source.buffer.readUtf8(), currentVersion)
                }
            }
            mutableState.value = if (release == null) DesktopUpdateState(message = if (manual)
                "No newer Windows update is published." else null) else DesktopUpdateState(DesktopUpdatePhase.AVAILABLE, release,
                totalBytes = release.size, message = "Windows update ${release.version} is available.")
        } catch (cancelled: CancellationException) {
            mutableState.value = DesktopUpdateState(message = "Update check cancelled."); throw cancelled
        } catch (error: Exception) {
            mutableState.value = DesktopUpdateState(if (manual) DesktopUpdatePhase.FAILED else DesktopUpdatePhase.IDLE,
                message = if (manual) safeUpdateError(error) else null)
        } finally { activeCall = null }
    } }

    suspend fun download() = mutex.withLock { withContext(Dispatchers.IO) {
        if (closed || activeInstaller != null) return@withContext
        val release = state.value.release ?: return@withContext
        val partial = root.resolve("incoming.msi")
        cancelRequested = false
        try {
            validateDesktopRelease(release)
            require(compareDesktopVersions(release.version, currentVersion) > 0) { "This Windows version is already installed" }
            Files.createDirectories(root)
            require(root.toFile().usableSpace > release.size + 64 * 1024 * 1024) { "Not enough free space for the update" }
            val settings = if (Files.exists(preferences)) readJson(preferences) else JSONObject()
            require(now() >= settings.optLong("retryAfter")) { "GitHub's request limit was reached. Try again later." }
            mutableState.value = DesktopUpdateState(DesktopUpdatePhase.DOWNLOADING, release, totalBytes = release.size)
            val operation = currentCoroutineContext()
            request(release.url) { response ->
                requireResponse(response)
                val body = response.body ?: error("Empty update download")
                require(body.contentLength() == -1L || body.contentLength() == release.size) { "Installer size differs from its release" }
                var received = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                FileOutputStream(partial.toFile()).use { output -> body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        operation.ensureActive(); check(!cancelRequested) { "Update download cancelled" }
                        val count = input.read(buffer); if (count < 0) break
                        received += count
                        require(received <= release.size) { "Installer exceeds its published size" }
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        mutableState.value = DesktopUpdateState(DesktopUpdatePhase.DOWNLOADING, release, received, release.size)
                    }
                    output.fd.sync()
                } }
                require(received == release.size && digest.digest().hex() == release.sha256) { "Installer verification failed; retry download" }
            }
            currentCoroutineContext().ensureActive(); check(!cancelRequested) { "Update download cancelled" }
            verifyMsi(partial, release)
            currentCoroutineContext().ensureActive(); check(!cancelRequested) { "Update download cancelled" }
            Files.move(partial, installer, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            writeJson(pending, releaseJson(release))
            mutableState.value = DesktopUpdateState(DesktopUpdatePhase.READY, release, release.size, release.size,
                "Update verified. Choose Install to open Windows Installer.")
        } catch (cancelled: CancellationException) {
            mutableState.value = DesktopUpdateState(DesktopUpdatePhase.AVAILABLE, release, message = "Update download cancelled.")
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = DesktopUpdateState(if (cancelRequested) DesktopUpdatePhase.AVAILABLE else DesktopUpdatePhase.FAILED,
                release, totalBytes = release.size, message = if (cancelRequested) "Update download cancelled." else safeUpdateError(error))
        } finally { activeCall = null; Files.deleteIfExists(partial) }
    } }

    /** Saves a second verified copy at the user's chosen path; the staged installer remains intact. */
    suspend fun saveVerifiedInstaller(destination: Path, overwriteConfirmed: Boolean = false) = mutex.withLock { withContext(Dispatchers.IO) {
        check(!closed && activeInstaller == null && state.value.phase in setOf(DesktopUpdatePhase.READY,
            DesktopUpdatePhase.INSTALLATION_CANCELLED, DesktopUpdatePhase.INSTALLATION_FAILED)) { "Download and verify an update first" }
        val release = state.value.release ?: error("Update metadata is missing")
        validateDesktopRelease(release)
        val target = destination.toAbsolutePath().normalize()
        require(target.fileName.toString().endsWith(".msi", ignoreCase = true)) { "Choose an .msi file name" }
        require(target != installer.toAbsolutePath().normalize()) { "Choose a different path from Echo's staged installer" }
        require(overwriteConfirmed || !Files.exists(target)) { "That file already exists; confirm overwrite in the save dialog" }
        verifyFile(installer, release); verifyMsi(installer, release)
        val parent = target.parent ?: error("Choose a file inside a folder")
        Files.createDirectories(parent)
        require(!Files.isDirectory(target)) { "Choose a file path, not a folder" }
        val temp = Files.createTempFile(parent, ".echo-update-", ".msi")
        try {
            Files.copy(installer, temp, StandardCopyOption.REPLACE_EXISTING)
            verifyFile(temp, release); verifyMsi(temp, release)
            if (overwriteConfirmed) {
                try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING) }
            } else Files.move(temp, target)
            verifyFile(target, release); verifyMsi(target, release)
            mutableState.value = state.value.copy(verifiedInstallerCopy = target,
                message = "A second installer copy was saved and verified at $target. Install it only with the separate Install saved copy action.")
        } finally { Files.deleteIfExists(temp) }
    } }

    /** Call only from an explicit user Install action; no background installer launch. */
    suspend fun launchInstaller() = launchInstallerFrom(installer)

    /** Installs only the separately saved copy, rechecking its bytes and MSI identity first. */
    suspend fun launchSavedInstaller() {
        val copy = state.value.verifiedInstallerCopy ?: error("Save a verified installer copy first")
        launchInstallerFrom(copy)
    }

    private suspend fun launchInstallerFrom(file: Path) = mutex.withLock { withContext(Dispatchers.IO) {
        check(!closed && activeInstaller == null && state.value.phase in setOf(DesktopUpdatePhase.READY,
            DesktopUpdatePhase.INSTALLATION_CANCELLED, DesktopUpdatePhase.INSTALLATION_FAILED)) { "Download and verify an update first, and wait for any installer to finish" }
        val release = state.value.release ?: error("Update metadata is missing")
        val savedCopy = state.value.verifiedInstallerCopy
        validateDesktopRelease(release)
        check(compareDesktopVersions(release.version, currentVersion) > 0) { "This version is already installed" }
        verifyFile(file, release); verifyMsi(file, release)
        Files.createDirectories(root)
        val log = root.resolve("installer-${now()}-${UUID.randomUUID()}.log").toAbsolutePath()
        val process = launch(file.toAbsolutePath(), log)
        activeInstaller = process
        mutableState.value = DesktopUpdateState(DesktopUpdatePhase.INSTALLING, release, release.size, release.size,
            "Windows Installer is running. Its verbose log is saved at $log", savedCopy)
        val exit = process.onExit()
        installerExit = exit
        exit.whenComplete { exited, error ->
            if (closed) return@whenComplete
            if (error != null && runCatching { process.isAlive }.getOrDefault(true)) {
                installerExit = null
                mutableState.value = DesktopUpdateState(DesktopUpdatePhase.INSTALLING, release, release.size, release.size,
                    "Windows Installer is still running, but its exit could not be monitored. Do not start another installer. Its log is at $log", savedCopy)
                return@whenComplete
            }
            val exitCode = if (error == null) runCatching { exited.exitValue() }.getOrNull() else null
            activeInstaller = null
            installerExit = null
            val (phase, result) = when (exitCode) {
                0 -> DesktopUpdatePhase.INSTALLATION_COMPLETE to "Windows Installer completed successfully. Restart Echo and confirm it reports version ${release.version}. Log: $log"
                3010 -> DesktopUpdatePhase.INSTALLATION_REBOOT_REQUIRED to "Windows Installer succeeded and reports that Windows must be restarted (3010). Echo will not restart Windows. After restarting Windows, relaunch Echo and confirm version ${release.version}. Log: $log"
                1602 -> DesktopUpdatePhase.INSTALLATION_CANCELLED to "Windows Installer was cancelled. You can try again. Log: $log"
                1603 -> DesktopUpdatePhase.INSTALLATION_FAILED to "Windows Installer reported a fatal error (1603). Review its log before retrying: $log"
                1618 -> DesktopUpdatePhase.INSTALLATION_FAILED to "Another Windows Installer operation is running (1618). Wait for it to finish, then retry. Log: $log"
                null -> DesktopUpdatePhase.INSTALLATION_FAILED to "Could not read the Windows Installer result. Review its log before retrying: $log"
                else -> DesktopUpdatePhase.INSTALLATION_FAILED to "Windows Installer failed with exit code $exitCode. Review its log before retrying: $log"
            }
            mutableState.value = DesktopUpdateState(phase, release, release.size, release.size, result, savedCopy)
        }
    } }

    fun cancel() { cancelRequested = true; activeCall?.cancel() }
    override fun close() {
        closed = true; cancel(); installerExit?.cancel(false); installerExit = null; activeInstaller = null
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }

    private suspend fun <T> request(url: String, block: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { activeCall?.cancel() }
        fun enqueue(address: String, redirects: Int) {
            try {
                val parsed = address.toHttpUrl()
                require(parsed.isHttps && parsed.host in UPDATE_HOSTS && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.port == 443 && parsed.fragment == null) { "Update redirected outside trusted GitHub hosts" }
                val call = client.newCall(Request.Builder().url(parsed).header("User-Agent", "EchoMusic-Windows-Updater")
                    .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").build())
                activeCall = call
                if (!continuation.isActive || cancelRequested) { call.cancel(); throw IOException("Update request cancelled") }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(IOException("Could not reach GitHub"))) }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                if (!continuation.isActive) return
                                if (it.code in setOf(301, 302, 303, 307, 308)) {
                                    require(redirects < 5) { "Too many update redirects" }
                                    val next = parsed.resolve(it.header("Location") ?: error("Missing update redirect")) ?: error("Invalid update redirect")
                                    enqueue(next.toString(), redirects + 1)
                                } else continuation.resumeWith(runCatching { block(it) })
                            }
                        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(error)) }
                    }
                })
            } catch (error: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(error)) }
        }
        enqueue(url, 0)
    }

    private fun requireResponse(response: Response) {
        if (response.code == 429 || response.code == 403 && response.header("X-RateLimit-Remaining") == "0") {
            val delay = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 86_400) ?: 3600
            val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { it in (now() / 1000 + 60)..(now() / 1000 + 86_400) }
            val settings = if (Files.exists(preferences)) readJson(preferences) else JSONObject()
            writeJson(preferences, settings.put("retryAfter", reset?.times(1000) ?: (now() + delay * 1000)))
            throw IOException("GitHub's request limit was reached. Try again later.")
        }
        require(response.isSuccessful) { "GitHub update request failed (HTTP ${response.code})" }
    }
}

internal fun desktopVersionParts(value: String): List<Int> {
    require(value.matches(Regex("(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,4})"))) { "Invalid Windows version" }
    return value.split('.').map(String::toInt).also { require(it[0] <= 255 && it[1] <= 255 && it[2] <= 65535) { "Windows version exceeds MSI limits" } }
}
internal fun compareDesktopVersions(first: String, second: String): Int = desktopVersionParts(first).zip(desktopVersionParts(second))
    .map { (a, b) -> a.compareTo(b) }.firstOrNull { it != 0 } ?: 0

internal fun parseDesktopReleases(json: String, installed: String): DesktopUpdateRelease? {
    val releases = JSONArray(json)
    require(releases.length() <= 100) { "Too many release entries" }
    // shortcut: one bounded page covers 100 releases; add pagination if Windows falls outside that window.
    return (0 until releases.length()).mapNotNull { index ->
        val release = releases.optJSONObject(index) ?: return@mapNotNull null
        runCatching { parseDesktopRelease(release.toString(), installed) }.getOrNull()
    }.maxWithOrNull { a, b -> compareDesktopVersions(a.version, b.version) }
}

internal fun parseDesktopRelease(json: String, installed: String): DesktopUpdateRelease? {
    val release = JSONObject(json)
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
    val assets = release.getJSONArray("assets")
    return (0 until assets.length()).mapNotNull { index ->
        val asset = assets.getJSONObject(index)
        val version = Regex("EchoMusic-Windows-x64-([0-9]+\\.[0-9]+\\.[0-9]+)\\.msi").matchEntire(asset.optString("name"))?.groupValues?.get(1) ?: return@mapNotNull null
        if (asset.optString("state") != "uploaded" || compareDesktopVersions(version, installed) <= 0) return@mapNotNull null
        DesktopUpdateRelease(version, asset.getString("name"), asset.getString("browser_download_url"), asset.getLong("size"),
            asset.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:").orEmpty(),
            release.optString("body").takeIf { it != "null" }.orEmpty().take(3000))
            .also(::validateDesktopRelease)
    }.maxWithOrNull { a, b -> compareDesktopVersions(a.version, b.version) }
}

internal fun validateDesktopRelease(release: DesktopUpdateRelease) {
    desktopVersionParts(release.version)
    require(release.name == "EchoMusic-Windows-x64-${release.version}.msi" && release.size in 1..MAX_INSTALLER_BYTES &&
        release.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid Windows release metadata" }
    val url = release.url.toHttpUrl()
    require(url.isHttps && url.host == "github.com" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        url.query == null && url.fragment == null && url.pathSegments.size == 6 &&
        url.pathSegments.take(4) == listOf("addy1243", "Echo", "releases", "download") && url.pathSegments[4].isNotBlank() &&
        url.pathSegments[5] == release.name) { "Windows update must come from Echo's GitHub release" }
}

private fun verifyFile(file: Path, release: DesktopUpdateRelease) {
    require(Files.isRegularFile(file) && !Files.isSymbolicLink(file) && Files.size(file) == release.size) { "Saved installer is missing or changed; retry download" }
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input -> val buffer = ByteArray(64 * 1024); while (true) {
        val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read)
    } }
    require(digest.digest().hex() == release.sha256) { "Saved installer failed verification; retry download" }
}
private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
private fun releaseJson(release: DesktopUpdateRelease) = JSONObject().put("version", release.version).put("name", release.name)
    .put("url", release.url).put("size", release.size).put("sha256", release.sha256).put("notes", release.notes)
private fun releaseFromJson(json: JSONObject) = DesktopUpdateRelease(json.getString("version"), json.getString("name"),
    json.getString("url"), json.getLong("size"), json.getString("sha256"), json.optString("notes").take(3000))
private fun readJson(file: Path): JSONObject {
    require(Files.size(file) <= 65_536 && !Files.isSymbolicLink(file)) { "Saved update settings are invalid" }
    return JSONObject(Files.readString(file))
}
private fun writeJson(file: Path, json: JSONObject) {
    val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
    try {
        FileOutputStream(temp.toFile()).use { it.write(json.toString().toByteArray()); it.fd.sync() }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temp) }
}
private fun safeUpdateError(error: Exception) = if (error is IllegalArgumentException || error is IllegalStateException || error is IOException)
    error.message?.take(250) ?: "Windows update failed. Retry later." else "Windows update failed. Retry later."

internal fun validateDesktopMsiIdentity(product: String, version: String, upgradeCode: String, template: String, release: DesktopUpdateRelease) {
    require(product == "EchoMusic" && version == release.version &&
        upgradeCode.trim('{', '}').equals(DESKTOP_UPGRADE_UUID, true) && template.substringBefore(';') == "x64") {
        "Installer identity does not match Echo Music for Windows"
    }
}

private fun windowsSystemFile(relative: String): String {
    check(System.getProperty("os.name").startsWith("Windows")) { "Windows Installer requires Windows" }
    return Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", relative).toAbsolutePath().toString()
}
internal fun desktopInstallerCommand(file: Path, log: Path): List<String> = listOf(windowsSystemFile("msiexec.exe"), "/i",
    file.toAbsolutePath().toString(), "/norestart", "/L*v", log.toAbsolutePath().toString())

internal fun verifyDesktopMsi(file: Path, release: DesktopUpdateRelease) {
    val path = file.toAbsolutePath().toString().replace("'", "''")
    val script = """
        ${'$'}ErrorActionPreference='Stop'
        ${'$'}ProgressPreference='SilentlyContinue'
        [Console]::OutputEncoding=New-Object System.Text.UTF8Encoding(${'$'}false)
        ${'$'}installer=New-Object -ComObject WindowsInstaller.Installer
        ${'$'}database=${'$'}installer.OpenDatabase('$path',0)
        function Property([string]${'$'}name) {
          ${'$'}view=${'$'}database.OpenView("SELECT ``Value`` FROM ``Property`` WHERE ``Property``='${'$'}name'")
          [void]${'$'}view.Execute(); ${'$'}row=${'$'}view.Fetch(); ${'$'}result=if(${'$'}row){${'$'}row.StringData(1)}else{''}; [void]${'$'}view.Close(); return ${'$'}result
        }
        ${'$'}json=@{product=(Property 'ProductName');version=(Property 'ProductVersion');upgrade=(Property 'UpgradeCode');template=${'$'}database.SummaryInformation(0).Property(7)} | ConvertTo-Json -Compress
        [Console]::Out.Write(${'$'}json)
    """.trimIndent()
    val process = ProcessBuilder(windowsSystemFile("WindowsPowerShell\\v1.0\\powershell.exe"), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
        "-EncodedCommand", Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))).redirectErrorStream(true).start()
    try {
        require(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) { "Windows could not inspect this installer" }
        val output = process.inputStream.readNBytes(16_385)
        require(output.size <= 16_384) { "Invalid installer inspection response" }
        val identity = JSONObject(output.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        validateDesktopMsiIdentity(identity.getString("product"), identity.getString("version"), identity.getString("upgrade"), identity.getString("template"), release)
    } finally { process.destroyForcibly() }
}
