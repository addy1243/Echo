package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.data.provider.YouTubeProvider
import dev.echo.data.provider.YouTubeVideoSelection
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

/** Short-lived, Stage-only video bytes. These files are never part of downloads or saved library data. */
internal class DesktopStageVideoCache internal constructor(
    private val resolve: suspend (Track) -> YouTubeVideoSelection?,
    private val client: OkHttpClient,
    private val transferGate: StageMediaTransferGate,
    tempBase: Path = Path.of(System.getProperty("java.io.tmpdir")),
    private val transferRateBytesPerSecond: Long = DEFAULT_RATE_BYTES_PER_SECOND,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val waitNanos: suspend (Long) -> Unit = { nanos -> delay((nanos + 999_999L) / 1_000_000L) },
) : Closeable {
    constructor(provider: YouTubeProvider, transferGate: StageMediaTransferGate) : this(
        provider::resolveVideoSelectionSpeculative,
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build(),
        transferGate,
    )

    private val root = Files.createTempDirectory(Files.createDirectories(tempBase), "echo-stage-video-")
    private val lock = Any()
    private val pins = mutableMapOf<String, Int>()
    private val needsAudio = mutableMapOf<String, Boolean>()
    private val idsByFile = mutableMapOf<String, String>()
    private val calls = mutableMapOf<String, okhttp3.Call>()
    private val activePrepareIds = mutableSetOf<String>()
    private val captures = mutableMapOf<String, DesktopStageStreamCapture>()
    private val captureReservations = mutableMapOf<Path, Long>()
    private val publishedCapturePaths = mutableMapOf<String, Path>()
    private var reservedCaptureBytes = 0L
    private var activeTransferBytes = 0L
    @Volatile private var closed = false

    init { require(transferRateBytesPerSecond > 0) }

    internal fun setForegroundBuffering(buffering: Boolean) = transferGate.setForegroundBuffering(buffering)

    /** Returns null when no safe/usable video representation is available; audio playback remains independent. */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun prepare(track: Track, onProgress: (StageCacheProgress) -> Unit = {}): CachedStageVideo? = transferGate.withPermit {
        check(!closed) { "Stage video cache is closed" }
        require(track.provider == "youtube" && track.id.matches(Regex("youtube:[A-Za-z0-9_-]{11}"))) {
            "Unsupported Stage recording"
        }
        val target = file(track.id)
        val cached = synchronized(lock) {
            if (target.isFile && target.length() in 1..MAX_VIDEO_BYTES) {
                pinLocked(track.id, target)
            } else {
                if (target.exists()) target.delete()
                null
            }
        }
        if (cached != null) {
            reportStageCacheProgress(onProgress, StageCacheProgress(StageBufferTransferPhase.READY, target.length(), target.length()))
            return@withPermit cached
        }
        synchronized(lock) {
            check(track.id !in activePrepareIds) { "Stage video is already being prepared" }
            check(captures[track.id]?.status != DesktopStageStreamCapture.Status.CAPTURING) { "Live Stage video capture is still active" }
            activePrepareIds += track.id
        }
        try {
        transferGate.awaitTransferPermit { !closed }
        reportStageCacheProgress(onProgress, StageCacheProgress(StageBufferTransferPhase.PREPARING))
        var resolverFailed = false
        val selection = try {
            resolve(track)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            resolverFailed = true
            null
        } ?: run {
            val phase = if (resolverFailed) StageBufferTransferPhase.FAILED else StageBufferTransferPhase.UNAVAILABLE
            reportStageCacheProgress(onProgress, StageCacheProgress(phase))
            return@withPermit null
        }
        val candidates = listOf(
            selection.source to selection.requiresAudioSlave,
            selection.muxedFallback to false,
        ).filter { (source, _) -> source != null }
            .map { (source, requiresAudioSlave) -> source!! to requiresAudioSlave }
            .distinctBy { it.first.uri }
            .filter { (source, _) -> validStageVideoSource(source) }
        if (candidates.isEmpty()) {
            reportStageCacheProgress(onProgress, StageCacheProgress(StageBufferTransferPhase.UNAVAILABLE))
            return@withPermit null
        }
        var attemptedTransfer = false
        var lastReceived = 0L
        for ((source, requiresAudioSlave) in candidates) {
            currentCoroutineContext().ensureActive()
            if (source.expiresAt?.let { it <= System.currentTimeMillis() + MIN_REMAINING_LIFETIME_MS } == true) continue
            val call = client.newCall(Request.Builder().url(source.uri).apply {
                source.headers.forEach { (name, value) -> header(name, value) }
            }.build())
            transferGate.awaitTransferPermit { !closed }
            synchronized(lock) { check(!closed); calls[track.id] = call }
            val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                if (cause is CancellationException) call.cancel()
            }
            val partial = File(target.path + ".part")
            var completed = false
            try {
                attemptedTransfer = true
                lastReceived = 0
                call.execute().use { response ->
                    if (response.code != 200) return@use
                    val body = response.body ?: return@use
                    val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }?.lowercase()
                    if (mime !in ALLOWED_VIDEO_MIME) return@use
                    val declared = body.contentLength()
                    if (declared == 0L || declared > MAX_VIDEO_BYTES) return@use
                    var validBody = true
                    val usedBefore = synchronized(lock) { bytesOnDisk() + reservedCaptureBytes }
                    if (usedBefore + declared.coerceAtLeast(0) > MAX_VIDEO_BYTES && declared >= 0) return@use
                    FileOutputStream(partial).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var received = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                transferGate.awaitTransferPermit { !closed }
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                val withinQuota = synchronized(lock) {
                                    if (bytesOnDisk() + reservedCaptureBytes + received > MAX_VIDEO_BYTES || received > MAX_VIDEO_BYTES) false
                                    else { activeTransferBytes = received; true }
                                }
                                if (!withinQuota) {
                                    validBody = false
                                    break
                                }
                                output.write(buffer, 0, count)
                                lastReceived = received
                                reportStageCacheProgress(onProgress, StageCacheProgress(StageBufferTransferPhase.DOWNLOADING, received, declared.takeIf { it >= 0 }))
                                transferGate.paceTransfer(count, transferRateBytesPerSecond, monotonicNanos, waitNanos) { !closed }
                            }
                            output.fd.sync()
                            if (received == 0L || declared >= 0 && received != declared) validBody = false
                        }
                    }
                    if (!validBody) return@use
                    currentCoroutineContext().ensureActive()
                    val committed = synchronized(lock) {
                        check(!closed)
                        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        completed = true
                        idsByFile[target.name] = track.id
                        needsAudio[track.id] = requiresAudioSlave
                        pinLocked(track.id, target)
                    }
                    reportStageCacheProgress(onProgress, StageCacheProgress(StageBufferTransferPhase.READY, target.length(), target.length()))
                    return@withPermit committed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Video is optional; continue to the provider-selected muxed fallback if present.
            } finally {
                cancellation?.dispose()
                synchronized(lock) { calls.remove(track.id, call); activeTransferBytes = 0L }
                partial.delete()
            }
            currentCoroutineContext().ensureActive()
            if (completed) break
        }
        reportStageCacheProgress(onProgress, StageCacheProgress(if (attemptedTransfer) StageBufferTransferPhase.FAILED else StageBufferTransferPhase.UNAVAILABLE, lastReceived))
        null
        } finally { synchronized(lock) { activePrepareIds.remove(track.id) } }
    }

    fun contains(id: String): Boolean = synchronized(lock) {
        !closed && id in needsAudio && file(id).let { it.isFile && it.length() in 1..MAX_VIDEO_BYTES }
    }

    fun pin(id: String): CachedStageVideo = synchronized(lock) {
        check(!closed && file(id).isFile && id in needsAudio) { "Stage video is not prepared" }
        pinLocked(id, file(id))
    }

    fun reconcile(desiredIds: Set<String>) = synchronized(lock) {
        check(!closed)
        idsByFile.toMap().forEach { (name, id) ->
            if (id !in desiredIds && (pins[id] ?: 0) == 0 && root.resolve(name).toFile().delete()) {
                idsByFile.remove(name)
                needsAudio.remove(id)
                publishedCapturePaths.remove(id)
            }
        }
    }

    fun cancel(id: String) = synchronized(lock) { calls[id]?.cancel() }

    /** Tee the exact video representation already being relayed to VLC; capture never delays playback. */
    internal fun beginStreamCapture(track: Track, source: dev.echo.core.PlayableSource, requiresAudioSlave: Boolean = false): DesktopStageStreamCapture? = synchronized(lock) {
        check(!closed) { "Stage video cache is closed" }
        require(track.provider == "youtube" && track.id.matches(Regex("youtube:[A-Za-z0-9_-]{11}")))
        require(validStageVideoSource(source))
        if (file(track.id).isFile || track.id in activePrepareIds || captures[track.id]?.status == DesktopStageStreamCapture.Status.CAPTURING) return@synchronized null
        needsAudio[track.id] = requiresAudioSlave
        val partial = root.resolve(file(track.id).name + ".${UUID.randomUUID()}.stream.part")
        lateinit var capture: DesktopStageStreamCapture
        capture = DesktopStageStreamCapture(
            expectedSource = source,
            partialFile = partial,
            reserveBytes = { size -> reserveCapture(track.id, partial, size) },
            releaseReservation = { size -> releaseCaptureReservation(partial, size) },
            publishComplete = { result -> publishCapture(track.id, capture, result) },
            maxBytes = MAX_VIDEO_BYTES,
            allowedMimeTypes = ALLOWED_VIDEO_MIME,
        )
        captures[track.id] = capture
        capture
    }

    internal fun streamCaptureStatus(id: String): DesktopStageStreamCapture.Status? = synchronized(lock) { captures[id]?.status }
    /** Discards a relay tee without affecting the playback source that owns it. */
    internal fun cancelStreamCapture(id: String) = synchronized(lock) { captures[id]?.close() }
    internal suspend fun awaitStreamCapture(id: String): CompletedStageStreamCapture? =
        synchronized(lock) { captures[id] }?.let { runCatching { it.awaitComplete() }.getOrNull() }
    internal suspend fun awaitStreamCaptureSettled(id: String) { synchronized(lock) { captures[id] }?.awaitSettled() }
    internal fun clearDiscardedStreamCapture(id: String): Boolean = synchronized(lock) {
        val capture = captures[id] ?: return@synchronized true
        if (capture.status != DesktopStageStreamCapture.Status.DISCARDED || !capture.isSettled) return@synchronized false
        captures.remove(id, capture)
        if (!file(id).exists()) needsAudio.remove(id)
        true
    }

    /** A same-ID file is accepted only when it is the exact completed relay capture. */
    internal fun pinStreamCapture(id: String, capture: DesktopStageStreamCapture, completed: CompletedStageStreamCapture): CachedStageVideo? = synchronized(lock) {
        if (closed || captures[id] !== capture || !capture.ownsCompletion(completed) ||
            capture.status != DesktopStageStreamCapture.Status.COMPLETE || publishedCapturePaths[id] != completed.partialFile ||
            !file(id).isFile || file(id).length() != completed.sizeBytes) return@synchronized null
        pinLocked(id, file(id))
    }

    /** Removes a completed relay capture when Stage preparation was disabled before attachment. */
    internal fun discardStreamCapture(id: String, capture: DesktopStageStreamCapture, completed: CompletedStageStreamCapture) = synchronized(lock) {
        if (captures[id] !== capture || !capture.ownsCompletion(completed) || publishedCapturePaths[id] != completed.partialFile || (pins[id] ?: 0) != 0) return@synchronized
        file(id).delete()
        idsByFile.remove(file(id).name)
        needsAudio.remove(id)
        publishedCapturePaths.remove(id)
    }

    private fun reserveCapture(id: String, partial: Path, size: Long): Boolean = synchronized(lock) {
        if (closed || captures[id]?.let { it.status == DesktopStageStreamCapture.Status.CAPTURING } != true || size !in 1..MAX_VIDEO_BYTES || partial in captureReservations) return@synchronized false
        val free = runCatching { Files.getFileStore(root).usableSpace }.getOrDefault(0L)
        if (bytesOnDisk() + reservedCaptureBytes + activeTransferBytes + size > MAX_VIDEO_BYTES || free < size + MIN_FREE_SPACE_BYTES) return@synchronized false
        captureReservations[partial] = size
        reservedCaptureBytes += size
        true
    }

    private fun releaseCaptureReservation(partial: Path, size: Long) = synchronized(lock) {
        val reserved = captureReservations[partial] ?: return@synchronized
        if (reserved == size) { captureReservations.remove(partial); reservedCaptureBytes -= reserved }
    }

    private fun publishCapture(id: String, capture: DesktopStageStreamCapture, result: CompletedStageStreamCapture) = synchronized(lock) {
        check(!closed && captures[id] === capture) { "Stage video capture is no longer current" }
        check(captureReservations[result.partialFile] == result.sizeBytes) { "Stage video capture reservation was lost" }
        val target = file(id)
        check(result.mimeType in ALLOWED_VIDEO_MIME && result.partialFile.toFile().length() == result.sizeBytes)
        if (target.exists()) Files.deleteIfExists(result.partialFile)
        else {
            Files.move(result.partialFile, target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            idsByFile[target.name] = id
            // requiresAudioSlave was registered when the source was matched to the selection.
            publishedCapturePaths[id] = result.partialFile
        }
        captureReservations.remove(result.partialFile)
        reservedCaptureBytes -= result.sizeBytes
    }

    private fun pinLocked(id: String, file: File): CachedStageVideo {
        idsByFile[file.name] = id
        pins[id] = (pins[id] ?: 0) + 1
        return CachedStageVideo(file, needsAudio.getValue(id)) { synchronized(lock) {
            val remaining = (pins[id] ?: error("Stage video pin was already released")) - 1
            if (remaining == 0) pins.remove(id) else pins[id] = remaining
            if (closed && remaining == 0) file.delete()
            removeRootIfEmpty()
        } }
    }

    private fun file(id: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return root.resolve("$name.video").toFile()
    }

    private fun bytesOnDisk(): Long = root.toFile().listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sumOf { it.length() } ?: 0L

    private fun removeRootIfEmpty() {
        if (closed && root.toFile().listFiles().isNullOrEmpty()) runCatching { Files.deleteIfExists(root) }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        calls.values.forEach { it.cancel() }
        val activeCaptures = captures.values.toList()
        root.toFile().listFiles()?.forEach { entry ->
            val id = idsByFile[entry.name.removeSuffix(".part")]
            if (entry.name.endsWith(".part") || id == null || (pins[id] ?: 0) == 0) {
                entry.delete()
                idsByFile.remove(entry.name.removeSuffix(".part"))
            }
        }
        activeCaptures.forEach { it.close() }
        removeRootIfEmpty()
        client.connectionPool.evictAll()
    }

    private companion object {
        const val MAX_VIDEO_BYTES = 200L * 1024 * 1024
        const val DEFAULT_RATE_BYTES_PER_SECOND = 128L * 1024
        const val MIN_FREE_SPACE_BYTES = 32L * 1024 * 1024
        const val MIN_REMAINING_LIFETIME_MS = 30_000L
        val ALLOWED_VIDEO_MIME = setOf("video/mp4", "video/webm", "video/3gpp")
    }
}

internal class CachedStageVideo internal constructor(val file: File, val requiresAudioSlave: Boolean, private val release: Closeable) : Closeable {
    private val released = AtomicBoolean(false)
    override fun close() { if (released.compareAndSet(false, true)) release.close() }
}

private fun validStageVideoSource(source: dev.echo.core.PlayableSource): Boolean {
    if (!validDesktopYouTubeSource(source)) return false
    val mime = source.mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return mime in setOf("video/mp4", "video/webm", "video/3gpp")
}
