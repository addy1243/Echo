package dev.echo.data.spotify

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class SpotifyArchiveStoreTest {
    @Test fun savesDistinctArchivesAsCiphertextAndKeepsExistingArchive() = runBlocking {
        val parent = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(parent.cacheDir, "spotify-archive-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = SpotifyArchiveStore(TestContext(parent, root))
            val first = "private spotify export fixture".toByteArray()
            val second = "another export fixture".toByteArray()
            store.save(first)
            val directory = File(root, "spotify-archives")
            val firstFile = File(directory, sha256(first) + ".spz")
            val originalCiphertext = firstFile.readBytes()

            store.save(first)
            store.save(second)

            assertEquals(2, directory.listFiles()?.size)
            assertEquals(setOf(sha256(first), sha256(second)), store.list().map { it.sha256 }.toSet())
            assertTrue(store.read(sha256(first)).contentEquals(first))
            assertTrue(firstFile.exists())
            assertTrue(originalCiphertext.contentEquals(firstFile.readBytes()))
            assertFalse(firstFile.readBytes().toString(Charsets.ISO_8859_1).contains(first.toString(Charsets.ISO_8859_1)))
            assertTrue(firstFile.length() > first.size)
            val other = File(directory, "other.txt").apply { writeText("keep") }
            firstFile.writeBytes(firstFile.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            try {
                store.read(sha256(first))
                throw AssertionError("Tampered Spotify archive was accepted")
            } catch (_: Exception) { }
            firstFile.writeBytes(originalCiphertext)
            store.deleteAll()
            assertTrue(other.exists())
            assertEquals(listOf("other.txt"), directory.listFiles()?.map { it.name })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsOversizedInputBeforeCreatingArchiveDirectory() = runBlocking {
        val parent = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(parent.cacheDir, "spotify-archive-limit-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = SpotifyArchiveStore(TestContext(parent, root))
            try {
                store.save(ByteArray(50_000_001))
                throw AssertionError("Oversized archive was accepted")
            } catch (_: IllegalArgumentException) {
                assertFalse(File(root, "spotify-archives").exists())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private class TestContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = root
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
