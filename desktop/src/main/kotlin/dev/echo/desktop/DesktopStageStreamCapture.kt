package dev.echo.desktop

import dev.echo.core.PlayableSource
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.BitSet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred

/**
 * Best-effort tee for the live desktop relay. Writes happen on a private worker; a full queue
 * invalidates only the capture and never back-pressures VLC. The cache owner supplies quota
 * reservation and the atomic publish callback so captures remain inside its Stage lifecycle.
 */
internal class DesktopStageStreamCapture(
    private val expectedSource: PlayableSource,
    private val partialFile: Path,
    private val reserveBytes: (Long) -> Boolean,
    private val releaseReservation: (Long) -> Unit,
    private val publishComplete: (CompletedStageStreamCapture) -> Unit,
    private val onDiscarded: () -> Unit = {},
    private val maxBytes: Long = MAX_CAPTURE_BYTES,
    private val queuedBytesLimit: Long = DEFAULT_QUEUED_BYTES,
    private val allowedMimeTypes: Set<String> = ALLOWED_AUDIO_MIME,
) : Closeable {
    private val queue = ArrayBlockingQueue<Event>(MAX_QUEUED_EVENTS)
    private val queuedBytes = AtomicLong()
    private val guard = Any()
    private val completed = CompletableDeferred<CompletedStageStreamCapture>()
    private val settled = CompletableDeferred<Unit>()
    private val reservationReady = CompletableDeferred<Unit>()
    private val worker = thread(name = "Echo Stage stream capture", isDaemon = true, start = false) { drain() }
    private var totalBytes: Long? = null
    private var mimeType: String? = null
    private var etag: String? = null
    private var sourceUri: String? = null
    private var reservationHeld = false
    private var active = true
    private var publishClaimed = false
    private var transferred = false
    @Volatile private var visibleStatus = Status.CAPTURING
    @Volatile private var completedResult: CompletedStageStreamCapture? = null

    internal fun ownsCompletion(result: CompletedStageStreamCapture): Boolean = completedResult === result

    init {
        require(maxBytes in 1..MAX_VIDEO_CAPTURE_BYTES && queuedBytesLimit in CHUNK_BYTES.toLong()..MAX_QUEUED_BYTES)
        require(validDesktopYouTubeSource(expectedSource))
        worker.start()
    }

    /** Starts one response segment, or returns null when this response cannot be safely captured. */
    fun beginResponse(
        requestedRange: String?,
        responseCode: Int,
        contentRange: String?,
        contentLength: Long,
        contentType: String?,
        contentEncoding: String?,
        servedSource: PlayableSource,
        responseEtag: String? = null,
    ): Segment? {
        if (!sameDesktopYouTubeRepresentation(expectedSource, servedSource)) return rejectResponse()
        if (responseEtag?.startsWith("W/", ignoreCase = true) == true) return rejectResponse()
        if (contentEncoding != null && !contentEncoding.equals("identity", true)) return rejectResponse()
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it in allowedMimeTypes } ?: return rejectResponse()
        if (mime != expectedSource.mimeType?.substringBefore(';')?.trim()?.lowercase()) return rejectResponse()
        val range = parseRange(responseCode, requestedRange, contentRange, contentLength) ?: return rejectResponse()
        synchronized(guard) {
            if (!active) return null
            val knownTotal = totalBytes
            if (knownTotal != null && knownTotal != range.total) return null.also { invalidateLocked() }
            if (mimeType != null && mimeType != mime) return null.also { invalidateLocked() }
            if (knownTotal != null && (etag == null || responseEtag != etag)) return null.also { invalidateLocked() }
            if (knownTotal != null && sourceUri != servedSource.uri && (etag == null || responseEtag != etag)) return null.also { invalidateLocked() }
            if (knownTotal == null) {
                totalBytes = range.total
                mimeType = mime
                etag = responseEtag
                sourceUri = servedSource.uri
                if (!queue.offer(Event.Initialize(range.total))) { invalidateLocked(); return null }
            }
            return Segment(range.start, range.endExclusive)
        }
    }

    inner class Segment internal constructor(private val start: Long, private val endExclusive: Long) {
        private var written = 0L
        private var ended = false

        /** The caller supplies bytes only after successfully forwarding them to the audio client. */
        fun write(buffer: ByteArray, length: Int) {
            if (ended || length <= 0 || length > buffer.size) { fail(); return }
            if (written + length > endExclusive - start) { fail(); return }
            val copy = buffer.copyOf(length)
            synchronized(guard) {
                if (!active) return
                if (queuedBytes.get() + length > queuedBytesLimit) { invalidateLocked(); return }
                queuedBytes.addAndGet(length.toLong())
                if (!queue.offer(Event.Bytes(start + written, copy))) {
                    queuedBytes.addAndGet(-length.toLong())
                    invalidateLocked()
                    return
                }
                written += length
            }
        }

        fun finish() {
            if (ended) return
            ended = true
            synchronized(guard) {
                if (!active) return
                if (written != endExclusive - start || !queue.offer(Event.SegmentFinished)) invalidateLocked()
            }
        }

        fun fail() { ended = true; invalidate() }
    }

    private fun drain() {
        var file: RandomAccessFile? = null
        val covered = BitSet()
        try {
            while (true) when (val event = queue.take()) {
                is Event.Initialize -> {
                    if (!reserveBytes(event.total)) error("Stage buffer is full or disk space is low")
                    val reserved = synchronized(guard) {
                        if (active) { reservationHeld = true; true } else false
                    }
                    if (!reserved) { releaseReservation(event.total); return }
                    file = RandomAccessFile(partialFile.toFile(), "rw").apply { setLength(event.total) }
                    reservationReady.complete(Unit)
                }
                is Event.Bytes -> {
                    val output = file ?: error("Capture was not initialized")
                    synchronized(guard) { if (!active) return }
                    val start = Math.toIntExact(event.offset)
                    val end = Math.addExact(start, event.bytes.size)
                    val existing = ByteArray(event.bytes.size)
                    output.seek(start.toLong())
                    output.readFully(existing)
                    for (index in event.bytes.indices) if (covered.get(start + index) && existing[index] != event.bytes[index]) {
                        error("Overlapping audio ranges differ")
                    }
                    output.seek(start.toLong())
                    output.write(event.bytes)
                    covered.set(start, end)
                    queuedBytes.addAndGet(-event.bytes.size.toLong())
                }
                Event.SegmentFinished -> {
                    val total = synchronized(guard) { totalBytes ?: return }
                    if (covered.nextClearBit(0).toLong() == total) {
                        val output = file ?: error("Capture file missing")
                        output.fd.sync()
                        output.close(); file = null
                        publish(result = CompletedStageStreamCapture(partialFile, total, mimeType!!, etag))
                        return
                    }
                }
                Event.Complete -> {
                    val total = synchronized(guard) { totalBytes ?: error("Capture size missing") }
                    if (covered.nextClearBit(0).toLong() != total) error("Audio stream has missing ranges")
                    val output = file ?: error("Capture file missing")
                    output.fd.sync()
                    output.close(); file = null
                    publish(result = CompletedStageStreamCapture(partialFile, total, mimeType!!, etag))
                    return
                }
                Event.Stop -> return
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            synchronized(guard) {
                // A failed owner publication must still discard and clean up the capture.
                if (publishClaimed && !transferred) { publishClaimed = false; active = true }
            }
            invalidate()
        } finally {
            runCatching { file?.close() }
            val cleanup = synchronized(guard) {
                val discarded = !transferred
                val reservation = totalBytes.takeIf { discarded && reservationHeld }
                if (discarded) reservationHeld = false
                discarded to reservation
            }
            // The live relay may still offer bytes after discard; never hold its guard during cleanup.
            if (cleanup.first) {
                runCatching { Files.deleteIfExists(partialFile) }
                cleanup.second?.let { size -> runCatching { releaseReservation(size) } }
                runCatching(onDiscarded)
            }
            settled.complete(Unit)
        }
    }

    /** Claims publication under the capture guard, then calls the owner without that guard. */
    private fun publish(result: CompletedStageStreamCapture) {
        val claimed = synchronized(guard) {
            if (!active) false else {
                active = false
                publishClaimed = true
                true
            }
        }
        if (!claimed) return
        publishComplete(result)
        synchronized(guard) {
            transferred = true
            publishClaimed = false
            visibleStatus = Status.COMPLETE
            completedResult = result
            completed.complete(result)
        }
    }

    /** Queue a completion check. It succeeds only after at least one full representation is covered. */
    fun finishCapture() = synchronized(guard) {
        if (active && !queue.offer(Event.Complete)) invalidateLocked()
    }

    fun invalidate() = synchronized(guard) { invalidateLocked() }

    private fun rejectResponse(): Segment? = synchronized(guard) { invalidateLocked(); null }

    val status: Status get() = visibleStatus

    /** Waits for complete verified coverage. Capture failure is reported without starting a fallback download. */
    suspend fun awaitComplete(): CompletedStageStreamCapture = completed.await()
    suspend fun awaitSettled() { settled.await() }
    suspend fun awaitReservation() { reservationReady.await() }
    val isSettled: Boolean get() = settled.isCompleted

    private fun invalidateLocked() {
        if (!active) return
        active = false
        visibleStatus = Status.DISCARDED
        completed.completeExceptionally(IllegalStateException("Stage stream capture was discarded"))
        reservationReady.completeExceptionally(IllegalStateException("Stage stream capture was discarded"))
        queue.clear()
        queuedBytes.set(0)
        queue.offer(Event.Stop)
    }

    override fun close() = invalidate()

    private sealed interface Event {
        data class Initialize(val total: Long) : Event
        data class Bytes(val offset: Long, val bytes: ByteArray) : Event
        data object SegmentFinished : Event
        data object Complete : Event
        data object Stop : Event
    }

    enum class Status { CAPTURING, COMPLETE, DISCARDED }

    private data class ParsedRange(val start: Long, val endExclusive: Long, val total: Long)

    private fun parseRange(code: Int, requested: String?, contentRange: String?, length: Long): ParsedRange? {
        if (code == 200 && requested == null && length in 1..maxBytes) return ParsedRange(0, length, length)
        if (code != 206 || length <= 0) return null
        val match = contentRange?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it) } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (start < 0 || end < start || total !in 1..maxBytes || end >= total || end - start + 1 != length) return null
        val request = requested?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) } ?: return null
        val requestedStart = request.groupValues[1].toLongOrNull() ?: return null
        val requestedEnd = request.groupValues[2].takeIf(String::isNotEmpty)?.toLongOrNull()
        if (requestedStart != start || requestedEnd?.let { end > it } == true) return null
        return ParsedRange(start, end + 1, total)
    }

    companion object {
        const val MAX_CAPTURE_BYTES = 100L * 1024 * 1024
        const val MAX_VIDEO_CAPTURE_BYTES = 200L * 1024 * 1024
        private const val DEFAULT_QUEUED_BYTES = 2L * 1024 * 1024
        private const val MAX_QUEUED_BYTES = 8L * 1024 * 1024
        private const val CHUNK_BYTES = 64 * 1024
        private const val MAX_QUEUED_EVENTS = 256
        private val ALLOWED_AUDIO_MIME = setOf("audio/webm", "audio/mp4", "audio/mpeg", "audio/ogg", "audio/aac", "audio/x-m4a")
    }
}

internal data class CompletedStageStreamCapture(val partialFile: Path, val sizeBytes: Long, val mimeType: String, val etag: String?)
