package dev.echo.app.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import dev.echo.app.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Social credentials are never sent until an owner-approved HTTPS origin is pinned here. */
internal class SocialApiClient(
    private val apiBaseUrlForTest: String = PINNED_BASE_URL,
    private val inviteBaseUrlForTest: String = PINNED_INVITE_BASE_URL,
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build(),
    private val beforeRequest: () -> Unit = {},
) {
    private val origin: String? = apiBaseUrlForTest.takeIf(String::isNotBlank)?.let(::validatedOrigin)
    private val inviteBase: String? = inviteBaseUrlForTest.takeIf(String::isNotBlank)?.let(::validatedInviteBase)
    val configured: Boolean get() = origin != null
    val invitesConfigured: Boolean get() = configured && inviteBase != null

    internal fun readSetupEpoch(me: JSONObject): Long = me.opt("setupEpoch").let { epoch ->
        require(epoch is Number && epoch.toDouble() == epoch.toLong().toDouble() && epoch.toLong() >= 0) {
            "Social service returned an invalid setup epoch; refresh and retry."
        }
        epoch.toLong()
    }

    init {
        if (apiBaseUrlForTest != PINNED_BASE_URL || inviteBaseUrlForTest != PINNED_INVITE_BASE_URL) {
            check(BuildConfig.DEBUG && apiBaseUrlForTest == "https://social.test.invalid" &&
                inviteBaseUrlForTest in setOf("", "https://invite.test.invalid/invite")) { "Social test endpoints are unavailable in production" }
        }
    }

    suspend fun getMe(token: String) = call(token, "GET", "/v1/me")
    suspend fun saveProfile(token: String, username: String, displayName: String, avatar: String, setupEpoch: Long, version: Int? = null,
                            onlineVisible: Boolean = true, currentSongVisible: Boolean = true): JSONObject {
        require(username.matches(Regex("[A-Za-z0-9_]{3,24}"))) { "Username must be 3–24 letters, numbers or underscores" }
        require(displayName.trim().length in 1..40 && displayName.none(Char::isISOControl)) { "Profile name must be 1–40 characters" }
        require(avatar.matches(Regex("avatar_[1-8]"))) { "Choose one of Echo's built-in avatars" }
        require(setupEpoch >= 0) { "Social data changed; refresh and retry" }
        val body = JSONObject().put("username", username).put("displayName", displayName.trim())
            .put("avatar", avatar).put("onlineVisible", onlineVisible).put("currentSongVisible", currentSongVisible)
            .put("setupEpoch", setupEpoch)
        version?.let { body.put("version", validVersion(it)) }
        return call(token, "PUT", "/v1/me", body)
    }
    suspend fun lookup(token: String, username: String): JSONObject {
        require(username.matches(Regex("[A-Za-z0-9_]{3,24}"))) { "Username must be 3–24 letters, numbers or underscores" }
        return call(token, "GET", "/v1/users/${encodePath(username)}")
    }
    suspend fun friends(token: String) = call(token, "GET", "/v1/friends")
    suspend fun requests(token: String, direction: String) = call(token, "GET", "/v1/requests?direction=${direction.also { require(it in setOf("incoming", "outgoing")) }}")
    suspend fun createRequest(token: String, userId: String, expectedVersion: String) =
        call(token, "POST", "/v1/requests", JSONObject().put("userId", validUserId(userId)).put("expectedVersion", validRelationshipVersion(expectedVersion)))
    suspend fun requestAction(token: String, userId: String, action: String, expectedVersion: String) {
        require(action in setOf("accept", "decline", "cancel"))
        call(token, "POST", "/v1/requests/${encodePath(validUserId(userId))}/$action", JSONObject().put("expectedVersion", validRelationshipVersion(expectedVersion)))
    }
    suspend fun removeFriend(token: String, userId: String, expectedVersion: String) =
        call(token, "DELETE", "/v1/friends/${encodePath(validUserId(userId))}", JSONObject().put("expectedVersion", validRelationshipVersion(expectedVersion)))
    suspend fun blocks(token: String) = call(token, "GET", "/v1/blocks")
    suspend fun setBlocked(token: String, userId: String, blocked: Boolean, expectedVersion: String) =
        call(token, if (blocked) "PUT" else "DELETE", "/v1/blocks/${encodePath(validUserId(userId))}", JSONObject().put("expectedVersion", validRelationshipVersion(expectedVersion)))
    suspend fun createInvite(token: String): JSONObject {
        check(invitesConfigured) { "Social invites are unavailable: no approved invite-link host is configured" }
        val invite = call(token, "POST", "/v1/invites", JSONObject())
        inviteToken(invite.getString("url"))
        return invite
    }
    suspend fun redeemInvite(token: String, invite: String) {
        check(invitesConfigured) { "Social invites are unavailable: no approved invite-link host is configured" }
        call(token, "POST", "/v1/invites/redeem", JSONObject().put("token", inviteToken(invite)))
    }
    suspend fun deleteSocialProfile(token: String) = call(token, "DELETE", "/v1/me/social")
    suspend fun setVisibility(token: String, profile: JSONObject, online: Boolean, currentSong: Boolean): JSONObject {
        val body = JSONObject().put("username", profile.getString("username"))
            .put("displayName", profile.getString("displayName"))
            .put("avatar", profile.getString("avatar"))
            .put("onlineVisible", online).put("currentSongVisible", currentSong)
            .put("version", validVersion(profile.getInt("version")))
            .put("setupEpoch", profile.getLong("setupEpoch").also { require(it >= 0) { "Social data changed; refresh and retry" } })
        return call(token, "PUT", "/v1/me", body)
    }

    private suspend fun call(token: String, method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val host = origin ?: error("Social is unavailable: no approved service endpoint is configured")
        require(token.length in 20..16_384 && token.none(Char::isISOControl)) { "Social sign-in is unavailable; reconnect your account" }
        val url = "$host$path"
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Accept", "application/json").apply {
                when (method) {
                    "GET" -> get()
                    "DELETE" -> if (body == null) delete() else delete(body.toString().toRequestBody(JSON))
                    else -> method(method, body?.toString()?.toRequestBody(JSON) ?: "{}".toRequestBody(JSON))
                }
            }.build()
        beforeRequest()
        val call = http.newCall(request)
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException) call.cancel()
        }
        try { call.execute().use { response ->
            val source = requireNotNull(response.body).source()
            source.request(MAX_RESPONSE + 1L)
            require(source.buffer.size <= MAX_RESPONSE) { "Social service returned an oversized response" }
            val json = if (source.buffer.size == 0L) JSONObject() else JSONObject(source.buffer.readUtf8())
            if (!response.isSuccessful) {
                val error = json.optJSONObject("error")
                throw SocialApiException(response.code, error?.optString("code").orEmpty(),
                    "${error?.optString("message")?.takeIf(String::isNotBlank) ?: "Social request failed"} (HTTP ${response.code})")
            }
            json
        } } finally { cancellation.dispose() }
    }

    private fun validatedOrigin(value: String): String {
        val uri = java.net.URI(value)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Social endpoint must be a pinned HTTPS origin"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "Social endpoint must be an origin, without a path" }
        return "https://${uri.rawAuthority}"
    }

    private fun validatedInviteBase(value: String): String {
        val uri = java.net.URI(value)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Invite host must be an approved HTTPS URL"
        }
        return "https://${uri.rawAuthority}${uri.rawPath.orEmpty()}"
    }

    private fun encodePath(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    private fun validUserId(value: String) = value.also { require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid social user" } }
    private fun validVersion(value: Int) = value.also { require(it >= 0) { "Social data changed; refresh and retry" } }
    private fun validRelationshipVersion(value: String) = value.also {
        require(it.length in 1..256 && it.none(Char::isISOControl)) { "Social data changed; refresh and retry" }
    }

    private fun inviteToken(link: String): String {
        val uri = java.net.URI(link)
        val expected = java.net.URI(requireNotNull(inviteBase) { "Social invites are unavailable: no approved invite-link host is configured" })
        require(uri.scheme == "https" && uri.userInfo == null && uri.query == null && uri.host != null &&
            uri.host.equals(expected.host, true) && uri.port == expected.port && uri.rawPath == expected.rawPath && uri.fragment != null) {
            "Invite must use Echo's approved HTTPS invite host"
        }
        val token = java.net.URLDecoder.decode(uri.rawFragment.substringAfter("token="), Charsets.UTF_8.name())
        require(uri.rawFragment.startsWith("token=") && token.matches(Regex("[A-Za-z0-9_-]{32,256}"))) { "Invite link is invalid" }
        return token
    }

    companion object {
        // shortcut: networking stays disabled until an owner-authorized production HTTPS origin is provisioned.
        private const val PINNED_BASE_URL = ""
        private const val PINNED_INVITE_BASE_URL = ""
        private const val MAX_RESPONSE = 256 * 1024L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun JSONObject.socialVersion(field: String): String? =
    (opt(field) as? String)?.takeIf { it.length in 1..256 && it.none(Char::isISOControl) }

internal class SocialApiException(val status: Int, val code: String, message: String) : java.io.IOException(message)
