package dev.echo.desktop

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DesktopStageIsolationTest {
    @Test fun stageUsesSeparateStorageForLibraryOfflineFilesAndCredentials() {
        val appData = "C:/Users/test/AppData/Local"
        val production = desktopDataDirectory(false, appData, "C:/Users/test")
        val stage = desktopDataDirectory(true, appData, "C:/Users/test")

        assertEquals(Path.of(appData, "EchoMusic"), production)
        assertEquals(Path.of(appData, "EchoMusicStage"), stage)
        assertNotEquals(production, stage)
        assertEquals(Path.of("D:/EchoStage"), desktopDataDirectory(true, appData, "C:/Users/test", "D:/EchoStage"))
    }
}
