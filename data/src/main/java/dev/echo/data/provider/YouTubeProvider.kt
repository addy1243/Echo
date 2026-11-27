package dev.echo.data.provider

import dev.echo.core.AudioSourceProvider
import dev.echo.core.MusicMetadataProvider
import dev.echo.core.PlayableSource
import dev.echo.core.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeProvider @Inject constructor() : AudioSourceProvider, MusicMetadataProvider {
    private val videoCache = YouTubeVideoSelectionCache()
    override val id = "youtube"
    override suspend fun isAvailable() = true

    override suspend fun search(query: String): List<Track> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        require(term.length <= 200) { "Search using 200 characters or fewer" }
        youtubeSearchVideoId(term)?.let { videoId ->
            return extract {
                val info = StreamInfo.getInfo(ServiceList.YouTube, youtubeWatchUrl(videoId))
                info.errors.forEach(::throwKnownYouTubeError)
                if (info.id == videoId) listOfNotNull(youtubeTrack(info)) else emptyList()
            }
        }
        return extract {
            val service = ServiceList.YouTube
            val handler = service.searchQHFactory.fromQuery(term,
                listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
            val info = SearchInfo.getInfo(service, handler)
            info.errors.forEach { throwKnownYouTubeError(it) }
            if (info.relatedItems.isEmpty() && info.errors.isNotEmpty())
                throw YouTubeRequestException("YouTube search could not be read. Try again later or update Echo.")
            info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull(::youtubeTrack)
                .distinctBy(Track::id).take(20)
        }
    }

    suspend fun searchPlaylists(query: String): List<YouTubePlaylistSearchResult> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        require(term.length <= 200) { "Search using 200 characters or fewer" }
        return extract {
            val service = ServiceList.YouTube
            val handler = service.searchQHFactory.fromQuery(term,
                listOf(YoutubeSearchQueryHandlerFactory.PLAYLISTS), "")
            val info = SearchInfo.getInfo(service, handler)
            info.errors.forEach { throwKnownYouTubeError(it) }
            if (info.relatedItems.isEmpty() && info.errors.isNotEmpty())
                throw YouTubeRequestException("YouTube playlist search could not be read. Try again later or update Echo.")
            info.relatedItems.filterIsInstance<PlaylistInfoItem>().mapNotNull { item ->
                if (item.playlistType != null && item.playlistType != PlaylistInfo.PlaylistType.NORMAL && !item.playlistType.isMix()) return@mapNotNull null
                val id = runCatching { youtubePlaylistId(item.url) }.getOrNull() ?: return@mapNotNull null
                val title = item.name?.trim().orEmpty()
                if (title.isEmpty() || title.length > 200) return@mapNotNull null
                YouTubePlaylistSearchResult(id, title, youtubePlaylistUrl(id), item.uploaderName.orEmpty(), item.streamCount.takeIf { it > 0 })
            }.distinctBy { it.id }.take(20)
        }
    }

    /** Reports the first available songs from the extraction worker while more pages are pending. */
    suspend fun playlist(link: String, onFirstPage: ((YouTubePlaylistPreview) -> Unit)? = null): YouTubePlaylistPreview {
        val id = youtubePlaylistId(link)
        val seed = youtubeVideoId(link)
        val url = if (id.startsWith("RD") && seed != null) "${youtubeWatchUrl(seed)}&list=$id" else youtubePlaylistUrl(id)
        return extract {
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
            info.errors.forEach(::throwKnownYouTubeError)
            require(info.id == id && (info.playlistType == PlaylistInfo.PlaylistType.NORMAL || info.playlistType.isMix())) {
                "Paste a public YouTube playlist or Mix link"
            }
            require(info.name.isNotBlank() && info.name.length <= 200) { "YouTube returned an invalid playlist name" }
            // YouTube Mixes are generated lists; their reported count is not a stable total.
            val total = info.streamCount.takeIf { it >= 0 && !info.playlistType.isMix() }
            require(total == null || total <= MAX_PLAYLIST_TRACKS) { "YouTube playlists are limited to 50,000 songs" }
            val tracks = mutableListOf<Track>()
            tracks += info.relatedItems.mapNotNull(::youtubeTrack)
            var page: Page? = info.nextPage
            if (page != null && tracks.isNotEmpty()) {
                onFirstPage?.invoke(YouTubePlaylistPreview(info.name, tracks.toList(), total, hasMore = true))
            }
            var pages = 1
            val seenPages = mutableSetOf<PlaylistPageIdentity>()
            while (page != null && tracks.size < MAX_PLAYLIST_TRACKS && pages < if (info.playlistType.isMix()) 5 else MAX_PLAYLIST_PAGES) {
                if (!seenPages.add(page.identity())) break
                val next = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, page)
                next.errors.forEach(::throwKnownYouTubeError)
                tracks += next.items.mapNotNull(::youtubeTrack)
                require(tracks.size <= MAX_PLAYLIST_TRACKS) { "YouTube playlists are limited to 50,000 songs" }
                page = next.nextPage
                pages++
            }
            require(tracks.isNotEmpty()) { "No public songs are available in this YouTube playlist" }
            YouTubePlaylistPreview(info.name, tracks, total, hasMore = page != null)
        }
    }

    override suspend fun resolve(track: Track): PlayableSource? {
        if (track.provider != id) return null
        val videoId = youtubeVideoId(track.sourceUri.orEmpty()) ?: return null
        if (track.id != "youtube:$videoId") return null
        return extract {
            val info = StreamInfo.getInfo(ServiceList.YouTube, youtubeWatchUrl(videoId))
            info.errors.forEach { throwKnownYouTubeError(it) }
            if (info.id != videoId || info.ageLimit > 0 ||
                info.contentAvailability !in setOf(ContentAvailability.AVAILABLE, ContentAvailability.UNKNOWN) ||
                info.streamType !in setOf(StreamType.VIDEO_STREAM, StreamType.AUDIO_STREAM))
                throw YouTubeRequestException("This YouTube recording is restricted or is not a recorded song")
            val audio = selectYouTubeAudio(info.audioStreams)
                ?: throw YouTubeRequestException("No playable audio is available for this YouTube recording. Try another result.")
            videoCache.remove(videoId)
            try {
                selectYouTubeVideoSelection(info.videoStreams + info.videoOnlyStreams)?.let { videoCache.put(videoId, it) }
            } catch (_: Exception) {
                // Cinema video is optional and must never change successful audio resolution.
            }
            audio
        }
    }

    /** Returns only a fresh selection from successful audio extraction; never enters the extractor lock. */
    fun cachedVideo(track: Track): PlayableSource? {
        if (track.provider != id) return null
        val videoId = youtubeVideoId(track.sourceUri.orEmpty()) ?: return null
        if (track.id != "youtube:$videoId") return null
        return videoCache.get(videoId)?.source
    }

    suspend fun resolveVideo(track: Track): PlayableSource? {
        return resolveVideoSelection(track)?.source
    }

    internal suspend fun resolveVideoSelection(track: Track): YouTubeVideoSelection? {
        if (track.provider != id) return null
        val videoId = youtubeVideoId(track.sourceUri.orEmpty()) ?: return null
        if (track.id != "youtube:$videoId") return null
        return extract {
            val info = StreamInfo.getInfo(ServiceList.YouTube, youtubeWatchUrl(videoId))
            info.errors.forEach(::throwKnownYouTubeError)
            if (info.id != videoId || info.ageLimit > 0 ||
                info.contentAvailability !in setOf(ContentAvailability.AVAILABLE, ContentAvailability.UNKNOWN)) {
                throw YouTubeRequestException("This recording has no available video stream")
            }
            selectYouTubeVideoSelection(info.videoStreams + info.videoOnlyStreams)
                ?: throw YouTubeRequestException("This recording has no supported video stream")
        }
    }

    suspend fun related(track: Track): List<Track> {
        if (track.provider != id) return emptyList()
        val videoId = youtubeVideoId(track.sourceUri.orEmpty()) ?: return emptyList()
        return extract {
            val info = StreamInfo.getInfo(ServiceList.YouTube, youtubeWatchUrl(videoId))
            info.errors.forEach { throwKnownYouTubeError(it) }
            info.relatedItems.orEmpty().filterIsInstance<StreamInfoItem>().mapNotNull(::youtubeTrack)
                .filter { it.id != track.id && it.durationMs in 60_000..900_000 }
                .distinctBy(Track::id).take(20)
        }
    }

    private suspend fun <T> extract(block: () -> T): T = extractorLock.withLock {
        runInterruptible(Dispatchers.IO) {
            try {
                if (!initialized) {
                    NewPipe.init(YouTubeDownloader())
                    initialized = true
                }
                block()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                throwKnownYouTubeError(error)
                generateSequence<Throwable>(error) { it.cause }.filterIsInstance<YouTubeRequestException>().firstOrNull()?.let { throw it }
                val retryable = generateSequence<Throwable>(error) { it.cause }.any { it is java.io.IOException }
                throw YouTubeRequestException("YouTube could not be reached or its response changed. Check your connection and try again; an Echo update may be needed.", retryable)
            }
        }
    }

    private companion object {
        val extractorLock = Mutex()
        var initialized = false
    }
}

internal class YouTubeVideoSelectionCache(
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val selection: YouTubeVideoSelection, val cachedAtElapsedMs: Long)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {}

    @Synchronized fun put(videoId: String, selection: YouTubeVideoSelection) {
        val now = wallClockMs()
        val expiresAt = listOfNotNull(selection.source.expiresAt, selection.muxedFallback?.expiresAt).minOrNull()
        if (expiresAt != null && expiresAt <= now + YOUTUBE_VIDEO_EXPIRY_MARGIN_MS) return
        entries[videoId] = Entry(selection, elapsedRealtimeMs())
        trim(now)
    }

    @Synchronized fun get(videoId: String): YouTubeVideoSelection? {
        val entry = entries[videoId] ?: return null
        val now = wallClockMs()
        if (elapsedRealtimeMs() - entry.cachedAtElapsedMs >= YOUTUBE_VIDEO_CACHE_TTL_MS ||
            listOfNotNull(entry.selection.source.expiresAt, entry.selection.muxedFallback?.expiresAt)
                .any { it <= now + YOUTUBE_VIDEO_EXPIRY_MARGIN_MS }) {
            entries.remove(videoId)
            return null
        }
        return entry.selection
    }

    @Synchronized fun remove(videoId: String) { entries.remove(videoId) }

    private fun trim(now: Long) {
        entries.entries.removeIf { (_, entry) ->
            elapsedRealtimeMs() - entry.cachedAtElapsedMs >= YOUTUBE_VIDEO_CACHE_TTL_MS ||
                listOfNotNull(entry.selection.source.expiresAt, entry.selection.muxedFallback?.expiresAt)
                    .any { it <= now + YOUTUBE_VIDEO_EXPIRY_MARGIN_MS }
        }
        while (entries.size > YOUTUBE_VIDEO_CACHE_CAPACITY) entries.remove(entries.keys.first())
    }

    private companion object {
        const val YOUTUBE_VIDEO_CACHE_CAPACITY = 64
        const val YOUTUBE_VIDEO_CACHE_TTL_MS = 30 * 60 * 1000L
        const val YOUTUBE_VIDEO_EXPIRY_MARGIN_MS = 60 * 1000L
    }
}

data class YouTubePlaylistPreview(val name: String, val tracks: List<Track>, val total: Long?, val hasMore: Boolean = false) {
    val complete: Boolean get() = !hasMore && total != null && total == tracks.size.toLong()
}

data class YouTubePlaylistSearchResult(val id: String, val title: String, val url: String, val uploader: String, val total: Long?)

private data class PlaylistPageIdentity(
    val url: String?, val id: String?, val ids: List<String>?, val cookies: Map<String, String>?, val bodyHash: Int?,
)

private fun Page.identity() = PlaylistPageIdentity(url, id, ids, cookies, body?.contentHashCode())

private fun PlaylistInfo.PlaylistType?.isMix() = this in setOf(
    PlaylistInfo.PlaylistType.MIX_STREAM, PlaylistInfo.PlaylistType.MIX_MUSIC,
    PlaylistInfo.PlaylistType.MIX_CHANNEL, PlaylistInfo.PlaylistType.MIX_GENRE,
)

private const val MAX_PLAYLIST_TRACKS = 50_000
private const val MAX_PLAYLIST_PAGES = 500

internal fun youtubePlaylistId(input: String): String {
    val url = input.trim().toHttpUrlOrNull()
    require(url != null && url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        url.fragment == null && url.host in setOf("youtube.com", "www.youtube.com", "music.youtube.com") &&
        url.encodedPath in setOf("/playlist", "/watch") && url.queryParameterValues("list").size == 1) {
        "Paste a full public YouTube playlist link"
    }
    val id = url.queryParameter("list")
    require(id != null && id.matches(Regex("[A-Za-z0-9_-]{10,80}"))) { "This is not a valid YouTube playlist link" }
    return id
}

private fun youtubePlaylistUrl(id: String) = "https://www.youtube.com/playlist?list=$id"

internal fun youtubeWatchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

internal fun youtubeVideoId(value: String): String? {
    val url = value.toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val id = when (url.host) {
        "youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com" ->
            if (url.encodedPath == "/watch" && url.queryParameterValues("v").size == 1) url.queryParameter("v") else null
        "youtu.be" -> url.pathSegments.singleOrNull()
        else -> null
    }
    return id?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{11}")) }
}

internal fun youtubeSearchVideoId(query: String) = youtubeVideoId(query.trim())

internal fun youtubeTrack(info: StreamInfo): Track? {
    if (info.ageLimit > 0 || info.contentAvailability !in setOf(ContentAvailability.AVAILABLE, ContentAvailability.UNKNOWN) ||
        info.streamType !in setOf(StreamType.VIDEO_STREAM, StreamType.AUDIO_STREAM)) return null
    return youtubeTrack(StreamInfoItem(0, youtubeWatchUrl(info.id), info.name, info.streamType).apply {
        uploaderName = info.uploaderName
        duration = info.duration
        contentAvailability = info.contentAvailability
        thumbnails = info.thumbnails
    })
}

internal fun youtubeTrack(item: StreamInfoItem): Track? {
    if (item.streamType !in setOf(StreamType.VIDEO_STREAM, StreamType.AUDIO_STREAM) ||
        item.contentAvailability !in setOf(ContentAvailability.AVAILABLE, ContentAvailability.UNKNOWN) ||
        item.duration <= 0 || item.duration > Long.MAX_VALUE / 1000 || item.name.isNullOrBlank() || item.uploaderName.isNullOrBlank()) return null
    val id = youtubeVideoId(item.url) ?: return null
    val locator = youtubeWatchUrl(id)
    return Track(id = "youtube:$id", title = item.name, artist = item.uploaderName,
        durationMs = item.duration * 1000, provider = "youtube", sourceUri = locator, sourcePage = locator,
        artworkUri = item.thumbnails.filter { image ->
            image.url.toHttpUrlOrNull()?.let { it.isHttps && it.username.isEmpty() && it.password.isEmpty() &&
                (it.host == "ytimg.com" || it.host.endsWith(".ytimg.com") || it.host == "googleusercontent.com" || it.host.endsWith(".googleusercontent.com")) } == true
        }.maxByOrNull { it.width.coerceAtLeast(0).toLong() * it.height.coerceAtLeast(0) }?.url)
}

internal fun selectYouTubeAudio(streams: List<AudioStream>, nowMs: Long = System.currentTimeMillis()): PlayableSource? {
    val stream = streams.filter { candidate ->
        val url = candidate.content.toHttpUrlOrNull()
        val expiry = url?.queryParameter("expire")
        val expiresAt = expiry?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
        candidate.isUrl && candidate.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
            candidate.audioTrackType in setOf(null, AudioTrackType.ORIGINAL) &&
            candidate.format?.mimeType?.startsWith("audio/") == true && url != null && trustedYouTubeRequestUrl(url) &&
            (url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com")) &&
            (expiry == null || expiresAt != null && expiresAt > nowMs + 30_000)
    }.maxWithOrNull(compareBy<AudioStream> { it.audioTrackType == AudioTrackType.ORIGINAL }.thenBy { it.averageBitrate }) ?: return null
    val url = stream.content.toHttpUrlOrNull() ?: return null
    return PlayableSource(stream.content, headers = mapOf("User-Agent" to YOUTUBE_USER_AGENT),
        mimeType = stream.format?.mimeType, expiresAt = url.queryParameter("expire")?.toLongOrNull()?.times(1000))
}

internal data class YouTubeVideoSelection(
    val source: PlayableSource,
    val requiresAudioSlave: Boolean,
    val muxedFallback: PlayableSource? = null,
)

internal fun selectYouTubeVideo(streams: List<VideoStream>, nowMs: Long = System.currentTimeMillis()): PlayableSource? =
    selectYouTubeVideoSelection(streams, nowMs)?.source

internal fun selectYouTubeVideoSelection(streams: List<VideoStream>, nowMs: Long = System.currentTimeMillis()): YouTubeVideoSelection? {
    val eligible = streams.filter { candidate ->
        val url = candidate.content.toHttpUrlOrNull()
        val expiry = url?.queryParameter("expire")
        val expiresAt = expiry?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
        candidate.isUrl && candidate.deliveryMethod in setOf(DeliveryMethod.PROGRESSIVE_HTTP, DeliveryMethod.DASH) &&
            candidate.format?.mimeType?.startsWith("video/") == true && url != null && trustedYouTubeRequestUrl(url) &&
            (url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com")) &&
            (expiry == null || expiresAt != null && expiresAt > nowMs + 30_000)
    }
    val separateVideo = eligible.filter { it.isVideoOnly() }
    val quality = compareBy<VideoStream> {
        it.height.takeIf { height -> height > 0 }
            ?: Regex("(\\d{3,4})p").find(it.getResolution())?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }.thenBy {
        it.fps.takeIf { fps -> fps > 0 }
            ?: Regex("\\d{3,4}p(\\d{2,3})").find(it.getResolution())?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }.thenBy { it.bitrate }
    val stream = (separateVideo.ifEmpty { eligible }).maxWithOrNull(quality) ?: return null
    val muxedFallback = if (stream.isVideoOnly()) eligible.filterNot { it.isVideoOnly() }.maxWithOrNull(quality) else null
    fun source(candidate: VideoStream): PlayableSource? {
        val url = candidate.content.toHttpUrlOrNull() ?: return null
        return PlayableSource(candidate.content, headers = mapOf("User-Agent" to YOUTUBE_USER_AGENT),
            mimeType = candidate.format?.mimeType, expiresAt = url.queryParameter("expire")?.toLongOrNull()?.times(1000))
    }
    return source(stream)?.let { YouTubeVideoSelection(it, stream.isVideoOnly(), muxedFallback?.let(::source)) }
}

private fun throwKnownYouTubeError(error: Throwable) {
    var cause: Throwable? = error
    repeat(10) {
        when (val current = cause) {
            is CancellationException -> throw current
            is YouTubeRequestException -> throw current
            is ReCaptchaException -> throw YouTubeRequestException("YouTube requires browser verification. Try again later.")
            is ContentNotAvailableException -> throw YouTubeRequestException("This YouTube recording is unavailable or restricted. Try another result.")
        }
        cause = cause?.cause
    }
}
