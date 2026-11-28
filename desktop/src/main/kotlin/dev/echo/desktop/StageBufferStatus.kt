package dev.echo.desktop

import dev.echo.core.Track

internal enum class StageBufferTransferPhase {
    IDLE, WAITING, PREPARING, DOWNLOADING, CAPTURING, READY, UNAVAILABLE, FAILED,
}

internal data class StageBufferTransfer(
    val phase: StageBufferTransferPhase,
    val receivedBytes: Long = 0,
    val totalBytes: Long? = null,
)

internal data class StageBufferPosition(
    val offset: Int,
    val track: Track?,
    val audio: StageBufferTransfer,
    val video: StageBufferTransfer,
)

internal data class StageBufferStatus(val positions: List<StageBufferPosition>)

/** Progress emitted synchronously by a cache prepare call on its caller's coroutine. */
internal data class StageCacheProgress(
    val phase: StageBufferTransferPhase,
    val receivedBytes: Long = 0,
    val totalBytes: Long? = null,
)

internal fun reportStageCacheProgress(
    callback: (StageCacheProgress) -> Unit,
    progress: StageCacheProgress,
) {
    try {
        callback(progress)
    } catch (_: Exception) {
        // Status observers must not change cache preparation behavior.
    }
}
