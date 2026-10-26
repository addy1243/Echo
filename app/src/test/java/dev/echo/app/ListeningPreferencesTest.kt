package dev.echo.app

import dev.echo.core.Track
import dev.echo.data.library.ListeningPreference
import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningPreferencesTest {
    @Test fun neutralHistoryPreservesProviderOrderAndRepeatedEarlySkipsAreExcluded() {
        val songs = (0..5).map { Track("$it", "Song $it", "Artist $it") }
        assertEquals(songs, preferredCandidates(songs, emptyMap(), songs))
        val preferences = mapOf("2" to ListeningPreference(2, 0, 2, 0, 3000))
        assertEquals(songs.filter { it.id != "2" }, preferredCandidates(songs, preferences, songs))
    }

    @Test fun artistPreferenceGentlyReordersRelatedResultsWithoutPromotingDistantResults() {
        val songs = (0..8).map { Track("$it", "Song $it", if (it == 3 || it == 8) "Favorite" else "Artist $it") }
        val known = Track("known", "Known", "Favorite")
        val preferences = mapOf("known" to ListeningPreference(10, 10, 0, 10, 1_000_000))
        val result = preferredCandidates(songs, preferences, listOf(known))
        assertEquals(listOf("0", "1", "3", "2", "4", "5", "6", "8", "7"), result.map { it.id })
    }
}
