package dev.echo.playback

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process timer state; the playback service owns ticking and the pause action. */
@Singleton
class SleepTimerController internal constructor(private val elapsedRealtime: () -> Long) {
    @Inject constructor() : this({ SystemClock.elapsedRealtime() })

    private var deadlineMs: Long? = null
    private val _remainingMs = MutableStateFlow<Long?>(null)
    val remainingMs = _remainingMs.asStateFlow()

    fun start(minutes: Int) {
        require(minutes in setOf(15, 30, 45, 60)) { "Choose a 15, 30, 45 or 60 minute sleep timer" }
        startAfter(minutes * 60_000L)
    }

    @Synchronized fun startAfter(durationMs: Long) {
        require(durationMs in 1..86_400_000) { "Sleep timer must be positive and at most 24 hours" }
        deadlineMs = elapsedRealtime() + durationMs
        _remainingMs.value = durationMs
    }

    @Synchronized fun cancel() {
        deadlineMs = null
        _remainingMs.value = null
    }

    /** Returns true once per expiration. A delayed tick still expires against the monotonic deadline. */
    @Synchronized internal fun tick(): Boolean {
        val deadline = deadlineMs ?: return false
        val remaining = deadline - elapsedRealtime()
        if (remaining <= 0) { cancel(); return true }
        _remainingMs.value = remaining
        return false
    }
}
