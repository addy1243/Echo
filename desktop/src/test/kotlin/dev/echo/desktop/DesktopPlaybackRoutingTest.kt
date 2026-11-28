package dev.echo.desktop

import dev.echo.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPlaybackRoutingTest {
    @Test fun onlyExplicitSilentFixturesUseStageSimulation() {
        val fixture = Track("youtube:stage000001", "Stage Signal", "Echo Demo", provider = "youtube")
        assertTrue(isStageFixture(fixture))
        assertFalse(isStageFixture(fixture.copy(sourceUri = "https://www.youtube.com/watch?v=stage000001")))
        assertFalse(isStageFixture(Track("spotify:123", "Song", "Artist", provider = "spotify")))
        assertFalse(isStageFixture(Track("youtube:abcdefghijk", "Song", "Artist", provider = "youtube")))
    }

    @Test fun realLibraryRecordingWinsOverSilentSimulatorCopy() {
        val live = Track("youtube:abcdefghijk", "Song", "Artist", provider = "youtube",
            sourceUri = "https://www.youtube.com/watch?v=abcdefghijk")
        val silentCopy = live.copy(sourceUri = null)
        assertEquals(live, stageTrackLookup(listOf(live), listOf(silentCopy))[live.id])
        assertEquals(silentCopy, stageTrackLookup(emptyList(), listOf(silentCopy))[live.id])
    }
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
