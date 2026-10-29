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
import dev.echo.core.Track
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ConnectedPlayerControls(vm: EchoViewModel, compact: Boolean = false) {
    val state by vm.connectedPlaybackState.collectAsStateWithLifecycle()
    val selectedId by vm.selectedConnectedPlaybackTargetId.collectAsStateWithLifecycle()
    val rememberedRemote by vm.selectedConnectedPlaybackDevice.collectAsStateWithLifecycle()
    var devicesExpanded by remember(vm) { mutableStateOf(false) }
    var actionExpanded by remember(vm) { mutableStateOf<String?>(null) }
    var dockExpanded by remember(vm) { mutableStateOf(false) }
    var deviceSectionPlaced by remember(vm) { mutableStateOf(false) }
    var actionSectionPlaced by remember(vm) { mutableStateOf(false) }
    val devicesBringIntoView = remember { BringIntoViewRequester() }
    val actionBringIntoView = remember { BringIntoViewRequester() }
    val compactExpandedHeight = (LocalConfiguration.current.screenHeightDp * 0.4f).dp
    LaunchedEffect(devicesExpanded, deviceSectionPlaced, actionExpanded, actionSectionPlaced, compact) {
        if (!compact && actionExpanded != null && actionSectionPlaced) actionBringIntoView.bringIntoView()
        else if (!compact && devicesExpanded && deviceSectionPlaced) devicesBringIntoView.bringIntoView()
    }
    val freshTarget = if (selectedId == null) null else state.devices.firstOrNull { it.id == selectedId }
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
                ConnectedUnavailable("Moving this song between devices is not supported yet.")
            }
            "together" -> Column(Modifier.fillMaxWidth().bringIntoViewRequester(actionBringIntoView)
                .onGloballyPositioned { if (!actionSectionPlaced) actionSectionPlaced = true }) {
                ConnectedUnavailable("Playing together is not supported yet.")
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
