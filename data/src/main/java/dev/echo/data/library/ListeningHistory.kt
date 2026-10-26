package dev.echo.data.library

import dev.echo.core.Track

enum class ListeningEventType { PLAYED, SKIPPED, COMPLETED, INTERRUPTED, ERROR }

data class ListeningEvent(
    val id: Long,
    val track: Track,
    val event: ListeningEventType,
    val occurredAt: Long,
    val listenedMs: Long,
    val durationMs: Long,
)

data class ListeningPreference(
    val plays: Int,
    val completions: Int,
    val earlySkips: Int,
    val qualifiedPlays: Int,
    val listenedMs: Long,
) {
    val score: Int get() = completions * 3 + (qualifiedPlays - completions) - earlySkips * 3
}

internal fun listeningPreferences(events: List<ListeningEvent>): Map<String, ListeningPreference> =
    events.groupBy { it.track.id }.mapValues { (_, entries) ->
        val terminal = entries.filter { it.event != ListeningEventType.PLAYED && it.event != ListeningEventType.ERROR }
        fun ListeningEvent.substantiveCompletion() = event == ListeningEventType.COMPLETED &&
            listenedMs >= (if (durationMs > 0) minOf(30_000L, durationMs / 2).coerceAtLeast(1) else 30_000L)
        ListeningPreference(
            plays = entries.count { it.event == ListeningEventType.PLAYED },
            completions = entries.count { it.substantiveCompletion() },
            earlySkips = entries.count { it.event == ListeningEventType.SKIPPED && it.listenedMs < 30_000 &&
                (it.durationMs <= 0 || it.listenedMs < it.durationMs / 2) },
            qualifiedPlays = terminal.count { it.substantiveCompletion() || it.listenedMs >= 30_000 },
            listenedMs = terminal.sumOf { it.listenedMs },
        )
    }
