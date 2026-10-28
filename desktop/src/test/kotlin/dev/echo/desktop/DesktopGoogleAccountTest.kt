package dev.echo.desktop

import com.sun.jna.platform.win32.Crypt32Util
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopGoogleAccountTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pkceMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            desktopGooglePkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertTrue(runCatching { desktopGooglePkceChallenge("short") }.isFailure)
    }

    @Test fun callbackRequiresStateAndUnambiguousSingleResult() {
        assertEquals("returned-code", desktopGoogleCallback("code=returned-code&state=expected", "expected").code)
        assertTrue(desktopGoogleCallback("error=access_denied&state=expected", "expected").denied)
        listOf(null, "code=a", "code=a&state=wrong", "code=a&state=expected&state=expected",
            "code=a&error=access_denied&state=expected", "state=expected", "code=&state=expected", "code=%zz&state=expected")
            .forEach { assertTrue("Invalid callback accepted", runCatching { desktopGoogleCallback(it, "expected") }.isFailure) }
    }

    @Test fun clientJsonMustBeInstalledClientWithMatchingIdentityAndTrustedEndpoints() {
        assertEquals("synthetic-client-configuration", parseDesktopGoogleClientJson(config().toString()).secret)
        listOf(
            JSONObject().put("web", config().getJSONObject("installed")),
            config().apply { getJSONObject("installed").put("client_id", "another.apps.googleusercontent.com") },
            config().apply { getJSONObject("installed").put("token_uri", "https://untrusted.example/token") },
            config().apply { getJSONObject("installed").put("client_secret", "") },
        ).forEach { assertTrue(runCatching { parseDesktopGoogleClientJson(it.toString()) }.isFailure) }
    }

    @Test fun firebaseProjectDesktopClientIsAcceptedButForeignProjectsAndWebClientsAreRejected() {
        val parsed = parseDesktopGoogleClientJson(firebaseConfig().toString())
        assertEquals(FIREBASE_CLIENT, parsed.clientId)
        assertEquals("echo-85da4", parsed.projectId)
        assertFalse(parsed.toString().contains(parsed.secret))
        listOf(
            firebaseConfig().apply { getJSONObject("installed").put("project_id", "foreign-project") },
            firebaseConfig().apply { getJSONObject("installed").remove("project_id") },
            firebaseConfig().apply { getJSONObject("installed").put("client_id", "123456789-other.apps.googleusercontent.com") },
            JSONObject().put("web", firebaseConfig().getJSONObject("installed")),
            firebaseConfig().apply { getJSONObject("installed").put("auth_uri", "https://foreign.example/auth") },
        ).forEach { assertTrue(runCatching { parseDesktopGoogleClientJson(it.toString()) }.isFailure) }
    }

    @Test fun changingOAuthClientClearsTokensAndUsesNewClientAfterProtectedReloadAndSignOut() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val path = temporary.root.toPath().resolve("migration.dpapi")
        val legacy = JSONObject().put("version", 1).put("client_id", ECHO_DESKTOP_GOOGLE_CLIENT_ID)
            .put("client_secret", "old-synthetic-secret").put("email", "user@example.com").put("subject", "subject-1")
            .put("identity", JSONObject().put("access_token", "old-identity"))
            .put("drive", JSONObject().put("access_token", "old-drive"))
            .put("firebase", JSONObject().put("idToken", "old-firebase"))
        Files.write(path, Crypt32Util.cryptProtectData(legacy.toString().toByteArray()))
        val old = DesktopGoogleAccount(path)
        assertEquals("user@example.com", old.state.value.email)
        assertTrue(old.state.value.driveConnected)
        val imported = temporary.newFile("new-project.json").apply { writeText(firebaseConfig().toString()) }
        old.importClientJson(imported)
        assertNull(old.state.value.email)
        assertFalse(old.state.value.driveConnected)
        assertTrue(old.state.value.message!!.contains("Sign in again"))
        fun saved() = JSONObject(String(Crypt32Util.cryptUnprotectData(Files.readAllBytes(path))))
        assertEquals(FIREBASE_CLIENT, saved().getString("client_id"))
        listOf("identity", "drive", "firebase", "email", "subject").forEach { assertFalse(saved().has(it)) }
        var browserClient: String? = null
        val reloaded = DesktopGoogleAccount(path) { uri ->
            val url = uri.toString().toHttpUrl()
            browserClient = url.queryParameter("client_id")
            val redirect = url.queryParameter("redirect_uri")!!
            val connection = URI("$redirect?error=access_denied&state=${url.queryParameter("state")}").toURL().openConnection() as HttpURLConnection
            try { assertEquals(200, connection.responseCode) } finally { connection.disconnect() }
        }
        assertTrue(reloaded.state.value.clientConfigured)
        withTimeout(5000) { reloaded.signIn() }
        assertEquals(FIREBASE_CLIENT, browserClient)
        reloaded.signOut()
        assertEquals(FIREBASE_CLIENT, saved().getString("client_id"))
        assertEquals("echo-85da4", saved().getString("project_id"))
        assertTrue(DesktopGoogleAccount(path).state.value.clientConfigured)
    }

    @Test fun driveGrantCannotChangeSignedInAccount() {
        requireDesktopGoogleAccountMatch("user@example.com", "subject-1", "user@example.com", "subject-1")
        assertTrue(runCatching { requireDesktopGoogleAccountMatch("user@example.com", "subject-1", "user@example.com", "subject-2") }.isFailure)
        assertTrue(runCatching { requireDesktopGoogleAccountMatch("user@example.com", "subject-1", "other@example.com", "subject-1") }.isFailure)
        assertTrue(runCatching { requireValidDesktopGoogleIdentity("unverified", "subject-1") }.isFailure)
    }

    @Test fun tokensRequireGrantedScopeAndBoundedLifetimeAndKeepRefreshIdentity() {
        val identity = token(DESKTOP_GOOGLE_EMAIL_SCOPE).put("refresh_token", "synthetic-refresh")
        val saved = desktopGoogleTokens(identity, setOf(DESKTOP_GOOGLE_EMAIL_SCOPE))
        assertEquals("synthetic-refresh", saved.getString("refresh_token"))
        val refreshed = desktopGoogleTokens(token(null), setOf(DESKTOP_GOOGLE_EMAIL_SCOPE), saved)
        assertEquals("synthetic-refresh", refreshed.getString("refresh_token"))
        assertTrue(runCatching { desktopGoogleTokens(identity, setOf(DESKTOP_GOOGLE_DRIVE_SCOPE)) }.isFailure)
        assertTrue(runCatching { desktopGoogleTokens(token(DESKTOP_GOOGLE_EMAIL_SCOPE).put("expires_in", -1), setOf(DESKTOP_GOOGLE_EMAIL_SCOPE)) }.isFailure)
        assertTrue(runCatching { desktopGoogleTokens(token(DESKTOP_GOOGLE_EMAIL_SCOPE).put("token_type", "Other"), setOf(DESKTOP_GOOGLE_EMAIL_SCOPE)) }.isFailure)
    }

    @Test fun realLoopbackRejectsWrongStateAndHandlesDeniedConsentWithoutNetworkExchange() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val path = temporary.root.toPath().resolve("account.dpapi")
        var browserOpened = false
        val account = DesktopGoogleAccount(path) { authorization ->
            browserOpened = true
            val url = authorization.toString().toHttpUrl()
            assertEquals("accounts.google.com", url.host)
            assertEquals("S256", url.queryParameter("code_challenge_method"))
            assertEquals(ECHO_DESKTOP_GOOGLE_CLIENT_ID, url.queryParameter("client_id"))
            val redirect = url.queryParameter("redirect_uri")!!
            assertEquals("127.0.0.1", URI(redirect).host)
            fun response(query: String): Int = (URI("$redirect?$query").toURL().openConnection() as HttpURLConnection).let {
                it.connectTimeout = 1000; it.readTimeout = 1000
                try { it.responseCode } finally { it.disconnect() }
            }
            assertEquals(400, response("code=not-real&state=wrong"))
            assertEquals(200, response("error=access_denied&state=${url.queryParameter("state")}"))
        }
        account.importClientJson(temporary.newFile("loopback-client.json").apply { writeText(config().toString()) })
        withTimeout(5000) { account.signIn() }
        assertTrue(browserOpened)
        assertNull(account.state.value.email)
        assertFalse(account.state.value.driveConnected)
        assertFalse(account.state.value.busy)
        assertTrue(account.state.value.message!!.contains("declined"))
        assertTrue(account.state.value.clientConfigured)
        assertNull(DesktopGoogleAccount(path).state.value.email)
    }

    @Test fun missingDesktopConfigurationStopsBeforeOpeningBrowser() = runBlocking {
        val path = temporary.root.toPath().resolve("unconfigured.dpapi")
        val account = DesktopGoogleAccount(path) { error("Browser must not open without client configuration") }
        account.signIn()
        assertFalse(account.state.value.clientConfigured)
        assertFalse(account.state.value.busy)
        assertEquals(DESKTOP_GOOGLE_SETUP_MESSAGE, account.state.value.message)
        assertFalse(Files.exists(path))
    }

    @Test fun googleErrorsExplainMissingConfigurationWithoutExposingResponseText() {
        assertEquals(DESKTOP_GOOGLE_SETUP_MESSAGE, desktopGoogleErrorMessage(400, JSONObject()
            .put("error", "invalid_request").put("error_description", "client_secret is missing.")))
        assertEquals(DESKTOP_GOOGLE_SETUP_MESSAGE, desktopGoogleErrorMessage(401, JSONObject().put("error", "invalid_client")))
        val marker = "sensitive-response-marker"
        listOf("invalid_request", "invalid_grant", "redirect_uri_mismatch", "access_denied", "unrecognized").forEach { code ->
            val message = desktopGoogleErrorMessage(400, JSONObject().put("error", code).put("error_description", marker))
            assertFalse(message.contains(marker))
            if (code == "invalid_grant") assertTrue(message.contains("fresh sign-in"))
        }
        assertTrue(desktopGoogleErrorMessage(429, JSONObject()).contains("rate limiting"))
    }

    @Test fun clientConfigUsesWindowsProtectionAndCorruptSavedCredentialsArePreserved() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val path = temporary.root.toPath().resolve("account.dpapi")
        val client = temporary.newFile("oauth.json").apply { writeText(config().toString()) }
        val account = DesktopGoogleAccount(path)
        account.importClientJson(client)
        assertTrue(account.state.value.message!!.contains("Windows protection"))
        assertTrue(account.state.value.clientConfigured)
        assertFalse(String(Files.readAllBytes(path), Charsets.ISO_8859_1).contains("synthetic-client-configuration"))
        assertNull(DesktopGoogleAccount(path).state.value.email)
        account.signOut()
        assertNull(account.state.value.email)
        Files.write(path, byteArrayOf(1, 2, 3))
        val failed = DesktopGoogleAccount(path)
        assertTrue(failed.state.value.message!!.contains("preserved"))
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(path))
    }

    private fun config() = JSONObject().put("installed", JSONObject()
        .put("client_id", ECHO_DESKTOP_GOOGLE_CLIENT_ID).put("client_secret", "synthetic-client-configuration")
        .put("auth_uri", "https://accounts.google.com/o/oauth2/auth").put("token_uri", "https://oauth2.googleapis.com/token"))
    private fun firebaseConfig() = config().apply { getJSONObject("installed")
        .put("client_id", FIREBASE_CLIENT).put("project_id", "echo-85da4") }
    private fun token(scope: String?) = JSONObject().put("access_token", "synthetic-access")
        .put("token_type", "Bearer").put("expires_in", 3600).apply { scope?.let { put("scope", it) } }

    companion object { private const val FIREBASE_CLIENT = "886330954520-syntheticdesktopclient.apps.googleusercontent.com" }
}
