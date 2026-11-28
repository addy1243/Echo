package dev.echo.desktop

import dev.echo.core.PlayableSource
import dev.echo.core.Track
import dev.echo.data.offline.offlineFileName
import dev.echo.data.provider.YouTubeProvider
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

internal class StageMediaTransferGate {
    private val mutex = Mutex()

    @Volatile private var foregroundBuffering = false
    @Volatile private var fastTransfersAllowed = false
    private val activeRelays = mutableSetOf<Any>()
    @Volatile private var upstreamRelayActive = false

    fun setForegroundBuffering(buffering: Boolean) { foregroundBuffering = buffering }
    fun setFastTransfersAllowed(allowed: Boolean) { fastTransfersAllowed = allowed }
    @Synchronized fun setUpstreamRelayActive(relay: Any, active: Boolean) {
        if (active) activeRelays.add(relay) else activeRelays.remove(relay)
        upstreamRelayActive = activeRelays.isNotEmpty()
    }

    /** Charge each chunk separately: pauses, slow reads and rate changes cannot bank burst credit. */
    suspend fun paceTransfer(bytes: Int, streamingRate: Long, monotonicNanos: () -> Long,
        waitNanos: suspend (Long) -> Unit, isOpen: () -> Boolean) {
        var remainingWork = bytes.toLong() * 1_000_000_000L
        while (remainingWork > 0) {
            currentCoroutineContext().ensureActive()
            awaitTransferPermit(isOpen)
            check(isOpen()) { "Stage cache is closed" }
            val rate = if (fastTransfersAllowed && !upstreamRelayActive) maxOf(streamingRate, FAST_RATE_BYTES_PER_SECOND) else streamingRate
            val wait = minOf(25_000_000L, (remainingWork + rate - 1) / rate)
            val startedAt = monotonicNanos()
            waitNanos(wait)
            val elapsed = (monotonicNanos() - startedAt).coerceAtLeast(0)
            remainingWork -= minOf(remainingWork, elapsed.coerceAtMost(wait) * rate)
        }
    }

    /** Returns true after waiting for foreground playback to recover. */
    suspend fun awaitTransferPermit(isOpen: () -> Boolean): Boolean {
        if (!foregroundBuffering) return false
        while (foregroundBuffering) {
            check(isOpen()) { "Stage cache is closed" }
            delay(25)
        }
        check(isOpen()) { "Stage cache is closed" }
        return true
    }

    suspend fun <T> withPermit(block: suspend () -> T): T = mutex.withLock { block() }

    private companion object { const val FAST_RATE_BYTES_PER_SECOND = 2L * 1024 * 1024 }
}

/** Per-process transient media bytes for explicitly requested personal Stage playback. */
class DesktopStageMediaCache internal constructor(
    private val resolve: suspend (Track) -> PlayableSource?,
    private val client: OkHttpClient,
    tempBase: Path = Path.of(System.getProperty("java.io.tmpdir")),
    private val transferRateBytesPerSecond: Long = DEFAULT_RATE_BYTES_PER_SECOND,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val waitNanos: suspend (Long) -> Unit = { nanos -> delay((nanos + 999_999L) / 1_000_000L) },
    private val transferGate: StageMediaTransferGate = StageMediaTransferGate(),
) : Closeable {
    internal constructor(provider: YouTubeProvider, gate: StageMediaTransferGate) : this(
        provider::resolveSpeculative,
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build(),
        transferGate = gate,
    )

    constructor(provider: YouTubeProvider) : this(provider, StageMediaTransferGate())

    private val root = Files.createTempDirectory(Files.createDirectories(tempBase), "echo-stage-")
    private val lock = Any()
    private val pins = mutableMapOf<String, Int>()
    private val keysByFile = mutableMapOf<String, String>()
    private val calls = mutableMapOf<String, okhttp3.Call>()
    private val captures = mutableMapOf<String, DesktopStageStreamCapture>()
    private val publishedCapturePaths = mutableMapOf<String, Path>()
    private val activePrepareIds = mutableSetOf<String>()
    private val reservedBytesById = mutableMapOf<String, Long>()
    private var reservedBytes = 0L
    private var activeDownloadBytes = 0L
    @Volatile private var closed = false

    init { require(transferRateBytesPerSecond > 0) }

    internal fun setForegroundBuffering(buffering: Boolean) = transferGate.setForegroundBuffering(buffering)
    internal fun setFastTransfersAllowed(allowed: Boolean) = transferGate.setFastTransfersAllowed(allowed)
    internal fun setUpstreamRelayActive(relay: Any, active: Boolean) = transferGate.setUpstreamRelayActive(relay, active)

    suspend fun prepare(track: Track): CachedStageFile = prepare(track) { }

    @OptIn(InternalCoroutinesApi::class)
    internal suspend fun prepare(track: Track, onProgress: (StageCacheProgress) -> Unit): CachedStageFile {
        require(track.provider == "youtube" && track.id.matches(Regex("youtube:[A-Za-z0-9_-]{11}"))) { "Unsupported Stage recording" }
        val alreadyCached = synchronized(lock) { !closed && file(track.id).isFile }
        if (!alreadyCached) checkCaptureState(track.id)
        return transferGate.withPermit {
        check(!closed) { "Stage cache is closed" }
        require(track.provider == "youtube" && track.id.matches(Regex("youtube:[A-Za-z0-9_-]{11}"))) { "Unsupported Stage recording" }
        val target = file(track.id)
        val cached = synchronized(lock) {
            if (!target.isFile) null else {
                keysByFile[target.name] = track.id
                pinLocked(track.id, target)
            }
        }
        if (cached != null) {
            reportStageCacheProgress(onProgress, StageCacheProgress(StageBufferTransferPhase.READY, target.length(), target.length()))
            return@withPermit cached
        }
        checkCaptureState(track.id)
        transferGate.awaitTransferPermit { !closed }
        val latestCached = synchronized(lock) {
            check(!closed) { "Stage cache is closed" }
            file(track.id).takeIf { it.isFile }?.let { keysByFile[it.name] = track.id; pinLocked(track.id, it) }
        }
        if (latestCached != null) {
            reportStageCacheProgress(onProgress, StageCacheProgress(StageBufferTransferPhase.READY, latestCached.file.length(), latestCached.file.length()))
            return@withPermit latestCached
        }
        synchronized(lock) {
            check(!closed) { "Stage cache is closed" }
            when (captures[track.id]?.status) {
                DesktopStageStreamCapture.Status.CAPTURING -> throw StageStreamCapturePending(track.id)
                DesktopStageStreamCapture.Status.DISCARDED -> throw StageStreamCaptureDiscarded(track.id)
                else -> Unit
            }
            check(track.id !in activePrepareIds) { "Stage media is already being prepared" }
            activePrepareIds += track.id
        }
        try {
        reportStageCacheProgress(onProgress, StageCacheProgress(StageBufferTransferPhase.PREPARING))
        var received = 0L
        var activeDownloadReservation = 0L
        try {
            val source = resolve(track) ?: error("No audio source")
            currentCoroutineContext().ensureActive()
            require(validDesktopYouTubeSource(source)) { "Unsupported audio source" }
            val request = Request.Builder().url(source.uri).apply { source.headers.forEach { (key, value) -> header(key, value) } }.build()
            transferGate.awaitTransferPermit { !closed }
            val call = client.newCall(request)
            synchronized(lock) { check(!closed); calls[track.id] = call }
            val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                if (cause is CancellationException) call.cancel()
            }
            val partial = File(target.path + ".part")
            try {
            call.execute().use { response ->
                require(response.code == 200) { "Stage media request failed" }
                val body = response.body ?: error("Missing audio")
                val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
                require(mime in ALLOWED_AUDIO_MIME) { "Unsupported audio format" }
                val declared = body.contentLength()
                require(declared < 0 || declared in 1..MAX_BYTES) { "Stage buffer size limit exceeded" }
                synchronized(lock) {
                    val reserve = declared.coerceAtLeast(0)
                    val freeSpace = runCatching { Files.getFileStore(root).usableSpace }.getOrDefault(0L)
                    require(bytesOnDisk() + reservedBytes + activeDownloadBytes + reserve <= MAX_BYTES || declared < 0) {
                        "Stage buffer is full; remove unused Stage items first"
                    }
                    require(declared < 0 || freeSpace >= reserve + MIN_FREE_SPACE_BYTES) { "Not enough free disk space for Stage media" }
                    activeDownloadBytes += reserve
                    activeDownloadReservation = reserve
                }
                FileOutputStream(partial).use { output -> body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024); received = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        transferGate.awaitTransferPermit { !closed }
                        val count = input.read(buffer); if (count < 0) break
                        received += count
                        synchronized(lock) {
                            require(received <= MAX_BYTES && (declared < 0 || received <= declared)) { "Stage buffer size limit exceeded" }
                            val extra = if (declared < 0) count.toLong() else 0L
                            require(bytesOnDisk() + reservedBytes + activeDownloadBytes + extra <= MAX_BYTES) {
                                "Stage buffer is full; remove unused Stage items first"
                            }
                            if (extra > 0) { activeDownloadBytes += extra; activeDownloadReservation += extra }
                        }
                        output.write(buffer, 0, count)
                        reportStageCacheProgress(onProgress, StageCacheProgress(StageBufferTransferPhase.DOWNLOADING, received, declared.takeIf { it >= 0 }))
                        transferGate.paceTransfer(count, transferRateBytesPerSecond, monotonicNanos, waitNanos) { !closed }
                    }
                    output.fd.sync()
                    require(received > 0 && (declared < 0 || declared == received)) { "Incomplete Stage media response" }
                } }
            }
            currentCoroutineContext().ensureActive()
            val committed = synchronized(lock) {
                check(!closed)
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                keysByFile[target.name] = track.id
                publishedCapturePaths.remove(track.id)
                activeDownloadBytes -= activeDownloadReservation
                activeDownloadReservation = 0L
                pinLocked(track.id, target)
            }
            reportStageCacheProgress(onProgress, StageCacheProgress(StageBufferTransferPhase.READY, received, received))
            committed
            } finally {
                cancellation?.dispose()
                synchronized(lock) {
                    calls.remove(track.id, call)
                    activeDownloadBytes -= activeDownloadReservation
                    activeDownloadReservation = 0L
                }
                partial.delete()
                synchronized(lock) { removeRootIfEmpty() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            reportStageCacheProgress(onProgress, StageCacheProgress(StageBufferTransferPhase.FAILED, received))
            throw failure
        }
        } finally {
            synchronized(lock) { activePrepareIds.remove(track.id) }
        }
        }
    }

    fun contains(id: String): Boolean = synchronized(lock) { !closed && file(id).isFile }

    /** Captures bytes from the exact source already relayed to the desktop player. */
    internal fun beginStreamCapture(track: Track, source: PlayableSource): DesktopStageStreamCapture? = synchronized(lock) {
        check(!closed) { "Stage cache is closed" }
        require(track.provider == "youtube" && track.id.matches(Regex("youtube:[A-Za-z0-9_-]{11}")))
        require(validDesktopYouTubeSource(source))
        if (file(track.id).isFile || track.id in activePrepareIds) return@synchronized null
        if (captures[track.id]?.status == DesktopStageStreamCapture.Status.CAPTURING) return@synchronized null
        val partial = root.resolve(file(track.id).name + ".${UUID.randomUUID()}.stream.part")
        val capture = DesktopStageStreamCapture(
            expectedSource = source,
            partialFile = partial,
            reserveBytes = { size -> reserveCapture(track.id, size) },
            releaseReservation = { size -> releaseCaptureReservation(track.id, size) },
            publishComplete = { result -> publishCapture(track.id, result) },
            onDiscarded = { synchronized(lock) { removeRootIfEmpty() } },
        )
        captures[track.id] = capture
        capture
    }

    internal fun streamCaptureStatus(id: String): DesktopStageStreamCapture.Status? = synchronized(lock) { captures[id]?.status }

    /** Discards a relay tee without affecting the playback source that owns it. */
    internal fun cancelStreamCapture(id: String) = synchronized(lock) { captures[id]?.close() }

    /** Scheduler can await outside the serialized transfer gate, then retry prepare for the committed file. */
    internal suspend fun awaitStreamCapture(id: String): CompletedStageStreamCapture? = synchronized(lock) { captures[id] }?.awaitComplete()
    internal suspend fun awaitStreamCaptureSettled(id: String) { synchronized(lock) { captures[id] }?.awaitSettled() }

    internal fun clearDiscardedStreamCapture(id: String): Boolean = synchronized(lock) {
        val capture = captures[id] ?: return@synchronized true
        if (capture.status != DesktopStageStreamCapture.Status.DISCARDED || !capture.isSettled) return@synchronized false
        captures.remove(id, capture)
        true
    }

    private fun checkCaptureState(id: String) {
        when (streamCaptureStatus(id)) {
            DesktopStageStreamCapture.Status.CAPTURING -> throw StageStreamCapturePending(id)
            DesktopStageStreamCapture.Status.DISCARDED -> throw StageStreamCaptureDiscarded(id)
            else -> Unit
        }
    }

    /** Pins a prepared recording until the returned handle is closed. */
    fun pin(id: String): CachedStageFile = synchronized(lock) {
        check(!closed && file(id).isFile) { "Stage media is not prepared" }
        keysByFile[file(id).name] = id
        pinLocked(id, file(id))
    }

    /** A track-ID cache hit alone cannot prove that the relay captured these exact bytes. */
    internal fun pinStreamCapture(id: String, capture: DesktopStageStreamCapture, completed: CompletedStageStreamCapture): CachedStageFile? = synchronized(lock) {
        if (closed || captures[id] !== capture || !capture.ownsCompletion(completed) ||
            capture.status != DesktopStageStreamCapture.Status.COMPLETE || publishedCapturePaths[id] != completed.partialFile ||
            !file(id).isFile || file(id).length() != completed.sizeBytes) return@synchronized null
        pinLocked(id, file(id))
    }

    /** Removes a completed relay capture when Stage preparation was disabled before attachment. */
    internal fun discardStreamCapture(id: String, capture: DesktopStageStreamCapture, completed: CompletedStageStreamCapture) = synchronized(lock) {
        if (captures[id] !== capture || !capture.ownsCompletion(completed) || publishedCapturePaths[id] != completed.partialFile || (pins[id] ?: 0) != 0) return@synchronized
        file(id).delete()
        keysByFile.remove(file(id).name)
        publishedCapturePaths.remove(id)
    }

    private fun pinLocked(id: String, file: File): CachedStageFile {
        pins[id] = (pins[id] ?: 0) + 1
        return CachedStageFile(file) { synchronized(lock) {
            val remaining = (pins[id] ?: error("Stage pin was already released")) - 1
            if (remaining == 0) pins.remove(id) else pins[id] = remaining
            if (closed && remaining == 0) { file.delete(); keysByFile.remove(file.name) }
            removeRootIfEmpty()
        } }
    }

    /** Caller reconciles desired recordings under the same lock used by pins and transfers. */
    fun reconcile(desiredIds: Set<String>) = synchronized(lock) {
        check(!closed) { "Stage cache is closed" }
        require(desiredIds.all { it.matches(Regex("youtube:[A-Za-z0-9_-]{11}")) })
        root.toFile().listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.forEach { candidate ->
            val id = keysByFile[candidate.name]
            if (id != null && id !in desiredIds && (pins[id] ?: 0) == 0) {
                if (candidate.delete()) { keysByFile.remove(candidate.name); publishedCapturePaths.remove(id) }
            }
        }
    }

    fun cancel(id: String) = synchronized(lock) { calls[id]?.cancel() }

    private fun file(id: String) = root.resolve(offlineFileName(id).removeSuffix(".audio") + ".audio").toFile()
    private fun bytesOnDisk(): Long = root.toFile().listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sumOf { it.length() } ?: 0L

    private fun reserveCapture(id: String, size: Long): Boolean = synchronized(lock) {
        if (closed || size !in 1..MAX_BYTES || reservedBytesById.containsKey(id)) return@synchronized false
        val freeSpace = runCatching { Files.getFileStore(root).usableSpace }.getOrDefault(0L)
        if (bytesOnDisk() + reservedBytes + activeDownloadBytes + size > MAX_BYTES || freeSpace < size + MIN_FREE_SPACE_BYTES) return@synchronized false
        reservedBytesById[id] = size
        reservedBytes += size
        true
    }

    private fun releaseCaptureReservation(id: String, size: Long) = synchronized(lock) {
        val reserved = reservedBytesById[id] ?: return@synchronized
        if (reserved == size) {
            reservedBytesById.remove(id)
            reservedBytes -= reserved
        }
    }

    private fun publishCapture(id: String, capture: CompletedStageStreamCapture) = synchronized(lock) {
        check(!closed) { "Stage cache is closed" }
        check(reservedBytesById[id] == capture.sizeBytes) { "Stage capture reservation was lost" }
        val target = file(id)
        if (target.exists()) {
            Files.deleteIfExists(capture.partialFile)
        } else {
            check(capture.partialFile.toFile().length() == capture.sizeBytes) { "Incomplete Stage capture" }
            Files.move(capture.partialFile, target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            keysByFile[target.name] = id
            publishedCapturePaths[id] = capture.partialFile
        }
        reservedBytesById.remove(id)
        reservedBytes -= capture.sizeBytes
    }
    private fun removeRootIfEmpty() {
        if (closed && root.toFile().listFiles().isNullOrEmpty()) runCatching { Files.deleteIfExists(root) }
    }

    override fun close() {
        val activeCaptures = synchronized(lock) {
            if (closed) return
            closed = true
            calls.values.forEach { it.cancel() }
            val capturesToClose = captures.values.toList()
            root.toFile().listFiles()?.forEach { entry ->
                val id = keysByFile[entry.name.removeSuffix(".part")]
                if (!entry.name.endsWith(".part") && (id == null || (pins[id] ?: 0) == 0)) {
                    entry.delete(); keysByFile.remove(entry.name)
                }
            }
            capturesToClose
        }
        activeCaptures.forEach { it.close() }
        synchronized(lock) { removeRootIfEmpty() }
        client.connectionPool.evictAll()
    }

    private companion object {
        const val MAX_BYTES = 100L * 1024 * 1024
        const val MIN_FREE_SPACE_BYTES = 32L * 1024 * 1024
        const val DEFAULT_RATE_BYTES_PER_SECOND = 128L * 1024
        val ALLOWED_AUDIO_MIME = setOf("audio/webm", "audio/mp4", "audio/mpeg", "audio/ogg", "audio/aac", "audio/x-m4a")
    }
}

internal class StageStreamCapturePending(val trackId: String) : IllegalStateException("Live Stage stream capture is still active")
internal class StageStreamCaptureDiscarded(val trackId: String) : IllegalStateException("Live Stage stream capture was incomplete")

class CachedStageFile internal constructor(val file: File, private val release: Closeable) : Closeable {
    private val released = AtomicBoolean(false)
    override fun close() { if (released.compareAndSet(false, true)) release.close() }
}
