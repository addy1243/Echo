package dev.echo.data.provider

import dev.echo.core.Track
import org.junit.Assert.*
import org.junit.Test

class RecordingMatchTest {
    private val song = Track("spotify:a", "Déjà Vu", "Artist, Guest", durationMs = 180_000)
    private val candidate = Track("youtube:a", "deja vu (Official Audio)", "Artist - Topic", durationMs = 180_001, provider = "youtube", sourceUri = "https://example.com/a")
    @Test fun matchesCreditedArtistTitleAndDuration() { assertEquals(candidate, automaticRecordingMatch(song, listOf(candidate))) }
    @Test fun wrongArtistVersionAndDurationNeedReview() {
        listOf(candidate.copy(artist = "Cover artist"), candidate.copy(title = "Deja Vu (Live)"), candidate.copy(durationMs = 220_000)).forEach {
            assertNull(automaticRecordingMatch(song, listOf(it)))
        }
    }
    @Test fun ambiguousRecordingsNeedReviewUnlessOneTopicUpload() {
        val other = candidate.copy(id = "youtube:b", artist = "Artist")
        assertEquals(candidate, automaticRecordingMatch(song, listOf(other, candidate)))
        assertNull(automaticRecordingMatch(song, listOf(other, other.copy(id = "youtube:c", durationMs = 183_000))))
        assertEquals(other, automaticRecordingMatch(song, listOf(other, other.copy(id = "youtube:d"))))
    }
}
