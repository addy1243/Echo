package dev.echo.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.GroupActionV2
import dev.echo.core.Track
import dev.echo.core.connected.MAX_CONNECTED_GROUP_MEMBERS
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ConnectedPlayerControls(vm: EchoViewModel, compact: Boolean = false) {
    val state by vm.connectedPlaybackState.collectAsStateWithLifecycle()
    val v2State by vm.connectedPlaybackV2State.collectAsStateWithLifecycle()
    val displacedMoveOperationId by vm.displacedMoveOperationId.collectAsStateWithLifecycle()
    val selectedId by vm.selectedConnectedPlaybackTargetId.collectAsStateWithLifecycle()
    val rememberedRemote by vm.selectedConnectedPlaybackDevice.collectAsStateWithLifecycle()
    var devicesExpanded by remember(vm) { mutableStateOf(false) }
    var actionExpanded by remember(vm) { mutableStateOf<String?>(null) }
    var dockExpanded by remember(vm) { mutableStateOf(false) }
    var deviceSectionPlaced by remember(vm) { mutableStateOf(false) }
    var actionSectionPlaced by remember(vm) { mutableStateOf(false) }
    var confirmRestorePrevious by remember(vm) { mutableStateOf(false) }
    var togetherDeviceSessions by remember(vm) { mutableStateOf(emptyMap<String, String>()) }
    var confirmTogether by remember(vm) { mutableStateOf(false) }
    val devicesBringIntoView = remember { BringIntoViewRequester() }
    val actionBringIntoView = remember { BringIntoViewRequester() }
    val compactExpandedHeight = (LocalConfiguration.current.screenHeightDp * 0.4f).dp
    LaunchedEffect(devicesExpanded, deviceSectionPlaced, actionExpanded, actionSectionPlaced, compact) {
        if (!compact && actionExpanded != null && actionSectionPlaced) actionBringIntoView.bringIntoView()
        else if (!compact && devicesExpanded && deviceSectionPlaced) devicesBringIntoView.bringIntoView()
    }
    val freshTarget = if (selectedId == null) null else state.devices.firstOrNull { it.id == selectedId }
    val togetherCandidates = v2State.devices.filter { it.id != vm.connectedPlaybackDeviceIdForUi && "group" in it.capabilities }
    val togetherDeviceIds = togetherDeviceSessions.keys
    val selectedTogetherDevices = togetherDeviceSessions.mapNotNull { (id, sessionId) ->
        togetherCandidates.firstOrNull { it.id == id && it.sessionId == sessionId }
    }
    val invalidTogetherSelection = togetherDeviceSessions.any { (id, sessionId) ->
        togetherCandidates.none { it.id == id && it.sessionId == sessionId && it.enabled }
    }
    val readyToStartTogether = togetherDeviceIds.size in 1 until MAX_CONNECTED_GROUP_MEMBERS &&
        !invalidTogetherSelection && selectedTogetherDevices.size == togetherDeviceIds.size
    LaunchedEffect(v2State.group?.ref, v2State.group?.status) {
        if (v2State.group?.status == dev.echo.data.firebase.GroupV2Status.ACTIVE) {
            togetherDeviceSessions = emptyMap()
            confirmTogether = false
        }
    }
    val target = freshTarget ?: rememberedRemote
    val targetLabel = target?.label ?: if (selectedId == null) "This phone" else "selected player"
    val status = when {
        selectedId != null && target == null -> "Reconnecting to $targetLabel"
        selectedId != null && freshTarget?.controlEnabled != true -> "Reconnecting to $targetLabel"
        selectedId != null && freshTarget?.player?.title == null -> "Controlling $targetLabel · Ready"
        selectedId != null -> "${if (target?.player?.isPlaying == true) "Playing on" else "Paused on"} $targetLabel"
        vm.current == null -> "Controlling This phone"
        vm.playing -> "Playing on This phone"
        else -> "Paused on This phone"
    }
    val actions: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            ConnectedAction("Devices", Icons.Default.Devices, devicesExpanded, "connected-devices-action") {
                deviceSectionPlaced = false
                devicesExpanded = !devicesExpanded
            }
            ConnectedAction("Move", Icons.Default.SwapHoriz, actionExpanded == "move", "connected-move-action") {
                actionSectionPlaced = false
                actionExpanded = if (actionExpanded == "move") null else "move"
            }
            ConnectedAction("Together", Icons.Default.Groups, actionExpanded == "together", "connected-together-action") {
                actionSectionPlaced = false
                actionExpanded = if (actionExpanded == "together") null else "together"
            }
        }
        if (devicesExpanded) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp).bringIntoViewRequester(devicesBringIntoView)
                .onGloballyPositioned { if (!deviceSectionPlaced) deviceSectionPlaced = true }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ConnectedDeviceRow("This phone", if (vm.playing) "Playing · local player" else "Paused · local player",
                    selectedId == null, true, "connected-device-local") { vm.selectConnectedPlaybackTarget(null); deviceSectionPlaced = false; devicesExpanded = false }
                val online = state.devices.filter { it.id != vm.connectedPlaybackDeviceIdForUi && it.controlEnabled }
                online.sortedByDescending { it.id == selectedId }.forEach { device ->
                    ConnectedRemoteRow(device, selectedId == device.id, true, "connected-device-${device.id}") {
                        vm.selectConnectedPlaybackTarget(device)
                        deviceSectionPlaced = false
                        devicesExpanded = false
                    }
                }
                val offline = state.devices.filter { it.id != vm.connectedPlaybackDeviceIdForUi && !it.controlEnabled }
                if (offline.isNotEmpty()) {
                    var showOffline by remember(vm) { mutableStateOf(false) }
                    Text("Other devices · ${offline.size}${if (showOffline) "  ▴" else "  ▾"}",
                        Modifier.fillMaxWidth().clickable { showOffline = !showOffline }.padding(horizontal = 12.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    if (showOffline) offline.sortedByDescending { it.id == selectedId }.forEach { device ->
                        ConnectedRemoteRow(device, selectedId == device.id, false, "connected-offline-${device.id}") {}
                    }
                }
                if (state.devices.isEmpty()) Text("No other Echo players are currently visible.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (actionExpanded) {
            "move" -> Column(Modifier.fillMaxWidth().bringIntoViewRequester(actionBringIntoView)
                .onGloballyPositioned { if (!actionSectionPlaced) actionSectionPlaced = true }) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (displacedMoveOperationId != null && selectedId == null && v2State.group == null) {
                    if (v2State.reconcilingMove) {
                        ConnectedUnavailable("A Move is still being reconciled. Wait for its outcome before restoring the previous queue.")
                    } else if (!confirmRestorePrevious) {
                        OutlinedButton(enabled = !v2State.pending, onClick = { confirmRestorePrevious = true },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("connected-restore-previous")) {
                            Text("Restore previous queue paused")
                        }
                    } else {
                        Text("This replaces the current queue and restores the previous one paused.",
                            Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !v2State.pending, onClick = {
                                confirmRestorePrevious = false
                                vm.restorePreviousConnectedQueue()
                            }, modifier = Modifier.weight(1f).testTag("connected-restore-previous-confirm")) {
                                Text("Confirm restore")
                            }
                            OutlinedButton(onClick = { confirmRestorePrevious = false }, modifier = Modifier.weight(1f)) {
                                Text("Cancel")
                            }
                        }
                    }
                }
                if (v2State.group != null) {
                    ConnectedUnavailable("Leave Play together before moving this queue.")
                } else if (selectedId != null) {
                    ConnectedUnavailable("Choose This phone as the source before moving. The selected remote player's queue is not moved by this action.")
                } else if (vm.current == null) {
                    ConnectedUnavailable("Start a song on this player before moving the queue.")
                } else if (v2State.devices.none { it.id != vm.connectedPlaybackDeviceIdForUi && it.enabled && "move" in it.capabilities }) {
                    ConnectedUnavailable(v2State.message ?: "No ready Echo player can receive this queue.")
                } else {
                    Text("Move the current song and remaining queue. The destination's independent queue will be saved for paused recovery.",
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    v2State.devices.filter { it.id != vm.connectedPlaybackDeviceIdForUi && it.enabled && "move" in it.capabilities }
                        .forEach { device ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(device.label, style = MaterialTheme.typography.labelLarge)
                                Text(if (device.snapshot.playing) "Playing · ${device.snapshot.title.orEmpty()}" else "Paused · ${device.snapshot.title ?: "ready"}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(enabled = !v2State.pending, onClick = { vm.moveConnectedPlayback(device.id, true) },
                                    modifier = Modifier.testTag("connected-move-confirm-${device.id}")) {
                                    Text("Replace destination queue and Move")
                                }
                            }
                        }
                }
            }
            "together" -> Column(Modifier.fillMaxWidth().bringIntoViewRequester(actionBringIntoView)
                .onGloballyPositioned { if (!actionSectionPlaced) actionSectionPlaced = true }) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val group = v2State.group
                if (group != null) {
                    Text("Together · ${group.status.name.lowercase()} · song ${((group.timeline?.cursor ?: 0) + 1)}",
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    group.members.forEach { member ->
                        val memberName = if (member.deviceId == vm.connectedPlaybackDeviceIdForUi) "This phone" else
                            v2State.devices.firstOrNull { it.id == member.deviceId }?.label ?: member.deviceId.takeLast(8)
                        val memberStatus = when (member.status) {
                            dev.echo.data.firebase.GroupMemberV2Status.BUFFERING -> "Buffering"
                            dev.echo.data.firebase.GroupMemberV2Status.DISCONNECTED -> "Reconnecting"
                            dev.echo.data.firebase.GroupMemberV2Status.LEFT -> "Left"
                            else -> member.status.name.lowercase().replaceFirstChar(Char::uppercase)
                        }
                        Text("$memberName · $memberStatus", Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    group.timeline?.let { timeline ->
                        Text("Shared queue · ${timeline.queue.entries.size} songs", Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        val preview = timeline.queue.entries.drop(timeline.cursor).take(12)
                        preview.forEachIndexed { offset, occurrence ->
                            val index = timeline.cursor + offset
                            Text("${if (offset == 0) "Now · " else "${index + 1}. "}${occurrence.recording.title} · ${occurrence.recording.artist}",
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (offset == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val remaining = timeline.queue.entries.size - timeline.cursor - preview.size
                        if (remaining > 0) Text("+$remaining more songs", Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        OutlinedButton(enabled = !v2State.pending, onClick = { vm.controlTogether(GroupActionV2.PREVIOUS) }) { Text("Previous") }
                        OutlinedButton(enabled = !v2State.pending, onClick = {
                            vm.controlTogether(if (group.timeline?.playing == true) GroupActionV2.PAUSE else GroupActionV2.PLAY)
                        }) { Text(if (group.timeline?.playing == true) "Pause all" else "Play all") }
                        OutlinedButton(enabled = !v2State.pending, onClick = { vm.controlTogether(GroupActionV2.NEXT) }) { Text("Next") }
                    }
                    OutlinedButton(enabled = !v2State.pending, onClick = { vm.controlTogether(GroupActionV2.LEAVE) },
                        modifier = Modifier.padding(horizontal = 12.dp).testTag("connected-together-leave")) {
                        Text(if (group.coordinatorDeviceId == vm.connectedPlaybackDeviceIdForUi)
                            "End for everyone · restore this queue paused" else "Leave group · restore this queue paused")
                    }
                } else if (selectedId != null) {
                    ConnectedUnavailable("Choose This phone as the source before creating a shared queue.")
                } else if (vm.current == null) {
                    ConnectedUnavailable("Start a song on this player before creating a shared queue.")
                } else {
                    Text("Share this queue with up to ${MAX_CONNECTED_GROUP_MEMBERS - 1} other Echo players. Each device's independent queue remains available for paused recovery.",
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    Text("${togetherDeviceIds.size} of ${MAX_CONNECTED_GROUP_MEMBERS - 1} other players selected",
                        Modifier.padding(horizontal = 12.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
                    togetherCandidates.forEach { device ->
                        val selected = device.id in togetherDeviceIds
                        val expectedSession = togetherDeviceSessions[device.id]
                        val changedSession = selected && expectedSession != device.sessionId
                        val ready = device.enabled && !changedSession
                        Row(Modifier.fillMaxWidth().clickable(enabled = ready || selected) {
                            togetherDeviceSessions = if (selected) togetherDeviceSessions - device.id else
                                if (togetherDeviceSessions.size < MAX_CONNECTED_GROUP_MEMBERS - 1) togetherDeviceSessions + (device.id to device.sessionId) else togetherDeviceSessions
                        }.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = selected, enabled = ready || selected, onCheckedChange = { checked ->
                                togetherDeviceSessions = if (checked && !selected && togetherDeviceSessions.size < MAX_CONNECTED_GROUP_MEMBERS - 1)
                                    togetherDeviceSessions + (device.id to device.sessionId)
                                    else if (!checked) togetherDeviceSessions - device.id else togetherDeviceSessions
                            }, modifier = Modifier.testTag("connected-together-select-${device.id}"))
                            Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
                                Text(device.label, style = MaterialTheme.typography.labelLarge)
                                Text(if (changedSession) "Session changed · remove and select again" else if (!device.enabled) "Offline or unavailable" else if (device.snapshot.playing)
                                    "Playing · ${device.snapshot.title.orEmpty()}" else "Paused · ${device.snapshot.title ?: "ready"}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    togetherDeviceIds.filter { id -> togetherCandidates.none { it.id == id } }.forEach { id ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Selected player ${id.takeLast(8)} is no longer available. Remove it to continue.",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = { togetherDeviceSessions = togetherDeviceSessions - id }) { Text("Remove") }
                        }
                    }
                    if (togetherCandidates.none { it.enabled })
                        ConnectedUnavailable(v2State.message ?: "No ready Echo player can join this shared queue.")
                    if (invalidTogetherSelection) ConnectedUnavailable("A selected player is offline, unavailable, or its session changed. Remove it or wait until it is ready; no selected players will be skipped.")
                    Button(enabled = readyToStartTogether && !v2State.pending, onClick = {
                        if (selectedTogetherDevices.any { it.snapshot.playing }) confirmTogether = true
                        else {
                            vm.startTogether(togetherDeviceIds.toList(), togetherDeviceSessions, false)
                        }
                    }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("connected-together-start")) {
                        Text("Start Play together")
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().testTag(if (compact) "connected-mini-controls" else "connected-full-controls")) {
        if (compact) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(status, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Surface(onClick = {
                    dockExpanded = !dockExpanded
                    if (!dockExpanded) { devicesExpanded = false; actionExpanded = null; deviceSectionPlaced = false; actionSectionPlaced = false }
                }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.testTag("connected-mini-devices")) {
                    Row(Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(if (dockExpanded) "Done" else "Devices", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (dockExpanded) {
                Column(Modifier.fillMaxWidth().heightIn(max = compactExpandedHeight).verticalScroll(rememberScrollState())) { actions() }
            }
        } else {
            Text(status, Modifier.fillMaxWidth().padding(bottom = 5.dp).testTag("connected-player-status"), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
        if (state.pending) Text("Sending command…", Modifier.fillMaxWidth().padding(top = 4.dp).testTag("connected-command-pending"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else state.message?.let { Text(it, Modifier.fillMaxWidth().padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (confirmTogether) AlertDialog(onDismissRequest = { confirmTogether = false },
        title = { Text("Replace playing devices?") },
        text = { Text("At least one selected player is currently playing. Play together will replace its current playback and queue.") },
        confirmButton = { Button(enabled = !v2State.pending && readyToStartTogether, onClick = {
            confirmTogether = false
            vm.startTogether(togetherDeviceIds.toList(), togetherDeviceSessions, true)
        }, modifier = Modifier.testTag("connected-together-confirm")) { Text("Replace and start") } },
        dismissButton = { OutlinedButton(onClick = { confirmTogether = false }) { Text("Cancel") } })
}

@Composable
private fun ConnectedAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, expanded: Boolean, tag: String, click: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Surface(onClick = click, shape = CircleShape,
            color = if (expanded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(50.dp).testTag(tag).semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = if (expanded) "Expanded" else "Collapsed"
                role = Role.Button
            }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(icon, contentDescription = null, tint = if (expanded) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConnectedDeviceRow(label: String, detail: String, selected: Boolean, enabled: Boolean, tag: String, click: () -> Unit) {
    Surface(onClick = click, enabled = enabled, shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectedRemoteRow(device: ConnectedDevice, selected: Boolean, enabled: Boolean, tag: String, click: () -> Unit) {
    val detail = if (enabled) {
        val player = device.player
        if (player.title != null) "${if (player.isPlaying) "Playing" else "Paused"} · ${player.title} · ${player.artist.orEmpty()}"
        else "Ready for playback"
    } else {
        "Offline · last seen ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(device.updatedAt))}"
    }
    ConnectedDeviceRow(device.label, detail, selected, enabled, tag, click)
}

@Composable
private fun ConnectedUnavailable(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).testTag("connected-action-unavailable"),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun connectedArtworkUri(trackId: String?, tracks: List<Track>): String? {
    val local = trackId?.let { id -> tracks.firstOrNull { it.id == id } }
    if (!local?.artworkUri.isNullOrBlank()) return local?.artworkUri
    val videoId = trackId?.takeIf { it.startsWith("youtube:") }?.removePrefix("youtube:") ?: return null
    if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
    return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
}
