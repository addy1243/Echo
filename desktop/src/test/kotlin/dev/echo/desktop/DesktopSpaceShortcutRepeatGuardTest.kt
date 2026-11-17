package dev.echo.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopSpaceShortcutRepeatGuardTest {
    @Test fun playbackShortcutAppliesAfterPointerFocusButKeyboardControlsKeepSpace() {
        val mode = SpaceShortcutInputMode()
        mode.pointerInput()
        assertEquals(true, mode.shouldHandlePlaybackShortcut(textInputFocused = false))
        assertEquals(false, mode.shouldHandlePlaybackShortcut(textInputFocused = true))
        mode.keyboardInput()
        assertEquals(false, mode.shouldHandlePlaybackShortcut(textInputFocused = false))
    }

    @Test fun consumesKeyUpOnlyWhenSpaceShortcutWasHandled() {
        val guard = SpaceShortcutRepeatGuard()
        assertEquals(false, guard.release())
        assertEquals(true, guard.press())
        assertEquals(true, guard.release())
        assertEquals(false, guard.release())
    }

    @Test fun focusLossResetsHeldSpaceWhenKeyUpIsNeverDelivered() {
        val guard = SpaceShortcutRepeatGuard()
        assertEquals(true, guard.press())
        assertEquals(false, guard.press())
        guard.release()
        assertEquals(true, guard.press())
    }
}
