package dev.echo.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.echo.core.Track
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

internal enum class StageScreensaverStyle { CINEMA, AMBIENT }

internal data class StageScreensaverConfig(
    val idleMinutes: Int = 5,
    val automatic: Boolean = false,
    val style: StageScreensaverStyle = StageScreensaverStyle.CINEMA,
)

/** Stage-only settings live in their own file; normal Echo preferences are never opened here. */
internal class StageScreensaverPreferences(private val file: Path) {
    private val loaded = read()
    private val initial = loaded.first
    private val loadFailure = loaded.second
    var idleMinutes by mutableIntStateOf(initial.idleMinutes); private set
    var automatic by mutableStateOf(initial.automatic); private set
    var style by mutableStateOf(initial.style); private set
    var error by mutableStateOf(loadFailure); private set

    fun configureIdleMinutes(value: Int) = update(idleMinutes = value.coerceIn(1, 60))
    fun configureAutomatic(value: Boolean) = update(automatic = value)
    fun configureStyle(value: StageScreensaverStyle) = update(style = value)

    private fun read(): Pair<StageScreensaverConfig, String?> {
        if (!runCatching { Files.exists(file) }.getOrDefault(false)) return StageScreensaverConfig() to null
        return runCatching {
            require(Files.isRegularFile(file) && Files.size(file) <= 16_384) { "invalid size" }
            val json = JSONObject(Files.readString(file))
            StageScreensaverConfig(
                idleMinutes = json.optInt("idleMinutes", 5).coerceIn(1, 60),
                automatic = json.optBoolean("automatic", false),
                style = runCatching { StageScreensaverStyle.valueOf(json.optString("style", "CINEMA")) }
                    .getOrDefault(StageScreensaverStyle.CINEMA),
            ) to null
        }.getOrElse { StageScreensaverConfig() to "Stage screensaver settings are damaged; the file was preserved." }
    }

    private fun update(idleMinutes: Int = this.idleMinutes, automatic: Boolean = this.automatic,
        style: StageScreensaverStyle = this.style) {
        if (loadFailure != null) return
        try {
            val parent = file.toAbsolutePath().parent
            Files.createDirectories(parent)
            val json = if (Files.exists(file)) JSONObject(Files.readString(file)) else JSONObject()
            json.put("idleMinutes", idleMinutes).put("automatic", automatic).put("style", style.name)
            val temporary = Files.createTempFile(parent, "stage-screensaver-", ".tmp")
            try {
                FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                    val buffer = java.nio.ByteBuffer.wrap(json.toString().toByteArray(Charsets.UTF_8))
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally { Files.deleteIfExists(temporary) }
            this.idleMinutes = idleMinutes; this.automatic = automatic; this.style = style; error = null
        } catch (_: Exception) {
            error = "Stage screensaver settings could not be saved. The previous file was preserved."
        }
    }
}

@Composable
internal fun StageScreensaverSettings(preferences: StageScreensaverPreferences, canStart: Boolean, onStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Stage screensaver", style = MaterialTheme.typography.titleMedium)
        Text("Show the current song full screen, or open Echo full screen when nothing is playing.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StageScreensaverStyle.entries.forEach { style ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (style == StageScreensaverStyle.CINEMA)
                            Brush.linearGradient(listOf(Color(0xFF111B32), Color(0xFF6D3C72)))
                        else Brush.linearGradient(listOf(Color(0xFF251344), Color(0xFF126078)))),
                        contentAlignment = Alignment.Center) {
                        if (style == StageScreensaverStyle.CINEMA) {
                            Text("▶", color = Color.White.copy(alpha = .8f), fontSize = 26.sp)
                            Text("Song · Artist", Modifier.align(Alignment.BottomStart).padding(8.dp),
                                color = Color.White, fontSize = 10.sp)
                        } else {
                            Box(Modifier.size(42.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF161D38)))
                        }
                    }
                    FilterChip(preferences.style == style, { preferences.configureStyle(style) },
                        label = { Text(if (style == StageScreensaverStyle.CINEMA) "Cinema" else "Ambient") })
                    Text(if (style == StageScreensaverStyle.CINEMA) "Video when available" else "Animated artwork",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Checkbox(preferences.automatic, preferences::configureAutomatic)
            Column {
                Text("Start after the laptop is idle")
                Text("Also works in the background. Waits while apps ask Windows to keep the display awake.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Idle time")
            listOf(1, 5, 10, 15, 30, 60).forEach { minutes ->
                FilterChip(preferences.idleMinutes == minutes, { preferences.configureIdleMinutes(minutes) },
                    label = { Text(if (minutes == 60) "1 hour" else "$minutes min") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = canStart, onClick = onStart) { Text("Start now") }
        }
        if (!canStart) Text("The screensaver is already active.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        preferences.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Use the OS tick counter and last-input counter, which share a wrapping 32-bit clock. */
internal fun windowsIdleMillis(lastInputTick: Int, currentTick: Int): Long =
    (currentTick - lastInputTick).toUInt().toLong()

internal fun windowsIdleMillis(): Long? {
    if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) return null
    return runCatching {
        val lastInput = windowsLastInputTick() ?: return@runCatching null
        windowsIdleMillis(lastInput, com.sun.jna.platform.win32.Kernel32.INSTANCE.GetTickCount())
    }.getOrNull()
}

internal fun windowsLastInputTick(): Int? {
    if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) return null
    return runCatching {
        if (!isDefaultWindowsInputDesktop()) return@runCatching null
        val input = com.sun.jna.platform.win32.WinUser.LASTINPUTINFO().apply { cbSize = size() }
        if (com.sun.jna.platform.win32.User32.INSTANCE.GetLastInputInfo(input)) input.dwTime else null
    }.getOrNull()
}

internal fun windowsInputChangedSince(entryTick: Int, currentTick: Int): Boolean =
    (currentTick - entryTick).toUInt() != 0u

internal fun stageScreensaverAutoStartAllowed(
    idleMillis: Long?,
    idleLimitMillis: Long,
    displayRequired: () -> Boolean? = ::windowsDisplayRequired,
): Boolean = idleMillis != null && idleMillis >= idleLimitMillis && displayRequired() == false

private interface StagePowerProfile : StdCallLibrary {
    fun CallNtPowerInformation(level: Int, input: Pointer?, inputSize: Int, output: IntByReference, outputSize: Int): Int
}

private val stagePowerProfile by lazy { Native.load("powrprof", StagePowerProfile::class.java) }
private const val SYSTEM_EXECUTION_STATE = 16
private const val ES_DISPLAY_REQUIRED = 0x00000002

private fun windowsDisplayRequired(): Boolean? = runCatching {
    val flags = IntByReference()
    val status = stagePowerProfile.CallNtPowerInformation(SYSTEM_EXECUTION_STATE, null, 0, flags, Int.SIZE_BYTES)
    if (status == 0) flags.value and ES_DISPLAY_REQUIRED != 0 else null
}.getOrNull()

internal fun stageSaverHasNewWindowsInput(entryTick: Int?, currentTick: Int?): Boolean =
    entryTick != null && currentTick != null && windowsInputChangedSince(entryTick, currentTick)

internal fun windowsInputDesktopInteractive(): Boolean {
    if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) return true
    return isDefaultWindowsInputDesktop()
}

private interface StageUser32 : StdCallLibrary {
    fun OpenInputDesktop(flags: Int, inherit: Boolean, desiredAccess: Int): WinNT.HANDLE?
    fun CloseDesktop(desktop: WinNT.HANDLE): Boolean
    fun GetUserObjectInformation(handle: WinNT.HANDLE, index: Int, info: Pointer, length: Int,
        needed: IntByReference): Boolean
}

private fun isDefaultWindowsInputDesktop(): Boolean = runCatching {
    val user32 = Native.load("user32", StageUser32::class.java, W32APIOptions.DEFAULT_OPTIONS)
    val desktop = user32.OpenInputDesktop(0, false, 0x0001) ?: return@runCatching false
    try {
        val name = com.sun.jna.Memory(512)
        val needed = IntByReference()
        user32.GetUserObjectInformation(desktop, 2, name, 512, needed) && name.getWideString(0) == "Default"
    } finally { user32.CloseDesktop(desktop) }
}.getOrDefault(false)

/** Stable-video heuristic: require two consecutive moving samples; a held still settles after 3. */
internal class StageVideoMotionDetector {
    private var occurrence: String? = null
    private var previous: IntArray? = null
    private var movingSamples = 0
    private var stillSamples = 0
    var moving by mutableStateOf(false)
        private set
    var classified by mutableStateOf(false)
        private set

    fun reset(occurrenceId: String?) {
        occurrence = occurrenceId
        previous = null
        movingSamples = 0
        stillSamples = 0
        moving = false
        classified = false
    }

    fun sample(occurrenceId: String?, pixels: IntArray?) {
        if (occurrenceId != occurrence) {
            occurrence = occurrenceId
            previous = null
            movingSamples = 0
            stillSamples = 0
            moving = false
            classified = false
        }
        if (pixels == null || pixels.isEmpty()) {
            // A missing snapshot says nothing about whether the video is moving.
            return
        }
        val last = previous
        previous = pixels.copyOf()
        if (last == null || last.size != pixels.size) return
        var changed = 0
        pixels.indices.forEach { i ->
            val a = last[i]; val b = pixels[i]
            val distance = kotlin.math.abs(((a shr 16) and 255) - ((b shr 16) and 255)) +
                kotlin.math.abs(((a shr 8) and 255) - ((b shr 8) and 255)) + kotlin.math.abs((a and 255) - (b and 255))
            if (distance >= 36) changed++
        }
        val threshold = (pixels.size * .015).toInt().coerceAtLeast(1)
        if (changed >= threshold) {
            movingSamples++
            stillSamples = 0
            if (movingSamples >= 2) { moving = true; classified = true }
        } else {
            movingSamples = 0
            stillSamples++
            if (stillSamples >= 3) { moving = false; classified = true }
        }
    }
}

@Composable
internal fun StageAmbientArtwork(track: Track?, bitmap: ImageBitmap?, positionMs: Long, durationMs: Long) {
    val transition = rememberInfiniteTransition(label = "stage-saver-artwork")
    val scale by transition.animateFloat(1.02f, 1.12f, infiniteRepeatable(tween(18_000), RepeatMode.Reverse), label = "scale")
    val drift by transition.animateFloat(0.78f, 1f, infiniteRepeatable(tween(8_000), RepeatMode.Reverse), label = "glow")
    val tileScale by transition.animateFloat(.96f, 1.04f, infiniteRepeatable(tween(9_000), RepeatMode.Reverse), label = "tile-scale")
    val hueShift by transition.animateFloat(0f, 34f, infiniteRepeatable(tween(20_000), RepeatMode.Reverse), label = "tile-hue")
    val hue = Math.floorMod((track?.id ?: track?.title ?: "Echo").hashCode(), 360).toFloat()
    val tileStart = Color.hsv((hue + hueShift) % 360f, .78f, .82f)
    val tileEnd = Color.hsv((hue + hueShift + 58f) % 360f, .72f, .62f)
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(
        if (bitmap == null) tileStart.copy(alpha = .52f) else Color(0xFF10151E),
        if (bitmap == null) tileEnd.copy(alpha = .64f) else Color(0xFF26303B))))) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize().scale(scale).alpha(.74f), contentScale = ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .14f),
            Color.Black.copy(alpha = .58f * drift), Color.Black.copy(alpha = .9f)))))
        if (bitmap == null) {
            Box(Modifier.align(Alignment.Center).size(280.dp).scale(tileScale).rotate(-2f)
                .clip(RoundedCornerShape(40.dp)).background(Brush.linearGradient(listOf(tileStart, tileEnd))),
                contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.White.copy(alpha = .26f), Color.Transparent))))
                Text(track?.title?.firstOrNull(Char::isLetterOrDigit)?.uppercase() ?: "♪", color = Color.White,
                    fontSize = 112.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(56.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(track?.title ?: "Echo", fontSize = 36.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2)
            Text(track?.artist.orEmpty(), fontSize = 20.sp, color = Color.White.copy(alpha = .78f), maxLines = 1)
            if (durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.width(240.dp).padding(top = 4.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = .28f),
                )
            }
        }
    }
}
