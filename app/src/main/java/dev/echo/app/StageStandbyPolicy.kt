package dev.echo.app

/** Automatic Standby settings default to off. */
internal data class StageStandbySettings(
    val automaticEnabled: Boolean = false,
    val chargingOnly: Boolean = false,
    val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    val cinema: Boolean = true, // Keep the saved style value for compatibility.
) {
    init { require(idleTimeoutMs > 0) }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 5 * 60 * 1000L
    }
}

/** Unknown charging state fails closed when charging-only mode is selected. */
internal fun shouldEnterStageStandby(
    settings: StageStandbySettings,
    idleForMs: Long,
    appForeground: Boolean,
    charging: Boolean?,
): Boolean = settings.automaticEnabled && appForeground && idleForMs >= settings.idleTimeoutMs &&
    (!settings.chargingOnly || charging == true)

/** Keep the idle clock monotonic and restart it when the app resumes or receives input. */
internal class StageStandbyIdleClock(startedAtMs: Long) {
    private var lastActivityMs = startedAtMs
    private var foreground = false

    fun recordInteraction(nowMs: Long) { lastActivityMs = nowMs }

    fun setForeground(value: Boolean, nowMs: Long) {
        if (foreground != value) lastActivityMs = nowMs
        foreground = value
    }

    fun idleFor(nowMs: Long): Long = (nowMs - lastActivityMs).coerceAtLeast(0)

    fun shouldEnter(settings: StageStandbySettings, nowMs: Long, charging: Boolean?): Boolean =
        shouldEnterStageStandby(settings, idleFor(nowMs), foreground, charging)
}
