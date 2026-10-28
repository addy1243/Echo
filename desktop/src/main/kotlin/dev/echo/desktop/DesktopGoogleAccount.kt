package dev.echo.desktop

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import dev.echo.data.firebase.FirebaseAuthRest
import dev.echo.data.firebase.FirebaseConfig
import dev.echo.data.firebase.FirebaseSession

const val ECHO_DESKTOP_GOOGLE_CLIENT_ID = "234985319029-53q65aqk1sfq8qaj13ijjs8ee95e6i5k.apps.googleusercontent.com"
internal const val ECHO_FIREBASE_GOOGLE_PROJECT_ID = "echo-85da4"
private const val ECHO_FIREBASE_GOOGLE_PROJECT_NUMBER = "886330954520"
internal const val DESKTOP_GOOGLE_EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email"
internal const val DESKTOP_GOOGLE_DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
internal const val DESKTOP_GOOGLE_SETUP_MESSAGE = "Import the downloaded Echo Desktop OAuth JSON using Import Desktop OAuth configuration, then sign in. Keep that file outside the repository."

data class DesktopAccountState(
    val email: String? = null,
    val driveConnected: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val clientConfigured: Boolean = false,
)

/** Browser OAuth only; no library data is uploaded and no real-time service is contacted. */
class DesktopGoogleAccount(
    private val file: Path = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "EchoMusic", "google-account.dpapi"),
    private val browse: (URI) -> Unit = { uri ->
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) { "System browser is unavailable" }
        Desktop.getDesktop().browse(uri)
    },
) {
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private var saved = JSONObject()
    private val mutableState = MutableStateFlow(DesktopAccountState(message = DESKTOP_GOOGLE_SETUP_MESSAGE))
    val state: StateFlow<DesktopAccountState> = mutableState

    init {
        if (Files.exists(file)) {
            try {
                require(Files.size(file) <= 1024 * 1024)
                val decoded = JSONObject(String(Crypt32Util.cryptUnprotectData(Files.readAllBytes(file)), Charsets.UTF_8))
                require(decoded.getInt("version") == 1)
                requireDesktopGoogleClient(decoded.getString("client_id"), decoded.optString("project_id"))
                if (decoded.has("email")) requireValidDesktopGoogleIdentity(decoded.getString("email"), decoded.getString("subject"))
                saved = decoded
                mutableState.value = currentState(if (decoded.has("email"))
                    "Saved Google account loaded. Live authorization has not been checked yet."
                    else if (decoded.optString("client_secret").isNotBlank()) "Desktop OAuth configuration loaded. You can now sign in."
                    else DESKTOP_GOOGLE_SETUP_MESSAGE)
            } catch (_: Exception) {
                mutableState.value = DesktopAccountState(message = "Saved Google credentials could not be read. The original encrypted file was preserved; sign in again or sign out to reset Echo's association.")
            }
        }
    }

    suspend fun signIn() = operation {
        val tokens = authorize(setOf(DESKTOP_GOOGLE_EMAIL_SCOPE), null)
        val profile = profile(tokens.getString("access_token"))
        val next = copySaved().apply {
            put("email", profile.first); put("subject", profile.second); put("identity", tokens)
            remove("drive")
            remove("firebase")
        }
        commit(next)
        mutableState.value = currentState("Signed in. Firebase library sync requires a separate confirmation.")
    }

    suspend fun connectDrive() = operation {
        val email = saved.optString("email").takeIf { it.isNotBlank() } ?: error("Sign in first")
        val subject = saved.getString("subject")
        val tokens = authorize(setOf(DESKTOP_GOOGLE_EMAIL_SCOPE, DESKTOP_GOOGLE_DRIVE_SCOPE), email)
        val profile = profile(tokens.getString("access_token"))
        requireDesktopGoogleAccountMatch(email, subject, profile.first, profile.second)
        commit(copySaved().put("drive", tokens))
        mutableState.value = currentState("Drive permission connected. Playlist, favorite and history synchronization is not implemented yet.")
    }

    suspend fun importClientJson(file: File) = operation {
        require(file.isFile && file.length() <= 64 * 1024) { "Choose Google's downloaded Desktop OAuth JSON (maximum 64 KiB)" }
        val configuration = parseDesktopGoogleClientJson(withContext(Dispatchers.IO) {
            val bytes = file.inputStream().use { it.readNBytes(64 * 1024 + 1) }
            require(bytes.size <= 64 * 1024) { "Desktop OAuth configuration is too large" }
            bytes.toString(Charsets.UTF_8)
        })
        val changedClient = configuration.clientId != clientId()
        val next = if (changedClient) JSONObject() else copySaved()
        commit(next.put("client_id", configuration.clientId).put("project_id", configuration.projectId)
            .put("client_secret", configuration.secret))
        mutableState.value = currentState("Desktop OAuth configuration saved with Windows protection. " +
            if (changedClient) "The OAuth client changed. Sign in again to connect this project." else "You can now sign in.")
    }

    suspend fun signOut() = operation {
        val next = JSONObject()
        next.put("client_id", clientId())
        saved.optString("project_id").takeIf { it.isNotBlank() }?.let { next.put("project_id", it) }
        saved.optString("client_secret").takeIf { it.isNotBlank() }?.let { next.put("client_secret", it) }
        commit(next)
        mutableState.value = currentState("Signed out on this laptop. Local music was kept; Google consent was not revoked.")
    }

    suspend fun firebaseSession(config: FirebaseConfig): FirebaseSession = mutex.withLock {
        val email = saved.optString("email").takeIf { it.isNotBlank() } ?: error("Sign in with Google first")
        val auth = FirebaseAuthRest(config)
        val stored = saved.optJSONObject("firebase")?.takeIf { it.optString("project") == config.projectId && it.optString("email") == email }
        val previous = stored?.let { FirebaseSession(it.getString("uid"), it.getString("email"), it.getString("idToken"),
            it.getString("refreshToken"), it.getLong("expiresAt")) }
        val session = if (previous != null) {
            if (previous.expiresAt > System.currentTimeMillis() + 60_000) previous else auth.refresh(previous)
        } else {
            var identity = saved.optJSONObject("identity") ?: error("Sign in with Google again")
            if (identity.getLong("expires_at") <= System.currentTimeMillis() + 60_000) {
                val refresh = identity.optString("refresh_token").takeIf { it.isNotBlank() } ?: error("Google access expired. Sign in again.")
                identity = desktopGoogleTokens(request(Request.Builder().url(TOKEN_URL).post(baseForm()
                    .add("grant_type", "refresh_token").add("refresh_token", refresh).build()).build()),
                    setOf(DESKTOP_GOOGLE_EMAIL_SCOPE), identity)
            }
            val profile = profile(identity.getString("access_token"))
            requireDesktopGoogleAccountMatch(email, saved.getString("subject"), profile.first, profile.second)
            commit(copySaved().put("identity", identity))
            auth.signInGoogle(identity.getString("access_token"), email)
        }
        check(session.email.equals(email, ignoreCase = true)) { "Firebase account changed. Sign in again." }
        if (session !== previous) commit(copySaved().put("firebase", JSONObject().put("project", config.projectId).put("uid", session.uid)
            .put("email", session.email).put("idToken", session.idToken).put("refreshToken", session.refreshToken)
            .put("expiresAt", session.expiresAt)))
        session
    }

    /** For a future sync client: refresh and re-verify account binding before returning a token. */
    suspend fun driveAccessToken(): String = mutex.withLock {
        val email = saved.optString("email").takeIf { it.isNotBlank() } ?: error("Sign in first")
        var token = saved.optJSONObject("drive") ?: error("Connect Drive first")
        if (token.getLong("expires_at") <= System.currentTimeMillis() + 60_000) {
            val refresh = token.optString("refresh_token").takeIf { it.isNotBlank() }
                ?: error("Drive access expired. Connect Drive again.")
            val body = baseForm().add("grant_type", "refresh_token").add("refresh_token", refresh).build()
            val response = request(Request.Builder().url(TOKEN_URL).post(body).build())
            token = desktopGoogleTokens(response, setOf(DESKTOP_GOOGLE_EMAIL_SCOPE, DESKTOP_GOOGLE_DRIVE_SCOPE), token)
        }
        val profile = profile(token.getString("access_token"))
        requireDesktopGoogleAccountMatch(email, saved.getString("subject"), profile.first, profile.second)
        commit(copySaved().put("drive", token))
        token.getString("access_token")
    }

    private suspend fun operation(action: suspend () -> Unit) = mutex.withLock {
        mutableState.value = state.value.copy(busy = true, message = "Waiting for Google authorization…")
        try { action() }
        catch (cancelled: CancellationException) {
            mutableState.value = currentState("Google authorization cancelled or timed out.")
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = currentState(if (error is GoogleAccountException || error is IllegalArgumentException || error is IllegalStateException)
                error.message ?: "Google authorization could not complete. Try again."
            else "Google authorization could not complete. Check your connection and Desktop OAuth configuration.")
        } finally { mutableState.value = state.value.copy(busy = false) }
    }

    private suspend fun authorize(scopes: Set<String>, loginHint: String?): JSONObject {
        requireDesktopGoogleConfiguration(saved.optString("client_secret"))
        val verifier = googleRandomToken()
        val nonce = googleRandomToken()
        val path = "/echo-oauth-${googleRandomToken()}"
        val deferred = CompletableDeferred<String>()
        val consumed = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "Echo Google callback").apply { isDaemon = true } }
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4)
        val authority = "127.0.0.1:${server.address.port}"
        val redirect = "http://$authority$path"
        server.executor = executor
        server.createContext(path) { exchange ->
            try {
                if (exchange.requestMethod != "GET" || exchange.requestURI.rawPath != path ||
                    exchange.requestHeaders.getFirst("Host") != authority || !exchange.remoteAddress.address.isLoopbackAddress) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    val callback = runCatching { desktopGoogleCallback(exchange.requestURI.rawQuery, nonce) }.getOrNull()
                    if (callback == null || !consumed.compareAndSet(false, true)) exchange.sendResponseHeaders(400, -1)
                    else {
                        val text = "Echo received your response. You can close this tab and return to Echo."
                        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
                        exchange.responseHeaders.set("Cache-Control", "no-store")
                        exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
                        exchange.sendResponseHeaders(200, text.toByteArray().size.toLong())
                        exchange.responseBody.use { it.write(text.toByteArray()) }
                        if (callback.denied) deferred.completeExceptionally(GoogleAccountException("Google authorization was declined. No account data was changed."))
                        else deferred.complete(callback.code!!)
                    }
                }
            } catch (_: Exception) { runCatching { exchange.sendResponseHeaders(400, -1) } }
            finally { exchange.close() }
        }
        try {
            server.start()
            val url = AUTH_URL.toHttpUrl().newBuilder().addQueryParameter("client_id", clientId())
                .addQueryParameter("redirect_uri", redirect).addQueryParameter("response_type", "code")
                .addQueryParameter("scope", scopes.sorted().joinToString(" ")).addQueryParameter("state", nonce)
                .addQueryParameter("code_challenge", desktopGooglePkceChallenge(verifier)).addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("access_type", "offline").addQueryParameter("prompt", "consent select_account")
                .apply { loginHint?.let { addQueryParameter("login_hint", it) } }.build()
            withContext(Dispatchers.IO) { browse(url.toUri()) }
            val code = withTimeout(180_000) { deferred.await() }
            val response = request(Request.Builder().url(TOKEN_URL).post(baseForm()
                .add("grant_type", "authorization_code").add("code", code).add("redirect_uri", redirect)
                .add("code_verifier", verifier).build()).build())
            return desktopGoogleTokens(response, scopes)
        } finally { server.stop(0); executor.shutdownNow(); deferred.cancel() }
    }

    private fun baseForm(): FormBody.Builder {
        val secret = saved.optString("client_secret")
        requireDesktopGoogleConfiguration(secret)
        return FormBody.Builder().add("client_id", clientId()).add("client_secret", secret)
    }

    private suspend fun profile(token: String): Pair<String, String> {
        val json = request(Request.Builder().url("https://www.googleapis.com/oauth2/v2/userinfo").header("Authorization", "Bearer $token").build())
        val email = json.getString("email")
        val subject = json.getString("id")
        require(json.optBoolean("verified_email")) { "Google returned an unverified account" }
        requireValidDesktopGoogleIdentity(email, subject)
        return email to subject
    }

    private suspend fun request(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(GoogleAccountException("Google could not be reached. Check your connection and try again."))
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                try {
                    val json = response.use {
                        val source = it.body?.source() ?: throw GoogleAccountException("Google returned no authorization response")
                        source.request(65_537)
                        require(source.buffer.size <= 65_536) { "Google authorization response is too large" }
                        val body = JSONObject(source.buffer.readUtf8())
                        if (!it.isSuccessful) throw GoogleAccountException(desktopGoogleErrorMessage(it.code, body))
                        body
                    }
                    if (continuation.isActive) continuation.resume(json)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }

    private fun copySaved() = JSONObject(saved.toString())
    private fun clientId() = saved.optString("client_id").takeIf { it.isNotBlank() } ?: ECHO_DESKTOP_GOOGLE_CLIENT_ID
    private fun currentState(message: String?) = DesktopAccountState(saved.optString("email").takeIf { it.isNotBlank() },
        saved.has("drive"), message = message, clientConfigured = saved.optString("client_secret").isNotBlank())
    private suspend fun commit(next: JSONObject) = withContext(Dispatchers.IO) {
        require(System.getProperty("os.name").startsWith("Windows")) { "Protected Google sign-in currently requires Windows" }
        if (!next.has("client_id")) next.put("client_id", clientId())
        requireDesktopGoogleClient(next.getString("client_id"), next.optString("project_id"))
        next.put("version", 1)
        val encrypted = Crypt32Util.cryptProtectData(next.toString().toByteArray(Charsets.UTF_8))
        Files.createDirectories(file.toAbsolutePath().parent)
        val temporary = Files.createTempFile(file.toAbsolutePath().parent, "echo-account-", ".tmp")
        try {
            Files.write(temporary, encrypted)
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            saved = next
        } finally { Files.deleteIfExists(temporary) }
    }
}

private class GoogleAccountException(message: String) : IllegalStateException(message)

internal fun requireDesktopGoogleConfiguration(secret: String?) {
    require(!secret.isNullOrBlank()) { DESKTOP_GOOGLE_SETUP_MESSAGE }
}

// Only recognized error categories become fixed messages; Google's raw response can contain credentials.
internal fun desktopGoogleErrorMessage(status: Int, body: JSONObject): String {
    val error = body.optString("error")
    val description = body.optString("error_description")
    return when {
        error == "invalid_client" || error == "invalid_request" && description.contains("client_secret", true) &&
            description.contains("missing", true) -> DESKTOP_GOOGLE_SETUP_MESSAGE
        error == "invalid_grant" -> "Google's sign-in code expired or was rejected. Start a fresh sign-in or reconnect Drive."
        error == "redirect_uri_mismatch" -> "Google rejected Echo's desktop callback. Import the matching Desktop OAuth configuration and try again."
        status in setOf(401, 403) || error == "access_denied" -> "Google access expired or was denied. Sign in or connect Drive again."
        status == 429 -> "Google is rate limiting requests. Wait before trying again."
        else -> "Google authorization failed (HTTP $status). Check the Desktop client and consent setup."
    }
}
internal data class DesktopGoogleCallback(val code: String?, val denied: Boolean)

internal fun desktopGoogleCallback(query: String?, expectedState: String): DesktopGoogleCallback {
    require(query != null && query.length <= 16_384) { "Invalid Google callback" }
    val fields = query.split('&').map { pair ->
        val pieces = pair.split('=', limit = 2)
        require(pieces.size == 2) { "Invalid Google callback" }
        URLDecoder.decode(pieces[0], Charsets.UTF_8) to URLDecoder.decode(pieces[1], Charsets.UTF_8)
    }
    require(fields.map { it.first }.distinct().size == fields.size) { "Duplicate Google callback fields" }
    val params = fields.toMap()
    require(MessageDigest.isEqual(params["state"].orEmpty().toByteArray(), expectedState.toByteArray())) { "Invalid Google callback state" }
    val code = params["code"]
    val error = params["error"]
    require((code == null) != (error == null)) { "Invalid Google callback result" }
    require(code == null || code.length in 1..4096) { "Invalid Google authorization code" }
    return DesktopGoogleCallback(code, error != null)
}

internal fun desktopGooglePkceChallenge(verifier: String): String {
    require(verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}"))) { "Invalid PKCE verifier" }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

private fun googleRandomToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
    .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

internal fun requireValidDesktopGoogleIdentity(email: String, subject: String) {
    require(email.length in 3..320 && '@' in email && email.none { it.isISOControl() } && subject.length in 1..256 && subject.none { it.isISOControl() }) { "Google returned an invalid account identity" }
}

internal fun requireDesktopGoogleAccountMatch(expectedEmail: String, expectedSubject: String, email: String, subject: String) {
    require(email == expectedEmail && subject == expectedSubject) { "Google account changed. Sign out and choose the account again." }
}

internal class DesktopGoogleClientConfiguration(val clientId: String, val projectId: String, val secret: String) {
    override fun toString() = "DesktopGoogleClientConfiguration(redacted)"
}

private fun requireDesktopGoogleClient(clientId: String, projectId: String) {
    require(clientId == ECHO_DESKTOP_GOOGLE_CLIENT_ID ||
        projectId == ECHO_FIREBASE_GOOGLE_PROJECT_ID &&
        clientId.matches(Regex("$ECHO_FIREBASE_GOOGLE_PROJECT_NUMBER-[A-Za-z0-9_-]{1,128}\\.apps\\.googleusercontent\\.com"))) {
        "Choose an installed Desktop OAuth client JSON from Firebase project echo-85da4"
    }
}

internal fun parseDesktopGoogleClientJson(json: String): DesktopGoogleClientConfiguration {
    require(json.length <= 64 * 1024) { "Desktop OAuth configuration is too large" }
    val installed = JSONObject(json).getJSONObject("installed")
    val clientId = installed.getString("client_id")
    val projectId = installed.optString("project_id")
    requireDesktopGoogleClient(clientId, projectId)
    require(installed.optString("auth_uri", AUTH_URL).let { it == AUTH_URL || it == "https://accounts.google.com/o/oauth2/auth" } &&
        installed.optString("token_uri", TOKEN_URL) == TOKEN_URL) { "Unsupported OAuth endpoints" }
    val secret = installed.getString("client_secret").also { require(it.length in 1..4096 && it.none(Char::isISOControl)) { "Invalid Desktop OAuth configuration" } }
    return DesktopGoogleClientConfiguration(clientId, projectId, secret)
}

internal fun desktopGoogleTokens(json: JSONObject, requiredScopes: Set<String>, previous: JSONObject? = null): JSONObject {
    val access = json.getString("access_token")
    require(access.length in 1..16_384 && access.none(Char::isISOControl) && json.getString("token_type").equals("Bearer", true)) { "Google returned an invalid access token" }
    val scope = if (json.has("scope")) json.getString("scope") else previous?.optString("scope") ?: ""
    val granted = scope.split(' ').filter(String::isNotBlank).map {
        when (it) { "email" -> DESKTOP_GOOGLE_EMAIL_SCOPE; else -> it }
    }.toSet()
    require(granted.containsAll(requiredScopes)) { "Google did not grant all requested permissions. Try again and approve the requested access." }
    val lifetime = json.getLong("expires_in")
    require(lifetime in 1..86_400) { "Google returned an invalid token lifetime" }
    return JSONObject().put("access_token", access).put("scope", scope)
        .put("expires_at", System.currentTimeMillis() + lifetime * 1000).apply {
            (json.optString("refresh_token").takeIf { it.isNotBlank() } ?: previous?.optString("refresh_token")?.takeIf { it.isNotBlank() })?.let {
                require(it.length <= 16_384 && it.none(Char::isISOControl)) { "Google returned an invalid refresh token" }
                put("refresh_token", it)
            }
        }
}
