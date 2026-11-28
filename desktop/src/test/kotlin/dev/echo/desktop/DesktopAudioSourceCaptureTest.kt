package dev.echo.desktop

import dev.echo.core.PlayableSource
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopAudioSourceCaptureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun liveRelayBytesAreCapturedWithoutAnotherUpstreamGet() = runBlocking {
        val payload = byteArrayOf(7, 3, 1, 9)
        val upstreamGets = AtomicInteger()
        val upstream = OkHttpClient.Builder().addInterceptor { chain ->
            upstreamGets.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .header("ETag", "\"stable-fixture\"")
                .body(object : ResponseBody() {
                    override fun contentType() = "audio/webm".toMediaType()
                    override fun contentLength() = payload.size.toLong()
                    override fun source() = Buffer().write(payload)
                }).build()
        }.build()
        val expected = PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm")
        val partial = temporary.root.toPath().resolve("captured.part")
        val committed = temporary.root.toPath().resolve("captured.audio")
        val capture = DesktopStageStreamCapture(
            expectedSource = expected,
            partialFile = partial,
            reserveBytes = { true },
            releaseReservation = {},
            publishComplete = { Files.move(it.partialFile, committed, StandardCopyOption.ATOMIC_MOVE) },
        )
        val source = DesktopAudioSource(expected, stageCapture = capture, client = upstream) { null }
        try {
            val response = OkHttpClient().newCall(Request.Builder().url(source.uri).get().build()).execute()
            response.use {
                assertEquals(200, it.code)
                assertArrayEquals(payload, it.body!!.bytes())
            }
            capture.awaitComplete()
            assertArrayEquals(payload, Files.readAllBytes(committed))
            assertEquals(1, upstreamGets.get())
            source.close()
            assertEquals(DesktopStageStreamCapture.Status.COMPLETE, capture.status)
            assertTrue(Files.exists(committed))
        } finally {
            source.close()
            upstream.connectionPool.evictAll()
        }
    }
}
