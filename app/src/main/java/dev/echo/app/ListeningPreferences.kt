package dev.echo.app

import dev.echo.core.Track
import dev.echo.data.library.ListeningPreference
import java.util.Locale

/** A small nudge within provider results, rather than replacing recording-related relevance. */
internal fun preferredCandidates(candidates: List<Track>, preferences: Map<String, ListeningPreference>, known: List<Track>): List<Track> {
    val artists = known.groupBy { it.artist.trim().lowercase(Locale.ROOT) }.mapValues { (_, songs) ->
        songs.sumOf { preferences[it.id]?.score ?: 0 }.coerceIn(-2, 2)
    }
    return candidates.withIndex().filter { (_, track) ->
        val preference = preferences[track.id]
        preference == null || preference.earlySkips < 2 || preference.score >= 0
    }.sortedBy { (index, track) ->
        val own = preferences[track.id]?.score?.coerceIn(-2, 2) ?: 0
        val artist = artists[track.artist.trim().lowercase(Locale.ROOT)] ?: 0
        index - (own + artist).coerceIn(-3, 3)
    }.map { it.value }
}
