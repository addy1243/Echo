package dev.echo.desktop

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpPrincipal
import dev.echo.core.PlayableSource
import dev.echo.core.Track
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopAudioSourceLocalCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val song = Track("youtube:abcdefghijk", "Song", "Artist", provider = "youtube")
    private val expected = PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm")
    private val payload = byteArrayOf(7, 3, 1, 9)

    @Test fun attachedCaptureServesHeadAndRangesAndOwnsPinUntilRelayCloses() = runBlocking {
        val gets = AtomicInteger()
        val upstream = OkHttpClient.Builder().addInterceptor { chain ->
            gets.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(payload.toResponseBody("audio/webm".toMediaType())).build()
        }.build()
        val cache = DesktopStageMediaCache({ expected }, upstream, temporary.newFolder().toPath())
        val capture = cache.beginStreamCapture(song, expected)!!
        val relay = DesktopAudioSource(expected, stageCapture = capture, client = upstream) { null }
        val client = OkHttpClient()
        fun get(range: String? = null, head: Boolean = false) = client.newCall(Request.Builder().url(relay.uri)
            .apply { range?.let { header("Range", it) }; if (head) head() }.build()).execute()
        try {
            get().use { assertArrayEquals(payload, it.body!!.bytes()) }
            val completed = withTimeout(3_000) { capture.awaitComplete() }
            assertNull(cache.pinStreamCapture(song.id, capture, completed.copy()))
            val pin = cache.pinStreamCapture(song.id, capture, completed)!!
            assertTrue(relay.attachCapturedFile(capture, completed, pin))
            assertTrue(relay.isFullyLocal)
            get().use { assertEquals(200, it.code); assertArrayEquals(payload, it.body!!.bytes()) }
            get("bytes=1-2").use {
                assertEquals(206, it.code); assertEquals("bytes 1-2/4", it.header("Content-Range"))
                assertArrayEquals(byteArrayOf(3, 1), it.body!!.bytes())
            }
            get("bytes=2-").use { assertArrayEquals(byteArrayOf(1, 9), it.body!!.bytes()) }
            get("bytes=2-99").use { assertArrayEquals(byteArrayOf(1, 9), it.body!!.bytes()) }
            get(head = true).use { assertEquals(200, it.code); assertEquals("4", it.header("Content-Length")); assertEquals(0, it.body!!.bytes().size) }
            get("bytes=1-2", head = true).use { assertEquals(206, it.code); assertEquals("2", it.header("Content-Length")) }
            for (range in listOf("bytes=4-", "bytes=3-1", "bytes=999999999999999999999999-", "bytes=-2", "bytes=0-1,2-3")) {
                get(range).use { assertEquals(range, 416, it.code) }
            }
            assertEquals(1, gets.get())
            cache.reconcile(emptySet())
            cache.close()
            assertTrue(pin.file.exists())
            get("bytes=3-").use { assertArrayEquals(byteArrayOf(9), it.body!!.bytes()) }
            relay.close()
            assertFalse(pin.file.exists())
            assertFalse(relay.isFullyLocal)
            val released = AtomicInteger()
            val latePin = CachedStageFile(pin.file) { released.incrementAndGet() }
            assertFalse(relay.attachCapturedFile(capture, completed, latePin))
            assertEquals(1, released.get())
        } finally { relay.close(); cache.close(); client.connectionPool.evictAll() }
    }

    @Test fun attachmentDoesNotDeclareLocalUntilAlreadyActiveUpstreamRequestEnds() = runBlocking {
        val requests = AtomicInteger()
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val upstreamEnded = CountDownLatch(1)
        val upstreamActive = AtomicInteger()
        val upstream = OkHttpClient.Builder().addInterceptor { chain ->
            if (requests.incrementAndGet() == 2) {
                secondStarted.countDown()
                check(releaseSecond.await(5, TimeUnit.SECONDS))
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(payload.toResponseBody("audio/webm".toMediaType())).build()
        }.build()
        val cache = DesktopStageMediaCache({ expected }, upstream, temporary.newFolder().toPath())
        val capture = cache.beginStreamCapture(song, expected)!!
        val relay = DesktopAudioSource(expected, stageCapture = capture, client = upstream,
            onUpstreamActivityChanged = { active ->
                upstreamActive.set(if (active) 1 else 0)
                if (!active && secondStarted.count == 0L) upstreamEnded.countDown()
            }) { null }
        val client = OkHttpClient()
        val executor = Executors.newSingleThreadExecutor()
        fun get() = client.newCall(Request.Builder().url(relay.uri).build()).execute().use { it.body!!.bytes() }
        try {
            assertArrayEquals(payload, get())
            val completed = withTimeout(3_000) { capture.awaitComplete() }
            val second = executor.submit<ByteArray> { get() }
            assertTrue(secondStarted.await(3, TimeUnit.SECONDS))
            assertEquals(1, upstreamActive.get())
            assertTrue(relay.attachCapturedFile(capture, completed, cache.pinStreamCapture(song.id, capture, completed)!!))
            assertFalse(relay.isFullyLocal)
            assertArrayEquals(payload, get()) // A new request uses local bytes while the old response stays alive.
            assertFalse(relay.isFullyLocal)
            assertEquals(2, requests.get())
            releaseSecond.countDown()
            assertArrayEquals(payload, second.get(3, TimeUnit.SECONDS))
            assertTrue(upstreamEnded.await(3, TimeUnit.SECONDS))
            assertTrue(relay.isFullyLocal)
            assertEquals(0, upstreamActive.get())
        } finally { releaseSecond.countDown(); relay.close(); cache.close(); executor.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun relayCloseRetainsPinUntilAnAlreadyOpenedLocalResponseFinishes() = runBlocking {
        val upstream = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(payload.toResponseBody("audio/webm".toMediaType())).build()
        }.build()
        val cache = DesktopStageMediaCache({ expected }, upstream, temporary.newFolder().toPath())
        val capture = cache.beginStreamCapture(song, expected)!!
        val relay = DesktopAudioSource(expected, stageCapture = capture, client = upstream) { null }
        val executor = Executors.newSingleThreadExecutor()
        val writing = CountDownLatch(1)
        val finishWriting = CountDownLatch(1)
        try {
            OkHttpClient().newCall(Request.Builder().url(relay.uri).build()).execute().use { assertArrayEquals(payload, it.body!!.bytes()) }
            val completed = withTimeout(3_000) { capture.awaitComplete() }
            val pin = cache.pinStreamCapture(song.id, capture, completed)!!
            assertTrue(relay.attachCapturedFile(capture, completed, pin))
            val exchange = BlockingExchange(URI(relay.uri), object : OutputStream() {
                override fun write(value: Int) { writing.countDown(); check(finishWriting.await(5, TimeUnit.SECONDS)) }
            })
            val serve = DesktopAudioSource::class.java.getDeclaredMethod("serve", HttpExchange::class.java).apply { isAccessible = true }
            val localResponse = executor.submit { serve.invoke(relay, exchange) }
            assertTrue(writing.await(3, TimeUnit.SECONDS))
            cache.close()
            relay.close()
            assertTrue("an in-flight local reader still owns the pin", pin.file.exists())
            finishWriting.countDown()
            localResponse.get(3, TimeUnit.SECONDS)
            assertFalse(pin.file.exists())
        } finally { finishWriting.countDown(); relay.close(); cache.close(); executor.shutdownNow() }
    }

    private class BlockingExchange(private val uri: URI, private val output: OutputStream) : HttpExchange() {
        private val requestHeaders = Headers()
        private val responseHeaders = Headers()
        override fun getRequestHeaders() = requestHeaders
        override fun getResponseHeaders() = responseHeaders
        override fun getRequestURI() = uri
        override fun getRequestMethod() = "GET"
        override fun getHttpContext(): HttpContext? = null
        override fun close() = Unit
        override fun getRequestBody(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getResponseBody() = output
        override fun sendResponseHeaders(code: Int, length: Long) = Unit
        override fun getRemoteAddress() = InetSocketAddress("127.0.0.1", 1)
        override fun getLocalAddress() = InetSocketAddress("127.0.0.1", 2)
        override fun getProtocol() = "HTTP/1.1"
        override fun getAttribute(name: String): Any? = null
        override fun setAttribute(name: String, value: Any) = Unit
        override fun setStreams(input: InputStream, output: OutputStream) = Unit
        override fun getPrincipal(): HttpPrincipal? = null
        override fun getResponseCode() = 200
    }
}
