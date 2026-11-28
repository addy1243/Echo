package dev.echo.desktop

import dev.echo.core.Track

internal fun isStageFixture(track: Track): Boolean =
    track.provider == "youtube" && track.sourceUri == null &&
        track.id in setOf("youtube:stage000001", "youtube:stage000002", "youtube:stage000003")

internal fun stageTrackLookup(library: List<Track>, simulated: List<Track>): Map<String, Track> =
    (simulated + library).associateBy(Track::id)

internal enum class DesktopFocusedPlaybackRoute { LOCAL, REMOTE, GROUP, STAGE, UNAVAILABLE }

internal fun desktopFocusedPlaybackRoute(
    remoteFocused: Boolean,
    remoteAvailable: Boolean,
    groupFocused: Boolean,
    stagingMode: Boolean,
): DesktopFocusedPlaybackRoute = when {
    groupFocused -> DesktopFocusedPlaybackRoute.GROUP
    remoteFocused -> if (remoteAvailable) DesktopFocusedPlaybackRoute.REMOTE else DesktopFocusedPlaybackRoute.UNAVAILABLE
    stagingMode -> DesktopFocusedPlaybackRoute.STAGE
    else -> DesktopFocusedPlaybackRoute.LOCAL
}
