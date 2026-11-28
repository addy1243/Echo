package dev.echo.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val StageBufferOffsets = listOf(-2, -1, 0, 1, 2)

/** Returns the fixed tracker order while leaving unprepared occurrences absent. */
internal fun stageBufferTrackerSlots(status: StageBufferStatus): List<StageBufferPosition?> {
    val positions = status.positions.associateBy { it.offset }
    return StageBufferOffsets.map(positions::get)
}

@Composable
internal fun StageBufferTracker(status: StageBufferStatus, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f))
            .padding(14.dp)
            .semantics { contentDescription = "Stage media cache: five queue positions for audio and video" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("STAGE CACHE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.1.sp)
            Spacer(Modifier.weight(1f))
            Text("AUDIO · VIDEO CACHE", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        stageBufferTrackerSlots(status).forEachIndexed { index, position ->
            val offset = StageBufferOffsets[index]
            if (position == null) {
                StageBufferEmptySlot(offset)
            } else {
                StageBufferRow(position)
            }
        }
    }
}

@Composable
private fun StageBufferEmptySlot(offset: Int) {
    Row(
        Modifier.fillMaxWidth().height(43.dp).padding(horizontal = 8.dp)
            .semantics { contentDescription = "Queue position ${stageBufferOffsetLabel(offset)}, not in queue" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OffsetMarker(offset, current = false, empty = true)
        Text("No track", Modifier.padding(start = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f),
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StageBufferRow(position: StageBufferPosition) {
    val current = position.offset == 0
    val active = stageBufferPositionIsActive(position)
    val complete = stageBufferPositionIsComplete(position)
    val containerTarget = if (current) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .76f)
        else MaterialTheme.colorScheme.surface.copy(alpha = .44f)
    val container by animateColorAsState(containerTarget, label = "stage-buffer-current")
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().clip(shape).then(
        if (complete) Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = .72f), shape)
        else Modifier,
    )) {
        if (active) StageBufferActiveGlow(shape, if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
        Column(
        Modifier.fillMaxWidth().background(container.copy(alpha = if (active) .66f else 1f)).padding(horizontal = 9.dp, vertical = 7.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("Queue position ${stageBufferOffsetLabel(position.offset)}")
                    if (current) append(", current")
                    position.track?.let { append(", ${it.title}, ${it.artist}") }
                    append(". Audio ${stageBufferTransferDescription(position.audio)}")
                    append(". Video ${stageBufferTransferDescription(position.video)}")
                    if (complete) append(". Audio and video cached")
                }
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OffsetMarker(position.offset, current, empty = false)
            Text(position.track?.title ?: "Preparing track", Modifier.weight(1f).padding(start = 8.dp),
                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StageBufferTransferIndicator("A", position.audio, Modifier.weight(1f))
            StageBufferTransferIndicator("V", position.video, Modifier.weight(1f))
        }
    }
    }
}

@Composable
private fun StageBufferActiveGlow(shape: RoundedCornerShape, color: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "stage-buffer-active-glow")
    val alpha by transition.animateFloat(
        initialValue = .04f,
        targetValue = .16f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1500), RepeatMode.Reverse),
        label = "stage-buffer-glow-alpha",
    )
    Box(Modifier.fillMaxWidth().height(57.dp).clip(shape).background(color.copy(alpha = alpha)))
}

@Composable
private fun OffsetMarker(offset: Int, current: Boolean, empty: Boolean) {
    val target = when {
        empty -> MaterialTheme.colorScheme.outlineVariant
        current -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    val color by animateColorAsState(target, label = "stage-buffer-marker")
    Box(
        Modifier.size(if (current) 26.dp else 22.dp).clip(CircleShape).background(color.copy(alpha = if (empty) .35f else .2f))
            .semantics { contentDescription = "${stageBufferOffsetLabel(offset)}${if (current) ", current" else ""}" },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (offset > 0) "+$offset" else offset.toString(), color = if (empty) MaterialTheme.colorScheme.onSurfaceVariant else color,
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StageBufferTransferIndicator(label: String, transfer: StageBufferTransfer, modifier: Modifier = Modifier) {
    val progress = stageBufferTransferProgress(transfer)
    val animatedProgress by animateFloatAsState(progress ?: 0f, label = "stage-buffer-$label-progress")
    val stateColor = when (transfer.phase) {
        StageBufferTransferPhase.READY -> MaterialTheme.colorScheme.primary
        StageBufferTransferPhase.FAILED -> MaterialTheme.colorScheme.error
        StageBufferTransferPhase.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.secondary
    }
    Column(modifier.semantics {
        contentDescription = "$label ${stageBufferTransferDescription(transfer)}"
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold)
            Text("  ${stageBufferTransferShortLabel(transfer)}", color = stateColor,
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Clip)
        }
        Spacer(Modifier.height(2.dp))
        when {
            progress != null -> LinearProgressIndicator(
                progress = { animatedProgress }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = stateColor,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f),
            )
            transfer.phase == StageBufferTransferPhase.DOWNLOADING || transfer.phase == StageBufferTransferPhase.PREPARING || transfer.phase == StageBufferTransferPhase.CAPTURING ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = stateColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            else -> Spacer(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(
                if (transfer.phase == StageBufferTransferPhase.READY) stateColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .42f)))
        }
    }
}

internal fun stageBufferOffsetLabel(offset: Int) = when {
    offset < 0 -> "${-offset} back"
    offset == 0 -> "Now"
    else -> "$offset ahead"
}

internal fun stageBufferPositionIsActive(position: StageBufferPosition): Boolean =
    listOf(position.audio, position.video).any {
        it.phase == StageBufferTransferPhase.PREPARING || it.phase == StageBufferTransferPhase.DOWNLOADING || it.phase == StageBufferTransferPhase.CAPTURING
    }

internal fun stageBufferPositionIsComplete(position: StageBufferPosition): Boolean =
    position.audio.phase == StageBufferTransferPhase.READY && position.video.phase == StageBufferTransferPhase.READY

internal fun stageBufferTransferProgress(transfer: StageBufferTransfer): Float? =
    transfer.totalBytes?.takeIf { it > 0 && transfer.phase == StageBufferTransferPhase.DOWNLOADING }
        ?.let { (transfer.receivedBytes.toFloat() / it).coerceIn(0f, 1f) }

internal fun stageBufferTransferShortLabel(transfer: StageBufferTransfer): String = when (transfer.phase) {
    StageBufferTransferPhase.IDLE -> "Idle"
    StageBufferTransferPhase.WAITING -> "Waiting"
    StageBufferTransferPhase.PREPARING -> "Preparing"
    StageBufferTransferPhase.DOWNLOADING -> stageBufferTransferProgress(transfer)?.let { "${(it * 100).toInt()}%" } ?: "Loading"
    StageBufferTransferPhase.CAPTURING -> "Saving stream"
    StageBufferTransferPhase.READY -> "Cached"
    StageBufferTransferPhase.UNAVAILABLE -> "Unavailable"
    StageBufferTransferPhase.FAILED -> "Failed"
}

internal fun stageBufferTransferDescription(transfer: StageBufferTransfer): String = when (transfer.phase) {
    StageBufferTransferPhase.IDLE -> "idle"
    StageBufferTransferPhase.WAITING -> "waiting"
    StageBufferTransferPhase.PREPARING -> "preparing"
    StageBufferTransferPhase.DOWNLOADING -> stageBufferTransferProgress(transfer)?.let { "loading ${(it * 100).toInt()} percent" } ?: "loading"
    StageBufferTransferPhase.CAPTURING -> "saving the playing stream"
    StageBufferTransferPhase.READY -> "cached"
    StageBufferTransferPhase.UNAVAILABLE -> "unavailable"
    StageBufferTransferPhase.FAILED -> "failed"
}
