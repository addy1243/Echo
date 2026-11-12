package dev.echo.desktop

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopSpotifyArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun archivePathUsesAppDataInsteadOfCustomLibraryDirectory() {
        val appData = temporary.root.toPath().resolve("LocalAppData/EchoMusic")
        val customLibrary = temporary.root.toPath().resolve("OneDrive/Music/library.json")
        assertEquals(appData.resolve("spotify-archives"), desktopSpotifyArchiveDirectory(appData))
        assertTrue(desktopSpotifyArchiveDirectory(appData) != customLibrary.resolveSibling("spotify-archives"))
    }

    @Test fun legacyArchivesCopyToLocalStoreWithoutRemovingSource() {
        val root = temporary.root.toPath()
        val legacy = root.resolve("OneDrive/Music/spotify-archives")
        val local = desktopSpotifyArchiveDirectory(root.resolve("LocalAppData/EchoMusic"))
        val contents = "legacy Spotify export".toByteArray()
        val id = MessageDigest.getInstance("SHA-256").digest(contents).joinToString("") { "%02x".format(it) }
        saveDesktopSpotifyArchive(contents, legacy)

        migrateDesktopSpotifyArchives(legacy, local)

        assertArrayEquals(contents, readDesktopSpotifyArchive(local, id))
        assertArrayEquals(contents, readDesktopSpotifyArchive(legacy, id))
    }

    @Test fun malformedLegacyArchiveFailsMigrationAndKeepsOriginal() {
        val root = temporary.root.toPath()
        val legacy = root.resolve("OneDrive/Music/spotify-archives")
        val local = desktopSpotifyArchiveDirectory(root.resolve("LocalAppData/EchoMusic"))
        val id = "a".repeat(64)
        Files.createDirectories(legacy)
        Files.write(legacy.resolve("$id.dpapi"), byteArrayOf(1, 2, 3))

        assertThrows(Exception::class.java) { migrateDesktopSpotifyArchives(legacy, local) }
        assertTrue(Files.exists(legacy.resolve("$id.dpapi")))
    }
}
