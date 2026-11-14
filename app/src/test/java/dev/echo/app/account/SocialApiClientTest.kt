package dev.echo.app.account

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SocialApiClientTest {
    @Test fun relationshipVersionsRemainOpaqueStringsInEveryMutation() = runBlocking {
        val bodies = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            bodies += okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val version = "not-a-number:opaque-token"
        val client = SocialApiClient("https://social.test.invalid", http = http)
        client.createRequest("synthetic-test-token-1234567890", "user", version)
        client.requestAction("synthetic-test-token-1234567890", "user", "accept", version)
        client.removeFriend("synthetic-test-token-1234567890", "user", version)
        client.setBlocked("synthetic-test-token-1234567890", "user", true, version)
        bodies.forEach { assertTrue(it.contains("\"expectedVersion\":\"$version\"")) }
        assertTrue(runCatching { client.createRequest("synthetic-test-token-1234567890", "user", "") }.isFailure)
    }

    @Test fun relationshipVersionReaderFailsClosedWithoutInterpretingTokens() {
        val token = "0123456789abcdef0123456789abcdef.abcdef0123456789abcdef0123456789"
        assertEquals(token, org.json.JSONObject().put("relationshipVersion", token).socialVersion("relationshipVersion"))
        assertEquals(null, org.json.JSONObject().put("relationshipVersion", 1).socialVersion("relationshipVersion"))
        assertEquals(null, org.json.JSONObject().socialVersion("relationshipVersion"))
        assertEquals(null, org.json.JSONObject().put("relationshipVersion", "").socialVersion("relationshipVersion"))
    }

    @Test fun socialRequestsFailClosedWithoutApprovedEndpoint() = runBlocking {
        val error = runCatching { SocialApiClient().getMe("a-valid-shaped-token-value-123456") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("no approved service endpoint"))
    }

    @Test fun inviteHostDoesNotGateProfileAndFriendApi() {
        val client = SocialApiClient("https://social.test.invalid")
        assertTrue(client.configured)
        assertTrue(!client.invitesConfigured)
    }

    @Test fun profileInputIsValidatedBeforeNetwork() = runBlocking {
        val error = runCatching { SocialApiClient().saveProfile("token", "x", "Name", "avatar_1", 0) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Username must be 3–24"))
    }

    @Test fun requestGuardStopsHttpWhenOfflineOrAccountChanged() = runBlocking {
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val error = runCatching {
            SocialApiClient("https://social.test.invalid", http = http, beforeRequest = { error("Social request stopped") })
                .getMe("synthetic-test-token-1234567890")
        }.exceptionOrNull()
        assertEquals("Social request stopped", error?.message)
        assertEquals(0, requests)
    }

    @Test fun pendingProfileEpochIsRequiredAndParsed() {
        val client = SocialApiClient("https://social.test.invalid")
        assertEquals(7L, client.readSetupEpoch(org.json.JSONObject().put("setupComplete", false).put("setupEpoch", 7)))
        assertTrue(runCatching { client.readSetupEpoch(org.json.JSONObject().put("setupComplete", false)) }.isFailure)
    }

    @Test fun setupUsesFakeHttpsEndpointAndEnablesSharingOnlyAfterConfirmation() = runBlocking {
        val captured = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            captured += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = SocialApiClient("https://social.test.invalid", "https://invite.test.invalid/invite", http)
        client.saveProfile("synthetic-test-token-1234567890", "echo_user", "Echo User", "avatar_1", 7)
        client.setVisibility("synthetic-test-token-1234567890", org.json.JSONObject()
            .put("username", "echo_user").put("displayName", "Echo User").put("avatar", "avatar_1")
            .put("version", 2).put("setupEpoch", 7), false, true)
        val request = captured[0]
        assertEquals("Bearer synthetic-test-token-1234567890", request.header("Authorization"))
        assertEquals("https://social.test.invalid/v1/me", request.url.toString())
        val setupBody = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(setupBody.contains("\"setupEpoch\":7"))
        assertTrue(!setupBody.contains("\"version\""))
        assertTrue(setupBody.contains("\"onlineVisible\":true"))
        assertTrue(setupBody.contains("\"currentSongVisible\":true"))
        val visibilityBody = okio.Buffer().also { captured[1].body!!.writeTo(it) }.readUtf8()
        assertTrue(visibilityBody.contains("\"setupEpoch\":7"))
        assertTrue(visibilityBody.contains("\"version\":2"))
    }

    @Test fun inviteUsesPinnedHttpsHostAndSendsOnlyFragmentSecretToApi() = runBlocking {
        val requests = mutableListOf<Pair<String, String>>()
        val link = "https://invite.test.invalid/invite#token=abcdefghijklmnopqrstuvwxyz0123456789AB"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
            requests += request.url.encodedPath to body
            val responseBody = if (request.url.encodedPath == "/v1/invites") """{"url":"$link","expiresAt":"2030-01-01T00:00:00Z"}""" else "{}"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(responseBody.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = SocialApiClient("https://social.test.invalid", "https://invite.test.invalid/invite", http)
        assertEquals(link, client.createInvite("synthetic-test-token-1234567890").getString("url"))
        client.redeemInvite("synthetic-test-token-1234567890", link)
        assertEquals("/v1/invites", requests[0].first)
        assertEquals("/v1/invites/redeem", requests[1].first)
        assertTrue(requests[1].second.contains("abcdefghijklmnopqrstuvwxyz0123456789AB"))
        assertTrue(!requests[1].second.contains("https://"))
    }

    @Test fun inviteRejectsUnpinnedHostAndPath() = runBlocking {
        for (link in listOf(
            "https://attacker.test.invalid/invite#token=abcdefghijklmnopqrstuvwxyz0123456789AB",
            "https://invite.test.invalid/other#token=abcdefghijklmnopqrstuvwxyz0123456789AB",
        )) {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(201).message("Created")
                    .body("""{"url":"$link","expiresAt":1}""".toResponseBody("application/json".toMediaType())).build()
            }.build()
            val error = runCatching {
                SocialApiClient("https://social.test.invalid", "https://invite.test.invalid/invite", http)
                    .createInvite("synthetic-test-token-1234567890")
            }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("approved HTTPS invite host"))
        }
    }
}
