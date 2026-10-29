package dev.echo.app.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.echo.app.EchoViewModel

@Composable
internal fun ConnectedPlaybackSection(vm: EchoViewModel) {
    val email by vm.googleAccounts.email.collectAsStateWithLifecycle()
    val enabled by vm.connectedPlaybackEnabled.collectAsStateWithLifecycle()
    val state by vm.connectedPlaybackState.collectAsStateWithLifecycle()
    val visiblePlayers = state.devices.count { it.id != vm.connectedPlaybackDeviceIdForUi }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connected playback", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Let this device appear to your other Echo devices", modifier = Modifier.weight(1f))
            Switch(checked = enabled, enabled = email != null && vm.firebaseConfigured, modifier = Modifier.testTag("connected-playback-toggle"),
                onCheckedChange = vm::setConnectedPlaybackEnabled)
        }
        Text("When enabled, this device shares the current title, artist and playback state while Echo is open. Other connected devices can control playback.", style = MaterialTheme.typography.bodySmall)
        if (email == null) Text("Sign in and connect Firebase before enabling connected playback.", style = MaterialTheme.typography.bodySmall)
        else Text(if (enabled) "Choose the player you want to view and control from the full or mini player${if (visiblePlayers > 0) " · $visiblePlayers other ${if (visiblePlayers == 1) "player" else "players"} visible" else ""}."
            else "This setting lets other signed-in Echo devices view and control this player.", style = MaterialTheme.typography.bodySmall)
        if (enabled) state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
