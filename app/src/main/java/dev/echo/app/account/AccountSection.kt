package dev.echo.app.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.echo.app.EchoViewModel
import kotlinx.coroutines.delay

/** The launcher and foreground scheduler live above navigation so leaving Profile cannot lose consent. */
@Composable internal fun AccountSyncHost(vm: EchoViewModel) {
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vm.completeGoogleSignIn(if (result.resultCode == android.app.Activity.RESULT_OK) result.data else null)
    }
    val choice = vm.googleAccountChoice
    LaunchedEffect(choice) {
        choice?.let {
            vm.accountChoiceLaunched()
            try { chooser.launch(it) } catch (_: Exception) { vm.completeGoogleSignIn(null) }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vm.completeDriveAuthorization(result.resultCode == android.app.Activity.RESULT_OK)
    }
    val resolution = vm.driveResolution
    LaunchedEffect(resolution) {
        resolution?.let {
            vm.driveResolutionLaunched()
            try { launcher.launch(it) }
            catch (_: Exception) { vm.completeDriveAuthorization(false) }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, vm) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (!vm.offline.settings.value.offlineOnly) vm.syncGoogle(manual = false)
                delay(60_000)
            }
        }
    }
}

@Composable internal fun AccountSection(vm: EchoViewModel) {
    val email by vm.googleAccounts.email.collectAsStateWithLifecycle()
    val automatic by vm.googleAccounts.automatic.collectAsStateWithLifecycle()
    var confirmSync by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Google account & playlist sync", style = MaterialTheme.typography.titleLarge)
        Text(email ?: "Using this device without an account")
        if (email == null) {
            Button(enabled = !vm.accountBusy, onClick = vm::signInGoogle) { Text("Sign in with Google") }
            Text("Uses the Google account provider installed on this device.", style = MaterialTheme.typography.bodySmall)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !vm.accountBusy, onClick = { confirmSync = true }) { Text("Connect Drive / Sync now") }
                TextButton(enabled = !vm.accountBusy, onClick = vm::signOutGoogle) { Text("Sign out") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Automatic playlist sync", modifier = Modifier.weight(1f))
                Switch(checked = automatic, enabled = !vm.accountBusy, onCheckedChange = {
                    if (it) confirmSync = true else vm.setAutomaticSync(false)
                })
            }
        }
        Text("Your local library stays on this device when you sign out. Connecting an account saves this device's playlists privately to its Drive and merges playlists from your other Echo devices. Conflicting edits become separate copies. Songs, likes, listening logs, downloads and local file paths aren't uploaded.", style = MaterialTheme.typography.bodySmall)
        Text("Automatic sync runs about once a minute while Echo is open. No Google account is needed for listening.", style = MaterialTheme.typography.bodySmall)
        if (vm.accountBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = vm::cancelAccountOperation) { Text("Cancel account operation") }
        }
        vm.accountStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmSync) AlertDialog(onDismissRequest = { confirmSync = false }, title = { Text("Sync playlists with $email?") },
        text = { Text("This saves the playlists currently on this device to this account. Other devices' changes will merge here, including deletions of unchanged playlists. Export a library backup first if you want an independent copy.") },
        confirmButton = { TextButton(onClick = { confirmSync = false; vm.setAutomaticSync(true) }) { Text("Enable automatic sync") } },
        dismissButton = { Row {
            TextButton(onClick = { confirmSync = false; vm.syncGoogle(manual = true) }) { Text("Sync once") }
            TextButton(onClick = { confirmSync = false }) { Text("Cancel") }
        } })
}
