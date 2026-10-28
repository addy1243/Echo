package dev.echo.desktop

import dev.echo.core.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth

class DesktopDiscoveryTest {
    private fun song(id: String, artist: String = "Artist $id", title: String = "Song $id") =
        Track(id, title, artist, provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=$id")

    @Test fun favoritesThenActualRecentHistorySeedAtMostThreeUniqueArtists() {
        val tracks = listOf(song("a", " Alpha "), song("b", "Beta"), song("c", "ALPHA"), song("d", "Unknown artist"), song("e", "Echo"))
        val history = listOf(DesktopHistoryEntry("1", "e", "PLAYED", 50, 0, 0), DesktopHistoryEntry("2", "b", "PLAYED", 20, 0, 0))
        assertEquals(listOf("Alpha", "Echo", "Beta"), desktopDiscoveryArtists(DesktopLibraryState(tracks = tracks, favorites = setOf("a"), history = history)))
    }

    @Test fun excludesKnownAliasesDuplicatesAndMetadataOnlyTracks() {
        val known = song("known")
        val candidate = song("new")
        assertEquals(listOf(candidate), desktopDiscoveryCandidates(listOf(known, known.copy(id = "alias"), candidate, candidate,
            song("metadata").copy(sourceUri = null)), DesktopLibraryState(tracks = listOf(known))))
    }

    @Test fun boundedArtistSearchesAndExplicitCurrentMonthQuery() = runBlocking {
        val queries = mutableListOf<String>()
        val library = DesktopLibraryState(tracks = (1..8).map { song("$it") })
        val result = loadDesktopDiscovery(library, { query -> queries += query; listOf(song(query)) }, YearMonth.of(2026, 10))
        assertEquals(listOf("Artist 1", "Artist 2", "Artist 3", "new music releases 2026-10"), queries)
        assertEquals(3, result.discovery.size)
        assertEquals(1, result.newMusic.size)
    }

    @Test fun emptyLibraryNeverFabricatesPersonalSeedsAndCancellationStopsSearches() = runBlocking {
        var calls = 0
        val result = loadDesktopDiscovery(DesktopLibraryState(), { calls++; emptyList() })
        assertEquals(1, calls)
        assertTrue(result.discovery.isEmpty())
        calls = 0
        try {
            loadDesktopDiscovery(DesktopLibraryState(tracks = listOf(song("a"), song("b"))), { calls++; throw CancellationException() })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }
}
