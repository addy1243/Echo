package dev.echo.app

import androidx.compose.animation.core.animateFloat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@Composable
internal fun StageStandbyScreen(vm: EchoViewModel, exit: () -> Unit) {
    val cinema = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val presentation = rememberPlayerPresentation(vm, tracks)
    val offlineSettings by vm.offlineSettings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = remember(context) {
        var current: android.content.Context? = context
        while (current !is android.app.Activity && current is android.content.ContextWrapper) current = current.baseContext
        current as? android.app.Activity
    }
    val localVideo = cinema && !offlineSettings.offlineOnly && presentation.playing && presentation.selectedRemoteId == null && presentation.group == null
    val occurrenceKey = if (localVideo) runCatching {
        val controller = vm.controller ?: return@runCatching null
        val index = controller.currentMediaItemIndex
        if (index < 0 || index >= controller.currentTimeline.windowCount) null
        else controller.currentTimeline.getWindow(index, androidx.media3.common.Timeline.Window()).uid.toString()
    }.getOrNull() else null
    DisposableEffect(lifecycle, context) {
        val window = activity?.window
        val wasKeepingScreenOn = window?.attributes?.flags?.and(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        val decor = window?.decorView
        val insets = decor?.let { androidx.core.view.ViewCompat.getRootWindowInsets(it) }
        val barsWereVisible = insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars()) ?: true
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (event == Lifecycle.Event.ON_STOP && !wasKeepingScreenOn) window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!wasKeepingScreenOn) window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (barsWereVisible) bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler(onBack = exit)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val rotation = androidx.compose.animation.core.rememberInfiniteTransition(label = "standby-artwork")
        .animateFloat(
            initialValue = -2f,
            targetValue = 2f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(9000, easing = androidx.compose.animation.core.LinearEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "standby-rotation",
        )
    var position by remember { mutableLongStateOf(presentation.positionMs) }
    LaunchedEffect(presentation.positionMs, presentation.playing) {
        position = presentation.positionMs
        while (presentation.playing) {
            delay(500)
            position = (position + 500).coerceAtMost(presentation.durationMs)
        }
    }
    val inputModifier = Modifier
        .pointerInput(exit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).consume()
                exit()
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) break
                }
            }
        }
        .onPreviewKeyEvent { exit(); true }
    val screenModifier = inputModifier
        .fillMaxSize()
        .testTag("stage-standby-screen")
        .semantics { customActions = listOf(CustomAccessibilityAction("Exit Standby") { exit(); true }) }
        .focusRequester(focusRequester)
        .focusable()
    val progress = {
        if (presentation.durationMs > 0) (position.toFloat() / presentation.durationMs).coerceIn(0f, 1f) else 0f
    }
    val exitHint = if (presentation.playing) "Tap or press any key to exit" else "Playback is paused · tap or press any key to exit"
    Box(Modifier.fillMaxSize().testTag(if (cinema) "standby-landscape" else "standby-portrait")) {
        if (cinema) {
            Row(screenModifier.background(Color(0xFF08090D)), verticalAlignment = Alignment.CenterVertically) {
                BoxWithConstraints(
                    Modifier.weight(.2f).fillMaxHeight()
                        .background(Color(0xFF171522))
                        .testTag("stage-cinema-track")
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val artworkSize = minOf(maxWidth, maxHeight * .48f, 168.dp)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Artwork(presentation.artworkUri, artworkSize.value.toInt())
                        Text(presentation.title, color = Color.White, style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(presentation.artist, color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(progress = progress, modifier = Modifier.width(artworkSize).height(3.dp))
                    }
                }
                Box(Modifier.weight(.8f).fillMaxHeight().testTag("stage-cinema-picture")) {
                    StageCinema(
                        track = presentation.current, occurrenceKey = occurrenceKey,
                        audioPositionMs = presentation.positionMs, audioPlaying = presentation.playing,
                        resolveVideo = vm::resolveStageCinemaVideo, showAmbientDetails = false,
                    )
                    Text(exitHint, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                        .background(Color(0xB308090D), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .78f))
                }
            }
        } else {
            Column(
                screenModifier.background(Brush.verticalGradient(listOf(Color(0xFF171522), Color(0xFF08090D))))
                    .padding(horizontal = 32.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(320.dp).graphicsLayer { rotationZ = rotation.value; scaleX = 1.025f; scaleY = 1.025f }
                        .clip(RoundedCornerShape(30.dp)).background(Brush.radialGradient(listOf(Color(0xFF594779), Color(0xFF202D3B), Color.Transparent))))
                    Artwork(presentation.artworkUri, 260)
                }
                Text("ECHO", color = Color.White, style = MaterialTheme.typography.labelLarge, letterSpacing = 5.sp)
                Spacer(Modifier.height(18.dp))
                Text(presentation.title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(presentation.artist, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = .78f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(22.dp))
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth(.72f).height(3.dp))
                Spacer(Modifier.height(16.dp))
                Text(exitHint, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .78f))
            }
        }
    }
}
