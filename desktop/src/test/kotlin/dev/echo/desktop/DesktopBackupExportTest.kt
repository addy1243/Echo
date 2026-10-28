package dev.echo.desktop

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopBackupExportTest {
    @Test fun savesAndReplacesCompleteBackupWithoutTemporaryFiles() {
        val directory = Files.createTempDirectory("echo-export-test")
        val file = directory.resolve("library.json").toFile()
        try {
            writeDesktopBackup(file, "first")
            writeDesktopBackup(file, "replacement")
            assertEquals("replacement", file.readText())
            Files.list(directory).use { assertEquals(1, it.count()) }
        } finally { file.delete(); Files.deleteIfExists(directory) }
    }
}
