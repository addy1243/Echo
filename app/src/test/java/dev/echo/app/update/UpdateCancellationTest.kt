package dev.echo.app.update

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCancellationTest {
    @Test fun cancellingWhileResponseBodyIsBlockedCancelsTheHttpCallPromptly() = runBlocking {
        val server = ServerSocket(0)
        val bodyStarted = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val worker = Thread {
            server.accept().use { socket ->
                socket.getInputStream().bufferedReader().readLine()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\nx".toByteArray())
                    flush()
                }
                finish.await(5, TimeUnit.SECONDS)
            }
        }.apply { isDaemon = true; start() }
        val client = OkHttpClient()
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/update.apk").build())
        val download = launch(Dispatchers.IO) { call.useCancellable { response -> bodyStarted.countDown(); response.body!!.string() } }
        try {
            assertTrue(bodyStarted.await(3, TimeUnit.SECONDS))
            withTimeout(1500) { download.cancelAndJoin() }
            assertTrue(call.isCanceled())
        } finally {
            finish.countDown(); server.close(); worker.join(2000)
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
}
