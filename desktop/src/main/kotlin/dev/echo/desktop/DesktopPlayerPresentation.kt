package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedGroupV2
import dev.echo.data.firebase.ConnectedPlayerSnapshot

internal data class SelectedRemotePlayer(val device: ConnectedDevice, val reconnecting: Boolean)

internal data class DesktopPlayerPresentationState(
    val track: Track?,
    val title: String?,
    val artist: String?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val group: ConnectedGroupV2?,
    val reconnecting: Boolean,
)

internal fun selectedRemotePlayer(
    selectedId: String?,
    devices: List<ConnectedDevice>,
    lastConfirmed: ConnectedDevice?,
): SelectedRemotePlayer? {
    selectedId ?: return null
    val remembered = lastConfirmed?.takeIf { it.id == selectedId }
    val current = devices.firstOrNull { it.id == selectedId }
    if (current != null && (remembered == null || current.sessionId == remembered.sessionId))
        return SelectedRemotePlayer(current, reconnecting = !current.controlEnabled)
    return remembered?.copy(controlEnabled = false)?.let { SelectedRemotePlayer(it, reconnecting = true) }
}

internal fun nextIndependentPlayer(
    devices: List<ConnectedDevice>,
    selectedRemoteId: String?,
    direction: Int,
    includeLocal: Boolean = true,
    selectedRemoteSessionId: String? = null,
): ConnectedDevice? = independentPlayersAfterFocus(
    devices, selectedRemoteId, direction, includeLocal, selectedRemoteSessionId,
).firstOrNull()

internal fun independentPlayersAfterFocus(
    devices: List<ConnectedDevice>,
    selectedRemoteId: String?,
    direction: Int,
    includeLocal: Boolean = true,
    selectedRemoteSessionId: String? = null,
): List<ConnectedDevice?> {
    if (devices.isEmpty()) return emptyList()
    val localOffset = if (includeLocal) 1 else 0
    val targets = buildList<ConnectedDevice?> {
        if (includeLocal) add(null)
        addAll(devices)
    }
    if (targets.size <= 1) return emptyList()
    val selectedIndex = if (selectedRemoteId == null) {
        if (includeLocal) 0 else return devices
    } else {
        devices.indexOfFirst { it.id == selectedRemoteId &&
            (selectedRemoteSessionId == null || it.sessionId == selectedRemoteSessionId) }
            .takeIf { it >= 0 }?.plus(localOffset) ?: return emptyList()
    }
    val step = if (direction < 0) -1 else 1
    return (1 until targets.size).map { offset -> targets[(selectedIndex + step * offset + targets.size) % targets.size] }
}

internal fun independentPlayerDeckOrder(
    devices: List<ConnectedDevice>,
    selectedRemoteId: String?,
    includeLocal: Boolean = true,
    selectedRemoteSessionId: String? = null,
): List<ConnectedDevice?> {
    val targets = buildList<ConnectedDevice?> {
        if (includeLocal) add(null)
        addAll(devices)
    }
    if (targets.size <= 1) return targets
    val localOffset = if (includeLocal) 1 else 0
    val selectedIndex = if (selectedRemoteId == null) {
        if (includeLocal) 0 else -1
    } else {
        devices.indexOfFirst { it.id == selectedRemoteId &&
            (selectedRemoteSessionId == null || it.sessionId == selectedRemoteSessionId) }
            .takeIf { it >= 0 }?.plus(localOffset) ?: -1
    }
    if (selectedIndex !in targets.indices) return targets
    return listOf(targets[selectedIndex]) + independentPlayersAfterFocus(
        devices, selectedRemoteId, direction = 1, includeLocal = includeLocal,
        selectedRemoteSessionId = selectedRemoteSessionId,
    )
}

internal fun connectedPresentationTrack(snapshot: ConnectedPlayerSnapshot, tracks: Map<String, Track>): Track? {
    val id = snapshot.trackId ?: return null
    tracks[id]?.let { return it }
    if (!id.matches(YOUTUBE_TRACK_ID) || snapshot.title.isNullOrBlank()) return null
    return Track(id = id, title = snapshot.title, artist = snapshot.artist.orEmpty(), durationMs = snapshot.durationMs,
        artworkUri = "https://i.ytimg.com/vi/${id.removePrefix("youtube:")}/hqdefault.jpg", provider = "youtube")
}

internal fun desktopPlayerPresentation(
    playback: DesktopPlaybackState,
    selectedRemote: SelectedRemotePlayer?,
    group: ConnectedGroupV2?,
    tracks: Map<String, Track>,
    ownDeviceId: String,
): DesktopPlayerPresentationState {
    val focusedRemote = selectedRemote.takeIf { group == null }
    val recording = group?.timeline?.let { timeline -> timeline.queue.entries.getOrNull(timeline.cursor)?.recording }
    val occurrenceId = group?.timeline?.let { timeline -> timeline.queue.entries.getOrNull(timeline.cursor)?.id }
    val localOccurrenceMatches = occurrenceId != null && group?.members?.firstOrNull { it.deviceId == ownDeviceId }
        ?.observedOccurrenceId == occurrenceId
    val track = when {
        focusedRemote != null -> connectedPresentationTrack(focusedRemote.device.player, tracks)
        group != null && recording != null -> tracks[recording.id] ?: Track(
            id = recording.id, title = recording.title, artist = recording.artist,
            durationMs = recording.durationMs, provider = "youtube",
            sourceUri = "https://www.youtube.com/watch?v=${recording.id.removePrefix("youtube:")}",
        )
        group != null -> null
        else -> playback.current
    }
    val title = when {
        focusedRemote != null -> focusedRemote.device.player.title ?: if (focusedRemote.reconnecting) "Reconnecting to player" else "Ready to play"
        group != null -> recording?.title ?: "Preparing shared player"
        else -> playback.current?.title
    }
    val artist = when {
        focusedRemote != null -> focusedRemote.device.player.artist.orEmpty()
        group != null -> recording?.artist.orEmpty()
        else -> playback.current?.artist
    }
    val duration = when {
        focusedRemote != null -> focusedRemote.device.player.durationMs
        group != null -> recording?.durationMs ?: 0L
        else -> playback.durationMs
    }
    val groupPosition = if (localOccurrenceMatches) playback.positionMs
        else group?.timeline?.positionMs ?: 0L
    val position = focusedRemote?.device?.player?.positionMs ?: if (group != null) groupPosition else playback.positionMs
    val playing = focusedRemote?.device?.player?.isPlaying ?: group?.timeline?.playing ?: desktopPlaybackIsActive(playback)
    return DesktopPlayerPresentationState(track, title, artist, playing, position.coerceAtLeast(0),
        duration.coerceAtLeast(0), group, focusedRemote?.reconnecting == true)
}

private val YOUTUBE_TRACK_ID = Regex("youtube:[A-Za-z0-9_-]{11}")
