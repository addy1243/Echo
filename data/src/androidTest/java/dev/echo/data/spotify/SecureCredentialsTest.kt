package dev.echo.data.spotify

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SecureCredentialsTest {
    @Test fun concurrentInstancesStoreCiphertextAndDecryptAcrossInstances() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "credential-test-${UUID.randomUUID()}"
        val first = SecureCredentials(context, preferenceName)
        val second = SecureCredentials(context, preferenceName)
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val writes = listOf(
                executor.submit { start.await(); first.set("first", "first-secret") },
                executor.submit { start.await(); second.set("second", "second-secret") },
            )
            start.countDown()
            writes.forEach { it.get(20, TimeUnit.SECONDS) }
            val reader = SecureCredentials(context, preferenceName)
            assertEquals("first-secret", reader.get("first"))
            assertEquals("second-secret", reader.get("second"))
            val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            for ((name, secret) in listOf("first" to "first-secret", "second" to "second-secret")) {
                val ciphertext = preferences.getString(name, null)
                assertNotEquals(secret, ciphertext)
                assertFalse(ciphertext.orEmpty().contains(secret))
            }
            first.set("first", null)
            assertNull(reader.get("first"))
            assertEquals("second-secret", reader.get("second"))
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            context.deleteSharedPreferences(preferenceName)
        }
    }
}
