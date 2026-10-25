package dev.echo.data.provider

import android.text.Html
import dev.echo.core.AudioSourceProvider
import dev.echo.core.MusicMetadataProvider
import dev.echo.core.PlayableSource
import dev.echo.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject

class CommonsProvider @Inject constructor() : AudioSourceProvider, MusicMetadataProvider {
    override val id = "commons"
    private val client = OkHttpClient()
    override suspend fun isAvailable() = true

    override suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("format", "json")
            .addQueryParameter("generator", "search").addQueryParameter("gsrsearch", "($query) filetype:audio deepcat:\"Audio files of music\"")
            .addQueryParameter("gsrnamespace", "6").addQueryParameter("gsrlimit", "25")
            .addQueryParameter("prop", "imageinfo").addQueryParameter("iiprop", "url|mime|mediatype|extmetadata|size")
            .build()
        val json = client.newCall(Request.Builder().url(url).header("User-Agent", "EchoMusic/1.0 (personal Android music player)").build()).execute().use {
            if (!it.isSuccessful) throw IOException("Commons request failed (${it.code})")
            JSONObject(it.body?.string() ?: throw IOException("Empty Commons response"))
        }
        val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return@withContext emptyList()
        pages.keys().asSequence().mapNotNull { key ->
            val page = pages.getJSONObject(key)
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
            val metadata = info.optJSONObject("extmetadata") ?: return@mapNotNull null
            fun field(name: String) = Html.fromHtml(metadata.optJSONObject(name)?.optString("value").orEmpty(), Html.FROM_HTML_MODE_LEGACY).toString().trim()
            val license = field("LicenseUrl").replaceFirst("http://creativecommons.org/", "https://creativecommons.org/")
            val artist = field("Artist")
            val recordingCredit = field("Attribution")
            val source = info.optString("url")
            val pageUrl = info.optString("descriptionurl")
            if (!supportedAudio(info.optString("mime"), info.optString("mediatype")) || !authorizedLicense(license) || artist.isBlank() || !validAudioUrl(source) || !validSourcePage(pageUrl)) return@mapNotNull null
            val seconds = info.optDouble("duration", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0
            Track(id = "commons:${page.getLong("pageid")}", title = field("ObjectName").ifBlank { page.getString("title").removePrefix("File:") },
                artist = artist, durationMs = (seconds * 1000).toLong(), sourceUri = source, provider = id,
                attribution = (listOf(artist, recordingCredit).filter { it.isNotBlank() }.distinct() + listOf(field("LicenseShortName"), "Wikimedia Commons")).joinToString(" · "),
                licenseUrl = license, sourcePage = pageUrl)
        }.toList()
    }

    override suspend fun resolve(track: Track): PlayableSource? {
        if (track.provider != id || track.attribution.isNullOrBlank() || !authorizedLicense(track.licenseUrl.orEmpty()) || !validSourcePage(track.sourcePage.orEmpty())) return null
        val source = track.sourceUri ?: return null
        return if (validAudioUrl(source)) PlayableSource(source, headers = mapOf("User-Agent" to "EchoMusic/1.0 (personal Android music player)")) else null
    }

    companion object {
        fun supportedAudio(mime: String, mediaType: String): Boolean = mediaType == "AUDIO" && (mime.startsWith("audio/") || mime == "application/ogg")
        fun authorizedLicense(value: String): Boolean {
            val url = runCatching { value.toHttpUrl() }.getOrNull() ?: return false
            return url.isHttps && url.host == "creativecommons.org" &&
                (url.encodedPath.matches(Regex("/licenses/(by|by-sa)/(1\\.0|2\\.0|2\\.5|3\\.0|4\\.0)/?")) ||
                    url.encodedPath.matches(Regex("/publicdomain/(zero|mark)/1\\.0/?")))
        }
        private fun validSourcePage(value: String): Boolean = runCatching {
            val url = value.toHttpUrl()
            url.isHttps && url.host == "commons.wikimedia.org" && url.encodedPath.startsWith("/wiki/File:")
        }.getOrDefault(false)
        private fun validAudioUrl(value: String): Boolean = runCatching {
            val url = value.toHttpUrl()
            url.isHttps && url.host == "upload.wikimedia.org"
        }.getOrDefault(false)
    }
}
