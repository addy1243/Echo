package dev.echo.data.provider

import dev.echo.core.Track
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
}
