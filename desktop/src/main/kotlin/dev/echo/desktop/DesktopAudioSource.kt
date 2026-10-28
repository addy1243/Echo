package dev.echo.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.echo.core.PlayableSource
import dev.echo.data.provider.trustedYouTubeRequestUrl
import java.io.Closeable
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
internal class DesktopAudioSource(initial: PlayableSource, private val refresh: suspend () -> PlayableSource?) : Closeable {
    init { require(validDesktopYouTubeSource(initial)) { "Unsupported audio source" } }
    private val executor = Executors.newFixedThreadPool(3) { task -> Thread(task, "Echo audio stream").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4)
    private val token = "/${UUID.randomUUID()}"
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    @Volatile private var source = initial
    private var refreshed = false
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
        for (attempt in 0..1) {
            val current = source
            require(validDesktopYouTubeSource(current))
            val request = Request.Builder().url(current.uri)
            current.headers.forEach { (key, value) -> request.header(key, value) }
            range?.let { request.header("Range", it) }
            request.method(exchange.requestMethod, null)
            client.newCall(request.build()).execute().use { response ->
                if (response.code == 403 && attempt == 0 && refreshOnce()) return@use
                if (response.code !in setOf(200, 206, 416)) { exchange.sendResponseHeaders(if (response.code == 429) 429 else 502, -1); return }
                listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges").forEach { header ->
                    response.header(header)?.let { exchange.responseHeaders.set(header, it) }
                }
                if (exchange.requestMethod == "HEAD" || response.code == 416) { exchange.sendResponseHeaders(response.code, -1); return }
                val body = response.body ?: error("Audio body is missing")
                exchange.sendResponseHeaders(response.code, body.contentLength().takeIf { it > 0 } ?: 0)
                exchange.responseBody.use { output -> body.byteStream().use { it.copyTo(output) } }
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
        server.stop(0)
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        executor.shutdownNow()
    }
}

internal fun validDesktopYouTubeSource(source: PlayableSource): Boolean {
    val url = source.uri.toHttpUrlOrNull() ?: return false
    return trustedYouTubeRequestUrl(url) && (url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com")) &&
        source.headers.all { (name, value) -> name.equals("User-Agent", true) && !value.contains('\r') && !value.contains('\n') }
}
