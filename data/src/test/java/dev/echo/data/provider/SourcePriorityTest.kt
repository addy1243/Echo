package dev.echo.data.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourcePriorityTest {
    @Test fun upgradeAddsYouTubeWithoutReenablingDisabledSources() {
        assertEquals("local,youtube", upgradedSourcePriority("local"))
        assertEquals("commons,local,youtube", upgradedSourcePriority("commons,local"))
        assertEquals("", upgradedSourcePriority(""))
        assertNull(upgradedSourcePriority(null))
    }

    @Test fun existingYouTubeOrderIsPreservedWithoutDuplicates() {
        assertEquals("youtube,local", upgradedSourcePriority("youtube,local"))
    }
}
