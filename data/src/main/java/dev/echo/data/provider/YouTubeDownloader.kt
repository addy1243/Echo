package dev.echo.data.provider

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val YOUTUBE_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

class YouTubeRequestException(message: String) : IOException(message)

internal class YouTubeDownloader(private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            if (!trustedYouTubeRequestUrl(chain.request().url))
                throw YouTubeRequestException("YouTube redirected to an unsupported address")
            chain.proceed(chain.request())
        }.build()) : Downloader() {
    override fun execute(request: Request): Response {
        val url = request.url().toHttpUrlOrNull()
        if (url == null || !trustedYouTubeRequestUrl(url))
            throw YouTubeRequestException("YouTube returned an unsupported address")
        val builder = okhttp3.Request.Builder().url(url).header("User-Agent", YOUTUBE_USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        val method = request.httpMethod()
        val body = request.dataToSend()?.toRequestBody()
            ?: if (method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
        builder.method(method, body)
        return client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429)
                throw YouTubeRequestException("YouTube is rate limiting requests (HTTP 429). Wait before trying again.")
            if (response.request.url.encodedPath.startsWith("/sorry"))
                throw ReCaptchaException("YouTube requires a browser verification. Try again later.", "https://www.youtube.com/")
            if (response.code == 401 || response.code == 403)
                throw YouTubeRequestException("YouTube denied access (HTTP ${response.code}). This recording may be restricted.")
            Response(response.code, response.message, response.headers.toMultimap(),
                response.body?.string().orEmpty(), response.request.url.toString())
        }
    }
}

internal fun trustedYouTubeRequestUrl(url: HttpUrl): Boolean =
    url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        (url.host == "youtube.com" || url.host.endsWith(".youtube.com") ||
            url.host == "youtubei.googleapis.com" || url.host == "www.googleapis.com" ||
            url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com"))
