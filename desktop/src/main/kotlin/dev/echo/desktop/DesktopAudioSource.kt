package dev.echo.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.echo.core.PlayableSource
import dev.echo.data.provider.trustedYouTubeRequestUrl
import java.io.Closeable
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

// VLC reads only this loopback endpoint; HTTPS host/redirect validation stays in our HTTP client.
internal class DesktopAudioSource(
    initial: PlayableSource,
    private val stageCapture: DesktopStageStreamCapture? = null,
    private val client: OkHttpClient = audioRelayClient(),
    private val onUpstreamActivityChanged: (Boolean) -> Unit = {},
    private val onLocalPlaybackChanged: () -> Unit = {},
    private val refresh: suspend () -> PlayableSource?,
) : Closeable {
    init { require(validDesktopYouTubeSource(initial)) { "Unsupported audio source" } }
    private val executor = Executors.newFixedThreadPool(3) { task -> Thread(task, "Echo audio stream").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4)
    private val token = "/${UUID.randomUUID()}"
    @Volatile private var source = initial
    private var refreshed = false
    private val relayLock = Any()
    private var closed = false
    private var upstreamRequests = 0
    private var localRequests = 0
    private var localFile: CachedStageFile? = null
    private var localMetadata: CompletedStageStreamCapture? = null
    val isFullyLocal: Boolean get() = synchronized(relayLock) { !closed && localFile != null && upstreamRequests == 0 }

    /** The cache supplies a pin proven to belong to this exact successful capture. Takes ownership. */
    fun attachCapturedFile(capture: DesktopStageStreamCapture, completed: CompletedStageStreamCapture, file: CachedStageVideo): Boolean =
        attachCapturedFile(capture, completed, CachedStageFile(file.file) { file.close() })

    fun attachCapturedFile(capture: DesktopStageStreamCapture, completed: CompletedStageStreamCapture, file: CachedStageFile): Boolean =
        synchronized(relayLock) {
            if (closed || stageCapture !== capture || !capture.ownsCompletion(completed) || capture.status != DesktopStageStreamCapture.Status.COMPLETE ||
                localFile != null || !file.file.isFile || file.file.length() != completed.sizeBytes) {
                file.close()
                return@synchronized false
            }
            localFile = file
            localMetadata = completed
            onLocalPlaybackChanged()
            true
        }
    @Volatile var terminalResponseCode: Int? = null
        private set
    val uri: String = "http://127.0.0.1:${server.address.port}$token"
    init {
        server.executor = executor
        server.createContext(token) { exchange ->
            try { serve(exchange) } catch (_: Exception) { runCatching { exchange.sendResponseHeaders(502, -1) } }
            finally { exchange.close() }
        }
        server.start()
    }
    private fun serve(exchange: HttpExchange) {
        if (exchange.requestURI.rawPath != token || exchange.requestURI.rawQuery != null ||
            exchange.requestMethod !in setOf("GET", "HEAD")) { exchange.sendResponseHeaders(404, -1); return }
        val range = exchange.requestHeaders.getFirst("Range")
        if (range != null && !range.matches(Regex("bytes=\\d+-\\d*"))) { exchange.sendResponseHeaders(416, -1); return }
        val local = synchronized(relayLock) {
            check(!closed) { "Audio relay is closed" }
            localFile?.let { file ->
                // Open while the owning pin is protected against close.
                val input = RandomAccessFile(file.file, "r")
                localRequests++
                input to checkNotNull(localMetadata)
            } ?: run {
                upstreamRequests++
                if (upstreamRequests == 1) onUpstreamActivityChanged(true)
                onLocalPlaybackChanged()
                null
            }
        }
        if (local != null) {
            try { local.first.use { serveLocal(exchange, range, it, local.second) } }
            finally { synchronized(relayLock) {
                localRequests--
                releaseLocalIfClosed()
            } }
            return
        }
        try { serveUpstream(exchange, range) }
        finally { synchronized(relayLock) {
            upstreamRequests--
            if (upstreamRequests == 0) onUpstreamActivityChanged(false)
            onLocalPlaybackChanged()
        } }
    }

    private fun serveLocal(exchange: HttpExchange, range: String?, input: RandomAccessFile, metadata: CompletedStageStreamCapture) {
        val total = metadata.sizeBytes
        val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val start = if (range == null) 0 else match?.groupValues?.get(1)?.toLongOrNull()
        val requestedEnd = match?.groupValues?.get(2)?.takeIf(String::isNotEmpty)
        val end = if (requestedEnd == null) total - 1 else requestedEnd.toLongOrNull()
        exchange.responseHeaders.set("Accept-Ranges", "bytes")
        if (start == null || end == null || start >= total || end < start) {
            exchange.responseHeaders.set("Content-Range", "bytes */$total")
            exchange.sendResponseHeaders(416, -1)
            return
        }
        val last = minOf(end, total - 1)
        val length = last - start + 1
        exchange.responseHeaders.set("Content-Type", metadata.mimeType)
        exchange.responseHeaders.set("Content-Length", length.toString())
        if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $start-$last/$total")
        val code = if (range == null) 200 else 206
        if (exchange.requestMethod == "HEAD") { exchange.sendResponseHeaders(code, -1); return }
        exchange.sendResponseHeaders(code, length)
        input.seek(start)
        exchange.responseBody.use { output ->
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                check(count > 0) { "Cached audio ended early" }
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }

    private fun serveUpstream(exchange: HttpExchange, range: String?) {
        for (attempt in 0..1) {
            val current = source
            require(validDesktopYouTubeSource(current))
            val request = Request.Builder().url(current.uri)
            current.headers.forEach { (key, value) -> request.header(key, value) }
            range?.let { request.header("Range", it) }
            request.method(exchange.requestMethod, null)
            client.newCall(request.build()).execute().use { response ->
                if (response.code == 403 && attempt == 0 && refreshOnce()) return@use
                if (response.code in 400..499 && response.code != 416) terminalResponseCode = response.code
                if (response.code !in setOf(200, 206, 416)) { exchange.sendResponseHeaders(if (response.code == 429) 429 else 502, -1); return }
                listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges").forEach { header ->
                    response.header(header)?.let { exchange.responseHeaders.set(header, it) }
                }
                if (exchange.requestMethod == "HEAD" || response.code == 416) { exchange.sendResponseHeaders(response.code, -1); return }
                val body = response.body ?: error("Audio body is missing")
                exchange.sendResponseHeaders(response.code, body.contentLength().takeIf { it >= 0 } ?: 0)
                val capture = if (exchange.requestMethod == "GET") stageCapture?.beginResponse(
                    requestedRange = range,
                    responseCode = response.code,
                    contentRange = response.header("Content-Range"),
                    contentLength = body.contentLength(),
                    contentType = body.contentType()?.let { "${it.type}/${it.subtype}" },
                    contentEncoding = response.header("Content-Encoding"),
                    servedSource = current,
                    responseEtag = response.header("ETag"),
                ) else null
                try {
                    exchange.responseBody.use { output -> body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            capture?.write(buffer, count)
                        }
                    } }
                    capture?.finish()
                } catch (failure: Exception) {
                    capture?.fail()
                    throw failure
                }
                return
            }
        }
    }
    @Synchronized private fun refreshOnce(): Boolean {
        if (refreshed) return false
        refreshed = true
        val replacement = runBlocking { refresh() } ?: return false
        if (!validDesktopYouTubeSource(replacement)) return false
        source = replacement
        return true
    }
    override fun close() {
        synchronized(relayLock) {
            if (closed) return
            closed = true
            releaseLocalIfClosed()
            onLocalPlaybackChanged()
        }
        stageCapture?.close()
        server.stop(0)
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        executor.shutdownNow()
    }

    private fun releaseLocalIfClosed() {
        if (closed && localRequests == 0) {
            localFile?.close()
            localFile = null
            localMetadata = null
        }
    }
}

private fun audioRelayClient() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

internal fun sameDesktopYouTubeRepresentation(first: PlayableSource, replacement: PlayableSource): Boolean {
    if (!validDesktopYouTubeSource(first) || !validDesktopYouTubeSource(replacement)) return false
    fun identity(source: PlayableSource): Pair<String, String>? {
        val url = source.uri.toHttpUrlOrNull() ?: return null
        val itag = url.queryParameter("itag")?.takeIf { it.matches(Regex("\\d+")) } ?: return null
        val mimeType = source.mimeType?.substringBefore(';')?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
        return mimeType to itag
    }
    return identity(first) != null && identity(first) == identity(replacement)
}

internal fun validDesktopYouTubeSource(source: PlayableSource): Boolean {
    val url = source.uri.toHttpUrlOrNull() ?: return false
    return trustedYouTubeRequestUrl(url) && (url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com")) &&
        source.headers.all { (name, value) -> name.equals("User-Agent", true) && !value.contains('\r') && !value.contains('\n') }
}
