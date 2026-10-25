package dev.echo.data.spotify

import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.data.provider.YouTubeProvider
import dev.echo.data.provider.automaticRecordingMatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RecordingDiscoveryLiveTest {
    @Test fun spotifyMetadataFindsClearRecordingsAndRelatedSongs() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with runLiveRecommendations and playlistLink", args.getString("runLiveRecommendations") == "true")
        val expected = SpotifyPlaylistLinks().preview(requireNotNull(args.getString("playlistLink"))).tracks.take(3)
        val provider = YouTubeProvider()
        val matches = expected.mapNotNull { song -> automaticRecordingMatch(song, provider.search("${song.title} ${song.artist}".take(200))) }
        assertTrue("At least one of the sample Spotify songs must have a clear title/artist/duration match", matches.isNotEmpty())
        val related = provider.related(matches.first())
        assertTrue("Song-based related recommendations must be available", related.isNotEmpty())
        assertTrue(related.all { it.provider == "youtube" && it.sourceUri != null && it.id != matches.first().id })
        android.util.Log.i("EchoRecommendationCheck", "Matched ${matches.size}/${expected.size} sample songs; ${related.size} related recordings")
        Unit
    }
}
