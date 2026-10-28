package dev.echo.desktop

import dev.echo.app.discoveryCandidates
import dev.echo.app.preferredCandidates
import dev.echo.app.smartMix
import dev.echo.app.smartShuffleAnchor
import dev.echo.core.Track
import dev.echo.data.library.ListeningEvent
import dev.echo.data.library.ListeningEventType
import dev.echo.data.library.listeningPreferences
import dev.echo.data.provider.automaticRecordingMatch
import kotlin.random.Random

data class DesktopSmartShuffleResult(val queue: List<Track>, val anchor: Track)

/** Preparation never changes playback; the caller checks the occurrence again before applying. */
suspend fun prepareDesktopSmartShuffle(
    items: List<Track>,
    retainedCurrent: Track?,
    library: DesktopLibraryState,
    related: suspend (Track) -> List<Track>,
    search: suspend (String) -> List<Track>,
    random: Random = Random.Default,
): DesktopSmartShuffleResult {
    val anchor = smartShuffleAnchor(items, retainedCurrent?.id, random)
    val seed = if (anchor.provider == "youtube") anchor else
        automaticRecordingMatch(anchor, search("${anchor.title} ${anchor.artist}".take(200)))
    require(seed != null) { "No clear online recording for this song. Use normal shuffle or choose a recording first." }
    val tracks = library.tracks.associateBy(Track::id)
    val history = library.history.takeLast(5000).mapIndexedNotNull { index, event ->
        val track = tracks[event.trackId] ?: return@mapIndexedNotNull null
        val type = ListeningEventType.entries.find { it.name == event.event } ?: return@mapIndexedNotNull null
        ListeningEvent(index.toLong(), track, type, event.occurredAt, event.listenedMs, event.durationMs)
    }
    val recent = history.asReversed().map { it.track }.distinctBy(Track::id).take(10)
    val suggestions = preferredCandidates(
        discoveryCandidates(related(seed), items + recent), listeningPreferences(history), library.tracks,
    ).take(10)
    require(suggestions.isNotEmpty()) { "No related songs are available for this recording. Your queue is unchanged." }
    return DesktopSmartShuffleResult(smartMix(items, suggestions, anchor.id), anchor)
}
