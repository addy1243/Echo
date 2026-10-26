package dev.echo.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.echo.app.account.AccountSection
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ProfileScreen(vm: EchoViewModel) {
    val email by vm.googleAccounts.email.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val report by vm.syncDeviceReport.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().testTag("profile-screen"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Your profile", style = MaterialTheme.typography.headlineMedium) }
        item { AccountSection(vm) }
        item {
            HorizontalDivider()
            Text("Your playlists", style = MaterialTheme.typography.titleLarge)
            Text("${playlists.size} playlists on this device")
            Text(if (email == null) "Sign in and connect Drive to save playlists to your Google account."
                else "Connect Drive / Sync now saves and merges your playlists with $email. Your local library remains on this device after signing out.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            HorizontalDivider()
            Text("Devices with synced playlists", style = MaterialTheme.typography.titleLarge)
            when {
                email == null -> Text("Sign in to view account device registrations.")
                report == null -> Text("Device count is available after a successful Drive sync.")
                else -> {
                    val verified = report!!
                    Text("${verified.registeredDevices} synced ${if (verified.registeredDevices == 1) "device" else "devices"}", style = MaterialTheme.typography.titleMedium)
                    Text("Last checked ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(verified.checkedAt))}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Counts Echo installations with saved playlist snapshots in this account, including this installation after sync. Devices may be offline, and reinstalling Echo creates a separate registration. The count updates after a successful sync.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
