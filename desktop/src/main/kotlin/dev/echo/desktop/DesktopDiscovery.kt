package dev.echo.desktop

import dev.echo.app.discoveryCandidates
import dev.echo.app.preferredCandidates
import dev.echo.core.Track
import dev.echo.data.library.ListeningEvent
import dev.echo.data.library.ListeningEventType
import dev.echo.data.library.listeningPreferences
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.YearMonth
import java.util.Locale

data class DesktopDiscoveryResult(val discovery: List<Track>, val newMusic: List<Track>, val artists: List<String>)

fun desktopDiscoveryArtists(library: DesktopLibraryState): List<String> {
    val tracks = library.tracks.associateBy(Track::id)
    val recent = library.history.sortedByDescending { it.occurredAt }.mapNotNull { tracks[it.trackId] }
    return (library.tracks.filter { it.id in library.favorites } + recent + library.tracks)
        .map { it.artist.trim() }.filter { it.isNotEmpty() && !it.equals("Unknown artist", ignoreCase = true) }
        .distinctBy { it.lowercase(Locale.ROOT) }.take(3)
}

fun desktopDiscoveryCandidates(candidates: List<Track>, library: DesktopLibraryState): List<Track> {
    val tracks = library.tracks.associateBy(Track::id)
    val events = library.history.takeLast(5000).mapIndexedNotNull { index, event ->
        val track = tracks[event.trackId] ?: return@mapIndexedNotNull null
        val type = ListeningEventType.entries.find { it.name == event.event } ?: return@mapIndexedNotNull null
        ListeningEvent(index.toLong(), track, type, event.occurredAt, event.listenedMs, event.durationMs)
    }
    return preferredCandidates(discoveryCandidates(candidates, library.tracks), listeningPreferences(events), library.tracks).take(20)
}

fun desktopNewMusicQuery(month: YearMonth = YearMonth.now()): String = "new music releases $month"

suspend fun loadDesktopDiscovery(
    library: DesktopLibraryState,
    search: suspend (String) -> List<Track>,
    month: YearMonth = YearMonth.now(),
): DesktopDiscoveryResult {
    val artists = desktopDiscoveryArtists(library)
    val found = mutableListOf<Track>()
    // shortcut: artist searches seed discovery; use a richer recommendation provider when available.
    for (artist in artists) {
        currentCoroutineContext().ensureActive()
        found += search(artist.take(200)).take(100)
    }
    currentCoroutineContext().ensureActive()
    val fresh = search(desktopNewMusicQuery(month)).filter { it.sourceUri != null }.distinctBy(Track::id).take(20)
    currentCoroutineContext().ensureActive()
    return DesktopDiscoveryResult(desktopDiscoveryCandidates(found, library), fresh, artists)
}
