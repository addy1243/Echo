package dev.echo.data.firebase

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FirebaseRestTest {
    private val config = FirebaseConfig("echo-music-test", "synthetic-public-api-key")
    private val session = FirebaseSession("user_one", "owner@example.com", "synthetic-id-token", "synthetic-refresh-token", 9_999_999_999_999)
    private val time = "2026-10-27T00:00:00.123456Z"

    @Test fun configAndSessionCannotInjectPathsOrExposeTokens() {
        listOf("../project", "https://evil.example", "", "abc").forEach {
            assertTrue(runCatching { FirebaseConfig(it, config.apiKey) }.isFailure)
        }
        assertTrue(runCatching { FirebaseConfig(config.projectId, "a&other=key") }.isFailure)
        assertTrue(runCatching { session.copy(uid = "../other") }.isFailure)
        assertFalse(session.toString().contains(session.idToken))
        assertFalse(session.toString().contains(session.refreshToken))
    }

    @Test fun googleExchangePinsProviderAndVerifiedExpectedAccount() = runBlocking {
        val client = fixtureClient { request ->
            assertEquals("identitytoolkit.googleapis.com", request.url.host)
            assertEquals(config.apiKey, request.url.queryParameter("key"))
            assertEquals("/v1/accounts:signInWithIdp", request.url.encodedPath)
            val body = JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertEquals("http://localhost", body.getString("requestUri"))
            assertEquals("access_token=synthetic-google-token&providerId=google.com", body.getString("postBody"))
            assertTrue(body.getBoolean("returnSecureToken"))
            200 to identity().toString()
        }
        val signed = FirebaseAuthRest(config, client).signInGoogle("synthetic-google-token", session.email)
        assertEquals(session.uid, signed.uid)
        assertEquals(session.email, signed.email)
        val wrong = FirebaseAuthRest(config, fixtureClient { 200 to identity().put("email", "other@example.com").toString() })
        assertTrue(runCatching { wrong.signInGoogle("synthetic-google-token", session.email) }.isFailure)
        val unverified = FirebaseAuthRest(config, fixtureClient { 200 to identity().put("emailVerified", false).toString() })
        assertTrue(runCatching { unverified.signInGoogle("synthetic-google-token", session.email) }.isFailure)
    }

    @Test fun refreshKeepsTheSameUserAndRejectsUnboundedLifetime() = runBlocking {
        val client = fixtureClient { request ->
            assertEquals("securetoken.googleapis.com", request.url.host)
            assertEquals("/v1/token", request.url.encodedPath)
            val form = request.body as okhttp3.FormBody
            assertEquals("refresh_token", form.value(0))
            200 to refreshed().toString()
        }
        assertEquals(session.uid, FirebaseAuthRest(config, client).refresh(session).uid)
        listOf(refreshed().put("user_id", "other_user"), refreshed().put("expires_in", "999999999")).forEach { response ->
            assertTrue(runCatching { FirebaseAuthRest(config, fixtureClient { 200 to response.toString() }).refresh(session) }.isFailure)
        }
    }

    @Test fun readsOnlyTheOwnerDocumentAndBoundsCompressedPayload() = runBlocking {
        val json = "{\"playlists\":[{\"trackIds\":[\"same\",\"same\"]}]}"
        val response = document(json)
        val cloud = FirebaseLibraryCloud(config, fixtureClient { request ->
            assertEquals("firestore.googleapis.com", request.url.host)
            assertEquals("Bearer ${session.idToken}", request.header("Authorization"))
            assertTrue(request.url.encodedPath.endsWith("/users/${session.uid}/sync/library-v1"))
            200 to response.toString()
        })
        assertEquals(FirebaseCloudDocument(json, time), cloud.read(session))
        response.getJSONObject("fields").getJSONObject("uid").put("stringValue", "other_user")
        assertTrue(runCatching { FirebaseLibraryCloud(config, fixtureClient { 200 to response.toString() }).read(session) }.isFailure)
    }

    @Test fun missingDocumentIsDifferentFromMissingDatabase() = runBlocking {
        val cloud = FirebaseLibraryCloud(config, fixtureClient { 404 to "{\"error\":{\"status\":\"NOT_FOUND\",\"message\":\"Document not found\"}}" })
        assertNull(cloud.read(session).json)
        val missingDatabase = FirebaseLibraryCloud(config, fixtureClient { 404 to "{\"error\":{\"status\":\"NOT_FOUND\",\"message\":\"Database not found for projects/echo/databases/(default)/documents/users\"}}" })
        assertTrue(runCatching { missingDatabase.read(session) }.isFailure)
    }

    @Test fun commitUsesCompareAndSwapAndNeverOverwritesConcurrentChanges() = runBlocking {
        val json = "{\"playlists\":[]}"
        val cloud = FirebaseLibraryCloud(config, fixtureClient { request ->
            val body = JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            val write = body.getJSONArray("writes").getJSONObject(0)
            assertEquals(time, write.getJSONObject("currentDocument").getString("updateTime"))
            val fields = write.getJSONObject("update").getJSONObject("fields")
            assertEquals(json, decodeFirebasePayload(fields.getJSONObject("payload").getString("stringValue")))
            assertEquals(session.uid, fields.getJSONObject("uid").getString("stringValue"))
            200 to JSONObject().put("writeResults", JSONArray().put(JSONObject().put("updateTime", time))).toString()
        })
        assertTrue(cloud.write(session, FirebaseCloudDocument(json, time), json))
        val create = FirebaseLibraryCloud(config, fixtureClient { request ->
            val body = JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertFalse(body.getJSONArray("writes").getJSONObject(0).getJSONObject("currentDocument").getBoolean("exists"))
            409 to "{\"error\":{\"status\":\"ALREADY_EXISTS\"}}"
        })
        assertFalse(create.write(session, FirebaseCloudDocument(null, null), json))
        val changed = FirebaseLibraryCloud(config, fixtureClient { 400 to "{\"error\":{\"status\":\"FAILED_PRECONDITION\"}}" })
        assertFalse(changed.write(session, FirebaseCloudDocument(json, time), json))
    }

    @Test fun rejectsGzipBombsAndOversizedOrInvalidPayloads() {
        val json = "{\"tracks\":[]}"
        assertEquals(json, decodeFirebasePayload(encodeFirebasePayload(json)))
        assertTrue(runCatching { encodeFirebasePayload("not JSON") }.isFailure)
        assertTrue(runCatching { decodeFirebasePayload("x".repeat(FIREBASE_MAX_ENCODED_BYTES + 1)) }.isFailure)
        val inflated = ByteArray(FIREBASE_MAX_JSON_BYTES + 1) { 'a'.code.toByte() }
        val zipped = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(inflated) } }.toByteArray()
        assertTrue(runCatching { decodeFirebasePayload(Base64.getEncoder().encodeToString(zipped)) }.isFailure)
    }

    @Test fun errorsNeverRevealServerTextOrCredentialValues() = runBlocking {
        val marker = "must-not-be-displayed"
        val auth = FirebaseAuthRest(config, fixtureClient { 403 to "{\"error\":{\"message\":\"$marker\"}}" })
        val failure = runCatching { auth.signInGoogle("synthetic-google-token", session.email) }.exceptionOrNull()!!
        assertTrue(failure is FirebaseRequestException)
        assertFalse(failure.message.orEmpty().contains(marker))
        assertFalse(failure.message.orEmpty().contains("synthetic-google-token"))
    }

    @Test fun oauthProjectMismatchExplainsSetupWithoutDisplayingServerDetails() = runBlocking {
        val auth = FirebaseAuthRest(config, fixtureClient { 400 to "{\"error\":{\"message\":\"INVALID_IDP_RESPONSE : access_token audience is not for this project synthetic-private-detail\"}}" })
        val failure = runCatching { auth.signInGoogle("synthetic-google-token", session.email) }.exceptionOrNull()!!
        assertTrue(failure.message.orEmpty().contains("OAuth client"))
        assertFalse(failure.message.orEmpty().contains("synthetic-private-detail"))
    }


    private fun identity() = JSONObject().put("providerId", "google.com").put("emailVerified", true).put("email", session.email)
        .put("localId", session.uid).put("idToken", session.idToken).put("refreshToken", session.refreshToken).put("expiresIn", "3600")
    private fun refreshed() = JSONObject().put("user_id", session.uid).put("token_type", "Bearer").put("id_token", session.idToken)
        .put("refresh_token", session.refreshToken).put("expires_in", "3600")
    private fun document(json: String) = JSONObject().put("name", "projects/${config.projectId}/databases/(default)/documents/users/${session.uid}/sync/library-v1")
        .put("updateTime", time).put("fields", JSONObject().put("uid", value(session.uid)).put("format", value("echo-library-state"))
            .put("version", JSONObject().put("integerValue", "1")).put("encoding", value("gzip-base64")).put("payload", value(encodeFirebasePayload(json))))
    private fun value(value: String) = JSONObject().put("stringValue", value)
    private fun fixtureClient(block: (okhttp3.Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val response = block(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(response.first).message("Fixture")
            .body(response.second.toResponseBody()).build()
    }.build()
}
