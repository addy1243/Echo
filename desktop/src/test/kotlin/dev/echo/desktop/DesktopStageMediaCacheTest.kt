package dev.echo.desktop

import dev.echo.core.PlayableSource
import dev.echo.core.Track
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopStageMediaCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val song = Track("youtube:abcdefghijk", "Song", "Artist", provider = "youtube")

    private fun client(contentLength: Long = 4, calls: MutableList<String> = mutableListOf(), payload: ByteArray = byteArrayOf(1, 2, 3, 4)) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            calls += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(object : ResponseBody() {
                    override fun contentType() = "audio/webm".toMediaType()
                    override fun contentLength() = contentLength
                    override fun source() = Buffer().write(payload)
                }).build()
        }.build()

    @Test fun preparePinsFixtureBytesAndExplicitReconcileRespectsActiveUse() = runBlocking {
        val tempBase = temporary.newFolder().toPath()
        val requests = mutableListOf<String>()
        val cache = DesktopStageMediaCache({ PlayableSource("https://rr1.googlevideo.com/videoplayback?token=fixture-private") }, client(calls = requests), tempBase)
        val active = cache.prepare(song)
        try {
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), active.file.readBytes())
            assertEquals(1, requests.size)
            cache.reconcile(emptySet())
            assertTrue(active.file.exists())
            val directory = active.file.parentFile.toPath()
            assertTrue(directory.startsWith(tempBase))
            assertFalse(Files.exists(directory.resolve("manifest.json")))
            cache.close()
            assertTrue("close must preserve pinned playback bytes", active.file.exists())
        } finally {
            active.close()
            active.close()
        }
        assertFalse(active.file.exists())
        assertFalse(active.file.name.contains("fixture-private"))
    }

    @Test fun liveStreamCapturePreventsDuplicatePrepareRequestAndSuppliesCachedBytes() = runBlocking {
        val requests = mutableListOf<String>()
        val cache = DesktopStageMediaCache({ PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm") }, client(calls = requests), temporary.newFolder().toPath())
        try {
            val live = cache.beginStreamCapture(song, PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm"))!!
            val pending = runCatching { cache.prepare(song) }.exceptionOrNull()
            assertTrue(pending is StageStreamCapturePending)
            assertTrue(requests.isEmpty())
            val segment = live.beginResponse(null, 200, null, 4, "audio/webm", null,
                PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm"))!!
            segment.write(byteArrayOf(1, 2, 3, 4), 4)
            segment.finish()
            withTimeout(3_000) { cache.awaitStreamCapture(song.id) }
            val cached = cache.prepare(song)
            try {
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), cached.file.readBytes())
                assertTrue(requests.isEmpty())
            } finally { cached.close() }
        } finally { cache.close() }
    }

    @Test fun discardingCompletedCapturePreservesItsPinThenRemovesUnpinnedFile() = runBlocking {
        val source = PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm")
        val cache = DesktopStageMediaCache({ source }, client(), temporary.newFolder().toPath())
        try {
            val capture = cache.beginStreamCapture(song, source)!!
            val segment = capture.beginResponse(null, 200, null, 4, "audio/webm", null, source)!!
            segment.write(byteArrayOf(1, 2, 3, 4), 4)
            segment.finish()
            val completed = withTimeout(3_000) { cache.awaitStreamCapture(song.id)!! }
            val pin = cache.pinStreamCapture(song.id, capture, completed)!!
            cache.discardStreamCapture(song.id, capture, completed)
            assertTrue(pin.file.exists())
            pin.close()
            cache.discardStreamCapture(song.id, capture, completed)
            assertFalse(cache.contains(song.id))
        } finally { cache.close() }
    }

    @Test fun streamCapturesReserveSharedQuotaAndCancellationReleasesIt() = runBlocking {
        val base = temporary.newFolder().toPath()
        val cache = DesktopStageMediaCache({ null }, client(), base)
        val firstTrack = song
        val secondTrack = song.copy(id = "youtube:bcdefghijkl")
        try {
            val first = cache.beginStreamCapture(firstTrack, PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm"))!!
            assertNotNull(first.beginResponse(null, 200, null, 65L * 1024 * 1024, "audio/webm", null,
                PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm")))
            first.awaitReservation()
            val second = cache.beginStreamCapture(secondTrack, PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm"))!!
            assertNotNull(second.beginResponse(null, 200, null, 40L * 1024 * 1024, "audio/webm", null,
                PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm")))
            assertTrue(runCatching { second.awaitReservation() }.isFailure)
            second.awaitSettled()
            first.close()
            first.awaitSettled()
            val retry = cache.beginStreamCapture(secondTrack, PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm"))!!
            assertNotNull(retry.beginResponse(null, 200, null, 40L * 1024 * 1024, "audio/webm", null,
                PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm")))
            retry.awaitReservation()
            retry.close()
            retry.awaitSettled()
        } finally { cache.close() }
    }

    @Test fun liveReservationAccountsForConcurrentStandardDownload() = runBlocking {
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val largeClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(object : ResponseBody() {
                    override fun contentType() = "audio/webm".toMediaType()
                    override fun contentLength() = 60L * 1024 * 1024
                    override fun source(): BufferedSource {
                        val data = Buffer().write(ByteArray(64 * 1024))
                        var emitted = false
                        return object : Source {
                            override fun read(sink: Buffer, byteCount: Long): Long {
                                readStarted.countDown()
                                check(releaseRead.await(3, TimeUnit.SECONDS))
                                if (emitted) return -1
                                emitted = true
                                return data.read(sink, byteCount)
                            }
                            override fun timeout() = Timeout.NONE
                            override fun close() = Unit
                        }.buffer()
                    }
                }).build()
        }.build()
        val cache = DesktopStageMediaCache({ PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm") }, largeClient, temporary.newFolder().toPath())
        val preparation = async(Dispatchers.IO) { runCatching { cache.prepare(song) } }
        try {
            assertTrue(readStarted.await(3, TimeUnit.SECONDS))
            val other = song.copy(id = "youtube:bcdefghijkl")
            val capture = cache.beginStreamCapture(other, PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm"))!!
            assertNotNull(capture.beginResponse(null, 200, null, 50L * 1024 * 1024, "audio/webm", null,
                PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm")))
            assertTrue(runCatching { capture.awaitReservation() }.isFailure)
            capture.awaitSettled()
        } finally {
            releaseRead.countDown()
            preparation.await()
            cache.close()
        }
    }

    @Test fun captureStatusDoesNotInvertCacheAndCaptureLocks() {
        val cache = DesktopStageMediaCache({ null }, client(), temporary.newFolder().toPath())
        val capture = cache.beginStreamCapture(song, PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm"))!!
        val ownerLockField = DesktopStageMediaCache::class.java.getDeclaredField("lock").apply { isAccessible = true }
        val captureGuardField = DesktopStageStreamCapture::class.java.getDeclaredField("guard").apply { isAccessible = true }
        val ownerLock = ownerLockField.get(cache)
        val captureGuard = captureGuardField.get(capture)
        val guardHeld = CountDownLatch(1)
        val producer = Thread {
            synchronized(captureGuard) {
                guardHeld.countDown()
                synchronized(ownerLock) { }
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            synchronized(ownerLock) {
                producer.start()
                assertTrue(guardHeld.await(2, TimeUnit.SECONDS))
                val status = executor.submit<DesktopStageStreamCapture.Status> { capture.status }
                assertEquals(DesktopStageStreamCapture.Status.CAPTURING, status.get(500, TimeUnit.MILLISECONDS))
            }
            producer.join(2_000)
            assertFalse(producer.isAlive)
        } finally {
            executor.shutdownNow()
            capture.close()
            runBlocking { capture.awaitSettled() }
            cache.close()
        }
    }

    @Test fun discardedCaptureCanBeClearedBeforeOrdinaryPrepareRetry() = runBlocking {
        val requests = mutableListOf<String>()
        val cache = DesktopStageMediaCache({ PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm") }, client(calls = requests), temporary.newFolder().toPath())
        try {
            val capture = cache.beginStreamCapture(song, PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm"))!!
            val segment = capture.beginResponse(null, 200, null, 4, "audio/webm", null,
                PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm"))!!
            capture.awaitReservation()
            segment.write(byteArrayOf(1, 2), 2)
            segment.finish()
            capture.close()
            capture.awaitSettled()
            assertTrue(cache.clearDiscardedStreamCapture(song.id))
            val prepared = cache.prepare(song)
            try { assertEquals(1, requests.size) } finally { prepared.close() }
        } finally { cache.close() }
    }

    @Test fun captureCannotRaceAStartedPrepareForTheSameTrack() = runBlocking {
        val resolving = CountDownLatch(1)
        val releaseResolve = CountDownLatch(1)
        val cache = DesktopStageMediaCache({
            resolving.countDown()
            check(releaseResolve.await(3, TimeUnit.SECONDS))
            PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm")
        }, client(), temporary.newFolder().toPath())
        val preparation = async(Dispatchers.IO) { runCatching { cache.prepare(song) } }
        try {
            assertTrue(resolving.await(3, TimeUnit.SECONDS))
            assertNull(cache.beginStreamCapture(song, PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm")))
        } finally {
            releaseResolve.countDown()
            preparation.await().getOrNull()?.close()
            cache.close()
        }
    }

    @Test fun oversizedAndUntrustedSourcesNeverCommitBytes() = runBlocking {
        val oversizeBase = temporary.newFolder().toPath()
        val oversized = DesktopStageMediaCache({ PlayableSource("https://rr1.googlevideo.com/videoplayback") }, client(100L * 1024 * 1024 + 1), oversizeBase)
        try {
            try { oversized.prepare(song); fail("oversized response should fail") } catch (_: IllegalArgumentException) { }
            assertTrue(oversizeBase.toFile().listFiles()!!.single().listFiles().isNullOrEmpty())
        } finally { oversized.close() }

        val calls = mutableListOf<String>()
        val untrusted = DesktopStageMediaCache({ PlayableSource("https://example.invalid/audio") }, client(calls = calls), temporary.newFolder().toPath())
        try {
            try { untrusted.prepare(song); fail("untrusted delivery must fail") } catch (_: IllegalArgumentException) { }
            assertTrue(calls.isEmpty())
        } finally { untrusted.close() }
    }

    @Test fun repeatedPrepareReusesBytesAndEachLeasePinsIndependently() = runBlocking {
        val tempBase = temporary.newFolder().toPath()
        val requests = mutableListOf<String>()
        val cache = DesktopStageMediaCache({ PlayableSource("https://rr1.googlevideo.com/videoplayback") }, client(calls = requests), tempBase)
        val first = cache.prepare(song)
        val second = cache.prepare(song)
        try {
            assertEquals(first.file, second.file)
            assertEquals(1, requests.size)
            first.close()
            cache.reconcile(emptySet())
            assertTrue(second.file.exists())
            second.close()
            cache.reconcile(emptySet())
            assertFalse(second.file.exists())
        } finally { first.close(); second.close(); cache.close() }
    }

    @Test fun reportsByteProgressAndCommittedCacheResidency() = runBlocking {
        val cache = DesktopStageMediaCache(
            { PlayableSource("https://rr1.googlevideo.com/videoplayback") }, client(), temporary.newFolder().toPath(),
        )
        val updates = mutableListOf<StageCacheProgress>()
        try {
            assertFalse(cache.contains(song.id))
            val first = cache.prepare(song) { updates += it }
            first.close()
            assertTrue(cache.contains(song.id))
            val cached = cache.prepare(song) { updates += it }
            cached.close()
            assertEquals(StageBufferTransferPhase.PREPARING, updates.first().phase)
            assertEquals(StageBufferTransferPhase.DOWNLOADING, updates[1].phase)
            assertEquals(4L, updates[1].receivedBytes)
            assertEquals(4L, updates[1].totalBytes)
            assertEquals(StageBufferTransferPhase.READY, updates[2].phase)
            assertEquals(StageBufferTransferPhase.READY, updates[3].phase)
        } finally { cache.close() }
    }

    @Test fun unknownContentLengthIsReportedWithoutInventingAProgressFraction() = runBlocking {
        val cache = DesktopStageMediaCache(
            { PlayableSource("https://rr1.googlevideo.com/videoplayback") },
            client(contentLength = -1), temporary.newFolder().toPath(),
        )
        val updates = mutableListOf<StageCacheProgress>()
        try {
            cache.prepare(song) { updates += it }.close()
            val download = updates.single { it.phase == StageBufferTransferPhase.DOWNLOADING }
            assertEquals(4L, download.receivedBytes)
            assertNull(download.totalBytes)
        } finally { cache.close() }
    }

    @Test fun bufferingPausesTheSameRequestAndKeepsPartialBytesUntilResume() = runBlocking {
        val requests = mutableListOf<String>()
        val gate = StageMediaTransferGate()
        val tempBase = temporary.newFolder().toPath()
        val paused = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cache = DesktopStageMediaCache(
            { PlayableSource("https://rr1.googlevideo.com/videoplayback") },
            client(calls = requests), tempBase, transferGate = gate,
        )
        try {
            val task = async(Dispatchers.IO) {
                cache.prepare(song) { progress ->
                    if (progress.phase == StageBufferTransferPhase.DOWNLOADING) {
                        cache.setForegroundBuffering(true)
                        paused.complete(Unit)
                    }
                }
            }
            withTimeout(5_000) { paused.await() }
            withTimeout(5_000) {
                while (tempBase.toFile().listFiles()?.singleOrNull()?.listFiles()?.any { it.name.endsWith(".part") && it.length() == 4L } != true) kotlinx.coroutines.delay(5)
            }
            assertEquals("one request must remain open while paused", 1, requests.size)
            assertFalse("paused transfer must not commit", task.isCompleted)
            cache.setForegroundBuffering(false)
            val media = withTimeout(5_000) { task.await() }
            try { assertArrayEquals(byteArrayOf(1, 2, 3, 4), media.file.readBytes()) } finally { media.close() }
            assertEquals("resume must continue the same HTTP response", 1, requests.size)
        } finally { cache.setForegroundBuffering(false); cache.close() }
    }

    @Test fun cancellingWhilePlaybackOwnsNetworkCancelsCallAndRemovesPartial() = runBlocking {
        val activeCall = AtomicReference<Call?>()
        val paused = kotlinx.coroutines.CompletableDeferred<Unit>()
        val tempBase = temporary.newFolder().toPath()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            activeCall.set(chain.call())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(object : ResponseBody() {
                    override fun contentType() = "audio/webm".toMediaType()
                    override fun contentLength() = -1L
                    override fun source() = Buffer().write(byteArrayOf(1, 2, 3, 4))
                }).build()
        }.build()
        val cache = DesktopStageMediaCache(
            { PlayableSource("https://rr1.googlevideo.com/videoplayback") }, client, tempBase,
        )
        try {
            val task = async(Dispatchers.IO) {
                cache.prepare(song) { progress ->
                    if (progress.phase == StageBufferTransferPhase.DOWNLOADING) {
                        cache.setForegroundBuffering(true)
                        paused.complete(Unit)
                    }
                }
            }
            withTimeout(5_000) { paused.await() }
            withTimeout(5_000) {
                while (tempBase.toFile().listFiles()?.singleOrNull()?.listFiles()?.any { it.name.endsWith(".part") } != true) kotlinx.coroutines.delay(5)
            }
            task.cancel()
            task.join()
            assertTrue(activeCall.get()!!.isCanceled())
            assertTrue(tempBase.toFile().listFiles()!!.single().listFiles().isNullOrEmpty())
        } finally { cache.setForegroundBuffering(false); cache.close() }
    }

    @Test fun speculativeTransferIsPacedByInjectedMonotonicClock() = runBlocking {
        val tempBase = temporary.newFolder().toPath()
        val bytes = ByteArray(128 * 1024) { it.toByte() }
        val clock = AtomicLong(0)
        val waits = mutableListOf<Long>()
        val cache = DesktopStageMediaCache(
            { PlayableSource("https://rr1.googlevideo.com/videoplayback") },
            client(contentLength = bytes.size.toLong(), payload = bytes),
            tempBase,
            transferRateBytesPerSecond = 128L * 1024,
            monotonicNanos = clock::get,
            waitNanos = { nanos -> waits += nanos; clock.addAndGet(nanos) },
        )
        val media = cache.prepare(song)
        try {
            assertEquals(bytes.size.toLong(), media.file.length())
            assertEquals(1_000_000_000L, waits.sum())
            assertEquals(1_000_000_000L, clock.get())
        } finally { media.close(); cache.close() }
    }

    @Test fun audioTransferAdjustsBothDirectionsWithoutRestartingTheResponse() = runBlocking {
        for (initiallyFast in listOf(false, true)) {
            val clock = AtomicLong(0)
            val gate = StageMediaTransferGate().apply { setFastTransfersAllowed(initiallyFast) }
            val bytes = ByteArray(128 * 1024)
            var firstWait = true
            val requests = mutableListOf<String>()
            val cache = DesktopStageMediaCache(
                { PlayableSource("https://rr1.googlevideo.com/videoplayback") },
                client(contentLength = bytes.size.toLong(), calls = requests, payload = bytes),
                temporary.newFolder().toPath(),
                monotonicNanos = clock::get,
                waitNanos = { nanos ->
                    clock.addAndGet(nanos)
                    if (firstWait) { firstWait = false; gate.setFastTransfersAllowed(!initiallyFast) }
                },
                transferGate = gate,
            )
            try {
                cache.prepare(song).close()
                assertEquals(if (initiallyFast) 941_406_250L else 85_937_500L, clock.get())
                assertEquals("changing rate must retain the HTTP response", 1, requests.size)
            } finally { cache.close() }
        }
    }

    @Test fun coroutineCancellationDuringReadCancelsCallAndCleansPartialFile() = runBlocking {
        val tempBase = temporary.newFolder().toPath()
        val readBlocked = CountDownLatch(1)
        val continueRead = CountDownLatch(1)
        val activeCall = AtomicReference<Call?>()
        val fixtureClient = OkHttpClient.Builder().addInterceptor { chain ->
            activeCall.set(chain.call())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(object : ResponseBody() {
                    override fun contentType() = "audio/webm".toMediaType()
                    override fun contentLength() = -1L
                    override fun source(): BufferedSource = object : ForwardingSource(Buffer().write(byteArrayOf(1, 2, 3, 4))) {
                        private var sent = false
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (sent) {
                                readBlocked.countDown()
                                check(continueRead.await(5, TimeUnit.SECONDS))
                                return -1
                            }
                            return super.read(sink, byteCount).also { if (it > 0) sent = true }
                        }
                        override fun close() { continueRead.countDown(); super.close() }
                    }.buffer()
                }).build()
        }.build()
        val cache = DesktopStageMediaCache({ PlayableSource("https://rr1.googlevideo.com/videoplayback") }, fixtureClient, tempBase)
        try {
            val task = async(Dispatchers.IO) { cache.prepare(song) }
            assertTrue(withTimeout(5000) { kotlinx.coroutines.withContext(Dispatchers.IO) { readBlocked.await(5, TimeUnit.SECONDS) } })
            task.cancel()
            assertTrue(activeCall.get()!!.isCanceled())
            continueRead.countDown()
            task.join()
            assertTrue(tempBase.toFile().listFiles()!!.single().listFiles().isNullOrEmpty())
        } finally { continueRead.countDown(); cache.close() }
    }
}
