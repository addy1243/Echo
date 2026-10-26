package dev.echo.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class QualifiedPlayIncreasesTest {
    @Test fun firstSnapshotAndUnchangedCountsDoNotPrefetchHistory() {
        val counts = mapOf("popular" to 12, "other" to 3)
        assertEquals(emptySet<String>(), qualifiedPlayIncreases(null, counts))
        assertEquals(emptySet<String>(), qualifiedPlayIncreases(counts, counts))
    }

    @Test fun onlyNewSubstantiveListensTriggerCandidates() {
        assertEquals(setOf("popular", "new"), qualifiedPlayIncreases(
            mapOf("popular" to 3, "unchanged" to 8),
            mapOf("popular" to 4, "unchanged" to 8, "new" to 1, "zero" to 0)))
    }

    @Test fun ResetAndRemovalDoNotTriggerDownloads() {
        assertEquals(emptySet<String>(), qualifiedPlayIncreases(
            mapOf("reset" to 9, "removed" to 4), mapOf("reset" to 0)))
        assertEquals(emptySet<String>(), qualifiedPlayIncreases(mapOf("removed" to 4), emptyMap()))
    }
}
