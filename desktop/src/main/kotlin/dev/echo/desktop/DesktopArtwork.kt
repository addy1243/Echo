package dev.echo.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.loadImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

internal const val MAX_ARTWORK_BYTES = 4 * 1024 * 1024
private val artworkClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .connectTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()

suspend fun loadDesktopArtwork(uri: String?): ImageBitmap? = withContext(Dispatchers.IO) {
    if (!trustedDesktopArtworkUrl(uri)) return@withContext null
    try {
        artworkClient.newCall(Request.Builder().url(uri!!).build()).execute().use { response ->
            val body = response.body ?: return@withContext null
            if (!response.isSuccessful || body.contentLength() > MAX_ARTWORK_BYTES) return@withContext null
            val bytes = body.byteStream().use(::readDesktopArtworkBytes)
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input == null) return@withContext null
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) return@withContext null
                val reader = readers.next()
                try {
                    reader.input = input
                    if (!validDesktopArtworkDimensions(reader.getWidth(0), reader.getHeight(0))) return@withContext null
                } finally { reader.dispose() }
            }
            loadImageBitmap(ByteArrayInputStream(bytes))
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}

internal suspend fun desktopArtworkAccent(uri: String?): Color? {
    val bitmap = loadDesktopArtwork(uri) ?: return null
    return withContext(Dispatchers.IO) {
        val pixel = IntArray(1)
        var red = 0L; var green = 0L; var blue = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            bitmap.readPixels(pixel, x * (bitmap.width - 1) / 7, y * (bitmap.height - 1) / 7, 1, 1)
            red += pixel[0] shr 16 and 255; green += pixel[0] shr 8 and 255; blue += pixel[0] and 255
        }
        Color((red / 64).toInt(), (green / 64).toInt(), (blue / 64).toInt())
    }
}

internal fun trustedDesktopArtworkUrl(uri: String?): Boolean {
    val url = uri?.toHttpUrlOrNull() ?: return false
    return url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        listOf("ytimg.com", "ggpht.com", "googleusercontent.com").any { url.host == it || url.host.endsWith(".$it") }
}

internal fun validDesktopArtworkDimensions(width: Int, height: Int): Boolean =
    width in 1..4096 && height in 1..4096 && width.toLong() * height <= 16_777_216L

internal fun readDesktopArtworkBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= MAX_ARTWORK_BYTES) { "Artwork exceeds size limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
