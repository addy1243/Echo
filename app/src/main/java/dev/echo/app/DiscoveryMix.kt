package dev.echo.app

import dev.echo.core.Track
import java.util.Locale

private fun Track.songKey() = title.trim().lowercase(Locale.ROOT) to artist.trim().lowercase(Locale.ROOT)

internal fun discoveryCandidates(candidates: List<Track>, known: List<Track>): List<Track> {
    val knownIds = known.map { it.id }.toSet()
    val knownSongs = known.map { it.songKey() }.toSet()
    return candidates.filter { it.sourceUri != null && it.id !in knownIds && it.songKey() !in knownSongs }
        .distinctBy { it.id }.distinctBy { it.songKey() }
}

internal fun smartMix(original: List<Track>, discoveries: List<Track>): List<Track> {
    val playable = original.filter { it.sourceUri != null }.shuffled()
    require(playable.isNotEmpty()) { "Choose recordings for your imported songs first" }
    val additions = discoveryCandidates(discoveries, original).shuffled().iterator()
    return buildList {
        playable.chunked(3).forEach { chunk -> addAll(chunk); if (additions.hasNext()) add(additions.next()) }
    }
}
