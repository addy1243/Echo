package dev.echo.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal enum class DesktopThemeMode { DARK, LIGHT }

internal fun String.toDesktopColor(): Color? = runCatching {
    val value = removePrefix("#")
    require(value.matches(Regex("[0-9a-fA-F]{6}")))
    Color((0xFF000000L or value.toLong(16)))
}.getOrNull()

internal class DesktopPreferences(
    private val file: Path,
    private val legacyFile: Path? = null,
) {
    private data class Appearance(val theme: DesktopThemeMode, val accent: String, val closeToTray: Boolean)
    private data class Loaded(val appearance: Appearance, val error: String?)

    private val tutorialMarker = file.resolveSibling(file.fileName.toString().removeSuffix(".json") + ".tutorial-complete")
    private val loaded = load()
    var tutorialCompleted by mutableStateOf(Files.exists(tutorialMarker)); private set
    var themeMode by mutableStateOf(loaded.appearance.theme); private set
    var customAccent by mutableStateOf(loaded.appearance.accent); private set
    var closeToTray by mutableStateOf(loaded.appearance.closeToTray); private set
    var error by mutableStateOf(loaded.error); private set

    fun setTheme(value: DesktopThemeMode) = update(theme = value)
    fun setAccent(value: String) {
        require(value.isBlank() || value.matches(Regex("#[0-9a-fA-F]{6}")))
        update(accent = value)
    }
    fun updateCloseToTray(value: Boolean) = update(closeToTray = value)

    fun completeTutorial() {
        Files.createDirectories(tutorialMarker.parent)
        try {
            Files.createFile(tutorialMarker)
        } catch (_: FileAlreadyExistsException) {
            check(Files.isRegularFile(tutorialMarker)) { "Tutorial completion marker is not a regular file" }
        }
        tutorialCompleted = true
    }

    private fun load(): Loaded {
        val primaryExists = runCatching { Files.exists(file) }.getOrDefault(false)
        val primary = readAppearanceFile(file)
        val legacyExists = legacyFile?.let { runCatching { Files.exists(it) }.getOrDefault(false) } == true
        val legacy = if (primary == null) legacyFile?.let(::readAppearanceFile) else null
        val source = primary ?: legacy
        val loadError = when {
            primaryExists && primary == null -> "Appearance settings are damaged; the file was preserved."
            !primaryExists && legacyExists && legacy == null -> "Older preferences could not be read; the file was preserved."
            else -> null
        }
        if (source == null) return Loaded(Appearance(DesktopThemeMode.DARK, "", true), loadError)
        val mode = runCatching { DesktopThemeMode.valueOf(source.optString("theme", "DARK")) }.getOrDefault(DesktopThemeMode.DARK)
        val accent = source.optString("accent", "").takeIf { it.isBlank() || it.matches(Regex("#[0-9a-fA-F]{6}")) }.orEmpty()
        return Loaded(Appearance(mode, accent, source.optBoolean("closeToTray", true)), loadError)
    }

    private fun update(theme: DesktopThemeMode = themeMode, accent: String = customAccent, closeToTray: Boolean = this.closeToTray) {
        try {
            val values = if (Files.exists(file)) {
                require(Files.size(file) <= 65_536) { "Appearance settings are too large; the file was preserved." }
                JSONObject(Files.readString(file))
            } else JSONObject()
            values.put("theme", theme.name).put("accent", accent).put("closeToTray", closeToTray)
            writeAtomically(file, values.toString())
            themeMode = theme
            customAccent = accent
            this.closeToTray = closeToTray
            error = null
        } catch (_: Exception) {
            error = "Appearance settings could not be saved. The existing file was preserved."
        }
    }

    private fun readAppearanceFile(path: Path): JSONObject? {
        return runCatching {
            if (!Files.isRegularFile(path) || Files.size(path) > 65_536) null
            else JSONObject(Files.readString(path))
        }.getOrNull()
    }

    private fun writeAtomically(path: Path, contents: String) {
        val parent = path.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "appearance-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(contents.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }
}
