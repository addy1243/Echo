package dev.echo.data.firebase

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class FirebaseConfig(val projectId: String, val apiKey: String) {
    init {
        require(projectId.matches(Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]"))) { "Invalid Firebase project ID" }
        require(apiKey.matches(Regex("[A-Za-z0-9_-]{20,200}"))) { "Invalid Firebase Web API key" }
    }
}

data class FirebaseSession(val uid: String, val email: String, val idToken: String, val refreshToken: String, val expiresAt: Long) {
    init {
        require(uid.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid Firebase user identity" }
        require(email.length in 3..320 && '@' in email && email.none(Char::isISOControl)) { "Invalid Firebase account" }
        require(validToken(idToken) && validToken(refreshToken) && expiresAt > 0) { "Invalid Firebase session" }
    }
    override fun toString() = "FirebaseSession(redacted)"
}

class FirebaseRequestException(val status: Int, message: String) : java.io.IOException(message)

class FirebaseAuthRest(private val config: FirebaseConfig, client: OkHttpClient = firebaseHttpClient()) {
    private val http = FirebaseHttp(client)

    suspend fun signInGoogle(googleAccessToken: String, expectedEmail: String): FirebaseSession {
        require(validToken(googleAccessToken)) { "Google sign-in is required" }
        val form = FormBody.Builder().add("access_token", googleAccessToken).add("providerId", "google.com").build()
        val encoded = okio.Buffer().also { form.writeTo(it) }.readUtf8()
        val body = JSONObject().put("postBody", encoded).put("requestUri", "http://localhost")
            .put("returnSecureToken", true).put("returnIdpCredential", false)
        val response = http.call("https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=${config.apiKey}",
            body = body.toString().toRequestBody(JSON), maximum = 65_536)
        val json = response.json()
        require(json.optString("providerId") == "google.com" && json.optBoolean("emailVerified") &&
            !json.optBoolean("needConfirmation") && !json.has("mfaPendingCredential") &&
            json.getString("email").equals(expectedEmail, ignoreCase = true)) { "Firebase returned a different or unverified Google account" }
        return FirebaseSession(json.getString("localId"), json.getString("email"), json.getString("idToken"),
            json.getString("refreshToken"), expiresAt(json.getString("expiresIn")))
    }

    suspend fun refresh(session: FirebaseSession): FirebaseSession {
        val body = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", session.refreshToken).build()
        val json = http.call("https://securetoken.googleapis.com/v1/token?key=${config.apiKey}", body = body, maximum = 65_536).json()
        require(json.getString("user_id") == session.uid && json.getString("token_type").equals("Bearer", true)) {
            "Firebase account changed; sign in again"
        }
        return FirebaseSession(session.uid, session.email, json.getString("id_token"), json.getString("refresh_token"),
            expiresAt(json.getString("expires_in")))
    }
}

data class FirebaseCloudDocument(val json: String?, val updateTime: String?) {
    init { require((json == null) == (updateTime == null)) { "Incomplete Firebase document identity" } }
    override fun toString() = "FirebaseCloudDocument(redacted)"
}

class FirebaseLibraryCloud(private val config: FirebaseConfig, client: OkHttpClient = firebaseHttpClient()) {
    private val http = FirebaseHttp(client)
    private fun name(session: FirebaseSession) = "projects/${config.projectId}/databases/(default)/documents/users/${session.uid}/sync/library-v1"

    suspend fun read(session: FirebaseSession): FirebaseCloudDocument {
        val response = http.call("https://firestore.googleapis.com/v1/${name(session)}", session.idToken, accepted = setOf(200, 404))
        if (response.status == 404) {
            val error = response.json().optJSONObject("error")
            require(error?.optString("status") == "NOT_FOUND" && error.optString("message").trimStart().startsWith("Document ", true)) {
                "Firebase database or project was not found; check cloud setup"
            }
            return FirebaseCloudDocument(null, null)
        }
        val document = response.json()
        require(document.getString("name") == name(session)) { "Firebase returned another document" }
        val fields = document.getJSONObject("fields")
        require(fields.getJSONObject("uid").getString("stringValue") == session.uid &&
            fields.getJSONObject("format").getString("stringValue") == "echo-library-state" &&
            fields.getJSONObject("version").getString("integerValue") == "1" &&
            fields.getJSONObject("encoding").getString("stringValue") == "gzip-base64") { "Unsupported Firebase library format" }
        val updateTime = document.getString("updateTime")
        require(validUpdateTime(updateTime)) { "Invalid Firebase document revision" }
        return FirebaseCloudDocument(decodeFirebasePayload(fields.getJSONObject("payload").getString("stringValue")), updateTime)
    }

    /** False means a concurrent cloud edit; callers must refetch and merge rather than overwrite it. */
    suspend fun write(session: FirebaseSession, expected: FirebaseCloudDocument, json: String): Boolean {
        val fields = JSONObject().put("uid", stringValue(session.uid)).put("format", stringValue("echo-library-state"))
            .put("version", JSONObject().put("integerValue", "1")).put("encoding", stringValue("gzip-base64"))
            .put("payload", stringValue(encodeFirebasePayload(json)))
        val precondition = expected.updateTime?.let {
            require(validUpdateTime(it)) { "Invalid Firebase document revision" }
            JSONObject().put("updateTime", it)
        } ?: JSONObject().put("exists", false)
        val write = JSONObject().put("update", JSONObject().put("name", name(session)).put("fields", fields))
            .put("currentDocument", precondition)
        val body = JSONObject().put("writes", JSONArray().put(write)).toString().toRequestBody(JSON)
        val response = http.call("https://firestore.googleapis.com/v1/projects/${config.projectId}/databases/(default)/documents:commit",
            session.idToken, body, accepted = setOf(200, 400, 409, 412))
        if (response.status == 409 || response.status == 412) return false
        val result = response.json()
        if (response.status == 400) {
            if (result.optJSONObject("error")?.optString("status") == "FAILED_PRECONDITION") return false
            throw FirebaseRequestException(400, "Firebase rejected the library document; local data was kept")
        }
        require(result.getJSONArray("writeResults").length() == 1 &&
            validUpdateTime(result.getJSONArray("writeResults").getJSONObject(0).getString("updateTime"))) { "Firebase did not confirm the library write" }
        return true
    }
}

internal const val FIREBASE_MAX_ENCODED_BYTES = 700 * 1024
internal const val FIREBASE_MAX_JSON_BYTES = 8 * 1024 * 1024
private val JSON = "application/json; charset=utf-8".toMediaType()
private fun stringValue(value: String) = JSONObject().put("stringValue", value)
private fun validToken(value: String) = value.length in 1..32_768 && value.none(Char::isISOControl)
private fun validUpdateTime(value: String) = value.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z"))
private fun expiresAt(seconds: String): Long {
    val lifetime = seconds.toLongOrNull()
    require(lifetime != null && lifetime in 1..86_400) { "Invalid Firebase token lifetime" }
    return System.currentTimeMillis() + lifetime * 1000
}

internal fun encodeFirebasePayload(json: String): String {
    val bytes = json.toByteArray(Charsets.UTF_8)
    require(bytes.size <= FIREBASE_MAX_JSON_BYTES) { "Library sync exceeds 8 MiB; local data was kept" }
    JSONObject(json)
    val output = ByteArrayOutputStream()
    GZIPOutputStream(output).use { it.write(bytes) }
    return Base64.getEncoder().encodeToString(output.toByteArray()).also {
        // shortcut: one bounded document, split snapshots into documents if real libraries reach this limit.
        require(it.length <= FIREBASE_MAX_ENCODED_BYTES) { "Library sync exceeds the current cloud document limit; local data was kept" }
    }
}

internal fun decodeFirebasePayload(encoded: String): String {
    require(encoded.length <= FIREBASE_MAX_ENCODED_BYTES) { "Firebase library payload is too large" }
    val bytes = Base64.getDecoder().decode(encoded)
    val json = GZIPInputStream(ByteArrayInputStream(bytes)).use { readFirebaseBytes(it, FIREBASE_MAX_JSON_BYTES) }.toString(Charsets.UTF_8)
    JSONObject(json)
    return json
}

private fun firebaseHttpClient() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

private data class FirebaseResponse(val status: Int, val text: String) {
    fun json() = try { JSONObject(text) } catch (_: Exception) { throw FirebaseRequestException(status, "Firebase returned an invalid response") }
}

private class FirebaseHttp(private val client: OkHttpClient) {
    suspend fun call(url: String, token: String? = null, body: RequestBody? = null,
        accepted: Set<Int> = setOf(200), maximum: Int = 1024 * 1024): FirebaseResponse = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).apply { token?.let { header("Authorization", "Bearer $it") } }
            .method(if (body == null) "GET" else "POST", body).build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(FirebaseRequestException(0, "Firebase could not be reached; local data was kept"))
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val result = response.use {
                        val text = it.body?.byteStream()?.use { input -> readFirebaseBytes(input, maximum).toString(Charsets.UTF_8) }
                            ?: throw FirebaseRequestException(it.code, "Firebase returned no data")
                        if (it.code !in accepted) throw FirebaseRequestException(it.code, firebaseError(it.code, text))
                        FirebaseResponse(it.code, text)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(if (error is FirebaseRequestException) error
                        else FirebaseRequestException(response.code, "Firebase returned invalid or oversized data; local data was kept"))
                }
            }
        })
    }
}

internal fun readFirebaseBytes(input: InputStream, maximum: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= maximum) { "Firebase response exceeds its size limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun firebaseError(status: Int, text: String): String {
    val code = runCatching { JSONObject(text).optJSONObject("error")?.optString("message").orEmpty() }.getOrDefault("")
    return when {
        code.startsWith("OPERATION_NOT_ALLOWED") -> "Enable Google sign-in in Firebase Authentication, then reconnect Echo"
        code.startsWith("API_KEY_INVALID") || code.startsWith("PROJECT_NOT_FOUND") || code.startsWith("CONFIGURATION_NOT_FOUND") ->
            "Check Echo's Firebase project and Web API key configuration"
        code.startsWith("INVALID_IDP_RESPONSE") && listOf("audience", "project", "client id", "client_id").any { code.contains(it, true) } ->
            "Google's OAuth client is not accepted by this Firebase project. Check Google provider configuration and the Desktop OAuth client's project."
        code.startsWith("INVALID_IDP_RESPONSE") || code.startsWith("INVALID_REFRESH_TOKEN") || code.startsWith("TOKEN_EXPIRED") || status == 401 ->
            "Firebase sign-in expired or was rejected; reconnect your Google account"
        status == 403 -> "Firebase denied access; check the signed-in account, enabled APIs and owner-only security rules"
        status == 429 || status == 503 -> "Firebase asked Echo to wait; try sync again later"
        else -> "Firebase request failed (HTTP $status); local data was kept"
    }
}
