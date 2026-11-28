package dev.echo.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.awt.SwingPanel
import dev.echo.core.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Canvas
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Rectangle

@Composable
internal fun StageCinemaWindow(
    active: Boolean,
    track: Track?,
    title: String?,
    artist: String?,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    videoMode: Boolean,
    videoLoading: Boolean,
    videoOccurrence: Long?,
    videoError: String?,
    saverCanvas: Canvas,
    bindCanvas: Boolean,
    rebindToSaver: suspend (Long) -> Boolean,
    rebindToMain: suspend () -> Boolean,
    hasSaverFrame: suspend () -> Boolean,
    onSaverFrameTimeout: suspend (Long) -> Unit,
    onSaverCanvasReturned: () -> Unit,
    onInteraction: () -> Unit,
    ownerScreenBounds: () -> Rectangle = {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
    },
) {
    var peerVisible by remember { mutableStateOf(false) }
    var attemptedOccurrence by remember { mutableStateOf<Long?>(null) }
    var readyOccurrence by remember { mutableStateOf<Long?>(null) }
    var surfaceError by remember { mutableStateOf<String?>(null) }
    var cinemaHud by remember { mutableStateOf<StageCinemaHud?>(null) }
    val currentActive by rememberUpdatedState(active)
    val currentBindCanvas by rememberUpdatedState(bindCanvas)
    val currentOccurrence by rememberUpdatedState(videoOccurrence)
    val currentPlaying by rememberUpdatedState(playing)
    val currentVideoError by rememberUpdatedState(videoError)
    val currentRebindToSaver by rememberUpdatedState(rebindToSaver)
    val currentRebindToMain by rememberUpdatedState(rebindToMain)
    val currentHasSaverFrame by rememberUpdatedState(hasSaverFrame)
    val currentOnSaverFrameTimeout by rememberUpdatedState(onSaverFrameTimeout)
    val currentOnSaverCanvasReturned by rememberUpdatedState(onSaverCanvasReturned)
    val artwork by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, track?.artworkUri) {
        value = loadDesktopArtwork(track?.artworkUri)
    }
    val presentationError = videoError ?: surfaceError.takeIf { attemptedOccurrence == videoOccurrence }
    val showVideo = active && videoMode && playing && !videoLoading && presentationError == null &&
        readyOccurrence != null && readyOccurrence == videoOccurrence

    LaunchedEffect(Unit) {
        var bindingWork: Job? = null
        var bindingOccurrence: Long? = null
        snapshotFlow { Triple(currentActive, currentBindCanvas, currentOccurrence) }
            .collect { (isActive, shouldBindCanvas, occurrence) ->
                if (isActive) {
                    peerVisible = true
                    if (bindingOccurrence != occurrence) bindingWork?.cancelAndJoin()
                    if (!shouldBindCanvas || occurrence == null || attemptedOccurrence == occurrence ||
                        bindingWork?.isActive == true) return@collect
                    bindingOccurrence = occurrence
                    bindingWork = launch {
                        val mounted = withTimeoutOrNull(5_000) {
                            while (!saverCanvas.isDisplayable) delay(20)
                            true
                        } == true
                        if (!currentActive || !currentBindCanvas || currentOccurrence != occurrence) return@launch
                        attemptedOccurrence = occurrence
                        readyOccurrence = null
                        surfaceError = null
                        val bound = mounted && try {
                            withTimeoutOrNull(30_000) { currentRebindToSaver(occurrence) } == true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        if (!currentActive || currentOccurrence != occurrence) return@launch
                        if (!bound) {
                            surfaceError = "Video surface could not start. Showing artwork."
                            currentOnSaverFrameTimeout(occurrence)
                            return@launch
                        }
                        while (currentActive && currentOccurrence == occurrence) {
                            snapshotFlow { currentPlaying || currentVideoError != null }.first { it }
                            if (currentVideoError != null) return@launch
                            val ready = withTimeoutOrNull(12_000) {
                                while (currentPlaying && currentVideoError == null) {
                                    val frame = try {
                                        currentHasSaverFrame()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        false
                                    }
                                    if (frame) return@withTimeoutOrNull true
                                    delay(100)
                                }
                                false
                            }
                            if (!currentActive || currentOccurrence != occurrence || currentVideoError != null) return@launch
                            if (ready == true) {
                                readyOccurrence = occurrence
                                return@launch
                            }
                            if (ready == null && currentPlaying) {
                                surfaceError = "Video could not display a frame. Showing artwork."
                                currentOnSaverFrameTimeout(occurrence)
                                return@launch
                            }
                        }
                    }
                } else {
                    bindingWork?.cancelAndJoin()
                    // Keep the native peer alive until VLC no longer owns it.
                    while (attemptedOccurrence != null) {
                        val returned = try {
                            currentRebindToMain()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        if (returned) break
                        surfaceError = "Video is still returning to Echo; the Cinema window remains open."
                        delay(200)
                    }
                    attemptedOccurrence = null
                    readyOccurrence = null
                    surfaceError = null
                    peerVisible = false
                    currentOnSaverCanvasReturned()
                }
            }
    }

    if (peerVisible) {
        // Give the native VLC canvas a borderless screen-sized peer without entering exclusive display mode.
        val bounds = remember { ownerScreenBounds() }
        val state = rememberWindowState(
            size = DpSize(bounds.width.dp, bounds.height.dp),
            position = WindowPosition(bounds.x.dp, bounds.y.dp),
        )
        Window(
            onCloseRequest = onInteraction,
            title = "Echo Stage Cinema",
            state = state,
            undecorated = true,
            resizable = false,
            alwaysOnTop = true,
        ) {
            val owner = window
            DisposableEffect(owner, showVideo) {
                val hud = if (showVideo &&
                    owner.graphicsConfiguration.device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
                    runCatching { StageCinemaHud(owner, onInteraction) }.getOrNull()
                } else null
                val shownHud = hud?.takeIf { overlay ->
                    runCatching { overlay.show(title, artist, positionMs, durationMs, playing) }.isSuccess
                }
                if (shownHud == null) hud?.close()
                cinemaHud = shownHud
                onDispose {
                    shownHud?.close()
                    if (cinemaHud === shownHud) cinemaHud = null
                }
            }
            SideEffect {
                cinemaHud?.update(title, artist, positionMs, durationMs, playing)
            }
            Surface(Modifier.fillMaxSize(), color = Color.Black) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (!showVideo) StageAmbientArtwork(track, artwork, positionMs, durationMs)
                        SwingPanel(
                            factory = { saverCanvas.apply { background = java.awt.Color.BLACK } },
                            modifier = if (showVideo) Modifier.fillMaxSize() else Modifier.size(1.dp),
                            background = Color.Black,
                        )
                        if (presentationError != null) {
                            Surface(Modifier.align(Alignment.BottomCenter).padding(24.dp), color = Color(0xDD291D1D)) {
                                Text(presentationError, color = Color.White,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                            }
                        }
                    }
                    if (showVideo && cinemaHud == null) Column(
                        Modifier.fillMaxWidth().padding(horizontal = 44.dp, vertical = 20.dp),
                    ) {
                        Text(title ?: "Echo", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                        artist?.takeIf(String::isNotBlank)?.let { Text(it, color = Color.White.copy(alpha = .72f)) }
                    }
                }
            }
        }
    }
}
