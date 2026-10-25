package dev.echo.data.provider

import dev.echo.core.Track
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

private fun recordingText(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

private fun songTitle(value: String) = recordingText(value.replace(
    Regex("(?i)\\s*[\\[(](?:official (?:audio|music video|video)|audio|lyrics?|lyric video)[\\])]"), ""))

/** Only identity evidence counts: title, credited artist and duration, never search rank alone. */
fun automaticRecordingMatch(expected: Track, candidates: List<Track>): Track? {
    val artists = expected.artist.split(',', ';', '&').map(::recordingText).filter { it.isNotEmpty() }
    val matches = candidates.filter { candidate ->
        val credited = candidate.artist.replace(Regex("(?i)\\s*-\\s*Topic$"), "").split(',', ';', '&').map(::recordingText)
        candidate.sourceUri != null && songTitle(expected.title).isNotEmpty() &&
            songTitle(expected.title) == songTitle(candidate.title) && credited.any { it.isNotEmpty() && it in artists } &&
            (expected.durationMs <= 0 || candidate.durationMs > 0 && abs(expected.durationMs - candidate.durationMs) <= 5_000)
    }.distinctBy { it.id }
    if (matches.size == 1) return matches.single()
    // Prefer one official Topic upload; conflicting recordings stay available for manual review.
    matches.filter { it.artist.endsWith(" - Topic", true) }.singleOrNull()?.let { return it }
    return matches.firstOrNull()?.takeIf { expected.durationMs > 0 && matches.maxOf { it.durationMs } - matches.minOf { it.durationMs } <= 1_500 }
}
