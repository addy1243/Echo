package dev.echo.app.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.echo.app.EchoViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The launcher and foreground scheduler live above navigation so leaving Profile cannot lose consent. */
@Composable internal fun AccountSyncHost(vm: EchoViewModel) {
    if (dev.echo.app.BuildConfig.STAGING) return
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
    val email by vm.googleAccounts.email.collectAsStateWithLifecycle()
    LaunchedEffect(vm, email) {
        if (email != null) {
            // The media service owns connected playback after this foreground handoff.
            // Keep retrying controller startup while the app composition is alive, but do not
            // stop the service-owned session merely because the Activity goes to background.
            while (true) { vm.runConnectedPlayback(); delay(2_000) }
        } else withContext(NonCancellable) { vm.stopConnectedPlaybackAndAwait() }
    }
}

@Composable internal fun AccountSection(vm: EchoViewModel) {
    if (dev.echo.app.BuildConfig.STAGING) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stage account disabled", style = MaterialTheme.typography.titleLarge)
            Text("This Stage install is separate from Echo Music. Admin adds local demo players; no Google account or cloud sync is used.")
        }
        return
    }
    val email by vm.googleAccounts.email.collectAsStateWithLifecycle()
    val automatic by vm.googleAccounts.automatic.collectAsStateWithLifecycle()
    var confirmSync by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Google account & library sync", style = MaterialTheme.typography.titleLarge)
        Text(email ?: "Using this device without an account")
        if (email == null) {
            Button(enabled = !vm.accountBusy, onClick = vm::signInGoogle) { Text("Sign in with Google") }
            Text("Uses the Google account provider installed on this device.", style = MaterialTheme.typography.bodySmall)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !vm.accountBusy && vm.firebaseConfigured, onClick = { confirmSync = true }) { Text("Connect Firebase / Sync now") }
                TextButton(enabled = !vm.accountBusy, onClick = vm::signOutGoogle) { Text("Sign out") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Automatic library sync", modifier = Modifier.weight(1f))
                Switch(checked = automatic, enabled = !vm.accountBusy && vm.firebaseConfigured, onCheckedChange = {
                    if (it) confirmSync = true else vm.setAutomaticSync(false)
                })
            }
        }
        Text("Firebase sync shares playlists, favorites and listening history with your other Echo devices. Your local library stays when you sign out. Conflicting playlist edits become separate copies. Audio, downloads, credentials and local file paths aren't uploaded. Existing Drive snapshots remain untouched.", style = MaterialTheme.typography.bodySmall)
        if (email != null) SocialSection(vm)
        if (!vm.firebaseConfigured) Text("Firebase project setup is pending. Listening and local library controls remain available.", style = MaterialTheme.typography.bodySmall)
        Text("Automatic sync runs about once a minute while Echo is open. No Google account is needed for listening.", style = MaterialTheme.typography.bodySmall)
        if (vm.accountBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = vm::cancelAccountOperation) { Text("Cancel account operation") }
        }
        vm.accountStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmSync) AlertDialog(onDismissRequest = { confirmSync = false }, title = { Text("Sync library with $email?") },
        text = { Text("This uploads this device's playlists, favorites and up to 90 days of listening history to your Firebase account. Changes from other devices merge here, including unlikes, playlist deletions and history clearing. The local library is shared on this device, so check the account before uploading. Export a library backup first if you want an independent copy.") },
        confirmButton = { TextButton(onClick = { confirmSync = false; vm.setAutomaticSync(true) }) { Text("Enable automatic sync") } },
        dismissButton = { Row {
            TextButton(onClick = { confirmSync = false; vm.syncGoogle(manual = true) }) { Text("Sync once") }
            TextButton(onClick = { confirmSync = false }) { Text("Cancel") }
        } })
}

@Composable private fun SocialSection(vm: EchoViewModel) {
    val offline by vm.offlineSettings.collectAsStateWithLifecycle()
    val enabled = vm.socialConfigured && !vm.accountBusy && !offline.offlineOnly
    var username by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var avatar by rememberSaveable { mutableStateOf("") }
    var avatarMenu by rememberSaveable { mutableStateOf(false) }
    var editingProfile by rememberSaveable { mutableStateOf(false) }
    var lookup by rememberSaveable { mutableStateOf("") }
    var invite by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(vm, vm.socialConfigured, offline.offlineOnly) { if (vm.socialConfigured && !offline.offlineOnly) vm.loadSocial() }
    val profile = vm.socialProfile
    LaunchedEffect(profile?.optInt("version"), profile == null) {
        if (profile != null) {
            username = profile.optString("username")
            displayName = profile.optString("displayName")
            avatar = profile.optString("avatar", "avatar_1")
            editingProfile = false
        } else editingProfile = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Friends", style = MaterialTheme.typography.titleLarge)
        Text("Social setup is optional. Signing in alone does not publish activity. Online presence and current-song sharing are separate controls.", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = vm.socialConfigured && !vm.accountBusy && !offline.offlineOnly, onClick = vm::loadSocial) { Text("Refresh social") }
        if (!vm.socialConfigured) Text("Social is unavailable: Echo has no approved HTTPS service endpoint yet. No account token will be sent.", style = MaterialTheme.typography.bodySmall)
        else if (offline.offlineOnly) Text("Social is paused while Offline mode is on.", style = MaterialTheme.typography.bodySmall)
        if (profile == null || editingProfile) {
            Text(if (profile == null) "Create a social profile" else "Edit social profile", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(username, { username = it.take(24) }, label = { Text("Username (3–24 letters, numbers or underscores)") }, singleLine = true, enabled = vm.socialConfigured, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(displayName, { displayName = it.take(40) }, label = { Text("Profile name (up to 40 characters)") }, singleLine = true, enabled = vm.socialConfigured, modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = vm.socialConfigured, onClick = { avatarMenu = true }) { Text("Avatar ${avatar.substringAfter('_').ifBlank { "1" }}") }
            DropdownMenu(expanded = avatarMenu, onDismissRequest = { avatarMenu = false }) {
                (1..8).forEach { index -> DropdownMenuItem(text = { Text("Avatar $index") }, onClick = { avatar = "avatar_$index"; avatarMenu = false }) }
            }
            Button(enabled = enabled && username.matches(Regex("[A-Za-z0-9_]{3,24}") ) && displayName.trim().length in 1..40, onClick = {
                vm.saveSocialProfile(username.trim(), displayName.trim(), avatar.ifBlank { "avatar_1" }, profile?.optInt("version"))
            }) { Text(if (profile == null) "Set up social profile" else "Save profile") }
            if (profile == null) Text("Online and current-song sharing start enabled only after you confirm profile setup.", style = MaterialTheme.typography.bodySmall)
            else TextButton(onClick = { editingProfile = false }) { Text("Cancel") }
        }
        if (profile != null) {
            SocialPersonLine(profile)
            if (!editingProfile) TextButton(enabled = enabled, onClick = { editingProfile = true }) { Text("Edit profile") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Share when online", Modifier.weight(1f))
                Switch(profile.optBoolean("onlineVisible", true), enabled = enabled, onCheckedChange = { vm.setSocialVisibility(it, profile.optBoolean("currentSongVisible", true)) }, modifier = Modifier.semantics { contentDescription = "Share when online" })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Share current song", Modifier.weight(1f))
                Switch(profile.optBoolean("currentSongVisible", true), enabled = enabled, onCheckedChange = { vm.setSocialVisibility(profile.optBoolean("onlineVisible", true), it) }, modifier = Modifier.semantics { contentDescription = "Share current song" })
            }
            Text("Look up a friend", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(lookup, { lookup = it.take(24) }, label = { Text("Exact username") }, singleLine = true, enabled = vm.socialConfigured, modifier = Modifier.weight(1f))
                Button(enabled = enabled && lookup.length in 3..24, onClick = { vm.lookupSocial(lookup) }) { Text("Find") }
            }
            vm.socialLookup?.let { person ->
                SocialPersonLine(person)
                val relationshipVersion = person.socialVersion("relationshipVersion")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = enabled && relationshipVersion != null, onClick = { vm.sendSocialRequest(person.optString("userId"), requireNotNull(relationshipVersion)) }) { Text("Send request") }
                    TextButton(enabled = enabled && relationshipVersion != null, onClick = { vm.blockSocialUser(person.optString("userId"), requireNotNull(relationshipVersion)) }) { Text("Block") }
                }
            }
            Text("Incoming requests", style = MaterialTheme.typography.titleMedium)
            SocialRows(vm.socialIncoming, emptyText = "No incoming requests") { item ->
                SocialPersonLine(item)
                val relationshipVersion = item.socialVersion("version")
                Row {
                    TextButton(enabled = enabled && relationshipVersion != null, onClick = { vm.respondSocialRequest(item.optString("userId"), "accept", requireNotNull(relationshipVersion)) }) { Text("Accept") }
                    TextButton(enabled = enabled && relationshipVersion != null, onClick = { vm.respondSocialRequest(item.optString("userId"), "decline", requireNotNull(relationshipVersion)) }) { Text("Decline") }
                }
            }
            Text("Outgoing requests", style = MaterialTheme.typography.titleMedium)
            SocialRows(vm.socialOutgoing, emptyText = "No outgoing requests") { item ->
                SocialPersonLine(item)
                val relationshipVersion = item.socialVersion("version")
                TextButton(enabled = enabled && relationshipVersion != null, onClick = { vm.respondSocialRequest(item.optString("userId"), "cancel", requireNotNull(relationshipVersion)) }) { Text("Cancel request") }
            }
            Text("Friends", style = MaterialTheme.typography.titleMedium)
            SocialRows(vm.socialFriends, emptyText = "No friends yet") { item ->
                SocialPersonLine(item)
                val relationshipVersion = item.socialVersion("relationshipVersion")
                Row {
                    TextButton(enabled = enabled && relationshipVersion != null, onClick = { vm.removeSocialFriend(item.optString("userId"), requireNotNull(relationshipVersion)) }) { Text("Remove") }
                    TextButton(enabled = enabled && relationshipVersion != null, onClick = { vm.blockSocialUser(item.optString("userId"), requireNotNull(relationshipVersion)) }) { Text("Block") }
                }
            }
            Text("Blocked users", style = MaterialTheme.typography.titleMedium)
            SocialRows(vm.socialBlocks, emptyText = "No blocked users") { item ->
                val relationshipVersion = item.socialVersion("relationshipVersion")
                Text(item.optString("username").takeIf { it.isNotBlank() }?.let { "@${it}" }
                    ?: "Unavailable account · ${item.optString("userId").takeLast(6)}")
                TextButton(enabled = enabled && relationshipVersion != null, onClick = { vm.unblockSocialUser(item.optString("userId"), requireNotNull(relationshipVersion)) }) { Text("Unblock") }
            }
            Text("Invite a friend", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(enabled = enabled && vm.socialInvitesConfigured, onClick = vm::createSocialInvite) { Text("Create 24-hour invite") }
            if (vm.socialConfigured && !vm.socialInvitesConfigured) Text("Invite links are unavailable until an approved HTTPS invite host is configured.", style = MaterialTheme.typography.bodySmall)
            vm.socialInvite?.let { secret ->
                SelectionContainer { Text(secret) }
                TextButton(onClick = { clipboard.setText(AnnotatedString(secret)) }) { Text("Copy invite link") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(invite, { invite = it.take(2048) }, label = { Text("Paste invite link") }, singleLine = true, enabled = vm.socialInvitesConfigured, modifier = Modifier.weight(1f))
                Button(enabled = enabled && vm.socialInvitesConfigured && invite.isNotBlank(), onClick = { vm.redeemSocialInvite(invite) }) { Text("Redeem") }
            }
            TextButton(enabled = enabled, onClick = { confirmDelete = true }) { Text("Delete social profile and data") }
            Text("This deletes social relationships and profile data only. It does not delete your Google/Echo account or local library.", style = MaterialTheme.typography.bodySmall)
        }
        vm.socialStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (vm.accountBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete social profile?") },
        text = { Text("This removes your social profile, friend relationships and invites. Other people's blocks may still apply if you set up again. Opaque safety records may remain, and backups may take up to 7 days to expire. Your Google/Echo account and local library stay intact.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteSocialProfile() }) { Text("Delete social data") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable private fun SocialPersonLine(person: org.json.JSONObject) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val avatar = person.optString("avatar").removePrefix("avatar_").takeIf { it in (1..8).map(Int::toString) }
        if (avatar != null) androidx.compose.material3.Surface(Modifier.size(40.dp).clip(CircleShape), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Text("$avatar", style = MaterialTheme.typography.titleMedium) }
        }
        Text("${person.optString("displayName")} · @${person.optString("username")}", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable private fun SocialRows(items: org.json.JSONArray?, emptyText: String, row: @Composable (org.json.JSONObject) -> Unit) {
    if (items == null || items.length() == 0) Text(emptyText, style = MaterialTheme.typography.bodySmall)
    else for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) { row(item) }
    }
}
