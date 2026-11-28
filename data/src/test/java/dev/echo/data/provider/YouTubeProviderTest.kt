package dev.echo.data.provider

import dev.echo.core.Track
import dev.echo.core.PlayableSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

class YouTubeProviderTest {
    @Test fun usesLargestTrustedArtworkForFullScreenPlayer() {
        val item = StreamInfoItem(0, "https://www.youtube.com/watch?v=abcdefghijk", "Song", StreamType.VIDEO_STREAM)
        item.uploaderName = "Artist"; item.duration = 180
        item.thumbnails = listOf(
            org.schabi.newpipe.extractor.Image("https://i.ytimg.com/small", 40, 40, org.schabi.newpipe.extractor.Image.ResolutionLevel.LOW),
            org.schabi.newpipe.extractor.Image("https://evil.example/large", 1000, 1000, org.schabi.newpipe.extractor.Image.ResolutionLevel.HIGH),
            org.schabi.newpipe.extractor.Image("https://i.ytimg.com/large", 500, 500, org.schabi.newpipe.extractor.Image.ResolutionLevel.HIGH),
        )
        assertEquals("https://i.ytimg.com/large", youtubeTrack(item)!!.artworkUri)
    }
    private val videoId = "aB12_cD34-e"
    private val mediaUrl = "https://rr1---sn.example.googlevideo.com/videoplayback?expire=2000000000"

    @Test fun acceptsOnlySecureVideoIdentities() {
        assertEquals(videoId, youtubeVideoId(youtubeWatchUrl(videoId)))
        assertEquals(videoId, youtubeVideoId("https://music.youtube.com/watch?v=$videoId"))
        assertEquals(videoId, youtubeVideoId("https://youtu.be/$videoId"))
        listOf("http://www.youtube.com/watch?v=$videoId", "https://youtube.com.evil.example/watch?v=$videoId",
            "https://user:secret@youtube.com/watch?v=$videoId", "https://youtube.com:8443/watch?v=$videoId",
            "https://youtube.com/watch?v=$videoId&v=other", "https://youtube.com/playlist?list=$videoId",
            "https://googlevideo.com/watch?v=$videoId", "https://youtube.com/watch?v=bad").forEach {
            assertNull(it, youtubeVideoId(it))
        }
    }

    @Test fun directVideoSearchRequiresOneSupportedYouTubeVideoUrl() {
        assertEquals(videoId, youtubeSearchVideoId("  https://youtu.be/$videoId  "))
        assertEquals(videoId, youtubeSearchVideoId("https://www.youtube.com/watch?v=$videoId"))
        assertNull(youtubeSearchVideoId("Song title"))
        assertNull(youtubeSearchVideoId("https://youtube.com/playlist?list=$videoId"))
    }

    @Test fun playlistLinksAcceptMixIdsAndMixCountsRemainUnverified() {
        val mixId = "RDAMVM$videoId"
        assertEquals(mixId, youtubePlaylistId("https://www.youtube.com/watch?v=$videoId&list=$mixId"))
        assertTrue(runCatching { youtubePlaylistId("https://youtube.com.evil.example/playlist?list=$mixId") }.isFailure)
        assertFalse(YouTubePlaylistPreview("Mix · Song", emptyList(), total = null, hasMore = true).complete)
    }

    @Test fun mapsVideoMetadataToTheSameStablePlayableTrackWithoutNetwork() {
        val info = org.schabi.newpipe.extractor.stream.StreamInfo(
            0, youtubeWatchUrl(videoId), youtubeWatchUrl(videoId), StreamType.VIDEO_STREAM, videoId, "Short song", 0,
        ).apply {
            uploaderName = "Artist"
            duration = 45
            contentAvailability = ContentAvailability.AVAILABLE
        }
        val track = youtubeTrack(info)!!
        assertEquals("youtube:$videoId", track.id)
        assertEquals(youtubeWatchUrl(videoId), track.sourceUri)
        assertEquals("Short song", track.title)
        assertEquals("Artist", track.artist)
        assertEquals(45_000L, track.durationMs)
    }

    @Test fun searchTracksPersistTheVideoLocatorAndExcludeLiveAndPaidResults() {
        val item = StreamInfoItem(0, "https://music.youtube.com/watch?v=$videoId", "Song", StreamType.VIDEO_STREAM)
        item.uploaderName = "Artist"
        item.duration = 180
        val track = youtubeTrack(item)!!
        assertEquals("youtube:$videoId", track.id)
        assertEquals(youtubeWatchUrl(videoId), track.sourceUri)
        assertEquals(track.sourceUri, track.sourcePage)
        assertEquals(180_000L, track.durationMs)
        item.contentAvailability = ContentAvailability.PAID
        assertNull(youtubeTrack(item))
        val live = StreamInfoItem(0, youtubeWatchUrl(videoId), "Live", StreamType.LIVE_STREAM)
        live.uploaderName = "Artist"
        live.duration = 180
        assertNull(youtubeTrack(live))
        item.contentAvailability = ContentAvailability.AVAILABLE
        item.duration = Long.MAX_VALUE
        assertNull(youtubeTrack(item))
        item.duration = 180
        item.uploaderName = ""
        assertNull(youtubeTrack(item))
    }

    @Test fun selectsOriginalProgressiveAudioAndPreservesExpiryAndMime() {
        val default = audio(bitrate = 192)
        val original = audio(url = "$mediaUrl&original=1", bitrate = 128, type = AudioTrackType.ORIGINAL)
        val dubbed = audio(bitrate = 256, type = AudioTrackType.DUBBED)
        val result = selectYouTubeAudio(listOf(default, original, dubbed), nowMs = 1_000)!!
        assertEquals(original.content, result.uri)
        assertEquals("audio/mp4", result.mimeType)
        assertEquals(2_000_000_000_000L, result.expiresAt)
        assertTrue(result.headers.getValue("User-Agent").isNotBlank())
    }

    @Test fun rejectsUnsafeExpiredManifestAndDubbedOnlyAudio() {
        listOf(audio("http://googlevideo.com/file"), audio("https://googlevideo.com.evil.example/file"),
            audio("https://user:secret@googlevideo.com/file"), audio("https://googlevideo.com:8443/file"),
            audio("https://googlevideo.com/file?expire=1"), audio("https://googlevideo.com/file?expire=invalid"),
            audio(type = AudioTrackType.DUBBED), audio(method = DeliveryMethod.DASH)).forEach {
            assertNull(selectYouTubeAudio(listOf(it), nowMs = 1_000))
        }
    }

    @Test fun selectsHighestQualitySeparateVideoAndFallsBackToMuxedStreams() {
        val low = video(url = mediaUrl, resolution = "360p")
        val highMuxed = video(url = "$mediaUrl&quality=high", resolution = "720p")
        val highFrameRate = video(url = "$mediaUrl&fps=60", resolution = "720p60")
        val higherResolution = video(url = "$mediaUrl&quality=1080", resolution = "1080p")
        val separate = video(url = "$mediaUrl&videoOnly=1", resolution = "1080p", videoOnly = true)
        val selection = selectYouTubeVideoSelection(listOf(low, highMuxed, highFrameRate, separate), nowMs = 1_000)!!
        assertEquals(separate.content, selection.source.uri)
        assertTrue(selection.requiresAudioSlave)
        assertEquals(highFrameRate.content, selection.muxedFallback?.uri)
        val dashSeparate = video(url = "$mediaUrl&dash=1", resolution = "1440p", videoOnly = true, method = DeliveryMethod.DASH)
        val dashSelection = selectYouTubeVideoSelection(listOf(dashSeparate), nowMs = 1_000)!!
        assertEquals(dashSeparate.content, dashSelection.source.uri)
        assertTrue(dashSelection.requiresAudioSlave)
        val muxed = selectYouTubeVideoSelection(listOf(low, highMuxed, highFrameRate, higherResolution), nowMs = 1_000)!!
        assertEquals(higherResolution.content, muxed.source.uri)
        assertFalse(muxed.requiresAudioSlave)
        listOf(video("http://googlevideo.com/file"), video("https://googlevideo.com.evil.example/file"),
            video("https://googlevideo.com/file?expire=1"), video(isUrl = false, method = DeliveryMethod.DASH)).forEach {
            assertNull(selectYouTubeVideo(listOf(it), nowMs = 1_000))
        }
    }

    @Test fun cachedVideoSelectionExpiresByElapsedAgeAndSignedUrlExpiry() {
        var elapsed = 0L
        var wall = 1_000_000L
        val cache = YouTubeVideoSelectionCache({ elapsed }, { wall })
        val selection = YouTubeVideoSelection(PlayableSource(mediaUrl, expiresAt = wall + 120_000), false)
        cache.put(videoId, selection)
        assertEquals(selection, cache.get(videoId))
        wall += 61_000
        assertNull(cache.get(videoId))

        val secondId = "zB12_cD34-e"
        wall = 1_000_000L
        cache.put(secondId, YouTubeVideoSelection(PlayableSource(mediaUrl), false))
        elapsed += 15 * 60 * 1000L
        assertNotNull(cache.get(secondId))
        elapsed += 15 * 60 * 1000L
        assertNull(cache.get(secondId))
    }

    @Test fun cachedVideoSelectionIsBoundedAndEvictsLeastRecentlyUsed() {
        var elapsed = 0L
        val cache = YouTubeVideoSelectionCache({ elapsed }, { 1_000_000L })
        val selection = YouTubeVideoSelection(PlayableSource(mediaUrl), false)
        repeat(64) { cache.put("video-$it", selection) }
        assertEquals(selection, cache.get("video-0")) // Refresh access order.
        cache.put("video-64", selection)
        assertNull(cache.get("video-1"))
        assertEquals(selection, cache.get("video-0"))
        assertEquals(selection, cache.get("video-64"))
        cache.remove("video-64")
        assertNull(cache.get("video-64"))
    }

    @Test fun invalidTracksReturnWithoutAnyNetworkRequest() = runTest {
        val provider = YouTubeProvider()
        val track = Track("youtube:$videoId", "Song", "Artist", provider = "youtube", sourceUri = youtubeWatchUrl(videoId))
        assertNull(provider.resolve(track.copy(id = "youtube:other")))
        assertNull(provider.resolve(track.copy(sourceUri = mediaUrl)))
        assertNull(provider.resolve(track.copy(provider = "spotify")))
        assertTrue(provider.search("  ").isEmpty())
    }

    private fun audio(url: String = mediaUrl, bitrate: Int = 128, type: AudioTrackType? = null,
        method: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP): AudioStream = AudioStream.Builder()
        .setId("$bitrate-${type ?: "default"}").setContent(url, true).setMediaFormat(MediaFormat.M4A)
        .setAverageBitrate(bitrate).setAudioTrackType(type).setDeliveryMethod(method).build()

    private fun video(url: String = mediaUrl, resolution: String = "720p", videoOnly: Boolean = false,
        method: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP, isUrl: Boolean = true): VideoStream = VideoStream.Builder()
        .setId("video-$resolution").setContent(url, isUrl).setMediaFormat(MediaFormat.MPEG_4)
        .setResolution(resolution).setIsVideoOnly(videoOnly).setDeliveryMethod(method).build()
}
