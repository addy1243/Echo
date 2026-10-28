package dev.echo.desktop

import dev.echo.core.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class DesktopSmartShuffleTest {
    private fun song(id: String, title: String = "Song $id", artist: String = "Artist $id") =
        Track(id, title, artist, durationMs = 180_000, provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=$id")
    private val songs = (1..6).map { song("track$it") }
    private val relatedSong = song("new")

    @Test fun retainsAnchorAndAllDuplicatePlaylistOccurrences() = runBlocking {
        val original = songs + songs[2]
        var requested: Track? = null
        val result = prepareDesktopSmartShuffle(original, songs[2], DesktopLibraryState(),
            related = { requested = it; listOf(relatedSong) }, search = { error("Must not search for YouTube seeds") })
        assertEquals(songs[2], requested)
        assertEquals(songs[2], result.queue.first())
        assertEquals(original.groupingBy { it.id }.eachCount(), result.queue.filter { it.id != relatedSong.id }.groupingBy { it.id }.eachCount())
        assertEquals(relatedSong, result.queue[3])
    }

    @Test fun freshSessionsChooseRandomAnchorRatherThanStaleFirstSong() = runBlocking {
        val starts = (0..15).map { seed ->
            val result = prepareDesktopSmartShuffle(songs, null, DesktopLibraryState(), { listOf(relatedSong) },
                { error("Unexpected search") }, Random(seed))
            assertEquals(songs.random(Random(seed)), result.anchor)
            assertEquals(result.anchor, result.queue.first())
            result.anchor.id
        }
        assertTrue(starts.toSet().size > 1)
    }

    @Test fun excludesKnownSongAliasesAndRecentSongsWithoutArtistSearchFallback() = runBlocking {
        val recent = song("recent")
        val duplicateTitle = song("alias", songs[0].title, songs[0].artist)
        val library = DesktopLibraryState(tracks = listOf(recent), history = listOf(event("recent", "COMPLETED", 45_000)))
        val result = prepareDesktopSmartShuffle(songs, songs[0], library,
            { listOf(songs[0], duplicateTitle, recent, relatedSong, relatedSong) }, { error("Unexpected search") })
        assertEquals(1, result.queue.count { it.id == relatedSong.id })
        assertFalse(result.queue.any { it.id == "alias" || it.id == "recent" })
    }

    @Test fun localAnchorUsesOnlyClearRecordingMatchForRelatedLookup() = runBlocking {
        val local = songs[0].copy(id = "desktop:file", provider = "local", sourceUri = "file:///music.wav")
        var seed: Track? = null
        var query = ""
        val result = prepareDesktopSmartShuffle(listOf(local) + songs.drop(1), local, DesktopLibraryState(),
            { seed = it; listOf(relatedSong) }, { query = it; listOf(songs[0], song("unrelated")) })
        assertEquals("${local.title} ${local.artist}", query)
        assertEquals(songs[0], seed)
        assertEquals(local, result.queue.first())
    }

    @Test fun unmatchedRecordingDoesNotFallBackToArtistRecommendations() = runBlocking {
        val local = songs[0].copy(provider = "local", sourceUri = "file:///music.wav")
        var relatedCalls = 0
        try {
            prepareDesktopSmartShuffle(listOf(local), local, DesktopLibraryState(),
                { relatedCalls++; listOf(relatedSong) }, { listOf(song("unrelated")) })
            fail("Expected unmatched recording rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, relatedCalls)
    }

    @Test fun repeatedEarlySkipsExcludeCandidatesButErrorsDoNot() = runBlocking {
        val skipped = song("skip")
        val failed = song("failed")
        val recentOther = (0..10).map { song("recent$it") }
        val history = listOf(event(skipped.id, "SKIPPED", 1000), event(skipped.id, "SKIPPED", 1000),
            event(failed.id, "ERROR", 1000)) + recentOther.map { event(it.id, "PLAYED", 0) }
        val library = DesktopLibraryState(tracks = listOf(skipped, failed) + recentOther, history = history)
        val result = prepareDesktopSmartShuffle(songs, songs[0], library, { listOf(skipped, failed) }, { error("Unexpected search") })
        assertFalse(result.queue.any { it.id == skipped.id })
        assertTrue(result.queue.any { it.id == failed.id })
    }

    @Test fun emptyRelatedResultsRejectInsteadOfChangingQueue() = runBlocking {
        try {
            prepareDesktopSmartShuffle(songs, songs[0], DesktopLibraryState(), { emptyList() }, { error("Unexpected search") })
            fail("Expected missing recommendations rejection")
        } catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("queue is unchanged")) }
    }

    @Test fun metadataOnlyPlaylistFailsBeforeNetworkLookup() = runBlocking {
        try {
            prepareDesktopSmartShuffle(listOf(songs[0].copy(sourceUri = null)), null, DesktopLibraryState(),
                { error("Unexpected related lookup") }, { error("Unexpected search") })
            fail("Expected unplayable playlist rejection")
        } catch (_: IllegalArgumentException) { }
    }

    private fun event(trackId: String, type: String, listened: Long) =
        DesktopHistoryEntry("$trackId-$type-$listened", trackId, type, System.currentTimeMillis(), listened, 180_000)
}
