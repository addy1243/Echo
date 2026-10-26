package dev.echo.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun SleepTimerControls(vm: EchoViewModel) {
    val remainingMs by vm.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    val seconds = remainingMs?.coerceAtLeast(0)?.let { (it + 999) / 1000 }
    val countdown = seconds?.let { "%d:%02d".format(it / 60, it % 60) }
    TextButton(onClick = { showDialog = true }, modifier = Modifier.testTag("sleep-timer-button").semantics {
        stateDescription = if (seconds == null) "Off" else "${seconds / 60} minutes ${seconds % 60} seconds remaining"
    }) {
        Icon(Icons.Default.Bedtime, null)
        Spacer(Modifier.width(6.dp))
        Text(if (countdown == null) "Sleep timer" else "Sleep timer · $countdown")
    }
    if (showDialog) AlertDialog(
        onDismissRequest = { showDialog = false },
        title = { Text("Sleep timer") },
        text = {
            Column {
                Text("Echo pauses playback when the countdown ends. Closing the player keeps the timer running. Stopping Echo cancels it.")
                countdown?.let { Text("Time remaining: $it", modifier = Modifier.padding(top = 10.dp)) }
                listOf(15, 30, 45, 60).forEach { minutes ->
                    TextButton(onClick = { vm.setSleepTimer(minutes); showDialog = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("$minutes minutes")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showDialog = false }) { Text("Close") } },
        dismissButton = {
            if (remainingMs != null) TextButton(onClick = { vm.cancelSleepTimer(); showDialog = false }) { Text("Cancel timer") }
        },
    )
}
