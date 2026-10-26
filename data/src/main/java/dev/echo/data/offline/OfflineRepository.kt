package dev.echo.data.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.echo.core.PlayableSource
import dev.echo.core.Track
import dev.echo.data.provider.CommonsProvider
import dev.echo.data.provider.YouTubeProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class OfflineSong(val trackId: String, val title: String, val pinned: Boolean,
    val status: String, val bytes: Long = 0, val savedAt: Long = 0, val message: String? = null,
    val sha256: String = "", val receivedBytes: Long = 0, val totalBytes: Long? = null)
data class OfflineSettings(val offlineOnly: Boolean = false, val autoCache: Boolean = false)

/** Complete private files only. Expiring media URLs never enter the durable manifest. */
@Singleton
class OfflineRepository @Inject constructor(@ApplicationContext private val context: Context,
    private val youtube: YouTubeProvider, private val commons: CommonsProvider) {
    private val directory = File(context.filesDir, "offline-audio").apply { mkdirs() }
    private val preferences = context.getSharedPreferences("offline-audio", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(1)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val removing = mutableSetOf<String>()
    private val calls = ConcurrentHashMap<String, okhttp3.Call>()
    private val lock = Any()
    private val mutableSongs = MutableStateFlow(readManifest())
    val songs = mutableSongs.asStateFlow()
    private val mutableSettings = MutableStateFlow(OfflineSettings(
        preferences.getBoolean("offline", false), preferences.getBoolean("automatic", false)))
    val settings = mutableSettings.asStateFlow()
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()

    init {
        // A killed process cannot retain a transfer; partial files are never offered for playback.
        directory.listFiles()?.filter { it.name.endsWith(".part") ||
            it.name.endsWith(".audio") && songs.value.none { song -> song.status == "saved" && offlineFileName(song.trackId) == it.name } }
            ?.forEach(File::delete)
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) = stopAutomaticTransfers()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) stopAutomaticTransfers()
            }
        })
    }

    fun setOfflineOnly(enabled: Boolean) {
        mutableSettings.value = mutableSettings.value.copy(offlineOnly = enabled)
        preferences.edit().putBoolean("offline", enabled).apply()
        if (enabled) jobs.keys.forEach { id -> calls[id]?.cancel(); jobs[id]?.cancel() }
    }

    fun setAutoCache(enabled: Boolean) {
        mutableSettings.value = mutableSettings.value.copy(autoCache = enabled)
        preferences.edit().putBoolean("automatic", enabled).apply()
        if (!enabled) stopAutomaticTransfers()
    }

    private fun stopAutomaticTransfers() {
        songs.value.filterNot(OfflineSong::pinned).forEach { calls[it.trackId]?.cancel(); jobs[it.trackId]?.cancel() }
    }

    suspend fun localSource(track: Track): PlayableSource? = withContext(Dispatchers.IO) {
        val saved = songs.value.find { it.trackId == track.id && it.status == "saved" } ?: return@withContext null
        val file = File(directory, offlineFileName(track.id))
        if (!runCatching { validOfflineFile(file, saved.bytes, saved.sha256) }.getOrDefault(false)) {
            update(saved.copy(status = "failed", message = "Saved audio is missing or damaged. Retry download."))
            return@withContext null
        }
        PlayableSource(Uri.fromFile(file).toString())
    }

    fun download(track: Track, pinned: Boolean = true) {
        require(track.provider in setOf("youtube", "commons")) { "This song cannot be downloaded. Local files are already on your device." }
        check(!settings.value.offlineOnly) { "Turn off Offline mode to download music" }
        synchronized(lock) {
            check(track.id !in removing) { "This download is being removed. Try again in a moment." }
            val previous = songs.value.find { it.trackId == track.id }
            if (previous?.status == "saved") {
                if (pinned && !previous.pinned) {
                    check(songs.value.filter { it.pinned && it.status == "saved" }.sumOf(OfflineSong::bytes) + previous.bytes <= DOWNLOAD_BUDGET) {
                        "Download storage limit reached. Remove some downloads first."
                    }
                    update(previous.copy(pinned = true))
                }
                return
            }
            if (jobs.containsKey(track.id)) return
            update(OfflineSong(track.id, track.title, pinned, "queued"))
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    gate.withPermit {
                        check(!settings.value.offlineOnly) { "Offline mode is enabled" }
                        transfer(track, pinned)
                    }
                } catch (cancelled: CancellationException) {
                    update(OfflineSong(track.id, track.title, pinned, "failed", message = "Download cancelled. Tap Retry to restart."))
                    throw cancelled
                } catch (_: Exception) {
                    update(OfflineSong(track.id, track.title, pinned, "failed",
                        message = "Download failed. Check connection, source availability and storage, then Retry."))
                } finally {
                    calls.remove(track.id)?.cancel()
                    File(directory, offlineFileName(track.id) + ".part").delete()
                }
            }
            jobs[track.id] = job
            job.invokeOnCompletion { synchronized(lock) {
                jobs.remove(track.id, job)
                val entry = songs.value.find { it.trackId == track.id }
                if (entry?.status in setOf("queued", "downloading")) update(entry!!.copy(status = "failed", message = "Download interrupted. Tap Retry to restart."))
            }
            }
            job.start()
        }
    }

    fun considerAutomaticCache(track: Track, qualifiedPlays: Int) {
        if (!settings.value.autoCache || settings.value.offlineOnly || qualifiedPlays < 3 ||
            track.provider !in setOf("youtube", "commons") || songs.value.any { it.trackId == track.id }) return
        val connection = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connection.getNetworkCapabilities(connection.activeNetwork)
        val unmetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (shouldAutoCache(settings.value.autoCache, settings.value.offlineOnly, unmetered, qualifiedPlays) &&
            track.provider in setOf("youtube", "commons") && songs.value.none { it.trackId == track.id }) {
            try { download(track, pinned = false) }
            catch (_: IllegalStateException) { /* Settings or removal changed after the eligibility check. */ }
        }
    }

    suspend fun cancel(trackId: String) {
        calls[trackId]?.cancel()
        jobs[trackId]?.cancelAndJoin()
    }

    suspend fun remove(trackId: String) = removeAudio(trackId, automaticOnly = false)

    private suspend fun removeAudio(trackId: String, automaticOnly: Boolean) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (trackId in removing || automaticOnly && songs.value.any { it.trackId == trackId && it.pinned }) return@withContext
            removing.add(trackId)
        }
        try {
            cancel(trackId)
            synchronized(lock) {
                check(!File(directory, offlineFileName(trackId)).exists() || File(directory, offlineFileName(trackId)).delete()) {
                    "Could not remove saved audio"
                }
                mutableSongs.value = songs.value.filterNot { it.trackId == trackId }
                persist()
            }
        } finally { synchronized(lock) { removing.remove(trackId) } }
    }

    suspend fun clearAutomaticCache() {
        songs.value.filterNot(OfflineSong::pinned).forEach { removeAudio(it.trackId, automaticOnly = true) }
    }

    private suspend fun transfer(track: Track, pinned: Boolean) {
        if (!pinned) {
            val connection = context.getSystemService(ConnectivityManager::class.java)
            check(!connection.isActiveNetworkMetered) { "Automatic caching waits for unmetered internet" }
        }
        val source = (if (track.provider == "youtube") youtube.resolve(track) else commons.resolve(track))
            ?: throw IOException("No authorized audio source")
        val url = source.uri.toHttpUrlOrNull() ?: throw IOException("Invalid audio URL")
        check(url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            (if (track.provider == "youtube") url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com")
            else url.host == "upload.wikimedia.org")) { "Unsupported audio host" }
        val request = Request.Builder().url(url).apply { source.headers.forEach { (key, value) -> header(key, value) } }.build()
        val call = client.newCall(request)
        calls[track.id] = call
        currentCoroutineContext().ensureActive()
        update(OfflineSong(track.id, track.title, pinned, "downloading"))
        val partial = File(directory, offlineFileName(track.id) + ".part")
        call.execute().use { response ->
            check(response.code == 200) { "Audio download rejected" }
            val body = response.body ?: throw IOException("Empty audio")
            val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
            check(mime.startsWith("audio/") || mime in setOf("application/ogg", "video/webm", "video/mp4", "application/octet-stream")) { "Unsupported audio response" }
            val declared = body.contentLength()
            check(declared <= MAX_SONG_BYTES) { "Recording exceeds song storage limit" }
            makeRoom(pinned, if (declared > 0) declared else MAX_SONG_BYTES)
            var count = 0L
            var lastProgress = 0L
            fun progress() = update(OfflineSong(track.id, track.title, pinned, "downloading",
                receivedBytes = count, totalBytes = declared.takeIf { it > 0 }), durable = false)
            progress()
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        check(!settings.value.offlineOnly && (pinned || settings.value.autoCache)) { "Downloads disabled" }
                        if (!pinned) check(!context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered) {
                            "Automatic caching stopped on metered connection"
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        count += read
                        check(count <= MAX_SONG_BYTES && directory.usableSpace >= FREE_SPACE_RESERVE + read) { "Storage limit reached" }
                        output.write(buffer, 0, read)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 250) { progress(); lastProgress = now }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            validateDownloadSize(count, declared, directory.usableSpace)
            progress()
            val digest = audioDigest(partial)
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                val used = songs.value.filter { it.trackId != track.id && it.pinned == pinned && it.status == "saved" }.sumOf(OfflineSong::bytes)
                check(used + count <= if (pinned) DOWNLOAD_BUDGET else CACHE_BUDGET) { "Audio storage budget reached" }
                check(partial.renameTo(File(directory, offlineFileName(track.id)))) { "Could not save recording" }
                update(OfflineSong(track.id, track.title, pinned, "saved", count, System.currentTimeMillis(), sha256 = digest))
            }
        }
    }

    private suspend fun makeRoom(pinned: Boolean, expected: Long) {
        val budget = if (pinned) DOWNLOAD_BUDGET else CACHE_BUDGET
        var used = songs.value.filter { it.pinned == pinned && it.status == "saved" }.sumOf(OfflineSong::bytes)
        if (!pinned) {
            for (old in songs.value.filter { !it.pinned && it.status == "saved" }.sortedBy(OfflineSong::savedAt)) {
                if (used + expected <= budget) break
                removeAudio(old.trackId, automaticOnly = true)
                used -= old.bytes
            }
        }
        check(used + expected <= budget && directory.usableSpace >= expected + FREE_SPACE_RESERVE) { "Storage budget reached; remove some downloads" }
    }

    private fun update(song: OfflineSong, durable: Boolean = true) = synchronized(lock) {
        mutableSongs.value = if (songs.value.any { it.trackId == song.trackId }) songs.value.map { if (it.trackId == song.trackId) song else it } else songs.value + song
        if (durable) persist()
    }

    private fun persist() {
        val array = JSONArray()
        songs.value.forEach { song -> array.put(JSONObject().put("id", song.trackId).put("title", song.title)
            .put("pinned", song.pinned).put("status", song.status).put("bytes", song.bytes).put("saved", song.savedAt).put("sha256", song.sha256)) }
        preferences.edit().putString("manifest", array.toString()).apply()
    }

    private fun readManifest(): List<OfflineSong> = try {
        val array = JSONArray(preferences.getString("manifest", "[]"))
        List(array.length()) { index ->
            val entry = array.getJSONObject(index)
            val complete = entry.getString("status") == "saved"
            OfflineSong(entry.getString("id"), entry.getString("title"), entry.getBoolean("pinned"),
                if (complete) "saved" else "failed", entry.optLong("bytes"), entry.optLong("saved"),
                if (complete) null else "Download interrupted. Tap Retry to restart.", entry.optString("sha256"))
        }
    } catch (_: Exception) { emptyList() }
}
