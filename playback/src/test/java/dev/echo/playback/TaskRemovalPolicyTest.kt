package dev.echo.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRemovalPolicyTest {
    @Test fun keepsPlaybackAndStopsWhenPausedOrEnded() {
        assertFalse(shouldStopAfterTaskRemoval(false, false, false, Player.STATE_READY))
        assertFalse(shouldStopAfterTaskRemoval(true, true, true, Player.STATE_READY))
        assertFalse(shouldStopAfterTaskRemoval(true, false, true, Player.STATE_BUFFERING))
        assertFalse(shouldStopAfterTaskRemoval(true, false, true, Player.STATE_READY))
        assertTrue(shouldStopAfterTaskRemoval(true, false, false, Player.STATE_READY))
        assertTrue(shouldStopAfterTaskRemoval(true, false, true, Player.STATE_ENDED))
    }

    @Test fun resumedPlaybackInvalidatesPendingShutdown() {
        assertTrue(shouldStopAfterTaskRemoval(true, false, false, Player.STATE_READY))
        assertFalse(shouldStopAfterTaskRemoval(true, true, true, Player.STATE_READY))
    }
}
