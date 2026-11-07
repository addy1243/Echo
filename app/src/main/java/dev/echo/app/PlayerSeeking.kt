package dev.echo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedPlaybackAction

internal sealed interface PlayerSeekTarget {
    val durationMs: Long

    data class Local(val windowUid: Any, override val durationMs: Long) : PlayerSeekTarget
    data class Remote(val device: ConnectedDevice) : PlayerSeekTarget {
        override val durationMs: Long get() = device.player.durationMs
    }
    data class Group(
        val ref: dev.echo.core.connected.GroupRef,
        val timelineSequence: Long,
        val occurrenceId: String,
        override val durationMs: Long,
    ) : PlayerSeekTarget
}

internal fun capturePlayerSeekTarget(
    presentation: PlayerPresentationState,
    player: Player?,
): PlayerSeekTarget? = when {
    presentation.selectedRemoteId != null -> presentation.remote?.let(PlayerSeekTarget::Remote)
    presentation.group != null -> presentation.group.timeline?.let { timeline ->
        timeline.queue.entries.getOrNull(timeline.cursor)?.let { occurrence ->
            PlayerSeekTarget.Group(presentation.group.ref, presentation.group.timelineSequence, occurrence.id, presentation.durationMs)
        }
    }
    else -> player?.currentWindowUid()?.let { PlayerSeekTarget.Local(it, presentation.durationMs) }
}

internal fun localSeekWindowMatches(capturedUid: Any, currentUid: Any?): Boolean = capturedUid == currentUid

private fun Player.currentWindowUid(): Any? {
    val index = currentMediaItemIndex
    if (index !in 0 until currentTimeline.windowCount) return null
    return currentTimeline.getWindow(index, Timeline.Window()).uid
}

internal fun playerSeekTargetMatches(
    target: PlayerSeekTarget,
    presentation: PlayerPresentationState,
    localWindowUid: Any?,
): Boolean = when (target) {
    is PlayerSeekTarget.Local -> presentation.selectedRemoteId == null && presentation.group == null &&
        localSeekWindowMatches(target.windowUid, localWindowUid)
    is PlayerSeekTarget.Remote -> presentation.selectedRemoteId == target.device.id && presentation.remote?.let {
        it.sessionId == target.device.sessionId && it.player.trackRevision == target.device.player.trackRevision &&
            it.player.queueRevision == target.device.player.queueRevision
    } == true
    is PlayerSeekTarget.Group -> presentation.group?.let { group ->
        val timeline = group.timeline
        group.ref == target.ref && group.timelineSequence == target.timelineSequence &&
            timeline?.queue?.entries?.getOrNull(timeline.cursor)?.id == target.occurrenceId
    } == true
}

private fun PlayerSeekTarget.isReady(vm: EchoViewModel, remotePending: Boolean, groupPending: Boolean): Boolean = when (this) {
    is PlayerSeekTarget.Local -> vm.controller != null
    is PlayerSeekTarget.Remote -> !remotePending && vm.canControlConnectedDevice(device)
    is PlayerSeekTarget.Group -> !groupPending
}

internal fun EchoViewModel.seekPlayerTo(target: PlayerSeekTarget, positionMs: Long) {
    when (target) {
        is PlayerSeekTarget.Local -> controller?.takeIf {
            localSeekWindowMatches(target.windowUid, it.currentWindowUid())
        }?.seekTo(positionMs)
        is PlayerSeekTarget.Remote -> sendConnectedPlaybackCommand(target.device, ConnectedPlaybackAction.SEEK, positionMs)
        is PlayerSeekTarget.Group -> seekTogetherAt(positionMs, target.ref, target.timelineSequence, target.occurrenceId)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PlayerSeekBar(
    vm: EchoViewModel,
    presentation: PlayerPresentationState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showTimeLabels: Boolean = false,
    onScrubbingChanged: (Boolean) -> Unit = {},
    testTag: String? = null,
    colors: SliderColors = SliderDefaults.colors(),
) {
    val connected by vm.connectedPlaybackState.collectAsStateWithLifecycle()
    val connectedV2 by vm.connectedPlaybackV2State.collectAsStateWithLifecycle()
    val localWindowUid = vm.controller?.currentWindowUid()
    val candidate = capturePlayerSeekTarget(presentation, vm.controller)
    var seeking by remember { mutableStateOf<Float?>(null) }
    var captured by remember { mutableStateOf<PlayerSeekTarget?>(null) }
    val target = captured ?: candidate
    val duration = target?.durationMs?.takeIf { it > 0 } ?: presentation.durationMs
    val value = (seeking ?: presentation.positionMs.toFloat()).coerceIn(0f, duration.toFloat().coerceAtLeast(1f))
    val ready = target != null && playerSeekTargetMatches(target, presentation, localWindowUid) &&
        target.isReady(vm, connected.pending, connectedV2.pending)
    val canSeek = enabled && ready && duration > 0
    val thumbColor = if (canSeek) colors.thumbColor else colors.disabledThumbColor
    val onScrubbingChangedState by rememberUpdatedState(onScrubbingChanged)

    LaunchedEffect(
        presentation.selectedRemoteId,
        presentation.remote?.sessionId,
        presentation.remote?.player?.trackRevision,
        presentation.remote?.player?.queueRevision,
        presentation.group?.ref,
        presentation.group?.timelineSequence,
        presentation.group?.timeline?.let { it.queue.entries.getOrNull(it.cursor)?.id },
        localWindowUid,
        canSeek,
    ) {
        seeking = null
        captured = null
        onScrubbingChangedState(false)
    }
    DisposableEffect(Unit) {
        onDispose { onScrubbingChangedState(false) }
    }

    Column(modifier) {
        Slider(
            value = value,
            onValueChange = { next ->
                if (captured == null && candidate != null && canSeek) {
                    captured = candidate
                    onScrubbingChangedState(true)
                }
                if (captured != null) seeking = next
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            enabled = canSeek,
            colors = colors,
            onValueChangeFinished = {
                val targetAtStart = captured
                val position = seeking
                if (targetAtStart != null && position != null &&
                    playerSeekTargetMatches(targetAtStart, presentation, localWindowUid) &&
                    targetAtStart.isReady(vm, connected.pending, connectedV2.pending)) {
                    vm.seekPlayerTo(targetAtStart, position.toLong())
                }
                seeking = null
                captured = null
                onScrubbingChangedState(false)
            },
            thumb = { Box(Modifier.size(12.dp).clip(CircleShape).background(thumbColor)) },
            track = { sliderState -> SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier.height(4.dp),
                colors = colors,
                enabled = canSeek,
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 2.dp,
                drawStopIndicator = {},
            ) },
            modifier = Modifier.fillMaxWidth().height(48.dp)
                .semantics {
                    contentDescription = "Song position"
                    stateDescription = "${playerTime(value.toLong())} of ${playerTime(duration)}"
                }
                .then(testTag?.let(Modifier::testTag) ?: Modifier),
        )
        if (showTimeLabels) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(playerTime(value.toLong()), textAlign = TextAlign.Start)
            Text(playerTime(duration), textAlign = TextAlign.End)
        }
    }
}

private fun playerTime(ms: Long) = "%d:%02d".format(ms.coerceAtLeast(0) / 60_000, ms.coerceAtLeast(0) / 1000 % 60)
