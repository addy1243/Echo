package dev.echo.desktop

import androidx.compose.ui.input.key.Key

internal enum class DesktopShortcutAction {
    TOGGLE_PLAYBACK, PREVIOUS, NEXT, SEEK_BACK, SEEK_FORWARD, VOLUME_DOWN, VOLUME_UP, MUTE,
}

internal fun desktopShortcutAction(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean): DesktopShortcutAction? = when {
    key == Key.Spacebar && !ctrl && !alt -> DesktopShortcutAction.TOGGLE_PLAYBACK
    ctrl && shift && key == Key.DirectionRight -> DesktopShortcutAction.SEEK_FORWARD
    ctrl && shift && key == Key.DirectionLeft -> DesktopShortcutAction.SEEK_BACK
    ctrl && key == Key.DirectionRight -> DesktopShortcutAction.NEXT
    ctrl && key == Key.DirectionLeft -> DesktopShortcutAction.PREVIOUS
    ctrl && key == Key.DirectionUp -> DesktopShortcutAction.VOLUME_UP
    ctrl && key == Key.DirectionDown -> DesktopShortcutAction.VOLUME_DOWN
    ctrl && key == Key.M -> DesktopShortcutAction.MUTE
    else -> null
}
