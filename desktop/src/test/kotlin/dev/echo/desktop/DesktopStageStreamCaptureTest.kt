package dev.echo.desktop

import dev.echo.core.PlayableSource
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopStageStreamCaptureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm")

    private fun capture(
        name: String = "capture.part",
        reserve: (Long) -> Boolean = { true },
        release: (Long) -> Unit = {},
        publish: (CompletedStageStreamCapture) -> Unit = {},
        queueBytes: Long = 2L * 1024 * 1024,
        onDiscarded: () -> Unit = {},
    ) = DesktopStageStreamCapture(source, temporary.root.toPath().resolve(name), reserve, release, publish, onDiscarded = onDiscarded, queuedBytesLimit = queueBytes)

    @Test fun combinesOutOfOrderRangesAndPublishesOnlyAfterCompleteCoverage() = runBlocking {
        val ready = CountDownLatch(1)
        lateinit var completed: CompletedStageStreamCapture
        val cache = capture(publish = { completed = it; ready.countDown() })
        try {
            val tail = cache.beginResponse("bytes=2-3", 206, "bytes 2-3/4", 2, "audio/webm", null, source, "\"stable\"")!!
            tail.write(byteArrayOf(3, 4), 2)
            tail.finish()
            assertEquals(DesktopStageStreamCapture.Status.CAPTURING, cache.status)
            val head = cache.beginResponse("bytes=0-1", 206, "bytes 0-1/4", 2, "audio/webm", null, source, "\"stable\"")!!
            head.write(byteArrayOf(1, 2), 2)
            head.finish()
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) { cache.awaitComplete() }
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(completed.partialFile))
            assertEquals(4, completed.sizeBytes)
            assertEquals(DesktopStageStreamCapture.Status.COMPLETE, cache.status)
        } finally { cache.close() }
    }

    @Test fun aGapDiscardsAndReleasesItsReservation() = runBlocking {
        val releases = mutableListOf<Long>()
        val cache = capture(reserve = { it == 4L }, release = releases::add)
        try {
            val segment = cache.beginResponse("bytes=0-1", 206, "bytes 0-1/4", 2, "audio/webm", null, source)!!
            cache.awaitReservation()
            segment.write(byteArrayOf(1, 2), 2)
            segment.finish()
            cache.close()
            cache.awaitSettled()
            assertEquals(DesktopStageStreamCapture.Status.DISCARDED, cache.status)
            assertEquals(listOf(4L), releases)
            assertFalse(Files.exists(temporary.root.toPath().resolve("capture.part")))
        } finally { cache.close() }
    }

    @Test fun changedOverlappingBytesInvalidateCapture() = runBlocking {
        val discarded = CountDownLatch(1)
        val cache = capture(release = { discarded.countDown() })
        try {
            val first = cache.beginResponse("bytes=0-2", 206, "bytes 0-2/4", 3, "audio/webm", null, source, "\"stable\"")!!
            first.write(byteArrayOf(1, 2, 3), 3)
            first.finish()
            val second = cache.beginResponse("bytes=2-3", 206, "bytes 2-3/4", 2, "audio/webm", null, source, "\"stable\"")!!
            second.write(byteArrayOf(9, 4), 2)
            second.finish()
            assertTrue(discarded.await(3, TimeUnit.SECONDS))
            assertEquals(DesktopStageStreamCapture.Status.DISCARDED, cache.status)
        } finally { cache.close() }
    }

    @Test fun malformedOrChangedRepresentationResponsesAreNotCaptured() {
        val malformed = capture("malformed.part")
        try {
            assertNull(malformed.beginResponse("bytes=0-1", 206, "bytes 0-1/4", 3, "audio/webm", null, source))
            assertEquals(DesktopStageStreamCapture.Status.DISCARDED, malformed.status)
        } finally { malformed.close() }

        val changed = capture("changed.part")
        try {
            val first = changed.beginResponse("bytes=0-1", 206, "bytes 0-1/4", 2, "audio/webm", null, source, "\"etag-one\"")!!
            first.write(byteArrayOf(1, 2), 2)
            assertNull(changed.beginResponse("bytes=2-3", 206, "bytes 2-3/4", 2, "audio/webm", null,
                source.copy(uri = "https://rr2.googlevideo.com/videoplayback?itag=251"), "\"etag-two\""))
            assertEquals(DesktopStageStreamCapture.Status.DISCARDED, changed.status)
        } finally { changed.close() }
    }

    @Test fun unvalidatedMultipleResponsesAreRejectedButOneFullResponseCanComplete() = runBlocking {
        val partial = capture("partial-no-validator.part")
        try {
            val first = partial.beginResponse("bytes=0-1", 206, "bytes 0-1/4", 2, "audio/webm", null, source)!!
            first.write(byteArrayOf(1, 2), 2)
            first.finish()
            assertNull(partial.beginResponse("bytes=2-3", 206, "bytes 2-3/4", 2, "audio/webm", null, source))
            partial.awaitSettled()
            assertEquals(DesktopStageStreamCapture.Status.DISCARDED, partial.status)
        } finally { partial.close() }

        val ready = CountDownLatch(1)
        lateinit var published: CompletedStageStreamCapture
        val whole = capture("whole-no-validator.part", publish = { published = it; ready.countDown() })
        try {
            val full = whole.beginResponse(null, 200, null, 4, "audio/webm", null, source)!!
            full.write(byteArrayOf(1, 2, 3, 4), 4)
            full.finish()
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) { whole.awaitComplete() }
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(published.partialFile))
            assertEquals(DesktopStageStreamCapture.Status.COMPLETE, whole.status)
        } finally { whole.close() }
    }

    @Test fun cancellationAfterPublicationClaimCannotOverwritePublishedCompletion() = runBlocking {
        val publishing = CountDownLatch(1)
        val finishPublish = CountDownLatch(1)
        val publishCount = AtomicInteger()
        val cache = capture("cancel-during-publish.part", publish = {
            publishCount.incrementAndGet()
            publishing.countDown()
            check(finishPublish.await(3, TimeUnit.SECONDS))
        })
        try {
            val segment = cache.beginResponse(null, 200, null, 4, "audio/webm", null, source)!!
            segment.write(byteArrayOf(1, 2, 3, 4), 4)
            segment.finish()
            assertTrue(publishing.await(3, TimeUnit.SECONDS))

            // Publication has claimed the terminal transition, so owner cancellation loses.
            cache.close()
            finishPublish.countDown()

            val result = withTimeout(3_000) { cache.awaitComplete() }
            withTimeout(3_000) { cache.awaitSettled() }
            assertEquals(DesktopStageStreamCapture.Status.COMPLETE, cache.status)
            assertEquals(1, publishCount.get())
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(result.partialFile))
        } finally {
            finishPublish.countDown()
            cache.close()
        }
    }

    @Test fun queueOverflowDropsCaptureWithoutBlockingProducer() = runBlocking {
        val releases = AtomicInteger()
        val cache = capture(release = { releases.incrementAndGet() }, queueBytes = 64 * 1024L)
        try {
            val total = 90L * 1024 * 1024
            val segment = cache.beginResponse(null, 200, null, total, "audio/webm", null, source)!!
            cache.awaitReservation()
            val chunk = ByteArray(64 * 1024)
            repeat(256) {
                segment.write(chunk, chunk.size)
                if (cache.status == DesktopStageStreamCapture.Status.DISCARDED) return@repeat
            }
            assertEquals(DesktopStageStreamCapture.Status.DISCARDED, cache.status)
            cache.awaitSettled()
            assertEquals(1, releases.get())
        } finally { cache.close() }
    }

    @Test fun discardedCaptureCleanupDoesNotBlockFurtherRelayWritesOrClose() = runBlocking {
        for (blockRelease in listOf(true, false)) {
            val cleanupStarted = CountDownLatch(1)
            val finishCleanup = CountDownLatch(1)
            val slowCleanup = { cleanupStarted.countDown(); check(finishCleanup.await(5, TimeUnit.SECONDS)); Unit }
            val cache = capture("cleanup-$blockRelease.part",
                release = { if (blockRelease) slowCleanup() },
                onDiscarded = { if (!blockRelease) slowCleanup() })
            val producer = Executors.newSingleThreadExecutor()
            try {
                val segment = cache.beginResponse(null, 200, null, 4, "audio/webm", null, source)!!
                cache.awaitReservation()
                cache.invalidate()
                assertTrue(cleanupStarted.await(3, TimeUnit.SECONDS))
                producer.submit { segment.write(byteArrayOf(1), 1); cache.close() }.get(500, TimeUnit.MILLISECONDS)
                assertEquals(DesktopStageStreamCapture.Status.DISCARDED, cache.status)
            } finally {
                finishCleanup.countDown()
                cache.close()
                withTimeout(3_000) { cache.awaitSettled() }
                producer.shutdownNow()
            }
        }
    }
}
