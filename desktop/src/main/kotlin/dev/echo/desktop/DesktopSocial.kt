package dev.echo.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.echo.data.firebase.EchoFirebaseProject
import dev.echo.data.firebase.FirebaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

internal data class DesktopSocialUser(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatar: String?,
    val version: String? = null,
)

internal class DesktopSocialHttpException(val status: Int, message: String) : IllegalStateException(message)

/** The production origin stays empty until the owner approves a fixed HTTPS endpoint. */
internal class DesktopSocialClient(
    endpoint: String = PINNED_ENDPOINT,
    inviteBase: String = PINNED_INVITE_BASE,
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build(),
) {
    private val base = endpoint.takeIf(String::isNotBlank)?.let(::validatedOrigin)
    private val inviteUri = inviteBase.takeIf(String::isNotBlank)?.let(::validatedInviteBase)
    private val inviteOrigin = inviteUri?.let { "https://${it.rawAuthority}" }
    val enabled: Boolean get() = base != null
    val inviteEnabled: Boolean get() = inviteOrigin != null

    fun validateInviteLink(value: String): String {
        val allowed = inviteOrigin ?: error("Invite links are unavailable until an approved HTTPS link host is configured")
        val uri = URI(value)
        require(uri.scheme == "https" && uri.rawAuthority == URI(allowed).rawAuthority && uri.userInfo == null && uri.query == null &&
            uri.path == inviteUri?.path && uri.fragment?.startsWith("token=") == true) {
            "Invite link host is not approved or the invite link is invalid"
        }
        val token = java.net.URLDecoder.decode(uri.rawFragment.substringAfter("token="), Charsets.UTF_8.name())
        require(token.matches(Regex("[A-Za-z0-9_-]{40,64}"))) { "Invite link token is invalid" }
        return token
    }

    private fun validatedOrigin(value: String): String {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/")) { "Social endpoint must be a pinned HTTPS origin" }
        return "https://${uri.rawAuthority}"
    }

    private fun validatedInviteBase(value: String): URI {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Invite link host must be an approved HTTPS URL"
        }
        return uri
    }

    suspend fun request(session: FirebaseSession, method: String, path: String, payload: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val host = base ?: error("Social is unavailable: no approved service endpoint is configured")
        require(path.startsWith("/v1/") && !path.contains(".."))
        val body = payload?.toString()?.toRequestBody(JSON)
        val builder = Request.Builder().url(host + path).header("Authorization", "Bearer ${session.idToken}").header("Accept", "application/json")
        val request = when (method) {
            "GET" -> builder.get().build()
            "POST" -> builder.post(body ?: "{}".toRequestBody(JSON)).build()
            "PUT" -> builder.put(body ?: "{}".toRequestBody(JSON)).build()
            "DELETE" -> if (body == null) builder.delete().build() else builder.delete(body).build()
            else -> error("Unsupported social request")
        }
        val call = http.newCall(request)
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try { call.execute().use { response ->
            val bodySource = response.body?.source()
            bodySource?.request(MAX_RESPONSE + 1)
            require((bodySource?.buffer?.size ?: 0L) <= MAX_RESPONSE) { "Social service returned an oversized response" }
            val text = bodySource?.buffer?.readUtf8().orEmpty()
            if (!response.isSuccessful) {
                val errorMessage = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                throw DesktopSocialHttpException(response.code, errorMessage?.takeIf(String::isNotBlank) ?: "Social request failed (HTTP ${response.code})")
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } } finally { cancellation.dispose() }
    }

    companion object {
        private const val PINNED_ENDPOINT = ""
        private const val PINNED_INVITE_BASE = ""
        private const val MAX_RESPONSE = 256 * 1024L
        private val JSON = "application/json; charset=utf-8".toMediaType()
        fun users(json: JSONArray, versionField: String? = null): List<DesktopSocialUser> = (0 until json.length()).map { i ->
            val u = json.getJSONObject(i)
            DesktopSocialUser(u.getString("userId"), u.optString("username"), u.optString("displayName"),
                u.optString("avatar").takeIf(String::isNotBlank), versionField?.let { field ->
                    u.opt(field)?.takeIf { it is String }?.toString()?.takeIf { it.isNotBlank() && it.length <= 256 }
                })
        }
    }
}

@Composable
internal fun DesktopSocialPanel(
    signedIn: Boolean,
    accountId: String?,
    stagingMode: Boolean,
    offlineOnly: Boolean,
    google: DesktopGoogleAccount,
    client: DesktopSocialClient = remember { DesktopSocialClient() },
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var friends by remember { mutableStateOf(emptyList<DesktopSocialUser>()) }
    var incoming by remember { mutableStateOf(emptyList<DesktopSocialUser>()) }
    var outgoing by remember { mutableStateOf(emptyList<DesktopSocialUser>()) }
    var blocks by remember { mutableStateOf(emptyList<DesktopSocialUser>()) }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf("avatar_1") }
    var lookup by remember { mutableStateOf<DesktopSocialUser?>(null) }
    var invite by remember { mutableStateOf("") }
    var redeem by remember { mutableStateOf("") }
    var online by remember { mutableStateOf(true) }
    var currentSong by remember { mutableStateOf(true) }
    var searchUsername by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    var requestGeneration by remember { mutableStateOf(0) }
    var setupEpoch by remember { mutableStateOf<Int?>(null) }
    val latestOperationJob = rememberUpdatedState(operationJob)
    val canUseSocial by rememberUpdatedState(signedIn && !stagingMode && client.enabled && !offlineOnly)
    val currentAccountId by rememberUpdatedState(accountId)

    DisposableEffect(signedIn, accountId, stagingMode, client.enabled, offlineOnly) {
        onDispose { latestOperationJob.value?.cancel() }
    }

    fun checkActive(expectedAccountId: String) = check(canUseSocial && currentAccountId == expectedAccountId) {
        "Social is unavailable while Offline mode is on or the Google account has changed."
    }

    suspend fun reload(session: FirebaseSession, expectedAccountId: String = currentAccountId.orEmpty()) {
        checkActive(expectedAccountId)
        val me = client.request(session, "GET", "/v1/me")
        checkActive(expectedAccountId)
        setupEpoch = me.optInt("setupEpoch").takeIf { me.has("setupEpoch") }
        profile = me.takeIf { it.optBoolean("setupComplete", true) }
        if (profile == null) {
            username = ""; displayName = google.state.value.email?.substringBefore('@').orEmpty(); avatar = "avatar_1"
            loaded = true
            return
        }
        profile?.let {
            username = it.optString("username"); displayName = it.optString("displayName")
            avatar = it.optString("avatar", "avatar_1"); online = it.optBoolean("onlineVisible", true)
            currentSong = it.optBoolean("currentSongVisible", true)
        }
        checkActive(expectedAccountId)
        friends = DesktopSocialClient.users(client.request(session, "GET", "/v1/friends").optJSONArray("items") ?: JSONArray(), "relationshipVersion")
        checkActive(expectedAccountId)
        incoming = DesktopSocialClient.users(client.request(session, "GET", "/v1/requests?direction=incoming").optJSONArray("items") ?: JSONArray(), "version")
        checkActive(expectedAccountId)
        outgoing = DesktopSocialClient.users(client.request(session, "GET", "/v1/requests?direction=outgoing").optJSONArray("items") ?: JSONArray(), "version")
        checkActive(expectedAccountId)
        blocks = DesktopSocialClient.users(client.request(session, "GET", "/v1/blocks").optJSONArray("items") ?: JSONArray(), "relationshipVersion")
        checkActive(expectedAccountId)
        loaded = true
    }

    fun action(block: suspend (FirebaseSession) -> Unit): () -> Unit = {
        if (!loading && canUseSocial) {
            val expectedAccountId = currentAccountId.orEmpty()
            val generation = ++requestGeneration
            operationJob = scope.launch {
                loading = true; message = null
                var actionSession: FirebaseSession? = null
                try {
                    val session = withContext(Dispatchers.IO) { google.firebaseSession(EchoFirebaseProject.configuration()) }
                    actionSession = session
                    checkActive(expectedAccountId)
                    block(session)
                    if (requestGeneration == generation && canUseSocial && currentAccountId == expectedAccountId) message = "Done."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) {
                    if (e is DesktopSocialHttpException && e.status == 409 && canUseSocial && currentAccountId == expectedAccountId)
                        actionSession?.let { runCatching { reload(it, expectedAccountId) } }
                    if (requestGeneration == generation && canUseSocial && currentAccountId == expectedAccountId)
                        message = if (e is DesktopSocialHttpException && e.status == 409) "Social data changed; refresh and retry"
                        else e.message ?: "Social request failed. Check your connection and try again."
                }
                finally { if (requestGeneration == generation) loading = false }
            }
        }
    }
    LaunchedEffect(signedIn, accountId, stagingMode, client.enabled, offlineOnly) {
        val generation = ++requestGeneration
        profile = null; friends = emptyList(); incoming = emptyList(); outgoing = emptyList(); blocks = emptyList()
        setupEpoch = null; username = ""; displayName = ""; avatar = "avatar_1"; lookup = null; invite = ""; redeem = ""
        online = true; currentSong = true; searchUsername = ""; loaded = false; confirmDelete = false; message = null
        if (canUseSocial) {
            loading = true
            try {
                val session = withContext(Dispatchers.IO) { google.firebaseSession(EchoFirebaseProject.configuration()) }
                reload(session, accountId.orEmpty())
                if (requestGeneration == generation) message = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (requestGeneration == generation) message = e.message ?: "Could not load social settings." }
            finally { if (requestGeneration == generation) loading = false }
        } else {
            loading = false
        }
    }

    Card(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Friends", style = MaterialTheme.typography.titleLarge)
            when {
                stagingMode -> Text("Social is unavailable in Stage mode.")
                offlineOnly -> Text("Social is unavailable while Offline mode is on. Turn off Offline mode to use friends.")
                !signedIn -> Text("Sign in to Google to set up an optional social profile.")
                !client.enabled -> Text("Social is disabled until an owner-approved HTTPS service is configured. No account data has been sent.")
                else -> {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    message?.let { Text(it, color = if (it == "Done.") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                    if (!loaded) {
                        if (!loading) OutlinedButton(onClick = { action { session -> reload(session) } }) { Text("Retry") }
                    } else if (profile == null) {
                        Text("Create an optional profile. Online and current-song visibility start on and can be controlled separately.")
                    }
                    if (loaded) {
                    OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, enabled = !loading,
                        supportingText = { Text("3–24 letters, numbers or underscores") }, singleLine = true)
                    OutlinedTextField(displayName, { displayName = it }, Modifier.fillMaxWidth(), label = { Text("Display name") }, enabled = !loading, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Avatar")
                        (1..8).forEach { n -> FilterChip(avatar == "avatar_$n", { avatar = "avatar_$n" }, label = { Text("$n") }, enabled = !loading) }
                    }
                    Button(enabled = !loading && username.matches(Regex("[A-Za-z0-9_]{3,24}")) && displayName.trim().isNotEmpty(), onClick = action { session ->
                        val body = JSONObject().put("username", username.lowercase()).put("displayName", displayName.trim()).put("avatar", avatar)
                            .put("onlineVisible", if (profile == null) true else online).put("currentSongVisible", if (profile == null) true else currentSong)
                            .put("setupEpoch", setupEpoch ?: error("Reload your social profile before saving."))
                        profile?.optInt("version")?.let { body.put("version", it) }
                        profile = client.request(session, "PUT", "/v1/me", body); online = profile!!.optBoolean("onlineVisible"); currentSong = profile!!.optBoolean("currentSongVisible")
                        reload(session)
                    }) { Text(if (profile == null) "Create profile" else "Save profile") }
                    if (profile != null) {
                        Row(Modifier.fillMaxWidth().toggleable(online, !loading, Role.Checkbox) { next -> action { session ->
                                val body = JSONObject(profile!!.toString()).put("onlineVisible", next).put("currentSongVisible", currentSong)
                                    .put("setupEpoch", setupEpoch ?: error("Reload your social profile before saving."))
                                profile = client.request(session, "PUT", "/v1/me", body); online = next
                            }() }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(online, null, enabled = !loading)
                            Text("Share online status", modifier = Modifier.padding(top = 12.dp))
                        }
                        Row(Modifier.fillMaxWidth().toggleable(currentSong, !loading, Role.Checkbox) { next -> action { session ->
                                val body = JSONObject(profile!!.toString()).put("onlineVisible", online).put("currentSongVisible", next)
                                    .put("setupEpoch", setupEpoch ?: error("Reload your social profile before saving."))
                                profile = client.request(session, "PUT", "/v1/me", body); currentSong = next
                            }() }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(currentSong, null, enabled = !loading)
                            Text("Share current song", modifier = Modifier.padding(top = 12.dp))
                        }
                        OutlinedTextField(searchUsername, { searchUsername = it }, Modifier.fillMaxWidth(), label = { Text("Find username") }, enabled = !loading, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !loading && searchUsername.isNotBlank(), onClick = action { session ->
                                val result = client.request(session, "GET", "/v1/users/${URLEncoder.encode(searchUsername, Charsets.UTF_8.name()).replace("+", "%20")}")
                                lookup = DesktopSocialClient.users(JSONArray().put(result), "relationshipVersion").single()
                            }) { Text("Look up") }
                            lookup?.let { person ->
                                Text("${person.displayName} (@${person.username})")
                                OutlinedButton(enabled = !loading && person.version != null, onClick = action { session ->
                                    client.request(session, "POST", "/v1/requests", JSONObject().put("userId", person.userId).put("expectedVersion", person.version))
                                    lookup = null; reload(session)
                                }) { Text("Send request") }
                                OutlinedButton(enabled = !loading && person.version != null, onClick = action { session ->
                                    client.request(session, "PUT", "/v1/blocks/${person.userId}", JSONObject().put("expectedVersion", person.version))
                                    lookup = null; reload(session)
                                }) { Text("Block") }
                            }
                        }
                        OutlinedTextField(redeem, { redeem = it }, Modifier.fillMaxWidth(), label = { Text("Invite link") }, enabled = !loading && client.inviteEnabled, singleLine = true)
                        OutlinedButton(enabled = !loading && redeem.isNotBlank() && client.inviteEnabled, onClick = action { session ->
                            val token = client.validateInviteLink(redeem.trim())
                            client.request(session, "POST", "/v1/invites/redeem", JSONObject().put("token", token)); redeem = ""; reload(session)
                        }) { Text("Redeem invite") }
                        OutlinedButton(enabled = !loading && client.inviteEnabled, onClick = action { session ->
                            val url = client.request(session, "POST", "/v1/invites", JSONObject()).getString("url")
                            client.validateInviteLink(url)
                            invite = url
                        }) { Text("Create invite") }
                        if (!client.inviteEnabled) Text("Invite links are unavailable until an owner-approved HTTPS link host is configured.")
                        if (invite.isNotBlank()) {
                            SelectionContainer { Text("Invite link (expires in 24 hours): $invite") }
                        }
                        SocialPeopleSection("Friends", friends) { person ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${person.displayName} (@${person.username})")
                                OutlinedButton(enabled = !loading && person.version != null, onClick = action { session ->
                                    client.request(session, "DELETE", "/v1/friends/${person.userId}", JSONObject().put("expectedVersion", person.version)); reload(session)
                                }) { Text("Remove") }
                                OutlinedButton(enabled = !loading && person.version != null, onClick = action { session ->
                                    client.request(session, "PUT", "/v1/blocks/${person.userId}", JSONObject().put("expectedVersion", person.version)); reload(session)
                                }) { Text("Block") }
                            }
                        }
                        SocialPeopleSection("Incoming requests", incoming) { person ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${person.displayName} (@${person.username})")
                                listOf("accept", "decline").forEach { actionName -> OutlinedButton(enabled = !loading && person.version != null, onClick = action { session ->
                                    client.request(session, "POST", "/v1/requests/${person.userId}/$actionName", JSONObject().put("expectedVersion", person.version)); reload(session)
                                }) { Text(actionName.replaceFirstChar(Char::uppercase)) } }
                            }
                        }
                        SocialPeopleSection("Outgoing requests", outgoing) { person ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${person.displayName} (@${person.username})")
                                OutlinedButton(enabled = !loading && person.version != null, onClick = action { session ->
                                    client.request(session, "POST", "/v1/requests/${person.userId}/cancel", JSONObject().put("expectedVersion", person.version)); reload(session)
                                }) { Text("Cancel") }
                                OutlinedButton(enabled = !loading && person.version != null, onClick = action { session ->
                                    client.request(session, "PUT", "/v1/blocks/${person.userId}", JSONObject().put("expectedVersion", person.version)); reload(session)
                                }) { Text("Block") }
                            }
                        }
                        SocialPeopleSection("Blocked users", blocks) { person ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (person.username.isNotBlank()) "${person.displayName} (@${person.username})" else "Unavailable account · ${person.userId.takeLast(6)}")
                                OutlinedButton(enabled = !loading && person.version != null, onClick = action { session ->
                                    client.request(session, "DELETE", "/v1/blocks/${person.userId}", JSONObject().put("expectedVersion", person.version)); reload(session)
                                }) { Text("Unblock") }
                            }
                        }
                        TextButton(enabled = !loading, onClick = { confirmDelete = true }) { Text("Delete social profile") }
                    }
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete social profile?") },
        text = { Text("This removes your Echo social profile, friends, requests, blocks you created, and invites. Blocks created by other users remain effective if you set up social again. A lifecycle ID is retained to prevent stale actions; D1 recovery copies may retain deleted data for up to 7 days. Your Google account and local music library stay on this laptop.") },
        confirmButton = { TextButton(enabled = canUseSocial, onClick = { confirmDelete = false; action { session ->
            client.request(session, "DELETE", "/v1/me/social"); profile = null; friends = emptyList(); incoming = emptyList(); outgoing = emptyList(); blocks = emptyList(); invite = ""; lookup = null
            reload(session)
        }() }) { Text("Delete profile") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep profile") } })
}

@Composable
private fun SocialPeopleSection(title: String, people: List<DesktopSocialUser>, content: @Composable (DesktopSocialUser) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (people.isEmpty()) Text("None", style = MaterialTheme.typography.bodySmall)
    else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { people.forEach { content(it) } }
}
