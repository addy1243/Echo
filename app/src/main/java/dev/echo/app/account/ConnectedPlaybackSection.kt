package dev.echo.app.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.echo.app.EchoViewModel

@Composable
internal fun ConnectedPlaybackSection(vm: EchoViewModel) {
    val email by vm.googleAccounts.email.collectAsStateWithLifecycle()
    val state by vm.connectedPlaybackState.collectAsStateWithLifecycle()
    val visiblePlayers = state.devices.count { it.id != vm.connectedPlaybackDeviceIdForUi }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connected playback", style = MaterialTheme.typography.titleLarge)
        Text("When Echo is open and you are signed in, this device shares its current title, artist and playback state with your other Echo devices. Android participation stops when Echo leaves the foreground. Audio stays on its original device.", style = MaterialTheme.typography.bodySmall)
        if (email == null) Text("Sign in to connect this player to your other Echo devices.", style = MaterialTheme.typography.bodySmall)
        else Text("Choose a connected player from the full or mini player${if (visiblePlayers > 0) " · $visiblePlayers other ${if (visiblePlayers == 1) "player" else "players"} visible" else ""}.", style = MaterialTheme.typography.bodySmall)
        if (email != null) state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
