package dev.echo.data.provider

import dev.echo.core.Track
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

object TrackMatcher {
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    fun matches(expected: Track, candidate: Track): Boolean {
        if (!expected.isrc.isNullOrBlank() && !candidate.isrc.isNullOrBlank())
            return expected.isrc.equals(candidate.isrc, ignoreCase = true)
        if (expected.durationMs > 0 && candidate.durationMs > 0 && abs(expected.durationMs - candidate.durationMs) > 5_000) return false
        return normalize(expected.title).isNotEmpty() && normalize(expected.title) == normalize(candidate.title) &&
            normalize(expected.artist).isNotEmpty() && normalize(expected.artist) == normalize(candidate.artist)
    }
}
