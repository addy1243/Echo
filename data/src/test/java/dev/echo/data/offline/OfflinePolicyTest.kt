package dev.echo.data.offline

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class OfflinePolicyTest {
    @Test fun downloadProgressUsesKnownByteCountsAndStaysIndeterminateWhenUnknown() {
        assertNull(downloadPercent(100, null))
        assertNull(downloadPercent(100, -1))
        assertNull(downloadPercent(100, 0))
        assertEquals(0, downloadPercent(0, 100))
        assertEquals(37, downloadPercent(37, 100))
        assertEquals(100, downloadPercent(100, 100))
        assertEquals(100, downloadPercent(101, 100))
    }
    @Test fun fileNamesAreStableAndCannotEscapePrivateDirectory() {
        assertEquals(offlineFileName("youtube:abcdefghijk"), offlineFileName("youtube:abcdefghijk"))
        assertTrue(offlineFileName("../../secrets").matches(Regex("[a-f0-9]{64}\\.audio")))
        assertNotEquals(offlineFileName("one"), offlineFileName("two"))
    }

    @Test fun automaticCacheNeedsConsentSubstantivePlaysAndUnmeteredNetwork() {
        assertTrue(shouldAutoCache(true, false, true, 3))
        assertFalse(shouldAutoCache(false, false, true, 3))
        assertFalse(shouldAutoCache(true, true, true, 3))
        assertFalse(shouldAutoCache(true, false, false, 3))
        assertFalse(shouldAutoCache(true, false, true, 2))
    }

    @Test fun partialEmptyOversizedAndLowStorageDownloadsAreRejected() {
        validateDownloadSize(12, 12, FREE_SPACE_RESERVE)
        for ((bytes, declared, available) in listOf(Triple(0L, -1L, FREE_SPACE_RESERVE),
            Triple(12L, 13L, FREE_SPACE_RESERVE), Triple(MAX_SONG_BYTES + 1, -1L, FREE_SPACE_RESERVE),
            Triple(12L, 12L, FREE_SPACE_RESERVE - 1))) {
            assertThrows(IllegalArgumentException::class.java) { validateDownloadSize(bytes, declared, available) }
        }
    }

    @Test fun missingTruncatedAndSameSizeDamagedFilesNeverCountAsOfflineAudio() {
        val file = File.createTempFile("echo-offline", ".audio")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val digest = audioDigest(file)
            assertTrue(validOfflineFile(file, 3, digest))
            file.writeBytes(byteArrayOf(1, 2))
            assertFalse(validOfflineFile(file, 3, digest))
            file.writeBytes(byteArrayOf(3, 2, 1))
            assertFalse(validOfflineFile(file, 3, digest))
            file.delete()
            assertFalse(validOfflineFile(file, 3, digest))
        } finally { file.delete() }
    }
}
