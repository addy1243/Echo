package dev.echo.app.account

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.echo.data.library.LibraryRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** One complete snapshot per device, merged against that device's last imported snapshot. */
@Singleton
class DrivePlaylistSync @Inject constructor(
    @ApplicationContext context: Context,
    private val library: LibraryRepository,
    private val accounts: GoogleAccounts,
) {
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("google-sync-device", Context.MODE_PRIVATE)
    private val deviceId = prefs.getString("device", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("device", it).commit()) { "Could not save sync device identity" }
    }
    private val root = File(context.noBackupFilesDir, "playlist-sync")
    private var retryAfter = 0L
    private val reportRevision = MutableStateFlow(0)
    val deviceReport = combine(accounts.email, reportRevision) { email, _ ->
        email?.let {
            val key = sha256(it)
            val count = prefs.getInt("device-count:$key", 0)
            val checked = prefs.getLong("device-checked:$key", 0)
            if (count in 1..101 && checked > 0) SyncDeviceReport(count, checked) else null
        }
    }
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).build()

    suspend fun synchronize(token: String, expectedAccount: String): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            checkAccount(expectedAccount)
            check(System.currentTimeMillis() >= retryAfter) { "Drive asked Echo to wait. Try syncing later." }
            require(token.isNotBlank()) { "Drive permission is required" }
            val accountDir = File(root, sha256(expectedAccount)).apply { mkdirs() }
            val listing = JSONObject(request(token, "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&pageSize=100&fields=nextPageToken,files(id,name)&q=trashed%3Dfalse"))
            check(!listing.has("nextPageToken")) { "Too many Drive app files to sync safely; use library export while this is reviewed." }
            val files = listing.getJSONArray("files")
            val registeredDevices = snapshotDevices(List(files.length()) { files.getJSONObject(it).getString("name") })
            var ownId: String? = null
            for (i in 0 until files.length()) {
                val metadata = files.getJSONObject(i)
                val name = metadata.getString("name")
                val remoteDevice = snapshotDeviceId(name) ?: continue
                val id = metadata.getString("id")
                require(id.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid Drive file identity" }
                if (remoteDevice == deviceId) {
                    check(ownId == null) { "Duplicate device snapshot in Drive; sync stopped to preserve data" }
                    ownId = id
                    continue
                }
                val envelope = JSONObject(request(token, "https://www.googleapis.com/drive/v3/files/$id?alt=media"))
                require(envelope.getString("format") == "echo-device-playlists" && envelope.getInt("version") == 1 && envelope.getString("device") == remoteDevice) { "Unsupported Drive playlist snapshot" }
                val remote = qualifyCloudLocalTracks(envelope.getJSONObject("library").toString(), remoteDevice)
                val baselineFile = File(accountDir, "$remoteDevice.json")
                val baseline = baselineFile.takeIf { it.exists() }?.readText()
                if (baseline == remote) continue
                checkAccount(expectedAccount)
                val expected = library.exportBackup()
                val local = qualifyCloudLocalTracks(expected, deviceId)
                val merged = mergeCloudBackups(baseline, local, remote)
                val removed = playlistIds(local) - playlistIds(merged)
                currentCoroutineContext().ensureActive()
                library.applyCloudPlaylists(expected, restoreOwnLocalTracks(merged, deviceId), removed)
                atomicWrite(baselineFile, remote)
            }
            checkAccount(expectedAccount)
            val snapshot = qualifyCloudLocalTracks(library.exportBackup(), deviceId)
            val previous = File(accountDir, "uploaded.json")
            if (ownId == null || !previous.exists() || previous.readText() != snapshot) {
                val envelope = JSONObject().put("format", "echo-device-playlists").put("version", 1)
                    .put("device", deviceId).put("library", JSONObject(snapshot)).toString()
                val media = "application/json; charset=utf-8".toMediaType()
                if (ownId == null) {
                    val metadata = JSONObject().put("name", "echo-playlists-$deviceId.json")
                        .put("parents", org.json.JSONArray(listOf("appDataFolder"))).toString()
                    val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                        .addPart(metadata.toRequestBody(media)).addPart(envelope.toRequestBody(media)).build()
                    request(token, "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart", body = body)
                } else request(token, "https://www.googleapis.com/upload/drive/v3/files/$ownId?uploadType=media", "PATCH", envelope.toRequestBody(media))
                atomicWrite(previous, snapshot)
            }
            checkAccount(expectedAccount)
            val reportKey = sha256(expectedAccount)
            check(prefs.edit().putInt("device-count:$reportKey", (registeredDevices + deviceId).size)
                .putLong("device-checked:$reportKey", System.currentTimeMillis()).commit()) { "Playlists synced but device status could not be saved" }
            reportRevision.value++
            "Playlists synced with $expectedAccount"
        }
    }

    private fun checkAccount(expected: String) { check(accounts.email.value == expected) { "Account changed; sync stopped" } }

    private suspend fun request(token: String, url: String, method: String = "GET", body: okhttp3.RequestBody? = null): String {
        currentCoroutineContext().ensureActive()
        val call = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").method(if (body != null && method == "GET") "POST" else method, body).build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try {
                        val text = response.use {
                            if (response.code == 401) accounts.invalidateToken(token)
                            if (response.code == 429 || response.code == 503) {
                                val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 86_400) ?: 300L
                                retryAfter = System.currentTimeMillis() + seconds * 1000
                            }
                            check(response.isSuccessful) { when (response.code) {
                                401, 403 -> "Google Drive access was denied or expired. Reconnect Drive and check OAuth setup."
                                429 -> "Google Drive is rate limiting requests. Try syncing later."
                                else -> "Drive sync failed (HTTP ${response.code}); local playlists were kept."
                            } }
                            val source = response.body ?: error("Drive returned no data")
                            source.byteStream().use { input ->
                                val bytes = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(bytes.size() + count <= 10_000_000) { "Drive snapshot exceeds 10 MB" }
                                    bytes.write(buffer, 0, count)
                                }
                                bytes.toString(Charsets.UTF_8.name())
                            }
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
            })
        }
    }
}

private fun playlistIds(json: String): Set<String> = JSONObject(json).getJSONArray("playlists").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet() }
private fun sha256(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
private fun atomicWrite(file: File, value: String) {
    val atomic = android.util.AtomicFile(file)
    val output = atomic.startWrite()
    try { output.write(value.toByteArray()); atomic.finishWrite(output) }
    catch (error: Exception) { atomic.failWrite(output); throw error }
}
