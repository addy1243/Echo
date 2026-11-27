package dev.echo.desktop

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class StageScreensaverTest {
    @Test fun idleClockHandlesUnsignedTickWrap() {
        assertEquals(32L, windowsIdleMillis(0xFFFFFFF0.toInt(), 0x10))
        assertEquals(5_000L, windowsIdleMillis(10_000, 15_000))
        assertTrue(windowsInputChangedSince(0xFFFFFFF0.toInt(), 0x10))
        assertFalse(windowsInputChangedSince(15_000, 15_000))
    }

    @Test fun syntheticWindowMouseEventsCannotDismissWithoutNewWindowsInput() {
        assertFalse(stageSaverHasNewWindowsInput(entryTick = 15_000, currentTick = 15_000))
        assertFalse(stageSaverHasNewWindowsInput(entryTick = null, currentTick = 15_000))
        assertFalse(stageSaverHasNewWindowsInput(entryTick = 15_000, currentTick = null))
        assertTrue(stageSaverHasNewWindowsInput(entryTick = 15_000, currentTick = 15_016))
        assertTrue(stageSaverHasNewWindowsInput(entryTick = 0xFFFFFFF0.toInt(), currentTick = 0x10))
    }

    @Test fun automaticStartRequiresIdlenessAndNoWindowsDisplayRequest() {
        assertTrue(stageScreensaverAutoStartAllowed(300_000, 300_000) { false })
        assertFalse(stageScreensaverAutoStartAllowed(300_000, 300_000) { true })
        assertFalse(stageScreensaverAutoStartAllowed(300_000, 300_000) { null })
        assertFalse(stageScreensaverAutoStartAllowed(299_999, 300_000) { error("Should wait for idle") })
        assertFalse(stageScreensaverAutoStartAllowed(null, 300_000) { error("Should wait for interactive desktop") })
    }

    @Test fun motionNeedsRepeatedChangeAndSettlesOnStillFrames() {
        val detector = StageVideoMotionDetector()
        detector.sample("one", IntArray(64) { 0 })
        detector.sample("one", IntArray(64) { 255 })
        assertFalse(detector.moving)
        assertFalse(detector.classified)
        detector.sample("one", IntArray(64) { 0 })
        assertTrue(detector.moving)
        assertTrue(detector.classified)
        repeat(3) { detector.sample("one", IntArray(64) { 0 }) }
        assertFalse(detector.moving)
        detector.sample("two", IntArray(64) { 255 })
        assertFalse(detector.moving)
        assertFalse(detector.classified)
    }

    @Test fun unavailableFramesPreserveMotionClassificationUntilExplicitReset() {
        val detector = StageVideoMotionDetector()
        repeat(3) { detector.sample("one", null) }
        assertFalse(detector.moving)
        assertFalse(detector.classified)

        detector.sample("one", IntArray(64) { 0 })
        detector.sample("one", IntArray(64) { 255 })
        detector.sample("one", IntArray(64) { 0 })
        assertTrue(detector.moving)
        assertTrue(detector.classified)
        repeat(3) { detector.sample("one", null) }
        assertTrue(detector.moving)
        assertTrue(detector.classified)

        detector.reset("one")
        assertFalse(detector.moving)
        assertFalse(detector.classified)
        repeat(3) { detector.sample("one", null) }
        assertFalse(detector.moving)
        assertFalse(detector.classified)
    }

    @Test fun preferencesPersistAndMalformedFileIsPreserved() {
        val directory = Files.createTempDirectory("echo-stage-saver-test")
        try {
            val file = directory.resolve("stage-screensaver.json")
            val preferences = StageScreensaverPreferences(file)
            preferences.configureAutomatic(true)
            preferences.configureIdleMinutes(15)
            preferences.configureStyle(StageScreensaverStyle.AMBIENT)
            val loaded = StageScreensaverPreferences(file)
            assertTrue(loaded.automatic)
            assertEquals(15, loaded.idleMinutes)
            assertEquals(StageScreensaverStyle.AMBIENT, loaded.style)

            val legacyFile = directory.resolve("legacy.json")
            val legacyJson = """{"idleMinutes":10,"automatic":true,"style":"GALLERY","other":42}"""
            Files.writeString(legacyFile, legacyJson)
            val legacy = StageScreensaverPreferences(legacyFile)
            assertEquals(10, legacy.idleMinutes)
            assertTrue(legacy.automatic)
            assertEquals(StageScreensaverStyle.CINEMA, legacy.style)
            assertNull(legacy.error)
            assertEquals(legacyJson, Files.readString(legacyFile))
            legacy.configureIdleMinutes(30)
            val saved = JSONObject(Files.readString(legacyFile))
            assertEquals(30, saved.getInt("idleMinutes"))
            assertTrue(saved.getBoolean("automatic"))
            assertEquals("CINEMA", saved.getString("style"))
            assertEquals(42, saved.getInt("other"))

            val invalid = directory.resolve("damaged.json")
            Files.writeString(invalid, "not-json")
            val damaged = StageScreensaverPreferences(invalid)
            damaged.configureAutomatic(true)
            assertEquals("not-json", Files.readString(invalid))
            assertNotNull(damaged.error)
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test fun cinemaProgressBarFitsNarrowWindows() {
        assertEquals(1, stageHudBarWidth(1, 96))
        assertEquals(8, stageHudBarWidth(120, 56))
        assertEquals(691, stageHudBarWidth(1440, 80))
    }
}
