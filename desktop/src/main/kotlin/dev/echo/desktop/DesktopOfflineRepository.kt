package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.core.PlayableSource
import dev.echo.data.offline.*
import dev.echo.data.provider.YouTubeProvider
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

enum class DesktopDownloadStatus { QUEUED, DOWNLOADING, COMPLETE, RETRY }
data class DesktopDownload(val track: Track, val status: DesktopDownloadStatus, val receivedBytes: Long = 0,
    val totalBytes: Long? = null, val sha256: String = "", val message: String? = null)
data class DesktopOfflineState(val entries: List<DesktopDownload> = emptyList(), val offlineOnly: Boolean = false,
    val error: String? = null)

/** Device-local copies only; expiring delivery URLs never enter the manifest. */
class DesktopOfflineRepository internal constructor(
    private val root: Path, private val client: OkHttpClient,
    private val resolve: suspend (Track) -> PlayableSource?,
) : Closeable {
    constructor(provider: YouTubeProvider,
        root: Path = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "EchoMusic", "offline-audio"),
    ) : this(root, OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES).build(), provider::resolve)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(1)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, okhttp3.Call>()
    private val removing = mutableSetOf<String>()
    private var closed = false
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<DesktopOfflineState> = mutableState

    init {
        Files.createDirectories(root)
        if (state.value.error == null) root.toFile().listFiles()?.filter { it.name.endsWith(".part") ||
            it.name.endsWith(".audio") && state.value.entries.none { e -> e.status == DesktopDownloadStatus.COMPLETE && offlineFileName(e.track.id) == it.name } }
            ?.forEach(File::delete)
    }
    private fun file(id: String) = root.resolve(offlineFileName(id)).toFile()
    private fun update(entry: DesktopDownload) = synchronized(lock) {
        try { persist(state.value.copy(entries = state.value.entries.filterNot { it.track.id == entry.track.id } + entry)) }
        catch (failure: Exception) {
            mutableState.value = state.value.copy(error = "Offline storage could not be saved. Free space or check file access, then restart Echo.")
            throw failure
        }
    }
    private fun persist(next: DesktopOfflineState) {
        check(next.error == null) { next.error ?: "Offline storage unavailable" }
        Files.createDirectories(root)
        val entries = JSONArray()
        next.entries.forEach { e -> entries.put(JSONObject().put("id", e.track.id).put("title", e.track.title)
            .put("artist", e.track.artist).put("duration", e.track.durationMs).put("status", e.status.name)
            .put("bytes", e.receivedBytes).put("sha256", e.sha256)) }
        val bytes = JSONObject().put("version", 1).put("offline", next.offlineOnly).put("entries", entries).toString().toByteArray()
        require(bytes.size <= 2 * 1024 * 1024) { "Offline manifest is full. Remove some entries first." }
        val temp = root.resolve("manifest.json.tmp")
        FileOutputStream(temp.toFile()).use { it.write(bytes); it.fd.sync() }
        Files.move(temp, root.resolve("manifest.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        mutableState.value = next
    }
    private fun read(): DesktopOfflineState = try {
        val manifest = root.resolve("manifest.json")
        if (!Files.exists(manifest)) DesktopOfflineState() else {
            require(Files.size(manifest) <= 2 * 1024 * 1024)
            val json = JSONObject(Files.readString(manifest)); require(json.getInt("version") == 1)
            val array = json.getJSONArray("entries"); require(array.length() <= 20_000)
            val entries = (0 until array.length()).map { index ->
                val e = array.getJSONObject(index); val id = e.getString("id")
                require(id.matches(Regex("youtube:[A-Za-z0-9_-]{11}")))
                val complete = e.getString("status") == "COMPLETE"
                DesktopDownload(Track(id, e.getString("title").take(2000), e.getString("artist").take(2000),
                    durationMs = e.optLong("duration").coerceAtLeast(0), provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=${id.removePrefix("youtube:")}"),
                    if (complete) DesktopDownloadStatus.COMPLETE else DesktopDownloadStatus.RETRY,
                    e.optLong("bytes").coerceAtLeast(0), sha256 = e.optString("sha256"),
                    message = if (complete) null else "Interrupted download. Retry to restart.")
            }
            require(entries.map { it.track.id }.distinct().size == entries.size)
            DesktopOfflineState(entries, json.optBoolean("offline"))
        }
    } catch (_: Exception) { DesktopOfflineState(error = "Offline manifest could not be read. Existing audio was preserved.") }

    fun download(track: Track): Unit = synchronized(lock) {
        check(!closed && state.value.error == null) { "Offline storage unavailable" }
        check(!state.value.offlineOnly) { "Turn off Offline mode to download" }
        require(track.provider == "youtube" && track.id.matches(Regex("youtube:[A-Za-z0-9_-]{11}"))) { "Match this song to a playable recording first. Local files are already offline." }
        require(track.title.length <= 2000 && track.artist.length <= 2000) { "Song metadata is too large" }
        check(track.id !in removing) { "Wait for this download to be removed" }
        if (jobs.containsKey(track.id) || state.value.entries.any { it.track.id == track.id && it.status == DesktopDownloadStatus.COMPLETE }) return@synchronized
        check(state.value.entries.size < 20_000 || state.value.entries.any { it.track.id == track.id }) { "Too many downloads" }
        update(DesktopDownload(track, DesktopDownloadStatus.QUEUED))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { gate.withPermit { transfer(track) } }
            catch (cancelled: CancellationException) {
                runCatching { update(DesktopDownload(track, DesktopDownloadStatus.RETRY, message = "Download cancelled. Retry to restart.")) }
                throw cancelled
            } catch (_: Exception) {
                runCatching { update(DesktopDownload(track, DesktopDownloadStatus.RETRY, message = "Download failed. Check connection, source and free storage, then Retry.")) }
            } finally { calls.remove(track.id)?.cancel(); File(file(track.id).path + ".part").delete() }
        }
        jobs[track.id] = job
        job.invokeOnCompletion { synchronized(lock) {
            jobs.remove(track.id, job)
            state.value.entries.find { it.track.id == track.id && it.status in setOf(DesktopDownloadStatus.QUEUED, DesktopDownloadStatus.DOWNLOADING) }
                ?.let { runCatching { update(it.copy(status = DesktopDownloadStatus.RETRY, message = "Download interrupted. Retry to restart.")) } }
        } }
        job.start()
    }
    private suspend fun transfer(track: Track) {
        check(!state.value.offlineOnly)
        val source = resolve(track) ?: error("No audio source")
        currentCoroutineContext().ensureActive(); check(!state.value.offlineOnly)
        require(validDesktopYouTubeSource(source))
        val request = Request.Builder().url(source.uri).apply { source.headers.forEach { (k, v) -> header(k, v) } }.build()
        val call = client.newCall(request); calls[track.id] = call
        currentCoroutineContext().ensureActive()
        val used = state.value.entries.filter { it.status == DesktopDownloadStatus.COMPLETE }.sumOf { it.receivedBytes }
        call.execute().use { response ->
            require(response.code == 200)
            val body = response.body ?: error("Missing audio")
            val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
            require(mime in setOf("audio/webm", "audio/mp4", "audio/mpeg", "audio/ogg", "audio/aac", "audio/x-m4a"))
            val total = body.contentLength(); require(total < 0 || total in 1..MAX_SONG_BYTES)
            require(total < 0 || used + total <= DOWNLOAD_BUDGET)
            val partial = File(file(track.id).path + ".part")
            var received = 0L; var reported = 0L
            FileOutputStream(partial).use { output -> body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive(); check(!state.value.offlineOnly)
                    val count = input.read(buffer); if (count == -1) break
                    received += count
                    require(received <= MAX_SONG_BYTES && used + received <= DOWNLOAD_BUDGET && root.toFile().usableSpace >= FREE_SPACE_RESERVE)
                    output.write(buffer, 0, count)
                    if (received - reported >= 256 * 1024) {
                        reported = received
                        synchronized(lock) { mutableState.value = state.value.copy(entries = state.value.entries.map {
                            if (it.track.id == track.id) it.copy(status = DesktopDownloadStatus.DOWNLOADING, receivedBytes = received, totalBytes = total.takeIf { n -> n > 0 }) else it
                        }) }
                    }
                }
                output.fd.sync()
            } }
            validateDownloadSize(received, total, root.toFile().usableSpace)
            val digest = audioDigest(partial)
            currentCoroutineContext().ensureActive(); check(!state.value.offlineOnly)
            Files.move(partial.toPath(), file(track.id).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            update(DesktopDownload(track, DesktopDownloadStatus.COMPLETE, received, received, digest))
        }
    }
    fun cancel(id: String) { calls[id]?.cancel(); jobs[id]?.cancel() }
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        synchronized(lock) { check(removing.add(id)) { "Removal already in progress" } }
        try {
            this@DesktopOfflineRepository.cancel(id); jobs[id]?.join()
            synchronized(lock) {
                check(!file(id).exists() || file(id).delete()) { "Audio is in use; stop playback and retry" }
                persist(state.value.copy(entries = state.value.entries.filterNot { it.track.id == id }))
            }
        } finally { synchronized(lock) { removing.remove(id) } }
    }
    suspend fun setOfflineOnly(enabled: Boolean) = withContext(Dispatchers.IO) {
        synchronized(lock) { persist(state.value.copy(offlineOnly = enabled)) }
        if (enabled) jobs.keys.toList().forEach { this@DesktopOfflineRepository.cancel(it) }
    }
    suspend fun savedFile(track: Track): File? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val entry = state.value.entries.find { it.track.id == track.id && it.status == DesktopDownloadStatus.COMPLETE } ?: return@synchronized null
            val saved = file(track.id)
            if (runCatching { validOfflineFile(saved, entry.receivedBytes, entry.sha256) }.getOrDefault(false)) saved
            else { update(entry.copy(status = DesktopDownloadStatus.RETRY, message = "Saved audio is missing or damaged. Retry download.")); null }
        }
    }
    override fun close() {
        synchronized(lock) { closed = true }
        calls.values.forEach { it.cancel() }; scope.cancel(); client.connectionPool.evictAll()
    }
}
