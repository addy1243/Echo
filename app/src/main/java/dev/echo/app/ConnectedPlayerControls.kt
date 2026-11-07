package dev.echo.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.graphics.Color
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectedPlayerControls(
    vm: EchoViewModel,
    compact: Boolean = false,
    presentation: PlayerPresentationState? = null,
) {
    val state by vm.connectedPlaybackState.collectAsStateWithLifecycle()
    val v2State by vm.connectedPlaybackV2State.collectAsStateWithLifecycle()
    val displacedMoveOperationId by vm.displacedMoveOperationId.collectAsStateWithLifecycle()
    val selectedId by vm.selectedConnectedPlaybackTargetId.collectAsStateWithLifecycle()
    val rememberedRemote by vm.selectedConnectedPlaybackDevice.collectAsStateWithLifecycle()
    var devicesExpanded by remember(vm) { mutableStateOf(false) }
    var actionExpanded by remember(vm) { mutableStateOf<String?>(null) }
    var deckOpen by remember(vm) { mutableStateOf(false) }
    var deviceSectionPlaced by remember(vm) { mutableStateOf(false) }
    var actionSectionPlaced by remember(vm) { mutableStateOf(false) }
    var confirmRestorePrevious by remember(vm) { mutableStateOf(false) }
    var togetherDeviceSessions by remember(vm) { mutableStateOf(emptyMap<String, String>()) }
    var confirmTogether by remember(vm) { mutableStateOf(false) }
    val devicesBringIntoView = remember { BringIntoViewRequester() }
    val actionBringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(devicesExpanded, deviceSectionPlaced, actionExpanded, actionSectionPlaced, compact) {
        if (!compact && actionExpanded != null && actionSectionPlaced) actionBringIntoView.bringIntoView()
        else if (!compact && devicesExpanded && deviceSectionPlaced) devicesBringIntoView.bringIntoView()
    }
    val selectedSession = rememberedRemote?.sessionId
    val freshTarget = if (selectedId == null || selectedSession == null) null else
        state.devices.firstOrNull { it.id == selectedId && it.sessionId == selectedSession }
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
    val groupMemberIds = v2State.group?.members?.filter { it.status != dev.echo.data.firebase.GroupMemberV2Status.LEFT }
        ?.map { it.deviceId }?.toSet().orEmpty()
    val activeGroup = v2State.group
    val remoteFocused = selectedId != null && selectedId !in groupMemberIds
    val groupFocused = v2State.group != null && !remoteFocused
    val target = if (remoteFocused) freshTarget ?: rememberedRemote else null
    val targetLabel = target?.label ?: when {
        groupFocused -> "Play together"
        selectedId == null -> "This phone"
        else -> "selected player"
    }
    val status = when {
        groupFocused -> "Together · ${v2State.group?.members?.size ?: 0} devices · ${when {
            v2State.group?.status == dev.echo.data.firebase.GroupV2Status.PREPARING -> "Preparing"
            v2State.group?.members?.any { it.status == dev.echo.data.firebase.GroupMemberV2Status.BUFFERING } == true -> "Catching up"
            v2State.group?.timeline?.playing == true -> "Playing"
            else -> "Paused"
        }}"
        selectedId != null && target == null -> "Reconnecting to $targetLabel"
        selectedId != null && freshTarget?.controlEnabled != true -> "Reconnecting to $targetLabel"
        selectedId != null && freshTarget?.player?.title == null -> "Controlling $targetLabel · Ready"
        selectedId != null -> "${if (target?.player?.isPlaying == true) "Playing on" else "Paused on"} $targetLabel"
        vm.current == null -> "Controlling This phone"
        vm.playing -> "Playing on This phone"
        else -> "Paused on This phone"
    }
    val independentDevices = independentConnectedPlayers(state.devices, vm.connectedPlaybackDeviceIdForUi, groupMemberIds)
    val focusTargets = connectedPlaybackFocusTargets(independentDevices,
        rememberedRemote.takeIf { selectedId != null && selectedId !in groupMemberIds })
    val deckTargets = focusTargets
    val selectedDeckIndex = deckTargets.indexOfFirst { target ->
        if (selectedId == null || groupFocused) target == null
        else target != null && target.id == selectedId && target.sessionId == rememberedRemote?.sessionId
    }
    val currentTargetIndex = focusTargets.indexOfFirst { target ->
        if (selectedId == null) target == null else target?.id == selectedId && target?.sessionId == rememberedRemote?.sessionId
    }.coerceAtLeast(0)
    val nextTargetIndex = if (focusTargets.size > 1) (currentTargetIndex + 1) % focusTargets.size else -1
    val nextTarget = focusTargets.getOrNull(nextTargetIndex)
    val nextTargetLabel = nextTarget?.label ?: if (v2State.group != null) "Play together" else "This phone"
    val targetSwipeKey = focusTargets.joinToString("|") { it?.let { device -> "${device.id}:${device.sessionId}" } ?: "local-or-group" }
    val changeFocus: (ConnectedDevice?) -> Unit = { device -> vm.selectConnectedPlaybackTarget(device) }
    if (compact) {
        val showDeck = deckTargets.size > 1
        val independentCount = deckTargets.size
        if (showDeck) {
            Box {
                Surface(
                    onClick = { deckOpen = true },
                    shape = RoundedCornerShape(12.dp),
                    color = if (groupFocused) Color(0xFF214A43) else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(48.dp)
                        .testTag("connected-mini-devices")
                        .semantics {
                            contentDescription = "Choose player, $independentCount players"
                            role = Role.Button
                            stateDescription = if (groupFocused) "Play together focused" else "${deckTargets.size} players"
                        },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val peekCount = (deckTargets.size - 1).coerceAtMost(2)
                        repeat(peekCount) { index ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.size(34.dp, 38.dp).offset(x = (index * 4).dp),
                            ) {}
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (groupFocused) Color(0xFF83E0D2) else MaterialTheme.colorScheme.primary),
                            modifier = Modifier.size(34.dp, 38.dp).offset(x = (peekCount * 4).dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                if (independentCount > 1) {
                    Surface(
                        shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).size(17.dp),
                    ) { Box(contentAlignment = Alignment.Center) {
                        Text("$independentCount", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary, maxLines = 1)
                    } }
                }
            }
        }
        if (deckOpen) {
            ModalBottomSheet(onDismissRequest = { deckOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
                Column(Modifier.fillMaxWidth().fillMaxHeight(.56f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Players", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (groupFocused) Text("Play together · ${v2State.group?.members?.size ?: 0} devices", color = Color(0xFF83E0D2),
                        style = MaterialTheme.typography.labelLarge)
                    if (deckTargets.isEmpty()) Text("No independent players are available.", style = MaterialTheme.typography.bodyMedium)
                    else BoxWithConstraints(Modifier.fillMaxWidth().height(154.dp).testTag("connected-mini-player-deck")) {
                        val cardWidth = (maxWidth - 18.dp).coerceAtLeast(220.dp).coerceAtMost(maxWidth)
                        val activeIndex = selectedDeckIndex.takeIf { it >= 0 } ?: 0
                        val order = deckTargets.indices.filter { it != activeIndex } + activeIndex
                        order.forEach { index ->
                            val target = deckTargets[index]
                            val isSelected = index == selectedDeckIndex
                            val groupRecording = activeGroup?.timeline?.let { timeline -> timeline.queue.entries.getOrNull(timeline.cursor)?.recording }
                            val groupTarget = target == null && activeGroup != null
                            val tab = if (isSelected) 0 else 18 + (index * 4 % 12)
                            val live = target?.let { device -> state.devices.firstOrNull {
                                it.id == device.id && it.sessionId == device.sessionId && it.controlEnabled
                            } }
                            val title = when {
                                groupTarget -> groupRecording?.title ?: "Preparing shared player"
                                target == null -> vm.controller?.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Choose a song"
                                live == null -> target.player.title ?: "Reconnecting"
                                else -> live.player.title ?: "Ready for playback"
                            }
                            val deviceLabel = target?.label ?: if (groupTarget) "Play together" else "This phone"
                            Surface(
                                onClick = {
                                    if (target == null || live != null) changeFocus(target)
                                    deckOpen = false
                                },
                                enabled = target == null || live != null,
                                shape = RoundedCornerShape(18.dp),
                                color = if (groupTarget) Color(0xFF1C302F) else MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(if (isSelected) 2.dp else 1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.width(cardWidth).height(142.dp).offset(x = tab.dp)
                                    .align(Alignment.CenterStart).testTag("connected-mini-card-${target?.id ?: "local"}"),
                            ) {
                                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                        val art = if (groupTarget) connectedArtworkUri(groupRecording?.id, emptyList())
                                            else if (target == null) vm.controller?.currentMediaItem?.mediaMetadata?.artworkUri?.toString()
                                            else connectedArtworkUri(target.player.trackId, listOfNotNull(presentation?.current))
                                        Artwork(art, 48)
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(deviceLabel, style = MaterialTheme.typography.labelLarge, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(when {
                                            target != null && live == null -> "Reconnecting · session changed"
                                            groupTarget && activeGroup?.timeline?.playing == true -> "Playing · ${groupRecording?.artist.orEmpty()}".trimEnd()
                                            groupTarget && groupRecording != null -> "Paused · ${groupRecording.artist}"
                                            groupTarget -> "Ready · Play together"
                                            target == null && vm.playing -> "Playing · ${vm.controller?.currentMediaItem?.mediaMetadata?.artist ?: ""}".trimEnd()
                                            target == null -> "${if (vm.current != null) "Paused" else "Ready"} · This phone"
                                            live?.player?.isPlaying == true -> "Playing · ${live.player.artist.orEmpty()}".trimEnd()
                                            live?.player?.title != null -> "Paused · ${live.player.artist.orEmpty()}".trimEnd()
                                            else -> "Ready · Echo player"
                                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        Row(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 10.dp)
                            .horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            deckTargets.indices.filter { it != activeIndex }.forEach { index ->
                                val target = deckTargets[index]
                                val live = target?.let { device -> state.devices.firstOrNull {
                                    it.id == device.id && it.sessionId == device.sessionId && it.controlEnabled
                                } }
                                Surface(onClick = {
                                    if (target == null || live != null) changeFocus(target)
                                    deckOpen = false
                                }, enabled = target == null || live != null,
                                    shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.height(48.dp).testTag("connected-mini-card-tab-${target?.id ?: "local"}")) {
                                    Text(target?.label ?: "This phone", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }
    val focusStrip: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().pointerInput(targetSwipeKey, selectedId, groupFocused) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount -> change.consume(); totalDrag += amount },
                    onDragEnd = {
                        when {
                            focusTargets.size > 1 && totalDrag < -48f -> changeFocus(focusTargets[(currentTargetIndex + 1) % focusTargets.size])
                            focusTargets.size > 1 && totalDrag > 48f -> changeFocus(focusTargets[(currentTargetIndex - 1 + focusTargets.size) % focusTargets.size])
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f },
                )
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlayerFocusPill(if (groupFocused) "Play together" else if (selectedId == null) "This phone" else targetLabel, selected = true,
                    compact = compact, modifier = Modifier.weight(1f).testTag("connected-player-focused-target"))
                if (focusTargets.size > 1) {
                    Surface(onClick = { changeFocus(nextTarget) }, shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f).testTag("connected-player-next-target")) {
                        Text(nextTargetLabel, Modifier.padding(horizontal = 10.dp, vertical = if (compact) 7.dp else 10.dp),
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (state.devices.isNotEmpty()) {
                    Surface(onClick = {
                        deviceSectionPlaced = false
                        devicesExpanded = !devicesExpanded
                    }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.testTag("connected-player-device-count")) {
                        Text("Devices ${state.devices.count { it.id != vm.connectedPlaybackDeviceIdForUi && it.id !in groupMemberIds && it.controlEnabled }}",
                            Modifier.padding(horizontal = 10.dp, vertical = if (compact) 7.dp else 10.dp),
                            style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
        }
    }
    val actions: @Composable () -> Unit = {
        if (dev.echo.app.BuildConfig.STAGING) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                ConnectedAction("Devices", Icons.Default.Devices, devicesExpanded, "connected-devices-action") {
                    deviceSectionPlaced = false
                    devicesExpanded = !devicesExpanded
                }
                ConnectedAction("Together", Icons.Default.Groups, actionExpanded == "together", "connected-together-action") {
                    actionSectionPlaced = false
                    actionExpanded = if (actionExpanded == "together") null else "together"
                }
            }
        } else {
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
        }
        if (devicesExpanded) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp).bringIntoViewRequester(devicesBringIntoView)
                .onGloballyPositioned { if (!deviceSectionPlaced) deviceSectionPlaced = true }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (activeGroup != null) {
                    ConnectedDeviceRow("Play together · ${activeGroup.members.size} devices", "Shared player",
                        selectedId == null, true, "connected-group-focus") {
                        vm.selectConnectedPlaybackTarget(null); deviceSectionPlaced = false; devicesExpanded = false
                    }
                    activeGroup.members.forEach { member ->
                        val label = if (member.deviceId == vm.connectedPlaybackDeviceIdForUi ||
                            BuildConfig.STAGING && member.deviceId == "android-stage-local") "This phone" else
                            v2State.devices.firstOrNull { it.id == member.deviceId }?.label ?: member.deviceId.takeLast(8)
                        val status = when (member.status) {
                            dev.echo.data.firebase.GroupMemberV2Status.BUFFERING -> "Buffering"
                            dev.echo.data.firebase.GroupMemberV2Status.DISCONNECTED -> "Reconnecting"
                            dev.echo.data.firebase.GroupMemberV2Status.LEFT -> "Left"
                            else -> member.status.name.lowercase().replaceFirstChar(Char::uppercase)
                        }
                        ConnectedDeviceRow(label, status, true, false, "connected-group-member-${member.deviceId}") {}
                    }
                    val independent = state.devices.filter {
                        it.id != vm.connectedPlaybackDeviceIdForUi && it.id !in groupMemberIds
                    }
                    independent.filter { it.controlEnabled }.sortedByDescending { it.id == selectedId }.forEach { device ->
                        ConnectedRemoteRow(device, selectedId == device.id, true, "connected-independent-device-${device.id}") {
                            vm.selectConnectedPlaybackTarget(device)
                            deviceSectionPlaced = false
                            devicesExpanded = false
                        }
                    }
                    independent.filterNot { it.controlEnabled }.forEach { device ->
                        ConnectedRemoteRow(device, selectedId == device.id, false, "connected-independent-offline-${device.id}") {}
                    }
                } else {
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
                } else if (vm.current == null && !dev.echo.app.BuildConfig.STAGING) {
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
                        val memberName = if (member.deviceId == vm.connectedPlaybackDeviceIdForUi ||
                            BuildConfig.STAGING && member.deviceId == "android-stage-local") "This phone" else
                            v2State.devices.firstOrNull { it.id == member.deviceId }?.label ?: member.deviceId.takeLast(8)
                        val memberStatus = when (member.status) {
                            dev.echo.data.firebase.GroupMemberV2Status.INVITED,
                            dev.echo.data.firebase.GroupMemberV2Status.PREPARING -> "Preparing"
                            dev.echo.data.firebase.GroupMemberV2Status.BUFFERING -> "Catching up"
                            dev.echo.data.firebase.GroupMemberV2Status.DISCONNECTED -> "Reconnecting"
                            dev.echo.data.firebase.GroupMemberV2Status.LEFT -> "Left"
                            else -> member.status.name.lowercase().replaceFirstChar(Char::uppercase)
                        }
                        Text("$memberName · $memberStatus", Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sample = group.syncTiming.firstOrNull { timing ->
                            timing.deviceId == member.deviceId && timing.sessionId == member.sessionId &&
                                System.currentTimeMillis() - timing.sampledAtMs in 0..15_000L
                        }
                        if (sample != null) Text("${if (BuildConfig.STAGING) "Simulated network estimate" else "Network estimate"} · ${sample.roundTripP95Ms} ms RTT · ±${sample.jitterMs} ms jitter",
                            Modifier.padding(start = 24.dp, end = 12.dp, bottom = 3.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else Text(if (BuildConfig.STAGING) "Simulated timing unavailable · 8s start"
                            else "Timing estimate unavailable · 8s start",
                            Modifier.padding(start = 24.dp, end = 12.dp, bottom = 3.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("Coordinator · ${if (group.coordinatorDeviceId == vm.connectedPlaybackDeviceIdForUi ||
                        BuildConfig.STAGING && group.coordinatorDeviceId == "android-stage-local") "This phone" else v2State.devices.firstOrNull { it.id == group.coordinatorDeviceId }?.label ?: group.coordinatorDeviceId.takeLast(8)}",
                        Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val independentPeers = v2State.devices.filter {
                        it.id != vm.connectedPlaybackDeviceIdForUi && it.id !in group.members.map { member -> member.deviceId } && it.enabled
                    }
                    if (independentPeers.isNotEmpty()) {
                        Text("Other online devices · leave the group to focus one separately",
                            Modifier.padding(horizontal = 12.dp, vertical = 5.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        independentPeers.forEach { peer ->
                            ConnectedDeviceRow(peer.label,
                                if (peer.snapshot.playing) "Playing · ${peer.snapshot.title.orEmpty()}" else "Paused · ${peer.snapshot.title ?: "ready"}",
                                false, false, "connected-independent-peer-${peer.id}") {}
                        }
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
                    val syncReady = group.status == dev.echo.data.firebase.GroupV2Status.ACTIVE &&
                        group.timeline != null && group.members.all { it.status in setOf(
                            dev.echo.data.firebase.GroupMemberV2Status.PLAYING,
                            dev.echo.data.firebase.GroupMemberV2Status.PAUSED,
                        ) && (it.deviceId == vm.connectedPlaybackDeviceIdForUi ||
                            BuildConfig.STAGING && it.deviceId == "android-stage-local" || v2State.devices.any { device ->
                            device.id == it.deviceId && device.sessionId == it.sessionId && "sync" in device.capabilities
                        }) }
                    OutlinedButton(enabled = syncReady && !v2State.pending,
                        onClick = { vm.controlTogether(GroupActionV2.SYNC) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("connected-together-sync")) {
                        Text(if (v2State.pending) "Adjusting timing…" else "Sync now")
                    }
                    Text("Network RTT and jitter are estimates. They do not measure speaker or output delay.",
                        Modifier.padding(horizontal = 12.dp, vertical = 3.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(enabled = !v2State.pending, onClick = { vm.controlTogether(GroupActionV2.LEAVE) },
                        modifier = Modifier.padding(horizontal = 12.dp).testTag("connected-together-leave")) {
                        Text(if (group.coordinatorDeviceId == vm.connectedPlaybackDeviceIdForUi ||
                            BuildConfig.STAGING && group.coordinatorDeviceId == "android-stage-local")
                            "End for everyone · restore this queue paused" else "Leave group · restore this queue paused")
                    }
                } else if (selectedId != null) {
                    ConnectedUnavailable("Choose This phone as the source before creating a shared queue.")
                } else if (vm.current == null && !dev.echo.app.BuildConfig.STAGING) {
                    ConnectedUnavailable("Start a song on this player before creating a shared queue.")
                } else {
                    Text("Coordinator · This phone", Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Share this queue with up to ${MAX_CONNECTED_GROUP_MEMBERS - 1} selected players. Each device's independent queue remains available for paused recovery.",
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
    Column(Modifier.fillMaxWidth().testTag("connected-full-controls")) {
            Text(status, Modifier.fillMaxWidth().padding(bottom = 5.dp).testTag("connected-player-status"), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            focusStrip()
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
            actions()
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
private fun PlayerFocusPill(label: String, selected: Boolean, compact: Boolean, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = if (compact) 7.dp else 10.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
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
