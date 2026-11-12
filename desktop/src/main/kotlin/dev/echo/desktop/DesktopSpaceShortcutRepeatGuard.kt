package dev.echo.desktop

internal class SpaceShortcutRepeatGuard {
    private var held = false

    fun press(): Boolean {
        if (held) return false
        held = true
        return true
    }

    fun release() { held = false }
}
