package dev.echo.app.update

import android.provider.Settings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.echo.app.EchoViewModel

@Composable
fun UpdatePrompt(vm: EchoViewModel) {
    val status by vm.updater.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val install = {
        vm.runAction {
            val intent = vm.updater.installationIntent()
            if (intent.action == Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) vm.message = "Allow updates from Echo, then return and tap Install update."
            context.startActivity(intent)
        }
        Unit
    }
    var hiddenDownload by remember { mutableStateOf(false) }
    LaunchedEffect(status is UpdateStatus.Downloading) { if (status !is UpdateStatus.Downloading) hiddenDownload = false }
    if (status !is UpdateStatus.Downloading || !hiddenDownload) {
        UpdateDialog(status, vm::downloadUpdate, vm::cancelUpdate,
            { if (status is UpdateStatus.Downloading) hiddenDownload = true else vm.updater.dismiss() }, install, { vm.checkUpdates() })
    }
}

@Composable
internal fun UpdateDialog(
    status: UpdateStatus, download: (UpdateRelease) -> Unit, cancel: () -> Unit,
    dismiss: () -> Unit, install: () -> Unit, check: () -> Unit,
) {
    when (val current = status) {
        is UpdateStatus.Available -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Update available · ${current.release.versionName}") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { Text("A newer Echo Music version is available (${current.release.size / 1024 / 1024} MB). Your library stays on this device."); Spacer(Modifier.height(12.dp)); Text(current.release.notes.ifBlank { "An updated version of Echo Music." }) } },
            confirmButton = { TextButton(onClick = { download(current.release) }) { Text("Update app") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Later") } },
        )
        is UpdateStatus.Downloading -> AlertDialog(
            onDismissRequest = dismiss, title = { Text("Downloading update") },
            text = { Column { LinearProgressIndicator(progress = { current.percent / 100f }, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(12.dp)); Text("${current.percent}% · downloading in the background") } },
            confirmButton = { TextButton(onClick = dismiss) { Text("Hide") } },
            dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } },
        )
        is UpdateStatus.Ready -> AlertDialog(
            onDismissRequest = dismiss, title = { Text("Update ready · ${current.release.versionName}") },
            text = { Text("Android will ask you to confirm the update. If permission is needed, allow installs from Echo, return here, and tap Install update again.") },
            confirmButton = { TextButton(onClick = install) { Text("Install update") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Later") } },
        )
        is UpdateStatus.Failed -> AlertDialog(
            onDismissRequest = dismiss, title = { Text("Update could not finish") }, text = { Text(current.message) },
            confirmButton = { TextButton(onClick = { current.release?.let(download) ?: check() }) { Text("Try again") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Close") } },
        )
        else -> Unit
    }
}
