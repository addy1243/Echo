package dev.echo.app.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.echo.app.EchoViewModel
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedPlaybackAction
import dev.echo.data.firebase.ConnectedPlaybackState
import java.util.concurrent.TimeUnit

@Composable
internal fun ConnectedPlaybackSection(vm: EchoViewModel) {
    val email by vm.googleAccounts.email.collectAsStateWithLifecycle()
    val enabled by vm.connectedPlaybackEnabled.collectAsStateWithLifecycle()
    val state by vm.connectedPlaybackState.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connected playback", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Let this device appear to your other Echo devices", modifier = Modifier.weight(1f))
            Switch(checked = enabled, enabled = email != null && vm.firebaseConfigured, modifier = Modifier.testTag("connected-playback-toggle"),
                onCheckedChange = vm::setConnectedPlaybackEnabled)
        }
        Text("When enabled, this device shares the current title, artist and playback state while Echo is open. Other connected devices can control playback.", style = MaterialTheme.typography.bodySmall)
        if (email == null) Text("Sign in and connect Firebase before enabling connected playback.", style = MaterialTheme.typography.bodySmall)
        else if (enabled && state.devices.isEmpty()) Text(state.message ?: "Waiting for connected Echo devices…", style = MaterialTheme.typography.bodySmall)
        if (enabled) state.devices.forEach { device -> ConnectedDeviceCard(vm, device, state) }
        if (enabled) state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ConnectedDeviceCard(vm: EchoViewModel, device: ConnectedDevice, state: ConnectedPlaybackState) {
    val own = device.id == vm.connectedPlaybackDeviceIdForUi
    val canControl = device.controlEnabled && device.player.title != null && !state.pending
    Column {
        Text(device.label + if (own) " · This device" else "", style = MaterialTheme.typography.titleSmall)
        Text("${device.player.title ?: "Nothing playing"} — ${device.player.artist.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
        Text("${if (device.player.isPlaying) "Playing" else "Paused"} · ${clock(device.player.positionMs)} / ${clock(device.player.durationMs)} · ${if (device.controlEnabled) "Online" else "Offline"}", style = MaterialTheme.typography.bodySmall)
        if (!own) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(enabled = canControl, onClick = { vm.sendConnectedPlaybackCommand(device, ConnectedPlaybackAction.PREVIOUS) }) { Text("Previous") }
                OutlinedButton(enabled = canControl, onClick = { vm.sendConnectedPlaybackCommand(device, if (device.player.isPlaying) ConnectedPlaybackAction.PAUSE else ConnectedPlaybackAction.PLAY) }) {
                    Text(if (device.player.isPlaying) "Pause" else "Play")
                }
                OutlinedButton(enabled = canControl, onClick = { vm.sendConnectedPlaybackCommand(device, ConnectedPlaybackAction.NEXT) }) { Text("Next") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(enabled = canControl && device.player.durationMs > 0,
                    onClick = { vm.sendConnectedPlaybackCommand(device, ConnectedPlaybackAction.SEEK, (device.player.positionMs - 10_000).coerceAtLeast(0)) }) { Text("−10 sec") }
                Button(enabled = canControl && device.player.durationMs > 0,
                    onClick = { vm.sendConnectedPlaybackCommand(device, ConnectedPlaybackAction.SEEK, (device.player.positionMs + 10_000).coerceAtMost(device.player.durationMs)) }) { Text("+10 sec") }
            }
        }
        if (device.ack?.status == "rejected") Text("Last command was rejected (${device.ack?.reason ?: "stale playback state"}). Refresh the device state and try again.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun clock(milliseconds: Long): String {
    val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds.coerceAtLeast(0))
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
