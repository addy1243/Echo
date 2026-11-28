package dev.echo.desktop

import java.nio.file.Files
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun mediaCachingIsOptInAndPersistsAcrossOtherChanges() {
        val file = temporary.root.toPath().resolve("appearance.json")
        val preferences = DesktopPreferences(file)
        assertFalse(preferences.mediaCachingEnabled)
        preferences.updateMediaCachingEnabled(true)
        preferences.updateStageVideoCachingEnabled(false)
        assertTrue(DesktopPreferences(file).mediaCachingEnabled)
        preferences.updateMediaCachingEnabled(false)
        assertFalse(DesktopPreferences(file).mediaCachingEnabled)
    }

    @Test fun stageCacheDefaultsToAudioAndVideoForNewAndExistingPreferences() {
        val file = temporary.root.toPath().resolve("appearance.json")
        assertTrue(DesktopPreferences(file).stageVideoCachingEnabled)
        Files.writeString(file, """{"theme":"LIGHT","closeToTray":false}""")
        val preferences = DesktopPreferences(file)
        assertTrue(preferences.stageVideoCachingEnabled)
        assertEquals(DesktopThemeMode.LIGHT, preferences.themeMode)
        assertFalse(preferences.closeToTray)
    }

    @Test fun stageCacheChoicePersistsAndSurvivesOtherPreferenceUpdates() {
        val file = temporary.root.toPath().resolve("appearance.json")
        Files.writeString(file, """{"unrelated":"preserve"}""")
        val preferences = DesktopPreferences(file)
        preferences.updateStageVideoCachingEnabled(false)
        preferences.setTheme(DesktopThemeMode.LIGHT)
        preferences.setAccent("#83E5CB")
        preferences.updateCloseToTray(false)
        val reopened = DesktopPreferences(file)
        assertFalse(reopened.stageVideoCachingEnabled)
        assertEquals(DesktopThemeMode.LIGHT, reopened.themeMode)
        assertEquals("#83E5CB", reopened.customAccent)
        assertFalse(reopened.closeToTray)
        assertEquals("preserve", org.json.JSONObject(Files.readString(file)).getString("unrelated"))
        reopened.updateStageVideoCachingEnabled(true)
        assertTrue(DesktopPreferences(file).stageVideoCachingEnabled)
    }

    @Test fun failedStageCachePreferenceSavePreservesFileAndActiveChoice() {
        val file = temporary.root.toPath().resolve("appearance.json")
        Files.writeString(file, "{broken")
        val preferences = DesktopPreferences(file)
        preferences.updateStageVideoCachingEnabled(false)
        assertTrue(preferences.stageVideoCachingEnabled)
        assertNotNull(preferences.error)
        assertEquals("{broken", Files.readString(file))
    }

    @Test fun tutorialCompletionMarkerPersistsAcrossRestart() {
        val file = temporary.root.toPath().resolve("preferences.json")
        val preferences = DesktopPreferences(file)
        assertFalse(preferences.tutorialCompleted)
        preferences.completeTutorial()

        val reopened = DesktopPreferences(file)
        assertTrue(reopened.tutorialCompleted)
    }

    @Test fun completionMarkerDoesNotRewritePreferencesAndSurvivesRestart() {
        val file = temporary.root.toPath().resolve("preferences.json")
        Files.writeString(file, "{broken")
        val preferences = DesktopPreferences(file)

        preferences.completeTutorial()
        assertEquals("{broken", Files.readString(file))
        assertTrue(preferences.tutorialCompleted)
        assertTrue(DesktopPreferences(file).tutorialCompleted)
    }

    @Test fun concurrentCompletionIsIdempotent() {
        val file = temporary.root.toPath().resolve("preferences.json")
        val preferences = DesktopPreferences(file)
        val workers = Executors.newFixedThreadPool(8)
        try {
            workers.invokeAll(List(8) { java.util.concurrent.Callable { preferences.completeTutorial() } })
                .forEach { it.get() }
        } finally { workers.shutdownNow() }
        assertTrue(DesktopPreferences(file).tutorialCompleted)
    }

    @Test fun appearanceAndCloseBehaviorPersistWithoutRemovingTutorialMarker() {
        val file = temporary.root.toPath().resolve("preferences.json")
        val preferences = DesktopPreferences(file)
        preferences.setTheme(DesktopThemeMode.LIGHT)
        preferences.setAccent("#83E5CB")
        preferences.updateCloseToTray(false)
        preferences.completeTutorial()

        val reopened = DesktopPreferences(file)
        assertEquals(DesktopThemeMode.LIGHT, reopened.themeMode)
        assertEquals("#83E5CB", reopened.customAccent)
        assertFalse(reopened.closeToTray)
        assertTrue(reopened.tutorialCompleted)
    }

    @Test fun appearanceMigratesLegacyValuesWithoutWritingLegacySettings() {
        val directory = temporary.root.toPath()
        val legacy = directory.resolve("preferences.json")
        val appearance = directory.resolve("appearance.json")
        val old = """{"theme":"LIGHT","accent":"#62B5F5","closeToTray":false,"updater":{"lastCheck":123}}"""
        Files.writeString(legacy, old)

        val preferences = DesktopPreferences(appearance, legacy)
        assertEquals(DesktopThemeMode.LIGHT, preferences.themeMode)
        assertEquals("#62B5F5", preferences.customAccent)
        assertFalse(preferences.closeToTray)
        preferences.setAccent("#83E5CB")

        assertEquals(old, Files.readString(legacy))
        assertEquals("#83E5CB", DesktopPreferences(appearance, legacy).customAccent)
    }

    @Test fun malformedAppearanceAndLegacyFilesAreReportedAndPreserved() {
        val directory = temporary.root.toPath()
        val legacy = directory.resolve("preferences.json")
        val appearance = directory.resolve("appearance.json")
        Files.writeString(legacy, "{broken legacy")
        val legacyPrefs = DesktopPreferences(appearance, legacy)
        assertNotNull(legacyPrefs.error)
        legacyPrefs.setTheme(DesktopThemeMode.LIGHT)
        assertEquals("{broken legacy", Files.readString(legacy))

        Files.writeString(appearance, "{broken appearance")
        val malformed = DesktopPreferences(appearance, legacy)
        assertNotNull(malformed.error)
        malformed.setTheme(DesktopThemeMode.LIGHT)
        assertEquals("{broken appearance", Files.readString(appearance))
        assertEquals(DesktopThemeMode.DARK, malformed.themeMode)
    }
}
