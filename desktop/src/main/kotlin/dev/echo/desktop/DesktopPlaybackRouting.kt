package dev.echo.desktop

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
