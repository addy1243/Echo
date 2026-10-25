package dev.echo.data.provider

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

class YouTubeDownloaderTest {
    @Test fun preservesMethodsBodiesAndHeadersWithoutNetwork() {
        listOf("GET", "HEAD", "POST").forEach { method ->
            val body = if (method == "POST") "{\"query\":\"music\"}".toByteArray() else null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val sent = chain.request()
                assertEquals(method, sent.method)
                assertEquals("test-agent", sent.header("User-Agent"))
                assertEquals("application/json", sent.header("Content-Type"))
                if (body == null) assertNull(sent.body)
                else assertArrayEquals(body, Buffer().also { sent.body!!.writeTo(it) }.readByteArray())
                okhttp3.Response.Builder().request(sent).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("X-Test", "preserved").body("response".toResponseBody()).build()
            }.build()
            val request = Request.newBuilder().httpMethod(method).url("https://www.youtube.com/youtubei/v1/search")
                .headers(mapOf("User-Agent" to listOf("test-agent"), "Content-Type" to listOf("application/json")))
                .apply { if (body != null) dataToSend(body) }.build()
            val response = YouTubeDownloader(client).execute(request)
            assertEquals(200, response.responseCode())
            assertEquals("response", response.responseBody())
            assertEquals("preserved", response.getHeader("X-Test"))
        }
    }

    @Test fun rateLimitsAndDeniedRequestsStopWithSafeMessages() {
        listOf(401, 403, 429).forEach { code ->
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                    .message("Restricted").body("private diagnostic".toResponseBody()).build()
            }.build()
            val error = assertThrows(YouTubeRequestException::class.java) {
                YouTubeDownloader(client).execute(Request.newBuilder()
                    .get("https://www.youtube.com/watch?v=aB12_cD34-e&token=private").build())
            }
            assertTrue(error.message!!.contains(code.toString()))
            assertFalse(error.message!!.contains("private"))
            assertEquals(1, calls)
        }
    }

    @Test fun captchaStopsAndUntrustedAddressesNeverReachClient() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("verification".toResponseBody()).build()
        }.build()
        val downloader = YouTubeDownloader(client)
        assertThrows(ReCaptchaException::class.java) {
            downloader.execute(Request.newBuilder().get("https://www.youtube.com/sorry/index").build())
        }
        listOf("http://www.youtube.com/", "https://youtube.com.evil.example/", "https://www.youtube.com:8443/",
            "https://user:secret@youtube.com/", "https://localhost/", "https://accounts.google.com/").forEach { url ->
            assertThrows(YouTubeRequestException::class.java) { downloader.execute(Request.newBuilder().get(url).build()) }
        }
        assertEquals(1, calls)
    }
}
