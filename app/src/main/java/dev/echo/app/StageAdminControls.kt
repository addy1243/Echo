package dev.echo.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun nextStagePeerLabel(peers: List<AndroidStagePlayback.AdminPeer>, type: String): String {
    val number = generateSequence(1) { it + 1 }.first { candidate ->
        peers.none { it.label == "$type · Stage $candidate" }
    }
    return "$type · Stage $number"
}

@Composable
internal fun StageAdminControls(stage: AndroidStagePlayback) {
    var open by remember { mutableStateOf(false) }
    val peers by stage.adminPeers.collectAsState()
    ExtendedFloatingActionButton(onClick = { open = true }, icon = { Icon(Icons.Default.Build, "Stage Admin") }, text = { Text("Admin") })
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Stage Admin") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { stage.add(nextStagePeerLabel(peers, "Android")) }) { Text("Add Android") }
                    OutlinedButton(onClick = { stage.add(nextStagePeerLabel(peers, "Laptop")) }) { Text("Add laptop") }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(peers, key = { it.id }) { peer ->
                        Column(Modifier.fillMaxWidth()) {
                            Text("${peer.label} · ${if (peer.online) "Online" else "Offline"}", maxLines = 1)
                            Text(if (peer.latency == AndroidStagePlayback.LatencyPreset.VARIABLE)
                                "Simulated network · variable RTT and jitter" else
                                "Simulated network · ${peer.latency.rttMs} ms RTT · ±${peer.latency.jitterMs} ms jitter",
                                maxLines = 1, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(onClick = { stage.setLatency(peer.id, AndroidStagePlayback.LatencyPreset.FAST_20) }) { Text("20 ms") }
                                OutlinedButton(onClick = { stage.setLatency(peer.id, AndroidStagePlayback.LatencyPreset.SLOW_500) }) { Text("500 ms") }
                                OutlinedButton(onClick = { stage.setLatency(peer.id, AndroidStagePlayback.LatencyPreset.VARIABLE) }) { Text("Variable") }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { stage.toggle(peer.id) }) { Text(if (peer.online) "Disconnect" else "Reconnect") }
                                TextButton(onClick = { stage.remove(peer.id) }) { Text("Remove") }
                            }
                        }
                    }
                }
                Text("Demo peers play silently. No account is needed.")
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } },
    )
}
