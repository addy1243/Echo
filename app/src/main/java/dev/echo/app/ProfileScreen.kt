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
    val lastSynced by vm.firebaseLastSynced.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().testTag("profile-screen"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Your profile", style = MaterialTheme.typography.headlineMedium) }
        item { AccountSection(vm) }
        item {
            HorizontalDivider()
            Text("Your playlists", style = MaterialTheme.typography.titleLarge)
            Text("${playlists.size} playlists on this device")
            Text(if (email == null) "Sign in and connect Firebase to sync your library."
                else "Connect Firebase / Sync now shares playlists, favorites and listening history with $email. Your local library remains after signing out.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            HorizontalDivider()
            Text("Account sync", style = MaterialTheme.typography.titleLarge)
            when {
                email == null -> Text("Sign in to sync your library.")
                lastSynced == null -> Text("No successful Firebase sync on this device yet.")
                else -> {
                    Text("Last synced ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastSynced!!))}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Live device status and remote playback controls are separate features and aren't available yet. A saved library does not mean another device is online.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
