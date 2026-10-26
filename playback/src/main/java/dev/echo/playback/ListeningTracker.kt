package dev.echo.playback

import dev.echo.data.library.ListeningEventType

/** Counts actual playing time; pauses, buffering and source retries are not new plays. */
internal class ListeningTracker(private val record: (String, ListeningEventType, Long, Long) -> Unit) {
    private var id: String? = null
    private var durationMs = 0L
    private var listenedMs = 0L
    private var started = false
    private var failed = false
    private var playingSince: Long? = null

    fun transition(trackId: String?, duration: Long, now: Long, outcome: ListeningEventType, replacement: Boolean = false) {
        if (replacement && trackId == id) return
        finish(outcome, now)
        id = trackId
        durationMs = duration.coerceAtLeast(0)
    }

    fun playing(trackId: String?, duration: Long, isPlaying: Boolean, now: Long) {
        if (id == null && trackId != null) { id = trackId; durationMs = duration.coerceAtLeast(0) }
        account(now)
        durationMs = duration.coerceAtLeast(0)
        if (isPlaying && id != null) {
            failed = false
            if (!started) { started = true; record(id!!, ListeningEventType.PLAYED, 0, durationMs) }
            playingSince = now
        }
    }

    fun error(now: Long) {
        account(now)
        failed = true
        id?.let { record(it, ListeningEventType.ERROR, listenedMs, durationMs) }
    }

    fun finish(outcome: ListeningEventType, now: Long) {
        account(now)
        if (started) id?.let { record(it, if (failed && outcome == ListeningEventType.SKIPPED) ListeningEventType.INTERRUPTED else outcome, listenedMs, durationMs) }
        id = null
        durationMs = 0
        listenedMs = 0
        started = false
        failed = false
    }

    private fun account(now: Long) {
        playingSince?.let { listenedMs += (now - it).coerceAtLeast(0) }
        playingSince = null
    }
}
