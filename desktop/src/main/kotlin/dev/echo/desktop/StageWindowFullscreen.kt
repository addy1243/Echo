package dev.echo.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser.*
import com.sun.jna.ptr.IntByReference
import java.awt.Frame

internal class StageForegroundWindow private constructor(
    private val handle: HWND,
    private val processId: Int,
    private val threadId: Int,
) {
    fun restoreIfEchoForeground() {
        if (!windowsInputDesktopInteractive()) return
        runCatching {
            val user32 = User32.INSTANCE
            val targetProcess = IntByReference()
            if (user32.GetWindowThreadProcessId(handle, targetProcess) != threadId ||
                targetProcess.value != processId || !user32.IsWindowVisible(handle)) return@runCatching

            val foreground = user32.GetForegroundWindow() ?: return@runCatching
            val foregroundProcess = IntByReference()
            if (user32.GetWindowThreadProcessId(foreground, foregroundProcess) != 0 &&
                foregroundProcess.value == Kernel32.INSTANCE.GetCurrentProcessId()) {
                user32.SetForegroundWindow(handle)
            }
        }
    }

    companion object {
        fun captureExternal(): StageForegroundWindow? {
            if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) return null
            return runCatching {
                val user32 = User32.INSTANCE
                val handle = user32.GetForegroundWindow() ?: return@runCatching null
                val processId = IntByReference()
                val threadId = user32.GetWindowThreadProcessId(handle, processId)
                if (threadId == 0 || processId.value == Kernel32.INSTANCE.GetCurrentProcessId()) null
                else StageForegroundWindow(handle, processId.value, threadId)
            }.getOrNull()
        }
    }
}

/** Temporarily covers one monitor with the existing window, keeping its Windows restore bounds. */
internal class StageWindowFullscreen private constructor(
    private val user32: User32,
    private val handle: HWND,
    private val decorations: Int,
    private val placement: WINDOWPLACEMENT,
) {
    fun restore(): Boolean {
        val currentStyle = user32.GetWindowLong(handle, GWL_STYLE)
        val restoredStyle = user32.SetWindowLong(handle, GWL_STYLE, currentStyle or decorations) != 0
        val restoredPlacement = user32.SetWindowPlacement(handle, placement).booleanValue()
        val frameUpdated = user32.SetWindowPos(handle, null, 0, 0, 0, 0,
            SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE)
        return restoredStyle && restoredPlacement && frameUpdated
    }

    companion object {
        fun enter(window: Frame): StageWindowFullscreen? {
            if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) return null
            val user32 = User32.INSTANCE
            val handle = HWND(Native.getWindowPointer(window))
            val placement = WINDOWPLACEMENT().apply { length = size() }
            val monitor = user32.MonitorFromWindow(handle, MONITOR_DEFAULTTONEAREST)
            val monitorInfo = MONITORINFO().apply { cbSize = size() }
            if (!user32.GetWindowPlacement(handle, placement).booleanValue() ||
                !user32.GetMonitorInfo(monitor, monitorInfo).booleanValue()) return null

            // GetWindowPlacement clears flags. A minimized maximized frame still needs its Restore behavior.
            if (placement.showCmd == SW_SHOWMINIMIZED &&
                window.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH) {
                placement.flags = WINDOWPLACEMENT.WPF_RESTORETOMAXIMIZED
            }
            val originalStyle = user32.GetWindowLong(handle, GWL_STYLE)
            val snapshot = StageWindowFullscreen(user32, handle, originalStyle and WS_OVERLAPPEDWINDOW, placement)
            if (placement.showCmd != SW_SHOWNORMAL) user32.ShowWindow(handle, SW_SHOWNORMAL)
            val style = user32.GetWindowLong(handle, GWL_STYLE)
            val bounds = monitorInfo.rcMonitor
            if (style != 0 &&
                user32.SetWindowLong(handle, GWL_STYLE, style and WS_OVERLAPPEDWINDOW.inv()) != 0 &&
                user32.SetWindowPos(handle, null, bounds.left, bounds.top, bounds.right - bounds.left,
                    bounds.bottom - bounds.top, SWP_FRAMECHANGED or SWP_NOZORDER or SWP_NOACTIVATE)) {
                return snapshot
            }
            snapshot.restore()
            return null
        }
    }
}
