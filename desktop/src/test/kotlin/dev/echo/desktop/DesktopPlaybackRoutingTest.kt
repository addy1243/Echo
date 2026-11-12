package dev.echo.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopPlaybackRoutingTest {
    @Test fun routesFocusedRemoteShortcutsToRemoteOnlyWhenAvailable() {
        assertEquals(DesktopFocusedPlaybackRoute.REMOTE, desktopFocusedPlaybackRoute(
            remoteFocused = true, remoteAvailable = true, groupFocused = false, stagingMode = false))
        assertEquals(DesktopFocusedPlaybackRoute.UNAVAILABLE, desktopFocusedPlaybackRoute(
            remoteFocused = true, remoteAvailable = false, groupFocused = false, stagingMode = false))
    }

    @Test fun groupAndStageTakeTheirIntendedRoutesBeforeLocalPlayback() {
        assertEquals(DesktopFocusedPlaybackRoute.GROUP, desktopFocusedPlaybackRoute(
            remoteFocused = false, remoteAvailable = false, groupFocused = true, stagingMode = false))
        assertEquals(DesktopFocusedPlaybackRoute.STAGE, desktopFocusedPlaybackRoute(
            remoteFocused = false, remoteAvailable = false, groupFocused = false, stagingMode = true))
        assertEquals(DesktopFocusedPlaybackRoute.LOCAL, desktopFocusedPlaybackRoute(
            remoteFocused = false, remoteAvailable = false, groupFocused = false, stagingMode = false))
    }
}
