package dev.echo.desktop

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopKeyboardShortcutsTest {
    @Test fun mapsPlaybackNavigationAndSeekingShortcuts() {
        assertEquals(DesktopShortcutAction.TOGGLE_PLAYBACK, desktopShortcutAction(Key.Spacebar, false, false, false))
        assertEquals(DesktopShortcutAction.NEXT, desktopShortcutAction(Key.DirectionRight, true, false, false))
        assertEquals(DesktopShortcutAction.PREVIOUS, desktopShortcutAction(Key.DirectionLeft, true, false, false))
        assertEquals(DesktopShortcutAction.SEEK_FORWARD, desktopShortcutAction(Key.DirectionRight, true, true, false))
        assertEquals(DesktopShortcutAction.SEEK_BACK, desktopShortcutAction(Key.DirectionLeft, true, true, false))
    }

    @Test fun mapsLaptopVolumeAndMuteAndLeavesOrdinaryTypingAlone() {
        assertEquals(DesktopShortcutAction.VOLUME_UP, desktopShortcutAction(Key.DirectionUp, true, false, false))
        assertEquals(DesktopShortcutAction.VOLUME_DOWN, desktopShortcutAction(Key.DirectionDown, true, false, false))
        assertEquals(DesktopShortcutAction.MUTE, desktopShortcutAction(Key.M, true, false, false))
        assertNull(desktopShortcutAction(Key.Spacebar, true, false, false))
        assertNull(desktopShortcutAction(Key.Spacebar, false, false, true))
        assertNull(desktopShortcutAction(Key.DirectionLeft, false, false, false))
    }

    @Test fun heldSpaceOnlyTogglesOnceUntilKeyUp() {
        val guard = SpaceShortcutRepeatGuard()
        assertEquals(true, guard.press())
        assertEquals(false, guard.press())
        assertEquals(false, guard.press())
        guard.release()
        assertEquals(true, guard.press())
    }

    @Test fun focusLossResetsHeldSpaceWhenKeyUpIsNeverDelivered() {
        val guard = SpaceShortcutRepeatGuard()
        assertEquals(true, guard.press())
        assertEquals(false, guard.press())
        guard.release()
        assertEquals(true, guard.press())
    }
}
