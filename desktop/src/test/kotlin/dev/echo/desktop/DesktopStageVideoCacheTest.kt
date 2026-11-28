package dev.echo.desktop

import dev.echo.core.PlayableSource
import dev.echo.core.Track
import dev.echo.data.provider.YouTubeVideoSelection
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopStageVideoCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val song = Track("youtube:abcdefghijk", "Song", "Artist", provider = "youtube")

    private fun source(uri: String, mime: String = "video/webm") =
        PlayableSource(uri, headers = mapOf("User-Agent" to "Echo"), mimeType = mime)

    private fun client(payload: ByteArray = byteArrayOf(1, 2, 3, 4), mime: String = "video/webm") =
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(object : ResponseBody() {
                    override fun contentType() = mime.toMediaType()
                    override fun contentLength() = payload.size.toLong()
                    override fun source() = Buffer().write(payload)
                }).build()
        }.build()

    @Test fun primarySelectionRetainsAudioSlaveAcrossPinAndReconcile() = runBlocking {
        val cache = DesktopStageVideoCache(
            { YouTubeVideoSelection(source("https://r1.googlevideo.com/video"), true) },
            client(), StageMediaTransferGate(), temporary.newFolder().toPath(),
        )
        val cached = cache.prepare(song)
        try {
            assertNotNull(cached)
            assertTrue(cached!!.requiresAudioSlave)
            cache.reconcile(emptySet())
            assertTrue(cached.file.exists())
            val repinned = cache.pin(song.id)
            assertTrue(repinned.requiresAudioSlave)
            repinned.close()
        } finally {
            cached?.close()
            cache.close()
        }
        assertFalse(cached!!.file.exists())
    }

    @Test fun failedAudioSlaveSourceUsesMuxedFallbackAndClearsAudioSlave() = runBlocking {
        val calls = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls += chain.request().url.toString()
            if (calls.size == 1) {
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(500).message("failed").build()
            } else {
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(object : ResponseBody() {
                        override fun contentType() = "video/mp4".toMediaType()
                        override fun contentLength() = 4L
                        override fun source() = Buffer().write(byteArrayOf(4, 3, 2, 1))
                    }).build()
            }
        }.build()
        val cache = DesktopStageVideoCache(
            { YouTubeVideoSelection(source("https://r1.googlevideo.com/video-only"), true, source("https://r1.googlevideo.com/muxed", "video/mp4")) },
            client, StageMediaTransferGate(), temporary.newFolder().toPath(),
        )
        val cached = try { cache.prepare(song) } finally { cache.close() }
        assertNotNull(cached)
        assertFalse(cached!!.requiresAudioSlave)
        assertEquals(2, calls.size)
        cached.close()
    }

    @Test fun invalidProviderSourceIsNotRequested() = runBlocking {
        var requests = 0
        val cache = DesktopStageVideoCache(
            { YouTubeVideoSelection(PlayableSource("https://example.invalid/video", mimeType = "video/mp4"), false) },
            OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                error("untrusted source was requested")
            }.build(), StageMediaTransferGate(), temporary.newFolder().toPath(),
        )
        try {
            assertNull(cache.prepare(song))
            assertEquals(0, requests)
        } finally { cache.close() }
    }

    @Test fun reportsPreparedBytesAndReadyOnCommittedVideoHit() = runBlocking {
        val cache = DesktopStageVideoCache(
            { YouTubeVideoSelection(source("https://r1.googlevideo.com/video"), false) },
            client(), StageMediaTransferGate(), temporary.newFolder().toPath(),
        )
        val updates = mutableListOf<StageCacheProgress>()
        try {
            assertFalse(cache.contains(song.id))
            val first = cache.prepare(song) { updates += it }!!
            first.close()
            assertTrue(cache.contains(song.id))
            val cached = cache.prepare(song) { updates += it }!!
            cached.close()
            assertEquals(StageBufferTransferPhase.PREPARING, updates.first().phase)
            assertEquals(StageBufferTransferPhase.DOWNLOADING, updates[1].phase)
            assertEquals(4L, updates[1].receivedBytes)
            assertEquals(4L, updates[1].totalBytes)
            assertEquals(StageBufferTransferPhase.READY, updates[2].phase)
            assertEquals(StageBufferTransferPhase.READY, updates[3].phase)
        } finally { cache.close() }
    }

    @Test fun bufferingPausesVideoBytesAndResumesTheSameResponse() = runBlocking {
        val requests = mutableListOf<String>()
        val gate = StageMediaTransferGate()
        val tempBase = temporary.newFolder().toPath()
        val paused = kotlinx.coroutines.CompletableDeferred<Unit>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(object : ResponseBody() {
                    override fun contentType() = "video/webm".toMediaType()
                    override fun contentLength() = 4L
                    override fun source() = Buffer().write(byteArrayOf(1, 2, 3, 4))
                }).build()
        }.build()
        val cache = DesktopStageVideoCache(
            { YouTubeVideoSelection(source("https://r1.googlevideo.com/video"), false) }, client, gate, tempBase,
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
            assertEquals(1, requests.size)
            assertFalse("paused transfer must not commit", task.isCompleted)
            cache.setForegroundBuffering(false)
            val video = withTimeout(5_000) { task.await() }!!
            try { assertArrayEquals(byteArrayOf(1, 2, 3, 4), video.file.readBytes()) } finally { video.close() }
            assertEquals("resume must continue the same HTTP response", 1, requests.size)
        } finally { cache.setForegroundBuffering(false); cache.close() }
    }

    @Test fun videoTransferAdjustsBothDirectionsWithoutRestartingTheResponse() = runBlocking {
        for (initiallyFast in listOf(false, true)) {
            val clock = AtomicLong(0)
            val gate = StageMediaTransferGate().apply { setFastTransfersAllowed(initiallyFast) }
            var firstWait = true
            val cache = DesktopStageVideoCache(
                { YouTubeVideoSelection(source("https://r1.googlevideo.com/video"), false) },
                client(ByteArray(128 * 1024)), gate, temporary.newFolder().toPath(),
                monotonicNanos = clock::get,
                waitNanos = { nanos ->
                    clock.addAndGet(nanos)
                    if (firstWait) { firstWait = false; gate.setFastTransfersAllowed(!initiallyFast) }
                },
            )
            try {
                assertNotNull(cache.prepare(song)?.also { it.close() })
                assertEquals(if (initiallyFast) 941_406_250L else 85_937_500L, clock.get())
            } finally { cache.close() }
        }
    }

    @Test fun distinguishesUnavailableVideoFromTransferFailure() = runBlocking {
        val unavailableUpdates = mutableListOf<StageCacheProgress>()
        val unavailable = DesktopStageVideoCache(
            { null }, client(), StageMediaTransferGate(), temporary.newFolder().toPath(),
        )
        try {
            assertNull(unavailable.prepare(song) { unavailableUpdates += it })
            assertEquals(StageBufferTransferPhase.UNAVAILABLE, unavailableUpdates.last().phase)
        } finally { unavailable.close() }

        val failedUpdates = mutableListOf<StageCacheProgress>()
        val failed = DesktopStageVideoCache(
            { YouTubeVideoSelection(source("https://r1.googlevideo.com/video"), false) },
            OkHttpClient.Builder().addInterceptor { throw java.io.IOException("fixture") }.build(),
            StageMediaTransferGate(), temporary.newFolder().toPath(),
        )
        try {
            assertNull(failed.prepare(song) { failedUpdates += it })
            assertEquals(StageBufferTransferPhase.FAILED, failedUpdates.last().phase)
        } finally { failed.close() }
    }

    @Test fun cancellationCancelsCallAndRemovesPartialFile() = runBlocking {
        val started = CountDownLatch(1)
        val continueRead = CountDownLatch(1)
        val activeCall = AtomicReference<Call?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            activeCall.set(chain.call())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(object : ResponseBody() {
                    override fun contentType() = "video/webm".toMediaType()
                    override fun contentLength() = -1L
                    override fun source(): BufferedSource = object : ForwardingSource(Buffer().write(byteArrayOf(1, 2, 3, 4))) {
                        private var sent = false
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (sent) {
                                started.countDown()
                                check(continueRead.await(5, TimeUnit.SECONDS))
                                return -1
                            }
                            return super.read(sink, byteCount).also { if (it > 0) sent = true }
                        }
                        override fun close() { continueRead.countDown(); super.close() }
                    }.buffer()
                }).build()
        }.build()
        val base = temporary.newFolder().toPath()
        val cache = DesktopStageVideoCache(
            { YouTubeVideoSelection(source("https://r1.googlevideo.com/video"), false) }, client, StageMediaTransferGate(), base,
        )
        try {
            val task = async(Dispatchers.IO) { cache.prepare(song) }
            assertTrue(withTimeout(5000) { withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) } })
            task.cancel()
            assertTrue(activeCall.get()!!.isCanceled())
            continueRead.countDown()
            task.join()
            assertTrue(base.toFile().listFiles()!!.single().listFiles().isNullOrEmpty())
        } finally {
            continueRead.countDown()
            cache.close()
        }
    }

    @Test fun completedLiveVideoCaptureCanBePinnedAndSurvivesReconcileUntilReleased() = runBlocking {
        val payload = byteArrayOf(9, 8, 7, 6, 5)
        val cache = DesktopStageVideoCache(
            { error("a live capture must avoid resolving or downloading another representation") },
            client(), StageMediaTransferGate(), temporary.newFolder().toPath(),
        )
        try {
            val capture = cache.beginStreamCapture(song, source("https://r1.googlevideo.com/video?itag=248"), requiresAudioSlave = true)!!
            val segment = capture.beginResponse(null, 200, null, payload.size.toLong(), "video/webm", null, source("https://r1.googlevideo.com/video?itag=248"))!!
            segment.write(payload, payload.size)
            segment.finish()
            capture.finishCapture()
            val completed = withTimeout(5_000) { cache.awaitStreamCapture(song.id)!! }
            val video = cache.pinStreamCapture(song.id, capture, completed)!!
            try {
                assertArrayEquals(payload, video.file.readBytes())
                assertTrue(video.requiresAudioSlave)
                cache.reconcile(emptySet())
                assertTrue(video.file.exists())
                cache.discardStreamCapture(song.id, capture, completed)
                assertTrue("discard must preserve an active playback pin", video.file.exists())
            } finally { video.close() }
            cache.discardStreamCapture(song.id, capture, completed)
            assertFalse(cache.contains(song.id))
        } finally { cache.close() }
    }

    @Test fun captureRejectsChangedRepresentationAndDiscardsWithoutPublishing() = runBlocking {
        val cache = DesktopStageVideoCache(
            { error("capture should not fall back to a new transfer") },
            client(), StageMediaTransferGate(), temporary.newFolder().toPath(),
        )
        try {
            val capture = cache.beginStreamCapture(song, source("https://r1.googlevideo.com/video"))!!
            assertNull(capture.beginResponse(null, 200, null, 4, "video/webm", null, source("https://r2.googlevideo.com/different")))
            withTimeout(5_000) { cache.awaitStreamCaptureSettled(song.id) }
            assertEquals(DesktopStageStreamCapture.Status.DISCARDED, cache.streamCaptureStatus(song.id))
            assertFalse(cache.contains(song.id))
            assertTrue(cache.clearDiscardedStreamCapture(song.id))
        } finally { cache.close() }
    }
}
