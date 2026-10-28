package dev.echo.desktop

import com.sun.net.httpserver.HttpServer
import dev.echo.data.firebase.FirebaseAuthRest
import dev.echo.data.firebase.FirebaseConfig
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FirebaseHttpTest {
    @Test fun actualLoopbackFixtureExercisesHttpWithoutCloudCredentials() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/") { exchange ->
            val response = JSONObject().put("providerId", "google.com").put("emailVerified", true)
                .put("email", "fixture@example.test").put("localId", "fixture-user").put("idToken", "synthetic-id")
                .put("refreshToken", "synthetic-refresh").put("expiresIn", "3600").toString().toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }; exchange.close()
        }
        server.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                .url("http://127.0.0.1:${server.address.port}/fixture").build()) }.build()
            val session = FirebaseAuthRest(FirebaseConfig("echo-fixture", "a".repeat(30)), client)
                .signInGoogle("synthetic-google-token", "fixture@example.test")
            assertEquals("fixture-user", session.uid)
        } finally { server.stop(0) }
    }
}
