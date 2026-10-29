package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedPlayerSnapshot

internal data class SelectedRemotePlayer(val device: ConnectedDevice, val reconnecting: Boolean)

internal fun selectedRemotePlayer(
    selectedId: String?,
    devices: List<ConnectedDevice>,
    lastConfirmed: ConnectedDevice?,
): SelectedRemotePlayer? {
    selectedId ?: return null
    devices.firstOrNull { it.id == selectedId }?.let { return SelectedRemotePlayer(it, reconnecting = !it.controlEnabled) }
    return lastConfirmed?.takeIf { it.id == selectedId }?.let { SelectedRemotePlayer(it, reconnecting = true) }
}

internal fun connectedPresentationTrack(snapshot: ConnectedPlayerSnapshot, tracks: Map<String, Track>): Track? {
    val id = snapshot.trackId ?: return null
    tracks[id]?.let { return it }
    if (!id.matches(YOUTUBE_TRACK_ID) || snapshot.title.isNullOrBlank()) return null
    return Track(id = id, title = snapshot.title, artist = snapshot.artist.orEmpty(), durationMs = snapshot.durationMs,
        artworkUri = "https://i.ytimg.com/vi/${id.removePrefix("youtube:")}/hqdefault.jpg", provider = "youtube")
}

private val YOUTUBE_TRACK_ID = Regex("youtube:[A-Za-z0-9_-]{11}")
