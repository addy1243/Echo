package dev.echo.desktop

internal class SpaceShortcutRepeatGuard {
    private var held = false

    fun press(): Boolean {
        if (held) return false
        held = true
        return true
    }

    fun release(): Boolean {
        val wasHeld = held
        held = false
        return wasHeld
    }
}

internal class SpaceShortcutInputMode {
    private var pointerInput = false

    fun pointerInput() { pointerInput = true }
    fun keyboardInput() { pointerInput = false }
    fun shouldHandlePlaybackShortcut(textInputFocused: Boolean) = pointerInput && !textInputFocused
}
